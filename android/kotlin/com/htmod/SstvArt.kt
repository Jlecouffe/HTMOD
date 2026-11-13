/*
 * HTMOD Android : dessins SSTV de Sstv.java refaits avec le Canvas Android (image recadree au format
 * du mode avec texte incruste, mire de test). Appele par la copie Android de Sstv.java.
 */
package com.htmod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface

object SstvArt {
    @JvmStatic fun prepare(src: Img?, m: Sstv.Mode, text: String?): Img {
        val out = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val g = Canvas(out)
        g.drawColor(Color.BLACK)
        if (src != null) {
            val s = Math.max(m.width / src.getWidth().toDouble(), m.height / src.getHeight().toDouble())
            val w = Math.round(src.getWidth() * s).toInt()
            val h = Math.round(src.getHeight() * s).toInt()
            val b = src.toBitmap()
            val x = (m.width - w) / 2
            val y = (m.height - h) / 2
            g.drawBitmap(b, null, Rect(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            b.recycle()
        }
        if (text != null && text.isNotBlank()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            p.textSize = Math.max(14, m.height / 9).toFloat()
            val x = m.width / 40f
            val y = m.height / 40f + p.textSize
            p.style = Paint.Style.STROKE
            p.strokeWidth = Math.max(3f, m.height / 70f)
            p.strokeJoin = Paint.Join.ROUND
            p.color = Color.BLACK
            g.drawText(text.trim(), x, y, p)
            p.style = Paint.Style.FILL
            p.color = 0xFFFFC83C.toInt()
            g.drawText(text.trim(), x, y, p)
        }
        val r = Img.of(out)
        out.recycle()
        return r
    }

    @JvmStatic fun testCard(m: Sstv.Mode, text: String?): Img {
        val img = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val g = Canvas(img)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bars = intArrayOf(0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000)
        val bh = m.height * 3 / 5
        for (i in bars.indices) {
            p.color = 0xFF000000.toInt() or bars[i]
            g.drawRect((i * m.width / bars.size).toFloat(), 0f, ((i + 1) * m.width / bars.size).toFloat(), bh.toFloat(), p)
        }
        val gp = Paint()
        gp.shader = LinearGradient(0f, 0f, m.width.toFloat(), 0f, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
        g.drawRect(0f, bh.toFloat(), m.width.toFloat(), (bh + m.height / 10).toFloat(), gp)
        p.color = 0xFF12161C.toInt()
        g.drawRect(0f, (bh + m.height / 10).toFloat(), m.width.toFloat(), m.height.toFloat(), p)
        p.color = 0xFFFFC83C.toInt()
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        p.textSize = Math.max(14, m.height / 10).toFloat()
        val t = if (text == null || text.isBlank()) "HTMod SSTV" else text.trim()
        g.drawText(t, m.width / 20f, m.height - m.height / 10f, p)
        p.color = 0xFF9AA4B2.toInt()
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        p.textSize = Math.max(10, m.height / 20).toFloat()
        g.drawText(m.name, m.width / 20f, m.height - m.height / 30f, p)
        val r = Img.of(img)
        img.recycle()
        return r
    }
}
