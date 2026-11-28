package com.locket.app.ui

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class StoredMomentUpload(
    val id: Long,
    val owner: String,
    val directory: File,
    val caption: String,
    val recipients: List<String>,
    val hasVideo: Boolean,
    var thumbnailUrl: String? = null,
    var md5: String? = null,
    var videoUrl: String? = null,
    var attemptedPost: Boolean = false,
    var confirmed: Boolean = false,
    var failure: String? = null,
    val overlaysJson: String = "[]"
) {
    val thumbnail: File get() = File(directory, "thumbnail")
    val video: File? get() = if (hasVideo) File(directory, "video.mp4") else null
}

/** Private per-account disk journal. Metadata uses explicit JSON, not reflection.
 * A job is accepted only after its payload and atomic metadata are committed. */
internal class MomentUploadStore(private val filesDir: File) {
    private fun folder(owner: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(File(filesDir, "moment_uploads"), hash)
    }

    fun create(id: Long, owner: String, bytes: ByteArray, video: File?, caption: String, recipients: List<String>, overlaysJson: String = "[]"): StoredMomentUpload {
        val dir = File(folder(owner), id.toString())
        check(dir.mkdirs()) { "Không tạo được hàng đợi gửi" }
        val item = StoredMomentUpload(id, owner, dir, caption, recipients, video != null, overlaysJson = overlaysJson)
        try {
            FileOutputStream(item.thumbnail).use { it.write(bytes); it.fd.sync() }
            if (video != null) {
                FileOutputStream(checkNotNull(item.video)).use { out ->
                    video.inputStream().use { it.copyTo(out) }
                    out.fd.sync()
                }
            }
            save(item)
            return item
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }

    fun save(item: StoredMomentUpload) {
        val json = JSONObject().apply {
            put("id", item.id); put("owner", item.owner); put("caption", item.caption)
            put("recipients", JSONArray(item.recipients)); put("video", item.hasVideo)
            put("overlays", JSONArray(item.overlaysJson))
            put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
            put("md5", item.md5 ?: JSONObject.NULL)
            put("videoUrl", item.videoUrl ?: JSONObject.NULL)
            put("attemptedPost", item.attemptedPost)
            put("confirmed", item.confirmed)
            put("failure", item.failure ?: JSONObject.NULL)
        }
        val atomic = AtomicFile(File(item.directory, "job.json"))
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    fun read(owner: String): List<StoredMomentUpload> {
        return folder(owner).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            try {
                val meta = AtomicFile(File(dir, "job.json"))
                val json = JSONObject(String(meta.readFully(), Charsets.UTF_8))
                check(json.getString("owner") == owner)
                val id = json.getLong("id")
                check(dir.name == id.toString())
                if (uploadRecovery(json.optBoolean("confirmed"), json.optBoolean("attemptedPost"), false) == MomentUploadRecovery.CLEANUP) {
                    dir.deleteRecursively()
                    return@mapNotNull null
                }
                val recipients = json.getJSONArray("recipients")
                val item = StoredMomentUpload(id, owner, dir, json.getString("caption"),
                    List(recipients.length()) { recipients.getString(it) }, json.getBoolean("video"),
                    optional(json, "thumbnailUrl"), optional(json, "md5"), optional(json, "videoUrl"),
                    json.optBoolean("attemptedPost"), false, optional(json, "failure"),
                    json.optJSONArray("overlays")?.toString() ?: "[]")
                if (!item.thumbnail.isFile || (item.hasVideo && item.video?.isFile != true)) {
                    item.failure = "Thiếu tệp của khoảnh khắc đã lưu; hãy bỏ mục này và chọn lại ảnh."
                }
                item
            } catch (e: Exception) {
                // A partial initial write has no committed job. Keep any journal
                // that exists for manual recovery rather than silently posting it.
                if (!File(dir, "job.json").exists() && !File(dir, "job.json.bak").exists()) dir.deleteRecursively()
                android.util.Log.w("LocketUpload", "Unable to read upload journal", e)
                null
            }
        }.sortedBy { it.id }
    }

    private fun optional(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }

    fun remove(item: StoredMomentUpload): Boolean = item.directory.deleteRecursively()
    fun clear(owner: String) { if (owner.isNotBlank()) folder(owner).deleteRecursively() }
}
