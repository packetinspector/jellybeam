package tv.jellybeam.updates

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.nio.file.Files
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProfileValidationTest {
    @Test fun `v015 dex section exposes APK-bound checksums`() {
        val dex = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putInt(1234).putInt(1).putInt(5).putShort(11).put("classes.dex".toByteArray()).array()
        val profile = ByteBuffer.allocate(28 + dex.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("pro\u0000015\u0000".toByteArray()).putInt(1).putInt(0).putInt(28).putInt(dex.size).putInt(0).put(dex).array()
        assertEquals(mapOf("classes.dex" to 1234L), profileDexChecksums(profile))
    }
    @Test fun `v010 compressed header exposes checksum`() {
        // One class id (2 bytes) and a 5-method 2-bit bitmap (2 bytes) follow the header.
        val body = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).putShort(11).putShort(1)
            .putInt(0).putInt(1234).putInt(5).put("classes.dex".toByteArray()).put(ByteArray(4)).array()
        val out = java.io.ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(body) }
        val packed = out.toByteArray()
        val header = ByteBuffer.allocate(17 + packed.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("pro\u0000010\u0000".toByteArray()).put(1).putInt(body.size).putInt(packed.size).put(packed).array()
        assertEquals(mapOf("classes.dex" to 1234L), profileDexChecksums(header))
    }
    @Test fun `invalid section bounds reject before decompression`() {
        val profile = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).put("pro\u0000015\u0000".toByteArray())
            .putInt(1).putInt(0).putInt(100).putInt(1).putInt(0).array()
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(profile) }
    }
}

class ProfileLayoutTest {
    private class Dex(val name: String, val checksum: Long, val hotBytes: Int, val classes: Int, val methodIds: Int) {
        val dataBytes get() = hotBytes + classes * 2 + (methodIds * 2 + 7) / 8
    }
    private fun header(d: Dex) = ByteBuffer.allocate(16 + d.name.length).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(d.name.length.toShort()).putShort(d.classes.toShort()).putInt(d.hotBytes).putInt(d.checksum.toInt())
        .putInt(d.methodIds).put(d.name.toByteArray()).array()
    private fun data(d: Dex, fill: Byte) = ByteArray(d.dataBytes) { fill }
    private fun container(count: Int, body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(body) }
        val packed = out.toByteArray()
        return ByteBuffer.allocate(17 + packed.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("pro\u0000010\u0000".toByteArray()).put(count.toByte()).putInt(body.size).putInt(packed.size).put(packed).array()
    }
    private val first = Dex("classes.dex", 0xA0000001L, 7, 3, 10)
    private val second = Dex("classes2.dex", 0xB0000002L, 5, 2, 9)
    // ART V0_1_0_P (androidx ProfileTranscoder.createCompressibleBody): every dex header, then every dex's data.
    private fun headersThenData(count: Int = 2) = (listOf(first, second).take(count).map(::header) +
        listOf(data(first, 1), data(second, 2)).take(count)).reduce(ByteArray::plus)

    @Test fun `v010 two dex entries with data regions expose both checksums`() {
        val parsed = profileDexChecksums(container(2, headersThenData()))
        assertEquals(mapOf("classes.dex" to 0xA0000001L, "classes2.dex" to 0xB0000002L), parsed)
    }
    @Test fun `v010 interleaved header and data is not the P layout`() {
        val body = header(first) + data(first, 1) + header(second) + data(second, 2)
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(container(2, body)) }
    }
    @Test fun `v010 data region size must match the headers`() {
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(container(2, headersThenData() + byteArrayOf(0))) }
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(container(2, headersThenData().copyOf(headersThenData().size - 1))) }
    }
    @Test fun `v010 count larger than present headers is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(container(3, headersThenData())) }
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(container(0, ByteArray(0))) }
    }
    @Test fun `v010 duplicate dex keys are rejected`() {
        val body = header(first) + header(first) + data(first, 1) + data(first, 1)
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(container(2, body)) }
    }
    @Test fun `truncated or unknown profiles are rejected`() {
        val good = container(2, headersThenData())
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(good.copyOf(good.size - 3)) }
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(good.copyOf(12)) }
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(ByteArray(4)) }
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums("pro\u0000099\u0000".toByteArray() + ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums("nope0000".toByteArray() + ByteArray(32)) }
    }
    @Test fun `declared expansion that disagrees with the stream is rejected`() {
        val good = container(2, headersThenData())
        ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).putInt(9, 64 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(good) }
        val lie = container(2, headersThenData())
        ByteBuffer.wrap(lie).order(ByteOrder.LITTLE_ENDIAN).putInt(9, 10)
        assertThrows(IllegalArgumentException::class.java) { profileDexChecksums(lie) }
    }

    private fun zip(entries: Map<String, ByteArray>): File {
        val file = Files.createTempFile("profile", ".zip").toFile().also { it.deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
        }
        return file
    }
    private val expected = mapOf("classes.dex" to 0xA0000001L, "classes2.dex" to 0xB0000002L)

    @Test fun `profile bundle binds to APK dex checksums`() {
        validateProfile(expected, zip(mapOf("primary.prof" to container(2, headersThenData()), "primary.profm" to ByteArray(4))))
        assertThrows(IllegalArgumentException::class.java) {
            validateProfile(expected + ("classes2.dex" to 1L), zip(mapOf("primary.prof" to container(2, headersThenData()))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateProfile(mapOf("classes.dex" to 0xA0000001L), zip(mapOf("primary.prof" to container(2, headersThenData()))))
        }
    }
    @Test fun `profile bundle rejects unexpected missing or malformed members`() {
        val prof = container(2, headersThenData())
        assertThrows(IllegalArgumentException::class.java) { validateProfile(expected, zip(mapOf("primary.prof" to prof, "extra.bin" to ByteArray(1)))) }
        assertThrows(IllegalArgumentException::class.java) { validateProfile(expected, zip(mapOf("primary.profm" to ByteArray(4)))) }
        assertThrows(IllegalArgumentException::class.java) { validateProfile(expected, zip(emptyMap())) }
        assertThrows(IllegalArgumentException::class.java) { validateProfile(expected, zip(mapOf("primary.prof" to prof.copyOf(20)))) }
    }
    @Test fun `bounded read enforces missing oversized and exact sizes`() {
        ZipFile(zip(mapOf("a" to ByteArray(10), "b" to ByteArray(11)))).use { z ->
            assertEquals(10, boundedRead(z, "a", 10).size)
            assertThrows(IllegalArgumentException::class.java) { boundedRead(z, "b", 10) }
            assertThrows(IllegalArgumentException::class.java) { boundedRead(z, "missing", 10) }
        }
    }
    @Test fun `apk contents come from one pass`() {
        val file = zip(mapOf("AndroidManifest.xml" to ByteArray(8), "classes.dex" to ByteArray(3), "classes2.dex" to ByteArray(2), "classes1.dex" to ByteArray(1),
            "lib/arm64-v8a/libjellybeam_core.so" to ByteArray(1), "lib/x86_64/libjellybeam_core.so" to ByteArray(1), "lib/x86/other.so" to ByteArray(1)))
        ZipFile(file).use { z ->
            val contents = apkContents(z)
            assertEquals(setOf("classes.dex", "classes2.dex"), contents.dexCrc.keys)
            assertEquals(listOf("arm64-v8a", "x86_64"), contents.abis)
            assertEquals(8, contents.manifest.size)
        }
    }
}

class ManifestMinSdkTest {
    private fun manifest(type: Int = 0x10, withSdk: Boolean = true, minSdk: Int = 23): ByteArray {
        val strings = listOf("uses-sdk", "minSdkVersion")
        val pool = java.io.ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        for (s in strings) { offsets += pool.size(); pool.write(s.length); pool.write(s.length); pool.write(s.toByteArray()); pool.write(0) }
        while (pool.size() % 4 != 0) pool.write(0)
        val poolSize = 28 + strings.size * 4 + pool.size()
        val chunk1 = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(28).putInt(poolSize)
            .putInt(strings.size).putInt(0).putInt(256).putInt(28 + strings.size * 4).putInt(0)
        offsets.forEach { chunk1.putInt(it) }
        chunk1.put(pool.toByteArray())
        val element = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).putShort(0x102).putShort(16).putInt(56)
            .putInt(1).putInt(-1).putInt(-1).putInt(if (withSdk) 0 else 1)
            .putShort(20).putShort(20).putShort(1).putShort(0).putShort(0).putShort(0)
            .putInt(-1).putInt(1).putInt(-1).putShort(8).put(0).put(type.toByte()).putInt(minSdk)
        val total = 8 + poolSize + 56
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).putShort(3).putShort(8).putInt(total)
            .put(chunk1.array()).put(element.array()).array()
    }
    @Test fun `reads the declared minimum`() = assertEquals(23, manifestMinSdk(manifest()))
    @Test fun `missing uses-sdk means API 1`() = assertEquals(1, manifestMinSdk(manifest(withSdk = false)))
    @Test fun `non-integer or non-positive values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { manifestMinSdk(manifest(type = 0x03)) }
        assertThrows(IllegalArgumentException::class.java) { manifestMinSdk(manifest(minSdk = 0)) }
    }
    @Test fun `truncated or inconsistent manifests are rejected`() {
        val good = manifest()
        assertThrows(Exception::class.java) { manifestMinSdk(good.copyOf(good.size - 4)) }
        assertThrows(Exception::class.java) { manifestMinSdk(good.copyOf(6)) }
        assertThrows(Exception::class.java) { manifestMinSdk(ByteArray(0)) }
        val bad = good.copyOf(); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bad.size + 1)
        assertThrows(Exception::class.java) { manifestMinSdk(bad) }
        val wrongType = good.copyOf(); ByteBuffer.wrap(wrongType).order(ByteOrder.LITTLE_ENDIAN).putShort(0, 2)
        assertThrows(Exception::class.java) { manifestMinSdk(wrongType) }
    }
}
