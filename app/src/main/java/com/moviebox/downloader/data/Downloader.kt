package com.moviebox.downloader.data

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.moviebox.downloader.api.MovieBoxApi
import com.moviebox.downloader.debug.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class DmStatus(
    val status: Int,
    val reason: Int,
    val soFar: Long,
    val total: Long,
    val localUri: String?,
)

/** Result of handing one file to the system DownloadManager. */
data class EnqueueResult(
    val dmId: Long,
    /** true = the file lands in the app's private staging folder first and is
     * moved into public Downloads/MovieBox by the app once the download ends. */
    val needsImport: Boolean,
)

/**
 * Wrapper around the system DownloadManager, with a device-compatible
 * destination cascade:
 *
 *  A) Android 10+ : hand the DownloadManager a MediaStore location directly —
 *     the file lands straight in public Downloads/MovieBox. Stock Android
 *     accepts this; some OEM forks (Xiaomi MIUI/HyperOS) reject it with
 *     "IllegalArgumentException: Not a file URI" — so we catch and fall back.
 *  B) Android 8/9 : the classic public-Downloads path (needs the storage
 *     permission, which the app requests on those versions).
 *  C) Any Android : download into the app's own external staging folder using
 *     a plain file:// URI (every DownloadManager build accepts this), then
 *     import the finished file into public Downloads/MovieBox via MediaStore.
 *
 * Downloads run in the system's DownloadManager: progress notification,
 * pause/resume, survives app death and reboot.
 */
class DownloadRepository(private val context: Context) {

    private val dm: DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Private staging folder used by strategy C (always writable). */
    private fun stagingDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "MovieBox")
            .apply { mkdirs() }

    private fun baseRequest(url: String, fileName: String, mimeType: String): DownloadManager.Request =
        DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("MovieBox Downloader")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType(mimeType)
            // CDN requires a browser UA + site referer
            .addRequestHeader("User-Agent", MovieBoxApi.MOBILE_UA)
            .addRequestHeader("Referer", "${MovieBoxApi.SITE_ORIGIN}/")

    fun enqueue(url: String, fileName: String, mimeType: String): EnqueueResult {

        /* ---- strategy A: direct public Downloads location (Android 10+) ---- */
        if (Build.VERSION.SDK_INT >= 29) {
            var mediaUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MovieBox")
                }
                mediaUri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw IOException("MediaStore insert failed")
                val request = baseRequest(url, fileName, mimeType)
                request.setDestinationUri(mediaUri)
                val id = dm.enqueue(request)
                AppLog.ok(
                    AppLog.CAT_DL,
                    "handing to system DownloadManager: \"$fileName\" → Downloads/MovieBox/ (id #$id)",
                    "source: ${url.take(120)}…\n" +
                        "destination: public Downloads/MovieBox via MediaStore (Android 10+, no storage permission needed)\n" +
                        "headers sent by DownloadManager: User-Agent (browser UA) + Referer (${MovieBoxApi.SITE_ORIGIN}/)\n" +
                        "progress: system notification; status updates polled by the app every second",
                )
                return EnqueueResult(id, needsImport = false)
            } catch (e: Exception) {
                // remove the half-registered MediaStore row so no 0-byte ghost file remains
                mediaUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                AppLog.warn(
                    AppLog.CAT_DL,
                    "this device's DownloadManager refused the public-Downloads destination " +
                        "(${e.javaClass.simpleName}: ${e.message ?: "no message"})",
                    "Some OEM DownloadProviders (Xiaomi MIUI/HyperOS and a few older forks) only accept " +
                        "plain file:// destinations.\n" +
                        "No problem: falling back to the app's private staging folder — the file will be " +
                        "moved into public Downloads/MovieBox automatically when the download finishes.",
                )
            }
        }

        /* ---- strategy B: classic public Downloads dir (Android 8/9) ---- */
        if (Build.VERSION.SDK_INT < 29) {
            try {
                val request = baseRequest(url, fileName, mimeType)
                @Suppress("DEPRECATION")
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "MovieBox/$fileName"
                )
                val id = dm.enqueue(request)
                AppLog.ok(
                    AppLog.CAT_DL,
                    "handing to system DownloadManager: \"$fileName\" → Downloads/MovieBox/ (id #$id)",
                    "source: ${url.take(120)}…\n" +
                        "destination: public Downloads/MovieBox via legacy path (Android 8/9, storage permission granted)\n" +
                        "headers sent by DownloadManager: User-Agent (browser UA) + Referer (${MovieBoxApi.SITE_ORIGIN}/)",
                )
                return EnqueueResult(id, needsImport = false)
            } catch (e: Exception) {
                AppLog.warn(
                    AppLog.CAT_DL,
                    "the classic public-Downloads destination failed too " +
                        "(${e.javaClass.simpleName}: ${e.message ?: "no message"}) — falling back to the app's private folder",
                )
            }
        }

        /* ---- strategy C: app staging folder + import on completion ---- */
        val request = baseRequest(url, fileName, mimeType)
        val target = File(stagingDir(), fileName)
        request.setDestinationUri(Uri.fromFile(target))
        val id = dm.enqueue(request)
        AppLog.ok(
            AppLog.CAT_DL,
            "handing to system DownloadManager: \"$fileName\" → app staging folder (id #$id)",
            "source: ${url.take(120)}…\n" +
                "destination (temporary): ${target.path}\n" +
                "the app moves the finished file into public Downloads/MovieBox automatically\n" +
                "headers sent by DownloadManager: User-Agent (browser UA) + Referer (${MovieBoxApi.SITE_ORIGIN}/)",
        )
        return EnqueueResult(id, needsImport = true)
    }

    /**
     * Moves a finished file out of the app's private staging folder into the
     * public Downloads/MovieBox collection so every file manager and player
     * can see it. Returns the final uri (content:// on 10+, file:// on 8/9)
     * or null if the move failed (the file stays safe in staging).
     */
    suspend fun importToMediaStore(localUri: String, fileName: String, mimeType: String): String? =
        withContext(Dispatchers.IO) {
            val src: File = try {
                val path = Uri.parse(localUri).path
                if (path == null) {
                    AppLog.e(AppLog.CAT_DL, "could not move \"$fileName\" — staging location unreadable ($localUri)")
                    return@withContext null
                }
                File(path)
            } catch (e: Exception) {
                AppLog.e(AppLog.CAT_DL, "could not move \"$fileName\" — staging location unreadable: ${e.message}")
                return@withContext null
            }
            if (!src.exists()) {
                AppLog.e(AppLog.CAT_DL, "could not move \"$fileName\" — staged file is missing (${src.path})")
                return@withContext null
            }
            val t0 = System.currentTimeMillis()
            try {
                val finalUri: String = if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MovieBox")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IOException("MediaStore insert failed")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out, 1 shl 20) }
                    } ?: throw IOException("could not open destination stream")
                    context.contentResolver.update(
                        uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
                    )
                    uri.toString()
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MovieBox",
                    ).apply { mkdirs() }
                    val dst = File(dir, fileName)
                    src.copyTo(dst, overwrite = true)
                    Uri.fromFile(dst).toString()
                }
                val bytes = src.length()
                src.delete()
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                val sizeTxt = "%.1f MB".format(bytes / 1048576.0)
                AppLog.ok(
                    AppLog.CAT_DL,
                    "moved into public Downloads/MovieBox: \"$fileName\" " +
                        "(${if (secs > 1) "$sizeTxt, ${"%.0f".format(secs)} s" else sizeTxt})",
                    "final location: $finalUri\n" +
                        "the file is now visible to every file manager, gallery and video player",
                )
                finalUri
            } catch (e: Exception) {
                AppLog.e(
                    AppLog.CAT_DL,
                    "could not move \"$fileName\" into public Downloads — ${e.javaClass.simpleName}: ${e.message ?: "no message"}",
                    "the file itself is safe in the app's staging folder:\n    $localUri\n" +
                        "it will be retried the next time you open the app; you can also copy it manually with a file manager",
                )
                null
            }
        }

    fun query(id: Long): DmStatus? {
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            fun col(name: String): Int = c.getColumnIndexOrThrow(name)
            return DmStatus(
                status = c.getInt(col(DownloadManager.COLUMN_STATUS)),
                reason = c.getInt(col(DownloadManager.COLUMN_REASON)),
                soFar = c.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                localUri = c.getString(col(DownloadManager.COLUMN_LOCAL_URI)),
            )
        }
    }

    /** Cancels a running/pending download and deletes partial data. */
    fun cancel(id: Long) {
        try {
            dm.remove(id)
            AppLog.warn(AppLog.CAT_DL, "download id #$id removed from the system DownloadManager")
        } catch (_: Exception) {
        }
    }

    /** Deletes a completed file from MediaStore or the staging folder. */
    fun deleteFile(localUri: String) {
        try {
            val uri = Uri.parse(localUri)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else if (uri.scheme == "file") {
                File(uri.path ?: return).delete()
            }
        } catch (_: Exception) {
        }
    }

    /** Opens a completed video with the default player. */
    fun openFile(localUri: String, mimeType: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(localUri), mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the system Downloads app. */
    fun openDownloadsApp() {
        try {
            context.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
