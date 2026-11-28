package com.locket.app.storage

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Only the signed-in account's own avatar is persisted outside Coil's evictable cache.
 * Server validators are kept with the original image bytes. An unsuccessful refresh
 * must not remove the last verified image.
 */
internal class OwnAvatarStore(private val context: Context, private val session: SessionManager) {
    data class Snapshot(val owner: String, val requestedUrl: String, val file: File?, val loading: Boolean)
    private val _snapshot = MutableStateFlow<Snapshot?>(
        session.currentUser.takeIf { session.isLoggedIn && it.uid.isNotBlank() && it.profilePictureUrl.isNotBlank() }
            ?.let { Snapshot(it.uid, it.profilePictureUrl, null, true) }
    )
    val snapshot = _snapshot.asStateFlow()
    private val lock = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()
    private val directory = File(context.noBackupFilesDir, "own-avatar")
    private val prefs = context.getSharedPreferences("own_avatar", Context.MODE_PRIVATE)
    private var lastCheckedOwner = ""
    private var lastCheckedUrl = ""
    private var lastCheckedAt = 0L

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ownerKey(owner: String) = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun active(owner: String, url: String) =
        session.isLoggedIn && session.currentUser.uid == owner && session.currentUser.profilePictureUrl == url

    /** For QR export and other non-Compose consumers; never resolves a friend's URL. */
    fun cachedFile(url: String): File? = snapshot.value?.takeIf {
        it.owner == session.currentUser.uid && it.requestedUrl == url && session.isLoggedIn
    }?.file?.takeIf { it.isFile }

    suspend fun refresh(owner: String, url: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (owner.isBlank() || !active(owner, url)) return@withLock
            val key = ownerKey(owner)
            val folder = File(directory, key)
            fun saved(): File? = prefs.getString("$key:file", null)?.let { File(folder, it) }
                ?.takeIf { it.isFile && it.length() > 0L }
            if (url.isBlank()) {
                _snapshot.value = Snapshot(owner, url, null, false)
                folder.deleteRecursively()
                prefs.edit().remove("$key:file").remove("$key:url").remove("$key:etag")
                    .remove("$key:modified").apply()
                return@withLock
            }
            var old = saved()
            _snapshot.value = Snapshot(owner, url, old, old == null)
            if (!url.startsWith("https://")) {
                _snapshot.value = Snapshot(owner, url, old, false)
                return@withLock
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && old != null && owner == lastCheckedOwner && url == lastCheckedUrl &&
                now - lastCheckedAt < TimeUnit.MINUTES.toMillis(15)) return@withLock
            val sameUrl = old != null && prefs.getString("$key:url", null) == url
            try {
                val request = Request.Builder().url(url).header("Cache-Control", "no-cache").apply {
                    if (sameUrl && !force) {
                        prefs.getString("$key:etag", null)?.let { header("If-None-Match", it) }
                        prefs.getString("$key:modified", null)?.let { header("If-Modified-Since", it) }
                    }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (!active(owner, url)) return@withLock
                    if (response.code == 304 && old != null) {
                        lastCheckedOwner = owner; lastCheckedUrl = url; lastCheckedAt = now
                        return@withLock
                    }
                    check(response.isSuccessful) { "Avatar HTTP ${response.code}" }
                    val body = checkNotNull(response.body)
                    check(body.contentLength() <= MAX_BYTES)
                    folder.mkdirs()
                    val next = File(folder, UUID.randomUUID().toString() + ".img")
                    try {
                        body.byteStream().use { input ->
                            next.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= MAX_BYTES)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(next.path, bounds)
                        check(bounds.outWidth > 0 && bounds.outHeight > 0)
                        if (!active(owner, url)) return@withLock
                        // Publish an immutable file name only after the entire image was written.
                        val unchanged = old?.let { previous ->
                            previous.length() == next.length() && digest(previous) == digest(next)
                        } ?: false
                        val chosen = if (unchanged) checkNotNull(old) else next
                        check(prefs.edit().putString("$key:file", chosen.name).putString("$key:url", url)
                            .putString("$key:etag", response.header("ETag"))
                            .putString("$key:modified", response.header("Last-Modified")).commit())
                        old = chosen
                        _snapshot.value = Snapshot(owner, url, chosen, false)
                        folder.listFiles()?.filter { it != chosen }?.forEach { it.delete() }
                        lastCheckedOwner = owner; lastCheckedUrl = url; lastCheckedAt = now
                    } finally {
                        if (next != old) next.delete()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Keep the previous genuine image when offline or the response is invalid.
            } finally {
                if (active(owner, url)) _snapshot.value = Snapshot(owner, url, old, false)
            }
        }
    }

    fun hide(owner: String) {
        if (_snapshot.value?.owner == owner) _snapshot.value = null
    }

    suspend fun clear(owner: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (owner.isBlank() || session.isLoggedIn && session.currentUser.uid == owner) return@withLock
            val key = ownerKey(owner)
            File(directory, key).deleteRecursively()
            prefs.edit().remove("$key:file").remove("$key:url").remove("$key:etag")
                .remove("$key:modified").apply()
            if (lastCheckedOwner == owner) { lastCheckedOwner = ""; lastCheckedUrl = ""; lastCheckedAt = 0L }
            hide(owner)
        }
    }
    private companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}
