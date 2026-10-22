package com.androidghosts.dnsttghosts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ghostcore.Ghostcore
import org.json.JSONObject
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.File

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectButton: View
    private lateinit var connectText: TextView
    private lateinit var statusText: TextView
    private lateinit var profileText: TextView
    private lateinit var emptyText: TextView
    private lateinit var statsGrid: GridLayout
    private lateinit var trafficChart: TrafficChartView
    private lateinit var chartSummary: TextView
    private lateinit var chartWindowButton: com.google.android.material.button.MaterialButton
    private lateinit var list: RecyclerView
    private val adapter = ProfileAdapter()
    private val statViews = mutableMapOf<String, TextView>()
    private var lastUp = 0L
    private var lastDown = 0L
    private var stopping = false
    private var peakUp = 0L
    private var peakDown = 0L
    private var showSessionChart = false
    private var pendingJson = ""
    private var pendingQrPng = ByteArray(0)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) GhostVpnService.start(this) else toast(getString(R.string.vpn_denied))
    }
    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshList() }
    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.let { importText(it) } ?: toast(getString(R.string.import_fail))
    }
    private val saveJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeExport(uri, pendingJson)
    }
    private val saveQrLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runCatching { requireNotNull(contentResolver.openOutputStream(uri)).use { it.write(pendingQrPng) } }
            .onFailure { toast(getString(R.string.export_failed, it.message ?: "")) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        findViewById<View>(R.id.telegramLink).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Android_Ghosts"))) }
                .onFailure { toast(getString(R.string.brand_telegram)) }
        }
        connectButton = findViewById(R.id.connectButton)
        connectText = findViewById(R.id.connectText)
        statusText = findViewById(R.id.statusText)
        profileText = findViewById(R.id.profileText)
        emptyText = findViewById(R.id.emptyText)
        statsGrid = findViewById(R.id.statsGrid)
        trafficChart = findViewById(R.id.trafficChart)
        chartSummary = findViewById(R.id.chartSummary)
        chartWindowButton = findViewById(R.id.chartWindowButton)
        chartWindowButton.setOnClickListener {
            showSessionChart = !showSessionChart
            trafficChart.setFullSession(showSessionChart)
            chartWindowButton.setText(if (showSessionChart) R.string.chart_last5 else R.string.chart_session)
        }
        list = findViewById(R.id.profileList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        connectButton.setOnClickListener { toggle() }
        findViewById<View>(R.id.fab).setOnClickListener {
            editLauncher.launch(Intent(this, ProfileEditActivity::class.java))
        }
        findViewById<View>(R.id.testButton).setOnClickListener { testLatency() }

        listOf(
            "up" to R.string.stat_upload, "down" to R.string.stat_download,
            "queries" to R.string.stat_queries, "conns" to R.string.stat_conns,
            "segment" to R.string.stat_segment, "uptime" to R.string.stat_uptime,
            "lookups" to R.string.stat_lookups, "sessions" to R.string.stat_sessions, "tunnels" to R.string.stat_tunnels,
            "rtt" to R.string.stat_rtt, "jitter" to R.string.stat_jitter, "loss" to R.string.stat_loss
        ).forEach { (k, res) -> addStat(k, getString(res)) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        importText(data)
    }

    private fun addStat(key: String, label: String) {
        val box = layoutInflater.inflate(android.R.layout.simple_list_item_2, statsGrid, false)
        val t1 = box.findViewById<TextView>(android.R.id.text1)
        val t2 = box.findViewById<TextView>(android.R.id.text2)
        t1.text = "—"; t1.textSize = 15f
        t2.text = label; t2.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
        box.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        statsGrid.addView(box)
        statViews[key] = t1
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun toggle() {
        if (stopping) return
        if (GhostVpnService.running || Ghostcore.isRunning()) {
            stopping = true
            statusText.text = getString(R.string.status_stopping)
            connectText.text = getString(R.string.disconnecting)
            GhostVpnService.stop(this)
            return
        }
        val p = Profiles.selected(this) ?: return toast(getString(R.string.select_profile))
        val cm = getSystemService(ConnectivityManager::class.java)
        val otherVpn = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        if (otherVpn != null) {
            val iface = cm.getLinkProperties(otherVpn)?.interfaceName?.takeIf { it.isNotBlank() } ?: getString(R.string.unknown_interface)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vpn_conflict_title)
                .setMessage(getString(R.string.vpn_conflict_message, iface))
                .setPositiveButton(R.string.ok, null).show()
            return
        }
        Profiles.select(this, p.optString("id"))
        val multiConfigured = AppSettings.selectedMultiProfiles(this).size >= 2
        val needVpn = multiConfigured || (p.optString("mode") != "forward" && !AppSettings.proxyOnly(this))
        if (needVpn && AppSettings.splitMode(this) == "include" && AppSettings.splitApps(this).none { it != packageName }) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.s_split)
                .setMessage(R.string.s_split_include_empty)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(getString(R.string.s_select_apps, AppSettings.splitApps(this).size)) { _, _ -> startActivity(Intent(this, AppsActivity::class.java)) }
                .show()
            return
        }
        if (needVpn && !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("vpn_disclosure_accepted", false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vpn_disclosure_title)
                .setMessage(R.string.vpn_disclosure_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.vpn_disclosure_accept) { _, _ ->
                    getSharedPreferences("settings", MODE_PRIVATE).edit()
                        .putBoolean("vpn_disclosure_accepted", true).apply()
                    requestVpnPermission()
                }
                .show()
            return
        }
        if (needVpn) requestVpnPermission() else GhostVpnService.start(this)
    }

    private fun requestVpnPermission() {
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep) else GhostVpnService.start(this)
    }

    private fun refreshStatus() {
        val running = Ghostcore.isRunning()
        if (!running) stopping = false
        connectButton.isActivated = running
        connectText.text = getString(if (running) R.string.disconnect else R.string.connect)
        val st = Ghostcore.status()
        statusText.text = if (!running && GhostVpnService.lastError != null)
            getString(R.string.status_error) + ": " + GhostVpnService.lastError
        else Fmt.status(this, st)
        val p = Profiles.selected(this)
        val multiCount = AppSettings.selectedMultiProfiles(this).size
        profileText.text = when {
            multiCount > 1 -> getString(R.string.multi_running_profiles, multiCount)
            p == null -> ""
            running && p.optString("mode") == "forward" -> getString(R.string.forward_running, p.optString("localForward"))
            running && AppSettings.proxyOnly(this) -> getString(R.string.proxy_only_running, p.optString("localSocks"))
            else -> Profiles.displayName(this, p)
        }
        val s = runCatching { JSONObject(Ghostcore.stats()) }.getOrDefault(JSONObject())
        if (!running) {
            statViews.values.forEach { it.text = "—" }
            lastUp = 0; lastDown = 0
            peakUp = 0; peakDown = 0
            chartSummary.text = ""
            trafficChart.clear()
            return
        }
        val up = s.optLong("bytesUp"); val down = s.optLong("bytesDown")
        val upDelta = up - lastUp
        val downDelta = down - lastDown
        statViews["up"]?.text = "${Fmt.bytes(up)} · ${Fmt.speed(upDelta)}"
        statViews["down"]?.text = "${Fmt.bytes(down)} · ${Fmt.speed(downDelta)}"
        val ping = PingMetrics.snapshot()
        trafficChart.push(upDelta, downDelta, ping.lastRttMs)
        peakUp = maxOf(peakUp, upDelta)
        peakDown = maxOf(peakDown, downDelta)
        chartSummary.text = getString(
            R.string.traffic_summary,
            Fmt.speed(upDelta), Fmt.speed(downDelta), Fmt.speed(peakUp), Fmt.speed(peakDown),
            Fmt.speed(up / maxOf(1L, (System.currentTimeMillis() - s.optLong("startedAt")) / 1000L)),
            Fmt.speed(down / maxOf(1L, (System.currentTimeMillis() - s.optLong("startedAt")) / 1000L))
        )
        lastUp = up; lastDown = down
        statViews["queries"]?.text = "${s.optLong("queriesSent")} / ${s.optLong("queriesRecv")}"
        statViews["conns"]?.text = "${s.optLong("activeConns")} (${s.optLong("totalConns")})"
        statViews["segment"]?.text = "${s.optLong("mtu")} B"
        val started = s.optLong("startedAt")
        statViews["uptime"]?.text = if (started > 0) Fmt.duration(System.currentTimeMillis() - started) else "—"
        statViews["lookups"]?.text = s.optLong("dnsLookups").toString()
        statViews["sessions"]?.text = s.optLong("sessions").toString()
        statViews["tunnels"]?.text = s.optLong("tunnelCount").coerceAtLeast(1).toString()
        statViews["rtt"]?.text = if (ping.sent > 0) "${ping.lastRttMs} ms / avg ${ping.averageRttMs} ms" else "—"
        statViews["jitter"]?.text = if (ping.sent > 0) "${ping.jitterMs} ms" else "—"
        statViews["loss"]?.text = if (ping.sent > 0) "${ping.lossPercent}%" else "—"
    }

    private fun testLatency() {
        if (!Ghostcore.isRunning()) return toast(getString(R.string.status_stopped))
        toast(getString(R.string.test_latency) + "…")
        Thread {
            val msg = try {
                val rtt = Ghostcore.testLatency("", 20000)
                PingMetrics.record(PingProbe.Result(true, rtt, "HTTP tunnel"))
                getString(R.string.latency_result, rtt)
            } catch (e: Exception) {
                PingMetrics.record(PingProbe.Result(false, 0, e.message ?: "test failed"))
                getString(R.string.latency_fail, e.message)
            }
            runOnUiThread { MaterialAlertDialogBuilder(this).setMessage(msg).setPositiveButton(R.string.ok, null).show() }
        }.start()
    }

    private fun refreshList() {
        adapter.items = Profiles.list(this)
        adapter.selected = Profiles.selected(this)?.optString("id")
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        val selectedCount = AppSettings.selectedMultiProfiles(this).size
        if (!Ghostcore.isRunning() && selectedCount > 1) {
            profileText.text = getString(R.string.multi_selected_profiles, selectedCount)
        }
    }

    private fun importText(text: String) {
        val p = Profiles.parse(text)
        if (p == null) {
            toast(getString(R.string.import_fail)); return
        }
        Profiles.save(this, p)
        Profiles.select(this, p.optString("id"))
        refreshList()
        toast(getString(R.string.import_ok))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_import -> {
                val clip = clipboard().primaryClip
                importText(clip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
            }
            R.id.m_import_file -> importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            R.id.m_logs -> startActivity(Intent(this, LogActivity::class.java))
            R.id.m_export -> exportProfile()
            R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.m_keygen -> showKeygen()
            R.id.m_about -> MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.app_name) + " " + packageManager.getPackageInfo(packageName, 0).versionName)
                .setMessage(getString(R.string.about_text) + "\n\n" + Ghostcore.version())
                .setPositiveButton(R.string.ok, null).show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun exportProfile() {
        val p = Profiles.selected(this) ?: return toast(getString(R.string.select_profile))
        val link = Profiles.toLink(p)
        pendingJson = Profiles.toProfileJson(p)
        // Credential-bearing profiles (notably SSH private keys) can exceed QR capacity.
        // Keep link/JSON export available when a QR cannot safely encode the full profile.
        pendingQrPng = runCatching {
            qrBitmap(link, 720).let { bitmap ->
                java.io.ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
            }
        }.getOrElse {
            LogBuffer.add("Profile QR unavailable: ${it.message ?: "profile link too large"}")
            ByteArray(0)
        }
        val hasQr = pendingQrPng.isNotEmpty()
        val density = resources.displayMetrics.density
        val image = if (hasQr) ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeByteArray(pendingQrPng, 0, pendingQrPng.size))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams((200 * density).toInt(), (200 * density).toInt()).apply { gravity = android.view.Gravity.CENTER }
            contentDescription = getString(R.string.export_profile_title)
        } else null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (8 * density).toInt())
            addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.export_profile_note)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            })
            if (image != null) addView(image) else addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.export_qr_too_large)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            })
            addView(android.widget.TextView(this@MainActivity).apply {
                text = link
                setTextIsSelectable(true)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                textSize = 11f
            })
            if (hasQr) {
                addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.copy_qr)
                    setOnClickListener { copyQr() }
                })
                addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                    text = getString(R.string.save_qr)
                    setOnClickListener { saveQrLauncher.launch("profile-qr.png") }
                })
            }
            addView(com.google.android.material.button.MaterialButton(this@MainActivity).apply {
                text = getString(R.string.share_safe_json)
                setOnClickListener { shareProfileJson(pendingJson) }
            })
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialogBuilder = MaterialAlertDialogBuilder(this).setTitle(R.string.export_profile_title).setView(scroll)
            .setPositiveButton(R.string.copy_link) { _, _ -> copy(link) }
            .setNegativeButton(R.string.save_json) { _, _ -> saveJsonLauncher.launch("profile.json") }
        if (hasQr) dialogBuilder.setNeutralButton(R.string.share_qr) { _, _ -> shareQr() }
        val dialog = dialogBuilder.create()
        dialog.setOnShowListener { dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92).toInt(), (resources.displayMetrics.heightPixels * .88).toInt()) }
        dialog.show()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun sharedUri(name: String, bytes: ByteArray): Uri {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name).apply { writeBytes(bytes) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun copyQr() {
        val uri = sharedUri("profile-qr.png", pendingQrPng)
        clipboard().setPrimaryClip(ClipData.newUri(contentResolver, "Profile QR", uri))
        toast(getString(R.string.copied))
    }

    private fun shareQr() {
        val uri = sharedUri("profile-qr.png", pendingQrPng)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "Profile QR", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_qr)))
    }

    private fun shareProfileJson(json: String) {
        val uri = sharedUri("profile.json", json.toByteArray(Charsets.UTF_8))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "Complete profile JSON", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_safe_json)))
    }

    private fun writeExport(uri: Uri, text: String) {
        runCatching { requireNotNull(contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
            .onSuccess { toast(getString(R.string.export_saved)) }
            .onFailure { toast(getString(R.string.export_failed, it.message ?: "")) }
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share)))
    }

    private fun showKeygen() {
        val k = JSONObject(Ghostcore.generateKeypair())
        val text = "privkey (server.key):\n${k.getString("privkey")}\n\npubkey (server.pub):\n${k.getString("pubkey")}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.keygen)
            .setMessage(text)
            .setPositiveButton(R.string.copy) { _, _ -> copy(text) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copy(text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("dnstt", text))
        toast(getString(R.string.copied))
    }

    private fun onProfileMenu(anchor: View, p: JSONObject) {
        val pm = PopupMenu(this, anchor)
        pm.menuInflater.inflate(R.menu.profile_item, pm.menu)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.p_edit -> editLauncher.launch(
                    Intent(this, ProfileEditActivity::class.java).putExtra("profile", p.toString())
                )
                R.id.p_dup -> { Profiles.duplicate(this, p); refreshList() }
                R.id.p_share -> startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Profiles.toLink(p)),
                    getString(R.string.share)
                ))
                R.id.p_copy -> copy(Profiles.toLink(p))
                R.id.p_delete -> MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> Profiles.delete(this, p.optString("id")); refreshList() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            true
        }
        pm.show()
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileAdapter.VH>() {
        var items: List<JSONObject> = emptyList()
        var selected: String? = null

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val radio: RadioButton = v.findViewById(R.id.radio)
            val name: TextView = v.findViewById(R.id.name)
            val detail: TextView = v.findViewById(R.id.detail)
            val more: ImageButton = v.findViewById(R.id.more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val p = items[i]
            val id = p.optString("id")
            h.name.text = Profiles.displayName(this@MainActivity, p)
            h.detail.text = "${p.optString("transport").uppercase()} · ${p.optString("mode").uppercase()} · ${p.optString("domain")}"
            h.radio.isChecked = id == selected
            h.itemView.setOnClickListener {
                Profiles.select(this@MainActivity, id)
                selected = id
                notifyDataSetChanged()
            }
            h.more.setOnClickListener { onProfileMenu(it, p) }
        }
    }
}
