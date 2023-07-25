package tachiyomi.source.local.io

import java.io.File

object ArchiveAnime {

    private val SUPPORTED_ARCHIVE_TYPES = listOf("mp4", "mkv")

    fun isSupported(file: File): Boolean = with(file) {
        return extension.lowercase() in SUPPORTED_ARCHIVE_TYPES
    }
}
