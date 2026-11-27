// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import dev.vanillite.bukkit.NativeAccess;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** A block state; on Vanillite its handle is the numeric block state id. */
public abstract class WrappedBlockData extends AbstractWrapper implements ClonableWrapper {
    public WrappedBlockData(Object handle) {
        super(Integer.class);
        this.handle = handle;
    }

    public abstract Material getType();

    public abstract int getData();

    public abstract void setType(Material type);

    public abstract void setData(int data);

    public abstract void setTypeAndData(Material type, int data);

    @Override
    public abstract WrappedBlockData deepClone();

    /** The block state id. */
    public int getStateId() {
        return (Integer) handle;
    }

    /** The block state as Bukkit block data. */
    public BlockData toBukkit() {
        return Bukkit.createBlockData(NativeAccess.stateName(getStateId()));
    }

    public static WrappedBlockData createData(Material type) {
        return fromStateId(defaultState(type));
    }

    public static WrappedBlockData createData(Material type, int data) {
        return createData(type);
    }

    public static WrappedBlockData fromHandle(Object handle) {
        return createData(handle);
    }

    public static WrappedBlockData createData(Object data) {
        if (data instanceof Integer id) {
            return fromStateId(id);
        }
        if (data instanceof BlockData b) {
            return fromStateId(NativeAccess.stateId(b.getAsString()));
        }
        if (data instanceof Material m) {
            return createData(m);
        }
        if (data instanceof WrappedBlockData w) {
            return w.deepClone();
        }
        throw new IllegalArgumentException("Vanillite: not a block state: " + data);
    }

    /** The block state with id {@code state}. */
    public static WrappedBlockData fromStateId(int state) {
        return new State(state);
    }

    private static int defaultState(Material type) {
        int id = NativeAccess.stateId(type.getKey().toString());
        if (id < 0) {
            throw new IllegalArgumentException(type + " is not a block");
        }
        return id;
    }

    @Override
    public String toString() {
        return "WrappedBlockData[" + NativeAccess.stateName(getStateId()) + "]";
    }

    @Override
    public int hashCode() {
        return getStateId();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedBlockData w && w.getStateId() == getStateId();
    }

    private static final class State extends WrappedBlockData {
        State(int id) {
            super(id);
        }

        @Override
        public Material getType() {
            String name = NativeAccess.stateName(getStateId());
            if (name == null) {
                return Material.AIR;
            }
            int bracket = name.indexOf('[');
            Material m = Material.matchMaterial(bracket < 0 ? name : name.substring(0, bracket));
            return m == null ? Material.AIR : m;
        }

        @Override
        public int getData() {
            return 0;
        }

        @Override
        public void setType(Material type) {
            handle = defaultState(type);
        }

        @Override
        public void setData(int data) {}

        @Override
        public void setTypeAndData(Material type, int data) {
            setType(type);
        }

        @Override
        public WrappedBlockData deepClone() {
            return new State(getStateId());
        }
    }
}
