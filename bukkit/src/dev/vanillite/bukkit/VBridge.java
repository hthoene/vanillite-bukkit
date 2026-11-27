// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** The API's service for server internals (found through {@link java.util.ServiceLoader}). */
public abstract class VBridge implements InternalAPIBridge {
    public VBridge() {}

    @Override
    public LifecycleEventManager<Plugin> createPluginLifecycleEventManager(JavaPlugin plugin, BooleanSupplier valid) {
        return new LifecycleEventManager<>() {
            @Override
            public void registerEventHandler(LifecycleEventHandlerConfiguration<? super Plugin> config) {
                // Lifecycle events (Brigadier command registration, ...) are not supported yet.
            }
        };
    }

    @Override
    public DamageSource.Builder createDamageSourceBuilder(DamageType type) {
        return new DamageSource.Builder() {
            private Entity causing;
            private Entity direct;
            private Location location;
            private Pointers context = Pointers.empty();

            @Override
            public DamageSource.Builder withCausingEntity(Entity entity) {
                causing = entity;
                return this;
            }

            @Override
            public DamageSource.Builder withDirectEntity(Entity entity) {
                direct = entity;
                return this;
            }

            @Override
            public DamageSource.Builder withDamageLocation(Location at) {
                location = at == null ? null : at.clone();
                return this;
            }

            @Override
            public DamageSource.Builder withDamageContext(Consumer<Pointers.Builder> builder) {
                Pointers.Builder b = Pointers.builder();
                builder.accept(b);
                context = b.build();
                return this;
            }

            @Override
            public DamageSource build() {
                return new VDamageSource(type, causing, direct != null ? direct : causing, location, context);
            }
        };
    }

    @Override
    public org.bukkit.inventory.ItemStack deserializeItem(byte[] data) {
        return VItemStack.fromSnbt(new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public org.bukkit.block.Biome constructLegacyCustomBiome() {
        return dev.vanillite.bukkit.registry.KeyedStandIn.create(org.bukkit.block.Biome.class,
                org.bukkit.NamespacedKey.minecraft("custom"), "biome");
    }

    @Override
    public org.bukkit.inventory.ItemStack createEmptyStack() {
        return VItemStack.empty();
    }

    @Override
    public ComponentFlattener componentFlattener() {
        return ComponentFlattener.basic();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getTranslationKey(EntityType type) {
        return type.getKey() == null ? null : "entity.minecraft." + type.getKey().getKey();
    }

    /** A damage source built by a plugin. */
    record VDamageSource(DamageType type, Entity causing, Entity direct, Location location, Pointers context)
            implements DamageSource {
        @Override
        public DamageType getDamageType() {
            return type;
        }

        @Override
        public Entity getCausingEntity() {
            return causing;
        }

        @Override
        public Entity getDirectEntity() {
            return direct;
        }

        @Override
        public Location getDamageLocation() {
            return location == null ? null : location.clone();
        }

        @Override
        public Location getSourceLocation() {
            return location != null ? location.clone() : direct != null ? direct.getLocation() : null;
        }

        @Override
        public boolean isIndirect() {
            return causing != direct;
        }

        @Override
        public float getFoodExhaustion() {
            return type.getExhaustion();
        }

        @Override
        public boolean scalesWithDifficulty() {
            return false;
        }

        @Override
        public Pointers getDamageContext() {
            return context;
        }
    }
}
