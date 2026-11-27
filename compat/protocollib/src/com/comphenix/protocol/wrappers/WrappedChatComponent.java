// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import dev.vanillite.bukkit.compat.protocollib.Nbt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * A text component as JSON. On the wire components are NBT; a component read from a packet keeps
 * its NBT and is written back unchanged unless its JSON was set.
 */
public class WrappedChatComponent extends AbstractWrapper implements ClonableWrapper {
    private String json;
    private byte[] nbt;

    private WrappedChatComponent(String json, byte[] nbt) {
        super(WrappedChatComponent.class);
        this.json = json;
        this.nbt = nbt;
        this.handle = this;
    }

    /** A component read from a packet (network NBT). */
    public static WrappedChatComponent fromNbt(byte[] nbt) {
        return new WrappedChatComponent(null, nbt);
    }

    /** The component as network NBT. */
    public byte[] toNbt() {
        if (nbt == null) {
            nbt = Nbt.fromJson(getJson());
        }
        return nbt;
    }

    public static WrappedChatComponent fromHandle(Object handle) {
        if (handle instanceof WrappedChatComponent w) {
            return w;
        }
        if (handle instanceof Component c) {
            return fromJson(GsonComponentSerializer.gson().serialize(c));
        }
        if (handle instanceof String s) {
            return fromJson(s);
        }
        throw new IllegalArgumentException("Vanillite: not a chat component: " + handle);
    }

    public static WrappedChatComponent fromJson(String json) {
        return new WrappedChatComponent(json, null);
    }

    public static WrappedChatComponent fromText(String text) {
        return fromJson(GsonComponentSerializer.gson().serialize(Component.text(text)));
    }

    public static WrappedChatComponent[] fromChatMessage(String message) {
        return new WrappedChatComponent[] {fromLegacyText(message)};
    }

    public static WrappedChatComponent fromLegacyText(String message) {
        return fromJson(GsonComponentSerializer.gson().serialize(LegacyComponentSerializer.legacySection().deserialize(message)));
    }

    public String getJson() {
        if (json == null) {
            json = Nbt.toJson(nbt);
        }
        return json;
    }

    public void setJson(String json) {
        this.json = json;
        this.nbt = null;
    }

    @Override
    public WrappedChatComponent deepClone() {
        return new WrappedChatComponent(json, nbt);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedChatComponent w && getJson().equals(w.getJson());
    }

    @Override
    public int hashCode() {
        return getJson().hashCode();
    }

    @Override
    public String toString() {
        return "WrappedChatComponent[json=" + getJson() + "]";
    }
}
