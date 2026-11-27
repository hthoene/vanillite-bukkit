// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import io.papermc.paper.ServerBuildInfo;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import net.kyori.adventure.key.Key;

/** What the server reports about its build (found through {@link java.util.ServiceLoader}). */
public final class VBuildInfo implements ServerBuildInfo {
    private static final Key BRAND = Key.key("vanillite", "vanillite");
    private static final Instant BUILT = Instant.now();

    public VBuildInfo() {}

    @Override
    public Key brandId() {
        return BRAND;
    }

    @Override
    public boolean isBrandCompatible(Key brand) {
        // The layer implements the Paper API: plugins that check for Paper see a compatible brand.
        return brand.equals(BRAND) || brand.equals(BRAND_PAPER_ID);
    }

    @Override
    public String brandName() {
        return "Vanillite";
    }

    @Override
    public String minecraftVersionId() {
        return "26.3";
    }

    @Override
    public String minecraftVersionName() {
        return "26.3";
    }

    @Override
    public OptionalInt buildNumber() {
        return OptionalInt.empty();
    }

    @Override
    public Instant buildTime() {
        return BUILT;
    }

    @Override
    public Optional<String> gitBranch() {
        return Optional.empty();
    }

    @Override
    public Optional<String> gitCommit() {
        return Optional.empty();
    }

    @Override
    public String asString(StringRepresentation representation) {
        return representation == StringRepresentation.VERSION_SIMPLE ? "26.3" : "Vanillite 26.3 (Bukkit layer 0.1.0)";
    }
}
