package com.margelo.nitro.audiorecorderplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.margelo.nitro.sound.R

/**
 * Self-managed Telecom ConnectionService used to keep the microphone alive when
 * the screen is off on aggressive OEM devices (Samsung, Xiaomi, Sony, Huawei,
 * OPPO, Vivo). This is "Stage B2" of the mic-keep-alive strategy and mirrors
 * the patterns from [react-native-callkeep](https://github.com/react-native-webrtc/react-native-callkeep)
 * (`VoiceConnectionService` / `VoiceConnection`). It is gated behind the
 * `AudioSet.enableTelecomSession` flag in the JS spec.
 *
 * Mechanism
 * ---------
 * 1. Register a [PhoneAccount] with [PhoneAccount.CAPABILITY_SELF_MANAGED] (idempotent).
 * 2. [TelecomManager.placeCall] using a synthetic `tel:` URI and our PhoneAccount.
 *    Telecom binds to this service and invokes [onCreateOutgoingConnection].
 * 3. Inside [onCreateOutgoingConnection] we **must** call [startForeground] with
 *    [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] within 5 seconds — otherwise
 *    Android tears down the call session.
 * 4. The returned [RecordingConnection] sets [Connection.PROPERTY_SELF_MANAGED]
 *    and [Connection.setAudioModeIsVoip] which together make the OS treat this
 *    as an active VoIP call. OEM HAL whitelists exempt active voice calls from
 *    mic suspension.
 * 5. We transition the connection `setDialing()` -> `setActive()` (skipping
 *    `setInitialized()` on Samsung — this is a callkeep-discovered quirk).
 *
 * Failure handling
 * ----------------
 * Every entry point catches [Throwable]. If anything fails (emergency call
 * already active, missing `MANAGE_OWN_CALLS`, OEM rejection, etc.) the helper
 * methods return `false` and recording proceeds without Telecom — Stage B1
 * (audio-mode VoIP) and the existing FOREGROUND_SERVICE_TYPE_MICROPHONE
 * recording stay in place.
 *
 * Policy notes
 * ------------
 * Uses `MANAGE_OWN_CALLS` (Normal protection level, NOT a Play Store restricted
 * permission). Uses `FOREGROUND_SERVICE_TYPE_MICROPHONE` (NOT `phoneCall`) for
 * the FGS — same as callkeep does. Does NOT request READ_CALL_LOG /
 * WRITE_CALL_LOG / READ_PHONE_STATE / CALL_PHONE; those are what trigger the
 * Play Store rejections seen in callkeep #408. We never display a fake call UI.
 */
@RequiresApi(Build.VERSION_CODES.O)
class RecordingConnectionService : ConnectionService() {

    companion object {
        private const val PHONE_ACCOUNT_ID = "nitrosound_recording"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"

        // Synthetic tel: URI. callkeep uses tel: schema (NOT sip:) — sip: is
        // rejected as malformed on several OEM Telecom forks.
        private val CALL_ADDRESS: Uri =
            Uri.fromParts(PhoneAccount.SCHEME_TEL, "recording", null)

        @Volatile
        private var registeredHandle: PhoneAccountHandle? = null

        @Volatile
        private var activeConnection: RecordingConnection? = null

        // -----------------------------------------------------------------
        // Public helpers — invoked from Sound.kt
        // -----------------------------------------------------------------

        /**
         * Register a self-managed [PhoneAccount] with Telecom. Idempotent — safe
         * to call before every recording. Returns `true` if a usable PhoneAccount
         * was registered (or was already registered), `false` otherwise.
         */
        @JvmStatic
        fun registerPhoneAccount(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return false
            }
            return try {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    ?: return false

                val handle = buildPhoneAccountHandle(context)
                val appLabel = readAppLabel(context)

                val account = PhoneAccount.Builder(handle, appLabel)
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .build()

                telecom.registerPhoneAccount(account)
                registeredHandle = handle
                Logger.d("[RecordingConnectionService] PhoneAccount registered: $handle, label=$appLabel")
                true
            } catch (se: SecurityException) {
                // App is missing MANAGE_OWN_CALLS at runtime. Should never happen
                // because the permission is install-time/normal, but log loudly.
                Logger.e("[RecordingConnectionService] registerPhoneAccount missing permission: ${se.message}", se)
                false
            } catch (t: Throwable) {
                Logger.e("[RecordingConnectionService] registerPhoneAccount failed: ${t.message}", t)
                false
            }
        }

        /**
         * Place a self-managed VoIP "call" against our registered PhoneAccount.
         * Telecom will bind to this service and invoke [onCreateOutgoingConnection]
         * within a few hundred ms. Returns `true` if `placeCall` returned
         * without throwing — does NOT guarantee the call was accepted by Telecom.
         */
        @JvmStatic
        fun startSession(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return false
            }
            if (activeConnection != null) {
                Logger.d("[RecordingConnectionService] startSession: connection already active, skipping")
                return true
            }
            return try {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    ?: return false

                val handle = registeredHandle ?: run {
                    Logger.w("[RecordingConnectionService] startSession called before registerPhoneAccount")
                    return false
                }

                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }

                Logger.d("[RecordingConnectionService] placeCall(uri=$CALL_ADDRESS, handle=$handle)")
                telecom.placeCall(CALL_ADDRESS, extras)
                true
            } catch (se: SecurityException) {
                Logger.e("[RecordingConnectionService] startSession missing permission: ${se.message}", se)
                false
            } catch (t: Throwable) {
                Logger.e("[RecordingConnectionService] startSession failed: ${t.message}", t)
                false
            }
        }

        /**
         * Disconnect any active call session. Idempotent — safe to call when no
         * session exists.
         */
        @JvmStatic
        fun stopSession() {
            try {
                activeConnection?.let { conn ->
                    Logger.d("[RecordingConnectionService] stopSession: disconnecting active connection")
                    conn.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                    conn.destroy()
                }
            } catch (t: Throwable) {
                Logger.w("[RecordingConnectionService] stopSession failed: ${t.message}", t)
            } finally {
                activeConnection = null
            }
        }

        @JvmStatic
        fun isSessionActive(): Boolean = activeConnection != null

        // -----------------------------------------------------------------
        // Internal helpers
        // -----------------------------------------------------------------

        private fun buildPhoneAccountHandle(context: Context): PhoneAccountHandle {
            return PhoneAccountHandle(
                ComponentName(context.applicationContext, RecordingConnectionService::class.java),
                PHONE_ACCOUNT_ID
            )
        }

        private fun readAppLabel(context: Context): String {
            return try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(context.packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (t: Throwable) {
                "Recording"
            }
        }
    }

    // -----------------------------------------------------------------
    // ConnectionService callbacks
    // -----------------------------------------------------------------

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Logger.d("[RecordingConnectionService] onCreateOutgoingConnection: handle=$connectionManagerPhoneAccount")

        // CRITICAL: must promote to FGS within 5s of placeCall — Android tears
        // down the call session otherwise. Do this FIRST before any other work.
        promoteToForeground()

        val connection = RecordingConnection().apply {
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            connectionCapabilities = Connection.CAPABILITY_MUTE or Connection.CAPABILITY_SUPPORT_HOLD
            // setAudioModeIsVoip(true) is the AudioManager.MODE_IN_COMMUNICATION
            // hint that travels through Telecom — same as setupAudioFocus does
            // in Sound.kt for Stage B1, but reinforced via the call session.
            audioModeIsVoip = true
            setCallerDisplayName(readAppLabel(this@RecordingConnectionService), TelecomManager.PRESENTATION_ALLOWED)
        }

        // callkeep state sequence: setDialing(), then setInitialized() (skipped
        // on Samsung — known native UI bug), then setActive() shortly after.
        connection.setDialing()
        if (!Build.MANUFACTURER.equals("Samsung", ignoreCase = true)) {
            connection.setInitialized()
        } else {
            Logger.d("[RecordingConnectionService] Skipping setInitialized() on Samsung")
        }

        activeConnection = connection

        // Transition to ACTIVE on the next event-loop tick so Telecom sees the
        // DIALING state first. callkeep does the same delay (250ms) for Samsung.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (activeConnection === connection) {
                    connection.setActive()
                    Logger.d("[RecordingConnectionService] Connection -> ACTIVE; mic HAL priority engaged")
                }
            } catch (t: Throwable) {
                Logger.w("[RecordingConnectionService] setActive failed: ${t.message}", t)
            }
        }, 250L)

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Logger.w(
            "[RecordingConnectionService] onCreateOutgoingConnectionFailed " +
                    "(emergency call active, OEM rejection, or permission missing). " +
                    "Recording continues without Telecom session."
        )
        activeConnection = null
        demoteFromForeground()
    }

    // -----------------------------------------------------------------
    // FGS plumbing
    // -----------------------------------------------------------------

    private fun promoteToForeground() {
        try {
            ensureNotificationChannel()
            val notification = buildOngoingCallNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Logger.d("[RecordingConnectionService] Promoted to foreground (FGS_TYPE_MICROPHONE)")
        } catch (t: Throwable) {
            Logger.e("[RecordingConnectionService] promoteToForeground failed: ${t.message}", t)
        }
    }

    private fun demoteFromForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Logger.w("[RecordingConnectionService] demoteFromForeground failed: ${t.message}", t)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nitrosound_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.nitrosound_notification_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildOngoingCallNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, pendingFlags)
        }

        val smallIcon = applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_btn_speak_now

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.nitrosound_notification_title_recording))
            .setContentText(getString(R.string.nitrosound_notification_text_recording))
            .setSmallIcon(smallIcon)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun readAppLabel(context: Context): String =
        Companion.readAppLabel(context)

    // -----------------------------------------------------------------
    // RecordingConnection — minimal self-managed Connection. We never
    // surface a UI to the user; the connection only exists to gain HAL
    // priority for the mic.
    // -----------------------------------------------------------------

    @Suppress("OVERRIDE_DEPRECATION")
    private inner class RecordingConnection : Connection() {

        override fun onDisconnect() {
            Logger.d("[RecordingConnectionService] RecordingConnection.onDisconnect")
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            cleanupAfterDisconnect()
        }

        override fun onAbort() {
            Logger.d("[RecordingConnectionService] RecordingConnection.onAbort")
            setDisconnected(DisconnectCause(DisconnectCause.UNKNOWN))
            destroy()
            cleanupAfterDisconnect()
        }

        // Note: several overrides below (onReject, onShowIncomingCallUi,
        // onCallAudioStateChanged) are marked deprecated by Telecom in favor of
        // newer API-26+ / API-34+ APIs, but the platform still invokes the
        // deprecated overloads on older devices and on remote surfaces
        // (Bluetooth, Android Auto). The class-level @Suppress("OVERRIDE_DEPRECATION")
        // covers them all.
        override fun onReject() {
            Logger.d("[RecordingConnectionService] RecordingConnection.onReject")
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            cleanupAfterDisconnect()
        }

        override fun onShowIncomingCallUi() {
            // We never show an incoming call UI; only outgoing connections.
            Logger.d("[RecordingConnectionService] onShowIncomingCallUi (ignored — no UI for recording session)")
        }

        override fun onCallAudioStateChanged(state: CallAudioState?) {
            // No-op — we don't expose audio routing to the user.
            Logger.d("[RecordingConnectionService] onCallAudioStateChanged: $state")
        }

        private fun cleanupAfterDisconnect() {
            if (activeConnection === this) {
                activeConnection = null
            }
            demoteFromForeground()
        }
    }
}
