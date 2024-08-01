package extensions.en.kaido

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import okhttp3.Response

class Kaido : ZoroTheme(
    "en",
    "Kaido",
    "https://kaido.to",
    hosterNames = listOf(
        "StreamTape",
        "Vidcloud",
        "Vidstreaming",
    ),
) {
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, preferences) }

    override fun extractVideo(server: VideoData?): List<Video> {
        if (server == null){
            return emptyList()
        }
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(server.link, "Streamtape - ${server.type}")
                    ?.let(::listOf)
                    ?: emptyList()
            }
            "Vidstreaming", "Vidcloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }

    override fun episodeVideoParse(response: Response): SEpisode {
        TODO("Not yet implemented")
    }
}
