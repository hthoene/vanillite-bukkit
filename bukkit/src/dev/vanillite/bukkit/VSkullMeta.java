// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.meta.SkullMeta;

/** A player head's meta: its owner is the {@code minecraft:profile} component. */
public abstract class VSkullMeta extends VItemMeta implements SkullMeta {
    private static final String PROFILE = "minecraft:profile";

    protected VSkullMeta() {}

    private Map<?, ?> profile() {
        Object p = other.get(PROFILE);
        if (p instanceof String name) {
            return Map.of("name", name);
        }
        return p instanceof Map<?, ?> m ? m : null;
    }

    private static UUID uuid(Object id) {
        if (id instanceof int[] a && a.length == 4) {
            return new UUID((long) a[0] << 32 | (a[1] & 0xffffffffL), (long) a[2] << 32 | (a[3] & 0xffffffffL));
        }
        if (id instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public String getOwner() {
        Map<?, ?> p = profile();
        return p != null && p.get("name") instanceof String n ? n : null;
    }

    @Override
    public boolean hasOwner() {
        return profile() != null;
    }

    @Override
    public boolean setOwner(String owner) {
        if (owner == null) {
            other.remove(PROFILE);
        } else {
            other.put(PROFILE, new LinkedHashMap<>(Map.of("name", owner)));
        }
        return true;
    }

    @Override
    public void setPlayerProfile(PlayerProfile profile) {
        if (profile == null) {
            other.remove(PROFILE);
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        if (profile.getName() != null) {
            p.put("name", profile.getName());
        }
        UUID id = profile.getId();
        if (id != null) {
            p.put("id", new int[] {(int) (id.getMostSignificantBits() >> 32), (int) id.getMostSignificantBits(),
                (int) (id.getLeastSignificantBits() >> 32), (int) id.getLeastSignificantBits()});
        }
        List<Object> props = new ArrayList<>();
        for (ProfileProperty prop : profile.getProperties()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", prop.getName());
            m.put("value", prop.getValue());
            if (prop.getSignature() != null) {
                m.put("signature", prop.getSignature());
            }
            props.add(m);
        }
        if (!props.isEmpty()) {
            p.put("properties", props);
        }
        other.put(PROFILE, p);
    }

    @Override
    public PlayerProfile getPlayerProfile() {
        Map<?, ?> p = profile();
        if (p == null) {
            return null;
        }
        PlayerProfile profile = VProfile.create(uuid(p.get("id")), p.get("name") instanceof String n ? n : null);
        if (p.get("properties") instanceof List<?> props) {
            for (Object o : props) {
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String name && m.get("value") instanceof String value) {
                    profile.setProperty(new ProfileProperty(name, value, m.get("signature") instanceof String sig ? sig : null));
                }
            }
        }
        return profile;
    }

    @Override
    public OfflinePlayer getOwningPlayer() {
        Map<?, ?> p = profile();
        if (p == null) {
            return null;
        }
        UUID id = uuid(p.get("id"));
        if (id != null) {
            return Bukkit.getOfflinePlayer(id);
        }
        return p.get("name") instanceof String n ? Bukkit.getOfflinePlayer(n) : null;
    }

    @Override
    public boolean setOwningPlayer(OfflinePlayer owner) {
        if (owner == null) {
            other.remove(PROFILE);
            return true;
        }
        setPlayerProfile(VProfile.create(owner.getUniqueId(), owner.getName()));
        return true;
    }

    @Override
    public VSkullMeta clone() {
        return (VSkullMeta) super.clone();
    }
}
