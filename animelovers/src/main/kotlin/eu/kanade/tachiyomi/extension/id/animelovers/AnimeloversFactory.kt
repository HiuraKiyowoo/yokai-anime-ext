package eu.kanade.tachiyomi.extension.id.animelovers

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class AnimeloversFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Animelovers(),
    )
}
