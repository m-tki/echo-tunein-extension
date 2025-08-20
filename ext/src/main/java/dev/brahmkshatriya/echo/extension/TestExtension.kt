package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonResponse(
    val body: List<JsonObject>
)

@Serializable
data class MediaItem(
    val type: String? = null,
    val text: String? = null,
    @SerialName("URL")
    val url: String? = null,
    val bitrate: String? = null,
    @SerialName("guide_id")
    val id: String? = null,
    val subtext: String? = null,
    val key: String? = null,
    val item: String? = null,
    val formats: String? = null,
    val image: String? = null,
    @SerialName("current_track")
    val currentTrack: String? = null,
    val children: List<MediaItem>? = null
)

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, RadioClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val apiLink = "https://opml.radiotime.com"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder().url(url).build()
        ).await().body.string()
    }

    private val json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    private fun parseMediaItem(jsonObject: JsonObject): MediaItem {
        return json.decodeFromJsonElement(MediaItem.serializer(), jsonObject)
    }

    private fun processMediaItem(item: MediaItem): List<Shelf> {
        return when (item.type) {
            "audio" -> listOf(createAudioShelf(item).toShelf())
            "link" -> listOf(createLinkShelf(item))
            else -> {
                item.children?.flatMap { processMediaItem(it) } ?: emptyList()
            }
        }
    }

    private fun createAudioShelf(item: MediaItem): Track {
        return Track(
            id = item.id ?: "",
            title = item.text ?: "",
            subtitle = item.subtext,
            description = item.currentTrack,
            cover = item.image?.toImageHolder(),
            streamables = listOf(
                Streamable.server(
                    item.url ?: "",
                    0,
                    "${item.formats?.uppercase() ?: "Unknown Format"}${item.bitrate?.let { " • " + item.bitrate + " kbps" } ?: ""}"
                )
            ),
            extras = mapOf("item" to (item.item ?: "")),
            isPlayable = if (item.key == "unavailable")
                Track.Playable.No(item.subtext ?: "") else Track.Playable.Yes
        )
    }

    private fun createLinkShelf(item: MediaItem): Shelf {
        return Shelf.Category(
            item.id ?: "",
            item.text ?: "",
            createShelf(item.url ?: "")
        )
    }

    private fun String.toShelf(): List<Shelf> {
        val response = json.decodeFromString<JsonResponse>(this)
        return response.body.flatMap { jsonObject ->
            val item = parseMediaItem(jsonObject)
            processMediaItem(item)
        }
    }

    private fun createShelf(url: String): Feed<Shelf> =
        PagedData.Single {
            val fullUrl = if (url.contains("?")) "$url&render=json"
                else "$url?render=json"
            call(fullUrl).toShelf()
        }.toFeed()

    override suspend fun loadHomeFeed(): Feed<Shelf> =
        listOf(
            Shelf.Lists.Categories(
                "home",
                "Home",
                json.decodeFromString<JsonResponse>(call("$apiLink?render=json"))
                    .body.map { jsonObject ->
                        val item = parseMediaItem(jsonObject)
                        Shelf.Category(
                            item.key ?: "",
                            item.text ?: "",
                            createShelf(item.url ?: "")
                        )
                },
                type = Shelf.Lists.Type.Grid
            )
        ).toFeed()

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    private suspend fun getUrls(url: String) =
        call(url).split("\n").filter { it.isNotEmpty() }

    private fun urlExtension(url: String, ext: String) =
        url.endsWith(".$ext", true) ||
                url.substringAfterLast('.').take(ext.length + 1)
                    .equals("$ext?", true)

    private suspend fun parsePLS(stream: String?): String {
        if (stream != null) {
            val content = call(stream)
            for (line in content.lines()) {
                if (line.startsWith("File1=")) {
                    return line.substring(6)
                }
            }
        }
        return ""
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val urls = getUrls(streamable.id).flatMap { url ->
            when {
                urlExtension(url, "pls") -> listOf(parsePLS(url))
                urlExtension(url, "m3u") -> getUrls(url)
                else -> listOf(url)
            }
        }
        return Streamable.Media.Server(
            urls.map { it.toSource(isLive = streamable.extras["item"] == "station") },
            false
        )
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track
    override suspend fun loadTracks(radio: Radio): Feed<Track> = PagedData.empty<Track>().toFeed()
    override suspend fun loadRadio(radio: Radio): Radio  = Radio("", "")
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = Radio("", "")

    private fun processSearchMediaItem(item: MediaItem, stationTracks: MutableList<Track>, otherTracks: MutableList<Track>, linkShelves: MutableList<Shelf.Category>) {
        when (item.type) {
            "audio" -> {
                if (item.item == "station")
                    stationTracks.add(createAudioShelf(item))
                else
                    otherTracks.add(createAudioShelf(item))
            }
            "link" -> linkShelves.add(createLinkShelf(item) as Shelf.Category)
            else -> {
                item.children?.forEach { child ->
                    processSearchMediaItem(child, stationTracks, otherTracks, linkShelves)
                }
            }
        }
    }

    private fun sortSearchShelf(stationTracks: List<Track>, otherTracks: List<Track>, linkShelves: List<Shelf.Category>): List<Shelf> {
        return listOfNotNull(
            Shelf.Lists.Items(
                "search_stations",
                "Stations",
                stationTracks.take(12),
                more = stationTracks.takeIf { stationTracks.size > 12 }?.map { it.toShelf() }?.toFeed()
            ).takeUnless { stationTracks.isEmpty() },
            Shelf.Lists.Categories(
                "search_podcasts",
                "Podcasts",
                linkShelves.take(6),
                more = linkShelves.takeIf { linkShelves.size > 6 }?.toFeed(),
                type = Shelf.Lists.Type.Grid
            ).takeUnless { linkShelves.isEmpty() },
            Shelf.Lists.Tracks(
                "search_tracks",
                "Tracks",
                otherTracks.take(18),
                more = otherTracks.takeIf { otherTracks.size > 18 }?.map { it.toShelf() }?.toFeed()
            ).takeUnless { otherTracks.isEmpty() }
        )
    }

    private fun String.toSearchShelf(): Feed<Shelf> {
        val response = json.decodeFromString<JsonResponse>(this)
        val stationTracks = mutableListOf<Track>()
        val otherTracks = mutableListOf<Track>()
        val linkShelves = mutableListOf<Shelf.Category>()
        response.body.forEach { jsonObject ->
            val item = parseMediaItem(jsonObject)
            processSearchMediaItem(item, stationTracks, otherTracks, linkShelves)
        }
        return sortSearchShelf(stationTracks, otherTracks, linkShelves).toFeed()
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        call("${apiLink}/Search.ashx?query=$query&render=json").toSearchShelf()
}