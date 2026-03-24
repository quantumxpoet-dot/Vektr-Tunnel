package com.vektr.tunnel.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Vektr Vault: Permanent, Linkless Local Storage.
 * Handles bit-perfect audio persistence for WAV, AIFF, FLAC, and MP3.
 */
class VektrVault(private val context: Context) {

    private val vaultDir: File by lazy {
        File(context.filesDir, "vektr_vault").apply { if (!exists()) mkdirs() }
    }

    // Creates the local file where the 'Tunnel' will inject data
    fun createIncomingFile(fileName: String): File {
        return File(vaultDir, fileName).apply { if (exists()) delete() }
    }

    // Appends raw binary chunks directly from the WebRTC DataChannel
    fun appendChunk(file: File, bytes: ByteArray) {
        FileOutputStream(file, true).use { it.write(bytes) }
    }

    // Professional status check
    fun getVaultContents(): List<File> = vaultDir.listFiles()?.toList() ?: emptyList()
}
