package eu.kanade.tachiyomi.extension.id.samehadaku

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class SamehadakuFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Samehadaku(),
    )
}