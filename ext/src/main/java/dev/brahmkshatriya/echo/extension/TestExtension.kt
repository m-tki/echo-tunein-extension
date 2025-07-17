package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
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
    val formats: String? = null,
    val image: String? = null,
    @SerialName("current_track")
    val currentTrack: String? = null,
    val children: List<MediaItem>? = null
)

class TestExtension : ExtensionClient, HomeFeedClient, TrackClient, RadioClient, SearchFeedClient {
    override suspend fun onExtensionSelected() {}

    override val settingItems: List<Setting> = emptyList()

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    private val apiLink = "https://opml.radiotime.com"

    private val client by lazy { OkHttpClient.Builder().build() }
    private suspend fun call(url: String) = client.newCall(
        Request.Builder().url(url).build()
    ).await().body.string()

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
            "audio" -> listOf(createAudioShelf(item))
            "link" -> listOf(createLinkShelf(item))
            else -> {
                item.children?.flatMap { processMediaItem(it) } ?: emptyList()
            }
        }
    }

    private fun createAudioShelf(item: MediaItem): Shelf {
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
            )
        ).toMediaItem().toShelf()
    }

    private fun createLinkShelf(item: MediaItem): Shelf {
        return Shelf.Category(
            title = item.text ?: "",
            items = createShelf(item.url ?: "")
        )
    }

    private fun String.toShelf(): List<Shelf> {
        val response = json.decodeFromString<JsonResponse>(this)
        return response.body.flatMap { jsonObject ->
            val item = parseMediaItem(jsonObject)
            processMediaItem(item)
        }
    }

    private fun createShelf(url: String): PagedData.Single<Shelf> {
        return PagedData.Single {
            val fullUrl = if (url.contains("?")) "$url&render=json"
                else "$url?render=json"
            call(fullUrl).toShelf()
        }
    }

    override fun getHomeFeed(tab: Tab?) = createShelf(tab?.id!!).toFeed()

    override suspend fun getHomeTabs(): List<Tab> {
        return json.decodeFromString<JsonResponse>(call("$apiLink?render=json"))
            .body.map { jsonObject ->
                val item = parseMediaItem(jsonObject)
                Tab(title = item.text ?: "", id = item.url ?: "")
            }
    }

    override fun getShelves(track: Track): PagedData<Shelf> {
        return PagedData.empty()
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        val response = call(streamable.id).split("\n").filter { it.isNotEmpty() }
        return Streamable.Media.Server(
            response.map { it.toSource() },
            false
        )
    }

    override suspend fun loadTrack(track: Track) = track
    override fun loadTracks(radio: Radio) = PagedData.empty<Track>()
    override suspend fun radio(track: Track, context: EchoMediaItem?) = Radio("", "")
    override suspend fun radio(album: Album) = throw ClientException.NotSupported("Album radio")
    override suspend fun radio(artist: Artist) = throw ClientException.NotSupported("Artist radio")
    override suspend fun radio(user: User) = throw ClientException.NotSupported("User radio")
    override suspend fun radio(playlist: Playlist) =
        throw ClientException.NotSupported("Playlist radio")

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}
    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        return emptyList()
    }

    override fun searchFeed(query: String, tab: Tab?) = PagedData.Single {
            call("${apiLink}/Search.ashx?query=$query&render=json").toShelf()
        }.toFeed()

    override suspend fun searchTabs(query: String) = emptyList<Tab>()
}