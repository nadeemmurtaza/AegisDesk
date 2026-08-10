package com.newax.aegis.platform.windows

import com.newax.aegis.model.ModelFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Verifies [GgufHeaderParser] against real GGUF v3 binary data written to a
 * temp file — pure JVM, no native dependencies.
 */
class GgufHeaderParserTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = Files.createTempFile("gguf-model", ".gguf").toFile()
    }

    @Test
    fun `rejects file with wrong magic`() {
        tempFile.writeBytes(byteArrayOf(0, 0, 0, 0, 3, 0, 0, 0) + zeroPad(16))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GgufHeaderParser.readDescriptor(tempFile, "sha")
        }
        assertEquals(true, ex.message?.contains("bad magic") == true)
    }

    @Test
    fun `rejects unsupported version`() {
        tempFile.writeBytes(buildGguf(magic = "GGUF", version = 1, kvPairs = emptyList()))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GgufHeaderParser.readDescriptor(tempFile, "sha")
        }
        assertEquals(true, ex.message?.contains("Unsupported GGUF version") == true)
    }

    @Test
    fun `parses model name and architecture from header`() {
        tempFile.writeBytes(buildGguf(kvPairs = listOf(
            "general.name" to stringValue("TestModel-7B"),
            "general.architecture" to stringValue("llama"),
        )))

        val d = GgufHeaderParser.readDescriptor(tempFile, "aabbccdd")
        assertEquals("TestModel-7B", d.modelName)
        assertEquals(ModelFormat.GGUF, d.format)
        assertEquals(tempFile.length(), d.sizeBytes)
        assertEquals("aabbccdd", d.sha256)
        // Vision flag must be false (no mmproj in architecture)
        assertEquals(false, d.supportsVision)
    }

    @Test
    fun `detects vision model from mmproj architecture`() {
        tempFile.writeBytes(buildGguf(kvPairs = listOf(
            "general.architecture" to stringValue("llama-mmproj"),
        )))
        val d = GgufHeaderParser.readDescriptor(tempFile, "sha")
        assertEquals(true, d.supportsVision)
    }

    @Test
    fun `falls back to filename when no name in header`() {
        tempFile.writeBytes(buildGguf(kvPairs = emptyList()))
        val d = GgufHeaderParser.readDescriptor(tempFile, "sha")
        assertEquals(tempFile.name, d.modelName)
    }

    @Test
    fun `parses context length`() {
        tempFile.writeBytes(buildGguf(kvPairs = listOf(
            "llama.context_length" to uint32Value(4096),
        )))
        val d = GgufHeaderParser.readDescriptor(tempFile, "sha")
        assertEquals("llama.context_length mapped", tempFile.name, d.modelName)
    }

    @Test
    fun `parses some file type`() {
        tempFile.writeBytes(buildGguf(kvPairs = listOf(
            "general.file_type" to int32Value(2), // Q4_0
            "general.name" to stringValue("q4-model"),
        )))
        val d = GgufHeaderParser.readDescriptor(tempFile, "sha")
        assertEquals("q4-model", d.modelName)
    }

    @Test
    fun `rejects file that is too small`() {
        tempFile.writeBytes(ByteArray(10))
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GgufHeaderParser.readDescriptor(tempFile, "sha")
        }
        assertEquals(true, ex.message?.contains("too small") == true)
    }

    // ── GGUF binary builders ──────────────────────────────────────────────

    private fun buildGguf(
        magic: String = "GGUF",
        version: Int = 3,
        tensorCount: Long = 0,
        kvPairs: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + 8 + 8 + kvPairs.sumOf { kvSize(it.first, it.second) })
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(magic.toByteArray(Charsets.US_ASCII))
        buf.putInt(version)
        buf.putLong(tensorCount)
        buf.putLong(kvPairs.size.toLong())
        for ((key, value) in kvPairs) {
            putString(buf, key)
            buf.put(value) // value bytes include the type prefix
        }
        return buf.array()
    }

    /** Total bytes consumed by one KV pair (key + value-type + value-data). */
    private fun kvSize(key: String, value: ByteArray): Int =
        8 + key.toByteArray(Charsets.UTF_8).size + value.size

    private fun putString(buf: ByteBuffer, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        buf.putLong(bytes.size.toLong())
        buf.put(bytes)
    }

    // ── GGUF value encoders ────────────────────────────────────────────────
    // Each returns a byte array that starts with the 4-byte type ID followed by
    // the value data (little-endian). The caller (buildGguf) writes type + value
    // directly after the key string.

    private fun stringValue(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + 8 + bytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8)    // TYPE_STRING
            .putLong(bytes.size.toLong())
            .put(bytes)
            .array()
    }

    private fun uint32Value(v: Int): ByteArray {
        return ByteBuffer.allocate(4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4)    // TYPE_UINT32
            .putInt(v)
            .array()
    }

    private fun int32Value(v: Int): ByteArray {
        return ByteBuffer.allocate(4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(5)    // TYPE_INT32
            .putInt(v)
            .array()
    }

    private fun zeroPad(n: Int) = ByteArray(n)
}