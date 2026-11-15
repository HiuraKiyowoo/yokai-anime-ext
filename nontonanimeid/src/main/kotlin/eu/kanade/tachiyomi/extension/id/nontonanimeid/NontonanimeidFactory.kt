package eu.kanade.tachiyomi.extension.id.nontonanimeid

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class NontonanimeidFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Nontonanimeid(),
    )
}
