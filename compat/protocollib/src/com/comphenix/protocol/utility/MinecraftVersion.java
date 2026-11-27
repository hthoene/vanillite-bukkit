// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.utility;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Server;

/** A Minecraft version: major, minor and build (e.g. 26.3.0). */
public final class MinecraftVersion implements Comparable<MinecraftVersion>, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern VERSION = Pattern.compile(".*\\(.*MC.\\s*([a-zA-z0-9\\-.]+).*");

    public static final MinecraftVersion v1_21_6 = new MinecraftVersion("1.21.6");
    public static final MinecraftVersion v1_21_5 = new MinecraftVersion("1.21.5");
    public static final MinecraftVersion v1_21_4 = new MinecraftVersion("1.21.4");
    public static final MinecraftVersion v1_21_2 = new MinecraftVersion("1.21.2");
    public static final MinecraftVersion v1_21_0 = new MinecraftVersion("1.21.0");
    public static final MinecraftVersion v1_20_5 = new MinecraftVersion("1.20.5");
    public static final MinecraftVersion v1_20_4 = new MinecraftVersion("1.20.4");
    public static final MinecraftVersion CONFIG_PHASE_PROTOCOL_UPDATE = new MinecraftVersion("1.20.2");
    public static final MinecraftVersion TRAILS_AND_TAILS = new MinecraftVersion("1.20");
    public static final MinecraftVersion FEATURE_PREVIEW_2 = new MinecraftVersion("1.19.4");
    public static final MinecraftVersion FEATURE_PREVIEW_UPDATE = new MinecraftVersion("1.19.3");
    public static final MinecraftVersion WILD_UPDATE = new MinecraftVersion("1.19");
    public static final MinecraftVersion CAVES_CLIFFS_2 = new MinecraftVersion("1.18");
    public static final MinecraftVersion CAVES_CLIFFS_1 = new MinecraftVersion("1.17");
    public static final MinecraftVersion NETHER_UPDATE_4 = new MinecraftVersion("1.16.4");
    public static final MinecraftVersion NETHER_UPDATE_2 = new MinecraftVersion("1.16.2");
    public static final MinecraftVersion NETHER_UPDATE = new MinecraftVersion("1.16");
    public static final MinecraftVersion BEE_UPDATE = new MinecraftVersion("1.15");
    public static final MinecraftVersion VILLAGE_UPDATE = new MinecraftVersion("1.14");
    public static final MinecraftVersion AQUATIC_UPDATE = new MinecraftVersion("1.13");
    public static final MinecraftVersion COLOR_UPDATE = new MinecraftVersion("1.12");
    public static final MinecraftVersion EXPLORATION_UPDATE = new MinecraftVersion("1.11");
    public static final MinecraftVersion FROSTBURN_UPDATE = new MinecraftVersion("1.10");
    public static final MinecraftVersion COMBAT_UPDATE = new MinecraftVersion("1.9");
    public static final MinecraftVersion BOUNTIFUL_UPDATE = new MinecraftVersion("1.8");
    public static final MinecraftVersion SKIN_UPDATE = new MinecraftVersion("1.7.8");
    public static final MinecraftVersion WORLD_UPDATE = new MinecraftVersion("1.7.2");
    public static final MinecraftVersion HORSE_UPDATE = new MinecraftVersion("1.6.1");
    public static final MinecraftVersion REDSTONE_UPDATE = new MinecraftVersion("1.5.0");
    public static final MinecraftVersion SCARY_UPDATE = new MinecraftVersion("1.4.2");
    public static final MinecraftVersion LATEST = new MinecraftVersion("26.3");

    private static volatile MinecraftVersion current;

    private final int major;
    private final int minor;
    private final int build;
    private final String development;

    public MinecraftVersion(Server server) {
        this(extractVersion(server.getVersion()));
    }

    public MinecraftVersion(String version) {
        String[] parts = version.split("-", 2);
        String[] numbers = parts[0].split("\\.");
        int[] n = new int[3];
        for (int i = 0; i < Math.min(3, numbers.length); i++) {
            n[i] = Integer.parseInt(numbers[i].trim());
        }
        this.major = n[0];
        this.minor = n[1];
        this.build = n[2];
        this.development = parts.length > 1 ? parts[1] : null;
    }

    public MinecraftVersion(int major, int minor, int build) {
        this(major, minor, build, null);
    }

    public MinecraftVersion(int major, int minor, int build, String development) {
        this.major = major;
        this.minor = minor;
        this.build = build;
        this.development = development;
    }

    public static String extractVersion(String text) {
        Matcher m = VERSION.matcher(text);
        if (m.matches() && m.group(1) != null) {
            return m.group(1);
        }
        throw new IllegalStateException("Cannot parse version String '" + text + "'");
    }

    public static MinecraftVersion fromServerVersion(String serverVersion) {
        return new MinecraftVersion(extractVersion(serverVersion));
    }

    public static MinecraftVersion getCurrentVersion() {
        MinecraftVersion v = current;
        if (v == null) {
            try {
                v = new MinecraftVersion(org.bukkit.Bukkit.getMinecraftVersion());
            } catch (RuntimeException e) {
                v = LATEST;
            }
            current = v;
        }
        return v;
    }

    public static void setCurrentVersion(MinecraftVersion version) {
        current = version;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getBuild() {
        return build;
    }

    public String getDevelopmentStage() {
        return development;
    }

    public boolean isSnapshot() {
        return false;
    }

    public boolean atOrAbove() {
        return getCurrentVersion().isAtLeast(this);
    }

    public String getVersion() {
        return development == null ? major + "." + minor + "." + build : major + "." + minor + "." + build + "-" + development;
    }

    @Override
    public int compareTo(MinecraftVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c == 0 ? Integer.compare(build, o.build) : c;
    }

    public boolean isAtLeast(MinecraftVersion other) {
        return other != null && compareTo(other) >= 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MinecraftVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        return (major * 31 + minor) * 31 + build;
    }

    @Override
    public String toString() {
        return "(MC: " + getVersion() + ")";
    }
}
