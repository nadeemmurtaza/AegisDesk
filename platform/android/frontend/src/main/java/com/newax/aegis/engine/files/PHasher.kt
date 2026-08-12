package com.newax.aegis.engine.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File

object PHasher {

    private const val HASH_SIZE = 8  // 8×8 = 64-bit hash

    // ── Average hash (aHash) ──────────────────────────────────────────────────

    fun hash(path: String): String? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = calculateSampleSize(path)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        hash(bmp).also { bmp.recycle() }
    }.getOrNull()

    fun hash(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        small.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        small.recycle()

        // Convert to grayscale values
        val gray = pixels.map { px ->
            val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
            (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }
        val avg = gray.average()

        // Build 64-bit hash: 1 if pixel > avg, 0 otherwise
        var hash = 0L
        gray.forEachIndexed { i, v -> if (v > avg) hash = hash or (1L shl i) }
        return "%016x".format(hash)
    }

    // ── Hamming distance ──────────────────────────────────────────────────────

    fun hamming(a: String, b: String): Int {
        if (a.length != 16 || b.length != 16) return Int.MAX_VALUE
        val la = a.toLongOrNull(16) ?: return Int.MAX_VALUE
        val lb = b.toLongOrNull(16) ?: return Int.MAX_VALUE
        return java.lang.Long.bitCount(la xor lb)
    }

    /** Returns true if two images are visually similar (hamming < threshold). */
    fun isSimilar(a: String, b: String, threshold: Int = 10): Boolean = hamming(a, b) < threshold

    /** Find the most similar hashes from a list, returns (id, distance) sorted by distance asc. */
    fun findSimilar(queryHash: String, candidates: List<Pair<Long, String>>, maxDistance: Int = 10, limit: Int = 10): List<Pair<Long, Int>> =
        candidates
            .map { (id, h) -> Pair(id, hamming(queryHash, h)) }
            .filter { it.second <= maxDistance }
            .sortedBy { it.second }
            .take(limit)

    // ── SHA-256 for exact deduplication ──────────────────────────────────────

    fun sha256(path: String): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered(8192).use { stream ->
            val buf = ByteArray(8192)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // ── Thumbnail ─────────────────────────────────────────────────────────────

    fun thumbnail(srcPath: String, destPath: String, maxDim: Int = 256): Boolean = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = calculateSampleSize(srcPath, maxDim) }
        val bmp = BitmapFactory.decodeFile(srcPath, opts) ?: return false
        val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
            val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
                .also { if (it != bmp) bmp.recycle() }
        } else bmp
        val out = File(destPath)
        out.parentFile?.mkdirs()
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 75, it) }
        scaled.recycle()
        true
    }.getOrDefault(false)

    private fun calculateSampleSize(path: String, target: Int = HASH_SIZE * 4): Int {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while ((o.outWidth / sample) > target * 2 && (o.outHeight / sample) > target * 2) sample *= 2
        return sample
    }
}
