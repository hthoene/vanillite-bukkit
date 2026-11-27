// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;
import java.util.EventObject;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/**
 * A packet on its way to or from a player. On Vanillite listeners run where the server writes and
 * reads packets (network threads), like ProtocolLib's listeners on Paper's Netty threads.
 */
public class PacketEvent extends EventObject implements Cancellable {
    private static final long serialVersionUID = 1L;

    private transient PacketContainer packet;
    private final transient Player player;
    private final boolean serverPacket;
    private boolean cancel;
    private boolean readOnly;
    private boolean replaced;

    public PacketEvent(Object source) {
        super(source);
        this.player = null;
        this.serverPacket = false;
    }

    private PacketEvent(Object source, PacketContainer packet, Player player, boolean serverPacket) {
        super(source);
        this.packet = packet;
        this.player = player;
        this.serverPacket = serverPacket;
    }

    public static PacketEvent fromClient(Object source, PacketContainer packet, Player client) {
        return new PacketEvent(source, packet, client, false);
    }

    public static PacketEvent fromServer(Object source, PacketContainer packet, Player recipient) {
        return new PacketEvent(source, packet, recipient, true);
    }

    public boolean isAsync() {
        return true;
    }

    public PacketContainer getPacket() {
        return packet;
    }

    public void setPacket(PacketContainer packet) {
        if (readOnly) {
            throw new IllegalStateException("The packet event is read-only.");
        }
        if (packet == null) {
            throw new IllegalArgumentException("Cannot set packet to null. Use setCancelled() instead.");
        }
        this.packet = packet;
        this.replaced = true;
    }

    /** Whether {@link #setPacket} replaced the packet. */
    public boolean isReplaced() {
        return replaced;
    }

    @Deprecated
    public int getPacketID() {
        return packet.getId();
    }

    public PacketType getPacketType() {
        return packet.getType();
    }

    @Override
    public boolean isCancelled() {
        return cancel;
    }

    @Override
    public void setCancelled(boolean cancel) {
        if (readOnly) {
            throw new IllegalStateException("The packet event is read-only.");
        }
        this.cancel = cancel;
    }

    /** Sets the cancel state without the read-only check (the layer's own use). */
    public void initCancelled(boolean cancel) {
        this.cancel = cancel;
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isPlayerTemporary() {
        return false;
    }

    public boolean isFiltered() {
        return true;
    }

    public boolean isServerPacket() {
        return serverPacket;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isAsynchronous() {
        return true;
    }

    public PacketEvent getBundle() {
        return null;
    }

    @Override
    public String toString() {
        return "PacketEvent[player=" + player + ", packet=" + packet + "]";
    }
}
