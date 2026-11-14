package eu.kanade.tachiyomi.extension.id.layaranime

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class LayaranimeFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Layaranime(),
    )
}
