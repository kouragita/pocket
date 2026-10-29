package dev.kouragita.pocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.File

/**
 * Draws our own card for a saved post: author, avatar, thumbnail and text, with
 * the source link on the face. Nothing is screenshotted from X; this is our
 * layout over public metadata, which keeps attribution with the work.
 */
object CardRenderer {
    private const val W = 1080
    private const val H = 1350

    fun render(meta: JSONObject, into: File): File? {
        val card = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) ?: return null
        val canvas = Canvas(card)
        canvas.drawColor(Color.rgb(0x11, 0x13, 0x15))

        drawAvatar(canvas, meta.optString("avatar"))
        drawHeader(canvas, meta)
        val textTop = drawThumb(canvas, meta.optString("thumb"))
        drawBody(canvas, meta.optString("text"), textTop)
        drawFooter(canvas, meta.optString("source"))

        into.parentFile?.mkdirs()
        into.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        card.recycle()
        return into
    }

    private fun paint(size: Float, colour: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour
        isFakeBoldText = bold
    }

    private fun drawAvatar(canvas: Canvas, path: String) {
        val bitmap = path.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeFile(it) } ?: return
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(120f, 132f, 44f, circle)
    }

    private fun drawHeader(canvas: Canvas, meta: JSONObject) {
        canvas.drawText(meta.optString("name"), 188f, 126f, paint(40f, Color.rgb(0xf3, 0xf5, 0xf7), bold = true))
        val sub = "@${meta.optString("author")} · ${meta.optString("date")}"
        canvas.drawText(sub, 188f, 166f, paint(28f, Color.rgb(0x8b, 0x94, 0x9e)))
    }

    /** Returns the y where body text may start. */
    private fun drawThumb(canvas: Canvas, path: String): Float {
        if (path.isEmpty()) return 470f
        val bitmap = BitmapFactory.decodeFile(path) ?: return 470f
        val target = RectF(72f, 230f, W - 72f, 650f)
        canvas.drawBitmap(bitmap, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
        return 700f
    }

    private fun drawBody(canvas: Canvas, text: String, top: Float) {
        val textPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 44f
            color = Color.rgb(0xe6, 0xed, 0xf3)
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, W - 144)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .setMaxLines(9)
            .build()
        canvas.save()
        canvas.translate(72f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawFooter(canvas: Canvas, source: String) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2c, 0x32, 0x38) }
        canvas.drawLine(72f, H - 150f, W - 72f, H - 150f, line)
        canvas.drawText(source, 72f, H - 104f, paint(24f, Color.rgb(0x6e, 0x77, 0x81)))
        canvas.drawText("Archived with Pocket", 72f, H - 64f, paint(24f, Color.rgb(0x4d, 0x54, 0x5c)))
    }
}
