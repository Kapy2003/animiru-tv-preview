package eu.kanade.tachiyomi.data.database.queries

import com.pushtorefresh.storio.Queries
import com.pushtorefresh.storio.sqlite.queries.DeleteQuery
import eu.kanade.tachiyomi.data.database.AnimeDbProvider
import eu.kanade.tachiyomi.data.database.inTransaction
import eu.kanade.tachiyomi.data.database.models.Anime
import eu.kanade.tachiyomi.data.database.models.AnimeCategory
import eu.kanade.tachiyomi.data.database.tables.AnimeCategoryTable

interface AnimeCategoryQueries : AnimeDbProvider {

    fun insertAnimesCategories(animesCategories: List<AnimeCategory>) = db.put().objects(animesCategories).prepare()

    fun deleteOldAnimesCategories(animes: List<Anime>) = db.delete()
        .byQuery(
            DeleteQuery.builder()
                .table(AnimeCategoryTable.TABLE)
                .where("${AnimeCategoryTable.COL_ANIME_ID} IN (${Queries.placeholders(animes.size)})")
                .whereArgs(*animes.map { it.id }.toTypedArray())
                .build(),
        )
        .prepare()

    fun setAnimeCategories(animesCategories: List<AnimeCategory>, animes: List<Anime>) {
        db.inTransaction {
            deleteOldAnimesCategories(animes).executeAsBlocking()
            insertAnimesCategories(animesCategories).executeAsBlocking()
        }
    }
}
