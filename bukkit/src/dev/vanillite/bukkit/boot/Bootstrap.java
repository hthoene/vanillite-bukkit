// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.boot;

import dev.vanillite.jvm.JvmPlugin;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Starts the layer: makes sure the Paper API and its libraries are present (downloaded from
 * their Maven repositories at first start, checked by SHA-1, never shipped with the layer), then
 * loads {@code dev.vanillite.bukkit.Layer} with them on its class path. This class must not refer
 * to any API class.
 */
public final class Bootstrap implements JvmPlugin {
    private static final String[] REPOSITORIES = {
        "https://repo.papermc.io/repository/maven-public/", "https://repo.maven.apache.org/maven2/",
    };

    private JvmPlugin layer;

    @Override
    public void load() throws Exception {
        long start = System.nanoTime();
        ClassLoader own = Bootstrap.class.getClassLoader();
        ClassLoader loader;
        if (available(own, "org.bukkit.Bukkit")) {
            // Everything is on the JVM's class path already (e.g. for an AOT/CDS cache).
            loader = own;
        } else {
            Path dir = Path.of(System.getProperty("vanillite.jvm.dir"), "libraries");
            List<URL> urls = new ArrayList<>();
            urls.add(Path.of(System.getProperty("vanillite.jvm.main-jar")).toUri().toURL());
            for (String line : lines("/libraries.txt")) {
                urls.add(library(dir, line).toUri().toURL());
            }
            loader = new URLClassLoader("vanillite-bukkit", urls.toArray(URL[]::new), ClassLoader.getSystemClassLoader());
        }
        Thread.currentThread().setContextClassLoader(loader);
        // JDBC drivers (as on Paper's class path) register with DriverManager when loaded; it
        // would only look for them in the system class loader by itself.
        ServiceLoader.load(Driver.class, loader).stream().forEach(ServiceLoader.Provider::get);
        layer = (JvmPlugin) Class.forName("dev.vanillite.bukkit.Layer", true, loader).getConstructor().newInstance();
        Native.log(Abi.log_level.INFO, "Bukkit layer classes ready in " + (System.nanoTime() - start) / 1_000_000 + " ms");
        layer.load();
    }

    @Override
    public int enable(MemorySegment ctx) {
        return layer.enable(ctx);
    }

    @Override
    public int disable(MemorySegment ctx) {
        return layer.disable(ctx);
    }

    private static boolean available(ClassLoader loader, String name) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static List<String> lines(String resource) throws IOException {
        try (InputStream in = Bootstrap.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }

    /** {@code group:artifact:version sha1}: the jar, downloaded once into {@code dir}. */
    private static Path library(Path dir, String line) throws Exception {
        String[] parts = line.split("\\s+");
        String[] gav = parts[0].split(":");
        String path = gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + gav[1] + "-" + gav[2] + ".jar";
        Path file = dir.resolve(path);
        if (Files.isRegularFile(file) && sha1(file).equals(parts[1])) {
            return file;
        }
        Files.createDirectories(file.getParent());
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (String repo : REPOSITORIES) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(repo + path)).build();
            Path tmp = file.resolveSibling(file.getFileName() + ".part");
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() == 200 && sha1(tmp).equals(parts[1])) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                Native.log(Abi.log_level.INFO, "Downloaded " + parts[0] + " from " + repo);
                return file;
            }
            Files.deleteIfExists(tmp);
        }
        throw new IOException("cannot download " + parts[0] + " (checked SHA-1 " + parts[1] + ")");
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
