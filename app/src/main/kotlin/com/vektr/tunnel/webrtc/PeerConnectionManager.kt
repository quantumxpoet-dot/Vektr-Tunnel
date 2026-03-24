package com.vektr.tunnel.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/**
 * PeerConnectionManager — manages the WebRTC PeerConnection and DataChannel.
 * Audio data is transferred as raw binary chunks over the DataChannel.
 * No transcoding; bit-perfect transfer is guaranteed by the binary stream approach.
 */
class PeerConnectionManager(
    private val context: Context,
    private val listener: PeerConnectionListener
) {
    interface PeerConnectionListener {
        fun onDataChannelOpen()
        fun onDataChannelClosed()
        fun onDataReceived(data: ByteArray)
        fun onOfferCreated(sdp: String)
        fun onAnswerCreated(sdp: String)
        fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onConnectionStateChanged(state: String)
        fun onError(error: String)
    }

    private val TAG = "PeerConnectionManager"

    // WebRTC STUN servers for NAT traversal (no TURN — no relay server stores data)
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // DataChannel configuration for binary file transfer
    private val dataChannelConfig = DataChannel.Init().apply {
        ordered = true          // guaranteed ordered delivery
        maxRetransmits = -1     // unlimited retransmits for reliability
        protocol = "binary"
    }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(isInitiator: Boolean) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE connection state: $state")
                listener.onConnectionStateChanged(state.name)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidate(
                    candidate.sdp,
                    candidate.sdpMid ?: "",
                    candidate.sdpMLineIndex
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(channel: DataChannel) {
                Log.d(TAG, "Remote DataChannel received: ${channel.label()}")
                setupDataChannel(channel)
            }

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        })

        if (isInitiator) {
            dataChannel = peerConnection?.createDataChannel("vektr-tunnel", dataChannelConfig)
            dataChannel?.let { setupDataChannel(it) }
        }
    }

    private fun setupDataChannel(channel: DataChannel) {
        dataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> {
                        Log.d(TAG, "DataChannel OPEN")
                        listener.onDataChannelOpen()
                    }
                    DataChannel.State.CLOSED -> {
                        Log.d(TAG, "DataChannel CLOSED")
                        listener.onDataChannelClosed()
                    }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                listener.onDataReceived(data)
            }
        })
    }

    fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) {
                        listener.onError("Set local SDP failed: $error")
                    }
                }, sdp)
                listener.onOfferCreated(sdp.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                listener.onError("Create offer failed: $error")
            }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {}
                    override fun onSetFailure(error: String) {
                        listener.onError("Set local answer SDP failed: $error")
                    }
                }, sdp)
                listener.onAnswerCreated(sdp.description)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                listener.onError("Create answer failed: $error")
            }
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun setRemoteOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote offer set successfully")
                createAnswer()
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {
                listener.onError("Set remote offer failed: $error")
            }
        }, sessionDescription)
    }

    fun setRemoteAnswer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set successfully")
            }
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {
                listener.onError("Set remote answer failed: $error")
            }
        }, sessionDescription)
    }

    fun addRemoteIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * Sends a raw binary chunk over the DataChannel.
     * This is the core bit-perfect transfer mechanism.
     */
    fun sendData(data: ByteArray): Boolean {
        val channel = dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
        return channel.send(buffer)
    }

    /** Exposes the raw DataChannel so VektrDataStreamer can inject binary chunks directly. */
    fun getDataChannel(): DataChannel? = dataChannel

    fun getDataChannelBufferedAmount(): Long {
        return dataChannel?.bufferedAmount() ?: 0L
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        dataChannel = null
        peerConnection = null
    }

    fun dispose() {
        close()
        factory.dispose()
        eglBase.release()
    }
}
