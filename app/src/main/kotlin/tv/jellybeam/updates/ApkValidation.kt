package tv.jellybeam.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile
import uniffi.jellybeam_core.InstalledUpdateFacts

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

@Suppress("DEPRECATION")
internal fun installedFacts(context: Context): InstalledUpdateFacts {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info = context.packageManager.getPackageInfo(context.packageName, flags)
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    require(signatures?.size == 1)
    return InstalledUpdateFacts(context.packageName, versionCode(info).toULong(), Build.VERSION.SDK_INT.toUInt(),
        Build.SUPPORTED_ABIS.toList(), sha256(signatures!![0].toByteArray()))
}
@Suppress("DEPRECATION")
internal fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

internal data class VerifiedArchive(val packageName: String, val code: Long, val minSdk: Int, val signer: String, val abis: List<String>)
internal fun verifyArchive(context: Context, apk: File, profile: File?): VerifiedArchive {
    val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
        .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
    require(result.isVerified && result.signerCertificates.size == 1)
    val info = requireNotNull(context.packageManager.getPackageArchiveInfo(apk.path, 0))
    require(info.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0)
    // docs/26 §3: one ZIP pass yields the manifest, ABIs and DEX CRCs.
    val contents = ZipFile(apk).use(::apkContents)
    if (profile != null) validateProfile(contents.dexCrc, profile)
    require(contents.abis.any { it in Build.SUPPORTED_ABIS })
    return VerifiedArchive(info.packageName, versionCode(info), manifestMinSdk(contents.manifest),
        sha256(result.signerCertificates.single().encoded), contents.abis)
}
internal class ApkContents(val manifest: ByteArray, val abis: List<String>, val dexCrc: Map<String, Long>)
private val dexEntryName = Regex("classes([2-9]|[1-9][0-9]+)?\\.dex")
internal fun apkContents(zip: ZipFile): ApkContents {
    val abis = linkedSetOf<String>()
    val dex = linkedMapOf<String, Long>()
    for (entry in zip.entries()) {
        val name = entry.name
        if (name.startsWith("lib/") && name.endsWith("/libjellybeam_core.so")) abis += name.split('/')[1]
        else if (dexEntryName.matches(name)) dex[name] = entry.crc
    }
    return ApkContents(boundedRead(zip, "AndroidManifest.xml", 4 * 1024 * 1024), abis.toList(), dex)
}
internal fun boundedRead(zip: ZipFile, name: String, limit: Int): ByteArray {
    val entry = requireNotNull(zip.getEntry(name))
    require(entry.size in 0..limit.toLong())
    return zip.getInputStream(entry).use { input ->
        val bytes = input.readBytesLimited(limit)
        require(bytes.size.toLong() == entry.size)
        bytes
    }
}
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        require(out.size() + n <= limit)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
// docs/26 §4: read the signed binary manifest rather than trusting release metadata on API 23.
internal fun manifestMinSdk(bytes: ByteArray): Int {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 8 && b.getShort(0).toInt() == 3 && b.getInt(4) == bytes.size)
    var strings = emptyList<String>()
    var offset = 8
    while (offset + 8 <= bytes.size) {
        val type = b.getShort(offset).toInt() and 65535
        val size = b.getInt(offset + 4)
        require(size >= 8 && size <= bytes.size - offset)
        if (type == 1) {
            val count = b.getInt(offset + 8)
            val flags = b.getInt(offset + 16)
            val start = offset + b.getInt(offset + 20)
            require(count in 0..65536 && offset + 28 + count * 4 <= offset + size)
            strings = (0 until count).map { i ->
                var pos = start + b.getInt(offset + 28 + i * 4)
                require(pos in offset until offset + size)
                fun length(utf8: Boolean): Int {
                    val first = if (utf8) bytes[pos++].toInt() and 255 else (b.getShort(pos).toInt() and 65535).also { pos += 2 }
                    return if (first and (if (utf8) 128 else 32768) == 0) first else {
                        val next = if (utf8) bytes[pos++].toInt() and 255 else (b.getShort(pos).toInt() and 65535).also { pos += 2 }
                        ((first and (if (utf8) 127 else 32767)) shl (if (utf8) 8 else 16)) or next
                    }
                }
                val utf8 = flags and 256 != 0
                if (utf8) length(true)
                val len = length(utf8) * if (utf8) 1 else 2
                require(len >= 0 && pos + len <= offset + size)
                String(bytes, pos, len, if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            }
        } else if (type == 0x102) {
            require(size >= 36)
            val name = b.getInt(offset + 20)
            if (strings.getOrNull(name) == "uses-sdk") {
                val start = offset + 16 + (b.getShort(offset + 24).toInt() and 65535)
                val stride = b.getShort(offset + 26).toInt() and 65535
                val count = b.getShort(offset + 28).toInt() and 65535
                require(stride >= 20 && start >= offset && start.toLong() + stride.toLong() * count <= offset + size)
                for (i in 0 until count) {
                    val attr = start + i * stride
                    if (strings.getOrNull(b.getInt(attr + 4)) == "minSdkVersion") {
                        require(bytes[attr + 15].toInt() in 0x10..0x11)
                        return b.getInt(attr + 16).also { require(it > 0) }
                    }
                }
                return 1
            }
        }
        offset += size
    }
    return 1
}

// docs/26 §3: profiles can optimize only the DEX files in this verified APK.
internal fun validateProfile(expected: Map<String, Long>, dm: File) {
    ZipFile(dm).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size in 1..3 && entries.map { it.name }.toSet().size == entries.size)
        require(entries.all { it.name in setOf("primary.prof", "primary.profm", "manifest.json") && it.size in 0..(32 * 1024 * 1024L) })
        val dex = profileDexChecksums(boundedRead(zip, "primary.prof", 16 * 1024 * 1024))
        require(dex.isNotEmpty() && dex.all { expected[it.key] == it.value })
    }
}
internal fun profileDexChecksums(bytes: ByteArray): Map<String, Long> =
    try { parseProfileDex(bytes) } catch (e: IllegalArgumentException) { throw e }
    catch (e: Exception) { throw IllegalArgumentException("Malformed profile", e) }
private fun parseProfileDex(bytes: ByteArray): Map<String, Long> {
    require(bytes.size >= 8)
    val header = String(bytes.copyOfRange(0, 8), Charsets.ISO_8859_1)
    require(header.startsWith("pro\u0000"))
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun unpack(offset: Int, size: Int, expanded: Int): ByteArray {
        require(offset >= 0 && size >= 0 && offset.toLong() + size <= bytes.size && expanded in 0..(32 * 1024 * 1024))
        val data = bytes.copyOfRange(offset, offset + size)
        return if (expanded == 0) data else InflaterInputStream(data.inputStream()).use { it.readBytesLimited(expanded) }.also { require(it.size == expanded) }
    }
    val result = linkedMapOf<String, Long>()
    fun key(raw: String): String = raw.substringAfterLast('!').substringAfterLast(':').let { if (it.endsWith(".apk")) "classes.dex" else it }
    fun insert(name: String, checksum: Long) { require(result.put(key(name), checksum) == null) }
    when (header.substring(4)) {
        "010\u0000" -> {
            // ART V0_1_0_P: all dex headers first, then each dex's data (hot methods, classes, 2-bit method bitmap).
            require(bytes.size >= 17)
            val count = bytes[8].toInt() and 255
            require(count >= 1)
            val data = ByteBuffer.wrap(unpack(17, b.getInt(13), b.getInt(9))).order(ByteOrder.LITTLE_ENDIAN)
            var dataBytes = 0L
            repeat(count) {
                val len = data.short.toInt() and 65535
                val classes = data.short.toLong() and 65535
                val hotBytes = data.int.toLong() and 0xffffffffL
                val sum = data.int.toLong() and 0xffffffffL
                val methodIds = data.int.toLong() and 0xffffffffL
                require(len <= data.remaining())
                insert(String(ByteArray(len).also { data.get(it) }, Charsets.UTF_8), sum)
                dataBytes += hotBytes + classes * 2 + (methodIds * 2 + 7) / 8
            }
            require(data.remaining().toLong() == dataBytes)
        }
        "015\u0000" -> {
            val count = b.getInt(8)
            require(count in 1..8 && 12 + count * 16 <= bytes.size)
            var found = false
            repeat(count) { i ->
                val start = 12 + i * 16
                if (b.getInt(start) == 0) {
                    require(!found); found = true
                    val data = ByteBuffer.wrap(unpack(b.getInt(start + 4), b.getInt(start + 8), b.getInt(start + 12))).order(ByteOrder.LITTLE_ENDIAN)
                    val dexCount = data.short.toInt() and 65535
                    require(dexCount in 1..256)
                    repeat(dexCount) {
                        val sum = data.int.toLong() and 0xffffffffL
                        data.int; data.int
                        val len = data.short.toInt() and 65535
                        insert(String(ByteArray(len).also { data.get(it) }, Charsets.UTF_8), sum)
                    }
                    require(!data.hasRemaining())
                }
            }
            require(found)
        }
        else -> error("Unsupported profile format")
    }
    return result
}
