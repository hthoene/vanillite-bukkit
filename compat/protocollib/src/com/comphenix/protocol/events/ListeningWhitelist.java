// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.injector.GamePhase;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** The packet types and priority a listener listens to. */
public class ListeningWhitelist {
    public static final ListeningWhitelist EMPTY_WHITELIST = new ListeningWhitelist(ListenerPriority.LOW, Set.of(),
            GamePhase.PLAYING, Set.of());

    private final ListenerPriority priority;
    private final Set<PacketType> types;
    private final GamePhase gamePhase;
    private final Set<ListenerOptions> options;

    private ListeningWhitelist(ListenerPriority priority, Set<PacketType> types, GamePhase gamePhase,
            Set<ListenerOptions> options) {
        this.priority = priority;
        this.types = Set.copyOf(types);
        this.gamePhase = gamePhase;
        this.options = options.isEmpty() ? Set.of() : Set.copyOf(options);
    }

    public static boolean isEmpty(ListeningWhitelist whitelist) {
        return whitelist == null || whitelist == EMPTY_WHITELIST || whitelist.types.isEmpty();
    }

    public static Builder newBuilder() {
        return new Builder(null);
    }

    public static Builder newBuilder(ListeningWhitelist template) {
        return new Builder(template);
    }

    public boolean isEnabled() {
        return !types.isEmpty();
    }

    public ListenerPriority getPriority() {
        return priority;
    }

    public Set<PacketType> getTypes() {
        return types;
    }

    public GamePhase getGamePhase() {
        return gamePhase;
    }

    public Set<ListenerOptions> getOptions() {
        return options;
    }

    @Override
    public int hashCode() {
        return Objects.hash(priority, types, gamePhase, options);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ListeningWhitelist w && w.priority == priority && w.types.equals(types)
                && w.gamePhase == gamePhase && w.options.equals(options);
    }

    @Override
    public String toString() {
        return this == EMPTY_WHITELIST ? "EMPTY_WHITELIST"
                : "ListeningWhitelist[priority=" + priority + ", packets=" + types + ", gamephase=" + gamePhase
                        + ", options=" + options + "]";
    }

    /** Builds a whitelist. */
    public static class Builder {
        private ListenerPriority priority = ListenerPriority.NORMAL;
        private final Set<PacketType> types = new LinkedHashSet<>();
        private GamePhase gamePhase = GamePhase.PLAYING;
        private final Set<ListenerOptions> options = EnumSet.noneOf(ListenerOptions.class);

        Builder(ListeningWhitelist template) {
            if (template != null) {
                priority = template.priority;
                types.addAll(template.types);
                gamePhase = template.gamePhase;
                options.addAll(template.options);
            }
        }

        public Builder priority(ListenerPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder monitor() {
            return priority(ListenerPriority.MONITOR);
        }

        public Builder normal() {
            return priority(ListenerPriority.NORMAL);
        }

        public Builder lowest() {
            return priority(ListenerPriority.LOWEST);
        }

        public Builder low() {
            return priority(ListenerPriority.LOW);
        }

        public Builder highest() {
            return priority(ListenerPriority.HIGHEST);
        }

        public Builder high() {
            return priority(ListenerPriority.HIGH);
        }

        public Builder types(PacketType... types) {
            this.types.addAll(Arrays.asList(types));
            return this;
        }

        public Builder types(Collection<PacketType> types) {
            this.types.addAll(types);
            return this;
        }

        public Builder gamePhase(GamePhase gamePhase) {
            this.gamePhase = gamePhase;
            return this;
        }

        public Builder gamePhaseBoth() {
            return gamePhase(GamePhase.BOTH);
        }

        public Builder options(Set<ListenerOptions> options) {
            this.options.clear();
            this.options.addAll(options);
            return this;
        }

        public Builder options(Collection<ListenerOptions> options) {
            this.options.clear();
            this.options.addAll(options);
            return this;
        }

        public Builder options(ListenerOptions[] options) {
            return options(Arrays.asList(options));
        }

        public Builder mergeOptions(ListenerOptions... options) {
            this.options.addAll(Arrays.asList(options));
            return this;
        }

        public Builder mergeOptions(Collection<ListenerOptions> options) {
            this.options.addAll(options);
            return this;
        }

        public ListeningWhitelist build() {
            return new ListeningWhitelist(priority, types, gamePhase, options);
        }
    }
}
