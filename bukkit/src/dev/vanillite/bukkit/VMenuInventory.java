// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.util.List;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;

/**
 * The container part of a menu the server opened for a player (a chest, a furnace, its own
 * crafting grid, ...): every slot is read from and written to the player's open menu.
 */
public abstract class VMenuInventory extends VInventory {
    final VPlayer player;
    final String menuType;
    private final InventoryType type;
    private final int size;

    protected VMenuInventory(VPlayer player, String menuType) {
        this.player = player;
        this.menuType = menuType;
        String t = menuType.substring(menuType.indexOf(':') + 1);
        if (t.startsWith("generic_9x")) {
            type = InventoryType.CHEST;
            size = 9 * (t.charAt(t.length() - 1) - '0');
        } else {
            InventoryType it;
            int n;
            switch (t) {
                case "inventory" -> { it = InventoryType.CRAFTING; n = 5; }
                case "generic_3x3" -> { it = InventoryType.DISPENSER; n = 9; }
                case "crafter_3x3" -> { it = InventoryType.CRAFTER; n = 9; }
                case "hopper" -> { it = InventoryType.HOPPER; n = 5; }
                case "shulker_box" -> { it = InventoryType.SHULKER_BOX; n = 27; }
                case "furnace" -> { it = InventoryType.FURNACE; n = 3; }
                case "blast_furnace" -> { it = InventoryType.BLAST_FURNACE; n = 3; }
                case "smoker" -> { it = InventoryType.SMOKER; n = 3; }
                case "brewing_stand" -> { it = InventoryType.BREWING; n = 5; }
                case "anvil" -> { it = InventoryType.ANVIL; n = 3; }
                case "grindstone" -> { it = InventoryType.GRINDSTONE; n = 3; }
                case "smithing" -> { it = InventoryType.SMITHING; n = 4; }
                case "enchantment" -> { it = InventoryType.ENCHANTING; n = 2; }
                case "beacon" -> { it = InventoryType.BEACON; n = 1; }
                case "cartography_table" -> { it = InventoryType.CARTOGRAPHY; n = 3; }
                case "loom" -> { it = InventoryType.LOOM; n = 4; }
                case "stonecutter" -> { it = InventoryType.STONECUTTER; n = 2; }
                case "merchant" -> { it = InventoryType.MERCHANT; n = 3; }
                case "crafting" -> { it = InventoryType.WORKBENCH; n = 10; }
                case "lectern" -> { it = InventoryType.LECTERN; n = 1; }
                default -> { it = InventoryType.CHEST; n = 27; }
            }
            type = it;
            size = n;
        }
    }

    static VMenuInventory create(VPlayer player, String menuType) {
        try {
            return Impl.of(VMenuInventory.class).getDeclaredConstructor(VPlayer.class, String.class).newInstance(player, menuType);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    VItemStack slot(int index) {
        VItemStack v = player.menuSlot(index);
        return v == null || v.isEmpty() ? null : v;
    }

    @Override
    void put(int index, VItemStack stack) {
        player.sendMenuSlot(index, stack);
    }

    @Override
    public int getSize() {
        return size;
    }

    @Override
    public List<HumanEntity> getViewers() {
        return List.of(player);
    }

    @Override
    public InventoryType getType() {
        return type;
    }

    @Override
    public InventoryHolder getHolder() {
        return type == InventoryType.CRAFTING ? player : null;
    }
}
