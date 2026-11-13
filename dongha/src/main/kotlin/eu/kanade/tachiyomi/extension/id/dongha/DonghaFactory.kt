package eu.kanade.tachiyomi.extension.id.dongha

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class DonghaFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Dongha(),
    )
}