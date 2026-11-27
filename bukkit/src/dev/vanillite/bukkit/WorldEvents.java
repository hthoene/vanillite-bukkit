// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.abi.Abi;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import static dev.vanillite.bukkit.Events.location;
import static dev.vanillite.bukkit.Events.str;
import static dev.vanillite.bukkit.Events.uuid;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Native world, entity and menu events → Bukkit events: block place, interact, damage,
 * explosions, pistons, fluid flow, spawns, moves, fire, and the inventory events of menus.
 */
final class WorldEvents {
    private WorldEvents() {}

    private static final UUID NIL = new UUID(0, 0);

    /** The layer's Bukkit event classes (internal names) → native kinds, with these added. */
    @SafeVarargs
    static Map<String, Integer> withKinds(Map.Entry<String, Integer>... base) {
        Map<String, Integer> m = new HashMap<>();
        for (var e : base) {
            m.put(e.getKey(), e.getValue());
        }
        m.put("org/bukkit/event/block/BlockPlaceEvent", Abi.event.BLOCK_PLACE);
        m.put("org/bukkit/event/player/PlayerInteractEvent", Abi.event.PLAYER_INTERACT);
        m.put("org/bukkit/event/entity/EntityDamageEvent", Abi.event.ENTITY_DAMAGE);
        m.put("org/bukkit/event/entity/EntityDamageByEntityEvent", Abi.event.ENTITY_DAMAGE);
        m.put("org/bukkit/event/entity/EntityExplodeEvent", Abi.event.ENTITY_EXPLODE);
        m.put("org/bukkit/event/block/BlockExplodeEvent", Abi.event.ENTITY_EXPLODE);
        m.put("org/bukkit/event/block/BlockPistonExtendEvent", Abi.event.BLOCK_PISTON);
        m.put("org/bukkit/event/block/BlockPistonRetractEvent", Abi.event.BLOCK_PISTON);
        m.put("org/bukkit/event/block/BlockFromToEvent", Abi.event.BLOCK_FLUID_FLOW);
        m.put("org/bukkit/event/entity/CreatureSpawnEvent", Abi.event.ENTITY_SPAWN);
        m.put("org/bukkit/event/entity/EntitySpawnEvent", Abi.event.ENTITY_SPAWN);
        m.put("org/bukkit/event/player/PlayerMoveEvent", Abi.event.PLAYER_MOVE);
        m.put("org/bukkit/event/block/BlockIgniteEvent", Abi.event.BLOCK_IGNITE);
        m.put("org/bukkit/event/block/BlockBurnEvent", Abi.event.BLOCK_BURN);
        m.put("org/bukkit/event/inventory/InventoryOpenEvent", Abi.event.INVENTORY_OPEN);
        m.put("org/bukkit/event/inventory/InventoryClickEvent", Abi.event.INVENTORY_CLICK);
        m.put("org/bukkit/event/inventory/InventoryDragEvent", Abi.event.INVENTORY_DRAG);
        m.put("org/bukkit/event/inventory/InventoryCloseEvent", Abi.event.INVENTORY_CLOSE);
        return Map.copyOf(m);
    }

    /** Native kinds a listened kind needs as well (menus: open and close keep the views). */
    static void implied(java.util.Set<Integer> kinds) {
        if (kinds.contains(Abi.event.INVENTORY_CLICK) || kinds.contains(Abi.event.INVENTORY_DRAG)
                || kinds.contains(Abi.event.INVENTORY_OPEN) || kinds.contains(Abi.event.INVENTORY_CLOSE)) {
            kinds.add(Abi.event.INVENTORY_OPEN);
            kinds.add(Abi.event.INVENTORY_CLOSE);
            kinds.add(Abi.event.INVENTORY_CLICK);
        }
    }

    static Events.State create(int kind, MemorySegment header) {
        return switch (kind) {
            case Abi.event.BLOCK_PLACE -> Place.of(header.reinterpret(Abi.BlockEvent.SIZE));
            case Abi.event.PLAYER_INTERACT -> Interact.of(header.reinterpret(Abi.InteractEvent.SIZE));
            case Abi.event.ENTITY_DAMAGE -> Damage.of(header.reinterpret(Abi.EntityDamageEvent.SIZE));
            case Abi.event.ENTITY_EXPLODE -> Explode.of(game(header));
            case Abi.event.BLOCK_PISTON -> Piston.of(game(header));
            case Abi.event.BLOCK_FLUID_FLOW -> Simple.flow(game(header));
            case Abi.event.ENTITY_SPAWN -> Spawn.of(game(header));
            case Abi.event.PLAYER_MOVE -> Move.of(game(header));
            case Abi.event.BLOCK_IGNITE -> Simple.ignite(game(header));
            case Abi.event.BLOCK_BURN -> Simple.burn(game(header));
            case Abi.event.INVENTORY_OPEN -> MenuOpen.of(game(header));
            case Abi.event.INVENTORY_CLICK -> Click.of(game(header));
            case Abi.event.INVENTORY_DRAG -> Drag.of(game(header));
            case Abi.event.INVENTORY_CLOSE -> MenuClose.of(game(header));
            default -> null;
        };
    }

    private static MemorySegment game(MemorySegment header) {
        return header.reinterpret(Abi.GameEvent.SIZE);
    }

    // ----- Helpers

    private static VWorld world(MemorySegment e, long offset) {
        return Layer.server.world(e.get(JAVA_INT, offset));
    }

    private static int[] pos(MemorySegment e, long offset) {
        return new int[] {e.get(JAVA_INT, offset + Abi.BlockPos.x), e.get(JAVA_INT, offset + Abi.BlockPos.y),
            e.get(JAVA_INT, offset + Abi.BlockPos.z)};
    }

    private static Block block(VWorld w, int[] p) {
        return w.getBlockAt(p[0], p[1], p[2]);
    }

    /** A player online, else an entity of the world (typed when its type key is known). */
    static Entity entity(VWorld w, UUID id, String typeKey) {
        if (id == null || id.equals(NIL)) {
            return null;
        }
        VPlayer p = Layer.server.players.get(id);
        if (p != null) {
            return p;
        }
        if (w == null) {
            return null;
        }
        EntityType type = typeKey == null || typeKey.isEmpty() ? null : VEntity.typeOf(typeKey);
        if (type == null) {
            Entity plain = VEntity.create(w, id);
            try {
                type = plain.getType();
            } catch (RuntimeException ex) {
                return plain;
            }
        }
        return VEntity.create(w, id, type);
    }

    private static BlockFace face(int index) {
        return switch (index) {
            case 0 -> BlockFace.DOWN;
            case 1 -> BlockFace.UP;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.WEST;
            case 5 -> BlockFace.EAST;
            default -> BlockFace.SELF;
        };
    }

    /** The blocks of a position list {@code x,y,z;x,y,z}. */
    private static List<Block> blocks(VWorld w, String text) {
        List<Block> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String p : text.split(";")) {
            String[] c = p.split(",");
            if (c.length == 3) {
                try {
                    out.add(w.getBlockAt(Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim())));
                } catch (NumberFormatException e) {
                    // skipped
                }
            }
        }
        return out;
    }

    private static String positions(List<Block> blocks) {
        StringBuilder b = new StringBuilder();
        for (Block block : blocks) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(block.getX()).append(',').append(block.getY()).append(',').append(block.getZ());
        }
        return b.toString();
    }

    /** A stack of one item of a type key such as {@code minecraft:stone}; null for air or none. */
    private static ItemStack itemOf(String key) {
        NamespacedKey k = key == null || key.isEmpty() ? null : NamespacedKey.fromString(key);
        org.bukkit.Material m = k == null ? null : org.bukkit.Material.getMaterial(k.getKey().toUpperCase(java.util.Locale.ROOT));
        return m == null || m.isAir() || !m.isItem() ? null : VItemStack.of(m, 1);
    }

    /** A cancellable Bukkit event whose cancel state is the native one. */
    private abstract static class Cancellable extends Events.State {
        final Event event;

        Cancellable(MemorySegment header, Event event) {
            super(header);
            this.event = event;
            ((org.bukkit.event.Cancellable) event).setCancelled(cancelled());
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p != EventPriority.MONITOR) {
                cancel(((org.bukkit.event.Cancellable) event).isCancelled());
                after();
            }
        }

        /** Writes the event's other changes back. */
        void after() {}
    }

    // ----- Blocks

    private static final class Place extends Cancellable {
        Place(MemorySegment e, BlockPlaceEvent event) {
            super(e, event);
        }

        static Place of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.BlockEvent.player));
            VWorld w = world(e, Abi.BlockEvent.world);
            if (player == null || w == null || !Events.listens(BlockPlaceEvent.class)) {
                return null;
            }
            int[] p = pos(e, Abi.BlockEvent.pos);
            int placed = NativeAccess.stateId(str(e, Abi.BlockEvent.state));
            int now = NativeAccess.state(w, p[0], p[1], p[2]);
            Block block = VBlock.withState(w, p[0], p[1], p[2], Math.max(placed, 0));
            ItemStack item = itemOf(block.getType().getKey().toString());
            var event = new BlockPlaceEvent(block, VBlockState.create(w, p[0], p[1], p[2], Math.max(now, 0)),
                    block.getRelative(BlockFace.DOWN), item == null ? VItemStack.empty() : item, player, true, EquipmentSlot.HAND);
            return new Place(e, event);
        }

        @Override
        void after() {
            if (!((BlockPlaceEvent) event).canBuild()) {
                cancel(true);
            }
        }
    }

    private static final class Interact extends Events.State {
        private final PlayerInteractEvent event;
        private final boolean onBlock;

        private Interact(MemorySegment e, PlayerInteractEvent event, boolean onBlock) {
            super(e);
            this.event = event;
            this.onBlock = onBlock;
            if (cancelled()) {
                event.setCancelled(true);
            }
        }

        static Interact of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.InteractEvent.player));
            VWorld w = world(e, Abi.InteractEvent.world);
            if (player == null || w == null || !Events.listens(PlayerInteractEvent.class)) {
                return null;
            }
            int a = e.get(JAVA_INT, Abi.InteractEvent.action);
            boolean off = e.get(JAVA_INT, Abi.InteractEvent.hand) == Abi.hand.OFF;
            Action action = switch (a) {
                case Abi.interact.USE_ON_BLOCK -> Action.RIGHT_CLICK_BLOCK;
                case Abi.interact.USE_ITEM -> Action.RIGHT_CLICK_AIR;
                case Abi.interact.LEFT_CLICK_BLOCK -> Action.LEFT_CLICK_BLOCK;
                case Abi.interact.LEFT_CLICK_AIR -> Action.LEFT_CLICK_AIR;
                default -> Action.PHYSICAL;
            };
            boolean onBlock = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK || action == Action.PHYSICAL;
            Block block = onBlock ? block(w, pos(e, Abi.InteractEvent.pos)) : null;
            ItemStack item = action == Action.PHYSICAL ? null : itemOf(str(e, Abi.InteractEvent.item));
            BlockFace face = onBlock && action != Action.PHYSICAL ? face(e.get(JAVA_INT, Abi.InteractEvent.face)) : BlockFace.SELF;
            var event = new PlayerInteractEvent(player, action, item, block, face, off ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
            if (action != Action.PHYSICAL) {
                // The inventory is lent out to the interaction: its hand reads see the event's item.
                player.eventHand(off ? "weapon.offhand" : "weapon.mainhand", item);
            }
            return new Interact(e, event, onBlock);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p == EventPriority.MONITOR) {
                ((VPlayer) event.getPlayer()).eventHand(null, null);
                return;
            }
            boolean deny = event.useItemInHand() == Event.Result.DENY;
            if (onBlock) {
                deny |= event.useInteractedBlock() == Event.Result.DENY;
            }
            cancel(deny);
        }
    }

    private static final class Piston extends Cancellable {
        Piston(MemorySegment e, BlockPistonEvent event) {
            super(e, event);
        }

        static Piston of(MemorySegment e) {
            VWorld w = world(e, Abi.GameEvent.world);
            if (w == null) {
                return null;
            }
            Block piston = block(w, pos(e, Abi.GameEvent.pos));
            BlockFace dir = face((int) e.get(JAVA_LONG, Abi.GameEvent.value));
            List<Block> moved = blocks(w, str(e, Abi.GameEvent.text));
            boolean extend = e.get(JAVA_INT, Abi.GameEvent.cause) == 1;
            if (extend ? !Events.listens(BlockPistonExtendEvent.class) : !Events.listens(BlockPistonRetractEvent.class)) {
                return null;
            }
            return new Piston(e, extend ? new BlockPistonExtendEvent(piston, moved, dir) : new BlockPistonRetractEvent(piston, moved, dir));
        }
    }

    private static final class Simple extends Cancellable {
        Simple(MemorySegment e, Event event) {
            super(e, event);
        }

        static Simple flow(MemorySegment e) {
            VWorld w = world(e, Abi.GameEvent.world);
            if (w == null || !Events.listens(BlockFromToEvent.class)) {
                return null;
            }
            Location from = location(e, Abi.GameEvent.from);
            Block source = w.getBlockAt(from.getBlockX(), from.getBlockY(), from.getBlockZ());
            return new Simple(e, new BlockFromToEvent(source, block(w, pos(e, Abi.GameEvent.pos))));
        }

        static Simple ignite(MemorySegment e) {
            VWorld w = world(e, Abi.GameEvent.world);
            if (w == null || !Events.listens(BlockIgniteEvent.class)) {
                return null;
            }
            BlockIgniteEvent.IgniteCause cause = switch (e.get(JAVA_INT, Abi.GameEvent.cause)) {
                case Abi.ignite_cause.FLINT_AND_STEEL -> BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL;
                case Abi.ignite_cause.LAVA -> BlockIgniteEvent.IgniteCause.LAVA;
                case Abi.ignite_cause.LIGHTNING -> BlockIgniteEvent.IgniteCause.LIGHTNING;
                case Abi.ignite_cause.SPREAD -> BlockIgniteEvent.IgniteCause.SPREAD;
                case Abi.ignite_cause.FIREBALL -> BlockIgniteEvent.IgniteCause.FIREBALL;
                case Abi.ignite_cause.EXPLOSION -> BlockIgniteEvent.IgniteCause.EXPLOSION;
                case Abi.ignite_cause.ARROW -> BlockIgniteEvent.IgniteCause.ARROW;
                default -> BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL;
            };
            UUID who = uuid(e, Abi.GameEvent.player);
            Entity igniter = entity(w, who.equals(NIL) ? uuid(e, Abi.GameEvent.entity) : who, null);
            return new Simple(e, new BlockIgniteEvent(block(w, pos(e, Abi.GameEvent.pos)), cause, igniter));
        }

        static Simple burn(MemorySegment e) {
            VWorld w = world(e, Abi.GameEvent.world);
            if (w == null || !Events.listens(BlockBurnEvent.class)) {
                return null;
            }
            return new Simple(e, new BlockBurnEvent(block(w, pos(e, Abi.GameEvent.pos)), null));
        }
    }

    // ----- Explosions

    private static final class Explode extends Cancellable {
        private final List<Block> list;
        private final List<Block> original;

        Explode(MemorySegment e, Event event, List<Block> list) {
            super(e, event);
            this.list = list;
            this.original = List.copyOf(list);
        }

        static Explode of(MemorySegment e) {
            VWorld w = world(e, Abi.GameEvent.world);
            if (w == null) {
                return null;
            }
            float radius = (float) e.get(JAVA_DOUBLE, Abi.GameEvent.amount);
            List<Block> list = blocks(w, str(e, Abi.GameEvent.text));
            ExplosionResult result = (e.get(JAVA_INT, Abi.GameEvent.flags) & Abi.explode_flag.BREAK_BLOCKS) != 0
                    ? ExplosionResult.DESTROY : ExplosionResult.KEEP;
            float yield = radius > 0 ? 1.0f / radius : 1.0f;
            Entity source = entity(w, uuid(e, Abi.GameEvent.entity), str(e, Abi.GameEvent.entity_type));
            Event event;
            if (source != null) {
                if (!Events.listens(EntityExplodeEvent.class)) {
                    return null;
                }
                event = new EntityExplodeEvent(source, location(e, Abi.GameEvent.to), list, yield, result);
            } else {
                if (!Events.listens(BlockExplodeEvent.class)) {
                    return null;
                }
                int[] p = pos(e, Abi.GameEvent.pos);
                int state = NativeAccess.stateId(str(e, Abi.GameEvent.state));
                event = new BlockExplodeEvent(block(w, p), VBlockState.create(w, p[0], p[1], p[2], Math.max(state, 0)), list,
                        yield, result);
            }
            return new Explode(e, event, list);
        }

        @Override
        void after() {
            if (!list.equals(original)) {
                Events.setText(header.get(ADDRESS, Abi.GameEvent.text_out), positions(list), Abi.text_format.PLAIN);
            }
        }
    }

    // ----- Entities

    private static EntityDamageEvent.DamageCause cause(String damageType) {
        String t = damageType.substring(damageType.indexOf(':') + 1);
        String name = switch (t) {
            case "player_attack", "mob_attack", "mob_attack_no_aggro", "sting", "spear" -> "ENTITY_ATTACK";
            case "player_explosion", "explosion" -> "ENTITY_EXPLOSION";
            case "bad_respawn_point" -> "BLOCK_EXPLOSION";
            case "arrow", "trident", "mob_projectile", "spit", "thrown", "wind_charge", "fireball", "unattributed_fireball",
                 "wither_skull", "fireworks" -> "PROJECTILE";
            case "fall", "ender_pearl" -> "FALL";
            case "in_fire" -> "FIRE";
            case "on_fire" -> "FIRE_TICK";
            case "lava" -> "LAVA";
            case "hot_floor" -> "HOT_FLOOR";
            case "campfire" -> "CAMPFIRE";
            case "drown" -> "DROWNING";
            case "starve" -> "STARVATION";
            case "cactus", "sweet_berry_bush", "stalagmite" -> "CONTACT";
            case "in_wall" -> "SUFFOCATION";
            case "cramming" -> "CRAMMING";
            case "out_of_world" -> "VOID";
            case "generic_kill" -> "KILL";
            case "magic", "indirect_magic" -> "MAGIC";
            case "wither" -> "WITHER";
            case "dragon_breath" -> "DRAGON_BREATH";
            case "lightning_bolt" -> "LIGHTNING";
            case "freeze" -> "FREEZE";
            case "fly_into_wall" -> "FLY_INTO_WALL";
            case "falling_block", "falling_anvil", "falling_stalactite" -> "FALLING_BLOCK";
            case "thorns" -> "THORNS";
            case "sonic_boom" -> "SONIC_BOOM";
            case "dry_out" -> "DRYOUT";
            case "outside_border" -> "WORLD_BORDER";
            default -> "CUSTOM";
        };
        try {
            return EntityDamageEvent.DamageCause.valueOf(name);
        } catch (IllegalArgumentException e) {
            return EntityDamageEvent.DamageCause.CUSTOM;
        }
    }

    private static final class Damage extends Events.State {
        private final EntityDamageEvent event;
        private double damage;

        private Damage(MemorySegment e, EntityDamageEvent event) {
            super(e);
            this.event = event;
            this.damage = event.getDamage();
            event.setCancelled(cancelled());
        }

        static Damage of(MemorySegment e) {
            if (!Events.listens(EntityDamageEvent.class) && !Events.listens(EntityDamageByEntityEvent.class)) {
                return null;
            }
            VWorld w = world(e, Abi.EntityDamageEvent.world);
            Entity victim = entity(w, uuid(e, Abi.EntityDamageEvent.entity), str(e, Abi.EntityDamageEvent.entity_type));
            if (victim == null) {
                return null;
            }
            String typeKey = str(e, Abi.EntityDamageEvent.damage_type);
            double amount = e.get(JAVA_FLOAT, Abi.EntityDamageEvent.amount);
            UUID attackerId = uuid(e, Abi.EntityDamageEvent.attacker);
            UUID directId = uuid(e, Abi.EntityDamageEvent.direct);
            Entity attacker = entity(w, attackerId, null);
            Entity direct = directId.equals(attackerId) ? attacker : entity(w, directId, null);
            NamespacedKey k = NamespacedKey.fromString(typeKey);
            DamageType type = k == null ? null : Registry.DAMAGE_TYPE.get(k);
            DamageSource.Builder source = DamageSource.builder(type != null ? type : DamageType.GENERIC);
            if (attacker != null) {
                source.withCausingEntity(attacker);
            }
            if (direct != null) {
                source.withDirectEntity(direct);
            }
            EntityDamageEvent.DamageCause cause = cause(typeKey);
            // The damager: the player behind a projectile when there is one (protection plugins
            // look for players), else the entity that hit.
            Entity damager = attacker instanceof VPlayer ? attacker : direct != null ? direct : attacker;
            @SuppressWarnings("removal")
            EntityDamageEvent event = damager != null
                    ? new EntityDamageByEntityEvent(damager, victim, cause, source.build(), amount)
                    : new EntityDamageEvent(victim, cause, source.build(), amount);
            return new Damage(e, event);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p == EventPriority.MONITOR) {
                return;
            }
            cancel(event.isCancelled());
            if (event.getDamage() != damage) {
                damage = event.getDamage();
                header.set(JAVA_FLOAT, Abi.EntityDamageEvent.amount, (float) damage);
            }
        }
    }

    private static CreatureSpawnEvent.SpawnReason reason(int r) {
        String name = switch (r) {
            case Abi.spawn_reason.NATURAL -> "NATURAL";
            case Abi.spawn_reason.CHUNK_GENERATION -> "CHUNK_GEN";
            case Abi.spawn_reason.SPAWNER -> "SPAWNER";
            case Abi.spawn_reason.BREEDING -> "BREEDING";
            case Abi.spawn_reason.JOCKEY -> "JOCKEY";
            case Abi.spawn_reason.EVENT -> "RAID";
            case Abi.spawn_reason.REINFORCEMENT -> "REINFORCEMENTS";
            case Abi.spawn_reason.TRIGGERED -> "TRAP";
            case Abi.spawn_reason.BUCKET -> "BUCKET";
            case Abi.spawn_reason.SPAWN_ITEM_USE -> "SPAWNER_EGG";
            case Abi.spawn_reason.COMMAND -> "COMMAND";
            case Abi.spawn_reason.DISPENSER -> "DISPENSE_EGG";
            case Abi.spawn_reason.PATROL -> "PATROL";
            case Abi.spawn_reason.TRIAL_SPAWNER -> "TRIAL_SPAWNER";
            case Abi.spawn_reason.PLUGIN -> "CUSTOM";
            default -> "DEFAULT";
        };
        try {
            return CreatureSpawnEvent.SpawnReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return CreatureSpawnEvent.SpawnReason.DEFAULT;
        }
    }

    private static final class Spawn extends Cancellable {
        Spawn(MemorySegment e, Event event) {
            super(e, event);
        }

        static Spawn of(MemorySegment e) {
            VWorld w = world(e, Abi.GameEvent.world);
            if (w == null) {
                return null;
            }
            EntityType type = VEntity.typeOf(str(e, Abi.GameEvent.entity_type));
            Location at = location(e, Abi.GameEvent.to);
            if (at.getWorld() == null) {
                at.setWorld(w);
            }
            Entity entity = VEntity.pending(w, uuid(e, Abi.GameEvent.entity), type, at);
            if (entity instanceof LivingEntity living) {
                if (!Events.listens(CreatureSpawnEvent.class) && !Events.listens(EntitySpawnEvent.class)) {
                    return null;
                }
                return new Spawn(e, new CreatureSpawnEvent(living, reason(e.get(JAVA_INT, Abi.GameEvent.cause))));
            }
            return Events.listens(EntitySpawnEvent.class) ? new Spawn(e, new EntitySpawnEvent(entity)) : null;
        }
    }

    private static final class Move extends Events.State {
        private final PlayerMoveEvent event;
        private Location to;

        private Move(MemorySegment e, VPlayer player) {
            super(e);
            to = location(e, Abi.GameEvent.to);
            player.cacheLocation(to);
            event = new PlayerMoveEvent(player, location(e, Abi.GameEvent.from), to.clone());
            event.setCancelled(cancelled());
        }

        static Move of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.GameEvent.entity));
            return player == null || !Events.listens(PlayerMoveEvent.class) ? null : new Move(e, player);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p == EventPriority.MONITOR) {
                return;
            }
            cancel(event.isCancelled());
            if (!event.getTo().equals(to)) {
                to = event.getTo().clone();
                Events.writeLocation(header, Abi.GameEvent.to, to);
            }
        }
    }

    // ----- Menus

    /** The view of a player's menu event: the plugin's when it opened it, else the server menu's. */
    private static VInventoryView view(VPlayer player, MemorySegment e) {
        String key = str(e, Abi.GameEvent.key);
        boolean plugin = (e.get(JAVA_INT, Abi.GameEvent.flags) & Abi.menu_flag.PLUGIN) != 0;
        if (key.equals("minecraft:inventory")) {
            return (VInventoryView) player.getOpenInventory();
        }
        if (plugin && player.pendingView != null) {
            return player.pendingView;
        }
        if (player.openView != null) {
            return player.openView;
        }
        Component title = Events.fromJson(str(e, Abi.GameEvent.text));
        return VInventoryView.create(player, VMenuInventory.create(player, key), title);
    }

    private abstract static class Menu extends Events.State {
        final Event event;

        Menu(MemorySegment header, Event event) {
            super(header);
            this.event = event;
            if (event instanceof org.bukkit.event.Cancellable c) {
                c.setCancelled(cancelled());
            }
        }

        @Override
        void stage(EventPriority p) {
            boolean was = Events.inMenuEvent;
            Events.inMenuEvent = true;
            try {
                Layer.server.pluginManager.call(event, p);
            } finally {
                Events.inMenuEvent = was;
            }
            if (p != EventPriority.MONITOR && event instanceof org.bukkit.event.Cancellable c) {
                cancel(c.isCancelled());
            }
        }
    }

    private static final class MenuOpen extends Menu {
        private final VPlayer player;
        private final VInventoryView view;

        MenuOpen(MemorySegment e, VPlayer player, VInventoryView view) {
            super(e, new InventoryOpenEvent(view));
            this.player = player;
            this.view = view;
        }

        static MenuOpen of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.GameEvent.entity));
            if (player == null) {
                return null;
            }
            return new MenuOpen(e, player, view(player, e));
        }

        @Override
        void done() {
            if (cancelled()) {
                return;
            }
            if (view.top instanceof VCustomInventory c && player.pendingView == view) {
                return;
            }
            player.openView = view;
        }
    }

    private static final class MenuClose extends Menu {
        private final VPlayer player;
        private final VInventoryView view;

        MenuClose(MemorySegment e, VPlayer player, VInventoryView view) {
            super(e, new InventoryCloseEvent(view));
            this.player = player;
            this.view = view;
        }

        static MenuClose of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.GameEvent.entity));
            return player == null ? null : new MenuClose(e, player, view(player, e));
        }

        @Override
        void done() {
            if (player.openView == view) {
                player.menuClosed();
            }
        }
    }

    private static ClickType clickType(int click, int button, int slot) {
        return switch (click) {
            case Abi.click.QUICK_MOVE -> button == 1 ? ClickType.SHIFT_RIGHT : ClickType.SHIFT_LEFT;
            case Abi.click.SWAP -> button == 40 ? ClickType.SWAP_OFFHAND : ClickType.NUMBER_KEY;
            case Abi.click.CLONE -> ClickType.MIDDLE;
            case Abi.click.THROW -> slot == -999 ? (button == 1 ? ClickType.RIGHT : ClickType.LEFT)
                    : button == 1 ? ClickType.CONTROL_DROP : ClickType.DROP;
            case Abi.click.PICKUP_ALL -> ClickType.DOUBLE_CLICK;
            default -> slot == -999 ? (button == 1 ? ClickType.WINDOW_BORDER_RIGHT : ClickType.WINDOW_BORDER_LEFT)
                    : button == 1 ? ClickType.RIGHT : ClickType.LEFT;
        };
    }

    private static InventoryAction action(ClickType type, ItemStack current, ItemStack cursor, int slot) {
        boolean hasCurrent = current != null && !current.isEmpty();
        boolean hasCursor = cursor != null && !cursor.isEmpty();
        return switch (type) {
            case SHIFT_LEFT, SHIFT_RIGHT -> hasCurrent ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.NOTHING;
            case NUMBER_KEY, SWAP_OFFHAND -> InventoryAction.HOTBAR_SWAP;
            case MIDDLE -> hasCurrent ? InventoryAction.CLONE_STACK : InventoryAction.NOTHING;
            case DROP -> hasCurrent ? InventoryAction.DROP_ONE_SLOT : InventoryAction.NOTHING;
            case CONTROL_DROP -> hasCurrent ? InventoryAction.DROP_ALL_SLOT : InventoryAction.NOTHING;
            case DOUBLE_CLICK -> InventoryAction.COLLECT_TO_CURSOR;
            case WINDOW_BORDER_LEFT -> hasCursor ? InventoryAction.DROP_ALL_CURSOR : InventoryAction.NOTHING;
            case WINDOW_BORDER_RIGHT -> hasCursor ? InventoryAction.DROP_ONE_CURSOR : InventoryAction.NOTHING;
            case RIGHT -> !hasCursor ? (hasCurrent ? InventoryAction.PICKUP_HALF : InventoryAction.NOTHING)
                    : !hasCurrent || current.isSimilar(cursor) ? InventoryAction.PLACE_ONE : InventoryAction.SWAP_WITH_CURSOR;
            default -> !hasCursor ? (hasCurrent ? InventoryAction.PICKUP_ALL : InventoryAction.NOTHING)
                    : !hasCurrent || current.isSimilar(cursor) ? InventoryAction.PLACE_ALL : InventoryAction.SWAP_WITH_CURSOR;
        };
    }

    private static final class Click extends Menu {
        private final VPlayer player;
        private final VInventoryView view;

        Click(MemorySegment e, VPlayer player, VInventoryView view, InventoryClickEvent event) {
            super(e, event);
            this.player = player;
            this.view = view;
        }

        static Click of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.GameEvent.entity));
            if (player == null) {
                return null;
            }
            VInventoryView view = view(player, e);
            int slot = (int) e.get(JAVA_LONG, Abi.GameEvent.value);
            int button = (int) e.get(JAVA_LONG, Abi.GameEvent.extra);
            ClickType type = clickType(e.get(JAVA_INT, Abi.GameEvent.cause), button, slot);
            ItemStack cursor = VItemStack.fromSnbt(str(e, Abi.GameEvent.other_item));
            view.cursor(cursor);
            ItemStack current = slot < 0 ? null : VItemStack.fromSnbt(str(e, Abi.GameEvent.item));
            InventoryType.SlotType slotType = view.getSlotType(slot);
            InventoryAction action = action(type, current, cursor, slot);
            InventoryClickEvent event = type == ClickType.NUMBER_KEY
                    ? new InventoryClickEvent(view, slotType, slot, type, action, button)
                    : new InventoryClickEvent(view, slotType, slot, type, action);
            if (slot < 0) {
                event.setCurrentItem(current);
            } else if (!(view.top instanceof VCustomInventory) || slot >= view.top.getSize()) {
                view.eventItem(slot, current);
            }
            return new Click(e, player, view, event);
        }

        @Override
        void done() {
            if (!cancelled() && view.top instanceof VCustomInventory custom && player.openView == view) {
                Layer.server.scheduler.onWorld(player.worldId(), () -> {
                    if (player.openView == view) {
                        custom.resync(player);
                    }
                });
            }
        }
    }

    private static final class Drag extends Menu {
        Drag(MemorySegment e, InventoryDragEvent event) {
            super(e, event);
        }

        static Drag of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.GameEvent.entity));
            if (player == null || !Events.listens(InventoryDragEvent.class)) {
                return null;
            }
            VInventoryView view = view(player, e);
            ItemStack cursor = VItemStack.fromSnbt(str(e, Abi.GameEvent.other_item));
            Map<Integer, ItemStack> slots = new LinkedHashMap<>();
            String text = str(e, Abi.GameEvent.text).replaceAll("[\\[\\]\\s]", "");
            String[] parts = text.isEmpty() ? new String[0] : text.split(",");
            int type = (int) e.get(JAVA_LONG, Abi.GameEvent.extra);
            int each = parts.length == 0 ? 0 : type == 1 ? 1 : type == 2 ? cursor.getMaxStackSize() : Math.max(1, cursor.getAmount() / parts.length);
            for (String p : parts) {
                try {
                    ItemStack s = cursor.clone();
                    s.setAmount(each);
                    slots.put(Integer.parseInt(p), s);
                } catch (NumberFormatException ex) {
                    // skipped
                }
            }
            ItemStack left = cursor.clone();
            left.setAmount(Math.max(0, cursor.getAmount() - (type == 2 ? 0 : each * slots.size())));
            return new Drag(e, new InventoryDragEvent(view, left.isEmpty() ? null : left, cursor, type == 1, slots));
        }
    }
}
