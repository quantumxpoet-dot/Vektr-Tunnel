package com.vektr.tunnel.transfer

import android.util.Log
import com.google.gson.Gson
import com.vektr.tunnel.core.TunnelVault
import com.vektr.tunnel.webrtc.PeerConnectionManager
import java.io.File
import java.security.MessageDigest

/**
 * FileTransferManager — orchestrates chunked binary streaming over the WebRTC DataChannel.
 *
 * Protocol:
 *   1. Sender sends a JSON metadata frame: { "type": "meta", "name": ..., "size": ..., "sha256": ... }
 *   2. Sender sends raw binary chunks until the full file is delivered.
 *   3. Sender sends a JSON end frame: { "type": "end", "name": ... }
 *   4. Receiver verifies SHA-256 checksum for bit-perfect integrity.
 *
 * No transcoding, no compression — raw binary streams only.
 */
class FileTransferManager(
    private val vault: TunnelVault,
    private val peerConnection: PeerConnectionManager
) {
    interface TransferListener {
        fun onSendProgress(fileName: String, bytesSent: Long, totalBytes: Long, bitrateKbps: Double)
        fun onSendComplete(fileName: String)
        fun onReceiveStart(fileName: String, totalBytes: Long)
        fun onReceiveProgress(fileName: String, bytesReceived: Long, totalBytes: Long, bitrateKbps: Double)
        fun onReceiveComplete(fileName: String, integrityValid: Boolean)
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "FileTransferManager"
        private const val CHUNK_SIZE = 16 * 1024           // 16 KB per chunk
        private const val MAX_BUFFERED_AMOUNT = 1024 * 1024L // 1 MB backpressure threshold
        private const val AUDIO_FORMATS = "wav,aiff,aif,flac,mp3"

        /** Supported audio MIME types — handled as raw binary, no transcoding. */
        val SUPPORTED_EXTENSIONS = AUDIO_FORMATS.split(",").toSet()

        private const val FRAME_TYPE_META = "meta"
        private const val FRAME_TYPE_END = "end"
    }

    private val gson = Gson()

    // Receive state
    private var receivingFileName: String? = null
    private var receivingExpectedSize: Long = 0L
    private var receivingBytesAccumulated: Long = 0L
    private var receivingExpectedSha256: String? = null
    private var receiveStartTime: Long = 0L
    private var listener: TransferListener? = null

    fun setListener(listener: TransferListener) {
        this.listener = listener
    }

    /**
     * Validates the file extension is a supported audio format.
     * All formats are handled as raw binary — no transcoding.
     */
    fun isSupportedAudioFile(file: File): Boolean {
        return file.extension.lowercase() in SUPPORTED_EXTENSIONS
    }

    /**
     * Sends a file as raw binary chunks over the DataChannel.
     * Uses SHA-256 to allow the receiver to verify bit-perfect integrity.
     */
    suspend fun sendFile(file: File) {
        if (!isSupportedAudioFile(file)) {
            listener?.onError("Unsupported format: ${file.extension}. Supported: $AUDIO_FORMATS")
            return
        }

        val totalSize = file.length()
        val sha256 = computeSha256(file)

        Log.d(TAG, "Sending ${file.name} ($totalSize bytes, SHA-256: $sha256)")

        // Send metadata frame
        val meta = gson.toJson(mapOf(
            "type" to FRAME_TYPE_META,
            "name" to file.name,
            "size" to totalSize,
            "sha256" to sha256
        ))
        if (!peerConnection.sendData(meta.toByteArray(Charsets.UTF_8))) {
            listener?.onError("Failed to send metadata frame")
            return
        }

        // Stream raw binary chunks
        var bytesSent = 0L
        val startTime = System.currentTimeMillis()
        var lastProgressTime = startTime

        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)

                // Backpressure: wait if the DataChannel buffer is full
                while (peerConnection.getDataChannelBufferedAmount() > MAX_BUFFERED_AMOUNT) {
                    kotlinx.coroutines.delay(5)
                }

                if (!peerConnection.sendData(chunk)) {
                    listener?.onError("DataChannel send failed at byte $bytesSent")
                    return
                }

                bytesSent += read
                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 250) {
                    val elapsedSec = (now - startTime) / 1000.0
                    val bitrateKbps = if (elapsedSec > 0) (bytesSent * 8) / (elapsedSec * 1000.0) else 0.0
                    listener?.onSendProgress(file.name, bytesSent, totalSize, bitrateKbps)
                    lastProgressTime = now
                }
            }
        }

        // Send end frame
        val end = gson.toJson(mapOf("type" to FRAME_TYPE_END, "name" to file.name))
        peerConnection.sendData(end.toByteArray(Charsets.UTF_8))

        listener?.onSendComplete(file.name)
        Log.d(TAG, "Send complete: ${file.name}")
    }

    /**
     * Processes incoming DataChannel data from the peer.
     * Distinguishes between JSON control frames and binary audio payload.
     */
    fun onDataReceived(data: ByteArray) {
        // Try to parse as JSON control frame first
        val text = tryDecodeUtf8Json(data)
        if (text != null) {
            handleControlFrame(text)
            return
        }

        // Binary audio chunk
        val fileName = receivingFileName ?: return
        vault.writeStreamToVault(fileName, data)
        receivingBytesAccumulated += data.size

        val now = System.currentTimeMillis()
        val elapsedSec = (now - receiveStartTime) / 1000.0
        val bitrateKbps = if (elapsedSec > 0) (receivingBytesAccumulated * 8) / (elapsedSec * 1000.0) else 0.0
        listener?.onReceiveProgress(fileName, receivingBytesAccumulated, receivingExpectedSize, bitrateKbps)
    }

    private fun handleControlFrame(json: String) {
        try {
            val obj = gson.fromJson(json, Map::class.java)
            when (obj["type"] as? String) {
                FRAME_TYPE_META -> {
                    val name = obj["name"] as? String ?: return
                    val size = (obj["size"] as? Double)?.toLong() ?: 0L
                    val sha256 = obj["sha256"] as? String

                    // Delete any prior partial file with same name
                    vault.deleteVaultFile(name)

                    receivingFileName = name
                    receivingExpectedSize = size
                    receivingBytesAccumulated = 0L
                    receivingExpectedSha256 = sha256
                    receiveStartTime = System.currentTimeMillis()

                    Log.d(TAG, "Receiving $name ($size bytes)")
                    listener?.onReceiveStart(name, size)
                }
                FRAME_TYPE_END -> {
                    val name = obj["name"] as? String ?: return
                    finalizeReceive(name)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control frame parse error: ${e.message}")
        }
    }

    private fun finalizeReceive(fileName: String) {
        val file = vault.getLocalVaultFile(fileName)
        val integrityValid = receivingExpectedSha256?.let { expected ->
            computeSha256(file) == expected
        } ?: true

        Log.d(TAG, "Receive complete: $fileName, integrity valid: $integrityValid")
        listener?.onReceiveComplete(fileName, integrityValid)

        // Reset state
        receivingFileName = null
        receivingExpectedSize = 0L
        receivingBytesAccumulated = 0L
        receivingExpectedSha256 = null
    }

    /**
     * Attempts to decode bytes as UTF-8 JSON. Returns null if not valid JSON.
     * Used to distinguish control frames from raw binary audio chunks.
     */
    private fun tryDecodeUtf8Json(data: ByteArray): String? {
        // Quick heuristic: JSON frames start with '{' (0x7B)
        if (data.isEmpty() || data[0] != 0x7B.toByte()) return null
        return try {
            val text = data.toString(Charsets.UTF_8)
            gson.fromJson(text, Map::class.java)
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(CHUNK_SIZE)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
