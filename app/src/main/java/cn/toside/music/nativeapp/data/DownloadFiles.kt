package cn.toside.music.nativeapp.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Audio and its LRC share a public directory that also permits non-media files on scoped storage. */
internal class DownloadFiles(private val context: Context) {
    fun create(name: String, mime: String): Uri {
        require(name == File(name).name && name.isNotBlank())
        return if (Build.VERSION.SDK_INT >= 29) {
            checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH); put(MediaStore.MediaColumns.IS_PENDING, 1)
            })) { "无法创建下载文件" }
        } else {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY)
            check(folder.isDirectory || folder.mkdirs()) { "无法创建下载目录" }
            val file = File(folder, name)
            check(file.createNewFile()) { "下载文件已存在" }
            Uri.fromFile(file)
        }
    }
    fun output(uri: Uri): OutputStream = if (uri.scheme == "file") File(requireNotNull(uri.path)).outputStream()
        else checkNotNull(context.contentResolver.openOutputStream(uri, "w")) { "无法写入下载文件" }
    fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
            check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } else MediaScannerConnection.scanFile(context, arrayOf(uri.path), null, null)
    }
    fun delete(uri: Uri) {
        if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
        else context.contentResolver.delete(uri, null, null)
    }
    companion object {
        const val DIRECTORY = "LX Music"
        const val RELATIVE_PATH = "Download/LX Music/"
    }
}
