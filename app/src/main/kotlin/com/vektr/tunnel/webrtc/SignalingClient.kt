package com.vektr.tunnel.webrtc

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * SignalingClient — WebSocket bridge used exclusively for WebRTC handshaking.
 * No file data passes through this channel; it carries only SDP offers/answers
 * and ICE candidates between peers.
 */
class SignalingClient(
    private val signalingUrl: String,
    private val localNodeId: String,
    private val listener: SignalingListener
) {
    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onRemoteNodeJoined(remoteNodeId: String)
        fun onOfferReceived(sdp: String, fromNodeId: String)
        fun onAnswerReceived(sdp: String, fromNodeId: String)
        fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int, fromNodeId: String)
        fun onError(error: String)
    }

    private val TAG = "SignalingClient"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var remoteNodeId: String? = null

    fun connect() {
        val request = Request.Builder().url(signalingUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Signaling connected")
                sendMessage(mapOf(
                    "type" to "register",
                    "nodeId" to localNodeId
                ))
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling failure: ${t.message}")
                listener.onError(t.message ?: "Connection failure")
                listener.onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Signaling closed: $reason")
                listener.onDisconnected()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            when (val type = json.get("type")?.asString) {
                "node-joined" -> {
                    val nodeId = json.get("nodeId")?.asString ?: return
                    remoteNodeId = nodeId
                    listener.onRemoteNodeJoined(nodeId)
                }
                "offer" -> {
                    val sdp = json.get("sdp")?.asString ?: return
                    val from = json.get("from")?.asString ?: return
                    listener.onOfferReceived(sdp, from)
                }
                "answer" -> {
                    val sdp = json.get("sdp")?.asString ?: return
                    val from = json.get("from")?.asString ?: return
                    listener.onAnswerReceived(sdp, from)
                }
                "ice-candidate" -> {
                    val candidate = json.get("candidate")?.asString ?: return
                    val sdpMid = json.get("sdpMid")?.asString ?: ""
                    val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: 0
                    val from = json.get("from")?.asString ?: return
                    listener.onIceCandidateReceived(candidate, sdpMid, sdpMLineIndex, from)
                }
                else -> Log.w(TAG, "Unknown signaling message type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse signaling message: ${e.message}")
        }
    }

    fun sendOffer(sdp: String, targetNodeId: String) {
        sendMessage(mapOf(
            "type" to "offer",
            "sdp" to sdp,
            "target" to targetNodeId,
            "from" to localNodeId
        ))
    }

    fun sendAnswer(sdp: String, targetNodeId: String) {
        sendMessage(mapOf(
            "type" to "answer",
            "sdp" to sdp,
            "target" to targetNodeId,
            "from" to localNodeId
        ))
    }

    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int, targetNodeId: String) {
        sendMessage(mapOf(
            "type" to "ice-candidate",
            "candidate" to candidate,
            "sdpMid" to sdpMid,
            "sdpMLineIndex" to sdpMLineIndex,
            "target" to targetNodeId,
            "from" to localNodeId
        ))
    }

    private fun sendMessage(message: Map<String, Any>) {
        val json = gson.toJson(message)
        webSocket?.send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnect")
        webSocket = null
    }
}
