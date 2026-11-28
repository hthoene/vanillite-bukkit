// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import dev.vanillite.bukkit.Impl;
import dev.vanillite.jvm.Native;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameEvent;
import org.bukkit.GameRule;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructureType;
import org.bukkit.potion.PotionEffectType;

/**
 * Registry entries of the abstract-class types (enchantments, effects, game rules, ...): a key and
 * a translation key; every other method throws naming itself (through {@link Impl}).
 */
final class KeyedClasses {
    private KeyedClasses() {}

    private static final Map<Class<?>, Class<?>> BASES = Map.of(
            Enchantment.class, Ench.class,
            PotionEffectType.class, Effect.class,
            GameRule.class, Rule.class,
            GameEvent.class, Event.class,
            Structure.class, Struct.class,
            StructureType.class, StructType.class,
            MusicInstrument.class, Instrument.class);
    private static final Map<Class<?>, MethodHandle> NEW = new ConcurrentHashMap<>();

    static Object create(Class<?> type, NamespacedKey key, String kind) {
        Class<?> base = BASES.get(type);
        if (base == null) {
            return null;
        }
        MethodHandle ctor = NEW.computeIfAbsent(base, b -> Impl.constructor(b, NamespacedKey.class, String.class));
        try {
            return ctor.invoke(key, KeyedStandIn.translationKey(kind.equals("mob_effect") ? "effect" : kind, key));
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    public abstract static class Ench extends Enchantment {
        private final NamespacedKey key;
        private final String tk;

        protected Ench(NamespacedKey key, String tk) {
            this.key = key;
            this.tk = tk;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }

        @Override
        public String translationKey() {
            return tk;
        }

        @Override
        public String getTranslationKey() {
            return tk;
        }

        @Override
        public String toString() {
            return "Enchantment{" + key + "}";
        }
    }

    public abstract static class Effect extends PotionEffectType {
        private final NamespacedKey key;
        private final String tk;

        protected Effect(NamespacedKey key, String tk) {
            this.key = key;
            this.tk = tk;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }

        @Override
        public String translationKey() {
            return tk;
        }

        @Override
        public String getTranslationKey() {
            return tk;
        }

        @Override
        public String toString() {
            return "PotionEffectType{" + key + "}";
        }
    }

    public abstract static class Rule<T> extends GameRule<T> {
        private final NamespacedKey key;
        private final String tk;

        protected Rule(NamespacedKey key, String tk) {
            this.key = key;
            this.tk = tk;
        }

        @Override
        public String getName() {
            return key.getKey();
        }

        /** Boolean or Integer, from the server's current value. */
        @Override
        @SuppressWarnings("unchecked")
        public Class<T> getType() {
            var s = Native.scratch();
            java.lang.foreign.MemorySegment buf = s.strBuf(256);
            String value = dev.vanillite.jvm.abi.Host.game_rule_get(Native.plugin, s.str(key.getKey()), buf)
                    == dev.vanillite.jvm.abi.Abi.status.OK ? Native.string(buf, true).strip() : "";
            return (Class<T>) (value.equals("true") || value.equals("false") ? Boolean.class : Integer.class);
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }

        @Override
        public String translationKey() {
            return tk;
        }
    }

    public abstract static class Event extends GameEvent {
        private final NamespacedKey key;

        protected Event(NamespacedKey key, String tk) {
            this.key = key;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }
    }

    public abstract static class Struct extends Structure {
        private final NamespacedKey key;

        protected Struct(NamespacedKey key, String tk) {
            this.key = key;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }
    }

    public abstract static class StructType extends StructureType {
        private final NamespacedKey key;

        protected StructType(NamespacedKey key, String tk) {
            this.key = key;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }
    }

    public abstract static class Instrument extends MusicInstrument {
        private final NamespacedKey key;
        private final String tk;

        protected Instrument(NamespacedKey key, String tk) {
            this.key = key;
            this.tk = tk;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }

        @Override
        public String translationKey() {
            return tk;
        }
    }
}
