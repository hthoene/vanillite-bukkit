// SPDX-License-Identifier: Apache-2.0
package dev.vanillite.jvm;

import java.lang.foreign.MemorySegment;

/** The Java side of a native Vanillite plugin hosted by vanillite-jvm. */
public interface JvmPlugin {
    /** Called once while the server loads plugins: register commands, events, services. */
    void load() throws Exception;

    /** The plugin's {@code enable} callback with its context. */
    default int enable(MemorySegment ctx) {
        return 0;
    }

    /** The plugin's {@code disable} callback with its context. */
    default int disable(MemorySegment ctx) {
        return 0;
    }
}
