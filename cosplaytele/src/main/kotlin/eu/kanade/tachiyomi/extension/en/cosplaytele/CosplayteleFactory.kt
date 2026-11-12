package eu.kanade.tachiyomi.extension.en.cosplaytele

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class CosplayteleFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Cosplaytele(),
    )
}
