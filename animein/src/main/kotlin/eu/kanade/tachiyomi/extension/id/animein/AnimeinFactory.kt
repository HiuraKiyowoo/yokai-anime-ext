package eu.kanade.tachiyomi.extension.id.animein

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class AnimeinFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Animein(),
    )
}
