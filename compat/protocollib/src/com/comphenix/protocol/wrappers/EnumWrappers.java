// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import java.util.Locale;
import org.bukkit.GameMode;

/** Enums of packet fields. */
public abstract class EnumWrappers {
    private EnumWrappers() {}

    public enum PlayerInfoAction {
        ADD_PLAYER, INITIALIZE_CHAT, UPDATE_GAME_MODE, UPDATE_LISTED, UPDATE_LATENCY, UPDATE_DISPLAY_NAME,
        UPDATE_LIST_ORDER, UPDATE_HAT, REMOVE_PLAYER
    }

    public enum SoundCategory {
        MASTER("master"), MUSIC("music"), RECORDS("record"), WEATHER("weather"), BLOCKS("block"), HOSTILE("hostile"),
        NEUTRAL("neutral"), PLAYERS("player"), AMBIENT("ambient"), VOICE("voice"), UI("ui");

        private final String key;

        SoundCategory(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public static SoundCategory getByKey(String key) {
            for (SoundCategory c : values()) {
                if (c.key.equals(key)) {
                    return c;
                }
            }
            return null;
        }
    }

    public enum NativeGameMode {
        NOT_SET, SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR, NONE;

        public GameMode toBukkit() {
            return switch (this) {
                case SURVIVAL -> GameMode.SURVIVAL;
                case CREATIVE -> GameMode.CREATIVE;
                case ADVENTURE -> GameMode.ADVENTURE;
                case SPECTATOR -> GameMode.SPECTATOR;
                default -> null;
            };
        }

        public static NativeGameMode fromBukkit(GameMode mode) {
            return mode == null ? NOT_SET : valueOf(mode.name());
        }

        /** The network id (-1 for none). */
        public int id() {
            return switch (this) {
                case SURVIVAL -> 0;
                case CREATIVE -> 1;
                case ADVENTURE -> 2;
                case SPECTATOR -> 3;
                default -> -1;
            };
        }

        /** The mode of a network id. */
        public static NativeGameMode byId(int id) {
            return switch (id) {
                case 0 -> SURVIVAL;
                case 1 -> CREATIVE;
                case 2 -> ADVENTURE;
                case 3 -> SPECTATOR;
                default -> NOT_SET;
            };
        }
    }

    public enum Hand {
        MAIN_HAND, OFF_HAND
    }

    public enum Direction {
        DOWN, UP, NORTH, SOUTH, WEST, EAST
    }

    public enum EntityPose {
        STANDING, FALL_FLYING, SLEEPING, SWIMMING, SPIN_ATTACK, CROUCHING, LONG_JUMPING, DYING, CROAKING,
        USING_TONGUE, SITTING, ROARING, SNIFFING, EMERGING, DIGGING, SLIDING, SHOOTING, INHALING;

        public static EntityPose fromNms(Object nms) {
            return valueOf(String.valueOf(nms).toUpperCase(Locale.ROOT));
        }

        public Object toNms() {
            return this;
        }
    }

    public enum ChatType {
        CHAT, SYSTEM, GAME_INFO;

        public byte getId() {
            return (byte) ordinal();
        }
    }

    public enum TitleAction {
        TITLE, SUBTITLE, ACTIONBAR, TIMES, CLEAR, RESET
    }
}
