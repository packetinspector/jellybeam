package tv.jellybeam.player

import java.io.File
import java.io.IOException
import tv.jellybeam.updates.sha256

/**
 * A directory of small cached files, each named by a hash of its key so no URL or token reaches disk.
 * A hit marks its file used; a write lands whole (temp file, then rename) and trims to the most recently
 * used files within [maxFiles] and [maxBytes], deleting temp files older than [stalePartMs]. Blocking:
 * callers run it off the main thread. [dir] is resolved on first use.
 */
internal class LruFileDir(
    dir: () -> File,
    private val suffix: String,
    private val maxBytes: Long,
    private val maxFiles: Int = Int.MAX_VALUE,
    private val stalePartMs: Long = 0L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dir by lazy(dir)

    fun file(name: String): File = File(dir, name + suffix)

    /** The named file's bytes, marked recently used; null when absent or unreadable, and a file over [limit] is deleted. */
    fun read(name: String, limit: Long = Long.MAX_VALUE): ByteArray? {
        val file = file(name)
        if (!file.isFile) return null
        if (file.length() > limit) {
            file.delete()
            return null
        }
        file.setLastModified(now())
        return try {
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    /** Throws the write's [IOException] after removing its temp file. */
    fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File.createTempFile("part-", PART_SUFFIX, dir)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file(name))) tmp.delete()
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        trim()
    }

    fun delete(name: String) {
        file(name).delete()
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.isFile } ?: return
        val at = now()
        files.filter { it.name.endsWith(PART_SUFFIX) && at - it.lastModified() > stalePartMs }.forEach { it.delete() }
        var count = 0
        var bytes = 0L
        for (file in files.filter { it.name.endsWith(suffix) }.sortedByDescending { it.lastModified() }) {
            count++
            bytes += file.length()
            if (count > maxFiles || bytes > maxBytes) file.delete()
        }
    }

    companion object {
        /** The hex name for [key]: its SHA-256, cut to [chars]. */
        fun nameFor(key: String, chars: Int = 64): String = sha256(key.toByteArray()).take(chars)
    }
}

private const val PART_SUFFIX = ".part"
