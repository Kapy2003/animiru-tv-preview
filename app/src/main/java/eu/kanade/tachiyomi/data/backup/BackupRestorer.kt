package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionPreferences
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.util.BackupUtil
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.CustomAnimeInfo
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.track.anime.model.AnimeTrack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
) {

    private var backupManager = BackupManager(context)

    private var restoreAmount = 0
    private var restoreProgress = 0

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()
    private var animeSourceMapping: Map<Long, String> = emptyMap()

    private val errors = mutableListOf<Pair<Date, String>>()

    suspend fun restoreBackup(uri: Uri): Boolean {
        val startTime = System.currentTimeMillis()
        restoreProgress = 0
        errors.clear()

        if (!performRestore(uri)) {
            return false
        }

        val endTime = System.currentTimeMillis()
        val time = endTime - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(time, errors.size, logFile.parent, logFile.name)
        return true
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("animiru_restore.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }

    private suspend fun performRestore(uri: Uri): Boolean {
        val backup = BackupUtil.decodeBackup(context, uri)

        restoreAmount = backup.backupAnime.size + 2 // +2 for categories

        // Restore categories

        if (backup.backupAnimeCategories.isNotEmpty()) {
            restoreAnimeCategories(backup.backupAnimeCategories)
        }

        // Store source mapping for error messages

        val backupAnimeMaps = backup.backupBrokenAnimeSources.map { BackupAnimeSource(it.name, it.sourceId) } + backup.backupAnimeSources
        animeSourceMapping = backupAnimeMaps.associate { it.sourceId to it.name }

        return coroutineScope {
            // Restore individual anime

            backup.backupAnime.forEach {
                if (!isActive) {
                    return@coroutineScope false
                }

                restoreAnime(it, backup.backupAnimeCategories)
            }

            if (backup.backupPreferences.isNotEmpty()) {
                restorePreferences(
                    backup.backupPreferences,
                    PreferenceManager.getDefaultSharedPreferences(context),
                )
            }

            if (backup.backupExtensionPreferences.isNotEmpty()) {
                restoreExtensionPreferences(backup.backupExtensionPreferences)
            }

            if (backup.backupExtensions.isNotEmpty()) {
                restoreExtensions(backup.backupExtensions)
            }

            // TODO: optionally trigger online library + tracker update
            true
        }
    }

    private suspend fun restoreAnimeCategories(backupCategories: List<BackupCategory>) {
        backupManager.restoreAnimeCategories(backupCategories)

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, context.getString(R.string.anime_categories))
    }

    private suspend fun restoreAnime(backupAnime: BackupAnime, backupCategories: List<BackupCategory>) {
        val anime = backupAnime.getAnimeImpl()
        val episodes = backupAnime.getEpisodesImpl()
        val categories = backupAnime.categories.map { it.toInt() }
        val history =
            backupAnime.brokenHistory.map { BackupAnimeHistory(it.url, it.lastSeen) } + backupAnime.history
        val tracks = backupAnime.getTrackingImpl()
        // AM (CU) -->
        val customAnime = backupAnime.getCustomAnimeInfo()
        // <-- AM (CU)

        try {
            val dbAnime = backupManager.getAnimeFromDatabase(anime.url, anime.source)
            if (dbAnime == null) {
                // Anime not in database
                // AM (CU)>
                restoreExistingAnime(anime, episodes, categories, history, tracks, backupCategories, customAnime)
            } else {
                // Anime in database
                // Copy information from anime already in database
                val updatedAnime = backupManager.restoreExistingAnime(anime, dbAnime)
                // Fetch rest of anime information
                // AM (CU)>
                restoreNewAnime(updatedAnime, episodes, categories, history, tracks, backupCategories, customAnime)
            }
        } catch (e: Exception) {
            val sourceName = sourceMapping[anime.source] ?: anime.source.toString()
            errors.add(Date() to "${anime.title} [$sourceName]: ${e.message}")
        }

        restoreProgress += 1
        showRestoreProgress(restoreProgress, restoreAmount, anime.title)
    }

    /**
     * Fetches anime information
     *
     * @param anime anime that needs updating
     * @param episodes episodes of anime that needs updating
     * @param categories categories that need updating
     */
    private suspend fun restoreExistingAnime(
        anime: Anime,
        episodes: List<Episode>,
        categories: List<Int>,
        history: List<BackupAnimeHistory>,
        tracks: List<AnimeTrack>,
        backupCategories: List<BackupCategory>,
        // AM (CU) -->
        customAnime: CustomAnimeInfo?,
        // <-- AM (CU)
    ) {
        val fetchedAnime = backupManager.restoreNewAnime(anime)
        backupManager.restoreEpisodes(fetchedAnime, episodes)
        // AM (CU)>
        restoreExtras(fetchedAnime, categories, history, tracks, backupCategories, customAnime)
    }

    private suspend fun restoreNewAnime(
        backupAnime: Anime,
        episodes: List<Episode>,
        categories: List<Int>,
        history: List<BackupAnimeHistory>,
        tracks: List<AnimeTrack>,
        backupCategories: List<BackupCategory>,
        // AM (CU) -->
        customAnime: CustomAnimeInfo?,
        // <-- AM (CU)
    ) {
        backupManager.restoreEpisodes(backupAnime, episodes)
        // AM (CU)>
        restoreExtras(backupAnime, categories, history, tracks, backupCategories, customAnime)
    }

    private suspend fun restoreExtras(
        anime: Anime,
        categories: List<Int>,
        history: List<BackupAnimeHistory>,
        tracks: List<AnimeTrack>,
        backupCategories: List<BackupCategory>,
        // AM (CU) -->
        customAnime: CustomAnimeInfo?,
        // <-- AM (CU)
    ) {
        backupManager.restoreAnimeCategories(anime, categories, backupCategories)
        backupManager.restoreAnimeHistory(history)
        backupManager.restoreAnimeTracking(anime, tracks)
        // AM (CU) -->
        backupManager.restoreEditedInfo(customAnime?.copy(id = anime.id))
        // <-- AM (CU)
    }

    private fun restorePreferences(preferences: List<BackupPreference>, sharedPrefs: SharedPreferences) {
        preferences.forEach { pref ->
            when (pref.value) {
                is IntPreferenceValue -> {
                    if (sharedPrefs.all[pref.key] is Int?) {
                        sharedPrefs.edit().putInt(pref.key, pref.value.value).apply()
                    }
                }
                is LongPreferenceValue -> {
                    if (sharedPrefs.all[pref.key] is Long?) {
                        sharedPrefs.edit().putLong(pref.key, pref.value.value).apply()
                    }
                }
                is FloatPreferenceValue -> {
                    if (sharedPrefs.all[pref.key] is Float?) {
                        sharedPrefs.edit().putFloat(pref.key, pref.value.value).apply()
                    }
                }
                is StringPreferenceValue -> {
                    if (sharedPrefs.all[pref.key] is String?) {
                        sharedPrefs.edit().putString(pref.key, pref.value.value).apply()
                    }
                }
                is BooleanPreferenceValue -> {
                    if (sharedPrefs.all[pref.key] is Boolean?) {
                        sharedPrefs.edit().putBoolean(pref.key, pref.value.value).apply()
                    }
                }
                is StringSetPreferenceValue -> {
                    if (sharedPrefs.all[pref.key] is Set<*>?) {
                        sharedPrefs.edit().putStringSet(pref.key, pref.value.value).apply()
                    }
                }
            }
        }
    }

    private fun restoreExtensionPreferences(prefs: List<BackupExtensionPreferences>) {
        prefs.forEach {
            val sharedPrefs = context.getSharedPreferences(it.name, 0x0)
            restorePreferences(it.prefs, sharedPrefs)
        }
    }

    private fun restoreExtensions(extensions: List<BackupExtension>) {
        extensions.forEach {
            if (context.packageManager.getInstalledPackages(0).none { pkg -> pkg.packageName == it.pkgName }) {
                logcat { it.pkgName }
                // save apk in files dir and open installer dialog
                val file = File(context.cacheDir, "${it.pkgName}.apk")
                file.writeBytes(it.apk)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(file.getUriCompat(context), "application/vnd.android.package-archive")
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            }
        }
    }

    /**
     * Called to update dialog in [BackupConst]
     *
     * @param progress restore progress
     * @param amount total restoreAmount of anime and manga
     * @param title title of restored anime and manga
     */
    private fun showRestoreProgress(progress: Int, amount: Int, title: String) {
        notifier.showRestoreProgress(title, progress, amount)
    }
}
