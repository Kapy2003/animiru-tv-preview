package eu.kanade.tachiyomi.data.track

import android.content.Context
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.simkl.Simkl

class TrackManager(context: Context) {

    companion object {
        const val MYANIMELIST = 1L
        const val ANILIST = 2L
        const val KITSU = 3L
        const val SHIKIMORI = 4L
        const val BANGUMI = 5L
        const val SIMKL = 101L
    }

    val myAnimeList = MyAnimeList(MYANIMELIST)
    val aniList = Anilist(ANILIST)
    val kitsu = Kitsu(KITSU)
    val shikimori = Shikimori(SHIKIMORI)
    val bangumi = Bangumi(BANGUMI)
    val simkl = Simkl(SIMKL)

    val services: List<TrackService> = listOf(myAnimeList, aniList, kitsu, shikimori, bangumi, simkl)

    fun getService(id: Long) = services.find { it.id == id }

    fun hasLoggedServices() = services.any { it.isLogged }
}
