package eu.kanade.tachiyomi.extension.ko.arcgrid

/** Lay out a Korean chapter on pages with phone-readable text. */
internal object FlowPages {
    private const val COLUMNS = PortablePng.MAX_COLUMNS
    private const val ROWS = PortablePng.MAX_ROWS

    fun render(text: String, heading: String): List<ByteArray> {
        val lines = arrayListOf<String>()
        lines += heading.take(COLUMNS)
        lines += ""
        for (paragraph in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (paragraph.isBlank()) {
                if (lines.lastOrNull() != "") lines += ""
                continue
            }
            val words = paragraph.trim().split(Regex("[\\t ]+"))
            val row = StringBuilder()
            fun flush() {
                if (row.isNotEmpty()) {
                    lines += row.toString()
                    row.setLength(0)
                }
            }
            for (word in words) {
                if (word.isBlank()) continue
                var remaining = word
                while (remaining.isNotEmpty()) {
                    if (row.isNotEmpty()) {
                        if (row.length + 1 + remaining.length <= COLUMNS) {
                            row.append(' ').append(remaining)
                            remaining = ""
                        } else {
                            flush()
                        }
                        continue
                    }
                    if (remaining.length <= COLUMNS) {
                        row.append(remaining)
                        remaining = ""
                    } else {
                        // Long unspaced runs (e.g. URLs or unusual punctuation) should not overflow.
                        lines += remaining.take(COLUMNS)
                        remaining = remaining.drop(COLUMNS)
                    }
                }
            }
            flush()
            if (lines.lastOrNull() != "") lines += ""
        }
        while (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        require(lines.isNotEmpty()) { "No chapter content" }
        val chunks = lines.chunked(ROWS)
        check(chunks.size <= 200) { "Unexpectedly large chapter" }
        return chunks.map { PortablePng.render(it) }
    }
}
