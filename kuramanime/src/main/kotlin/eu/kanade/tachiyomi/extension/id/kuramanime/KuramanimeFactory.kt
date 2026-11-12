package eu.kanade.tachiyomi.extension.id.kuramanime

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class KuramanimeFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Kuramanime(),
    )
}
