package tv.jellybeam.data

import uniffi.jellybeam_core.ImageKind

/**
 * The per-account-epoch half of an image URL, from the core's `image_url_prefix`: [token] is a
 * credential, so this class never prints it.
 */
class ImageUrlBase(val epoch: ULong, baseUrl: String, token: String) {
    private val head = "$baseUrl/Items/"
    private val tail = "&ApiKey=${ImageUrls.percentEncode(token)}"

    fun url(itemId: String, kind: ImageKind, tag: String, maxWidth: UInt): String =
        ImageUrls.build(head, tail, itemId, kind, tag, maxWidth)

    override fun toString() = "ImageUrlBase(epoch=$epoch)"
}

/**
 * Pure Kotlin twin of the core's `jellyfin_api::JellyfinClient::image_url`, so a card's art URL
 * costs no FFI call (docs/10). The format and encoding must stay byte-identical to the core's;
 * `ImageUrlsTest` pins it against that function's own test cases.
 */
object ImageUrls {
    // Without `quality` the server re-encodes near-lossless; backdrops render dimmed, so lower.
    private const val BACKDROP_QUALITY = 80
    private const val SHARP_QUALITY = 90

    internal fun build(head: String, tail: String, itemId: String, kind: ImageKind, tag: String, maxWidth: UInt): String {
        val (kindName, quality) = when (kind) {
            ImageKind.PRIMARY -> "Primary" to SHARP_QUALITY
            ImageKind.BACKDROP -> "Backdrop" to BACKDROP_QUALITY
            ImageKind.THUMB -> "Thumb" to SHARP_QUALITY
        }
        return buildString(head.length + tail.length + 96) {
            append(head)
            append(percentEncode(itemId))
            append("/Images/")
            append(kindName)
            append("?tag=")
            append(percentEncode(tag))
            append("&maxWidth=")
            append(maxWidth.toString())
            append("&quality=")
            append(quality)
            append("&format=Webp")
            append(tail)
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** The core's `percent_encode`: every UTF-8 byte outside `A-Za-z0-9-_.~` becomes `%XX`
     * (uppercase hex).
     */
    fun percentEncode(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size + 8)
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(HEX[b ushr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }
}
