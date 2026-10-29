package dev.kouragita.pocket

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the queue's loopback listener. */
object PocketClient {
    private const val BASE = "http://127.0.0.1:8787"

    data class Outcome(
        val chars: Int,
        val words: Int,
        val uniqueWords: Int,
        val hash: String,
        val elapsedMs: Long,
    ) {
        val summary: String get() = "$words words · $uniqueWords unique · ${hash.take(8)}"
    }

    data class Job(
        val id: String,
        val text: String,
        val status: String,
        val attempts: Int,
        val enqueuedAt: Long,
        val doneAt: Long?,
        val outcome: Outcome?,
        val error: String?,
        val savedFile: String?,
        val savedBytes: Long?,
        val raw: JSONObject?,
        val topics: List<String>,
        val thumb: String?,
    ) {
        /** A retry is the product working, so it belongs on screen. */
        val recovered: Boolean get() = attempts > 1

        val statusLine: String
            get() = buildString {
                append(status)
                // A failed job that was attempted five times is not "recovered".
                if (recovered && status != "failed") append(" · recovered · attempt $attempts")
                else if (recovered) append(" · attempt $attempts")
            }

        val detail: String get() = error ?: outcome?.summary.orEmpty()
    }

    /**
     * A share can arrive before the host has finished starting, so the first
     * attempts may be refused. Retrying is the whole cold-start story.
     */
    fun submit(
        text: String,
        action: String? = null,
        attempts: Int = 40,
    ): String? {
        val body = JSONObject().put("text", text).apply {
            if (action != null) put("action", action)
        }.toString()

        repeat(attempts) {
            val id = try {
                post("$BASE/jobs", body)
            } catch (_: Exception) {
                null
            }
            if (id != null) return id
            Thread.sleep(200)
        }
        return null
    }

    /** True once the loopback listener answers; used before reconciling. */
    fun isReady(): Boolean = try {
        getRaw("$BASE/readyz") != null
    } catch (_: Exception) {
        false
    }

    fun list(): List<Job> = try {
        val array = JSONArray(getRaw("$BASE/jobs") ?: "[]")
        (0 until array.length()).map { parse(array.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun post(url: String, body: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("content-type", "application/json")
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        connection.outputStream.use { it.write(body.toByteArray()) }

        if (connection.responseCode != 201) {
            connection.disconnect()
            return null
        }
        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        return JSONObject(response).getString("id")
    }

    private fun get(url: String): Job? = try {
        getRaw(url)?.let { parse(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }

    private fun getRaw(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        val code = connection.responseCode
        val text = if (code == 200) connection.inputStream.bufferedReader().readText() else null
        connection.disconnect()
        return text
    }

    private fun parse(json: JSONObject): Job {
        val result = json.optJSONObject("result")
        val outcome = result?.takeIf { it.has("hash") }?.let {
            Outcome(
                chars = it.optInt("chars"),
                words = it.optInt("words"),
                uniqueWords = it.optInt("unique_words"),
                hash = it.optString("hash"),
                elapsedMs = it.optLong("elapsed_ms"),
            )
        }
        return Job(
            id = json.getString("id"),
            text = json.getString("text"),
            status = json.getString("status"),
            attempts = json.getInt("attempts"),
            enqueuedAt = json.optLong("enqueued_at"),
            doneAt = json.optLong("done_at").takeIf { it > 0 },
            outcome = outcome,
            error = result?.optString("error")?.takeIf { it.isNotBlank() },
            savedFile = result?.optString("file")?.takeIf { it.isNotBlank() },
            savedBytes = result?.optLong("bytes")?.takeIf { it > 0 },
            raw = result,
            topics = result?.optJSONArray("topics")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty(),
            thumb = result?.optString("thumb")?.takeIf { it.isNotBlank() },
        )
    }
}
