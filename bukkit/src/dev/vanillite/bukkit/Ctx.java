// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.abi.Abi;
import java.lang.foreign.MemorySegment;

/**
 * The context of the native callback that is running plugin code. Owner-bound host calls
 * (blocks, entity positions) use it. The layer runs with {@code threading = "serialized"}, so
 * one callback runs at a time and a plain field is enough.
 */
public final class Ctx {
    private Ctx() {}

    /** The running callback's context, or {@link MemorySegment#NULL} outside a callback. */
    public static MemorySegment current = MemorySegment.NULL;

    /** Throws for a failed owner-bound call. */
    public static void check(int status, String what) {
        if (status == Abi.status.OK) {
            return;
        }
        throw switch (status) {
            case Abi.status.WRONG_OWNER -> new IllegalStateException(
                    what + ": not allowed from this thread or task (the world is owned by another task)");
            case Abi.status.NOT_FOUND -> new IllegalStateException(what + ": not loaded or not found");
            default -> new IllegalStateException(what + " failed with status " + status);
        };
    }
}
