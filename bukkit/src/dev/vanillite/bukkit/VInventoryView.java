// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * A player's open menu as Bukkit sees it: the container ({@code top}) and the player's inventory
 * ({@code bottom}) with Bukkit's raw slot numbers, which are the menu's slot numbers.
 */
public abstract class VInventoryView implements InventoryView {
    final VPlayer player;
    final VInventory top;
    final Component title;
    private ItemStack cursor;

    protected VInventoryView(VPlayer player, VInventory top, Component title) {
        this.player = player;
        this.top = top;
        this.title = title == null ? Component.empty() : title;
    }

    static VInventoryView create(VPlayer player, VInventory top, Component title) {
        try {
            return Impl.of(VInventoryView.class).getDeclaredConstructor(VPlayer.class, VInventory.class, Component.class)
                    .newInstance(player, top, title);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean own() {
        return top.getType() == InventoryType.CRAFTING;
    }

    @Override
    public Inventory getTopInventory() {
        return top;
    }

    @Override
    public Inventory getBottomInventory() {
        return player.getInventory();
    }

    @Override
    public HumanEntity getPlayer() {
        return player;
    }

    @Override
    public InventoryType getType() {
        return top.getType();
    }

    @Override
    public Inventory getInventory(int rawSlot) {
        if (rawSlot < 0 || rawSlot >= countSlots()) {
            return null;
        }
        return rawSlot < top.getSize() ? top : getBottomInventory();
    }

    @Override
    public int convertSlot(int rawSlot) {
        int n = top.getSize();
        if (rawSlot < n) {
            return rawSlot;
        }
        if (own()) {
            if (rawSlot < 9) {
                return 39 - (rawSlot - 5);
            }
            if (rawSlot < 36) {
                return rawSlot;
            }
            return rawSlot < 45 ? rawSlot - 36 : 40;
        }
        int r = rawSlot - n;
        return r < 27 ? r + 9 : r - 27;
    }

    @Override
    public InventoryType.SlotType getSlotType(int slot) {
        if (slot < 0) {
            return InventoryType.SlotType.OUTSIDE;
        }
        int n = top.getSize();
        if (slot < n) {
            if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.WORKBENCH) {
                return slot == 0 ? InventoryType.SlotType.RESULT : InventoryType.SlotType.CRAFTING;
            }
            if ((top.getType() == InventoryType.FURNACE || top.getType() == InventoryType.SMOKER
                    || top.getType() == InventoryType.BLAST_FURNACE) && slot == 2) {
                return InventoryType.SlotType.RESULT;
            }
            return InventoryType.SlotType.CONTAINER;
        }
        int index = convertSlot(slot);
        if (index < 9) {
            return InventoryType.SlotType.QUICKBAR;
        }
        return index >= 36 && index < 40 ? InventoryType.SlotType.ARMOR : InventoryType.SlotType.CONTAINER;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot == eventSlot && Events.depth > 0) {
            return eventItem;
        }
        Inventory inv = getInventory(slot);
        return inv == null ? null : inv.getItem(convertSlot(slot));
    }

    @Override
    public void setItem(int slot, ItemStack item) {
        if (slot == eventSlot && Events.depth > 0) {
            eventItem = item;
        }
        Inventory inv = getInventory(slot);
        if (inv != null) {
            inv.setItem(convertSlot(slot), item);
        } else if (item != null && !item.isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), item);
        }
    }

    @Override
    public ItemStack getCursor() {
        return cursor == null ? VItemStack.empty() : cursor;
    }

    @Override
    public void setCursor(ItemStack item) {
        cursor = item;
    }

    void cursor(ItemStack item) {
        cursor = item;
    }

    /**
     * The slot a running click event is about and its item: the player's own slots cannot be
     * read while the core hands the click to plugins.
     */
    private int eventSlot = Integer.MIN_VALUE;
    private ItemStack eventItem;

    void eventItem(int slot, ItemStack item) {
        eventSlot = slot;
        eventItem = item;
    }

    @Override
    public int countSlots() {
        return own() ? 46 : top.getSize() + 36;
    }

    @Override
    public void close() {
        player.closeInventory();
    }

    @Override
    public void open() {
        player.openInventory(this);
    }

    @Override
    public boolean setProperty(Property prop, int value) {
        return false;
    }

    @Override
    public Component title() {
        return title;
    }

    @Override
    public String getTitle() {
        return LegacyComponentSerializer.legacySection().serialize(title);
    }

    @Override
    public String getOriginalTitle() {
        return getTitle();
    }

    @Override
    public void setTitle(String title) {
        // The server has no menu title change yet; the title stays.
    }
}
