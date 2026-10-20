package eu.kanade.tachiyomi.extension.ko.arcgrid

/** Convert Korean text into continuous page images, with paragraph breaks. */
internal object FlowPages {
    fun render(text: String, heading: String): List<ByteArray> {
        val lines = arrayListOf<String>()
        lines += heading.take(28)
        lines += ""
        for (paragraph in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (paragraph.isBlank()) {
                if (lines.lastOrNull() != "") lines += ""
                continue
            }
            val trimmed = paragraph.trim()
            var row = StringBuilder()
            for (word in trimmed.split(' ')) {
                if (word.isEmpty()) continue
                if (row.isNotEmpty() && row.length + 1 + word.length > 28) {
                    lines += row.toString(); row = StringBuilder()
                }
                var remain = word
                while (remain.length > 28) {
                    if (row.isNotEmpty()) { lines += row.toString(); row = StringBuilder() }
                    lines += remain.take(28)
                    remain = remain.drop(28)
                }
                if (remain.isNotEmpty()) {
                    if (row.isNotEmpty()) row.append(' ')
                    row.append(remain)
                }
            }
            if (row.isNotEmpty()) lines += row.toString()
            lines += ""
        }
        if (lines.isEmpty()) error("No content")
        val chunks = lines.chunked(27)
        check(chunks.size <= 200) { "Unexpectedly large chapter" }
        return chunks.map { PortablePng.render(it) }
    }
}
