package com.newax.aegis.platform.windows

import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Minimal GGUF v3 binary header parser. Extracts model name, architecture, context
 * length, and file-type metadata from the header so [GgufModelProvider] can build
 * an accurate [ModelDescriptor] without loading the full model.
 *
 * GGUF format reference: https://github.com/ggerganov/ggml/blob/master/docs/gguf.md
 *
 * This parser is pure JVM — no native dependencies — and is testable from synthetic
 * byte arrays in standard JUnit tests.
 */
object GgufHeaderParser {

    private const val GGUF_MAGIC = "GGUF"
    private const val MIN_HEADER_BYTES = 24L // magic(4) + version(4) + tensorCount(8) + kvCount(8)

    // GGUF value type IDs (gguf.h enum gguf_type)
    private const val TYPE_UINT8   = 0
    private const val TYPE_INT8    = 1
    private const val TYPE_UINT16  = 2
    private const val TYPE_INT16   = 3
    private const val TYPE_UINT32  = 4
    private const val TYPE_INT32   = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL    = 7
    private const val TYPE_STRING  = 8
    private const val TYPE_ARRAY   = 9
    private const val TYPE_UINT64  = 10
    private const val TYPE_INT64   = 11
    private const val TYPE_FLOAT64 = 12

    // Well-known metadata keys
    private const val KEY_NAME         = "general.name"
    private const val KEY_ARCH         = "general.architecture"
    private const val KEY_FILE_TYPE    = "general.file_type"
    private const val KEY_CONTEXT_LLM  = "llama.context_length"
    private const val KEY_CONTEXT_GEN  = "general.context_length"
    private const val KEY_SIZE_LABEL   = "general.size_label"
    private const val KEY_DESCRIPTION  = "general.description"

    /**
     * Reads the GGUF header from [file], validates the magic, and returns a
     * [ModelDescriptor] populated with the metadata found in the header.
     * [sha256] is passed in from the importer or caller (the parser does not
     * compute it — that is the importer's job).
     */
    fun readDescriptor(file: File, sha256: String): ModelDescriptor {
        require(file.isFile) { "GGUF file not found: $file" }
        require(file.length() >= MIN_HEADER_BYTES) {
            "File too small for GGUF header: ${file.length()} bytes"
        }

        val meta = parse(file)

        return ModelDescriptor(
            modelName     = meta.name.ifBlank { file.name },
            format        = ModelFormat.GGUF,
            sizeBytes     = file.length(),
            sha256        = sha256,
            supportsVision = meta.architecture.contains("mmproj") ||
                             meta.architecture.contains("multimodal") ||
                             meta.architecture.contains("vision"),
            ramEstimateBytes = file.length() + 2_000_000_000L, // model size + ~2 GB overhead
        )
    }

    /**
     * Low-level GGUF metadata extraction. Returns the raw values found in header
     * KV pairs. Callers (like [readDescriptor]) map these onto the contract types.
     */
    data class GgufMetadata(
        val name: String,
        val architecture: String,
        val fileType: Int,
        val contextLength: Long,
        val sizeLabel: String,
        val description: String,
    )

    /**
     * Parse raw GGUF metadata from [file]. This is package-visible for testing.
     */
    fun parse(file: File): GgufMetadata {
        val buf = readHeaderBuffer(file)

        // ── Magic ──────────────────────────────────────────────────────────
        val magic = ByteArray(4)
        buf.get(magic)
        val magicStr = String(magic, Charsets.US_ASCII)
        require(magicStr == GGUF_MAGIC) { "Not a GGUF file: bad magic '$magicStr'" }

        // ── Version ────────────────────────────────────────────────────────
        // We accept v2+ (v1 is obsolete); the format parsing below is v3 but
        // the KV layout is backward-compatible for the keys we query.
        val version = buf.getInt().toLong() and 0xFFFFFFFFL
        require(version in 2..3) { "Unsupported GGUF version $version — v2/v3 expected" }

        // ── Counts ─────────────────────────────────────────────────────────
        val tensorCount = buf.getLong()
        val kvCount     = buf.getLong()

        // ── Metadata KV pairs ──────────────────────────────────────────────
        var name        = ""
        var arch        = ""
        var fileType    = 0
        var contextLen  = 0L
        var sizeLabel   = ""
        var description = ""

        for (i in 0 until kvCount) {
            val keyStr = readString(buf)
            val valueType = buf.getInt().toInt()
            when (keyStr) {
                KEY_NAME         -> name = readStringValue(buf, valueType)
                KEY_ARCH         -> arch = readStringValue(buf, valueType)
                KEY_FILE_TYPE    -> fileType = readIntValue(buf, valueType).toInt()
                KEY_CONTEXT_LLM  -> contextLen = readIntValue(buf, valueType)
                KEY_CONTEXT_GEN  -> contextLen = readIntValue(buf, valueType)
                KEY_SIZE_LABEL   -> sizeLabel = readStringValue(buf, valueType)
                KEY_DESCRIPTION  -> description = readStringValue(buf, valueType)
                else             -> skipValue(buf, valueType)
            }
        }

        return GgufMetadata(
            name           = name,
            architecture   = arch,
            fileType       = fileType,
            contextLength  = contextLen,
            sizeLabel      = sizeLabel,
            description    = description,
        )
    }

    // ── Binary I/O helpers ─────────────────────────────────────────────────

    /** Memory-map just the header region (first 512 KB is plenty for KV pairs). */
    private fun readHeaderBuffer(file: File): ByteBuffer {
        val mapLen = minOf(file.length(), 512 * 1024L)
        RandomAccessFile(file, "r").use { raf ->
            return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, mapLen)
                .order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    private fun readString(buf: ByteBuffer): String {
        val len = buf.getLong()
        require(len >= 0 && len <= Int.MAX_VALUE) { "GGUF string length overflow: $len" }
        val bytes = ByteArray(len.toInt())
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readStringValue(buf: ByteBuffer, type: Int): String {
        require(type == TYPE_STRING) { "Expected GGUF string (type $TYPE_STRING) but got $type" }
        return readString(buf)
    }

    private fun readIntValue(buf: ByteBuffer, type: Int): Long = when (type) {
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> buf.getInt().toLong() and 0xFFFFFFFFL
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> buf.getLong()
        TYPE_BOOL -> if (buf.get().toInt() != 0) 1L else 0L
        else -> {
            skipValue(buf, type)
            0L
        }
    }

    /**
     * Advance the buffer past one value of [type] without reading it. Recursively
     * handles arrays (type 9) by skipping each element.
     */
    private fun skipValue(buf: ByteBuffer, type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL  -> buf.position(buf.position() + 1)
            TYPE_UINT16, TYPE_INT16            -> buf.position(buf.position() + 2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> buf.position(buf.position() + 4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> buf.position(buf.position() + 8)
            TYPE_STRING -> {
                val len = buf.getLong()
                buf.position(buf.position() + len.toInt())
            }
            TYPE_ARRAY -> {
                val elemType = buf.getInt()
                val elemCount = buf.getLong()
                repeat(elemCount.toInt()) { skipValue(buf, elemType) }
            }
            else -> error("Unknown GGUF value type $type — cannot skip")
        }
    }
}