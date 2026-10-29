package dev.kouragita.pocket

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the Rust host for the whole process. Starting blocks until the host
 * reports readiness, so it never runs on the UI thread.
 */
object Host {
    private const val STOP_GRACE_MS = 12_000L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocket-host").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    fun start(context: Context, onReady: (Result) -> Unit) {
        worker.execute {
            val result = startBlocking(context.applicationContext)
            onReady(result)
        }
    }

    private fun startBlocking(context: Context): Result {
        if (started) return Result.Ready(RiviumBridge.nativeVersion())

        val root = File(context.filesDir, "pocket").apply { mkdirs() }
        val code = RiviumBridge.nativeStart(
            arrayOf(
                "--root", root.absolutePath,
                // Default grace is 3 s, which truncates any honest demo job.
                "--set", "lifecycle.stop_timeout=10s",
                "--set", "log.filter=info",
            ),
        )

        return when (code) {
            0 -> {
                started = true
                Result.Ready(RiviumBridge.nativeVersion())
            }
            1 -> {
                started = true
                Result.Ready(RiviumBridge.nativeVersion())
            }
            else -> Result.Failed(code, RiviumBridge.nativeLastError())
        }
    }

    fun stop() {
        if (!started) return
        RiviumBridge.nativeStop(STOP_GRACE_MS)
        started = false
    }

    sealed interface Result {
        data class Ready(val version: String) : Result
        data class Failed(val code: Int, val message: String) : Result
    }
}
