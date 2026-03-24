package com.vektr.tunnel.core

import android.content.Context
import java.io.File

/**
 * Vektr Tunnel Core Logic
 * Dictates the "Linkless" and "Local-Only" architecture.
 * All received audio is persisted exclusively to internal app storage — no cloud, no URLs.
 */
class TunnelVault(private val context: Context) {

    companion object {
        private const val VAULT_DIR = "vektr_vault"
    }

    /** Returns the vault directory, creating it if necessary. */
    fun getVaultDirectory(): File {
        val directory = File(context.filesDir, VAULT_DIR)
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    /** Ensures the file stays in the account locally. */
    fun getLocalVaultFile(fileName: String): File {
        return File(getVaultDirectory(), fileName)
    }

    /** Bit-perfect stream handler — no compression or transcoding. */
    fun writeStreamToVault(fileName: String, dataChunk: ByteArray) {
        val file = getLocalVaultFile(fileName)
        file.appendBytes(dataChunk)
    }

    /** Returns all audio files currently stored in the vault. */
    fun listVaultFiles(): List<File> {
        return getVaultDirectory().listFiles()?.toList() ?: emptyList()
    }

    /** Deletes a file from the vault. */
    fun deleteVaultFile(fileName: String): Boolean {
        return getLocalVaultFile(fileName).delete()
    }

    /** Returns total bytes stored in the vault. */
    fun vaultSizeBytes(): Long {
        return getVaultDirectory().listFiles()?.sumOf { it.length() } ?: 0L
    }
}

/** Definition of a 'Node' — no links, just hardware-to-hardware. */
data class VektrNode(
    val nodeId: String,
    val isAuthorized: Boolean = false
)
