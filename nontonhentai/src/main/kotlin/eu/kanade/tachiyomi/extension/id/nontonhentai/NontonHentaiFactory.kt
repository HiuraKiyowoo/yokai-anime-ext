package eu.kanade.tachiyomi.extension.id.nontonhentai

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class NontonHentaiFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        NontonHentai(),
    )
}