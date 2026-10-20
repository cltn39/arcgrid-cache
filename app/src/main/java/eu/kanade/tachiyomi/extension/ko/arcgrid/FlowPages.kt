package eu.kanade.tachiyomi.extension.ko.arcgrid

/**
 * Reflow Korean paragraphs like an e-book rather than fixed-size comic panels.
 *  - dialogue has no first-line indent
 *  - ordinary prose has a one-character first-line indent
 *  - keep purposeful scene-break spacing; remove giant empty gaps between <p> tags
 *  - break at Korean characters to avoid ragged, very short lines
 */
internal object FlowPages {
    private const val COLUMNS = PortablePng.MAX_COLUMNS
    private const val ROWS = PortablePng.MAX_ROWS
    private val closingPunctuation = ",.!?;:，。！？、)]}〉》」』’”"

    fun render(text: String, heading: String): List<ByteArray> {
        val lines = ArrayList<String>()
        appendWrapped(lines, heading.trim())
        lines += ""
        var emptyCount = 0
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val paragraph = raw.trim()
            if (paragraph.isBlank()) {
                emptyCount++
                continue
            }
            // Extra source line breaks indicate a scene break, unlike ordinary <p> gaps.
            if (emptyCount >= 2 && lines.lastOrNull() != "") lines += ""
            emptyCount = 0
            val dialogue = paragraph.first() in "\"'“‘「『—-([{〈《"
            appendWrapped(lines, paragraph, if (dialogue) "" else " ")
        }
        while (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        check(lines.isNotEmpty()) { "Empty chapter" }
        val chunks = lines.chunked(ROWS)
        check(chunks.size <= 200) { "Unexpectedly large chapter" }
        return chunks.map { PortablePng.render(it) }
    }

    private fun appendWrapped(lines: MutableList<String>, content: String, firstIndent: String = "") {
        var remaining = firstIndent + content
        while (remaining.isNotEmpty()) {
            var take = minOf(COLUMNS, remaining.length)
            // Keep common sentence-ending punctuation off the start of the next line.
            if (take < remaining.length && remaining[take] in closingPunctuation && take > 1) take--
            val line = remaining.take(take).trimEnd()
            if (line.isNotEmpty()) lines += line
            remaining = remaining.drop(take).trimStart()
        }
    }
}
