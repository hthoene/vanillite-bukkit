// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.worldedit;

import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockState;
import dev.vanillite.bukkit.NativeAccess;
import org.bukkit.World;
import org.enginehub.linbus.format.snbt.LinStringIO;
import org.enginehub.linbus.tree.LinCompoundTag;

/**
 * WorldEdit's block writes into a Vanillite world by state id. The server applies Vanilla's
 * updates itself when a write has physics (WorldEdit's {@code neighbors} or {@code update}
 * side effect), and always lights and sends the block; the notify steps are therefore empty.
 */
final class VanilliteWorldAccess implements WorldNativeAccess<World, Integer, BlockVector3> {
    private final VanilliteAdapter adapter;
    private final World world;
    private SideEffectSet sideEffects = SideEffectSet.defaults();

    VanilliteWorldAccess(VanilliteAdapter adapter, World world) {
        this.adapter = adapter;
        this.world = world;
    }

    @Override
    public void setCurrentSideEffectSet(SideEffectSet sideEffectSet) {
        sideEffects = sideEffectSet;
    }

    @Override
    public World getChunk(int x, int z) {
        return world;
    }

    @Override
    public Integer toNative(BlockState state) {
        return adapter.id(state);
    }

    @Override
    public Integer getBlockState(World chunk, BlockVector3 position) {
        return NativeAccess.state(world, position.x(), position.y(), position.z());
    }

    @Override
    public Integer setBlockState(World chunk, BlockVector3 position, Integer state) {
        int old = NativeAccess.state(world, position.x(), position.y(), position.z());
        if (old == state || old < 0) {
            return null;
        }
        boolean physics = sideEffects.shouldApply(SideEffect.NEIGHBORS) || sideEffects.shouldApply(SideEffect.UPDATE);
        return NativeAccess.setState(world, position.x(), position.y(), position.z(), state, physics) ? old : null;
    }

    @Override
    public Integer getValidBlockForPosition(Integer block, BlockVector3 position) {
        return block;
    }

    @Override
    public BlockVector3 getPosition(int x, int y, int z) {
        return BlockVector3.at(x, y, z);
    }

    @Override
    public void updateLightingForBlock(BlockVector3 position) {
        // The server lights every changed block.
    }

    @Override
    public boolean updateTileEntity(BlockVector3 position, LinCompoundTag tag) {
        return NativeAccess.mergeBlockEntity(world, position.x(), position.y(), position.z(), LinStringIO.writeToString(tag));
    }

    @Override
    public void notifyBlockUpdate(World chunk, BlockVector3 position, Integer oldState, Integer newState) {
        // Sent by the server with the change.
    }

    @Override
    public boolean isChunkTicking(World chunk) {
        return true;
    }

    @Override
    public void markBlockChanged(World chunk, BlockVector3 position) {
        // Sent by the server with the change.
    }

    @Override
    public void notifyNeighbors(BlockVector3 pos, Integer oldState, Integer newState) {
        // Done by the server for writes with physics.
    }

    @Override
    public void updateNeighbors(BlockVector3 pos, Integer oldState, Integer newState, int recursionLimit) {
        // Done by the server for writes with physics.
    }

    @Override
    public void onBlockStateChange(BlockVector3 pos, Integer oldState, Integer newState) {
        // Points of interest follow the server's block changes.
    }
}
