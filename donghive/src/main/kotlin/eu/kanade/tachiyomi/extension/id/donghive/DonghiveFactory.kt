package eu.kanade.tachiyomi.extension.id.donghive

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class DonghiveFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Donghive(),
    )
}
