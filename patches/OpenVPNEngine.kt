package com.androidghosts.dnsttghosts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus as IcsStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import ghostcore.Ghostcore
import java.io.StringReader

/** Thin native bridge to the bundled ics-openvpn/OpenVPN 3 engine. */
internal object OpenVPNEngine : VpnStatus.StateListener, VpnStatus.ByteCountListener {
    private const val TAG = "GhostsOpenVPN"
    private const val PROFILE_NAME = "Android_Ghosts OpenVPN"
    @Volatile var state = "DISCONNECTED"; private set
    @Volatile var failed = false; private set
    @Volatile var downloadBytes = 0L; private set
    @Volatile var uploadBytes = 0L; private set
    @Volatile var currentProfileName = ""; private set
    @Volatile var lastError = ""; private set
    private lateinit var appContext: Context
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val connectionTimeout = Runnable {
        if (state == "CONNECTING") {
            failed = true
            lastError = "timeout"
            Log.w(TAG, "OpenVPN connection deadline reached; stopping pending connection")
            disconnect(appContext)
        }
    }
    @Volatile private var initialized = false

    // In-app diagnostics history, available without privileged Android logcat access.
    private val diagnosticLock = Any()
    private val diagnosticLines = java.util.ArrayDeque<String>()
    private val maxDiagnosticLines = 250

    fun diagnostics(): String = synchronized(diagnosticLock) { diagnosticLines.joinToString("\n") }

    private fun recordDiagnostic(message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ').take(220)
        if (clean.isBlank()) return
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        synchronized(diagnosticLock) {
            diagnosticLines.addLast("$stamp [OPENVPN] $clean")
            while (diagnosticLines.size > maxDiagnosticLines) diagnosticLines.removeFirst()
        }
    }

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        initialized = true
    }

    fun connect(context: Context, profileName: String, configText: String) {
        initialize(context)
        check(!GhostVpnService.running && !Ghostcore.isRunning()) {
            "Stop the DNSTT tunnel before starting OpenVPN. Android permits only one active VPN tunnel."
        }
        check(VpnService.prepare(context) == null) { "Android VPN permission has not been granted yet." }
        timeoutHandler.removeCallbacks(connectionTimeout)
        state = "CONNECTING"; failed = false; lastError = ""; downloadBytes = 0L; uploadBytes = 0L
        currentProfileName = profileName
        recordDiagnostic("Connection requested for profile: " + profileName.take(80))
        timeoutHandler.postDelayed(connectionTimeout, CONNECTION_DEADLINE_MS)
        try {
            val boundedConfig = applyConnectionLimits(configText)
            val profile: VpnProfile = ConfigParser().run {
                parseConfig(StringReader(boundedConfig))
                convertProfile()
            }
            // Match the upstream ics-openvpn launch path: reject profiles that the
            // engine itself considers incomplete before starting the foreground service.
            val profileCheck = profile.checkProfile(appContext)
            require(profileCheck == de.blinkt.openvpn.R.string.no_error_found) {
                appContext.getString(profileCheck)
            }
            require(!needsKeystoreCertificate(profile)) {
                "This profile requires a client certificate from Android KeyChain. Embed the client certificate and private key inside the .ovpn file or import the matching text certificate/key files together."
            }
            profile.mName = PROFILE_NAME
            profile.mPersistTun = true
            profile.mBlockUnusedAddressFamilies = true
            ProfileManager.setTemporaryProfile(appContext, profile)
            VPNLaunchHelper.startOpenVpn(profile, appContext, "android_ghosts_openvpn", false)
            Log.i(TAG, "OpenVPN start requested; profile contents are not logged")
        } catch (error: Exception) {
            timeoutHandler.removeCallbacks(connectionTimeout)
            state = "DISCONNECTED"; failed = true; lastError = error.message?.take(220) ?: "start_failed"
            recordDiagnostic("Connection start failed: " + (error.message ?: "unknown error"))
            Log.e(TAG, "OpenVPN connect failed", error)
            throw error
        }
    }

    /**
     * Keep the imported profile intact. These retry directives are ignored by the
     * bundled OpenVPN 3 core, so rewriting them here falsely suggests they are enforced
     * and can make exported/debugged profiles differ from what the user imported.
     */
    private fun applyConnectionLimits(config: String): String = config
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    private fun needsKeystoreCertificate(profile: VpnProfile): Boolean = when (profile.mAuthenticationType) {
        VpnProfile.TYPE_KEYSTORE, VpnProfile.TYPE_USERPASS_KEYSTORE, VpnProfile.TYPE_EXTERNAL_APP -> profile.mAlias == null
        else -> false
    }

    fun disconnect(context: Context) {
        initialize(context)
        if (state == "DISCONNECTED") return
        timeoutHandler.removeCallbacks(connectionTimeout)
        state = "DISCONNECTING"; downloadBytes = 0L; uploadBytes = 0L
        val serviceIntent = Intent(appContext, OpenVPNService::class.java).apply { action = OpenVPNService.START_SERVICE }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val stopped = IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
                    recordDiagnostic(if (stopped == true) "Disconnect command accepted" else "Disconnect command sent")
                    // Some backend/service versions do not emit a final callback when stopping
                    // a failed connection. Never leave the UI permanently stuck in DISCONNECTING.
                    timeoutHandler.postDelayed({
                        if (state == "DISCONNECTING") {
                            state = "DISCONNECTED"
                            recordDiagnostic("Disconnect finalized after waiting for service state callback")
                        }
                    }, DISCONNECT_STATE_FALLBACK_MS)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not send OpenVPN disconnect", error)
                    state = "DISCONNECTED"; failed = true
                    lastError = "Disconnect failed: " + (error.message ?: "unknown error").take(160)
                    recordDiagnostic(lastError)
                } finally { runCatching { appContext.unbindService(this) } }
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (state == "DISCONNECTING") state = "DISCONNECTED"
            }
        }
        val bound = runCatching { appContext.bindService(serviceIntent, connection, 0) }.getOrDefault(false)
        if (!bound) {
            state = "DISCONNECTED"
            runCatching { appContext.unbindService(connection) }
        }
    }

    override fun updateState(
        state: String?, logmessage: String?, localizedResId: Int,
        level: IcsStatus?, intent: Intent?,
    ) {
        // Preserve only short, useful diagnostic messages; never log the profile or credentials.
        if (!logmessage.isNullOrBlank() && Regex(
                "(?i)(auth_failed|auth failed|tls|ssl|resolve|unreachable|refused|timed out|timeout|certificate|verify|unsupported option|cannot load|failed|error|fatal|no route|network is down|cannot assign requested address|tun interface|permission denied|cannot open)"
            ).containsMatchIn(logmessage)
        ) {
            lastError = logmessage.trim().take(220)
            recordDiagnostic(lastError)
            Log.w(TAG, "OpenVPN diagnostic: $lastError")
        }
        when (level) {
            IcsStatus.LEVEL_CONNECTED -> { timeoutHandler.removeCallbacks(connectionTimeout); this.state = "CONNECTED"; failed = false; lastError = ""; recordDiagnostic("Tunnel connected successfully") }
            IcsStatus.LEVEL_NOTCONNECTED -> { timeoutHandler.removeCallbacks(connectionTimeout); this.state = "DISCONNECTED"; if (lastError.isNotBlank()) failed = true; recordDiagnostic(if (lastError.isNotBlank()) "Disconnected: $lastError" else "Tunnel disconnected") }
            IcsStatus.LEVEL_AUTH_FAILED -> { timeoutHandler.removeCallbacks(connectionTimeout); this.state = "DISCONNECTED"; failed = true; if (lastError.isBlank()) lastError = "Authentication failed"; recordDiagnostic("Authentication failed: $lastError") }
            IcsStatus.LEVEL_START,
            IcsStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            IcsStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            IcsStatus.LEVEL_WAITING_FOR_USER_INPUT,
            IcsStatus.LEVEL_NONETWORK -> this.state = "CONNECTING"
            else -> if (this.state != "CONNECTED" && this.state != "DISCONNECTING") this.state = "CONNECTING"
        }
    }
    override fun setConnectedVPN(uuid: String?) = Unit
    private const val CONNECTION_DEADLINE_MS = 120_000L
    private const val DISCONNECT_STATE_FALLBACK_MS = 3_000L

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        downloadBytes = inBytes.coerceAtLeast(0L)
        uploadBytes = outBytes.coerceAtLeast(0L)
    }
}
