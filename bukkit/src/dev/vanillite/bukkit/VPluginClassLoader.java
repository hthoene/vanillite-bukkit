// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The class loader of one plugin jar: the API and the layer first, then the jar, then its
 * {@code libraries:}, then the classes of the other plugins (Bukkit's shared class lookup).
 */
public final class VPluginClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    /** Every plugin's loader in load order. */
    static final List<VPluginClassLoader> ALL = new CopyOnWriteArrayList<>();

    private final PluginDescriptionFile description;
    private final File file;
    private final File dataFolder;
    private final Logger logger;
    private final ClassLoader libraries;
    private JavaPlugin plugin;

    VPluginClassLoader(File file, PluginDescriptionFile description, File dataFolder, Logger logger, ClassLoader parent,
            ClassLoader libraries) throws java.net.MalformedURLException {
        super(description.getName(), new URL[] {file.toURI().toURL()}, parent);
        this.file = file;
        this.description = description;
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.libraries = libraries;
        ALL.add(this);
    }

    @Override
    public PluginMeta getConfiguration() {
        return description;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return loadClass(name, resolve, true, true);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries)
            throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    c = getParent().loadClass(name);
                } catch (ClassNotFoundException e) {
                    // not an API or layer class
                }
            }
            if (c == null) {
                try {
                    c = findClass(name);
                } catch (ClassNotFoundException e) {
                    // not in this jar
                }
            }
            if (c == null && checkLibraries && libraries != null) {
                try {
                    c = libraries.loadClass(name);
                } catch (ClassNotFoundException e) {
                    // not in its libraries
                }
            }
            if (c == null && checkGlobal) {
                for (VPluginClassLoader other : ALL) {
                    if (other == this) {
                        continue;
                    }
                    try {
                        c = other.loadClass(name, false, false, false);
                        break;
                    } catch (ClassNotFoundException e) {
                        // try the next plugin
                    }
                }
            }
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
        return super.getResources(name);
    }

    @Override
    public void init(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.init(Layer.server, description, dataFolder, file, this, description, logger);
    }

    @Override
    public JavaPlugin getPlugin() {
        return plugin;
    }

    @Override
    public PluginClassLoaderGroup getGroup() {
        return null;
    }
}
