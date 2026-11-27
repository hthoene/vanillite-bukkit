// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** An online player as a Bukkit {@link Player}. */
public abstract class VPlayer extends VEntity implements Player {
    static final MethodHandle NEW = Impl.constructor(VPlayer.class, VWorld.class, UUID.class, String.class);

    private final String name;

    protected VPlayer(VWorld world, UUID uuid, String name) {
        super(world, uuid);
        this.name = name;
    }

    static VPlayer create(VWorld world, UUID uuid, String name) {
        try {
            return (VPlayer) NEW.invokeExact(world, uuid, name);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    int worldId() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        if (Host.player_world(Native.plugin, s.uuid(uuid), out) == Abi.status.OK) {
            int id = out.get(JAVA_INT, 0);
            if (id != world.id) {
                VWorld w = Layer.server.world(id);
                if (w != null) {
                    world = w;
                }
            }
        }
        return world.id;
    }

    @Override
    public org.bukkit.World getWorld() {
        worldId();
        return world;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public Component displayName() {
        return Component.text(name);
    }

    @Override
    public EntityType getType() {
        return EntityType.PLAYER;
    }

    @Override
    public boolean isOnline() {
        return Layer.server.players.containsKey(uuid);
    }

    private void send(String text, int format) {
        var s = Native.scratch();
        Host.player_send_message(Native.plugin, s.uuid(uuid), s.str(text), format);
    }

    @Override
    public void sendMessage(String message) {
        if (message.indexOf('§') < 0) {
            send(message, Abi.text_format.PLAIN);
        } else {
            send(Texts.legacyJson(message), Abi.text_format.JSON);
        }
    }

    @Override
    public void sendMessage(String... messages) {
        for (String m : messages) {
            sendMessage(m);
        }
    }

    @Override
    public void sendMessage(Component message) {
        send(Texts.json(message), Abi.text_format.JSON);
    }

    @Override
    public boolean hasPermission(String node) {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        return Host.player_has_permission(Native.plugin, s.uuid(uuid), s.str(node), out) == Abi.status.OK
                && out.get(JAVA_INT, 0) != 0;
    }

    @Override
    public boolean isOp() {
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.PlayerInfo.SIZE);
        info.set(JAVA_INT, Abi.PlayerInfo.size, (int) Abi.PlayerInfo.SIZE);
        return Host.player_info(Native.plugin, s.uuid(uuid), info) == Abi.status.OK
                && info.get(JAVA_INT, Abi.PlayerInfo.op_level) >= 1;
    }

    @Override
    public void kickPlayer(String reason) {
        var s = Native.scratch();
        Host.player_kick(Native.plugin, s.uuid(uuid), s.str(reason == null ? "" : reason), Abi.text_format.PLAIN);
    }

    @Override
    public void kick(Component reason) {
        var s = Native.scratch();
        Host.player_kick(Native.plugin, s.uuid(uuid), s.str(Texts.json(reason)), Abi.text_format.JSON);
    }
}
