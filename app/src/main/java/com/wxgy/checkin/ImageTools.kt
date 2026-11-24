package com.wxgy.checkin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 照片后处理。相机返回的 JPEG 只是「原始传感器画面 + EXIF 方向标记」，
 * 不同机型传感器方向不同（90 / 270），不按 EXIF 转正就会出现躺着、歪着、
 * 甚至被播放器/服务端按错误比例拉伸的照片。这里统一转成像素级正立的图，
 * 重新编码时 EXIF 一并丢弃，服务端拿到的就是所见即所得的一张竖图。
 *
 * 全程在内存里完成，不写任何文件。
 */
object ImageTools {

    /** 上传前的最长边。太大没必要，还会拖慢上传；太小会影响人脸比对 */
    private const val MAX_SIDE = 1280

    fun toUprightJpeg(src: ByteArray): ByteArray {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(src))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        // 先按 2 的幂降采样解码，避免 5000 万像素的原图直接 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("照片解码失败")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(src, 0, src.size, opts)
            ?: throw IllegalStateException("照片解码失败")

        val rotated = applyOrientation(decoded, orientation)
        val scaled = scaleDown(rotated, MAX_SIDE)

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        android.util.Log.d(
            "WXGY",
            "photo decoded=${decoded.width}x${decoded.height} orientation=$orientation final=${scaled.width}x${scaled.height} bytes=${out.size()}",
        )
        if (scaled !== decoded) scaled.recycle()
        if (rotated !== decoded && rotated !== scaled) rotated.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(bmp.width, bmp.height)
        if (longSide <= maxSide) return bmp
        val ratio = maxSide.toFloat() / longSide
        val w = (bmp.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
