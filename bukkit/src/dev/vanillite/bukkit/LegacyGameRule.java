// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.lang.invoke.MethodHandle;
import java.util.function.Function;
import org.bukkit.GameRule;
import org.bukkit.NamespacedKey;

/**
 * A game rule of Bukkit's old set that stands for a current one with a converted value (e.g.
 * {@code DISABLE_PLAYER_MOVEMENT_CHECK} for the inverted {@code player_movement_check}).
 */
public abstract class LegacyGameRule<M, L> extends GameRule<L> {
    private static final MethodHandle NEW = Impl.constructor(LegacyGameRule.class, GameRule.class, Function.class,
            Function.class, Class.class);

    final GameRule<M> modern;
    private final Function<L, M> fromLegacy;
    private final Function<M, L> toLegacy;
    private final Class<L> type;

    protected LegacyGameRule(GameRule<M> modern, Function<L, M> fromLegacy, Function<M, L> toLegacy, Class<L> type) {
        this.modern = modern;
        this.fromLegacy = fromLegacy;
        this.toLegacy = toLegacy;
        this.type = type;
    }

    @SuppressWarnings("unchecked")
    static <M, L> GameRule<L> create(GameRule<M> modern, Function<L, M> fromLegacy, Function<M, L> toLegacy, Class<L> type) {
        try {
            return (GameRule<L>) (LegacyGameRule<M, L>) NEW.invoke(modern, fromLegacy, toLegacy, type);
        } catch (Throwable t) {
            throw dev.vanillite.jvm.Native.rethrow(t);
        }
    }

    /** The current rule's value for a value of this one. */
    @SuppressWarnings("unchecked")
    Object toModern(Object legacy) {
        return fromLegacy.apply((L) legacy);
    }

    /** This rule's value for a value of the current one. */
    @SuppressWarnings("unchecked")
    Object toLegacy(Object value) {
        return value == null ? null : toLegacy.apply((M) value);
    }

    @Override
    public NamespacedKey getKey() {
        return modern.getKey();
    }

    @Override
    public String getName() {
        return modern.getName();
    }

    @Override
    public Class<L> getType() {
        return type;
    }

    @Override
    public String translationKey() {
        return modern.translationKey();
    }
}
