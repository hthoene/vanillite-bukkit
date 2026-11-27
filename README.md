# vanillite-bukkit

Optional support for Paper and Spigot plugins on [Vanillite](https://github.com/hthoene/vanillite).

Vanillite itself contains no Java and no Bukkit code. This add-on is an ordinary native Vanillite
plugin: when it is installed, it starts a Java virtual machine inside the server process and
runs Bukkit/Paper plugins on top of Vanillite's plugin API. Without it nothing of this exists
in the server: no JVM, no memory, no work.

**Status: stage 2 (in progress).** Plugins load from `plugins/*.jar` (`plugin.yml` or
`paper-plugin.yml`) in dependency order (`depend`, `softdepend`, `loadbefore`, `STARTUP` first)
with shared class lookup between plugins and `libraries:` resolved from Maven Central. The plugin
manager is Bukkit's `SimplePluginManager` with Bukkit's permission registry (plugin.yml
permissions, `PermissionAttachment`, defaults), so permission plugins such as LuckPerms inject
as on Paper; the layer is also the server's permission provider. Commands use Bukkit's
`SimpleCommandMap` with tab completion; a player's Bukkit command takes precedence over a Vanilla
one of the same name. Events: pre-login/login, join, quit, chat (`AsyncPlayerChatEvent`,
`AsyncChatEvent`), command preprocess, teleport, respawn, block break; Bukkit priorities
interleave with native plugins. Services, plugin messaging and offline players are there.
Paper's registries (`Registry.BLOCK`, `ITEM`, enchantments, effects, sounds, ...) and tags
(`Tag.*`) come from the server's registries and data packs; block and item types carry their
Vanilla facts (hardness, blast resistance, stack size, ...). Worlds have chunks (handles), the
world border and heights; players have speeds, metadata, passengers and the main scoreboard's
teams (by name). EssentialsX (with Chat and Spawn), LuckPerms and Vault run: homes, warps,
spawn, `/tpa`, `/msg`, chat formats with LuckPerms prefixes.
Every other API method throws an `UnsupportedOperationException` that names it.

## Layout

| Path | What | Licence |
|---|---|---|
| `vanillite-jvm/` | The native plugin (Rust) that starts the JVM, and the Java side of the plugin ABI: `Boot`, `Native` and the bindings in `dev.vanillite.jvm.abi`, generated from `vanillite-abi`'s layout data by `vanillite-jvm-bindgen`. Knows nothing about Bukkit; usable for any Java plugin. | Apache-2.0 |
| `bukkit/` | The Bukkit/Paper API implementation (`dev.vanillite.bukkit`), the library bootstrap and a self test. | GPL-3.0 |
| `examples/` | `HelloBukkit` (a trivial plugin) and `VBench` (measures common API calls; the same jar runs on Paper). | GPL-3.0 |
| `package/plugin.toml` | The Vanillite package description of the layer. | |

## How it works

- `libvanillite_jvm.so` (`.dylib` on macOS, `vanillite_jvm.dll` on Windows) is loaded by Vanillite like any native plugin (`threading = "serialized"`:
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

## Install

Nothing to do by hand: put the Paper/Spigot plugin jars into the server's `plugins/` and start
Vanillite. It downloads the release of this layer made for its version into `plugins/bukkit/`
and, without a Java 25 runtime, Eclipse Temurin into `plugins/bukkit/runtime/` (both checked by
SHA-256), then loads the plugins (`plugins.bukkit.auto-setup`; see Vanillite's
`docs/plugins.md`). The layer looks for Java in `jvm.java-home` of `plugins/bukkit/config.toml`,
then `plugins/bukkit/runtime/`, `JAVA_HOME` and `java` on the `PATH`.

## Release

A tag `v<version>` (the version of `package/plugin.toml` and `Cargo.toml`) runs
`.github/workflows/release.yml`: the library is built for each target on its own runner
(`x86_64-` and `aarch64-unknown-linux-gnu` on Ubuntu 22.04 (glibc 2.35), `x86_64-pc-windows-msvc`
with the C runtime linked in, `aarch64-` and `x86_64-apple-darwin`), the Java parts once, and
`scripts/package.sh <target> <library>` packs `vanillite-bukkit-<version>-<target>.tar.gz` (the
folder `bukkit/`) per target; the release gets the archives and `SHA256SUMS`. Vanillite pins a
release by those lines in `crates/vanillite-server/bukkit-layer.txt`. A manual run of the workflow is a dry run.

## Build from source

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
