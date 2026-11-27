// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.boot;

import java.io.File;
import java.util.jar.JarFile;

/**
 * Training run for an AOT cache of the layer: loads and links the classes of the layer and the
 * API jars on the class path, without a server. Run it with the same class path and options as
 * the layer, e.g. {@code java -XX:AOTCacheOutput=layer.aot -cp <class path> ...Train}.
 */
public final class Train {
    private Train() {}

    public static void main(String[] args) throws Exception {
        String[] prefixes = {"dev/vanillite/", "org/bukkit/", "io/papermc/", "net/kyori/", "com/destroystokyo/"};
        int loaded = 0;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            try (JarFile jar = new JarFile(entry)) {
                for (var e : java.util.Collections.list(jar.entries())) {
                    String name = e.getName();
                    if (!name.endsWith(".class") || name.contains("module-info")) {
                        continue;
                    }
                    for (String p : prefixes) {
                        if (name.startsWith(p)) {
                            try {
                                Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false,
                                        Train.class.getClassLoader());
                                loaded++;
                            } catch (Throwable ignored) {
                                // optional dependencies missing: skip the class
                            }
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("loaded " + loaded + " classes");
    }
}
