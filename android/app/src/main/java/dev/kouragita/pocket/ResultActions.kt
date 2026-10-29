package dev.kouragita.pocket

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/** Handles the notification's actions without opening the app. */
class ResultActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(Notifier.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(Notifier.EXTRA_BODY).orEmpty()
        val hash = intent.getStringExtra(Notifier.EXTRA_HASH).orEmpty()

        when (intent.action) {
            Notifier.ACTION_COPY -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
                clipboard.setPrimaryClip(ClipData.newPlainText("job hash", hash))
                Log.i(TAG, "copied hash $hash")
                Toast.makeText(context, "Hash copied", Toast.LENGTH_SHORT).show()
            }

            Notifier.ACTION_SHARE -> {
                Log.i(TAG, "sharing result for $title")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, "$title — $body")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(send, "Share result").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    private companion object {
        const val TAG = "Pocket"
    }
}
