package com.juhao.murexide.utils

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteConstraintException
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal const val MUREXIDE_DOWNLOAD_DIRECTORY = "Murexide"
internal const val LEGACY_STORAGE_PERMISSION_REQUEST_CODE = 0x4D55
private const val MAX_DOWNLOAD_NAME_ATTEMPTS = 100

internal fun murexideDownloadRelativePath(): String =
    "${Environment.DIRECTORY_DOWNLOADS}/$MUREXIDE_DOWNLOAD_DIRECTORY"

internal fun requiresLegacyWritePermission(
    sdkInt: Int,
    permissionGranted: Boolean
): Boolean = sdkInt <= Build.VERSION_CODES.P && !permissionGranted

internal fun hasLegacyWritePermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
}

internal fun requiresLegacyWritePermission(context: Context): Boolean =
    requiresLegacyWritePermission(Build.VERSION.SDK_INT, hasLegacyWritePermission(context))

@Suppress("DEPRECATION")
internal fun legacyMurexideDownloadDirectory(context: Context): File {
    val publicDownloads = Environment.getExternalStoragePublicDirectory(
        Environment.DIRECTORY_DOWNLOADS
    )
    val baseDirectory = publicDownloads ?: context.getExternalFilesDir(null) ?: context.filesDir
    return File(baseDirectory, MUREXIDE_DOWNLOAD_DIRECTORY)
}

internal fun downloadedMediaFileName(
    sourceFile: File,
    isVideo: Boolean,
    mimeType: String?
): String {
    val extension = MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mimeType.orEmpty().lowercase())
        ?.takeIf { it.isNotBlank() }
        ?: sourceFile.extension.takeIf { it.isNotBlank() }
        ?: if (isVideo) "mp4" else "jpg"
    val prefix = if (isVideo) "video" else "image"
    return "${prefix}_${System.currentTimeMillis()}.$extension"
}

internal class LegacyStoragePermissionRequiredException : IOException("需要存储权限")

/**
 * Activities that host a download UI can implement this to resume a download
 * after the legacy storage permission result is delivered.
 */
internal interface LegacyStoragePermissionRequester {
    fun requestLegacyStoragePermission(onResult: (Boolean) -> Unit)
}

internal fun requestLegacyStoragePermission(
    activity: Activity,
    onResult: (Boolean) -> Unit
) {
    if (!requiresLegacyWritePermission(activity)) {
        onResult(true)
        return
    }

    val requester = activity as? LegacyStoragePermissionRequester
    if (requester != null) {
        val request = {
            requester.requestLegacyStoragePermission(onResult)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            request()
        } else {
            activity.runOnUiThread(request)
        }
        return
    }

    activity.runOnUiThread {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            LEGACY_STORAGE_PERMISSION_REQUEST_CODE
        )
        // There is no callback bridge for an arbitrary Activity. The next
        // download attempt will use the newly granted permission.
        onResult(false)
    }
}

internal fun saveMediaFileToDownloads(
    context: Context,
    sourceFile: File,
    displayName: String,
    mimeType: String?
): String {
    if (!sourceFile.isFile) throw IOException("下载源文件不可用")
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveMediaFileWithMediaStore(context, sourceFile, displayName, mimeType)
    } else {
        saveMediaFileLegacy(context, sourceFile, displayName, mimeType)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveMediaFileWithMediaStore(
    context: Context,
    sourceFile: File,
    displayName: String,
    mimeType: String?
): String {
    val resolver = context.contentResolver
    val uri = insertPendingMediaStoreFile(resolver, displayName, mimeType)
    try {
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(sourceFile).use { input ->
                input.copyTo(output)
            }
            output.flush()
        } ?: throw IOException("无法写入下载文件")

        val published = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(uri, published, null, null) <= 0) {
            throw IOException("无法发布下载文件")
        }
        return uri.toString()
    } catch (error: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw error
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun insertPendingMediaStoreFile(
    resolver: ContentResolver,
    displayName: String,
    mimeType: String?
): Uri {
    var lastCollision: RuntimeException? = null
    repeat(MAX_DOWNLOAD_NAME_ATTEMPTS) { collisionIndex ->
        val candidate = collisionDisplayName(displayName, collisionIndex)
        if (mediaStoreDisplayNameExists(resolver, candidate)) return@repeat

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
            mimeType?.takeIf { it.isNotBlank() }?.let {
                put(MediaStore.MediaColumns.MIME_TYPE, it)
            }
            put(MediaStore.MediaColumns.RELATIVE_PATH, murexideDownloadRelativePath())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        try {
            return resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IOException("无法创建下载条目")
        } catch (error: RuntimeException) {
            if (!error.isMediaStorePathCollision()) throw error
            lastCollision = error
        }
    }
    throw IOException("无法创建不重名的下载文件", lastCollision)
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun mediaStoreDisplayNameExists(
    resolver: ContentResolver,
    displayName: String
): Boolean {
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val args = arrayOf(displayName, murexideDownloadRelativePath())
    resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        null
    )?.use { cursor ->
        return cursor.moveToFirst()
    }
    return false
}

private fun saveMediaFileLegacy(
    context: Context,
    sourceFile: File,
    displayName: String,
    mimeType: String?
): String {
    if (requiresLegacyWritePermission(context)) {
        throw LegacyStoragePermissionRequiredException()
    }

    val directory = legacyMurexideDownloadDirectory(context)
    if (!directory.exists() && !directory.mkdirs()) {
        throw IOException("无法创建下载目录")
    }

    val target = createUniqueLegacyFile(directory, displayName)
    try {
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null
        )
        return target.absolutePath
    } catch (error: Throwable) {
        runCatching { target.delete() }
        throw error
    }
}

private fun createUniqueLegacyFile(directory: File, displayName: String): File {
    repeat(MAX_DOWNLOAD_NAME_ATTEMPTS) { collisionIndex ->
        val candidate = File(directory, collisionDisplayName(displayName, collisionIndex))
        if (candidate.createNewFile()) return candidate
    }
    throw IOException("无法创建不重名的下载文件")
}

private fun collisionDisplayName(fileName: String, collisionIndex: Int): String {
    if (collisionIndex == 0) return fileName
    val dotIndex = fileName.lastIndexOf('.')
    return if (dotIndex > 0) {
        "${fileName.substring(0, dotIndex)}($collisionIndex)${fileName.substring(dotIndex)}"
    } else {
        "$fileName($collisionIndex)"
    }
}

private fun Throwable.isMediaStorePathCollision(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty()
        if (current is SQLiteConstraintException &&
            message.contains("files._data", ignoreCase = true)
        ) {
            return true
        }
        if (message.contains("UNIQUE constraint failed: files._data", ignoreCase = true)) {
            return true
        }
        current = current.cause
    }
    return false
}
