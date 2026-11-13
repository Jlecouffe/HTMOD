/*
 * HTMOD Android : image RVB minimale (pixels 0xRRGGBB) remplacant java.awt.image.BufferedImage dans la
 * copie Android de Sstv.java (generee par build.ps1), avec conversions Bitmap / PNG.
 */
package com.htmod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

class Img(private val w: Int, private val h: Int, @Suppress("UNUSED_PARAMETER") type: Int) {
    @JvmField val px = IntArray(w * h)

    fun getWidth() = w
    fun getHeight() = h
    fun getRGB(x: Int, y: Int) = px[y * w + x]
    fun setRGB(x: Int, y: Int, rgb: Int) { px[y * w + x] = rgb and 0xFFFFFF }

    fun getRGB(x0: Int, y0: Int, ww: Int, hh: Int, out: IntArray?, off: Int, scan: Int): IntArray {
        val o = out ?: IntArray(off + hh * scan)
        for (y in 0 until hh) for (x in 0 until ww) o[off + y * scan + x] = px[(y0 + y) * w + x0 + x]
        return o
    }

    fun setRGB(x0: Int, y0: Int, ww: Int, hh: Int, src: IntArray, off: Int, scan: Int) {
        for (y in 0 until hh) for (x in 0 until ww) px[(y0 + y) * w + x0 + x] = src[off + y * scan + x] and 0xFFFFFF
    }

    fun toBitmap(): Bitmap {
        val c = IntArray(px.size) { 0xFF000000.toInt() or px[it] }
        return Bitmap.createBitmap(c, w, h, Bitmap.Config.ARGB_8888)
    }

    fun png(): ByteArray {
        val b = toBitmap()
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, o)
        b.recycle()
        return o.toByteArray()
    }

    companion object {
        @JvmField val TYPE_INT_RGB = 1

        fun of(b: Bitmap): Img {
            val m = Img(b.width, b.height, TYPE_INT_RGB)
            b.getPixels(m.px, 0, m.w, 0, 0, m.w, m.h)
            for (i in m.px.indices) m.px[i] = m.px[i] and 0xFFFFFF
            return m
        }

        /** Image lue (PNG, JPEG, GIF, BMP, WebP), ou null. */
        fun decode(data: ByteArray): Img? {
            val b = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            val m = of(b)
            b.recycle()
            return m
        }
    }
}
