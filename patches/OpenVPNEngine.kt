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
        timeoutHandler.postDelayed(connectionTimeout, CONNECTION_DEADLINE_MS)
        try {
            val boundedConfig = applyConnectionLimits(configText)
            val profile: VpnProfile = ConfigParser().run {
                parseConfig(StringReader(boundedConfig))
                convertProfile()
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
            state = "DISCONNECTED"; failed = true; lastError = "start_failed"
            Log.e(TAG, "OpenVPN connect failed", error)
            throw error
        }
    }

    /** Replace unbounded retry directives while preserving the rest of the imported profile. */
    private fun applyConnectionLimits(config: String): String {
        val withoutOverrides = config.lineSequence()
            .filterNot { line -> line.trimStart().matches(Regex("(?i)^(connect-retry|connect-retry-max|connect-timeout)(\\s+.*)?$")) }
            .joinToString("\n")
        return withoutOverrides.trimEnd() + "\nconnect-timeout 10\nconnect-retry 5 15\nconnect-retry-max 3\n"
    }

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
                    IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
                } catch (error: Exception) {
                    Log.e(TAG, "Could not send OpenVPN disconnect", error)
                    state = "DISCONNECTED"; failed = true
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
        when (level) {
            IcsStatus.LEVEL_CONNECTED -> { timeoutHandler.removeCallbacks(connectionTimeout); this.state = "CONNECTED"; failed = false; lastError = "" }
            IcsStatus.LEVEL_NOTCONNECTED -> { timeoutHandler.removeCallbacks(connectionTimeout); this.state = "DISCONNECTED" }
            IcsStatus.LEVEL_AUTH_FAILED -> { timeoutHandler.removeCallbacks(connectionTimeout); this.state = "DISCONNECTED"; failed = true; lastError = "auth" }
            IcsStatus.LEVEL_START,
            IcsStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            IcsStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            IcsStatus.LEVEL_WAITING_FOR_USER_INPUT,
            IcsStatus.LEVEL_NONETWORK -> this.state = "CONNECTING"
            else -> if (this.state != "CONNECTED" && this.state != "DISCONNECTING") this.state = "CONNECTING"
        }
    }
    override fun setConnectedVPN(uuid: String?) = Unit
    private const val CONNECTION_DEADLINE_MS = 45_000L

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        downloadBytes = inBytes.coerceAtLeast(0L)
        uploadBytes = outBytes.coerceAtLeast(0L)
    }
}
