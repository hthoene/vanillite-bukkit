// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Text conversion between Bukkit's forms and the host's (plain or JSON). */
public final class Texts {
    private Texts() {}

    /** JSON of a component. */
    public static String json(Component c) {
        return GsonComponentSerializer.gson().serialize(c);
    }

    /** A legacy string with section-sign codes as a component. */
    public static Component legacy(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s == null ? "" : s);
    }

    /** JSON of a legacy string with section-sign codes. */
    public static String legacyJson(String s) {
        return json(LegacyComponentSerializer.legacySection().deserialize(s));
    }
}
