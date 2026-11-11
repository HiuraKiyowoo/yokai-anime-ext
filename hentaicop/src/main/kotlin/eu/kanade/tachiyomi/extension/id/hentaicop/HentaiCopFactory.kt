package eu.kanade.tachiyomi.extension.id.hentaicop

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class HentaiCopFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        HentaiCop(),
    )
}
