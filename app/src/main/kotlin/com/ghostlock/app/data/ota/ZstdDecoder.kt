package com.ghostlock.app.data.ota

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Zstd decoder for payload `REPLACE_ZSTD` blobs, delegated to the bundled
 * native extractor (`libextract.so --zstd-decode`): the pure-Java decoders
 * either reflect `sun.misc.Unsafe`/`java.nio.Buffer.address` from their static
 * initializers (blocked by hidden-API enforcement on newer Android, which
 * surfaces as an uncatchable ExceptionInInitializerError and a hard crash) or
 * require an extra packaged native library. The Rust binary already links the
 * zstd crate for its own payload support, so this adds nothing to the APK.
 *
 * The decompressed size is fully determined by the operation's destination
 * extents; a mismatch fails closed on either side of the process boundary.
 */
object ZstdDecoder {

    fun decode(
        extractBinary: File,
        workDir: File,
        input: ByteArray,
        expected: Int,
    ): ByteArray {
        if (expected == 0) return ByteArray(0)
        if (!extractBinary.isFile) {
            throw IOException("missing native extractor: ${extractBinary.absolutePath}")
        }
        val inFile = File.createTempFile("glk_zst_in", ".bin", workDir)
        val outFile = File.createTempFile("glk_zst_out", ".bin", workDir)
        try {
            inFile.writeBytes(input)
            val process = ProcessBuilder(
                extractBinary.absolutePath, "--zstd-decode",
                inFile.absolutePath, outFile.absolutePath, expected.toString(),
            ).redirectErrorStream(true).start()
            val diagnostics = try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IOException("zstd native decode timed out")
            }
            if (process.exitValue() != 0) {
                throw IOException(
                    "zstd native decode failed (exit=${process.exitValue()}): ${diagnostics.take(200)}"
                )
            }
            val out = outFile.readBytes()
            if (out.size != expected) {
                throw IOException("zstd size mismatch: expected $expected, got ${out.size}")
            }
            return out
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }
}
