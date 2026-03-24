'use strict';

/**
 * Vektr Signaling Bridge — server/index.js
 * ────────────────────────────────────────
 * Volatile in-memory registry. No database. No persistence.
 * The nodeRegistry only exists while a Node is connected.
 * This server NEVER touches audio data — strictly handshake relay.
 */

const io = require('socket.io')(3000, { cors: { origin: "*" } });

// Volatile Registry: Only exists while the Node is connected
const nodeRegistry = new Map();

io.on('connection', (socket) => {
    console.log('New connection attempt...');

    // 1. REGISTRATION: Device announces its unique Node ID
    socket.on('register_node', (nodeId) => {
        socket.nodeId = nodeId;
        nodeRegistry.set(nodeId, socket.id);
        console.log(`Node [${nodeId}] is now ONLINE.`);

        // Notify all other Nodes that a new peer is reachable
        socket.broadcast.emit('node_joined', nodeId);
    });

    // 2. SIGNALING RELAY: Forwarding the P2P handshake
    // signalData carries offer / answer / ICE — the bridge never inspects it
    socket.on('tunnel_signal', ({ targetNodeId, signalData }) => {
        const targetSocketId = nodeRegistry.get(targetNodeId);

        if (targetSocketId) {
            // Relay the "Tunnel Offer" or "ICE Candidate" directly
            io.to(targetSocketId).emit('incoming_signal', {
                senderNodeId: socket.nodeId,
                signalData:   signalData
            });
        } else {
            socket.emit('error', { message: "Target Node Offline" });
        }
    });

    // 3. PURGE: Remove Node from registry immediately on disconnect
    socket.on('disconnect', () => {
        if (socket.nodeId) {
            nodeRegistry.delete(socket.nodeId);
            console.log(`Node [${socket.nodeId}] is now OFFLINE.`);
        }
    });
});
