package com.androidghosts.dnsttghosts

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ghostcore.Ghostcore
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/** Ready .ovpn import/connect screen. The DNSTT service and profiles remain separate. */
class OpenVPNActivity : AppCompatActivity() {
    private data class Profile(val id: String, val name: String, val remote: String, val proto: String, val file: File)
    private data class Document(val name: String, val text: String?)

    private lateinit var status: TextView
    private lateinit var traffic: TextView
    private lateinit var empty: TextView
    private lateinit var list: LinearLayout
    private lateinit var importButton: MaterialButton
    private lateinit var advancedButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private var profiles = emptyList<Profile>()
    private var selectedId: String? = null
    private var pendingName = ""
    private var pendingConfig = ""

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) importProfiles(uris)
    }
    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && pendingConfig.isNotBlank()) startTunnel(pendingName, pendingConfig)
        else { pendingName = ""; pendingConfig = ""; toast(getString(R.string.openvpn_permission_denied)) }
    }
    private val ticker = object : Runnable {
        override fun run() { refreshState(); handler.postDelayed(this, 900) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 21, 16)
        window.navigationBarColor = Color.rgb(7, 13, 10)
        setContentView(R.layout.activity_openvpn)
        status = findViewById(R.id.openVpnStatusView)
        traffic = findViewById(R.id.openVpnTrafficView)
        empty = findViewById(R.id.openVpnEmptyView)
        list = findViewById(R.id.openVpnProfileList)
        importButton = findViewById(R.id.openVpnImportButton)
        advancedButton = findViewById(R.id.openVpnAdvancedButton)
        connectButton = findViewById(R.id.openVpnConnectButton)
        disconnectButton = findViewById(R.id.openVpnDisconnectButton)
        val toolbar = findViewById<MaterialToolbar>(R.id.openVpnToolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, MENU_SWITCH_TUNNEL, 0, getString(R.string.select_tunnel)).apply {
            setIcon(android.R.drawable.ic_menu_manage)
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_SWITCH_TUNNEL) { showTunnelChooser(); true } else false
        }
        importButton.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        advancedButton.setOnClickListener { showAdvancedOptions() }
        connectButton.setOnClickListener { connectSelected() }
        disconnectButton.setOnClickListener { OpenVPNEngine.disconnect(applicationContext); refreshState() }
        OpenVPNEngine.initialize(applicationContext)
        selectedId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SELECTED, null)
        reloadProfiles()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        reloadProfiles()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }
    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun folder() = File(filesDir, "openvpn-profiles").apply { mkdirs() }

    private fun reloadProfiles() {
        profiles = folder().listFiles { f -> f.isFile && f.extension.equals("ovpn", true) }.orEmpty().mapNotNull { f ->
            runCatching {
                val metaFile = File(folder(), f.nameWithoutExtension + ".meta")
                val meta = if (metaFile.isFile) JSONObject(metaFile.readText()) else JSONObject()
                val config = f.readText()
                Profile(
                    f.nameWithoutExtension,
                    meta.optString("name", f.name),
                    meta.optString("remote", firstMatch(REMOTE_RE, config) ?: getString(R.string.openvpn_profile_detail)),
                    meta.optString("proto", firstMatch(PROTO_RE, config) ?: "udp"),
                    f
                )
            }.getOrNull()
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        if (profiles.none { it.id == selectedId }) selectedId = profiles.firstOrNull()?.id
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED, selectedId).apply()
        renderProfiles()
    }

    private fun renderProfiles() {
        list.removeAllViews()
        empty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        profiles.forEach { profile ->
            val selected = profile.id == selectedId
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.rgb(13, 23, 17))
                strokeWidth = dp(if (selected) 2 else 1)
                setStrokeColor(Color.parseColor(if (selected) "#FF39FF14" else "#5555AA66"))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedId = profile.id
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED, selectedId).apply()
                    renderProfiles(); refreshState()
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(9), dp(8), dp(9))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            texts.addView(TextView(this).apply {
                text = profile.name; textSize = 14f
                setTextColor(Color.parseColor(if (selected) "#FF39FF14" else "#FFE7F5E9"))
            })
            texts.addView(TextView(this).apply {
                text = profile.remote + "  •  " + profile.proto.uppercase(Locale.ROOT)
                textSize = 12f; setTextColor(Color.rgb(153, 177, 159)); setPadding(0, dp(4), 0, 0)
            })
            row.addView(texts)
            row.addView(TextView(this).apply {
                text = "×"; textSize = 24f; gravity = Gravity.CENTER
                minWidth = dp(48); minHeight = dp(48)
                setTextColor(Color.rgb(255, 120, 120))
                contentDescription = getString(R.string.openvpn_delete_profile)
                setOnClickListener { deleteProfile(profile) }
            })
            card.addView(row)
            list.addView(card)
        }
        refreshState()
    }

    private fun deleteProfile(profile: Profile) {
        if (OpenVPNEngine.state != "DISCONNECTED") {
            toast(getString(R.string.openvpn_disconnect_before_delete)); return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.openvpn_delete_profile)
            .setMessage(getString(R.string.openvpn_confirm_delete, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                profile.file.delete()
                File(folder(), profile.id + ".meta").delete()
                if (selectedId == profile.id) selectedId = null
                reloadProfiles()
            }.show()
    }

    private fun importProfiles(uris: List<Uri>) {
        importButton.isEnabled = false
        Thread {
            try {
                val documents = uris.map { readDocument(it) }
                val configs = documents.filter { it.name.endsWith(".ovpn", true) || it.name.endsWith(".conf", true) }
                require(configs.isNotEmpty()) { getString(R.string.openvpn_select_config) }
                val saved = configs.map { document ->
                    val text = document.text ?: throw IllegalArgumentException(getString(R.string.openvpn_read_failed, document.name))
                    val complete = inlineSidecars(text, documents.filter { it.name != document.name })
                    validateConfig(complete)
                    saveProfile(document.name, complete)
                }
                runOnUiThread {
                    selectedId = saved.firstOrNull()?.id ?: selectedId
                    reloadProfiles()
                    toast(getString(R.string.openvpn_import_count, saved.size))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.openvpn_import_failed)
                        .setMessage(error.message ?: getString(R.string.openvpn_invalid_profile))
                        .setPositiveButton(R.string.ok, null).show()
                }
            } finally { runOnUiThread { importButton.isEnabled = true } }
        }.start()
    }

    private fun readDocument(uri: Uri): Document {
        val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor: Cursor? ->
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported-file"
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                require(total <= MAX_FILE_BYTES) { getString(R.string.openvpn_file_too_large, displayName) }
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException(getString(R.string.openvpn_read_failed, displayName))
        val isBinary = displayName.endsWith(".p12", true) || displayName.endsWith(".pfx", true) || bytes.take(2048).any { it == 0.toByte() }
        return Document(displayName, if (isBinary) null else String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
    }

    private fun inlineSidecars(config: String, docs: List<Document>): String {
        val lookup = docs.groupBy { it.name.substringAfterLast('/').lowercase(Locale.ROOT) }
        val missing = linkedSetOf<String>()
        val result = SIDECAR_RE.replace(config) { match ->
            val directive = match.groupValues[1].lowercase(Locale.ROOT)
            val referenced = match.groupValues[2].ifBlank { match.groupValues[3] }
            val option = match.groupValues[4]
            val sidecar = lookup[referenced.substringAfterLast('/').lowercase(Locale.ROOT)].orEmpty().singleOrNull()
            if (sidecar == null) {
                missing += referenced
                match.value
            } else if (sidecar.text == null) {
                missing += referenced + " (binary file)"
                match.value
            } else {
                val content = sidecar.text.trim()
                when (directive) {
                    "auth-user-pass" -> {
                        val lines = content.lines()
                        if (lines.size < 2 || lines[0].isBlank()) { missing += referenced; match.value }
                        else "auth-user-pass " + writeAuthCredentials(lines[0], lines[1]).absolutePath
                    }
                    else -> {
                        if (content.isBlank()) { missing += referenced; match.value }
                        else {
                            val block = "<" + directive + ">\n" + content + "\n</" + directive + ">"
                            if (directive == "tls-auth" && option.isNotBlank()) block + "\nkey-direction " + option else block
                        }
                    }
                }
            }
        }
        val unsupported = UNSUPPORTED_EXTERNAL_RE.findAll(result).map { it.value.trim() }.toList()
        missing.addAll(unsupported)
        require(missing.isEmpty()) {
            getString(R.string.openvpn_sidecars_missing, missing.take(6).joinToString(", "))
        }
        return result
    }

    private fun validateConfig(config: String) {
        require(config.isNotBlank() && !config.startsWith("PK\u0003\u0004")) { getString(R.string.openvpn_invalid_profile) }
        require(REMOTE_RE.containsMatchIn(config)) { getString(R.string.openvpn_no_remote) }
        require(!Regex("(?im)^\\s*(?:server|tls-server)\\s*$").containsMatchIn(config)) {
            getString(R.string.openvpn_server_config)
        }
        require(!UNSUPPORTED_EXTERNAL_RE.containsMatchIn(config)) {
            getString(R.string.openvpn_sidecars_missing, UNSUPPORTED_EXTERNAL_RE.find(config)?.value ?: "")
        }
    }

    /** OpenVPN expects auth-user-pass to point to a credentials file; it does not accept an inline XML-like block. */
    private fun writeAuthCredentials(username: String, password: String, profileId: String? = null): File {
        // Keep credentials in app-private storage, stable per profile so they survive reconnects.
        // The OS sandbox prevents other ordinary apps from reading this file.
        val file = File(folder(), profileId?.let { "auth-$it.txt" } ?: ("auth-" + UUID.randomUUID().toString() + ".txt"))
        file.writeText(username + "\n" + password + "\n", StandardCharsets.UTF_8)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return file
    }

    private fun saveProfile(originalName: String, config: String): Profile {
        val id = UUID.randomUUID().toString()
        val name = originalName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "OpenVPN profile" }.take(80)
        val file = File(folder(), id + ".ovpn")
        val remote = firstMatch(REMOTE_RE, config) ?: "OpenVPN profile"
        val proto = firstMatch(PROTO_RE, config) ?: "udp"
        file.writeText(config, StandardCharsets.UTF_8)
        File(folder(), id + ".meta").writeText(JSONObject().put("name", name).put("remote", remote).put("proto", proto).toString())
        return Profile(id, name, remote, proto, file)
    }

    private fun showTunnelChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_tunnel)
            .setItems(arrayOf(getString(R.string.select_dnstt), getString(R.string.select_openvpn))) { _, which ->
                if (which == 0) {
                    if (OpenVPNEngine.state != "DISCONNECTED") {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.openvpn_disconnect)
                            .setMessage(R.string.openvpn_switch_disconnect_first)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.openvpn_disconnect) { _, _ ->
                                OpenVPNEngine.disconnect(applicationContext)
                                waitForDisconnectAndFinish(0)
                            }.show()
                    } else finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun waitForDisconnectAndFinish(attempt: Int) {
        if (OpenVPNEngine.state == "DISCONNECTED") { finish(); return }
        if (attempt >= 20) {
            toast(getString(R.string.openvpn_switch_disconnect_timeout))
            return
        }
        handler.postDelayed({ waitForDisconnectAndFinish(attempt + 1) }, 250)
    }

    private fun showAdvancedOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.openvpn_advanced_title)
            .setItems(arrayOf(getString(R.string.openvpn_full_config_editor), getString(R.string.openvpn_transport_assistant))) { _, which ->
                if (which == 0) showAdvancedEditor() else showTransportAssistant()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Transport compatibility helper, not a universal DPI bypass. TCP/443 remains
     * identifiable as OpenVPN unless the server also supports a compatible wrapper.
     */
    private fun showTransportAssistant() {
        val profile = profiles.firstOrNull { it.id == selectedId }
        if (profile == null) { toast(getString(R.string.openvpn_choose_profile)); return }
        if (OpenVPNEngine.state != "DISCONNECTED") {
            toast(getString(R.string.openvpn_advanced_disconnect_first)); return
        }
        val config = runCatching { profile.file.readText(StandardCharsets.UTF_8) }
            .getOrElse { toast(getString(R.string.openvpn_read_failed, profile.name)); return }
        val currentProto = firstMatch(PROTO_RE, config)?.lowercase(Locale.ROOT) ?: "udp"
        val currentPort = REMOTE_PORT_RE.find(config)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: if (currentProto.startsWith("tcp")) 443 else 1194
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val mode = android.widget.Spinner(this)
        mode.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.openvpn_transport_udp), getString(R.string.openvpn_transport_tcp)))
        mode.setSelection(if (currentProto.startsWith("tcp")) 1 else 0)
        val port = EditText(this).apply {
            hint = getString(R.string.openvpn_remote_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(currentPort.toString())
        }
        val mtu = EditText(this).apply {
            hint = getString(R.string.openvpn_tun_mtu)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(firstMatch(TUN_MTU_RE, config) ?: "1500")
        }
        val mss = EditText(this).apply {
            hint = getString(R.string.openvpn_mssfix)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(firstMatch(MSSFIX_RE, config) ?: "1360")
        }
        box.addView(TextView(this).apply { text = getString(R.string.openvpn_transport_warning); setTextColor(Color.rgb(220, 190, 110)); setPadding(0, 0, 0, dp(10)) })
        box.addView(TextView(this).apply { text = getString(R.string.openvpn_transport_label) })
        box.addView(mode, LinearLayout.LayoutParams(-1, dp(52)))
        box.addView(port, LinearLayout.LayoutParams(-1, dp(54)))
        box.addView(mtu, LinearLayout.LayoutParams(-1, dp(54)))
        box.addView(mss, LinearLayout.LayoutParams(-1, dp(54)))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.openvpn_transport_assistant)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.openvpn_save_advanced, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = port.text.toString().toIntOrNull()
                val m = mtu.text.toString().toIntOrNull()
                val ms = mss.text.toString().toIntOrNull()
                if (p == null || p !in 1..65535 || m == null || m !in 1200..9000 || ms == null || ms !in 500..1460) {
                    toast(getString(R.string.openvpn_transport_invalid)); return@setOnClickListener
                }
                val tcp = mode.selectedItemPosition == 1
                var updated = config
                val proto = if (tcp) "tcp-client" else "udp"
                updated = if (PROTO_RE.containsMatchIn(updated)) PROTO_RE.replaceFirst(updated, "proto $proto")
                    else "proto $proto\\n" + updated
                val remotePortMatch = REMOTE_PORT_RE.find(updated)
                updated = if (remotePortMatch != null) {
                    val relativeStart = remotePortMatch.groups[2]!!.range.first - remotePortMatch.range.first
                    val start = remotePortMatch.range.first + relativeStart
                    updated.replaceRange(start, start + remotePortMatch.groupValues[2].length, p.toString())
                } else {
                    val remoteMatch = REMOTE_RE.find(updated)
                    if (remoteMatch != null) updated.replaceRange(remoteMatch.range.last + 1, remoteMatch.range.last + 1, " $p")
                    else updated
                }
                updated = setDirective(updated, TUN_MTU_RE, "tun-mtu", m.toString())
                // mssfix is a UDP-side tuning option; remove it for TCP to avoid a misleading setting.
                updated = if (tcp) MSSFIX_RE.replace(updated, "") else setDirective(updated, MSSFIX_RE, "mssfix", ms.toString())
                try {
                    profile.file.writeText(updated, StandardCharsets.UTF_8)
                    File(folder(), profile.id + ".meta").writeText(
                        JSONObject().put("name", profile.name)
                            .put("remote", firstMatch(REMOTE_RE, updated) ?: profile.remote)
                            .put("proto", proto).toString()
                    )
                    dialog.dismiss()
                    reloadProfiles()
                    toast(getString(R.string.openvpn_advanced_saved))
                } catch (error: Exception) {
                    toast(error.message ?: getString(R.string.openvpn_invalid_profile))
                }
            }
        }
        dialog.show()
    }

    private fun setDirective(config: String, regex: Regex, name: String, value: String): String {
        return if (regex.containsMatchIn(config)) regex.replaceFirst(config, "$name $value")
        else config.trimEnd() + "\\n$name $value\\n"
    }

    private fun showAdvancedEditor() {
        val profile = profiles.firstOrNull { it.id == selectedId }
        if (profile == null) { toast(getString(R.string.openvpn_choose_profile)); return }
        if (OpenVPNEngine.state != "DISCONNECTED") {
            toast(getString(R.string.openvpn_advanced_disconnect_first)); return
        }
        val editor = EditText(this).apply {
            setText(profile.file.readText(StandardCharsets.UTF_8))
            setSelection(0)
            minLines = 14
            maxLines = 24
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
            addView(editor, LinearLayout.LayoutParams(-1, dp(380)))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.openvpn_advanced_title)
            .setMessage(R.string.openvpn_advanced_help)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.openvpn_save_advanced, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val config = editor.text.toString()
                try {
                    require(config.isNotBlank() && REMOTE_RE.containsMatchIn(config)) {
                        getString(R.string.openvpn_no_remote)
                    }
                    require(!Regex("(?im)^[ \\t]*(?:server|tls-server)[ \\t]*$").containsMatchIn(config)) {
                        getString(R.string.openvpn_server_config)
                    }
                    profile.file.writeText(config, StandardCharsets.UTF_8)
                    File(folder(), profile.id + ".meta").writeText(
                        JSONObject().put("name", profile.name)
                            .put("remote", firstMatch(REMOTE_RE, config) ?: profile.remote)
                            .put("proto", firstMatch(PROTO_RE, config) ?: profile.proto).toString()
                    )
                    dialog.dismiss()
                    reloadProfiles()
                    toast(getString(R.string.openvpn_advanced_saved))
                } catch (error: Exception) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.openvpn_import_failed)
                        .setMessage(error.message ?: getString(R.string.openvpn_invalid_profile))
                        .setPositiveButton(R.string.ok, null).show()
                }
            }
        }
        dialog.show()
    }

    private fun connectSelected() {
        val p = profiles.firstOrNull { it.id == selectedId }
        if (p == null) { toast(getString(R.string.openvpn_choose_profile)); return }
        if (OpenVPNEngine.state != "DISCONNECTED") { toast(getString(R.string.openvpn_already_active)); return }
        if (GhostVpnService.running || Ghostcore.isRunning()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.openvpn_vpn_conflict_title)
                .setMessage(R.string.openvpn_stop_dnstt_first).setPositiveButton(R.string.ok, null).show()
            return
        }
        val cm = getSystemService(ConnectivityManager::class.java)
        val anotherVpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        if (anotherVpn) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.openvpn_vpn_conflict_title)
                .setMessage(R.string.openvpn_close_other_vpn).setPositiveButton(R.string.ok, null).show()
            return
        }
        val config = runCatching { p.file.readText(StandardCharsets.UTF_8) }
            .getOrElse { toast(getString(R.string.openvpn_read_failed, p.name)); return }
        if (AUTH_USER_PASS_RE.containsMatchIn(config) && !INLINE_AUTH_RE.containsMatchIn(config)) askCredentials(p.id, p.name, config)
        else requestVpnConsent(p.name, config)
    }

    private fun askCredentials(profileId: String, name: String, config: String) {
        val savedCredentials = runCatching {
            File(folder(), "auth-$profileId.txt").takeIf { it.isFile }?.readLines(StandardCharsets.UTF_8)
        }.getOrNull()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val user = EditText(this).apply {
            hint = getString(R.string.openvpn_username); setSingleLine(true)
            setText(savedCredentials?.getOrNull(0).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        val pass = EditText(this).apply {
            hint = getString(R.string.openvpn_password); setSingleLine(true)
            setText(savedCredentials?.getOrNull(1).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(user, LinearLayout.LayoutParams(-1, dp(54)))
        box.addView(pass, LinearLayout.LayoutParams(-1, dp(54)))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.openvpn_auth_title).setView(box)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.openvpn_continue, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val u = user.text.toString(); val p = pass.text.toString()
                if (u.isBlank() || p.isBlank() || u.contains('\n') || u.contains('\r') || p.contains('\n') || p.contains('\r')) {
                    toast(getString(R.string.openvpn_credentials_invalid)); return@setOnClickListener
                }
                val credentialFile = writeAuthCredentials(u, p, profileId)
                val inline = AUTH_USER_PASS_RE.replace(config) {
                    "auth-user-pass " + credentialFile.absolutePath
                }
                dialog.dismiss()
                requestVpnConsent(name, inline)
            }
        }
        dialog.show()
    }

    private fun requestVpnConsent(name: String, config: String) {
        pendingName = name; pendingConfig = config
        val consent = VpnService.prepare(this)
        if (consent == null) startTunnel(name, config) else consentLauncher.launch(consent)
    }

    private fun startTunnel(name: String, config: String) {
        pendingName = ""; pendingConfig = ""
        connectButton.isEnabled = false; importButton.isEnabled = false
        status.setText(R.string.openvpn_connecting)
        Thread {
            try {
                OpenVPNEngine.connect(applicationContext, name, config)
                runOnUiThread { refreshState() }
            } catch (error: Exception) {
                runOnUiThread {
                    MaterialAlertDialogBuilder(this).setTitle(R.string.openvpn_connect_failed)
                        .setMessage(error.message ?: getString(R.string.openvpn_invalid_profile))
                        .setPositiveButton(R.string.ok, null).show()
                    refreshState()
                }
            } finally { runOnUiThread { importButton.isEnabled = true } }
        }.start()
    }

    private fun refreshState() {
        val s = OpenVPNEngine.state
        status.text = when {
            OpenVPNEngine.lastError == "timeout" -> getString(R.string.openvpn_timeout)
            OpenVPNEngine.failed -> getString(R.string.openvpn_failed)
            s == "CONNECTED" -> getString(R.string.openvpn_connected) + " • " + OpenVPNEngine.currentProfileName
            s == "CONNECTING" -> getString(R.string.openvpn_connecting)
            s == "DISCONNECTING" -> getString(R.string.openvpn_disconnecting)
            else -> getString(R.string.openvpn_disconnected)
        }
        status.setTextColor(when {
            OpenVPNEngine.failed -> Color.rgb(255, 120, 120)
            s == "CONNECTED" -> Color.parseColor("#FF39FF14")
            s == "CONNECTING" || s == "DISCONNECTING" -> Color.rgb(255, 202, 40)
            else -> Color.rgb(153, 177, 159)
        })
        traffic.text = getString(R.string.openvpn_traffic, formatBytes(OpenVPNEngine.downloadBytes), formatBytes(OpenVPNEngine.uploadBytes))
        val busy = s != "DISCONNECTED"
        connectButton.isEnabled = !busy && profiles.isNotEmpty()
        disconnectButton.isEnabled = busy
        importButton.isEnabled = !busy
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return bytes.toString() + " B"
        var value = bytes.toDouble(); var index = -1
        val units = arrayOf("KB", "MB", "GB", "TB")
        do { value /= 1024.0; index++ } while (value >= 1024.0 && index < units.lastIndex)
        return String.format(Locale.ROOT, "%.1f %s", value, units[index.coerceAtMost(units.lastIndex)])
    }
    private fun firstMatch(regex: Regex, value: String): String? = regex.find(value)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val MENU_SWITCH_TUNNEL = 7101
        private const val PREFS = "openvpn_profiles"
        private const val KEY_SELECTED = "selected_id"
        private const val MAX_FILE_BYTES = 5 * 1024 * 1024
        private val REMOTE_RE = Regex("(?im)^[ \\t]*remote[ \\t]+([^ \\t\\r\\n#]+)")
        private val PROTO_RE = Regex("(?im)^[ \\t]*proto[ \\t]+([^ \\t\\r\\n#]+)")
        private val REMOTE_PORT_RE = Regex("(?im)^[ \\t]*remote[ \\t]+([^ \\t\\r\\n#]+)[ \\t]+([0-9]{1,5})(?:[ \\t]+[^\\r\\n#]+)?")
        private val TUN_MTU_RE = Regex("(?im)^[ \\t]*tun-mtu[ \\t]+([0-9]+)")
        private val MSSFIX_RE = Regex("(?im)^[ \\t]*mssfix(?:[ \\t]+([0-9]+))?[^\\r\\n]*")
        private val SIDECAR_RE = Regex("(?im)^[ \\t]*(ca|cert|key|tls-auth|tls-crypt|secret|auth-user-pass)[ \\t]+(?:\\\"([^\\\"]+)\\\"|([^ \\t\\r\\n#]+))(?:[ \\t]+([^ \\t\\r\\n#]+))?[^\\r\\n]*")
        private val UNSUPPORTED_EXTERNAL_RE = Regex("(?im)^[ \\t]*(pkcs12|crl-verify|extra-certs|dh)[ \\t]+(?:\\\"([^\\\"]+)\\\"|([^ \\t\\r\\n#]+))[^\\r\\n]*")
        private val AUTH_USER_PASS_RE = Regex("(?im)^[ \\t]*auth-user-pass[ \\t]*(?:#.*)?")
        private val INLINE_AUTH_RE = Regex("(?is)<auth-user-pass>.*?</auth-user-pass>")
    }
}
