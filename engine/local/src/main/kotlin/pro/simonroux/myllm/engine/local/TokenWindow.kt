package pro.simonroux.myllm.engine.local

/**
 * Growable primitive int buffer holding the tokens currently in the KV cache.
 *
 * A plain `IntArray + token` in the decode loop would reallocate and copy the
 * whole history on every single token, which is quadratic in the length of the
 * reply. Capacity doubling keeps it amortised constant, and staying on IntArray
 * avoids boxing a few thousand Integers per answer.
 */
internal class TokenWindow(initialCapacity: Int = 1024) {

    private var data = IntArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    operator fun get(index: Int): Int = data[index]

    fun append(token: Int) {
        ensureCapacity(size + 1)
        data[size++] = token
    }

    fun reset(tokens: IntArray) {
        ensureCapacity(tokens.size)
        tokens.copyInto(data, 0)
        size = tokens.size
    }

    fun clear() {
        size = 0
    }

    /** Length of the shared leading run with [other]. */
    fun commonPrefixLength(other: IntArray, limit: Int): Int {
        var i = 0
        val max = minOf(size, other.size, limit)
        while (i < max && data[i] == other[i]) i++
        return i
    }

    /** Drops everything from [newSize] onward, mirroring a KV cache trim. */
    fun truncateTo(newSize: Int) {
        size = newSize.coerceIn(0, size)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var capacity = data.size
        while (capacity < required) capacity *= 2
        data = data.copyOf(capacity)
    }
}
