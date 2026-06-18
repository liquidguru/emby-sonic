package guru.liquid.embysonic.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflinePrefetchCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    fun cachedUri(trackId: String): Uri? {
        val file = cacheFile(trackId)
        if (!file.exists() || file.length() <= 0) return null
        file.setLastModified(System.currentTimeMillis())
        return Uri.fromFile(file)
    }

    suspend fun prefetch(trackId: String, streamUrl: String, headers: Map<String, String>): Uri? =
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            cachedUri(trackId)?.let { return@withContext it }

            val destination = cacheFile(trackId)
            val temp = File(cacheDir, "${destination.name}.${System.nanoTime()}.tmp")
            try {
                val connection = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                }
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Prefetch failed for $trackId: HTTP $code")
                    return@withContext null
                }
                val expectedBytes = connection.contentLengthLong
                if (expectedBytes > MAX_CACHE_BYTES) {
                    Log.w(TAG, "Prefetch skipped for $trackId: $expectedBytes bytes exceeds cache cap")
                    return@withContext null
                }
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > MAX_CACHE_BYTES) {
                                Log.w(TAG, "Prefetch aborted for $trackId: exceeded cache cap")
                                return@withContext null
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (temp.length() <= 0L) return@withContext null
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                    temp.delete()
                }
                destination.setLastModified(System.currentTimeMillis())
                evictIfNeeded()
                Uri.fromFile(destination)
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed for $trackId", e)
                null
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

    suspend fun deleteTracks(trackIds: Collection<String>) = withContext(Dispatchers.IO) {
        trackIds.forEach { cacheFile(it).delete() }
        evictIfNeeded()
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (files.size > MAX_TRACKS || total > MAX_CACHE_BYTES) {
            val victim = files.removeFirst()
            total -= victim.length()
            victim.delete()
        }
    }

    private fun cacheFile(trackId: String): File =
        File(cacheDir, "$FILE_PREFIX${hash(trackId)}$FILE_SUFFIX")

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$value|$QUALITY_KEY".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "OfflinePrefetch"
        const val CACHE_DIR_NAME = "liquidwave-prefetch"
        const val FILE_PREFIX = "prefetch_"
        const val FILE_SUFFIX = ".lwcache"
        const val QUALITY_KEY = "universal-140000000-v1"
        const val MAX_TRACKS = 5
        const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
