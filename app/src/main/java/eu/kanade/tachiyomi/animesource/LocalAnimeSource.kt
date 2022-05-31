package eu.kanade.tachiyomi.animesource

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.toAnimeInfo
import eu.kanade.tachiyomi.animesource.model.toEpisodeInfo
import eu.kanade.tachiyomi.animesource.model.toSAnime
import eu.kanade.tachiyomi.animesource.model.toSEpisode
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.util.episode.EpisodeRecognition
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import rx.Observable
import tachiyomi.animesource.model.AnimeInfo
import tachiyomi.animesource.model.EpisodeInfo
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

class LocalAnimeSource(
    private val context: Context,
    private val coverCache: AnimeCoverCache = Injekt.get(),
) : AnimeCatalogueSource, UnmeteredSource {

    private val json: Json by injectLazy()

    override val name = context.getString(R.string.local_anime_source)

    override val id: Long = ID

    override val lang = "other"

    override fun toString() = name

    override val supportsLatest = true

    // Browse related
    override fun fetchPopularAnime(page: Int) = fetchSearchAnime(page, "", POPULAR_FILTERS)

    override fun fetchLatestUpdates(page: Int) = fetchSearchAnime(page, "", LATEST_FILTERS)

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val baseDirsFiles = getBaseDirectoriesFiles(context)

        var animeDirs = baseDirsFiles
            // Filter out files that are hidden and is not a folder
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .distinctBy { it.name }

        val lastModifiedLimit = if (filters === LATEST_FILTERS) System.currentTimeMillis() - LATEST_THRESHOLD else 0L
        // Filter by query or last modified
        animeDirs = animeDirs.filter {
            if (lastModifiedLimit == 0L) {
                it.name.contains(query, ignoreCase = true)
            } else {
                it.lastModified() >= lastModifiedLimit
            }
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy -> {
                    when (filter.state!!.index) {
                        0 -> {
                            animeDirs = if (filter.state!!.ascending) {
                                animeDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                            } else {
                                animeDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
                            }
                        }
                        1 -> {
                            animeDirs = if (filter.state!!.ascending) {
                                animeDirs.sortedBy(File::lastModified)
                            } else {
                                animeDirs.sortedByDescending(File::lastModified)
                            }
                        }
                    }
                }

                else -> { /* Do nothing */ }
            }
        }

        // Transform animeDirs to list of SAnime
        val animes = animeDirs.map { animeDir ->
            SAnime.create().apply {
                title = animeDir.name
                url = animeDir.name

                // Try to find the cover
                val cover = getCoverFile(animeDir.name, baseDirsFiles)
                if (cover != null && cover.exists()) {
                    thumbnail_url = cover.absolutePath
                }
            }
        }

        // Fetch episodes of all the anime
        animes.forEach { anime ->
            val animeInfo = anime.toAnimeInfo()
            runBlocking {
                val episodes = getEpisodeList(animeInfo)
                if (episodes.isNotEmpty()) {
                    val episode = episodes.last().toSEpisode()
                    // Copy the cover from the first episode found if not available
                    if (anime.thumbnail_url == null) {
                        updateCoverFromVideo(episode, anime)
                    }
                }
            }
        }

        return Observable.just(AnimesPage(animes.toList(), false))
    }

    fun updateAnimeInfo(anime: SAnime) {
        val directory = getBaseDirectories(context).map { File(it, anime.url) }.find {
            it.exists()
        } ?: return
        val existingFileName = directory.listFiles()?.find { it.extension == "json" }?.name
        val file = File(directory, existingFileName ?: "info.json")
        file.outputStream().use {
            json.encodeToStream(anime.toJson(), it)
        }
    }

    private fun SAnime.toJson(): AnimeJson {
        return AnimeJson(title, author, artist, description, genre?.split(", "), status)
    }

    @Serializable
    data class AnimeJson(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Int? = null,
    )

    override fun fetchLatestUpdates(page: Int) = fetchSearchAnime(page, "", LATEST_FILTERS)

    override suspend fun getAnimeDetails(anime: AnimeInfo): AnimeInfo {
        var animeInfo = anime

        val baseDirsFile = getBaseDirectoriesFiles(context)

        val coverFile = getCoverFile(anime.key, baseDirsFile)

        coverFile?.let {
            animeInfo = animeInfo.copy(cover = it.absolutePath)
        }

        val localDetails = getAnimeDirsFiles(anime.key, baseDirsFile)
            .firstOrNull { it.extension.equals("json", ignoreCase = true) }

        if (localDetails != null) {
            val obj = json.decodeFromStream<JsonObject>(localDetails.inputStream())

            animeInfo = animeInfo.copy(
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: anime.title,
                author = obj["author"]?.jsonPrimitive?.contentOrNull ?: anime.author,
                artist = obj["artist"]?.jsonPrimitive?.contentOrNull ?: anime.artist,
                description = obj["description"]?.jsonPrimitive?.contentOrNull ?: anime.description,
                genres = obj["genre"]?.jsonArray?.map { it.jsonPrimitive.content } ?: anime.genres,
                status = obj["status"]?.jsonPrimitive?.intOrNull ?: anime.status,
            )
        }

        return animeInfo
    }

    // Episodes
    override suspend fun getEpisodeList(anime: AnimeInfo): List<EpisodeInfo> {
        val sAnime = anime.toSAnime()

        val baseDirsFile = getBaseDirectoriesFiles(context)
        return getAnimeDirsFiles(anime.key, baseDirsFile)
            // Only keep supported formats
            .filter { it.isDirectory || isSupportedFile(it.extension) }
            .map { episodeFile ->
                SEpisode.create().apply {
                    url = episodeFile.absolutePath
                    name = if (episodeFile.isDirectory) {
                        episodeFile.name
                    } else {
                        episodeFile.nameWithoutExtension
                    }
                    date_upload = episodeFile.lastModified()

                    EpisodeRecognition.parseEpisodeNumber(this, sAnime)
                }
            }
            .map { it.toEpisodeInfo() }
            .sortedWith { e1, e2 ->
                val e = e2.number.compareTo(e1.number)
                if (e == 0) e2.name.compareToCaseInsensitiveNaturalOrder(e1.name) else e
            }
            .toList()
    }

    // Filters
    override fun getFilterList() = AnimeFilterList(OrderBy(context))

    private val POPULAR_FILTERS = AnimeFilterList(OrderBy(context))
    private val LATEST_FILTERS = AnimeFilterList(OrderBy(context).apply { state = AnimeFilter.Sort.Selection(1, false) })

    private class OrderBy(context: Context) : AnimeFilter.Sort(
        context.getString(R.string.local_filter_order_by),
        arrayOf(context.getString(R.string.title), context.getString(R.string.date)),
        Selection(0, true),
    )

    // Unused stuff
    override suspend fun getVideoList(episode: EpisodeInfo) = throw UnsupportedOperationException("Unused")

    // Miscellaneous
    private fun isSupportedFile(extension: String): Boolean {
        return extension.lowercase() in SUPPORTED_FILE_TYPES
    }

    private fun updateCoverFromVideo(episode: SEpisode, anime: SAnime) {
        val baseDirsFiles = getBaseDirectoriesFiles(context)
        val animeDir = getAnimeDir(anime.url, baseDirsFiles) ?: return
        val coverPath = "${animeDir.absolutePath}/$DEFAULT_COVER_NAME"

        val ffProbe = FFprobeKit.execute("-v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 '${episode.url}'")
        val duration = ffProbe.allLogsAsString.trim().toFloat()
        val second = duration.toInt() / 2
        FFmpegKit.execute("-ss $second -i '${episode.url}' -frames 1 -q:v 2 '$coverPath'")
        anime.thumbnail_url = coverPath
        coverCache.clearMemoryCache()
    }

    companion object {
        const val ID = 0L
        const val HELP_URL = "https://aniyomi.jmir.xyz/help/guides/local-anime/"

        private const val DEFAULT_COVER_NAME = "cover.jpg"
        private val LATEST_THRESHOLD = TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)

        private fun getBaseDirectories(context: Context): Sequence<File> {
            val localFolder = context.getString(R.string.app_name) + File.separator + "localanime"
            return DiskUtil.getExternalStorages(context)
                .map { File(it.absolutePath, localFolder) }
                .asSequence()
        }

        private fun getBaseDirectoriesFiles(context: Context): Sequence<File> {
            return getBaseDirectories(context)
                // Get all the files inside all baseDir
                .flatMap { it.listFiles().orEmpty().toList() }
        }

        private fun getAnimeDir(animeUrl: String, baseDirsFile: Sequence<File>): File? {
            return baseDirsFile
                // Get the first animeDir or null
                .firstOrNull { it.isDirectory && it.name == animeUrl }
        }

        private fun getAnimeDirsFiles(animeUrl: String, baseDirsFile: Sequence<File>): Sequence<File> {
            return baseDirsFile
                // Filter out ones that are not related to the anime and is not a directory
                .filter { it.isDirectory && it.name == animeUrl }
                // Get all the files inside the filtered folders
                .flatMap { it.listFiles().orEmpty().toList() }
        }

        private fun getCoverFile(animeUrl: String, baseDirsFile: Sequence<File>): File? {
            return getAnimeDirsFiles(animeUrl, baseDirsFile)
                // Get all file whose names start with 'cover'
                .filter { it.isFile && it.nameWithoutExtension.equals("cover", ignoreCase = true) }
                // Get the first actual image
                .firstOrNull {
                    ImageUtil.isImage(it.name) { it.inputStream() }
                }
        }

        fun updateCover(context: Context, anime: SAnime, inputStream: InputStream): File? {
            val baseDirsFiles = getBaseDirectoriesFiles(context)

            val animeDir = getAnimeDir(anime.url, baseDirsFiles)
            if (animeDir == null) {
                inputStream.close()
                return null
            }

            var coverFile = getCoverFile(anime.url, baseDirsFiles)
            if (coverFile == null) {
                coverFile = File(animeDir.absolutePath, DEFAULT_COVER_NAME)
            }

            // It might not exist at this point
            coverFile.parentFile?.mkdirs()
            inputStream.use { input ->
                coverFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Create a .nomedia file
            DiskUtil.createNoMediaFile(UniFile.fromFile(animeDir), context)

            anime.thumbnail_url = coverFile.absolutePath
            return coverFile
        }
    }
}

private val SUPPORTED_FILE_TYPES = listOf("mp4", "mkv")
