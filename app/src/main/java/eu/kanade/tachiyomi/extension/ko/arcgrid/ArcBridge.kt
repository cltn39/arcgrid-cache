package eu.kanade.tachiyomi.extension.ko.arcgrid

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable

/** Experimental single-title source: Bookkor HTML -> bitmap-font -> PNG without Android graphics. */
class ArcBridge : HttpSource() {
    override val name = "Page Cache"
    override val lang = "ko"
    override val baseUrl = "https://005.bookkor.com"
    override val supportsLatest = false

    private val bookPath = "/여헌터를-따먹으면-주인공이-강해짐"
    private val bookTitle = "여헌터를 따먹으면 주인공이 강해짐"
    private val firstPath = "$bookPath/6426-ep-4165710"
    private val pageHost = "pg-cache.invalid"
    @Volatile private var cachedCoverUrl: String? = null

    // Access this same client from the PNG image loader, just as the successful demo did.
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != pageHost) {
                chain.proceed(request)
            } else {
                val key = request.url.encodedPath
                val png = PageCache.load(key)
                    ?: error("Rendered page is no longer in memory. Reopen this chapter: $key")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(png.toResponseBody("image/png".toMediaType()))
                    .build()
            }
        }.build()

    private fun oneBook(): SManga = SManga.create().apply {
        url = bookPath
        title = bookTitle
        description = "실험: 사이트에서 회차를 읽고 글자를 직접 PNG로 변환합니다. 예제 작품 한 개만 포함합니다."
        genre = "텍스트, 실험"
        // Existing library entries may need a metadata refresh to receive a cover URL.
        initialized = false
    }

    // Include cover metadata in the catalogue entry, not only the details callback.
    private fun listBook(): SManga = oneBook().apply {
        thumbnail_url = cachedCoverUrl ?: runCatching { coverFrom(loadHtml(bookPath)) }.getOrNull()
            ?.also { cachedCoverUrl = it }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        MangasPage(if (page == 1) listOf(listBook()) else emptyList(), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable {
            MangasPage(
                if (page == 1 && bookTitle.contains(query.trim(), ignoreCase = true)) listOf(listBook()) else emptyList(),
                false,
            )
        }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchPopularManga(page)
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        getMangaDetailsBlocking()
    }
    override suspend fun getMangaDetails(manga: SManga): SManga = getMangaDetailsBlocking()
    private fun getMangaDetailsBlocking(): SManga {
        val doc = loadHtml(bookPath)
        return oneBook().apply {
            description = doc.select("meta[name=description]").attr("content").ifBlank { doc.select("h1").firstOrNull()?.text() ?: bookTitle }
            thumbnail_url = coverFrom(doc).also { if (it != null) cachedCoverUrl = it }
            initialized = true
        }
    }

    /** Resolve Open Graph metadata and common lazy-loaded cover image attributes. */
    private fun coverFrom(doc: Document): String? {
        fun valid(url: String): Boolean {
            val lower = url.lowercase()
            return (lower.startsWith("https://") || lower.startsWith("http://")) &&
                !listOf("placeholder", "loading.gif", "default-cover", "no-image", "noimage", "logo.", "favicon").any {
                    it in lower
                }
        }

        for (meta in doc.select(
            "meta[property=og:image],meta[property=og:image:secure_url]," +
                "meta[name=twitter:image],meta[property=twitter:image],link[rel=image_src]"
        )) {
            for (attribute in listOf("content", "href")) {
                val url = meta.absUrl(attribute)
                if (valid(url)) return url
            }
        }

        val selectors = listOf(
            ".book-cover img", ".novel-cover img", ".bookimg img",
            ".book-img img", ".cover img", ".thumbnail img",
            ".detail-cover img", ".post-thumbnail img",
            "img[class*=cover]", "img[id*=cover]",
            "main article img", "article img"
        )
        for (selector in selectors) {
            for (img in doc.select(selector)) {
                for (attribute in listOf("data-original", "data-src", "data-lazy-src", "data-url", "src")) {
                    val url = img.absUrl(attribute)
                    if (valid(url)) return url
                }
                for (attribute in listOf("data-srcset", "srcset")) {
                    val first = img.attr(attribute).substringBefore(',').trim().substringBefore(' ').trim()
                    if (first.isNotEmpty()) {
                        val url = runCatching { java.net.URI(doc.baseUri()).resolve(first).toString() }.getOrNull()
                        if (url != null && valid(url)) return url
                    }
                }
            }
        }
        return null
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable { getChaptersBlocking() }
    override suspend fun getChapterList(manga: SManga): List<SChapter> = getChaptersBlocking()
    private fun getChaptersBlocking(): List<SChapter> {
        val doc = loadHtml(bookPath)
        val paths = linkedMapOf<String, Pair<String, Float>>()
        val episodeLink = Regex("""^/[^/]+/6426-ep-\d+$""")
        for (anchor in doc.select("a[href*='6426-ep-']")) {
            val url = anchor.absUrl("href")
            if (!url.startsWith("$baseUrl/")) continue
            val path = url.removePrefix(baseUrl).substringBefore('#').substringBefore('?')
            if (!episodeLink.matches(path)) continue
            val label = anchor.text().trim()
            val num = Regex("""(\d+)\s*화""").find(label)?.groupValues?.get(1)?.toFloatOrNull()
                ?: continue
            paths[path] = label to num
        }
        // The user supplied this particular chapter URL, so it is the only hard-coded episode.
        paths.putIfAbsent(firstPath, "1화 (직접 지정된 링크)" to 1f)
        // Never assume that episode IDs increase by one. Discover the next chapter via links.
        if (paths.values.none { it.second == 2f }) {
            val first = runCatching { loadHtml(firstPath) }.getOrNull()
            first?.select("a[href*='6426-ep-']")?.forEach { anchor ->
                val label = anchor.text().trim()
                val hint = (label + " " + anchor.className() + " " + anchor.attr("rel"))
                if (!(hint.contains("다음") || hint.contains("next", true) || hint.contains("2화"))) return@forEach
                val url = anchor.absUrl("href")
                if (!url.startsWith("$baseUrl/")) return@forEach
                val path = url.removePrefix(baseUrl).substringBefore('#').substringBefore('?')
                if (path != firstPath && episodeLink.matches(path)) {
                    paths.putIfAbsent(path, "2화 (다음 회차 링크에서 발견)" to 2f)
                }
            }
        }
        return paths.map { (url, meta) -> SChapter.create().apply {
            this.url = url; name = meta.first; chapter_number = meta.second
        } }.distinctBy { it.chapter_number }.sortedByDescending { it.chapter_number }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val doc = loadHtml(chapter.url)
        val content = BodyExtract.extract(doc)
        require(content.length >= 100) { "Chapter body not found. HTML selectors may have changed." }
        val imgs = FlowPages.render(content, chapter.name)
        require(imgs.isNotEmpty()) { "Rendering produced no pages" }
        val prefix = "/" + chapter.url.hashCode().toUInt().toString(16)
        PageCache.store(prefix, imgs)
        imgs.indices.map { index -> Page(index, imageUrl = "https://$pageHost$prefix/$index.png") }
    }

    private fun loadHtml(path: String): Document {
        require(path == bookPath || Regex("""^/[^/]+/6426-ep-\d+$""").matches(path)) { "Unexpected path" }
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            .build()
        client.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "Site returned HTTP ${resp.code}" }
            val html = resp.body?.string() ?: error("Empty HTML")
            check(html.length > 300) { "Site returned an unexpectedly short page" }
            return Jsoup.parse(html, resp.request.url.toString())
        }
    }

    override fun getFilterList() = FilterList()
    override fun imageUrlParse(response: Response): String = error("Image URLs are provided directly")
    override fun popularMangaRequest(page: Int): Request = error("Custom observable source")
    override fun popularMangaParse(response: Response): MangasPage = error("Custom observable source")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = error("Custom observable source")
    override fun searchMangaParse(response: Response): MangasPage = error("Custom observable source")
    override fun latestUpdatesRequest(page: Int): Request = error("Custom observable source")
    override fun latestUpdatesParse(response: Response): MangasPage = error("Custom observable source")
    override fun mangaDetailsParse(response: Response): SManga = error("Custom observable source")
    override fun chapterListParse(response: Response): List<SChapter> = error("Custom observable source")
    override fun pageListParse(response: Response): List<Page> = error("Custom observable source")
}

internal object PageCache {
    // Retain up to two recent chapters, avoiding errors during quick chapter switches.
    private val cache = linkedMapOf<String, Map<String, ByteArray>>()
    @Synchronized fun store(prefix: String, pages: List<ByteArray>) {
        cache.remove(prefix)
        cache[prefix] = pages.mapIndexed { i, data -> "$prefix/$i.png" to data }.toMap()
        while (cache.size > 2) cache.remove(cache.keys.first())
    }
    @Synchronized fun load(path: String): ByteArray? = cache.values.firstNotNullOfOrNull { it[path] }
}

internal object BodyExtract {
    /** Picks a text-dense region while strongly penalizing navigation links and comments. */
    fun extract(doc: Document): String {
        doc.select("script,style,nav,footer,header,aside,form,iframe").remove()
        val best = doc.select(
            "#chapter-content,#reader-content,#novel-content,#content,.chapter-content,.reader-content,.novel-content,.reading-content,.entry-content,.read-content,.page-content,.prose,article,main,section,div"
        ).asSequence()
            .filter { it.text().length >= 250 }
            .maxByOrNull { node ->
                val n = node.text().length
                val links = node.select("a").size
                val buttons = node.select("button").size
                val bonus = if (node.id().contains("content", true) || node.className().contains("reader", true)) 600 else 0
                n - links * 240 - buttons * 130 + bonus
            } ?: error("No text region found on this chapter page")
        val paragraphs = best.select("p")
            .map { it.text() }
            .filter { it.length >= 10 }
        val body = if (paragraphs.size >= 3) paragraphs.joinToString("\n\n")
            else best.wholeText().replace(Regex("[ \\t]+"), " ")
        return body.trim().take(150_000)
    }
}
