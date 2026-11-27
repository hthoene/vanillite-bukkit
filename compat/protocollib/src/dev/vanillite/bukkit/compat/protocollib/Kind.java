// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

/** The type of a decoded packet field, as ProtocolLib's accessors group them. */
public enum Kind {
    BYTE(Byte.class),
    BOOLEAN(Boolean.class),
    SHORT(Short.class),
    INT(Integer.class),
    LONG(Long.class),
    FLOAT(Float.class),
    DOUBLE(Double.class),
    STRING(String.class),
    UUID(UUID.class),
    COMPONENT(WrappedChatComponent.class),
    BLOCK_POS(BlockPosition.class),
    BLOCK_DATA(WrappedBlockData.class),
    ENTITY_TYPE(EntityType.class),
    VECTOR(Vector.class),
    DATA_VALUES(List.class),
    INT_LIST(List.class),
    UUID_LIST(List.class),
    SOUND(Sound.class),
    SOUND_CATEGORY(EnumWrappers.SoundCategory.class),
    INFO_ACTIONS(Set.class),
    INFO_DATA(List.class),
    INSTANT(Instant.class),
    HAND(EnumWrappers.Hand.class),
    MOVING_BLOCK(MovingObjectPositionBlock.class);

    /** The type plugins see. */
    public final Class<?> type;

    Kind(Class<?> type) {
        this.type = type;
    }

    /** Whether a field of this kind matches a requested type ({@code withType}). */
    public boolean matches(Class<?> requested) {
        if (requested == null || requested == Object.class) {
            return true;
        }
        Class<?> boxed = switch (requested.getName()) {
            case "byte" -> Byte.class;
            case "boolean" -> Boolean.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            default -> requested;
        };
        return boxed.isAssignableFrom(type) || type.isAssignableFrom(boxed);
    }
}
