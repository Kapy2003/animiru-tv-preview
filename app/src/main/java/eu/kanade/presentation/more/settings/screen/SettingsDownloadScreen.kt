package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastMap
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.category.anime.interactor.GetAnimeCategories
import eu.kanade.domain.category.model.Category
import eu.kanade.domain.download.service.DownloadPreferences
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import eu.kanade.presentation.util.collectAsState
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

object SettingsDownloadScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    @StringRes
    override fun getTitleRes() = R.string.pref_category_downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val getAnimeCategories = remember { Injekt.get<GetAnimeCategories>() }
        val allAnimeCategories by getAnimeCategories.subscribe().collectAsState(initial = runBlocking { getAnimeCategories.await() })

        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
        val basePreferences = remember { Injekt.get<BasePreferences>() }

        return listOf(
            getDownloadLocationPreference(downloadPreferences = downloadPreferences),
            Preference.PreferenceItem.SwitchPreference(
                pref = downloadPreferences.downloadOnlyOverWifi(),
                title = stringResource(R.string.connected_to_wifi),
            ),
            // AM (DS) -->
            Preference.PreferenceItem.SwitchPreference(
                pref = downloadPreferences.showDownloadedEpisodeSize(),
                title = stringResource(R.string.pref_show_downloaded_episode_size),
            ),
            // <-- AM (DS)
            getDeleteEpisodesGroup(
                downloadPreferences = downloadPreferences,
                categories = allAnimeCategories,
            ),
            getAutoDownloadGroup(
                downloadPreferences = downloadPreferences,
                allAnimeCategories = allAnimeCategories,
            ),
            getDownloadAheadGroup(downloadPreferences = downloadPreferences),
            getExternalDownloaderGroup(downloadPreferences = downloadPreferences, basePreferences = basePreferences),
        )
    }

    @Composable
    private fun getDownloadLocationPreference(
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceItem.ListPreference<String> {
        val context = LocalContext.current
        val currentDirPref = downloadPreferences.downloadsDirectory()
        val currentDir by currentDirPref.collectAsState()

        val pickLocation = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                context.contentResolver.takePersistableUriPermission(uri, flags)

                val file = UniFile.fromUri(context, uri)
                currentDirPref.set(file.uri.toString())
            }
        }

        val defaultDirPair = rememberDefaultDownloadDir()
        val externalDownloaderDirPair = rememberExternalDownloaderDownloadDir()
        val customDirEntryKey = currentDir.takeIf { it != defaultDirPair.first } ?: "custom"

        return Preference.PreferenceItem.ListPreference(
            pref = currentDirPref,
            title = stringResource(R.string.pref_download_directory),
            subtitleProvider = { value, _ ->
                remember(value) {
                    UniFile.fromUri(context, value.toUri())?.filePath
                } ?: stringResource(R.string.invalid_location, value)
            },
            entries = mapOf(
                defaultDirPair,
                externalDownloaderDirPair,
                customDirEntryKey to stringResource(R.string.custom_dir),
            ),
            onValueChanged = {
                val default = it == defaultDirPair.first
                if (!default) {
                    pickLocation.launch(null)
                }
                default // Don't update when non-default chosen
            },
        )
    }

    @Composable
    private fun rememberDefaultDownloadDir(): Pair<String, String> {
        val appName = stringResource(R.string.app_name)
        return remember {
            val file = UniFile.fromFile(
                File(
                    "${Environment.getExternalStorageDirectory().absolutePath}${File.separator}$appName",
                    "downloads",
                ),
            )!!
            file.uri.toString() to file.filePath!!
        }
    }

    @Composable
    private fun rememberExternalDownloaderDownloadDir(): Pair<String, String> {
        val appName = stringResource(R.string.app_name)
        return remember {
            val file = UniFile.fromFile(
                File(
                    "${Environment.getExternalStorageDirectory().absolutePath}${File.separator}${Environment.DIRECTORY_DOWNLOADS}${File.separator}$appName",
                    "downloads",
                ),
            )!!
            "(ADM)" + file.uri.toString() to file.filePath!!
        }
    }

    @Composable
    private fun getDeleteEpisodesGroup(
        downloadPreferences: DownloadPreferences,
        categories: List<Category>,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(R.string.pref_category_delete_episodes),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = downloadPreferences.removeAfterMarkedAsSeen(),
                    title = stringResource(R.string.pref_remove_after_marked_as_seen),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = downloadPreferences.removeAfterSeenSlots(),
                    title = stringResource(R.string.pref_remove_after_seen),
                    entries = mapOf(
                        -1 to stringResource(R.string.disabled),
                        0 to stringResource(R.string.last_read_episode),
                        1 to stringResource(R.string.second_to_last_episode),
                        2 to stringResource(R.string.third_to_last_episode),
                        3 to stringResource(R.string.fourth_to_last_episode),
                        4 to stringResource(R.string.fifth_to_last_episode),
                    ),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = downloadPreferences.removeBookmarkedEpisodes(),
                    title = stringResource(R.string.pref_remove_bookmarked_episodes),
                ),
                getExcludedCategoriesPreference(
                    downloadPreferences = downloadPreferences,
                    categories = { categories },
                ),
            ),
        )
    }

    @Composable
    private fun getExcludedCategoriesPreference(
        downloadPreferences: DownloadPreferences,
        categories: () -> List<Category>,
    ): Preference.PreferenceItem.MultiSelectListPreference {
        return Preference.PreferenceItem.MultiSelectListPreference(
            pref = downloadPreferences.removeExcludeAnimeCategories(),
            title = stringResource(R.string.pref_remove_exclude_categories),
            entries = categories().associate { it.id.toString() to it.visualName },
        )
    }

    @Composable
    private fun getAutoDownloadGroup(
        downloadPreferences: DownloadPreferences,
        allAnimeCategories: List<Category>,
    ): Preference.PreferenceGroup {
        val downloadNewEpisodesPref = downloadPreferences.downloadNewEpisodes()
        val downloadNewEpisodeCategoriesPref = downloadPreferences.downloadNewEpisodeCategories()
        val downloadNewEpisodeCategoriesExcludePref = downloadPreferences.downloadNewEpisodeCategoriesExclude()

        val downloadNewEpisodes by downloadNewEpisodesPref.collectAsState()

        val includedAnime by downloadNewEpisodeCategoriesPref.collectAsState()
        val excludedAnime by downloadNewEpisodeCategoriesExcludePref.collectAsState()
        var showAnimeDialog by rememberSaveable { mutableStateOf(false) }
        if (showAnimeDialog) {
            TriStateListDialog(
                title = stringResource(R.string.anime_categories),
                message = stringResource(R.string.pref_download_new_categories_details),
                items = allAnimeCategories,
                initialChecked = includedAnime.mapNotNull { id -> allAnimeCategories.find { it.id.toString() == id } },
                initialInversed = excludedAnime.mapNotNull { id -> allAnimeCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showAnimeDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    downloadNewEpisodeCategoriesPref.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    downloadNewEpisodeCategoriesExcludePref.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showAnimeDialog = false
                },
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(R.string.pref_category_auto_download),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = downloadNewEpisodesPref,
                    title = stringResource(R.string.pref_download_new_episodes),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(R.string.anime_categories),
                    subtitle = getCategoriesLabel(
                        allCategories = allAnimeCategories,
                        included = includedAnime,
                        excluded = excludedAnime,
                    ),
                    onClick = { showAnimeDialog = true },
                    enabled = downloadNewEpisodes,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = downloadPreferences.notDownloadFillermarkedItems(),
                    title = stringResource(R.string.pref_not_download_fillermarked_items),
                ),
            ),
        )
    }

    @Composable
    private fun getDownloadAheadGroup(
        downloadPreferences: DownloadPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(R.string.download_ahead),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    pref = downloadPreferences.autoDownloadWhileWatching(),
                    title = stringResource(R.string.auto_download_while_watching),
                    entries = listOf(0, 2, 3, 5, 10).associateWith {
                        if (it == 0) {
                            stringResource(R.string.disabled)
                        } else {
                            pluralStringResource(id = R.plurals.next_unseen_episodes, count = it, it)
                        }
                    },
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(R.string.download_ahead_info)),
            ),
        )
    }

    @Composable
    private fun getExternalDownloaderGroup(downloadPreferences: DownloadPreferences, basePreferences: BasePreferences): Preference.PreferenceGroup {
        val useExternalDownloader = downloadPreferences.useExternalDownloader()
        val externalDownloaderPreference = downloadPreferences.externalDownloaderSelection()

        val pm = basePreferences.context.packageManager
        val installedPackages = pm.getInstalledPackages(0)
        val supportedDownloaders = installedPackages.filter {
            when (it.packageName) {
                "idm.internet.download.manager" -> true
                "idm.internet.download.manager.plus" -> true
                "idm.internet.download.manager.lite" -> true
                "com.dv.adm" -> true
                else -> false
            }
        }
        val packageNames = supportedDownloaders.map { it.packageName }
        val packageNamesReadable = supportedDownloaders
            .map { pm.getApplicationLabel(it.applicationInfo).toString() }

        val packageNamesMap: Map<String, String> =
            packageNames.zip(packageNamesReadable)
                .toMap()

        return Preference.PreferenceGroup(
            title = stringResource(R.string.pref_category_external_downloader),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = useExternalDownloader,
                    title = stringResource(R.string.pref_use_external_downloader),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = externalDownloaderPreference,
                    title = stringResource(R.string.pref_external_downloader_selection),
                    entries = mapOf("" to "None") + packageNamesMap,
                ),
            ),
        )
    }
}
