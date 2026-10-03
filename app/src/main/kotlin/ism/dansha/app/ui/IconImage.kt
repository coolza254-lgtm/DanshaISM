package ism.dansha.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** รูปไอคอนบัญชีที่ผู้ใช้เลือก → "img:<base64 PNG 96×96>" (เก็บในช่อง icon จึงติดไปกับไฟล์ส่งออก) */
object IconImage {
    private const val SIZE = 96

    suspend fun fromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            // อ่านขนาดก่อน แล้วย่อขณะ decode กันรูปใหญ่กินหน่วยความจำ
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
            val src = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            // ตัดกลางให้เป็นสี่เหลี่ยมจัตุรัส แล้วย่อ
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            "img:" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}
