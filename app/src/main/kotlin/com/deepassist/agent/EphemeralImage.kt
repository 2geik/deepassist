package com.deepassist.agent

/**
 * A screenshot or photo that exists only for one request. The user's rule:
 * images are never saved anywhere, so every image goes through this holder
 * and is zeroed on [close] — use it in `use { }` around the request.
 */
class EphemeralImage(bytes: ByteArray) : AutoCloseable {

    private var data: ByteArray? = bytes

    val bytes: ByteArray
        get() = data ?: error("image already wiped")

    override fun close() {
        data?.fill(0)
        data = null
    }
}
