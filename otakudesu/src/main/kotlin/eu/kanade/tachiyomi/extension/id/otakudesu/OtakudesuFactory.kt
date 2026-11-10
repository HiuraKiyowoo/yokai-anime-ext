package eu.kanade.tachiyomi.extension.id.otakudesu

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class OtakudesuFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Otakudesu(),
    )
}