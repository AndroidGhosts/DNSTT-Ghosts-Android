package com.androidghosts.dnsttghosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostic console. It displays only events emitted by the tunnel core;
 * it does not fabricate scan results or perform network actions.
 */
class LogActivity : AppCompatActivity() {
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private lateinit var summary: TextView
    private lateinit var filterBar: LinearLayout
    private var selectedFilter = "ALL"
    private var live = true
    private var autoScroll = true
    private var currentLines: List<String> = emptyList()
    private val filters = listOf("ALL", "CONNECTION", "NETWORK", "DNS", "AUTH", "ERROR")
    private val accent = Color.rgb(0, 238, 190)
    private val bg = Color.rgb(5, 12, 19)
    private val panel = Color.rgb(10, 23, 32)
    private val muted = Color.rgb(119, 150, 166)
    private val listener: (String) -> Unit = {
        runOnUiThread {
            if (live) {
                currentLines = LogBuffer.all().lineSequence().filter(String::isNotBlank).toList()
                renderLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        buildUi()
        currentLines = LogBuffer.all().lineSequence().filter(String::isNotBlank).toList()
        renderLogs()
        LogBuffer.listen(listener)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(Typeface.MONOSPACE, Typeface.BOLD) else typeface = Typeface.MONOSPACE
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "GHOST // DIAGNOSTICS"
            setTitleTextColor(accent)
            setBackgroundColor(panel)
            subtitle = "LOCAL EVENT STREAM"
            setSubtitleTextColor(muted)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        setSupportActionBar(toolbar)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
            setBackgroundColor(panel)
        }
        top.addView(label("●  SYSTEM TELEMETRY     /     ANDROID_GHOSTS", 11f, accent, true))
        summary = label("BUFFER 0  •  INFO 0  •  WARN 0  •  ERROR 0", 10f, muted)
        val summaryParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
        top.addView(summary, summaryParams)
        root.addView(top)

        search = EditText(this).apply {
            hint = "Search events, IPs, hostnames, RTT…"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            textSize = 13f
            singleLine = true
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setBackgroundColor(Color.rgb(15, 32, 43))
        }
        val searchParams = LinearLayout.LayoutParams(-1, dp(46)).apply {
            setMargins(dp(12), dp(10), dp(12), dp(6))
        }
        root.addView(search, searchParams)

        filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        root.addView(filterBar, LinearLayout.LayoutParams(-1, -2))
        buildFilters()

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(3, 8, 13))
        }
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(206, 224, 232))
            setPadding(dp(12), dp(12), dp(12), dp(20))
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        scroll.addView(logText, ScrollView.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setBackgroundColor(panel)
        }
        val liveButton = TextView(this).apply {
            text = "● LIVE"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(accent)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener {
                live = !live
                text = if (live) "● LIVE" else "Ⅱ PAUSED"
                setTextColor(if (live) accent else Color.YELLOW)
                if (live) {
                    currentLines = LogBuffer.all().lineSequence().filter(String::isNotBlank).toList()
                    renderLogs()
                }
            }
        }
        footer.addView(liveButton)
        val hint = label("  STREAM: LOCAL ONLY", 10f, muted)
        footer.addView(hint, LinearLayout.LayoutParams(0, -2, 1f))
        val bottomClear = label("CLEAR", 11f, Color.rgb(255, 105, 105), true).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener {
                LogBuffer.clear()
                currentLines = emptyList()
                renderLogs()
            }
        }
        footer.addView(bottomClear)
        root.addView(footer)
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderLogs()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            autoScroll = y >= logText.height - scroll.height - dp(36) || y <= oldY
        }
    }

    private fun buildFilters() {
        filterBar.removeAllViews()
        filters.forEach { filter ->
            val active = filter == selectedFilter
            val button = TextView(this).apply {
                text = filter
                textSize = 9f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(if (active) bg else muted)
                setBackgroundColor(if (active) accent else Color.rgb(15, 32, 43))
                setPadding(dp(9), dp(8), dp(9), dp(8))
                setOnClickListener {
                    selectedFilter = filter
                    buildFilters()
                    renderLogs()
                }
            }
            val params = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            filterBar.addView(button, params)
        }
    }

    private fun category(line: String): String {
        val upper = line.uppercase(Locale.ROOT)
        return when {
            "ERROR" in upper || "FAILED" in upper || "EXCEPTION" in upper || "FATAL" in upper -> "ERROR"
            "WARN" in upper || "RETRY" in upper || "TIMEOUT" in upper || "DEGRADED" in upper -> "WARN"
            "NETWORK_PROBE" in upper || "PROBE" in upper || "RTT=" in upper || "LATENCY" in upper -> "PROBE"
            "SSH" in upper || "HOST KEY" in upper || "SSH-" in upper || "SSH LOGGED" in upper -> "SSH"
            "DNS" in upper || "RESOLVER" in upper || "DNSTT" in upper -> "DNS"
            "CONNECTION" in upper || "TUNNEL" in upper || "VPN" in upper || "SESSION" in upper -> "TUNNEL"
            "AUTH" in upper || "KEY" in upper || "LOGIN" in upper -> "AUTH"
            "NETWORK" in upper || "ROUTE" in upper || "DEVICE" in upper -> "NETWORK"
            else -> "INFO"
        }
    }

    private fun severity(line: String): String {
        val upper = line.uppercase(Locale.ROOT)
        return when {
            "ERROR" in upper || "FAILED" in upper || "EXCEPTION" in upper || "FATAL" in upper -> "ERROR"
            "WARN" in upper || "RETRY" in upper || "TIMEOUT" in upper -> "WARN"
            else -> "INFO"
        }
    }

    private fun sessionState(lines: List<String>): String {
        var state = "IDLE"
        lines.forEach { line ->
            val upper = line.uppercase(Locale.ROOT)
            when {
                "TUNNEL CONNECTED" in upper || "CONNECTION STATE=CONNECTED" in upper -> state = "CONNECTED"
                "TUNNEL DISCONNECTED" in upper || "CONNECTION STATE=DISCONNECTED" in upper ||
                    "CONNECTION STATE=STOPPED" in upper -> state = "STANDBY"
                "CONNECTION STATE=ERROR" in upper || "TUNNEL FAILED" in upper -> state = "FAULT"
            }
        }
        return state
    }

    private fun renderLogs() {
        if (!::logText.isInitialized) return
        val query = search.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val filtered = currentLines.filter { line ->
            (selectedFilter == "ALL" || category(line) == selectedFilter) &&
                (query.isEmpty() || line.lowercase(Locale.ROOT).contains(query))
        }
        val all = currentLines
        val warnCount = all.count { severity(it) == "WARN" }
        val errorCount = all.count { severity(it) == "ERROR" }
        val probes = all.count { category(it) == "PROBE" }
        val dnsEvents = all.count { category(it) == "DNS" }
        summary.text = "EVENTS ${all.size}  •  WARN ${warnCount}  •  ERROR ${errorCount}"
        val state = sessionState(all)
        insight.setTextColor(when (state) {
            "CONNECTED" -> Color.rgb(92, 224, 156)
            "FAULT" -> Color.rgb(255, 92, 106)
            else -> muted
        })
        insight.text = "SESSION: ${state}  •  PROBES: ${probes}  •  DNS EVENTS: ${dnsEvents}"
        val builder = SpannableStringBuilder()
        filtered.forEachIndexed { index, line ->
            val start = builder.length
            val tag = when (category(line)) {
                "ERROR" -> "[ERR] "
                "WARN" -> "[WRN] "
                "PROBE" -> "[RTT] "
                "SSH" -> "[SSH] "
                "DNS" -> "[DNS] "
                "TUNNEL" -> "[VPN] "
                "NETWORK" -> "[NET] "
                "AUTH" -> "[AUTH] "
                else -> "[INF] "
            }
            builder.append(tag).append(line)
            val color = when (category(line)) {
                "ERROR" -> Color.rgb(255, 92, 106)
                "WARN" -> Color.rgb(255, 190, 80)
                "AUTH", "SSH" -> Color.rgb(205, 130, 255)
                "DNS" -> Color.rgb(74, 205, 255)
                "NETWORK", "PROBE" -> Color.rgb(92, 224, 156)
                "TUNNEL" -> accent
                else -> Color.rgb(177, 198, 210)
            }
            builder.setSpan(ForegroundColorSpan(color), start, builder.length, 0)
            val timeEnd = line.indexOf("  ").takeIf { it in 1..12 } ?: -1
            if (timeEnd > 0) builder.setSpan(ForegroundColorSpan(muted), start + tag.length, start + tag.length + timeEnd, 0)
            builder.append(if (index == filtered.lastIndex) "" else "\n")
        }
        logText.text = if (filtered.isEmpty()) {
            if (query.isNotEmpty() || selectedFilter != "ALL") "NO EVENTS MATCH THIS FILTER\nTry another category or search term."
            else "WAITING FOR TELEMETRY…\n\nStart a tunnel to view connection, DNS, authentication and network events."
        } else builder
        if (autoScroll) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        LogBuffer.unlisten(listener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "COPY").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "EXPORT").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 3, 2, "CLEAR").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Android_Ghosts diagnostics", logText.text))
                toast(getString(R.string.copied))
            }
            2 -> {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Android_Ghosts diagnostics $stamp")
                    putExtra(Intent.EXTRA_TEXT, LogBuffer.all())
                }
                startActivity(Intent.createChooser(send, "Export diagnostic log"))
            }
            3 -> {
                LogBuffer.clear()
                currentLines = emptyList()
                renderLogs()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }
}
