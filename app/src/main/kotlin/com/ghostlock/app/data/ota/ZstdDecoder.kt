package com.ghostlock.app.data.ota

import java.io.IOException

/**
 * Zstd decoder for payload `REPLACE_ZSTD` blobs. Newer vivo OTA packages
 * compress the boot partition with zstd instead of xz/bzip2. The decompressed
 * size is fully determined by the operation's destination extents, so the
 * decode is exact-size: a mismatch means the blob or the manifest is corrupt
 * and the operation fails closed.
 */
object ZstdDecoder {
    private val decompressor = io.airlift.compress.zstd.ZstdDecompressor()

    fun decode(input: ByteArray, expected: Int): ByteArray {
        if (expected == 0) return ByteArray(0)
        val output = ByteArray(expected)
        val written = try {
            decompressor.decompress(input, 0, input.size, output, 0, expected)
        } catch (error: Exception) {
            throw IOException("zstd decompression failed: ${error.message}")
        }
        if (written != expected) {
            throw IOException(
                "zstd decompressed size mismatch: expected $expected, got $written"
            )
        }
        return output
    }
}
