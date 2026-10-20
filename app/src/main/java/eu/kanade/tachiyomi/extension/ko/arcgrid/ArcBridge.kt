package eu.kanade.tachiyomi.extension.ko.arcgrid

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import rx.Observable

/** Bookkor Korean novel extension: HTML chapter pages rendered to PNG. */
class ArcBridge : HttpSource() {
    override val name = "Bookkor"
    override val lang = "ko"
    override val baseUrl = "https://005.bookkor.com"
    override val supportsLatest = false

    private val pageHost = "pg-cache.invalid"

    /** Known working Bookkor catalogue sections; doesn't change source ID. */
    private class SectionFilter : Filter.Select<String>(
        "작품 분류",
        arrayOf("전체 작품", "일반소설", "성인소설", "BL/GL", "완결소설"),
    )

    private fun selectedSection(filters: FilterList): String {
        val index = filters.filterIsInstance<SectionFilter>().firstOrNull()?.state ?: 0
        return when (index) {
            1 -> "/일반소설"
            2 -> "/성인소설"
            3 -> "/BLGL"
            4 -> "/완결소설"
            else -> "/"
        }
    }
    @Volatile private var usePlainHtmlForEpisodes = false
    private val coverCache = linkedMapOf<String, String>()

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

    private fun novelPath(value: String): String {
        // Accept saved Korean and percent-encoded paths, but never an external URL.
        val path = value.substringBefore('#').substringBefore('?')
        require(Regex("""^/[^/?#\\]+$""").matches(path)) { "Unexpected novel path: $value" }
        require(path != "/" && path != "/검색" && path != "/성인소설" &&
            path != "/일반소설" && path != "/완결소설" && path != "/BLGL") { "Not a novel: $path" }
        return path
    }

    private fun titleFromPath(path: String): String {
        val raw = path.substringAfterLast('/')
        return java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace('-', ' ')
    }

    private fun categoryPage(page: Int, query: String, section: String): Pair<JSONObject, Document?> {
        require(page in 1..200) { "Invalid catalogue page: $page" }
        val keyword = query.trim()
        // Site-wide search uses /?keyword=...; known section pages support catalogue browsing.
        // When a keyword is supplied with a section, pass it to that section URL.
        val queryString = if (keyword.isBlank()) "?page=$page" else {
            val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
            "?keyword=$encoded&page=$page"
        }
        val request = Request.Builder()
            .url("$baseUrl$section$queryString")
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            // Ordinary HTML GET avoids the 409 experienced with stale Inertia headers.
            .header("Accept", "text/html, application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Search page $page returned HTTP ${response.code}" }
            val body = response.body?.string() ?: error("Empty catalogue response")
            if (body.trimStart().startsWith("{")) return JSONObject(body) to null
            val doc = Jsoup.parse(body, response.request.url.toString())
            val root = doc.selectFirst("[data-page]")
                ?: error("Bookkor catalogue does not contain an Inertia data-page payload")
            val embedded = root.attr("data-page").takeIf { it.trimStart().startsWith("{") }
                ?: root.data().takeIf { it.trimStart().startsWith("{") }
                ?: error("Bookkor catalogue JSON is not available")
            return JSONObject(embedded) to doc
        }
    }

    private fun cataloguePage(page: Int, query: String, section: String = "/"): MangasPage {
        val (payload, htmlDoc) = categoryPage(page, query, section)
        // Captured search response uses component=contents/Home and props.comics.data.
        val props = payload.optJSONObject("props")
            ?: error("Bookkor catalogue response has no props")
        val comics = props.optJSONObject("comics")
            ?: error("Bookkor catalogue response has no props.comics")
        val items = comics.optJSONArray("data")
            ?: error("Bookkor catalogue response has no props.comics.data")
        val kind = props.optJSONObject("content")?.optString("kind")
        check(kind.isNullOrBlank() || kind == "novel") { "The selected catalogue is not a novel source" }

        // Map covers from HTML cards when the JSON thumbnail is only a bare filename.
        val listedCovers = HashMap<String, String>()
        htmlDoc?.select("a[href] img")?.forEach { img ->
            val anchor = img.closest("a[href]") ?: return@forEach
            val href = anchor.absUrl("href")
            if (!href.startsWith("$baseUrl/")) return@forEach
            val path = href.removePrefix(baseUrl).substringBefore('?').substringBefore('#')
            if (runCatching { novelPath(path) }.isFailure) return@forEach
            val candidate = listOf("data-original", "data-src", "data-lazy-src", "src")
                .map { img.absUrl(it) }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            if (candidate != null) listedCovers[path] = candidate
        }

        val mangas = LinkedHashMap<String, SManga>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val slug = item.optString("slug").trim()
            val path = runCatching { novelPath("/$slug") }.getOrNull() ?: continue
            val title = item.optString("title").trim().ifBlank { titleFromPath(path) }
            val thumbnail = item.optString("thumbnail").trim()
            val directThumb = when {
                thumbnail.startsWith("https://") || thumbnail.startsWith("http://") -> thumbnail
                thumbnail.startsWith("/") -> "$baseUrl$thumbnail"
                else -> null // A bare storage filename is not a usable URL.
            }
            val knownCover = synchronized(coverCache) { coverCache[path] }
            val cover = knownCover ?: listedCovers[path] ?: directThumb
            mangas[path] = SManga.create().apply {
                url = path
                this.title = title
                thumbnail_url = cover
                genre = item.optString("tags_string").takeIf { it.isNotBlank() }
                initialized = false // Let the details request resolve cover and description.
            }
            if (cover != null) synchronized(coverCache) { coverCache[path] = cover }
        }

        // For small search results (e.g. one title), resolve bare-filename covers
        // from the actual detail page without flooding the site on large results.
        if (query.isNotBlank() && mangas.size <= 3) {
            mangas.values.forEach { manga ->
                if (manga.thumbnail_url.isNullOrBlank()) {
                    val cover = runCatching { coverFrom(loadHtml(manga.url)) }.getOrNull()
                    if (cover != null) {
                        manga.thumbnail_url = cover
                        synchronized(coverCache) { coverCache[manga.url] = cover }
                    }
                }
            }
        }
        val current = comics.optInt("current_page", page)
        val last = comics.optInt("last_page", page)
        val hasNext = comics.optString("next_page_url").let {
            it.isNotBlank() && it != "null"
        } || current < last
        return MangasPage(mangas.values.toList(), hasNext)
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        cataloguePage(page, "")
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable { cataloguePage(page, query, selectedSection(filters)) }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchPopularManga(page)

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        getMangaDetailsBlocking(manga)
    }
    override suspend fun getMangaDetails(manga: SManga): SManga = getMangaDetailsBlocking(manga)

    private fun getMangaDetailsBlocking(manga: SManga): SManga {
        val path = novelPath(manga.url)
        val doc = loadHtml(path)
        return manga.apply {
            title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: title
            description = doc.select("meta[name=description]").attr("content")
                .ifBlank { doc.selectFirst(".description,.synopsis,.summary")?.text().orEmpty() }
            val cover = coverFrom(doc)
            if (!cover.isNullOrBlank()) {
                thumbnail_url = cover
                synchronized(coverCache) { coverCache[path] = cover }
            } else synchronized(coverCache) { thumbnail_url = coverCache[path] ?: thumbnail_url }
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

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { getChaptersBlocking(manga) }

    override suspend fun getChapterList(manga: SManga): List<SChapter> = getChaptersBlocking(manga)

    private data class EpisodeInfo(val path: String, val name: String, val number: Float)
    private data class EpisodeBatch(val records: List<EpisodeInfo>, val page: Int, val lastPage: Int?)

    private fun parseEpisodeBatch(inertia: JSONObject, bookPath: String): EpisodeBatch {
        val listing = inertia.optJSONObject("props")?.optJSONObject("episodes")
            ?: error("Inertia response does not contain props.episodes")
        val data = listing.optJSONArray("data") ?: error("Episode page has no data array")
        val records = ArrayList<EpisodeInfo>(data.length())
        val episodeSlug = Regex("""\d+-ep-\d+""")
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val slug = item.optString("slug").ifBlank {
                item.optJSONObject("content")?.optString("slug").orEmpty()
            }
            if (!episodeSlug.matches(slug)) continue // use only real episode slugs
            val seriesId = item.optInt("series_id", 0)
            if (seriesId > 0 && !slug.startsWith("$seriesId-ep-")) continue
            val number = item.optDouble("sort", Double.NaN)
            if (number.isNaN() || number.isInfinite() || number <= 0) continue
            val title = item.optString("title").trim()
            val label = title.ifBlank { "${number.toInt()}화" }
            records += EpisodeInfo("$bookPath/$slug", label, number.toFloat())
        }
        return EpisodeBatch(
            records,
            listing.optInt("current_page", 1),
            listing.optInt("last_page", 0).takeIf { it > 0 },
        )
    }

    /** Inertia places its first JSON page in the HTML's data-page attribute. */
    private fun readInitialBatch(doc: Document, bookPath: String): Pair<EpisodeBatch?, String?> {
        val raw = doc.selectFirst("[data-page]")?.attr("data-page").orEmpty()
        if (raw.isBlank()) return null to null
        return runCatching {
            val payload = JSONObject(raw)
            parseEpisodeBatch(payload, bookPath) to payload.optString("version").takeIf { it.isNotBlank() }
        }.getOrElse { null to null }
    }

    /** Accept the JSON page object either from an Inertia response or the full HTML shell. */
    private fun readEpisodePayload(body: String, url: String, page: Int, bookPath: String): EpisodeBatch {
        val trimmed = body.trimStart()
        val json = if (trimmed.startsWith("{")) {
            JSONObject(trimmed)
        } else {
            val doc = Jsoup.parse(body, url)
            val root = doc.selectFirst("[data-page]")
                ?: error("Episode page $page has no embedded Inertia page object")
            // Inertia v1/v2: JSON in data-page attribute. v3: JSON in a script body.
            val raw = root.attr("data-page").takeIf { it.trimStart().startsWith("{") }
                ?: root.data().takeIf { it.trimStart().startsWith("{") }
                ?: error("Episode page $page has an unrecognised Inertia page object")
            JSONObject(raw)
        }
        return parseEpisodeBatch(json, bookPath).also {
            check(it.page == page) {
                "Expected episode page $page but the server returned ${it.page}"
            }
        }
    }

    /** Request ?page=N as a normal page visit: no X-Inertia-Version means no stale-asset 409. */
    private fun getEpisodeBatchHtml(page: Int, bookPath: String): EpisodeBatch {
        val request = Request.Builder()
            .url("$baseUrl$bookPath?page=$page")
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            .header("Accept", "text/html, application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Full HTML episode page $page returned HTTP ${response.code}"
            }
            val body = response.body?.string() ?: error("No body in HTML episode page $page")
            return readEpisodePayload(body, response.request.url.toString(), page, bookPath)
        }
    }

    private fun getEpisodeBatch(page: Int, inertiaVersion: String?, bookPath: String): EpisodeBatch {
        if (usePlainHtmlForEpisodes) return getEpisodeBatchHtml(page, bookPath)
        val request = Request.Builder()
            .url("$baseUrl$bookPath?page=$page")
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.7")
            .header("Accept", "text/html, application/xhtml+xml")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Inertia", "true")
            .header("X-Inertia-Partial-Component", "contents/List")
            .header("X-Inertia-Partial-Data", "episodes")
            .header("Referer", Request.Builder().url(baseUrl + bookPath).build().url.toString())
            .apply { if (!inertiaVersion.isNullOrBlank()) header("X-Inertia-Version", inertiaVersion) }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 409) {
                // Inertia uses 409 + X-Inertia-Location for asset-version mismatch.
                // Switching to a regular GET loads the current page without stale headers.
                usePlainHtmlForEpisodes = true
                return getEpisodeBatchHtml(page, bookPath)
            }
            check(response.isSuccessful) { "Episode list page $page returned HTTP ${response.code}" }
            val body = response.body?.string() ?: error("No response for episode page $page")
            return readEpisodePayload(body, response.request.url.toString(), page, bookPath)
        }
    }

    private fun getChaptersBlocking(manga: SManga): List<SChapter> {
        val bookPath = novelPath(manga.url)
        val doc = loadHtml(bookPath)
        val results = linkedMapOf<String, EpisodeInfo>()
        val (initial, inertiaVersion) = readInitialBatch(doc, bookPath)
        initial?.records?.forEach { results[it.path] = it }

        // Keep an HTML fallback, useful when the initial server-rendered payload is absent.
        val episodeLink = Regex("""^/[^/]+/\d+-ep-\d+$""")
        for (anchor in doc.select("a[href*='-ep-']")) {
            val fullUrl = anchor.absUrl("href")
            if (!fullUrl.startsWith("$baseUrl/")) continue
            val path = fullUrl.removePrefix(baseUrl).substringBefore('#').substringBefore('?')
            if (!episodeLink.matches(path) || path in results) continue
            val title = anchor.text().trim()
            val number = Regex("""(\d+)\s*화""").find(title)?.groupValues?.get(1)?.toFloatOrNull()
                ?: continue
            results[path] = EpisodeInfo(path, title, number)
        }

        // Follow the site's actual paginated episode feed; do not synthesize slugs.
        val knownLastPage = initial?.lastPage
        var page = 2
        var previousFirstPath: String? = null
        while (page <= minOf(knownLastPage ?: 100, 100)) {
            // A temporary failure must not wipe out already loaded chapters.
            val batch = try {
                getEpisodeBatch(page, inertiaVersion, bookPath)
            } catch (e: Exception) {
                if (results.isEmpty()) throw e
                break // Keep the chapters we have rather than reporting an empty source.
            }
            if (batch.records.isEmpty()) break
            check(batch.records.first().path != previousFirstPath) {
                "Episode page $page repeated previous page; stopped to prevent an infinite loop"
            }
            previousFirstPath = batch.records.first().path
            val oldSize = results.size
            batch.records.forEach { results[it.path] = it }
            check(results.size > oldSize) { "Episode page $page contained no new chapters" }
            if (page >= (batch.lastPage ?: knownLastPage ?: 100)) break
            page++
        }

        return results.values.distinctBy { it.number }.sortedByDescending { it.number }
            .map { ep -> SChapter.create().apply {
                url = ep.path
                name = ep.name
                chapter_number = ep.number
            } }
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
        val valid = path == "/" || Regex("""^/\?page=\d+$""").matches(path) ||
            Regex("""^/[^/?#\\]+$""").matches(path) ||
            Regex("""^/[^/?#\\]+/\d+-ep-\d+$""").matches(path)
        require(valid) { "Unexpected path: $path" }
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

    override fun getFilterList() = FilterList(SectionFilter())
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
