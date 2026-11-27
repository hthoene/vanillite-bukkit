// SPDX-License-Identifier: GPL-3.0-only
package org.bukkit.craftbukkit.entity;

import dev.vanillite.bukkit.VEntity;
import dev.vanillite.bukkit.VWorld;
import java.util.UUID;
import org.bukkit.permissions.PermissibleBase;

/**
 * Compatibility name for players: permission plugins (LuckPerms and others) replace the
 * {@code perm} field by reflection with their own {@link PermissibleBase}; every permission check
 * of the player goes through it.
 */
public abstract class CraftHumanEntity extends VEntity {
    protected PermissibleBase perm;

    protected CraftHumanEntity(VWorld world, UUID uuid) {
        super(world, uuid);
    }
}
