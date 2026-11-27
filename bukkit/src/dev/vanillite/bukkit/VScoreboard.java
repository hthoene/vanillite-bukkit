// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.bukkit.OfflinePlayer;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * The server's main scoreboard as far as plugins read it so far: the team of an entry. Teams know
 * their name; their other properties and the rest of the scoreboard API are not there yet.
 */
public abstract class VScoreboard implements Scoreboard {
    static final VScoreboard MAIN = Impl.create(VScoreboard.class);
    static final ScoreboardManager MANAGER = new ScoreboardManager() {
        @Override
        public Scoreboard getMainScoreboard() {
            return MAIN;
        }

        @Override
        public Scoreboard getNewScoreboard() {
            throw new UnsupportedOperationException("Vanillite: ScoreboardManager.getNewScoreboard is not implemented yet");
        }
    };

    @Override
    public Team getEntryTeam(String entry) {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(256);
        if (Host.team_of(Native.plugin, s.str(entry), buf) != Abi.status.OK) {
            return null;
        }
        return VTeam.create(Native.string(buf, true));
    }

    @Override
    @SuppressWarnings("deprecation")
    public Team getPlayerTeam(OfflinePlayer player) {
        return player.getName() == null ? null : getEntryTeam(player.getName());
    }

    /** A team of the main scoreboard by name. */
    public abstract static class VTeam implements Team {
        private static final MethodHandle NEW = Impl.constructor(VTeam.class, String.class);
        private final String name;

        protected VTeam(String name) {
            this.name = name;
        }

        static Team create(String name) {
            try {
                return (VTeam) NEW.invokeExact(name);
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Scoreboard getScoreboard() {
            return MAIN;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VTeam t && t.name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }
}
