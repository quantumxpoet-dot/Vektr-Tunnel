package com.vektr.tunnel.webrtc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vektr.tunnel.core.VektrVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * TunnelManager — the central orchestrator for a Vektr Tunnel session.
 *
 * Responsibilities:
 *  - Manages the Node identity (unique per device installation)
 *  - Connects to the signaling bridge for WebRTC handshaking
 *  - Opens a WebRTC DataChannel between two authorized Nodes
 *  - Streams audio files as raw binary chunks (no transcoding)
 *  - Persists received bytes to VektrVault (context.filesDir/vektr_vault/)
 *  - Verifies SHA-256 integrity after each transfer
 *
 * No URL links. No cloud. Hardware-to-hardware only.
 */
class TunnelManager(
    private val context: Context,
    private val signalingUrl: String,
    private val listener: TunnelListener
) {
    interface TunnelListener {
        fun onNodeRegistered(nodeId: String)
        fun onPeerNodeDiscovered(remoteNodeId: String)
        fun onTunnelOpen()
        fun onTunnelClosed()
        fun onSendProgress(fileName: String, bytesSent: Long, totalBytes: Long, bitrateKbps: Double)
        fun onSendComplete(fileName: String)
        fun onReceiveStart(fileName: String, totalBytes: Long)
        fun onReceiveProgress(fileName: String, bytesReceived: Long, totalBytes: Long, bitrateKbps: Double)
        fun onReceiveComplete(fileName: String, file: File, integrityOk: Boolean)
        fun onError(message: String)
        fun onSignalingStatus(connected: Boolean)
    }

    companion object {
        private const val TAG = "TunnelManager"
        private const val CHUNK_SIZE = 16 * 1024              // 16 KB per DataChannel frame
        private const val MAX_BUFFERED_AMOUNT = 1024 * 1024L  // 1 MB backpressure ceiling
        private const val PREF_KEY_NODE_ID = "vektr_node_id"

        private const val FRAME_META = "meta"
        private const val FRAME_END  = "end"

        /** Supported audio extensions — all handled as raw binary, zero transcoding. */
        val SUPPORTED_EXTENSIONS = setOf("wav", "aiff", "aif", "flac", "mp3")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson  = Gson()
    private val vault = VektrVault(context)

    val localNodeId: String = resolveNodeId()

    private var remoteNodeId: String? = null
    private var signalingClient: SignalingClient? = null
    private var peerManager: PeerConnectionManager? = null

    // Receive state machine
    private var rxFile: File? = null
    private var rxExpectedSize: Long = 0L
    private var rxBytesReceived: Long = 0L
    private var rxExpectedSha256: String? = null
    private var rxStartTime: Long = 0L

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Connects to the signaling bridge and registers this Node. */
    fun connect() {
        val client = SignalingClient(signalingUrl, localNodeId, signalingListener)
        signalingClient = client
        client.connect()
    }

    /** Disconnects from signaling and closes any active DataChannel. */
    fun disconnect() {
        peerManager?.dispose()
        peerManager = null
        signalingClient?.disconnect()
        signalingClient = null
    }

    /**
     * Initiates a call to a remote Node by sending a WebRTC offer.
     * Must be called after [onPeerNodeDiscovered].
     */
    fun callNode(targetNodeId: String) {
        remoteNodeId = targetNodeId
        val pm = buildPeerConnectionManager()
        pm.createPeerConnection(isInitiator = true)
        pm.createOffer()
    }

    /**
     * Sends an audio file to the connected peer as a raw binary stream.
     * Formats: WAV, AIFF, FLAC, MP3 — all treated identically as byte arrays.
     */
    fun sendAudioFile(file: File) {
        if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
            listener.onError("Unsupported format: .${file.extension}")
            return
        }
        scope.launch { streamFile(file) }
    }

    /** Returns all audio files currently stored in the local vault. */
    fun getVaultContents(): List<File> = vault.getVaultContents()

    // -------------------------------------------------------------------------
    // Signaling listener
    // -------------------------------------------------------------------------

    private val signalingListener = object : SignalingClient.SignalingListener {
        override fun onConnected() {
            Log.d(TAG, "Signaling connected, node: $localNodeId")
            listener.onNodeRegistered(localNodeId)
            listener.onSignalingStatus(true)
        }

        override fun onDisconnected() {
            listener.onSignalingStatus(false)
        }

        override fun onRemoteNodeJoined(remoteNodeId: String) {
            this@TunnelManager.remoteNodeId = remoteNodeId
            listener.onPeerNodeDiscovered(remoteNodeId)
        }

        override fun onOfferReceived(sdp: String, fromNodeId: String) {
            Log.d(TAG, "Offer received from $fromNodeId")
            remoteNodeId = fromNodeId
            val pm = buildPeerConnectionManager()
            pm.createPeerConnection(isInitiator = false)
            pm.setRemoteOffer(sdp)
        }

        override fun onAnswerReceived(sdp: String, fromNodeId: String) {
            Log.d(TAG, "Answer received from $fromNodeId")
            peerManager?.setRemoteAnswer(sdp)
        }

        override fun onIceCandidateReceived(
            candidate: String, sdpMid: String, sdpMLineIndex: Int, fromNodeId: String
        ) {
            peerManager?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
        }

        override fun onError(error: String) {
            listener.onError("Signaling error: $error")
        }
    }

    // -------------------------------------------------------------------------
    // PeerConnection listener
    // -------------------------------------------------------------------------

    private fun buildPeerConnectionManager(): PeerConnectionManager {
        val pm = PeerConnectionManager(context, object : PeerConnectionManager.PeerConnectionListener {
            override fun onDataChannelOpen() {
                Log.d(TAG, "Tunnel open")
                listener.onTunnelOpen()
            }

            override fun onDataChannelClosed() {
                Log.d(TAG, "Tunnel closed")
                listener.onTunnelClosed()
            }

            override fun onDataReceived(data: ByteArray) {
                handleIncomingData(data)
            }

            override fun onOfferCreated(sdp: String) {
                remoteNodeId?.let { target ->
                    signalingClient?.sendOffer(sdp, target)
                }
            }

            override fun onAnswerCreated(sdp: String) {
                remoteNodeId?.let { target ->
                    signalingClient?.sendAnswer(sdp, target)
                }
            }

            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                remoteNodeId?.let { target ->
                    signalingClient?.sendIceCandidate(candidate, sdpMid, sdpMLineIndex, target)
                }
            }

            override fun onConnectionStateChanged(state: String) {
                Log.d(TAG, "ICE state: $state")
            }

            override fun onError(error: String) {
                listener.onError("WebRTC error: $error")
            }
        })
        peerManager = pm
        return pm
    }

    // -------------------------------------------------------------------------
    // Receive: binary stream → VektrVault
    // -------------------------------------------------------------------------

    private fun handleIncomingData(data: ByteArray) {
        // JSON control frames begin with '{' (0x7B)
        if (data.isNotEmpty() && data[0] == 0x7B.toByte()) {
            parseControlFrame(data)
            return
        }

        // Raw binary audio chunk — write directly to vault via VektrVault.appendChunk()
        val file = rxFile ?: return
        vault.appendChunk(file, data)
        rxBytesReceived += data.size

        val elapsedSec = (System.currentTimeMillis() - rxStartTime) / 1000.0
        val bitrateKbps = if (elapsedSec > 0) (rxBytesReceived * 8) / (elapsedSec * 1000.0) else 0.0
        listener.onReceiveProgress(file.name, rxBytesReceived, rxExpectedSize, bitrateKbps)
    }

    private fun parseControlFrame(data: ByteArray) {
        try {
            val text = data.toString(Charsets.UTF_8)
            val obj  = gson.fromJson(text, JsonObject::class.java)
            when (obj.get("type")?.asString) {
                FRAME_META -> {
                    val name   = obj.get("name")?.asString  ?: return
                    val size   = obj.get("size")?.asLong    ?: 0L
                    val sha256 = obj.get("sha256")?.asString

                    // Atomically open a new local file — no partial overwrites
                    rxFile             = vault.createIncomingFile(name)
                    rxExpectedSize     = size
                    rxBytesReceived    = 0L
                    rxExpectedSha256   = sha256
                    rxStartTime        = System.currentTimeMillis()

                    Log.d(TAG, "RX START $name ($size bytes)")
                    listener.onReceiveStart(name, size)
                }
                FRAME_END -> {
                    val file = rxFile ?: return
                    finaliseReceive(file)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control frame error: ${e.message}")
        }
    }

    private fun finaliseReceive(file: File) {
        val integrityOk = rxExpectedSha256?.let { expected ->
            sha256Hex(file) == expected
        } ?: true

        Log.d(TAG, "RX END ${file.name}, integrity=$integrityOk")
        listener.onReceiveComplete(file.name, file, integrityOk)

        rxFile           = null
        rxExpectedSize   = 0L
        rxBytesReceived  = 0L
        rxExpectedSha256 = null
    }

    // -------------------------------------------------------------------------
    // Send: file → DataChannel chunks
    // -------------------------------------------------------------------------

    private suspend fun streamFile(file: File) {
        val totalSize = file.length()
        val sha256    = sha256Hex(file)

        // 1. Metadata control frame
        val meta = gson.toJson(mapOf(
            "type"   to FRAME_META,
            "name"   to file.name,
            "size"   to totalSize,
            "sha256" to sha256
        ))
        if (peerManager?.sendData(meta.toByteArray(Charsets.UTF_8)) != true) {
            listener.onError("Failed to send metadata frame")
            return
        }

        // 2. Raw binary stream — no encoding, no compression
        var bytesSent    = 0L
        val startTime    = System.currentTimeMillis()
        var lastProgress = startTime

        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(CHUNK_SIZE)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                val chunk = if (read == buf.size) buf else buf.copyOf(read)

                // Backpressure: yield until the DataChannel buffer drains
                while ((peerManager?.getDataChannelBufferedAmount() ?: 0L) > MAX_BUFFERED_AMOUNT) {
                    kotlinx.coroutines.delay(5)
                }

                if (peerManager?.sendData(chunk) != true) {
                    listener.onError("Send failed at byte $bytesSent")
                    return
                }
                bytesSent += read

                val now = System.currentTimeMillis()
                if (now - lastProgress >= 200) {
                    val elapsedSec  = (now - startTime) / 1000.0
                    val bitrateKbps = if (elapsedSec > 0) (bytesSent * 8) / (elapsedSec * 1000.0) else 0.0
                    listener.onSendProgress(file.name, bytesSent, totalSize, bitrateKbps)
                    lastProgress = now
                }
            }
        }

        // 3. End-of-transfer frame
        val end = gson.toJson(mapOf("type" to FRAME_END, "name" to file.name))
        peerManager?.sendData(end.toByteArray(Charsets.UTF_8))

        listener.onSendComplete(file.name)
        Log.d(TAG, "TX END ${file.name}")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Stable Node ID: persisted across sessions so peers can re-discover each other. */
    private fun resolveNodeId(): String {
        val prefs = context.getSharedPreferences("vektr_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_NODE_ID, null) ?: run {
            val newId = "NODE-${UUID.randomUUID().toString().uppercase().take(8)}"
            prefs.edit().putString(PREF_KEY_NODE_ID, newId).apply()
            newId
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(CHUNK_SIZE)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
