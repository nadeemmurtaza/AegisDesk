package com.newax.aegis.engine.files

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipFile

object TextExtractor {

    data class ExtractionResult(
        val text: String,
        val pageCount: Int = 0,
        val language: String = "",
        val wordCount: Int = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    )

    fun extract(file: File, mimeType: String): ExtractionResult? = when {
        mimeType == "application/pdf" || file.extension.lowercase() == "pdf"
            -> extractPdf(file)
        mimeType.contains("wordprocessingml") || file.extension.lowercase() in setOf("docx","odt")
            -> extractDocx(file)
        mimeType.contains("spreadsheetml") || file.extension.lowercase() in setOf("xlsx","ods")
            -> extractXlsx(file)
        mimeType.startsWith("text/") || file.extension.lowercase() in setOf("txt","md","csv","json","xml","html","htm","log","yaml","yml","ini","properties")
            -> extractText(file)
        else -> null
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    private fun extractPdf(file: File): ExtractionResult? {
        // Try text stream extraction first (fast, no rendering)
        val streamText = extractPdfTextStreams(file)
        if (streamText.isNotBlank() && streamText.length > 50) {
            val pages = countPdfPages(file)
            return ExtractionResult(streamText.take(50_000), pages)
        }
        // Scanned PDF: return page count only; OCR queued separately by FileIndexer
        val pages = countPdfPages(file)
        return if (pages > 0) ExtractionResult("", pages) else null
    }

    private fun extractPdfTextStreams(file: File): String {
        // Minimal PDF text extraction: read BT...ET blocks and extract (text)Tj / [(text)]TJ operators
        val content = file.readText(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        val btBlocks = Regex("""BT(.*?)ET""", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(content)
        for (block in btBlocks) {
            val inner = block.groupValues[1]
            // Match string literals: (text)Tj or [(text)]TJ
            Regex("""\(([^)]*)\)\s*Tj""").findAll(inner).forEach { m ->
                sb.append(decodePdfString(m.groupValues[1])).append(' ')
            }
            Regex("""\[([^\]]*)\]\s*TJ""").findAll(inner).forEach { m ->
                Regex("""\(([^)]*)\)""").findAll(m.groupValues[1]).forEach { s ->
                    sb.append(decodePdfString(s.groupValues[1])).append(' ')
                }
            }
        }
        return sb.toString().trim()
    }

    private fun decodePdfString(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    '(' -> { append('('); i += 2 }
                    ')' -> { append(')'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(s[i]); i++ }
                }
            } else { append(s[i]); i++ }
        }
    }

    private fun countPdfPages(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    }.getOrDefault(0)

    // ── DOCX / ODT (ZIP-based XML) ────────────────────────────────────────────

    private fun extractDocx(file: File): ExtractionResult? = runCatching {
        val zip = ZipFile(file)
        val entry = zip.getEntry("word/document.xml")
            ?: zip.getEntry("content.xml")   // ODT
            ?: return null
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        zip.close()
        val text = stripXmlTags(xml).normalizeWhitespace()
        ExtractionResult(text.take(50_000))
    }.getOrNull()

    private fun extractXlsx(file: File): ExtractionResult? = runCatching {
        val zip = ZipFile(file)
        val sb = StringBuilder()
        // xl/sharedStrings.xml has all cell text
        val ss = zip.getEntry("xl/sharedStrings.xml") ?: zip.getEntry("content.xml")
        if (ss != null) {
            val xml = zip.getInputStream(ss).bufferedReader().readText()
            Regex("<t[^>]*>([^<]+)</t>").findAll(xml).forEach { sb.append(it.groupValues[1]).append(' ') }
        }
        zip.close()
        ExtractionResult(sb.toString().normalizeWhitespace().take(50_000))
    }.getOrNull()

    // ── Plain text ────────────────────────────────────────────────────────────

    private fun extractText(file: File): ExtractionResult? = runCatching {
        val text = file.readText(Charsets.UTF_8).take(100_000)
        ExtractionResult(text)
    }.getOrNull()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stripXmlTags(xml: String): String = xml.replace(Regex("<[^>]+>"), " ")

    private fun String.normalizeWhitespace(): String =
        replace(Regex("\\s+"), " ").trim()
}
