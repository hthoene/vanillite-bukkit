# Shared settings of the scripts (sourced). Finds a Java 25 JDK and the library cache.
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# A JDK 25 or newer: $JAVA_HOME, a known location, or javac on the PATH.
jdk25() { [[ -x "$1/bin/javac" ]] && "$1/bin/javac" -version 2>&1 | grep -qE 'javac (2[5-9]|[3-9][0-9])'; }
if ! jdk25 "${JAVA_HOME:-/nonexistent}"; then
    JAVA_HOME=""
    for candidate in "$HOME/jdks/temurin-25-jdk" /usr/lib/jvm/java-25-openjdk-amd64 \
        "$(dirname "$(dirname "$(readlink -f "$(command -v javac || echo /nonexistent)")")")"; do
        if jdk25 "$candidate"; then
            JAVA_HOME="$candidate"
            break
        fi
    done
    [[ -n "$JAVA_HOME" ]] || { echo "a JDK 25 or newer is needed (set JAVA_HOME)" >&2; exit 1; }
fi
export JAVA_HOME
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
JAVA="$JAVA_HOME/bin/java"
CACHE="${VANILLITE_BUKKIT_CACHE:-$HOME/.cache/vanillite-bukkit}"
BUILD="$ROOT/build"

# Downloads the libraries of bukkit/resources/libraries.txt into the cache (checked by SHA-1)
# and sets CLASSPATH_LIBS to them. The jars are never part of the repository or the build output.
fetch_libraries() {
    CLASSPATH_LIBS=""
    local gav sha g a v path file
    while read -r gav sha; do
        [[ -z "$gav" || "$gav" == \#* ]] && continue
        IFS=: read -r g a v <<<"$gav"
        path="${g//.//}/$a/$v/$a-$v.jar"
        file="$CACHE/libraries/$path"
        if [[ ! -f "$file" ]] || [[ "$(sha1sum "$file" | cut -d' ' -f1)" != "$sha" ]]; then
            mkdir -p "$(dirname "$file")"
            local ok=""
            for repo in https://repo.papermc.io/repository/maven-public https://repo.maven.apache.org/maven2; do
                if curl -fsSL -o "$file.part" "$repo/$path" && [[ "$(sha1sum "$file.part" | cut -d' ' -f1)" == "$sha" ]]; then
                    mv "$file.part" "$file"
                    ok=1
                    break
                fi
            done
            rm -f "$file.part"
            [[ -n "$ok" ]] || { echo "cannot download $gav" >&2; exit 1; }
        fi
        CLASSPATH_LIBS="$CLASSPATH_LIBS:$file"
    done <bukkit/resources/libraries.txt
    CLASSPATH_LIBS="${CLASSPATH_LIBS#:}"
}

# Downloads the compile-time jars of a compat module (compat/<name>/libraries.txt: <url> <sha1>)
# into the cache and sets CLASSPATH_COMPAT to them. Never shipped.
fetch_compat() {
    CLASSPATH_COMPAT=""
    local url sha file
    while read -r url sha; do
        [[ -z "$url" || "$url" == \#* ]] && continue
        file="$CACHE/compat/$sha.jar"
        if [[ ! -f "$file" ]] || [[ "$(sha1sum "$file" | cut -d' ' -f1)" != "$sha" ]]; then
            mkdir -p "$(dirname "$file")"
            curl -fsSL -o "$file.part" "$url" && [[ "$(sha1sum "$file.part" | cut -d' ' -f1)" == "$sha" ]] \
                || { rm -f "$file.part"; echo "cannot download $url" >&2; exit 1; }
            mv "$file.part" "$file"
        fi
        CLASSPATH_COMPAT="$CLASSPATH_COMPAT:$file"
    done <"compat/$1/libraries.txt"
    CLASSPATH_COMPAT="${CLASSPATH_COMPAT#:}"
}

# Compiles Java sources: compile_java <out dir> <class path> <source dir>...
compile_java() {
    local out="$1" cp="$2"
    shift 2
    rm -rf "$out"
    mkdir -p "$out"
    # shellcheck disable=SC2046
    "$JAVAC" --release 25 -encoding UTF-8 -Xlint:all,-deprecation,-removal,-serial,-processing,-options,-restricted,-path -Werror \
        -cp "$cp" -d "$out" $(find "$@" -name '*.java')
}
