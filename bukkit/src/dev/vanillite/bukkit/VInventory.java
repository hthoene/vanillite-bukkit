// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * What every inventory of the layer shares: the Bukkit operations (add, remove, find, contents)
 * on top of the two slot accessors {@link #slot} and {@link #put} of a kind of inventory.
 */
public abstract class VInventory implements Inventory {
    private int maxStack = 99;

    protected VInventory() {}

    /** The stack in a slot, null when empty. */
    abstract VItemStack slot(int index);

    /** Puts a stack (null or empty: nothing) into a slot. */
    abstract void put(int index, VItemStack stack);

    /** Slots that {@link #addItem} and {@link #getStorageContents} use. */
    int storageSize() {
        return getSize();
    }

    @Override
    public ItemStack getItem(int index) {
        VItemStack v = slot(index);
        return v == null || v.isEmpty() ? null : v;
    }

    @Override
    public void setItem(int index, ItemStack item) {
        put(index, item == null || item.isEmpty() ? null : VItemStack.of(item).clone());
    }

    @Override
    public int getMaxStackSize() {
        return maxStack;
    }

    @Override
    public void setMaxStackSize(int size) {
        maxStack = size;
    }

    @Override
    public ItemStack[] getContents() {
        ItemStack[] out = new ItemStack[getSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = getItem(i);
        }
        return out;
    }

    @Override
    public void setContents(ItemStack[] items) {
        if (items.length > getSize()) {
            throw new IllegalArgumentException("Invalid inventory size (" + items.length + "); expected " + getSize() + " or less");
        }
        for (int i = 0; i < getSize(); i++) {
            setItem(i, i < items.length ? items[i] : null);
        }
    }

    @Override
    public ItemStack[] getStorageContents() {
        return Arrays.copyOf(getContents(), storageSize());
    }

    @Override
    public void setStorageContents(ItemStack[] items) {
        if (items.length > storageSize()) {
            throw new IllegalArgumentException("Invalid inventory size (" + items.length + "); expected " + storageSize() + " or less");
        }
        for (int i = 0; i < storageSize(); i++) {
            setItem(i, i < items.length ? items[i] : null);
        }
    }

    private int limit(ItemStack item) {
        return Math.min(getMaxStackSize(), item.getMaxStackSize());
    }

    @Override
    public HashMap<Integer, ItemStack> addItem(ItemStack... items) {
        HashMap<Integer, ItemStack> left = new HashMap<>();
        int size = storageSize();
        for (int n = 0; n < items.length; n++) {
            ItemStack item = items[n];
            if (item == null) {
                throw new IllegalArgumentException("Item cannot be null");
            }
            int amount = item.getAmount();
            int max = limit(item);
            for (int i = 0; i < size && amount > 0; i++) {
                VItemStack s = slot(i);
                if (s != null && !s.isEmpty() && s.getAmount() < max && s.isSimilar(item)) {
                    int add = Math.min(max - s.getAmount(), amount);
                    VItemStack c = s.clone();
                    c.setAmount(s.getAmount() + add);
                    put(i, c);
                    amount -= add;
                }
            }
            for (int i = 0; i < size && amount > 0; i++) {
                VItemStack s = slot(i);
                if (s == null || s.isEmpty()) {
                    int add = Math.min(max, amount);
                    VItemStack c = VItemStack.of(item).clone();
                    c.setAmount(add);
                    put(i, c);
                    amount -= add;
                }
            }
            if (amount > 0) {
                ItemStack rest = item.clone();
                rest.setAmount(amount);
                left.put(n, rest);
            }
        }
        return left;
    }

    @Override
    public HashMap<Integer, ItemStack> removeItem(ItemStack... items) {
        HashMap<Integer, ItemStack> left = new HashMap<>();
        for (int n = 0; n < items.length; n++) {
            ItemStack item = items[n];
            int amount = item.getAmount();
            for (int i = 0; i < getSize() && amount > 0; i++) {
                VItemStack s = slot(i);
                if (s != null && !s.isEmpty() && s.isSimilar(item)) {
                    int take = Math.min(s.getAmount(), amount);
                    amount -= take;
                    if (take == s.getAmount()) {
                        put(i, null);
                    } else {
                        VItemStack c = s.clone();
                        c.setAmount(s.getAmount() - take);
                        put(i, c);
                    }
                }
            }
            if (amount > 0) {
                ItemStack rest = item.clone();
                rest.setAmount(amount);
                left.put(n, rest);
            }
        }
        return left;
    }

    @Override
    public HashMap<Integer, ItemStack> removeItemAnySlot(ItemStack... items) {
        return removeItem(items);
    }

    @Override
    public boolean contains(Material material) {
        return first(material) >= 0;
    }

    @Override
    public boolean contains(ItemStack item) {
        return item != null && first(item) >= 0;
    }

    @Override
    public boolean contains(Material material, int amount) {
        int found = 0;
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.getType() == material) {
                found += s.getAmount();
            }
        }
        return found >= amount;
    }

    @Override
    public boolean contains(ItemStack item, int amount) {
        if (item == null) {
            return false;
        }
        int found = 0;
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.equals(item)) {
                found++;
            }
        }
        return found >= amount;
    }

    @Override
    public boolean containsAtLeast(ItemStack item, int amount) {
        if (item == null) {
            return false;
        }
        int found = 0;
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.isSimilar(item)) {
                found += s.getAmount();
            }
        }
        return found >= amount;
    }

    @Override
    public HashMap<Integer, ? extends ItemStack> all(Material material) {
        HashMap<Integer, ItemStack> out = new HashMap<>();
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.getType() == material) {
                out.put(i, s);
            }
        }
        return out;
    }

    @Override
    public HashMap<Integer, ? extends ItemStack> all(ItemStack item) {
        HashMap<Integer, ItemStack> out = new HashMap<>();
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.equals(item)) {
                out.put(i, s);
            }
        }
        return out;
    }

    @Override
    public int first(Material material) {
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.getType() == material && !s.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int first(ItemStack item) {
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.equals(item)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int firstEmpty() {
        for (int i = 0; i < storageSize(); i++) {
            VItemStack s = slot(i);
            if (s == null || s.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && !s.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void remove(Material material) {
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.getType() == material) {
                put(i, null);
            }
        }
    }

    @Override
    public void remove(ItemStack item) {
        for (int i = 0; i < getSize(); i++) {
            VItemStack s = slot(i);
            if (s != null && s.equals(item)) {
                put(i, null);
            }
        }
    }

    @Override
    public void clear(int index) {
        put(index, null);
    }

    @Override
    public void clear() {
        for (int i = 0; i < getSize(); i++) {
            put(i, null);
        }
    }

    @Override
    public int close() {
        List<HumanEntity> viewers = new ArrayList<>(getViewers());
        viewers.forEach(HumanEntity::closeInventory);
        return viewers.size();
    }

    @Override
    public InventoryHolder getHolder(boolean useSnapshot) {
        return getHolder();
    }

    @Override
    public ListIterator<ItemStack> iterator() {
        return iterator(0);
    }

    @Override
    public ListIterator<ItemStack> iterator(int index) {
        List<ItemStack> items = new ArrayList<>(Arrays.asList(getContents()));
        ListIterator<ItemStack> it = items.listIterator(index < 0 ? items.size() + index : index);
        return new ListIterator<>() {
            private int last = -1;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public ItemStack next() {
                last = it.nextIndex();
                return it.next();
            }

            @Override
            public boolean hasPrevious() {
                return it.hasPrevious();
            }

            @Override
            public ItemStack previous() {
                last = it.previousIndex();
                return it.previous();
            }

            @Override
            public int nextIndex() {
                return it.nextIndex();
            }

            @Override
            public int previousIndex() {
                return it.previousIndex();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Cannot remove from an inventory");
            }

            @Override
            public void set(ItemStack item) {
                it.set(item);
                setItem(last, item);
            }

            @Override
            public void add(ItemStack item) {
                throw new UnsupportedOperationException("Cannot add to an inventory");
            }
        };
    }

    @Override
    public Location getLocation() {
        return getHolder() instanceof org.bukkit.block.BlockState b ? b.getLocation()
                : getHolder() instanceof org.bukkit.entity.Entity e ? e.getLocation() : null;
    }
}
