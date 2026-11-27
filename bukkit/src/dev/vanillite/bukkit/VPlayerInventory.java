// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.invoke.MethodHandle;
import java.util.List;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * A player's inventory in Bukkit's slot order (0–8 hotbar, 9–35 main, 36–39 boots to helmet, 40
 * off hand); every slot is read from and written to the server ({@code /item} slot names).
 */
public abstract class VPlayerInventory extends VInventory implements PlayerInventory {
    private static final MethodHandle NEW = Impl.constructor(VPlayerInventory.class, VPlayer.class);
    private static final String[] SLOTS = new String[41];

    static {
        for (int i = 0; i < 36; i++) {
            SLOTS[i] = "container." + i;
        }
        SLOTS[36] = "armor.feet";
        SLOTS[37] = "armor.legs";
        SLOTS[38] = "armor.chest";
        SLOTS[39] = "armor.head";
        SLOTS[40] = "weapon.offhand";
    }

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

    /**
     * Whether the slots cannot be reached now: during an event of the player's own action (a menu
     * click, breaking a block) the core lends the inventory out.
     */
    private boolean lentOut() {
        if (Events.depth == 0) {
            return false;
        }
        int w = player.worldId();
        var s = Native.scratch();
        int status = Host.entity_item_get(Ctx.current, w, s.uuid(player.getUniqueId()), s.str(SLOTS[0]), Native.sink(s));
        return status == Abi.status.UNSUPPORTED || status == Abi.status.WRONG_OWNER || status == Abi.status.NOT_FOUND;
    }

    /** Runs a change after the running event when the inventory is lent out to it. */
    private boolean deferred(Runnable change) {
        if (!lentOut()) {
            return false;
        }
        Layer.server.scheduler.onWorld(player.worldId(), change);
        return true;
    }

    @Override
    public java.util.HashMap<Integer, ItemStack> addItem(ItemStack... items) {
        ItemStack[] copy = items.clone();
        if (deferred(() -> super.addItem(copy))) {
            return new java.util.HashMap<>();
        }
        return super.addItem(items);
    }

    @Override
    public java.util.HashMap<Integer, ItemStack> removeItem(ItemStack... items) {
        ItemStack[] copy = items.clone();
        if (deferred(() -> super.removeItem(copy))) {
            return new java.util.HashMap<>();
        }
        return super.removeItem(items);
    }

    private VItemStack read(String slot) {
        int w = player.worldId();
        var s = Native.scratch();
        var sink = Native.sink(s);
        if (Host.entity_item_get(Ctx.current, w, s.uuid(player.getUniqueId()), s.str(slot), sink) != Abi.status.OK) {
            return player.eventSlot(slot);
        }
        VItemStack v = VItemStack.fromSnbt(Native.sunk());
        return v.isEmpty() ? null : v;
    }

    private void write(String slot, VItemStack stack) {
        int w = player.worldId();
        String snbt = stack == null ? "{}" : stack.toSnbt();
        var s = Native.scratch();
        int status = Host.entity_item_set(Ctx.current, w, s.uuid(player.getUniqueId()), s.str(slot), s.str(snbt));
        if (status != Abi.status.OK && Events.depth > 0 && (status == Abi.status.UNSUPPORTED
                || status == Abi.status.WRONG_OWNER || status == Abi.status.NOT_FOUND)) {
            Layer.server.scheduler.onWorld(w, () -> write(slot, stack));
            return;
        }
        Ctx.check(status, "PlayerInventory.setItem");
    }

    @Override
    VItemStack slot(int index) {
        return read(SLOTS[index]);
    }

    @Override
    void put(int index, VItemStack stack) {
        write(SLOTS[index], stack);
    }

    @Override
    int storageSize() {
        return 36;
    }

    @Override
    public int getSize() {
        return 41;
    }

    @Override
    public Player getHolder() {
        return player;
    }

    @Override
    public org.bukkit.inventory.InventoryHolder getHolder(boolean useSnapshot) {
        return player;
    }

    @Override
    public List<HumanEntity> getViewers() {
        // Other players cannot open a player's inventory on Vanillite.
        return List.of(player);
    }

    @Override
    public InventoryType getType() {
        return InventoryType.PLAYER;
    }

    private ItemStack orAir(VItemStack v) {
        return v == null ? VItemStack.empty() : v;
    }

    @Override
    public ItemStack[] getArmorContents() {
        return new ItemStack[] {getItem(36), getItem(37), getItem(38), getItem(39)};
    }

    @Override
    public void setArmorContents(ItemStack[] items) {
        for (int i = 0; i < 4; i++) {
            setItem(36 + i, items != null && i < items.length ? items[i] : null);
        }
    }

    @Override
    public ItemStack[] getExtraContents() {
        return new ItemStack[] {getItem(40)};
    }

    @Override
    public void setExtraContents(ItemStack[] items) {
        setItem(40, items != null && items.length > 0 ? items[0] : null);
    }

    @Override
    public ItemStack getHelmet() {
        return getItem(39);
    }

    @Override
    public ItemStack getChestplate() {
        return getItem(38);
    }

    @Override
    public ItemStack getLeggings() {
        return getItem(37);
    }

    @Override
    public ItemStack getBoots() {
        return getItem(36);
    }

    @Override
    public void setHelmet(ItemStack item) {
        setItem(39, item);
    }

    @Override
    public void setChestplate(ItemStack item) {
        setItem(38, item);
    }

    @Override
    public void setLeggings(ItemStack item) {
        setItem(37, item);
    }

    @Override
    public void setBoots(ItemStack item) {
        setItem(36, item);
    }

    private static int index(EquipmentSlot slot) {
        return switch (slot) {
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            case OFF_HAND -> 40;
            default -> -1;
        };
    }

    @Override
    public void setItem(EquipmentSlot slot, ItemStack item) {
        if (slot == EquipmentSlot.HAND) {
            setItemInMainHand(item);
        } else if (index(slot) >= 0) {
            setItem(index(slot), item);
        } else {
            throw new IllegalArgumentException("Not a player slot: " + slot);
        }
    }

    @Override
    public ItemStack getItem(EquipmentSlot slot) {
        if (slot == EquipmentSlot.HAND) {
            return getItemInMainHand();
        }
        if (index(slot) < 0) {
            throw new IllegalArgumentException("Not a player slot: " + slot);
        }
        return orAir(slot(index(slot)));
    }

    @Override
    public ItemStack getItemInMainHand() {
        return orAir(read("weapon.mainhand"));
    }

    @Override
    public void setItemInMainHand(ItemStack item) {
        write("weapon.mainhand", item == null || item.isEmpty() ? null : VItemStack.of(item));
    }

    @Override
    public ItemStack getItemInOffHand() {
        return orAir(slot(40));
    }

    @Override
    public void setItemInOffHand(ItemStack item) {
        setItem(40, item);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ItemStack getItemInHand() {
        return getItemInMainHand();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setItemInHand(ItemStack stack) {
        setItemInMainHand(stack);
    }

    @Override
    public int getHeldItemSlot() {
        return (int) player.get(Abi.player_prop.SELECTED_SLOT);
    }

    @Override
    public void setHeldItemSlot(int slot) {
        if (slot < 0 || slot > 8) {
            throw new IllegalArgumentException("Slot is not between 0 and 8 inclusive");
        }
        player.set(Abi.player_prop.SELECTED_SLOT, slot);
    }
}
