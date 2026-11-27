// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import org.bukkit.util.Vector;

/** Where a player's use of an item hit a block. */
public class MovingObjectPositionBlock implements Cloneable {
    private BlockPosition position;
    private Vector posVector;
    private EnumWrappers.Direction direction;
    private boolean insideBlock;
    private boolean worldBorderHit;

    public MovingObjectPositionBlock() {}

    public MovingObjectPositionBlock(BlockPosition position, Vector posVector, EnumWrappers.Direction direction,
            boolean insideBlock) {
        this.position = position;
        this.posVector = posVector;
        this.direction = direction;
        this.insideBlock = insideBlock;
    }

    public BlockPosition getBlockPosition() {
        return position;
    }

    public void setBlockPosition(BlockPosition position) {
        this.position = position;
    }

    public Vector getPosVector() {
        return posVector;
    }

    public void setPosVector(Vector posVector) {
        this.posVector = posVector;
    }

    public EnumWrappers.Direction getDirection() {
        return direction;
    }

    public void setDirection(EnumWrappers.Direction direction) {
        this.direction = direction;
    }

    public boolean isInsideBlock() {
        return insideBlock;
    }

    public void setInsideBlock(boolean insideBlock) {
        this.insideBlock = insideBlock;
    }

    /** Whether the hit was on the world border (Vanillite extension; Vanilla's field). */
    public boolean isWorldBorderHit() {
        return worldBorderHit;
    }

    public void setWorldBorderHit(boolean worldBorderHit) {
        this.worldBorderHit = worldBorderHit;
    }

    @Override
    public MovingObjectPositionBlock clone() {
        try {
            return (MovingObjectPositionBlock) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
}
