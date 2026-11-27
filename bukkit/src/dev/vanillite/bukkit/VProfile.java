// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.vanillite.jvm.Native;
import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A player's profile (UUID, name, properties). */
public abstract class VProfile implements PlayerProfile {
    private static final MethodHandle NEW = Impl.constructor(VProfile.class, UUID.class, String.class);

    private UUID id;
    private String name;
    private final Set<ProfileProperty> properties = new HashSet<>();

    protected VProfile(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    static PlayerProfile create(UUID id, String name) {
        try {
            return (VProfile) NEW.invokeExact(id, name);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public UUID setId(UUID uuid) {
        UUID old = id;
        id = uuid;
        return old;
    }

    @Override
    public UUID getUniqueId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String setName(String name) {
        String old = this.name;
        this.name = name;
        return old;
    }

    @Override
    public Set<ProfileProperty> getProperties() {
        return properties;
    }

    @Override
    public boolean hasProperty(String property) {
        return properties.stream().anyMatch(p -> p.getName().equals(property));
    }

    @Override
    public void setProperty(ProfileProperty property) {
        removeProperty(property.getName());
        properties.add(property);
    }

    @Override
    public boolean removeProperty(String property) {
        return properties.removeIf(p -> p.getName().equals(property));
    }

    @Override
    public void clearProperties() {
        properties.clear();
    }

    @Override
    public boolean isComplete() {
        return id != null && name != null;
    }

    @Override
    public PlayerProfile clone() {
        PlayerProfile copy = create(id, name);
        copy.setProperties(properties);
        return copy;
    }

    @Override
    public java.util.Map<String, Object> serialize() {
        return java.util.Map.of("uniqueId", String.valueOf(id), "name", String.valueOf(name));
    }
}
