package be.thalos.artiest.ui

/**
 * A JSON value, as much of one as a workspace file needs.
 *
 * Accessors rather than casts, because every read in [WorkspaceJson] is a read
 * of something a stranger wrote: `obj.str("name")` gives null when the field is
 * missing, when it is a number, or when it is an array, and the caller has one
 * thing to handle instead of three.
 */
internal sealed interface JsonValue {

    data class Str(val value: String) : JsonValue

    data class Num(val value: Double) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(name: String): JsonValue? = fields[name]

        fun str(name: String): String? = (fields[name] as? Str)?.value

        fun int(name: String): Int? = (fields[name] as? Num)?.value
            ?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }
            ?.toInt()

        fun float(name: String): Float? =
            (fields[name] as? Num)?.value?.takeIf { it.isFinite() }?.toFloat()

        fun arr(name: String): List<JsonValue>? = (fields[name] as? Arr)?.items

        fun obj(name: String): Obj? = fields[name] as? Obj

        /** Field names this build does not know. What the importer reports. */
        fun unknown(known: Set<String>): List<String> = fields.keys.filter { it !in known }
    }
}

/**
 * A strict JSON reader with hard limits, and no dependency.
 *
 * ## Why this is written out rather than taken off the shelf
 *
 * Two reasons, and the second is the real one.
 *
 * `org.json` is on the platform but is a stub on a plain JVM, where every
 * method throws *"not mocked"*. The whole point of the workspace format is that
 * a hostile file can be thrown at it in a unit test, and a parser that only
 * exists on a device is a parser nobody will test that way.
 *
 * And the limits below are **the security model**, not a nicety. A workspace
 * file is a file from a stranger, and every number in it sizes an allocation:
 * how many surfaces, how many rectangles, how long a name is. `org.json` will
 * happily build a ten-thousand-deep tree from ten kilobytes of `[[[[[`, and by
 * the time the caller can refuse it, it has already been built. Enforcing depth
 * and node count *while parsing* is only possible from inside the parser.
 *
 * ## It never throws
 *
 * [parse] returns null for anything it will not accept, which is the same
 * contract `DockCodec.decode` has and for the same reason: the caller has a
 * default, and a crash on opening a file somebody sent you is the worst of the
 * available answers.
 */
internal object Json {

    /**
     * A string as a JSON string literal, escaped.
     *
     * Here rather than in [WorkspaceJson] because there are two writers now and
     * an escaper that is nearly the same in two files is an escaper that is
     * wrong in one of them.
     */
    fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * A string on its way to a `Text`, with the characters that do not belong
     * on a label taken out.
     *
     * Control characters, and the bidirectional overrides — those are how a
     * name that reads *"Sketcher"* on screen is something else in the file, and
     * a list of names is exactly the sort of place that trick is aimed at.
     */
    fun clean(text: String): String = buildString {
        for (c in text) {
            if (c < ' ' || c == '\u007F') continue
            // The bidirectional overrides and isolates: how a name that
            // reads "Sketcher" on screen is something else in the file.
            if (c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069') continue
            append(c)
        }
    }.trim()

    /**
     * A workspace is a few hundred numbers. Anything larger is not a mistake.
     *
     * Measured in characters rather than bytes, which is stricter than the
     * 64 KiB the plan names for any file that is not pure ASCII — and stricter
     * is the right side to be wrong on for a limit whose job is to bound an
     * allocation.
     */
    const val MAX_CHARS = 64 * 1024

    /** Deeper than the format goes, with room to spare. Stops `[[[[[[[`. */
    const val MAX_DEPTH = 16

    /** Every value in the tree counts. Stops ten kilobytes of `1,1,1,1,…`. */
    const val MAX_NODES = 8192

    /** A name, a description, an id. Longer than this is not a name. */
    const val MAX_STRING = 4096

    /** Null when [text] is not JSON, or is more of it than anyone should send. */
    fun parse(text: String): JsonValue? {
        if (text.length > MAX_CHARS) return null
        val reader = Reader(text)
        val value = reader.value(depth = 0) ?: return null
        reader.spaces()
        // Trailing junk is a truncated or concatenated file, not a value with
        // something after it. Refusing is what stops the second half of a
        // corrupted download being read as the whole of it.
        if (!reader.done) return null
        return value
    }

    private class Reader(private val text: String) {
        private var at = 0
        private var nodes = 0

        val done: Boolean get() = at >= text.length

        fun spaces() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(depth: Int): JsonValue? {
            if (depth > MAX_DEPTH) return null
            if (++nodes > MAX_NODES) return null
            spaces()
            if (done) return null
            return when (text[at]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()?.let { JsonValue.Str(it) }
                't' -> literal("true")?.let { JsonValue.Bool(true) }
                'f' -> literal("false")?.let { JsonValue.Bool(false) }
                'n' -> literal("null")?.let { JsonValue.Null }
                else -> number()
            }
        }

        private fun obj(depth: Int): JsonValue? {
            at++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            spaces()
            if (!done && text[at] == '}') { at++; return JsonValue.Obj(fields) }
            while (true) {
                spaces()
                if (done || text[at] != '"') return null
                val name = string() ?: return null
                spaces()
                if (done || text[at] != ':') return null
                at++
                val v = value(depth + 1) ?: return null
                // A duplicated key is a file written by two hands. The first
                // wins, the same way a duplicated bar does in DockCodec: it is
                // the answer that does not depend on how far the reader got.
                fields.putIfAbsent(name, v)
                spaces()
                if (done) return null
                when (text[at]) {
                    ',' -> at++
                    '}' -> { at++; return JsonValue.Obj(fields) }
                    else -> return null
                }
            }
        }

        private fun arr(depth: Int): JsonValue? {
            at++ // [
            val items = ArrayList<JsonValue>()
            spaces()
            if (!done && text[at] == ']') { at++; return JsonValue.Arr(items) }
            while (true) {
                items += value(depth + 1) ?: return null
                spaces()
                if (done) return null
                when (text[at]) {
                    ',' -> at++
                    ']' -> { at++; return JsonValue.Arr(items) }
                    else -> return null
                }
            }
        }

        private fun string(): String? {
            at++ // "
            val out = StringBuilder()
            while (true) {
                if (done) return null
                if (out.length > MAX_STRING) return null
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (done) return null
                        when (val e = text[at++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) return null
                                val hex = text.substring(at, at + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                at += 4
                                out.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    // A raw control character inside a string is not legal JSON
                    // and is the shape of a file that has been cut in half.
                    c < ' ' -> return null
                    else -> out.append(c)
                }
            }
        }

        private fun number(): JsonValue? {
            val start = at
            if (!done && text[at] == '-') at++
            while (!done && (text[at].isDigit() || text[at] in ".eE+-")) at++
            if (at == start) return null
            val d = text.substring(start, at).toDoubleOrNull() ?: return null
            // NaN and the infinities have no JSON spelling, so a number that
            // reaches one got there by overflow.
            if (!d.isFinite()) return null
            return JsonValue.Num(d)
        }

        private fun literal(word: String): Unit? {
            if (!text.startsWith(word, at)) return null
            at += word.length
            return Unit
        }
    }
}

/**
 * A field read as a label: cleaned, cut to [max], and null when there is
 * nothing left of it.
 *
 * An extension here rather than a private helper in each reader, because both
 * of them are reading a name somebody else typed and the rules for that are one
 * set of rules. See [Json.clean].
 */
internal fun JsonValue.Obj.text(name: String, max: Int): String? =
    str(name)?.let(Json::clean)?.take(max)?.takeIf { it.isNotEmpty() }
