package ai.hindidub.engine.audio

/**
 * Growable primitive float buffer.
 *
 * v4 collected samples into `ArrayList<Float>`. Every sample is then a boxed object: a
 * 24-minute stereo episode is ~69 M boxed Floats, roughly a gigabyte of garbage on a phone
 * with 4 GB. This class appends into a FloatArray and doubles when full — the same data in
 * ~280 MB less memory and without a GC storm.
 */
class FloatSink(initialCapacity: Int = 1 shl 15) {

    private var buf = FloatArray(initialCapacity.coerceAtLeast(1024))
    var size: Int = 0
        private set

    fun add(v: Float) {
        if (size == buf.size) grow(size + 1)
        buf[size++] = v
    }

    fun addAll(src: FloatArray, count: Int = src.size) {
        if (size + count > buf.size) grow(size + count)
        System.arraycopy(src, 0, buf, size, count)
        size += count
    }

    /** Adds [count] interleaved frames of [channels], downmixing to mono. */
    fun addDownmixed(src: FloatArray, count: Int, channels: Int) {
        if (channels <= 1) {
            addAll(src, count)
            return
        }
        if (size + count > buf.size) grow(size + count)
        var i = 0
        for (f in 0 until count) {
            var acc = 0f
            for (c in 0 until channels) acc += src[i++]
            buf[size++] = acc / channels
        }
    }

    fun toArray(): FloatArray = buf.copyOf(size)

    private fun grow(min: Int) {
        var cap = buf.size
        while (cap < min) cap = cap shl 1
        buf = buf.copyOf(cap)
    }
}
