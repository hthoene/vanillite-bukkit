// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;

/** Protocol ids of registry entries (entity types, sound events), from the server. */
final class Registries {
    private Registries() {}

    private static volatile Table entityTypes;
    private static volatile Table sounds;

    /** The names of a registry in protocol id order and their ids. */
    record Table(List<String> names, Map<String, Integer> ids) {
        static Table load(String registry) {
            var s = Native.scratch();
            int status = Host.registry_entries(Native.plugin, s.str(registry), Native.sink(s));
            List<String> names = status == Abi.status.OK ? Native.sunk().lines().toList() : List.of();
            Map<String, Integer> ids = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                ids.put(names.get(i), i);
            }
            return new Table(names, ids);
        }

        String name(int id) {
            return id >= 0 && id < names.size() ? names.get(id) : null;
        }

        int id(String name) {
            return ids.getOrDefault(name, -1);
        }
    }

    private static Table entityTypes() {
        Table t = entityTypes;
        if (t == null) {
            t = Table.load("minecraft:entity_type");
            entityTypes = t;
        }
        return t;
    }

    private static Table sounds() {
        Table t = sounds;
        if (t == null) {
            t = Table.load("minecraft:sound_event");
            sounds = t;
        }
        return t;
    }

    static EntityType entityType(int id) {
        String name = entityTypes().name(id);
        if (name == null) {
            return EntityType.UNKNOWN;
        }
        for (EntityType type : EntityType.values()) {
            if (type != EntityType.UNKNOWN && type.getKey().toString().equals(name)) {
                return type;
            }
        }
        return EntityType.UNKNOWN;
    }

    static int entityTypeId(EntityType type) {
        if (type == null || type == EntityType.UNKNOWN) {
            throw new IllegalArgumentException("Vanillite: entity type " + type + " has no protocol id");
        }
        int id = entityTypes().id(type.getKey().toString());
        if (id < 0) {
            throw new IllegalArgumentException("Vanillite: unknown entity type " + type);
        }
        return id;
    }

    /** The sound of a registry id; null when Bukkit does not know it. */
    static Sound sound(int id) {
        String name = sounds().name(id);
        return name == null ? null : sound(name);
    }

    static Sound sound(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        return k == null ? null : Registry.SOUNDS.get(k);
    }

    /** The registry id of a sound, -1 for one the server does not have. */
    static int soundId(Sound sound) {
        return sounds().id(sound.getKey().toString());
    }
}
