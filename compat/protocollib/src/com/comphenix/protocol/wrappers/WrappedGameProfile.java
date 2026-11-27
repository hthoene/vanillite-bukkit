// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** A game profile: id, name and properties. */
public class WrappedGameProfile extends AbstractWrapper {
    private final UUID id;
    private final String name;
    private final Multimap<String, WrappedSignedProperty> properties = LinkedHashMultimap.create();

    public WrappedGameProfile(String id, String name) {
        this(id == null ? null : UUID.fromString(id), name);
    }

    public WrappedGameProfile(UUID id, String name) {
        super(WrappedGameProfile.class);
        this.id = id;
        this.name = name;
        this.handle = this;
    }

    public static WrappedGameProfile fromPlayer(Player player) {
        WrappedGameProfile p = new WrappedGameProfile(player.getUniqueId(), player.getName());
        try {
            for (var property : player.getPlayerProfile().getProperties()) {
                p.properties.put(property.getName(),
                        new WrappedSignedProperty(property.getName(), property.getValue(), property.getSignature()));
            }
        } catch (RuntimeException e) {
            // Profiles without properties on this server.
        }
        return p;
    }

    public static WrappedGameProfile fromOfflinePlayer(OfflinePlayer player) {
        return new WrappedGameProfile(player.getUniqueId(), player.getName());
    }

    public static WrappedGameProfile fromHandle(Object handle) {
        return (WrappedGameProfile) handle;
    }

    public UUID getUUID() {
        return id;
    }

    public String getId() {
        return id == null ? null : id.toString();
    }

    public String getName() {
        return name;
    }

    public Multimap<String, WrappedSignedProperty> getProperties() {
        return properties;
    }

    public WrappedGameProfile withName(String newName) {
        WrappedGameProfile p = new WrappedGameProfile(id, newName);
        p.properties.putAll(properties);
        return p;
    }

    public WrappedGameProfile withId(String newId) {
        WrappedGameProfile p = new WrappedGameProfile(newId, name);
        p.properties.putAll(properties);
        return p;
    }

    public boolean isComplete() {
        return id != null && name != null && !name.isBlank();
    }

    @Override
    public String toString() {
        return "WrappedGameProfile[id=" + id + ", name=" + name + ", properties=" + properties + "]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedGameProfile p && Objects.equals(p.id, id) && Objects.equals(p.name, name)
                && p.properties.equals(properties);
    }
}
