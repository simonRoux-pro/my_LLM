package pro.simonroux.myllm.engine.local

/**
 * Turns a stream of token byte fragments into a stream of valid text.
 *
 * A single token frequently carries only part of a multi-byte character, and
 * accents and emoji are exactly where that happens. Decoding each fragment on
 * its own would emit replacement characters, so incomplete tail sequences are
 * held back until the bytes that finish them arrive.
 */
internal class Utf8Accumulator {

    private var buffer = ByteArray(0)

    fun append(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        buffer = if (buffer.isEmpty()) bytes.copyOf() else buffer + bytes

        val complete = completeLength(buffer)
        if (complete == 0) return ""

        val text = String(buffer, 0, complete, Charsets.UTF_8)
        buffer = if (complete == buffer.size) ByteArray(0) else buffer.copyOfRange(complete, buffer.size)
        return text
    }

    /** Emits whatever is left, replacement characters included. Call once at the end. */
    fun flush(): String {
        if (buffer.isEmpty()) return ""
        val text = String(buffer, Charsets.UTF_8)
        buffer = ByteArray(0)
        return text
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    /**
     * Length of the longest prefix that ends on a character boundary.
     *
     * Walks back over continuation bytes to find the last lead byte, then checks
     * whether its full sequence is present.
     */
    private fun completeLength(bytes: ByteArray): Int {
        var index = bytes.size
        var stepsBack = 0

        while (index > 0 && stepsBack < 4) {
            val byte = bytes[index - 1].toInt() and 0xFF

            // 10xxxxxx: a continuation byte, keep walking back to the lead byte.
            if (byte and 0xC0 == 0x80) {
                index--
                stepsBack++
                continue
            }

            val needed = when {
                byte and 0x80 == 0x00 -> 1
                byte and 0xE0 == 0xC0 -> 2
                byte and 0xF0 == 0xE0 -> 3
                byte and 0xF8 == 0xF0 -> 4
                // Not a valid lead byte. Nothing is gained by holding it, let
                // the decoder substitute and move on.
                else -> 1
            }
            val available = bytes.size - (index - 1)
            return if (available >= needed) bytes.size else index - 1
        }

        // Four or more continuation bytes with no lead byte means the stream is
        // malformed; flushing is better than growing the buffer forever.
        return if (stepsBack >= 4) bytes.size else 0
    }
}
