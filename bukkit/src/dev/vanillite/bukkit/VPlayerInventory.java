// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import java.lang.invoke.MethodHandle;
import java.util.List;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.PlayerInventory;

/** A player's inventory. Items are not readable through the layer yet; nobody else views it. */
public abstract class VPlayerInventory implements PlayerInventory {
    private static final MethodHandle NEW = Impl.constructor(VPlayerInventory.class, VPlayer.class);

    private final VPlayer player;

    protected VPlayerInventory(VPlayer player) {
        this.player = player;
    }

    static PlayerInventory create(VPlayer player) {
        try {
            return (VPlayerInventory) NEW.invokeExact(player);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    public Player getHolder() {
        return player;
    }

    @Override
    public List<HumanEntity> getViewers() {
        // Other players cannot open a player's inventory on Vanillite.
        return List.of();
    }

    @Override
    public InventoryType getType() {
        return InventoryType.PLAYER;
    }
}
