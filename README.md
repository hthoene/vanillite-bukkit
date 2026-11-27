# vanillite-bukkit

Optional support for Paper and Spigot plugins on [Vanillite](https://github.com/hthoene/vanillite).

Vanillite itself contains no Java and no Bukkit code. This add-on is an ordinary native Vanillite
plugin: when it is installed, it starts a Java virtual machine inside the server process and
runs Bukkit/Paper plugins on top of Vanillite's plugin API. Without it nothing of this exists
in the server: no JVM, no memory, no work.

**Status: stage 1 (spike).** Only a small part of the API is implemented: plugin loading from
`plugins/*.jar` (`plugin.yml`), `PlayerJoinEvent`, `PlayerQuitEvent`, `BlockBreakEvent`, block
type get/set, players (`sendMessage`, permissions, location), entities (location, type, nearby
entities), the scheduler and plugin commands. Every other API method throws an
`UnsupportedOperationException` that names it.

## Layout

| Path | What | Licence |
|---|---|---|
| `vanillite-jvm/` | The native plugin (Rust) that starts the JVM, and the Java side of the plugin ABI: `Boot`, `Native` and the bindings in `dev.vanillite.jvm.abi`, generated from `vanillite-abi`'s layout data by `vanillite-jvm-bindgen`. Knows nothing about Bukkit; usable for any Java plugin. | Apache-2.0 |
| `bukkit/` | The Bukkit/Paper API implementation (`dev.vanillite.bukkit`), the library bootstrap and a self test. | GPL-3.0 |
| `examples/` | `HelloBukkit` (a trivial plugin) and `VBench` (measures common API calls; the same jar runs on Paper). | GPL-3.0 |
| `package/plugin.toml` | The Vanillite package description of the layer. | |

## How it works

- `libvanillite_jvm.so` is loaded by Vanillite like any native plugin (`threading = "serialized"`:
  one call into the layer at a time, the stand-in for Bukkit's main thread). On `load` it opens
  `libjvm` of a Java 25 runtime (`jvm.java-home` in `plugins/bukkit/config.toml`, `JAVA_HOME`,
  or `java` on the `PATH`) and starts the JVM with `-Xrs` (signals stay with the server).
- Java calls the server through the Foreign Function & Memory API with downcalls linked from the
  host's function table; the server calls Java through upcall stubs. No JNI glue per function.
- The Paper API jar and its libraries are downloaded from Paper's and Maven Central's
  repositories at first start into `plugins/bukkit/libraries/` and checked by SHA-1. They are
  never shipped with the layer.
- API objects (`World`, `Block`, `Player`, `Entity`) are thin wrappers around ids; no world state
  is copied into the Java heap. Each implementation class is an abstract class that implements
  what is supported; at run time a final subclass is generated whose remaining methods throw.
- Only native events that some plugin listens to are subscribed (the plugin jars are scanned for
  `@EventHandler` methods before they are loaded): without a listener there is no upcall.

## Build and install

Needs Rust (stable) and a JDK 25.

```sh
scripts/build.sh            # build/dist/plugins/: bukkit/ + HelloBukkit.jar + VBench.jar
cp -r build/dist/plugins/. /path/to/server/plugins/
```

`scripts/quick-check.sh` (format, lint, bindings up to date, Java compiles without warnings) and
`scripts/check.sh` (build, tests, self test) are the repository's checks. After a change of the
plugin ABI, `scripts/bindgen.sh` regenerates the Java bindings.

Commands of the layer (operators): `/vjvm bench` (call costs between native code and Java),
`/vjvm mem` (JVM memory, process RSS), `/vjvm gc <seconds>` (garbage-collection stress).

## Clean-room rule

The API implementation is written from the API's signatures and documentation and from
Vanilla's observable behaviour. No code is copied from CraftBukkit, Spigot, Paper or Folia.
See `NOTICE`.
