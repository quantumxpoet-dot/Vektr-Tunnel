package com.vektr.tunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.vektr.tunnel.BuildConfig
import com.vektr.tunnel.core.VektrVault
import com.vektr.tunnel.ui.TransferState
import com.vektr.tunnel.ui.VektrDashboard
import com.vektr.tunnel.ui.theme.VektrTheme
import com.vektr.tunnel.webrtc.PeerConnectionManager
import com.vektr.tunnel.webrtc.TunnelManager
import java.io.File

/**
 * Vektr Activity: The Central Command.
 * Connects the Dashboard UI to the DataStreamer Engine.
 *
 * Premium standard:
 *  - System Integration: native audio/* picker handles WAV/FLAC/MP3/AIFF without a custom browser.
 *  - Professional State Management: UI progresses IDLE → TUNNELING → BIT-PERFECT.
 *  - Minimal Overload: open app, see your Node ID, tunnel. No cloud login. No profile setup.
 */
class VektrActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VektrActivity"
        // Bridge URL is set via gradle.properties (vektr.bridge.url) — no hardcoded strings.
        // Local dev default: http://10.0.2.2:3000 (Android emulator → host loopback)
        private val BRIDGE_URL get() = BuildConfig.BRIDGE_URL
    }

    // ── Core components ───────────────────────────────────────────────────────
    private lateinit var vault: VektrVault
    private lateinit var tunnelManager: TunnelManager

    // ── UI state (Compose-observable) ─────────────────────────────────────────
    private var transferState by mutableStateOf(TransferState())
    private var targetNodeId  by mutableStateOf("")
    private var isConnected   by mutableStateOf(false)
    private var isTunnelReady by mutableStateOf(false)

    // ── Foreground service binding ────────────────────────────────────────────
    private var tunnelService: TunnelForegroundService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            tunnelService = (binder as TunnelForegroundService.TunnelBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            tunnelService = null
        }
    }

    // ── File Picker: Premium, system-level access to raw audio ────────────────
    // Filters to audio/* so the picker surfaces WAV, AIFF, FLAC, and MP3 natively.
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startTunnel(it) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vault = VektrVault(this)
        tunnelManager = TunnelManager(
            context      = this,
            signalingUrl = BRIDGE_URL,
            listener     = tunnelListener
        )

        // Connect to the signaling bridge immediately so the Node ID is registered
        tunnelManager.connect()

        setContent {
            VektrTheme {
                VektrDashboard(
                    myNodeId             = tunnelManager.localNodeId,
                    transferStatus       = transferState,
                    targetNodeId         = targetNodeId,
                    isConnected          = isConnected,
                    isTunnelReady        = isTunnelReady,
                    onTargetNodeIdChange = { targetNodeId = it.uppercase() },
                    onConnectNode        = { tunnelManager.callNode(targetNodeId.trim()) },
                    onOpenTunnel         = {
                        // Triggered by 'OPEN TUNNEL' button — system picker filtered to audio
                        filePicker.launch("audio/*")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelManager.disconnect()
        runCatching { unbindService(serviceConnection) }
    }

    // ── Tunnel start (called after user picks a file) ─────────────────────────

    private fun startTunnel(uri: Uri) {
        val fileName = resolveFileName(uri) ?: "audio_${System.currentTimeMillis()}"

        // Validate extension before touching storage
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in TunnelManager.SUPPORTED_EXTENSIONS) {
            transferState = transferState.copy(label = "ERROR: UNSUPPORTED FORMAT")
            return
        }

        // Copy content URI → internal cache (ContentResolver → real File for streaming)
        val tempFile = File(cacheDir, fileName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audio URI: ${e.message}")
            transferState = transferState.copy(label = "ERROR: FILE READ FAILED")
            return
        }

        // Start foreground service to prevent Android killing the tunnel mid-transfer
        val serviceIntent = Intent(this, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_START
            putExtra(TunnelForegroundService.EXTRA_FILE_NAME, fileName)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        transferState = transferState.copy(
            label           = "TUNNELING...",
            fileName        = fileName,
            progress        = 0f,
            bitrate         = "0",
            integrityStatus = "VERIFYING"
        )

        val totalBytes = tempFile.length()

        // VektrDataStreamer streams bit-for-bit; TunnelManager orchestrates the DataChannel
        tunnelManager.sendAudioFile(
            file = tempFile,
            onProgress = { bytesSent, bitrateKbps ->
                val pct = if (totalBytes > 0) (bytesSent.toFloat() / totalBytes) else 0f
                runOnUiThread {
                    transferState = transferState.copy(
                        progress = pct,
                        bitrate  = "%.0f".format(bitrateKbps)
                    )
                    tunnelService?.updateProgress(fileName, (pct * 100).toInt(), bitrateKbps)
                }
            },
            onComplete = {
                runOnUiThread {
                    transferState = transferState.copy(
                        label           = "COMPLETE",
                        progress        = 1.0f,
                        integrityStatus = "BIT-PERFECT"
                    )
                    stopTunnelService()
                    tempFile.delete()   // clean up cache copy
                }
            },
            onError = { reason ->
                runOnUiThread {
                    transferState = transferState.copy(label = "ERROR")
                    Log.e(TAG, "Tunnel error: $reason")
                    stopTunnelService()
                }
            }
        )
    }

    // ── TunnelManager listener ────────────────────────────────────────────────

    private val tunnelListener = object : TunnelManager.TunnelListener {
        override fun onNodeRegistered(nodeId: String) {
            Log.d(TAG, "Registered as $nodeId")
        }
        override fun onPeerNodeDiscovered(peerId: String) {
            Log.d(TAG, "Peer discovered: $peerId")
        }
        override fun onTunnelOpen() {
            runOnUiThread {
                isTunnelReady = true
                transferState = transferState.copy(label = "TUNNEL READY")
            }
        }
        override fun onTunnelClosed() {
            runOnUiThread {
                isTunnelReady = false
                transferState = TransferState()
            }
        }
        override fun onSendProgress(fileName: String, bytesSent: Long, totalBytes: Long, bitrateKbps: Double) {
            // Handled per-call in sendAudioFile onProgress lambda
        }
        override fun onSendComplete(fileName: String) {
            // Handled per-call in sendAudioFile onComplete lambda
        }
        override fun onReceiveStart(fileName: String, totalBytes: Long) {
            runOnUiThread {
                transferState = transferState.copy(
                    label           = "RECEIVING...",
                    fileName        = fileName,
                    progress        = 0f,
                    bitrate         = "0",
                    integrityStatus = "VERIFYING"
                )
            }
        }
        override fun onReceiveProgress(fileName: String, bytesReceived: Long, totalBytes: Long, bitrateKbps: Double) {
            val pct = if (totalBytes > 0) (bytesReceived.toFloat() / totalBytes) else 0f
            runOnUiThread {
                transferState = transferState.copy(
                    progress = pct,
                    bitrate  = "%.0f".format(bitrateKbps)
                )
            }
        }
        override fun onReceiveComplete(fileName: String, file: File, integrityOk: Boolean) {
            runOnUiThread {
                transferState = transferState.copy(
                    label           = "RECEIVED",
                    progress        = 1.0f,
                    integrityStatus = if (integrityOk) "BIT-PERFECT" else "FAILED"
                )
            }
        }
        override fun onSignalingStatus(connected: Boolean) {
            runOnUiThread { isConnected = connected }
        }
        override fun onError(message: String) {
            Log.e(TAG, message)
            runOnUiThread {
                transferState = transferState.copy(label = "ERROR")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Resolves the display filename from a content:// URI using ContentResolver. */
    private fun resolveFileName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    private fun stopTunnelService() {
        runCatching { unbindService(serviceConnection) }
        stopService(Intent(this, TunnelForegroundService::class.java).apply {
            action = TunnelForegroundService.ACTION_STOP
        })
    }
}
