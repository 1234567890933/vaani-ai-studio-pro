package ai.hindidub.cloud

/**
 * Extracts a JSON array of strings from a model's reply.
 *
 * Cloud models wrap JSON in prose or ```json fences, and sometimes return a numbered list
 * instead of JSON. v4 parsed with `org.json`, which is fine on Android but made the parser
 * untestable and brittle against fences. This is a small hand-written scanner:
 *  * tolerates leading/trailing prose and code fences;
 *  * handles `\"`, `\\`, `\/`, `\n`, `\t`, `\uXXXX`;
 *  * skips nested objects and non-string values instead of throwing;
 *  * terminates on truncated input instead of looping.
 *
 * It is deliberately *not* a general JSON parser — only what a translation reply contains.
 * Verified by tools/jvmtest/EngineSelfTest.kt (plain array, fences, prose, escapes,
 * unicode, nested objects, trailing commas, truncated input).
 */
object JsonArrays {

    fun parseStrings(raw: String): List<String> {
        val start = raw.indexOf('[')
        if (start < 0) return emptyList()

        val out = ArrayList<String>()
        var i = start + 1
        var objectDepth = 0

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == ']' && objectDepth == 0 -> return out
                c == ']' -> i++
                c == '{' -> {
                    objectDepth++
                    i++
                }
                c == '}' -> {
                    if (objectDepth > 0) objectDepth--
                    i++
                }
                c == '"' && objectDepth > 0 -> {
                    i = skipString(raw, i)
                }
                c == '"' -> {
                    val parsed = readString(raw, i)
                    // a string that never closed means truncated output: drop it rather
                    // than injecting half a line into the dub
                    if (parsed.closed) out.add(parsed.text)
                    i = parsed.next
                }
                else -> i++
            }
        }
        return out
    }

    /** Advances past a quoted string starting at [from] (which must be a quote). */
    private fun skipString(raw: String, from: Int): Int {
        var i = from + 1
        while (i < raw.length) {
            when {
                raw[i] == '\\' && i + 1 < raw.length -> i += 2
                raw[i] == '"' -> return i + 1
                else -> i++
            }
        }
        return i
    }

    private class Parsed(val text: String, val next: Int, val closed: Boolean)

    /** Reads a quoted string starting at [from]. */
    private fun readString(raw: String, from: Int): Parsed {
        val sb = StringBuilder()
        var i = from + 1
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val e = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        val code = hex.toIntOrNull(16) ?: 63
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            } else if (c == '"') {
                return Parsed(sb.toString(), i + 1, true)
            } else {
                sb.append(c)
                i++
            }
        }
        return Parsed(sb.toString(), i, false)
    }
}
