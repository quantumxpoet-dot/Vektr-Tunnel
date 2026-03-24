package com.vektr.tunnel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vektr.tunnel.ui.theme.VektrAmber
import com.vektr.tunnel.ui.theme.VektrAmberLight
import com.vektr.tunnel.ui.theme.VektrGreen
import com.vektr.tunnel.ui.theme.VektrGreenLight
import com.vektr.tunnel.ui.theme.VektrRed
import com.vektr.tunnel.ui.theme.VektrRedLight
import com.vektr.tunnel.ui.theme.VektrTheme

/**
 * Vektr Dashboard: Professional, Minimalist UI.
 * Three sections only — Node ID, Target, Console.
 * Data-driven: every pixel earns its place.
 */
@Composable
fun VektrDashboard(
    myNodeId: String,
    transferStatus: TransferState,
    targetNodeId: String,
    isConnected: Boolean,
    isTunnelReady: Boolean,
    onTargetNodeIdChange: (String) -> Unit,
    onConnectNode: () -> Unit,
    onOpenTunnel: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {

        // ── Header: VEKTR // TUNNEL + Local Node ID ──────────────────────────
        Text(
            text  = "VEKTR // TUNNEL",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text  = "LOCAL NODE: $myNodeId",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )

        // Connection status indicator
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor by animateColorAsState(
                targetValue = if (isConnected) VektrGreen else MaterialTheme.colorScheme.outline,
                animationSpec = tween(600),
                label = "statusDot"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, shape = MaterialTheme.shapes.extraSmall)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text  = if (isConnected) "BRIDGE CONNECTED" else "BRIDGE OFFLINE",
                style = MaterialTheme.typography.labelSmall,
                color = if (isConnected) VektrGreen else MaterialTheme.colorScheme.outline
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // ── Target: Enter the peer's Node ID ────────────────────────────────
        Text(
            text  = "TARGET",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value         = targetNodeId,
            onValueChange = onTargetNodeIdChange,
            label         = { Text("NODE ID", style = MaterialTheme.typography.labelSmall) },
            placeholder   = { Text("VK-XXXXXX", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            shape         = MaterialTheme.shapes.extraSmall,
            textStyle     = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction      = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick   = { focusManager.clearFocus(); onConnectNode() },
            modifier  = Modifier.fillMaxWidth(),
            shape     = MaterialTheme.shapes.extraSmall,
            enabled   = targetNodeId.isNotBlank() && !isTunnelReady,
            border    = ButtonDefaults.outlinedButtonBorder
        ) {
            Text(
                text  = if (isTunnelReady) "TUNNEL OPEN" else "CONNECT",
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ── Console: Real-time transfer data ─────────────────────────────────
        Text(
            text  = "CONSOLE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall),
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                // Status label + filename
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        text  = transferStatus.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(transferStatus.label, isDark)
                    )
                    if (transferStatus.fileName.isNotEmpty()) {
                        Text(
                            text  = transferStatus.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress      = { transferStatus.progress },
                    modifier      = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color         = MaterialTheme.colorScheme.primary,
                    trackColor    = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Data row: BITRATE + PROGRESS % + INTEGRITY
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DataCell(label = "BITRATE", value = "${transferStatus.bitrate} kbps")
                    DataCell(
                        label = "PROGRESS",
                        value = "${(transferStatus.progress * 100).toInt()}%"
                    )
                    DataCell(
                        label = "INTEGRITY",
                        value = transferStatus.integrityStatus,
                        valueColor = integrityColor(transferStatus.integrityStatus, isDark)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Primary Action: OPEN TUNNEL ───────────────────────────────────────
        Button(
            onClick   = onOpenTunnel,
            modifier  = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape     = MaterialTheme.shapes.extraSmall,
            enabled   = isTunnelReady,
            colors    = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor   = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.outline,
                disabledContentColor   = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(
                text  = "OPEN TUNNEL",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun DataCell(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text  = value,
            style = MaterialTheme.typography.labelMedium,
            color = valueColor
        )
    }
}

// ── State model ───────────────────────────────────────────────────────────────

/**
 * TransferState — the single source of truth for the Console panel.
 * Drives every data readout: status label, filename, progress, bitrate, integrity.
 */
data class TransferState(
    val label:           String = "IDLE",
    val fileName:        String = "",
    val progress:        Float  = 0f,
    val bitrate:         String = "0",
    val integrityStatus: String = "PENDING"
)

// ── Color helpers ─────────────────────────────────────────────────────────────

@Composable
private fun statusColor(label: String, isDark: Boolean): Color = when {
    label.contains("COMPLETE")   -> if (isDark) VektrGreen  else VektrGreenLight
    label.contains("TUNNEL")     -> if (isDark) VektrAmber  else VektrAmberLight
    label.contains("ERROR")      -> if (isDark) VektrRed    else VektrRedLight
    else                         -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun integrityColor(status: String, isDark: Boolean): Color = when (status) {
    "BIT-PERFECT", "VERIFIED" -> if (isDark) VektrGreen  else VektrGreenLight
    "VERIFYING"               -> if (isDark) VektrAmber  else VektrAmberLight
    "FAILED"                  -> if (isDark) VektrRed    else VektrRedLight
    else                      -> MaterialTheme.colorScheme.onSurfaceVariant
}
