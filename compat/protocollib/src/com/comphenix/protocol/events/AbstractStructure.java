// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import com.comphenix.protocol.reflect.EquivalentConverter;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import dev.vanillite.bukkit.compat.protocollib.Kind;
import dev.vanillite.bukkit.compat.protocollib.Packets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Typed access to the fields of a packet. On Vanillite the fields are those Vanillite maps for the
 * packet type (see {@code Codecs}); an accessor for a type the packet has no field of is empty,
 * and reading from it fails with a {@link com.comphenix.protocol.reflect.FieldAccessException}
 * that names the packet.
 */
public abstract class AbstractStructure {
    protected transient Object handle;
    protected transient StructureModifier<Object> structureModifier;

    protected AbstractStructure() {}

    protected AbstractStructure(Object handle, StructureModifier<Object> structureModifier) {
        this.handle = handle;
        this.structureModifier = structureModifier;
    }

    /** The decoded fields. */
    abstract StructureModifier.Fields fields();

    /** The slots of one kind. */
    abstract int[] slots(Kind kind);

    private <T> StructureModifier<T> of(Kind kind) {
        return new StructureModifier<>(fields(), slots(kind), kind.type, null);
    }

    private <T> StructureModifier<T> none(Class<?> type) {
        return new StructureModifier<>(fields(), new int[0], type, null);
    }

    public Object getHandle() {
        return this;
    }

    public StructureModifier<Object> getModifier() {
        return new StructureModifier<>(fields(), fields().slots(null), Object.class, null);
    }

    public <T> StructureModifier<T> getSpecificModifier(Class<T> type) {
        return getModifier().withType(type);
    }

    public StructureModifier<Byte> getBytes() {
        return of(Kind.BYTE);
    }

    public StructureModifier<Boolean> getBooleans() {
        return of(Kind.BOOLEAN);
    }

    public StructureModifier<Short> getShorts() {
        return of(Kind.SHORT);
    }

    public StructureModifier<Integer> getIntegers() {
        return of(Kind.INT);
    }

    public StructureModifier<Long> getLongs() {
        return of(Kind.LONG);
    }

    public StructureModifier<Float> getFloat() {
        return of(Kind.FLOAT);
    }

    public StructureModifier<Double> getDoubles() {
        return of(Kind.DOUBLE);
    }

    public StructureModifier<String> getStrings() {
        return of(Kind.STRING);
    }

    public StructureModifier<UUID> getUUIDs() {
        return of(Kind.UUID);
    }

    public StructureModifier<String[]> getStringArrays() {
        return none(String[].class);
    }

    public StructureModifier<byte[]> getByteArrays() {
        return none(byte[].class);
    }

    public StructureModifier<int[]> getIntegerArrays() {
        return none(int[].class);
    }

    public StructureModifier<short[]> getShortArrays() {
        return none(short[].class);
    }

    public StructureModifier<ItemStack> getItemModifier() {
        return none(ItemStack.class);
    }

    public StructureModifier<ItemStack[]> getItemArrayModifier() {
        return none(ItemStack[].class);
    }

    public StructureModifier<List<ItemStack>> getItemListModifier() {
        return none(List.class);
    }

    public StructureModifier<Entity> getEntityModifier(World world) {
        return new StructureModifier<>(fields(), slots(Kind.INT), Entity.class, new EquivalentConverter<Entity>() {
            @Override
            public Object getGeneric(Entity specific) {
                return specific.getEntityId();
            }

            @Override
            public Entity getSpecific(Object generic) {
                return Packets.entity(world, (Integer) generic);
            }

            @Override
            public Class<Entity> getSpecificType() {
                return Entity.class;
            }
        });
    }

    public StructureModifier<Entity> getEntityModifier(PacketEvent event) {
        return getEntityModifier(event.getPlayer().getWorld());
    }

    public StructureModifier<EntityType> getEntityTypeModifier() {
        return of(Kind.ENTITY_TYPE);
    }

    public StructureModifier<BlockPosition> getBlockPositionModifier() {
        return of(Kind.BLOCK_POS);
    }

    public StructureModifier<Vector> getVectors() {
        return of(Kind.VECTOR);
    }

    public StructureModifier<List<BlockPosition>> getBlockPositionCollectionModifier() {
        return none(List.class);
    }

    public StructureModifier<List<WrappedDataValue>> getDataValueCollectionModifier() {
        return of(Kind.DATA_VALUES);
    }

    public StructureModifier<Material> getBlocks() {
        return none(Material.class);
    }

    public StructureModifier<WrappedGameProfile> getGameProfiles() {
        return none(WrappedGameProfile.class);
    }

    public StructureModifier<WrappedBlockData> getBlockData() {
        return of(Kind.BLOCK_DATA);
    }

    public StructureModifier<WrappedChatComponent> getChatComponents() {
        return of(Kind.COMPONENT);
    }

    public StructureModifier<WrappedChatComponent[]> getChatComponentArrays() {
        return none(WrappedChatComponent[].class);
    }

    /**
     * As on ProtocolLib, the entries of {@code PLAYER_INFO} are at index 1: the accessor counts the
     * packet's collection fields, and the action set comes first.
     */
    public StructureModifier<List<PlayerInfoData>> getPlayerInfoDataLists() {
        int[] actions = slots(Kind.INFO_ACTIONS);
        int[] entries = slots(Kind.INFO_DATA);
        int[] both = java.util.Arrays.copyOf(actions, actions.length + entries.length);
        System.arraycopy(entries, 0, both, actions.length, entries.length);
        java.util.Arrays.sort(both);
        return new StructureModifier<>(fields(), both, List.class, null);
    }

    public StructureModifier<EnumWrappers.NativeGameMode> getGameModes() {
        return none(EnumWrappers.NativeGameMode.class);
    }

    public StructureModifier<EnumWrappers.PlayerInfoAction> getPlayerInfoAction() {
        return none(EnumWrappers.PlayerInfoAction.class);
    }

    public StructureModifier<Set<EnumWrappers.PlayerInfoAction>> getPlayerInfoActions() {
        return of(Kind.INFO_ACTIONS);
    }

    public StructureModifier<EnumWrappers.TitleAction> getTitleActions() {
        return none(EnumWrappers.TitleAction.class);
    }

    public StructureModifier<EnumWrappers.SoundCategory> getSoundCategories() {
        return of(Kind.SOUND_CATEGORY);
    }

    public StructureModifier<Sound> getSoundEffects() {
        return of(Kind.SOUND);
    }

    public StructureModifier<EnumWrappers.Hand> getHands() {
        return of(Kind.HAND);
    }

    public StructureModifier<EnumWrappers.Direction> getDirections() {
        return none(EnumWrappers.Direction.class);
    }

    public StructureModifier<EnumWrappers.ChatType> getChatTypes() {
        return none(EnumWrappers.ChatType.class);
    }

    public StructureModifier<MovingObjectPositionBlock> getMovingBlockPositions() {
        return of(Kind.MOVING_BLOCK);
    }

    public StructureModifier<List<Integer>> getIntLists() {
        return of(Kind.INT_LIST);
    }

    public StructureModifier<List<UUID>> getUUIDLists() {
        return of(Kind.UUID_LIST);
    }

    public StructureModifier<Instant> getInstants() {
        return of(Kind.INSTANT);
    }

    public <T extends Enum<T>> StructureModifier<T> getEnumModifier(Class<T> enumClass, Class<?> nmsClass) {
        return getModifier().withType(enumClass);
    }

    public <T extends Enum<T>> StructureModifier<T> getEnumModifier(Class<T> enumClass, int index) {
        return getModifier().withType(enumClass);
    }
}
