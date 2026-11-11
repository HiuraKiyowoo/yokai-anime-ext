package eu.kanade.tachiyomi.extension.id.anichin

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class AnichinFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Anichin(),
    )
}
