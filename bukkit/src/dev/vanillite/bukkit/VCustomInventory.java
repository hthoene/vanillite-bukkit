// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;

/**
 * An inventory a plugin created ({@code Bukkit.createInventory}): its items live here. Players see
 * it as a plugin menu of the server; while they do, every change is sent to them, and their
 * clicks that move items are read back ({@link #resync}).
 */
public abstract class VCustomInventory extends VInventory {
    final InventoryType type;
    final InventoryHolder holder;
    final Component title;
    private final VItemStack[] items;
    final List<HumanEntity> viewers = new ArrayList<>();

    protected VCustomInventory(InventoryType type, int size, InventoryHolder holder, Component title) {
        this.type = type;
        this.holder = holder;
        this.title = title == null ? type.defaultTitle() : title;
        this.items = new VItemStack[size];
    }

    static VCustomInventory create(InventoryType type, int size, InventoryHolder holder, Component title) {
        try {
            return Impl.of(VCustomInventory.class)
                    .getDeclaredConstructor(InventoryType.class, int.class, InventoryHolder.class, Component.class)
                    .newInstance(type, size, holder, title);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The server's menu type for this inventory. */
    String menuType() {
        return switch (type) {
            case DISPENSER, DROPPER -> "minecraft:generic_3x3";
            case CRAFTER -> "minecraft:crafter_3x3";
            case HOPPER -> "minecraft:hopper";
            case SHULKER_BOX -> "minecraft:shulker_box";
            default -> "minecraft:generic_9x" + Math.max(1, Math.min(6, (items.length + 8) / 9));
        };
    }

    /** The items as the SNBT list with {@code Slot} that opening a menu takes. */
    String itemsSnbt() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && !items[i].isEmpty()) {
                String s = items[i].toSnbt();
                if (b.length() > 1) {
                    b.append(',');
                }
                b.append("{Slot:").append(i).append("b,").append(s, 1, s.length());
            }
        }
        return b.append(']').toString();
    }

    @Override
    VItemStack slot(int index) {
        return items[index];
    }

    @Override
    void put(int index, VItemStack stack) {
        items[index] = stack;
        for (HumanEntity h : viewers) {
            if (h instanceof VPlayer p) {
                p.sendMenuSlot(index, stack);
            }
        }
    }

    /** Takes over what a player's clicks did to the menu. */
    void resync(VPlayer player) {
        for (int i = 0; i < items.length; i++) {
            VItemStack v = player.menuSlot(i);
            if (v != null) {
                items[i] = v.isEmpty() ? null : v;
            }
        }
    }

    @Override
    public int getSize() {
        return items.length;
    }

    @Override
    public List<HumanEntity> getViewers() {
        return new ArrayList<>(viewers);
    }

    @Override
    public InventoryType getType() {
        return type;
    }

    @Override
    public InventoryHolder getHolder() {
        return holder;
    }
}
