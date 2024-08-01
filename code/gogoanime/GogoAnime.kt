package extensions.en.gogoanime


import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.gogostreamextractor.GogoStreamExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.prefs.Preferences

class GogoAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gogoanime"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://anitaku.to"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val preferences = Preferences.userRoot()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular.html?page=$page", headers)

    override fun popularAnimeSelector(): String = "div.img a"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.attr("title")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home.html?page=$page", headers)
    override fun episodeVideoParse(response: Response): SEpisode {
        throw NotImplementedError("No episodeVideoParse implemented")
    }

    override fun latestUpdatesSelector(): String = "div.img a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.attr("title")
        val slug = element.attr("href").substringAfter(baseUrl)
            .trimStart('/')
            .substringBefore("-episode-")
        setUrlWithoutDomain("/category/$slug")
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = GogoAnimeFilters.getSearchParameters(filters)

        return when {
            params.genre.isNotEmpty() -> GET("$baseUrl/genre/${params.genre}?page=$page", headers)
            params.recent.isNotEmpty() -> GET("$AJAX_URL/page-recent-release.html?page=$page&type=${params.recent}", headers)
            params.season.isNotEmpty() -> GET("$baseUrl/${params.season}?page=$page", headers)
            else -> GET("$baseUrl/filter.html?keyword=$query&${params.filter}&page=$page", headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = GogoAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val infoDocument = document.selectFirst("div.anime-info a[href]")?.let {
            client.newCall(GET(it.absUrl("href"), headers)).execute().asJsoup()
        } ?: document

        return SAnime.create().apply {
            title = infoDocument.selectFirst("div.anime_info_body_bg > h1")!!.text()
            genre = infoDocument.getInfo("Genre:")
            status = parseStatus(infoDocument.getInfo("Status:").orEmpty())

            description = buildString {
                val summary = infoDocument.selectFirst("div.anime_info_body_bg > div.description")
                append(summary?.text())

                // add alternative name to anime description
                infoDocument.getInfo("Other name:")?.also {
                    if (isNotBlank()) append("\n\n")
                    append("Other name(s): $it")
                }
            }
        }
    }

    // ============================== Episodes ==============================
    private fun episodesRequest(totalEpisodes: String, id: String): List<SEpisode> {
        val request = GET("$AJAX_URL/load-list-episode?ep_start=0&ep_end=$totalEpisodes&id=$id", headers)
        val epResponse = client.newCall(request).execute()
        val document = epResponse.asJsoup()
        return document.select("a").map(::episodeFromElement)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val totalEpisodes = document.select(episodeListSelector()).last()!!.attr("ep_end")
        val id = document.select("input#movie_id").attr("value")
        return episodesRequest(totalEpisodes, id)
    }

    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val ep = element.selectFirst("div.name")!!.ownText().substringAfter(" ")
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            episode_number = ep.toFloat()
            name = "Episode $ep"
        }
    }

    // ============================ Video Links =============================
    private val gogoExtractor by lazy { GogoStreamExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val hosterSelection = preferences.get(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).split(",").toSet()

        return document.select("div.anime_muti_link > ul > li").parallelCatchingFlatMapBlocking { server ->
            val className = server.className()
            if (!hosterSelection.contains(className)) return@parallelCatchingFlatMapBlocking emptyList()
            val serverUrl = server.selectFirst("a")
                ?.attr("abs:data-video")
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            getHosterVideos(className, serverUrl)
        }
//        return document.select("div.anime_muti_link > ul > li")
//            .mapNotNull { server ->
//                val className = server.className()
//                if (!hosterSelection.contains(className)) return@mapNotNull null
//                val serverUrl = server.selectFirst("a")?.attr("abs:data-video") ?: return@mapNotNull null
//                className to serverUrl
//            }
//            .flatMap { (className, serverUrl) ->
//                try {
//                    getHosterVideos(className, serverUrl)
//                } catch (e: Exception) {
//                    emptyList<Video>() // Handle exception by returning an empty list
//                }
//            }
    }

    private fun getHosterVideos(className: String, serverUrl: String): List<Video> {
        return when (className) {
            "anime", "vidcdn" -> gogoExtractor.videosFromUrl(serverUrl)
            "streamwish" -> streamwishExtractor.videosFromUrl(serverUrl)
            "doodstream" -> doodExtractor.videosFromUrl(serverUrl)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(serverUrl, headers)
            "filelions" -> {
                streamwishExtractor.videosFromUrl(serverUrl, videoNameGen = { quality -> "FileLions - $quality" })
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    private fun Document.getInfo(text: String): String? {
        val base = selectFirst("p.type:has(span:containsOwn($text))") ?: return null
        return base.select("a").eachText().joinToString("")
            .ifBlank { base.ownText() }
            .takeUnless(String::isBlank)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.get(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.get(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private const val AJAX_URL = "https://ajax.gogocdn.net/ajax"

        private val HOSTERS = arrayOf(
            "Gogostream",
            "Vidstreaming",
            "Doodstream",
            "StreamWish",
            "Mp4upload",
            "FileLions",
        )
        private val HOSTERS_NAMES = arrayOf(
            // Names that appears in the gogo html
            "gogostream",
            "vidcdn",
            "anime",
            "doodstream",
            "streamwish",
            "mp4upload",
            "filelions",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "Gogostream"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_HOSTER_TITLE = "Enable/Disable Hosts"
        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.joinToString(",")
    }
    override fun setupPreferenceScreen(screen: Preferences) {
        preferences.put(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        preferences.put(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
        preferences.put(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)
    }

    // ============================== Settings ==============================
//    override fun setupPreferenceScreen(screen: PreferenceScreen) {
//        ListPreference(screen.context).apply {
//            key = PREF_QUALITY_KEY
//            title = PREF_QUALITY_TITLE
//            entries = PREF_QUALITY_ENTRIES
//            entryValues = PREF_QUALITY_VALUES
//            setDefaultValue(PREF_QUALITY_DEFAULT)
//            summary = "%s"
//
//            setOnPreferenceChangeListener { _, newValue ->
//                val selected = newValue as String
//                val index = findIndexOfValue(selected)
//                val entry = entryValues[index] as String
//                preferences.edit().putString(key, entry).commit()
//            }
//        }.also(screen::addPreference)
//
//        ListPreference(screen.context).apply {
//            key = PREF_SERVER_KEY
//            title = PREF_SERVER_TITLE
//            entries = HOSTERS
//            entryValues = HOSTERS
//            setDefaultValue(PREF_SERVER_DEFAULT)
//            summary = "%s"
//
//            setOnPreferenceChangeListener { _, newValue ->
//                val selected = newValue as String
//                val index = findIndexOfValue(selected)
//                val entry = entryValues[index] as String
//                preferences.edit().putString(key, entry).commit()
//            }
//        }.also(screen::addPreference)
//
//        MultiSelectListPreference(screen.context).apply {
//            key = PREF_HOSTER_KEY
//            title = PREF_HOSTER_TITLE
//            entries = HOSTERS
//            entryValues = HOSTERS_NAMES
//            setDefaultValue(PREF_HOSTER_DEFAULT)
//
//            setOnPreferenceChangeListener { _, newValue ->
//                @Suppress("UNCHECKED_CAST")
//                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
//            }
//        }.also(screen::addPreference)
//    }
}
