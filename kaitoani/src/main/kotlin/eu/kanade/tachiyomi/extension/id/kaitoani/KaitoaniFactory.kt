package eu.kanade.tachiyomi.extension.id.kaitoani

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class KaitoaniFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Kaitoani(),
    )
}
