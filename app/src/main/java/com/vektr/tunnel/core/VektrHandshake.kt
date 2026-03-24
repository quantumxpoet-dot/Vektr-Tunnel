package com.vektr.tunnel.core

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Vektr Handshake: The "Operator" logic.
 * Connects two Nodes via ID to establish the P2P Tunnel.
 *
 * Premium architecture:
 *  - No Links: The user types (or scans) a Node ID — that's the only address.
 *  - Zero Middleman: This class handles ONLY the WebRTC handshake.
 *    Audio bytes never pass through here; they flow straight through the DataChannel.
 *  - Vektr Suite Ready: Reusable in Vektr Studio to link two musicians' workstations
 *    for a live session with zero configuration.
 *
 * Signal protocol (mirrors server/index.js exactly):
 *   EMIT  → "register_node"   payload: nodeId (String)
 *   EMIT  → "tunnel_signal"   payload: { targetNodeId, signalData: { type, ...fields } }
 *   RECV  ← "incoming_signal" payload: { senderNodeId, signalData: { type, ...fields } }
 *   RECV  ← "node_joined"     payload: nodeId (String)
 *
 * signalData.type values: "offer" | "answer" | "ice"
 */
class VektrHandshake(
    val myNodeId: String,
    val bridgeUrl: String,
    private val listener: HandshakeListener
) {
    interface HandshakeListener {
        /** Another Node just joined the Bridge and is discoverable. */
        fun onPeerDiscovered(peerId: String)

        /** Receiver: an offer arrived — pass [sdp] to WebRTC setRemoteDescription. */
        fun onIncomingOffer(sdp: String, fromNodeId: String)

        /** Initiator: the peer accepted — pass [sdp] to WebRTC setRemoteDescription. */
        fun onIncomingAnswer(sdp: String, fromNodeId: String)

        /** ICE candidate trickled in from the peer — add to PeerConnection. */
        fun onIncomingIceCandidate(
            candidate: String, sdpMid: String, sdpMLineIndex: Int, fromNodeId: String
        )

        fun onConnected()
        fun onDisconnected()
        fun onError(reason: String)
    }

    private val TAG = "VektrHandshake"
    private val socket: Socket = IO.socket(bridgeUrl)

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect() {
        socket.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Bridge connected — registering $myNodeId")
            socket.emit("register_node", myNodeId)
            listener.onConnected()
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            Log.d(TAG, "Bridge disconnected")
            listener.onDisconnected()
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            listener.onError(args.firstOrNull()?.toString() ?: "Connection error")
        }

        // A new peer just registered on the Bridge
        socket.on("node_joined") { args ->
            val peerId = args.getOrNull(0) as? String ?: return@on
            if (peerId != myNodeId) {
                Log.d(TAG, "Peer discovered: $peerId")
                listener.onPeerDiscovered(peerId)
            }
        }

        // Unified incoming signal — dispatch by signalData.type
        // Mirrors the server's: io.to(target).emit('incoming_signal', { senderNodeId, signalData })
        socket.on("incoming_signal") { args ->
            val envelope     = args.getOrNull(0) as? JSONObject ?: return@on
            val senderNodeId = envelope.optString("senderNodeId")
            val signalData   = envelope.optJSONObject("signalData") ?: return@on

            when (signalData.optString("type")) {
                "offer"  -> {
                    Log.d(TAG, "Incoming offer from $senderNodeId")
                    listener.onIncomingOffer(signalData.optString("sdp"), senderNodeId)
                }
                "answer" -> {
                    Log.d(TAG, "Incoming answer from $senderNodeId")
                    listener.onIncomingAnswer(signalData.optString("sdp"), senderNodeId)
                }
                "ice"    -> {
                    listener.onIncomingIceCandidate(
                        signalData.optString("candidate"),
                        signalData.optString("sdpMid"),
                        signalData.optInt("sdpMLineIndex", 0),
                        senderNodeId
                    )
                }
                else -> Log.w(TAG, "Unknown signal type: ${signalData.optString("type")}")
            }
        }

        socket.connect()
    }

    fun disconnect() {
        socket.disconnect()
        socket.off()
    }

    // ── Emitters — all three use the same tunnel_signal event ─────────────────
    // Mirrors the server's single socket.on('tunnel_signal', { targetNodeId, signalData })

    /**
     * Sends a WebRTC offer to [targetNodeId].
     * This is how the initiating Node opens the Tunnel.
     */
    fun sendTunnelOffer(targetNodeId: String, sdp: String) {
        val signalData = JSONObject().apply {
            put("type", "offer")
            put("sdp",  sdp)
        }
        emitTunnelSignal(targetNodeId, signalData)
        Log.d(TAG, "Offer → $targetNodeId")
    }

    /**
     * Sends a WebRTC answer SDP back to the initiating Node.
     */
    fun sendTunnelAnswer(targetNodeId: String, sdp: String) {
        val signalData = JSONObject().apply {
            put("type", "answer")
            put("sdp",  sdp)
        }
        emitTunnelSignal(targetNodeId, signalData)
        Log.d(TAG, "Answer → $targetNodeId")
    }

    /**
     * Trickles an ICE candidate to [targetNodeId].
     * Once the DataChannel opens, the bridge is out of the data path entirely.
     */
    fun sendIceCandidate(
        targetNodeId: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int
    ) {
        val signalData = JSONObject().apply {
            put("type",          "ice")
            put("candidate",     candidate)
            put("sdpMid",        sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        }
        emitTunnelSignal(targetNodeId, signalData)
    }

    /** Single emit path — matches server's tunnel_signal handler exactly. */
    private fun emitTunnelSignal(targetNodeId: String, signalData: JSONObject) {
        val payload = JSONObject().apply {
            put("targetNodeId", targetNodeId)
            put("signalData",   signalData)
        }
        socket.emit("tunnel_signal", payload)
    }
}
