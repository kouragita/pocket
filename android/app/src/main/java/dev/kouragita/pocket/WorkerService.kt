package dev.kouragita.pocket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors

/**
 * Keeps a transfer going after you leave the app, so sharing a link and going
 * back to what you were doing is enough.
 *
 * While it runs it polls the queue, publishes finished files and notifies; it
 * stops itself once the queue has been idle for a while.
 */
class WorkerService : Service() {

    private val io = Executors.newSingleThreadExecutor()
    private val handled = mutableSetOf<String>()

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(CHANNEL_ID.hashCode(), notification())
        if (!running) {
            running = true
            io.execute(::loop)
        }
        return START_STICKY
    }

    private fun loop() {
        var idle = 0
        while (running) {
            val jobs = try {
                PocketClient.list()
            } catch (_: Exception) {
                emptyList()
            }

            jobs.filter { it.status == "done" && it.id !in handled }.forEach { job ->
                handled.add(job.id)
                Publisher.publishFinished(this, job)?.let { name ->
                    Notifier.done(this, job, name)
                }
            }

            val busy = jobs.any { it.status == "running" || it.status == "pending" }
            idle = if (busy) 0 else idle + 1
            if (idle > 45) {
                break
            }
            Thread.sleep(1_000)
        }
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Saving", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pocket is saving")
            .setContentText("Your transfer keeps going while you use other apps.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "saving"

        fun start(context: Context) {
            val intent = Intent(context, WorkerService::class.java)
            context.startForegroundService(intent)
        }
    }
}
