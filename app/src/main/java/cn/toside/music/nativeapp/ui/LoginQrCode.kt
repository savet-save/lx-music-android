package cn.toside.music.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Local QR encoding; no login URL or token is sent to an image service. */
internal object LoginQrCode {
    fun bitmap(url: String): Bitmap {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
}
