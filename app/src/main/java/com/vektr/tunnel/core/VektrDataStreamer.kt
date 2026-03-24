package com.vektr.tunnel.core

import android.util.Log
import org.webrtc.DataChannel
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Vektr DataStreamer: The bit-perfect conduit.
 * Transports raw binary chunks (WAV, AIFF, FLAC, MP3) through the P2P Tunnel.
 *
 * Why this is the "No-Link" winner:
 *  - Infinite Size: Streaming means a 2GB session export costs no more RAM than a 5MB MP3.
 *  - No Transcoding: Reads FileInputStream directly — 24-bit WAV in, 24-bit WAV out.
 *  - Hardware-to-Hardware: Storage chip → Wi-Fi/5G antenna → their storage chip. Nothing else.
 */
class VektrDataStreamer(private val dataChannel: DataChannel) {

    private val CHUNK_SIZE = 16384 // 16KB — optimal MTU for WebRTC DataChannel

    /** Sentinel that tells the receiver to finalise and run the integrity check. */
    val EOF_SIGNAL = "VEKTR_EOF".toByteArray(Charsets.UTF_8)

    /**
     * Streams [file] as raw binary chunks into the DataChannel.
     * Sends a VEKTR_EOF sentinel upon completion to trigger the receiver's integrity check.
     *
     * @param onProgress Called periodically with bytes sent so far.
     * @param onError    Called if the DataChannel is not open or a send fails.
     */
    fun tunnelFile(
        file: File,
        onProgress: ((bytesSent: Long) -> Unit)? = null,
        onError: ((reason: String) -> Unit)? = null
    ) {
        if (dataChannel.state() != DataChannel.State.OPEN) {
            onError?.invoke("DataChannel is not open (state=${dataChannel.state()})")
            return
        }

        var bytesSent = 0L
        val inputStream = FileInputStream(file)
        val buffer = ByteArray(CHUNK_SIZE)

        inputStream.use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                // Wrap the raw bits into a ByteBuffer for the Tunnel
                val payload = ByteBuffer.wrap(buffer, 0, bytesRead)
                val bufferData = DataChannel.Buffer(payload, true)

                // Direct injection into the P2P pipe
                if (!dataChannel.send(bufferData)) {
                    onError?.invoke("DataChannel send failed at byte $bytesSent")
                    return
                }

                bytesSent += bytesRead
                onProgress?.invoke(bytesSent)
            }
        }

        // Signal 'EndOfTunnel' to trigger the Integrity Check on the receiver
        val eofSignal = ByteBuffer.wrap(EOF_SIGNAL)
        dataChannel.send(DataChannel.Buffer(eofSignal, true))
        Log.d("VektrDataStreamer", "Tunnelled ${file.name} — $bytesSent bytes sent + VEKTR_EOF")
    }
}
