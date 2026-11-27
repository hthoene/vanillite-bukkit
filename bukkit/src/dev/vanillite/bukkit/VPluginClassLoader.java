// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

/** The class loader of one plugin jar. */
public final class VPluginClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final PluginDescriptionFile description;
    private final File file;
    private final File dataFolder;
    private final Logger logger;
    private JavaPlugin plugin;

    VPluginClassLoader(File file, PluginDescriptionFile description, File dataFolder, Logger logger, ClassLoader parent)
            throws java.net.MalformedURLException {
        super(description.getName(), new URL[] {file.toURI().toURL()}, parent);
        this.file = file;
        this.description = description;
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    @Override
    public PluginMeta getConfiguration() {
        return description;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal, boolean checkLibraries)
            throws ClassNotFoundException {
        return loadClass(name, resolve);
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
