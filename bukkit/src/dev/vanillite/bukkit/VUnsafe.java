// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.UnsafeValues;
import org.bukkit.material.MaterialData;
import org.bukkit.plugin.PluginDescriptionFile;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Bukkit's {@link UnsafeValues}, as far as plugins use it without server internals. Legacy
 * (pre-1.13) materials map to the modern material of the same name when there is one; the full
 * legacy conversion table is not there yet.
 */
@SuppressWarnings("deprecation")
public abstract class VUnsafe implements UnsafeValues {
    static final VUnsafe INSTANCE = Impl.create(VUnsafe.class);

    @Override
    public Material toLegacy(Material material) {
        if (material == null || material.isLegacy()) {
            return material;
        }
        Material legacy = Material.getMaterial(Material.LEGACY_PREFIX + material.name());
        return legacy != null ? legacy : material;
    }

    @Override
    public Material fromLegacy(Material material) {
        if (material == null || !material.isLegacy()) {
            return material;
        }
        Material modern = Material.getMaterial(material.name().substring(Material.LEGACY_PREFIX.length()).toUpperCase(Locale.ROOT));
        return modern != null ? modern : material;
    }

    @Override
    public Material fromLegacy(MaterialData data) {
        return fromLegacy(data.getItemType());
    }

    @Override
    public Material fromLegacy(MaterialData data, boolean itemPriority) {
        return fromLegacy(data.getItemType());
    }

    @Override
    public int getDataVersion() {
        return 5023;
    }

    @Override
    public int getProtocolVersion() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        return Host.protocol_version(Native.plugin, out) == Abi.status.OK ? out.get(JAVA_INT, 0) : 777;
    }

    @Override
    public boolean isSupportedApiVersion(String apiVersion) {
        return true;
    }

    @Override
    public void checkSupported(PluginDescriptionFile pdf) {
    }

    @Override
    public String getMainLevelName() {
        return "world";
    }

    @Override
    public org.bukkit.inventory.ItemStack deserializeStack(java.util.Map<String, Object> args) {
        StringBuilder b = new StringBuilder("{id:").append(Snbt.quote(String.valueOf(args.get("id"))))
                .append(",count:").append(args.get("count") instanceof Number n ? n.intValue() : 1);
        if (args.get("components") instanceof String c && !c.isBlank()) {
            b.append(",components:").append(c);
        }
        return VItemStack.fromSnbt(b.append('}').toString());
    }

}
