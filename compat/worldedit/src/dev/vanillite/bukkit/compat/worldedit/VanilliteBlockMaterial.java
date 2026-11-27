// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.worldedit;

import com.sk89q.worldedit.world.registry.BlockMaterial;

/** A block type's material facts from the server ({@code block_type_info}). */
record VanilliteBlockMaterial(int flags, float hardness, float resistance, float friction) implements BlockMaterial {
    private static final int AIR = 1;
    private static final int SOLID = 2;
    private static final int OCCLUDING = 4;
    private static final int FLAMMABLE = 8;
    private static final int BURNABLE = 16;
    private static final int BLOCK_ENTITY = 64;
    private static final int REPLACEABLE = 128;
    private static final int LIQUID = 256;

    private boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public boolean isAir() {
        return has(AIR);
    }

    @Override
    public boolean isFullCube() {
        return has(OCCLUDING);
    }

    @Override
    public boolean isOpaque() {
        return has(OCCLUDING);
    }

    @Override
    public boolean isPowerSource() {
        return false;
    }

    @Override
    public boolean isLiquid() {
        return has(LIQUID);
    }

    @Override
    public boolean isSolid() {
        return has(SOLID);
    }

    @Override
    public float getHardness() {
        return hardness;
    }

    @Override
    public float getResistance() {
        return resistance;
    }

    @Override
    public float getSlipperiness() {
        return friction;
    }

    @Override
    public int getLightValue() {
        return 0;
    }

    @Override
    public boolean isFragileWhenPushed() {
        return !has(SOLID) && !has(AIR);
    }

    @Override
    public boolean isUnpushable() {
        return hardness < 0 || has(BLOCK_ENTITY);
    }

    @Override
    public boolean isTicksRandomly() {
        return false;
    }

    @Override
    public boolean isMovementBlocker() {
        return has(SOLID);
    }

    @Override
    public boolean isBurnable() {
        return has(BURNABLE) || has(FLAMMABLE);
    }

    @Override
    public boolean isToolRequired() {
        return false;
    }

    @Override
    public boolean isReplacedDuringPlacement() {
        return has(REPLACEABLE);
    }

    @Override
    public boolean isTranslucent() {
        return !has(OCCLUDING);
    }

    @Override
    public boolean hasContainer() {
        return has(BLOCK_ENTITY);
    }
}
