package com.vektr.tunnel.webrtc

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vektr.tunnel.core.VektrDataStreamer
import com.vektr.tunnel.core.VektrHandshake
import com.vektr.tunnel.core.VektrVault
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * TunnelManager — the central orchestrator for a Vektr Tunnel session.
 *
 * Architecture (no cloud, no links):
 *  VektrHandshake (Socket.IO, handshake only)
 *      └─► PeerConnectionManager (WebRTC offer/answer/ICE)
 *              └─► DataChannel open
 *                      ├─► SEND: VektrDataStreamer.tunnelFile() → raw binary chunks → VEKTR_EOF
 *                      └─► RECV: binary chunks → VektrVault.appendChunk() → SHA-256 verify
 */
class TunnelManager(
    private val context: Context,
    private val signalingUrl: String,
    private val listener: TunnelListener
) {
    interface TunnelListener {
        fun onNodeRegistered(nodeId: String)
        fun onPeerNodeDiscovered(peerId: String)
        fun onTunnelOpen()
        fun onTunnelClosed()
        fun onSendProgress(fileName: String, bytesSent: Long, totalBytes: Long, bitrateKbps: Double)
        fun onSendComplete(fileName: String)
        fun onReceiveStart(fileName: String, totalBytes: Long)
        fun onReceiveProgress(fileName: String, bytesReceived: Long, totalBytes: Long, bitrateKbps: Double)
        fun onReceiveComplete(fileName: String, file: File, integrityOk: Boolean)
        fun onSignalingStatus(connected: Boolean)
        fun onError(message: String)
    }

    companion object {
        private const val TAG          = "TunnelManager"
        private const val PREF_NODE_ID = "vektr_node_id"

        // Control frame markers (JSON prefix 0x7B vs binary)
        private const val FRAME_META = "meta"

        /** VEKTR_EOF sentinel — signals receiver to finalise and verify integrity. */
        private val EOF_BYTES = "VEKTR_EOF".toByteArray(Charsets.UTF_8)

        val SUPPORTED_EXTENSIONS = setOf("wav", "aiff", "aif", "flac", "mp3")
    }

    private val gson  = Gson()
    private val vault = VektrVault(context)

    val localNodeId: String = resolveNodeId()

    private var remoteNodeId: String? = null
    private var handshake: VektrHandshake? = null
    private var peerManager: PeerConnectionManager? = null

    // ── Receive state machine ────────────────────────────────────────────────
    private var rxFile: File? = null
    private var rxExpectedSize: Long    = 0L
    private var rxBytesReceived: Long   = 0L
    private var rxExpectedSha256: String? = null
    private var rxStartTime: Long       = 0L

    // ── Public API ───────────────────────────────────────────────────────────

    fun connect() {
        val h = VektrHandshake(localNodeId, signalingUrl, object : VektrHandshake.HandshakeListener {
            override fun onConnected() {
                listener.onNodeRegistered(localNodeId)
                listener.onSignalingStatus(true)
            }
            override fun onDisconnected() { listener.onSignalingStatus(false) }
            override fun onPeerDiscovered(peerId: String) {
                remoteNodeId = peerId
                listener.onPeerNodeDiscovered(peerId)
            }
            override fun onIncomingOffer(sdp: String, fromNodeId: String) {
                remoteNodeId = fromNodeId
                val pm = buildPeerManager()
                pm.createPeerConnection(isInitiator = false)
                pm.setRemoteOffer(sdp)
            }
            override fun onIncomingAnswer(sdp: String, fromNodeId: String) {
                peerManager?.setRemoteAnswer(sdp)
            }
            override fun onIncomingIceCandidate(
                candidate: String, sdpMid: String, sdpMLineIndex: Int, fromNodeId: String
            ) {
                peerManager?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
            }
            override fun onError(reason: String) { listener.onError("Handshake: $reason") }
        })
        handshake = h
        h.connect()
    }

    fun callNode(targetNodeId: String) {
        remoteNodeId = targetNodeId
        val pm = buildPeerManager()
        pm.createPeerConnection(isInitiator = true)
        pm.createOffer()
    }

    /**
     * Sends an audio file to the connected peer.
     * Uses VektrDataStreamer for the raw binary stream — no encoding, no cloud.
     */
    fun sendAudioFile(
        file: File,
        onProgress: (bytesSent: Long, bitrateKbps: Double) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val dc = peerManager?.getDataChannel()
        if (dc == null) {
            onError("Tunnel not open — establish connection first")
            return
        }
        if (file.extension.lowercase() !in SUPPORTED_EXTENSIONS) {
            onError("Unsupported format: .${file.extension}")
            return
        }

        val totalSize = file.length()
        val sha256    = sha256Hex(file)

        // 1. Metadata frame so receiver knows filename, size, and expected checksum
        val meta = gson.toJson(mapOf(
            "type"   to FRAME_META,
            "name"   to file.name,
            "size"   to totalSize,
            "sha256" to sha256
        ))
        dc.send(org.webrtc.DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(meta.toByteArray(Charsets.UTF_8)), true
        ))

        // 2. VektrDataStreamer handles the raw binary stream + VEKTR_EOF sentinel
        val startTime = System.currentTimeMillis()
        val streamer  = VektrDataStreamer(dc)
        streamer.tunnelFile(
            file = file,
            onProgress = { bytesSent ->
                val elapsed     = (System.currentTimeMillis() - startTime) / 1000.0
                val bitrateKbps = if (elapsed > 0) (bytesSent * 8) / (elapsed * 1000.0) else 0.0
                listener.onSendProgress(file.name, bytesSent, totalSize, bitrateKbps)
                onProgress(bytesSent, bitrateKbps)
            },
            onError = { reason ->
                listener.onError(reason)
                onError(reason)
            }
        )

        listener.onSendComplete(file.name)
        onComplete()
    }

    fun getVaultContents(): List<File> = vault.getVaultContents()

    fun disconnect() {
        peerManager?.dispose()
        peerManager = null
        handshake?.disconnect()
        handshake = null
    }

    // ── PeerConnectionManager factory ────────────────────────────────────────

    private fun buildPeerManager(): PeerConnectionManager {
        peerManager?.dispose()
        val pm = PeerConnectionManager(context, object : PeerConnectionManager.PeerConnectionListener {
            override fun onDataChannelOpen()  { listener.onTunnelOpen() }
            override fun onDataChannelClosed() { listener.onTunnelClosed() }
            override fun onDataReceived(data: ByteArray) { handleIncomingData(data) }

            override fun onOfferCreated(sdp: String) {
                remoteNodeId?.let { handshake?.sendTunnelOffer(it, sdp) }
            }
            override fun onAnswerCreated(sdp: String) {
                remoteNodeId?.let { handshake?.sendTunnelAnswer(it, sdp) }
            }
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                remoteNodeId?.let { handshake?.sendIceCandidate(it, candidate, sdpMid, sdpMLineIndex) }
            }
            override fun onConnectionStateChanged(state: String) {
                Log.d(TAG, "ICE state: $state")
            }
            override fun onError(error: String) { listener.onError("WebRTC: $error") }
        })
        peerManager = pm
        return pm
    }

    // ── Receive: DataChannel → VektrVault ────────────────────────────────────

    private fun handleIncomingData(data: ByteArray) {
        when {
            // JSON control frame (starts with '{')
            data.isNotEmpty() && data[0] == 0x7B.toByte() -> parseMetaFrame(data)

            // VEKTR_EOF sentinel — finalise and verify
            data.contentEquals(EOF_BYTES) -> rxFile?.let { finaliseReceive(it) }

            // Raw audio binary chunk — write directly to vault, no intermediate buffer
            else -> {
                val file = rxFile ?: return
                vault.appendChunk(file, data)
                rxBytesReceived += data.size

                val elapsed     = (System.currentTimeMillis() - rxStartTime) / 1000.0
                val bitrateKbps = if (elapsed > 0) (rxBytesReceived * 8) / (elapsed * 1000.0) else 0.0
                listener.onReceiveProgress(file.name, rxBytesReceived, rxExpectedSize, bitrateKbps)
            }
        }
    }

    private fun parseMetaFrame(data: ByteArray) {
        try {
            val obj    = gson.fromJson(data.toString(Charsets.UTF_8), JsonObject::class.java)
            val name   = obj.get("name")?.asString   ?: return
            val size   = obj.get("size")?.asLong     ?: 0L
            val sha256 = obj.get("sha256")?.asString

            // Atomically create the vault file — no partial overwrites
            rxFile           = vault.createIncomingFile(name)
            rxExpectedSize   = size
            rxBytesReceived  = 0L
            rxExpectedSha256 = sha256
            rxStartTime      = System.currentTimeMillis()

            Log.d(TAG, "RX START $name ($size bytes)")
            listener.onReceiveStart(name, size)
        } catch (e: Exception) {
            Log.e(TAG, "Meta frame parse error: ${e.message}")
        }
    }

    private fun finaliseReceive(file: File) {
        val integrityOk = rxExpectedSha256?.let { sha256Hex(file) == it } ?: true
        Log.d(TAG, "RX END ${file.name}, integrity=$integrityOk")
        listener.onReceiveComplete(file.name, file, integrityOk)
        rxFile = null; rxExpectedSize = 0L; rxBytesReceived = 0L; rxExpectedSha256 = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveNodeId(): String {
        val prefs = context.getSharedPreferences("vektr_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_NODE_ID, null) ?: run {
            val id = "VK-${UUID.randomUUID().toString().uppercase().take(6)}"
            prefs.edit().putString(PREF_NODE_ID, id).apply()
            id
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { s ->
            val buf = ByteArray(16 * 1024)
            var n: Int
            while (s.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
