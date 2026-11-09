package eu.kanade.tachiyomi.extension.id.wibutime

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

/**
 * Titik masuk yang dibaca ExtensionLoader lewat meta-data
 * `tachiyomi.extension.factory`.
 *
 * Provider anime baru = kelas baru di sini (atau modul ekstensi baru).
 * APK utama tidak perlu dibangun ulang.
 */
class WibutimeFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Wibutime(),
    )
}
