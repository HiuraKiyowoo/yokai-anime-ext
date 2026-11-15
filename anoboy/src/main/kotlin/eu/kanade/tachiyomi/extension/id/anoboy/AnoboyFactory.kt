package eu.kanade.tachiyomi.extension.id.anoboy

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class AnoboyFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Anoboy(),
    )
}