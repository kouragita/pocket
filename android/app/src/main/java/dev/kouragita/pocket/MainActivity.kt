package dev.kouragita.pocket

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.view.Gravity
import android.provider.MediaStore
import java.io.File
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.view.View
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var jobs: LinearLayout
    private lateinit var tabs: LinearLayout

    /** One worker thread serialises HTTP calls, so polls can never overlap. */
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocket-io").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    private val accent = 0xFF5B9DFF.toInt()
    private val dim = 0xFF8B949E.toInt()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Which tab is showing: 0 library, 1 downloads, 2 queue. */
    private var selectedTab = 0
    private var lastJobs: List<PocketClient.Job> = emptyList()

    /** Job ids already handed to MediaStore this session. */
    private val publishAttempted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Rendering only on change keeps the window idle, so nothing flickers. */
    private var rendered: List<PocketClient.Job>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app draws its own header; the platform title bar would cover it.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setContentView(buildUi())
        io.execute { Publisher.sweepPartials(applicationContext) }
        askForNotifications()

        Host.start(this) { result ->
            runOnUiThread {
                when (result) {
                    is Host.Result.Ready -> status.visibility = View.GONE
                    is Host.Result.Failed -> {
                        status.text = "Host failed (${result.code}): ${result.message}"
                        status.visibility = View.VISIBLE
                    }
                }
                refresh()
                // The listener is up now, so reconciliation can actually read.
                reconcilePublished()
            }
        }

        val shared = sharedText(intent)
        if (shared != null && shared.contains("/status/") && OverlayChooser.canShow(this)) {
            // Ask over whatever the user was doing, then get out of the way.
            startOverlay(shared)
            finish()
            return
        }
        shared?.let(::offerActions)
    }

    /** Chooses a format without taking the screen, then leaves the app running in the background. */
    private fun startOverlay(link: String) {
        WorkerService.start(applicationContext)
        OverlayChooser.show(applicationContext, link) { action ->
            Thread {
                var waited = 0
                while (!PocketClient.isReady() && waited < 40) {
                    Thread.sleep(250)
                    waited++
                }
                PocketClient.submit(link, action = action)
            }.start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText(intent)?.let(::offerActions)
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.post(poll)
        reconcilePublished()
    }

    /**
     * A download that finished while the app was dead is still in app storage.
     * Only safe to run once the host is serving, so call it from readiness too.
     */
    private fun reconcilePublished(attempt: Int = 0) {
        io.execute {
            if (!PocketClient.isReady() && attempt < 5) {
                // On a cold start the listener can still be binding; try again shortly.
                handler.postDelayed({ reconcilePublished(attempt + 1) }, 1_000)
                return@execute
            }
            PocketClient.list()
                .filter { it.status == "done" && it.id !in publishAttempted }
                .forEach { job ->
                    publishAttempted.add(job.id)
                    publishFinished(job)?.let { published ->
                        Notifier.done(applicationContext, job, published)
                    }
                }
        }
    }

    /** Our own inserts are visible to us, so this is enough to avoid duplicates. */
    /** Only a row with the same byte count counts as the same file. */
    private fun alreadyPublished(name: String): Boolean = Publisher.alreadyPublished(this, name)

    override fun onPause() {
        polling = false
        handler.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        // Only surfaces a problem; a healthy host shows nothing here.
        status = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }
        title = TextView(this).apply {
            text = "Pocket"
            textSize = 26f
            paint.isFakeBoldText = true
            setPadding(0, 8, 0, 2)
        }
        summary = TextView(this).apply { textSize = 16f; alpha = 0.7f; setPadding(0, 2, 0, 2) }
        jobs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 14)
            background = GradientDrawable().apply { setColor(0xFF15181B.toInt()) }
        }
        refreshTabs()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 32)
            addView(status)
            addView(title)
            addView(summary)
            addView(jobs)
        }
        val scrolling = ScrollView(this).apply {
            addView(column)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrolling)
            addView(android.view.View(this@MainActivity).apply {
                setBackgroundColor(0xFF23282D.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                )
            })
            addView(tabs)
            // The window is edge-to-edge, so keep clear of the status and gesture bars.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
    }

    /** Icon over label, equal widths, the selected one carried by the accent. */

    /** A link can be archived; plain text can only be described. */
    private fun offerActions(text: String) {
        if (!text.contains("/status/")) {
            submit(text)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save this post")
            .setNeutralButton("Quick save") { _, _ ->
                // Without this permission the chooser has to open the app.
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
            .setItems(arrayOf("Download the video", "Save a card image", "Just describe the text")) { _, which ->
                when (which) {
                    0 -> submit(text, action = "video")
                    1 -> submit(text, action = "card")
                    else -> submit(text)
                }
            }
            .show()
    }

    /** The queue takes the text from a share, then reports back through a notification. */
    private fun submit(text: String, action: String? = null) {
        // Whatever you just asked for is what you want to watch.
        runOnUiThread {
            if (selectedTab != 2) {
                selectedTab = 2
                refreshTabs()
                rendered = emptyList()
                render(lastJobs)
            }
        }
        io.execute {
            PocketClient.submit(text, action = action)
            // The service carries the transfer and publishes it when it lands.
            // Waiting for the job here blocked this same executor, which is what
            // froze the list for the whole download.
            WorkerService.start(applicationContext)
            refresh()
        }
    }

    /** A finished job is either a downloaded video or a card we draw ourselves. */
    /** Long-pressing a saved row offers to remove its file. */
    private fun confirmDelete(job: PocketClient.Job) {
        if (job.savedFile == null && job.raw?.optString("kind") != "card") return
        AlertDialog.Builder(this)
            .setTitle("Delete this file?")
            .setMessage("It will be removed from Downloads/Pocket. The job stays in the list.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                io.execute {
                    Publisher.delete(applicationContext, job)
                    runOnUiThread {
                        rendered = emptyList()
                        refresh()
                    }
                }
            }
            .show()
    }

    /** The file name, but only when this call published it — so a job that was
     *  already in Downloads does not announce itself again. */
    private fun publishFinished(job: PocketClient.Job): String? =
        Publisher.publishFinished(this, job)


    /** Name a card the way a person would look for it. */
    private fun cardName(raw: org.json.JSONObject, id: String): String =
        Publisher.cardName(raw, id)

    /** MediaStore keeps the file visible in Files and Gallery, not buried in app data. */
    /** Publish only if an identical copy is not already there. */
    /** True only when this call actually copied the file into Downloads. */


    /** The post a link points at, or the text itself when it is not a link. */
    private fun postKey(text: String): String =
        Regex("/status/(\\d+)").find(text)?.groupValues?.get(1) ?: text

    private fun refresh() {
        io.execute {
            val current = PocketClient.list()
            // A job that finishes while the screen is up is published now, not
            // deferred until the app is next opened.
            val fresh = current.filter { it.status == "done" && it.id !in publishAttempted }
            fresh.forEach { publishAttempted.add(it.id) }
            fresh.forEach { job ->
                publishFinished(job)?.let { published ->
                    Notifier.done(applicationContext, job, published)
                }
            }
            runOnUiThread { render(current) }
        }
    }

    private fun render(current: List<PocketClient.Job>) {
        if (current == rendered) return
        rendered = current
        lastJobs = current

        summary.text = when (current.size) {
            0 -> "Nothing queued yet"
            else -> {
                fun count(status: String) = current.count { it.status == status }
                val parts = ArrayList<String>()
                count("running").takeIf { it > 0 }?.let { parts += "$it working" }
                count("done").takeIf { it > 0 }?.let { parts += "$it done" }
                count("failed").takeIf { it > 0 }?.let { parts += "$it failed" }
                current.count { it.status == "pending" }
                    .takeIf { it > 0 }?.let { parts += "$it waiting" }
                parts.joinToString(" · ")
            }
        }

        jobs.removeAllViews()
        // One row per post. The same link arrives with different query strings, so
        // compare the status id rather than the whole URL.
        val saved = current
            .filter { it.savedFile != null || it.raw?.optString("kind") == "card" }
            .distinctBy { postKey(it.text) }

        when (selectedTab) {
            // Library: what you kept, filed by topic.
            0 -> {
                if (saved.isEmpty()) {
                    jobs.addView(empty("Nothing saved yet. Share a post from X and choose Download the video or Save a card."))
                }
                val groups = saved.groupBy { it.topics.firstOrNull() ?: "Unsorted" }
                groups.keys
                    .sortedWith(compareBy({ it == "Unsorted" }, { it.lowercase() }))
                    .forEach { topic ->
                        jobs.addView(heading(topic, groups.getValue(topic).size))
                        groups.getValue(topic).forEach { jobs.addView(row(it)) }
                    }
            }
            // Downloads: the files themselves, newest first.
            1 -> {
                if (saved.isEmpty()) {
                    jobs.addView(empty("No files yet. Videos and cards you save land in Downloads/Pocket and show up here."))
                } else {
                    jobs.addView(heading("Saved files", saved.size))
                }
                // Newest first: the list arrives newest-first, so reversing put the
                // file you just saved at the bottom.
                saved.forEach { jobs.addView(row(it)) }
            }
            // Queue: everything, including what is still running or failed.
            else -> {
                if (current.isEmpty()) jobs.addView(empty("Nothing queued. Share a link from X to add your first job."))
                current.forEach { jobs.addView(row(it)) }
            }
        }
    }

    /** Decode a saved thumbnail at list size rather than full resolution. */
    private fun thumbnail(path: String?): android.graphics.Bitmap? {
        if (path == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    /** Icon over label, equal widths, the selected one carried by the accent. */
    private fun refreshTabs() {
        tabs.removeAllViews()
        listOf(
            Triple("Library", R.drawable.ic_tab_library, 0),
            Triple("Downloads", R.drawable.ic_tab_download, 1),
            Triple("Queue", R.drawable.ic_tab_queue, 2),
        ).forEach { (label, icon, index) ->
            val tint = if (selectedTab == index) accent else dim
            tabs.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                    setPadding(0, 10, 0, 6)
                    minimumHeight = dp(56)
                    isClickable = true
                    setOnClickListener {
                        if (selectedTab != index) {
                            selectedTab = index
                            refreshTabs()
                            rendered = emptyList()
                            render(lastJobs)
                        }
                    }
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(icon)
                            setColorFilter(tint)
                            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = label
                            textSize = 12f
                            setTextColor(tint)
                            setPadding(0, 6, 0, 0)
                            maxLines = 1
                            gravity = Gravity.CENTER
                        },
                    )
                },
            )
        }
    }

    /** Section heading: the context a person would look under. */
    private fun empty(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 14f
        alpha = 0.6f
        setPadding(0, 16, 0, 24)
    }

    private fun heading(topic: String, count: Int): TextView = TextView(this).apply {
        text = "${topic.replaceFirstChar { it.uppercase() }}  ·  $count"
        textSize = 13f
        letterSpacing = 0.08f
        setPadding(0, 28, 0, 10)
        alpha = 0.75f
    }

    private fun row(job: PocketClient.Job): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(0x14888888)
            }
            setPadding(36, 24, 36, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = 16 }
            isClickable = true
            setOnClickListener { showDetail(job) }
            setOnLongClickListener { confirmDelete(job); true }
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        thumbnail(job.thumb)?.let { bitmap ->
            body.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (170 * resources.displayMetrics.density).toInt(),
                    ).apply { bottomMargin = 16 }
                },
            )
        }

        body.addView(chip(job))
        body.addView(TextView(this).apply {
            text = job.text.take(140)
            textSize = 15f
            setPadding(0, 6, 0, 6)
        })
        job.savedBytes?.let { bytes ->
            body.addView(TextView(this).apply {
                text = "%.1f MB".format(bytes / 1_048_576.0)
                textSize = 12f
                alpha = 0.6f
            })
        }
        if (job.topics.isNotEmpty()) {
            body.addView(TextView(this).apply {
                text = job.topics.joinToString("   ") { "#$it" }
                textSize = 12f
                alpha = 0.65f
            })
        }
        job.detail.takeIf { it.isNotEmpty() }?.let { detail ->
            body.addView(TextView(this).apply {
                text = detail
                textSize = 13f
                alpha = 0.7f
            })
        }
        card.addView(body)
        return card
    }


    /** A tinted pill, so done, running, recovered and failed differ at a glance. */
    private fun chip(job: PocketClient.Job): TextView {
        val tone = when {
            job.status == "failed" -> 0xFFD9534F
            job.recovered -> 0xFFE0A030
            job.status == "running" -> 0xFF4A90D9
            job.status == "pending" -> 0xFF9E9E9E
            else -> 0xFF4CAF50
        }.toInt()

        return TextView(this).apply {
            text = job.statusLine
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(tone)
            setPadding(30, 10, 30, 10)
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor((tone and 0x00FFFFFF) or 0x26000000)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun showDetail(job: PocketClient.Job) {
        val outcome = job.outcome
        val body = buildString {
            append(job.text)
            append("\n\n")
            append("status      ${job.statusLine}\n")
            append("attempts    ${job.attempts}\n")
            if (outcome != null) {
                append("characters  ${outcome.chars}\n")
                append("words       ${outcome.words}\n")
                append("distinct    ${outcome.uniqueWords}\n")
                append("hash        ${outcome.hash}\n")
                append("work time   ${outcome.elapsedMs} ms\n")
            }
            job.error?.let { append("error       $it\n") }
            append("queued      ${job.enqueuedAt}\n")
            job.doneAt?.let { append("finished    $it\n") }
        }
        val message = SpannableString(body)
        job.outcome?.hash?.let { hash ->
            val start = body.indexOf(hash)
            if (start >= 0) {
                message.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    start + hash.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        val detail = AlertDialog.Builder(this)
            .setTitle("Job ${job.id.takeLast(6)}")
            .setMessage(message)
            .setPositiveButton("Close", null)
        // Only offer the file action when there is a file to remove.
        if (job.savedFile != null || job.raw?.optString("kind") == "card") {
            detail.setNeutralButton("Delete file") { _, _ -> confirmDelete(job) }
        }
        detail.show()
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
