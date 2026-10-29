package dev.kouragita.pocket

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Asks what to do with a shared link without taking the screen away.
 *
 * A small panel floats over whatever you were using, so choosing a format is the
 * whole interaction and you can carry on scrolling. It needs the "display over
 * other apps" permission, which only the user can grant.
 */
object OverlayChooser {

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, link: String, onAction: (String?) -> Unit) {
        val window = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (18 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(0xFF1B1F23.toInt())
                setStroke((1 * density).toInt(), 0xFF2C3238.toInt())
            }
        }

        panel.addView(TextView(context).apply {
            text = "Save this post"
            textSize = 16f
            setTextColor(0xFFF3F5F7.toInt())
            setPadding(0, 0, 0, (10 * density).toInt())
        })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun choice(label: String, action: String?) {
            row.addView(Button(context).apply {
                text = label
                textSize = 13f
                setOnClickListener {
                    window.removeView(panel)
                    onAction(action)
                }
            })
        }
        choice("Video", "video")
        choice("Card", "card")
        choice("Text", null)
        panel.addView(row)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            0x00000004,
        ).apply {
            gravity = Gravity.BOTTOM
            y = (90 * density).toInt()
        }
        window.addView(panel, params)

        // Nothing should linger if it is ignored.
        panel.postDelayed({ runCatching { window.removeView(panel) } }, 12_000)
    }
}
