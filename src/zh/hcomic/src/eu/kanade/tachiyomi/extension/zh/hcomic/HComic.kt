package eu.kanade.tachiyomi.extension.zh.hcomic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class HComic : HttpSource() {
    override val name = "H-Comic"
    override val baseUrl = "https://h-comic.com"
    override val lang = "zh"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val imageBaseUrl = "https://h-comic.link/api"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

    private fun pageHeaders(referer: String = baseUrl): Headers = headersBuilder()
        .add("Referer", referer)
        .build()

    override fun popularMangaRequest(page: Int): Request = dataRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = parseComicList(response)

    override fun latestUpdatesRequest(page: Int): Request = dataRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseComicList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/__data.json?x-sveltekit-invalidated=01&page=$page&q=${query.urlEncode()}"
        return GET(url, pageHeaders(baseUrl))
    }

    override fun searchMangaParse(response: Response): MangasPage = parseComicList(response)

    private fun dataRequest(page: Int): Request {
        val url = "$baseUrl/__data.json?x-sveltekit-invalidated=01&page=$page"
        return GET(url, pageHeaders(baseUrl))
    }

    private fun parseComicList(response: Response): MangasPage {
        val root = json.parseToJsonElement(response.body.string())
        val comics = root.decodeSvelteData()
            .findObjectsWithKeys("id", "media_id", "title")
            .distinctBy { it.string("id") }
            .mapNotNull { it.toSManga() }
        val pageInfo = root.decodeSvelteData().findObjectsWithKeys("pages").firstOrNull()
        val currentPage = pageInfo?.int("page") ?: response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = pageInfo?.int("pages") ?: currentPage
        return MangasPage(comics, currentPage < totalPages)
    }

    private fun JsonObject.toSManga(): SManga? {
        val id = string("id") ?: return null
        val mediaId = string("media_id") ?: return null
        val comicSource = string("comic_source") ?: "nh"
        val titleObj = get("title") as? JsonObject
        val displayTitle = titleObj?.string("display")
            ?: titleObj?.string("japanese")
            ?: titleObj?.string("english")
            ?: titleObj?.string("pretty")
            ?: return null
        return SManga.create().apply {
            title = displayTitle
            url = "/comics/$id/1"
            thumbnail_url = imageServer(comicSource) + "/" + mediaId
            author = tagsByType("artist").joinToString(", ").ifBlank { null }
            artist = author
            genre = get("tags")?.asArrayOrNull()
                ?.mapNotNull { (it as? JsonObject)?.string("name_zh") ?: (it as? JsonObject)?.string("name") }
                ?.joinToString(", ")
            description = titleObj?.let {
                listOfNotNull(
                    it.string("japanese")?.let { value -> "日文：$value" },
                    it.string("english")?.let { value -> "英文：$value" },
                    int("num_pages")?.let { value -> "页数：$value" },
                    string("media_id")?.let { value -> "Media ID：$value" },
                ).joinToString("\n")
            }
            status = SManga.COMPLETED
            initialized = true
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, pageHeaders(baseUrl + manga.url))

    override fun mangaDetailsParse(response: Response): SManga {
        val comic = parseComicFromResponse(response)
        return comic?.toSManga() ?: SManga.create()
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val comic = parseComicFromResponse(response) ?: return emptyList()
        val id = comic.string("id") ?: response.request.url.pathSegments.getOrNull(1) ?: return emptyList()
        val titleObj = comic["title"] as? JsonObject
        val chapterName = titleObj?.string("display") ?: titleObj?.string("japanese") ?: "全本"
        return listOf(
            SChapter.create().apply {
                name = chapterName
                url = "/comics/$id/1"
                date_upload = comic.long("upload_date")?.times(1000L) ?: 0L
                chapter_number = 1f
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, pageHeaders(baseUrl + chapter.url))

    override fun pageListParse(response: Response): List<Page> {
        val comic = parseComicFromResponse(response) ?: error("Cannot find comic data")
        val mediaId = comic.string("media_id") ?: error("Cannot find media_id")
        val comicSource = comic.string("comic_source") ?: "nh"
        val pages = comic["images"]?.jsonObjectOrNull()
            ?.get("pages")?.asArrayOrNull()
            ?: JsonArray(List(comic.int("num_pages") ?: 0) { JsonObject(emptyMap()) })
        val server = imageServer(comicSource)
        return pages.mapIndexed { index, page ->
            Page(index, imageUrl = "$server/$mediaId/pages/${index + 1}")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headersBuilder()
            .add("Referer", baseUrl)
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build(),
    )

    private fun parseComicFromResponse(response: Response): JsonObject? {
        val root = when {
            response.request.url.encodedPath.endsWith("/__data.json") -> json.parseToJsonElement(response.body.string())
            else -> extractSvelteData(response.body.string())
        }
        val id = response.request.url.pathSegments.getOrNull(1)
        return root.decodeSvelteData()
            .findObjectsWithKeys("id", "media_id", "title")
            .firstOrNull { id == null || it.string("id") == id }
    }

    private fun extractSvelteData(html: String): JsonElement {
        val dataIndex = html.indexOf("data: [")
        if (dataIndex == -1) error("Cannot find Svelte data")
        val start = html.indexOf('[', dataIndex)
        val end = findMatchingBracket(html, start)
        val jsonLike = html.substring(start, end + 1)
        return json.parseToJsonElement(jsonLike)
    }

    private fun findMatchingBracket(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        error("Cannot find end of Svelte data")
    }

    private fun imageServer(comicSource: String): String = when (comicSource) {
        "MMCG_SHORT", "mms" -> "$imageBaseUrl/mms"
        "MMCG_LONG", "mml" -> "$imageBaseUrl/mml"
        else -> "$imageBaseUrl/nh"
    }

    private fun JsonElement.decodeSvelteData(): JsonElement {
        val data = when (this) {
            is JsonObject -> this["nodes"]?.asArrayOrNull()
                ?.mapNotNull { (it as? JsonObject)?.get("data") }
                ?.firstOrNull { it !is JsonNull }
                ?: this["data"]
                ?: this
            else -> this
        }
        return SvelteDecoder(data).decode()
    }

    private class SvelteDecoder(private val table: JsonElement) {
        private val array = table as? JsonArray

        fun decode(): JsonElement = decodeElement(table, mutableSetOf())

        private fun decodeRef(index: Int, stack: MutableSet<Int>): JsonElement {
            val items = array ?: return JsonNull
            if (index !in items.indices || !stack.add(index)) return JsonNull
            val decoded = decodeElement(items[index], stack)
            stack.remove(index)
            return decoded
        }

        private fun decodeElement(element: JsonElement, stack: MutableSet<Int>): JsonElement = when (element) {
            is JsonArray -> decodeArray(element, stack)
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> decodeElement(value, stack) })
            is JsonPrimitive -> {
                val ref = element.intOrNull
                if (ref != null && array != null && ref in array.indices) decodeRef(ref, stack) else element
            }
            else -> element
        }

        private fun decodeArray(value: JsonArray, stack: MutableSet<Int>): JsonElement {
            if (value.isEmpty()) return value
            val first = value[0]
            if (first is JsonObject) {
                val keys = first.keys.toList()
                if (keys.isNotEmpty() && value.size == keys.size + 1) {
                    return JsonObject(keys.mapIndexed { index, key -> key to decodeElement(value[index + 1], stack) }.toMap())
                }
            }
            return JsonArray(value.map { decodeElement(it, stack) })
        }
    }

    private fun JsonElement.findObjectsWithKeys(vararg keys: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if (keys.all { element.containsKey(it) }) out += element
                    element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(this)
        return out
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitiveOrNull()?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitiveOrNull()?.contentOrNull?.toLongOrNull()
        ?: this[key]?.jsonPrimitiveOrNull()?.doubleOrNull?.toLong()

    private fun JsonObject.tagsByType(type: String): List<String> = get("tags")?.asArrayOrNull()
        ?.mapNotNull { it as? JsonObject }
        ?.filter { it.string("type") == type }
        ?.mapNotNull { it.string("name_zh") ?: it.string("name") }
        ?: emptyList()

    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
    }
}
