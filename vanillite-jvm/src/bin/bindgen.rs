//! Generates the Java bindings of the plugin ABI from `vanillite_abi::layout`.
//!
//! `cargo run -p vanillite-jvm --bin vanillite-jvm-bindgen -- <java source dir>` writes
//! `dev/vanillite/jvm/abi/Abi.java` (constants, record layouts and offsets, callback
//! descriptors) and `dev/vanillite/jvm/abi/Host.java` (one Foreign Function & Memory downcall per
//! host function). With `--check` it fails when the files on disk differ.

use std::collections::HashMap;
use std::fmt::Write as _;
use std::path::PathBuf;

use vanillite_abi::layout::{self, StructLayout};

/// Host functions that only read and never call back into a plugin: linked as "critical"
/// (no thread state transition). Each also gets a `<name>_transition` variant with a normal
/// linkage, for measurements.
const CRITICAL: &[&str] = &[
    "owner_world",
    "block_get_id",
    "block_state_id",
    "block_state_name",
    "player_position",
    "entity_location",
    "entity_get",
    "player_get",
    "entities_in_box",
    "entity_type_name",
];

const JAVA_KEYWORDS: &[&str] = &[
    "int", "float", "double", "long", "short", "byte", "char", "boolean", "class", "new",
    "default", "switch", "case", "final", "static", "public", "private", "native", "package",
    "import",
];

fn ident(name: &str) -> String {
    if JAVA_KEYWORDS.contains(&name) {
        format!("{name}_")
    } else {
        name.to_owned()
    }
}

/// A type of the ABI as FFM sees it.
#[derive(Clone, Debug)]
enum Ty {
    /// A value layout constant (`JAVA_INT`, ...) and its Java carrier type.
    Value(&'static str, &'static str, usize),
    /// A record by value.
    Struct(String),
    /// A fixed array.
    Array(Box<Ty>, usize),
}

struct Gen {
    aliases: HashMap<&'static str, String>,
    structs: HashMap<&'static str, &'static StructLayout>,
}

impl Gen {
    fn ty(&self, raw: &str) -> Ty {
        let t: String = raw.chars().filter(|c| !c.is_whitespace()).collect();
        if t.starts_with('*') || t.contains("fn(") || t.starts_with("Option<") {
            return Ty::Value("ADDRESS", "MemorySegment", 8);
        }
        if let Some(inner) = t.strip_prefix('[').and_then(|r| r.strip_suffix(']')) {
            let (elem, n) = inner.rsplit_once(';').expect("array type");
            return Ty::Array(Box::new(self.ty(elem)), n.parse().expect("array length"));
        }
        match t.as_str() {
            "u8" | "i8" => Ty::Value("JAVA_BYTE", "byte", 1),
            "u16" | "i16" => Ty::Value("JAVA_SHORT", "short", 2),
            "u32" | "i32" => Ty::Value("JAVA_INT", "int", 4),
            "u64" | "i64" | "usize" | "isize" => Ty::Value("JAVA_LONG", "long", 8),
            "f32" => Ty::Value("JAVA_FLOAT", "float", 4),
            "f64" => Ty::Value("JAVA_DOUBLE", "double", 8),
            other => {
                if let Some(alias) = self.aliases.get(other) {
                    return self.ty(alias);
                }
                if self.structs.contains_key(other) {
                    return Ty::Struct(other.to_owned());
                }
                panic!("unknown ABI type {raw:?}");
            }
        }
    }

    fn layout(&self, ty: &Ty) -> String {
        match ty {
            Ty::Value(l, _, _) => l.to_string(),
            Ty::Struct(name) => format!("{name}.LAYOUT"),
            Ty::Array(elem, n) => {
                format!("MemoryLayout.sequenceLayout({n}, {})", self.layout(elem))
            }
        }
    }

    fn size(&self, ty: &Ty) -> usize {
        match ty {
            Ty::Value(_, _, size) => *size,
            Ty::Struct(name) => self.structs[name.as_str()].size,
            Ty::Array(elem, n) => self.size(elem) * n,
        }
    }

    fn carrier(&self, ty: &Ty) -> &'static str {
        match ty {
            Ty::Value(_, c, _) => c,
            _ => "MemorySegment",
        }
    }

    /// Parameters and return type of a function type.
    fn signature(&self, raw: &str) -> (Vec<Ty>, Option<Ty>) {
        let t: String = raw.chars().filter(|c| !c.is_whitespace()).collect();
        let t = t
            .strip_prefix("Option<")
            .and_then(|r| r.strip_suffix('>'))
            .unwrap_or(&t);
        let open = t.find("fn(").expect("function type") + 3;
        let mut depth = 0;
        let mut close = open;
        for (i, c) in t[open..].char_indices() {
            match c {
                '(' | '<' | '[' => depth += 1,
                ')' | '>' | ']' if depth > 0 => depth -= 1,
                ')' => {
                    close = open + i;
                    break;
                }
                _ => {}
            }
        }
        let mut params = Vec::new();
        let (mut depth, mut start) = (0, open);
        let args = &t[open..close];
        for (i, c) in args.char_indices() {
            match c {
                '(' | '<' | '[' => depth += 1,
                ')' | '>' | ']' => depth -= 1,
                ',' if depth == 0 => {
                    params.push(&t[start..open + i]);
                    start = open + i + 1;
                }
                _ => {}
            }
        }
        if start < close {
            params.push(&t[start..close]);
        }
        let params = params
            .into_iter()
            .map(|p| p.rsplit_once(':').map_or(p, |(_, ty)| ty))
            .map(|p| self.ty(p))
            .collect();
        let ret = t[close + 1..].strip_prefix("->").map(|r| self.ty(r));
        (params, ret)
    }

    fn descriptor(&self, params: &[Ty], ret: &Option<Ty>) -> String {
        let args = params
            .iter()
            .map(|p| self.layout(p))
            .collect::<Vec<_>>()
            .join(", ");
        match ret {
            Some(r) if args.is_empty() => format!("FunctionDescriptor.of({})", self.layout(r)),
            Some(r) => format!("FunctionDescriptor.of({}, {args})", self.layout(r)),
            None => format!("FunctionDescriptor.ofVoid({args})"),
        }
    }
}

fn header(out: &mut String) {
    let _ = writeln!(
        out,
        "// Generated by vanillite-jvm-bindgen from vanillite-abi {}.{}. Do not edit.\n\
         // SPDX-License-Identifier: Apache-2.0\n\
         package dev.vanillite.jvm.abi;\n\n\
         import java.lang.foreign.*;\n\
         import java.lang.invoke.MethodHandle;\n\
         import static java.lang.foreign.ValueLayout.*;\n",
        vanillite_abi::ABI_MAJOR,
        vanillite_abi::ABI_MINOR
    );
}

fn abi_java(g: &Gen) -> String {
    let mut out = String::new();
    header(&mut out);
    out.push_str(
        "/** The plugin ABI: constants, record layouts with field offsets, callback descriptors. */\n\
         @SuppressWarnings({\"unused\", \"checkstyle:all\"})\npublic final class Abi {\n    private Abi() {}\n\n",
    );
    let _ = writeln!(
        out,
        "    public static final int ABI_MAJOR = {};\n    public static final int ABI_MINOR = {};\n",
        vanillite_abi::ABI_MAJOR,
        vanillite_abi::ABI_MINOR
    );
    // Constant groups in declaration order.
    let mut groups: Vec<(&str, Vec<&layout::ConstLayout>)> = Vec::new();
    for c in layout::CONSTS {
        let (group, _) = c.name.split_once("::").expect("group::NAME");
        match groups.iter_mut().find(|(g, _)| *g == group) {
            Some((_, list)) => list.push(c),
            None => groups.push((group, vec![c])),
        }
    }
    for (group, consts) in groups {
        let _ = writeln!(
            out,
            "    /** Constants {group}. */\n    public static final class {group} {{\n        private {group}() {{}}"
        );
        for c in consts {
            let (_, name) = c.name.split_once("::").expect("group::NAME");
            let carrier = g.carrier(&g.ty(c.ty));
            let suffix = if carrier == "long" { "L" } else { "" };
            let value = if carrier == "int" {
                (c.value as i32).to_string()
            } else {
                c.value.to_string()
            };
            let _ = writeln!(
                out,
                "        public static final {carrier} {name} = {value}{suffix};"
            );
        }
        out.push_str("    }\n\n");
    }
    for s in layout::STRUCTS {
        let _ = writeln!(
            out,
            "    /** Record {}: layout, size and field offsets. */\n    public static final class {} {{\n        private {}() {{}}\n        public static final long SIZE = {};",
            s.name, s.name, s.name, s.size
        );
        let mut members = Vec::new();
        let mut at = 0;
        for f in s.fields {
            if f.offset > at {
                members.push(format!("MemoryLayout.paddingLayout({})", f.offset - at));
            }
            let ty = g.ty(f.ty);
            members.push(format!("{}.withName(\"{}\")", g.layout(&ty), f.name));
            at = f.offset + g.size(&ty);
            let _ = writeln!(
                out,
                "        public static final long {} = {};",
                ident(f.name),
                f.offset
            );
        }
        if s.size > at {
            members.push(format!("MemoryLayout.paddingLayout({})", s.size - at));
        }
        let _ = writeln!(
            out,
            "        public static final StructLayout LAYOUT = MemoryLayout.structLayout(\n            {}).withName(\"{}\");\n    }}\n",
            members.join(",\n            "),
            s.name
        );
    }
    out.push_str("    /** Descriptors of the callback types (for upcall stubs). */\n    public static final class Fn {\n        private Fn() {}\n");
    for t in layout::TYPES {
        let def: String = t
            .definition
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect();
        if def.contains("fn(") {
            let (params, ret) = g.signature(t.definition);
            let _ = writeln!(
                out,
                "        public static final FunctionDescriptor {} = {};",
                t.name,
                g.descriptor(&params, &ret)
            );
        }
    }
    out.push_str("    }\n}\n");
    out
}

fn host_java(g: &Gen) -> String {
    let mut out = String::new();
    header(&mut out);
    out.push_str(
        "import dev.vanillite.jvm.Native;\nimport dev.vanillite.jvm.abi.Abi.*;\n\n\
         /** The host functions of the plugin ABI as Java methods (one downcall each). */\n\
         @SuppressWarnings({\"unused\", \"checkstyle:all\"})\npublic final class Host {\n    private Host() {}\n\n",
    );
    let host = g.structs["HostApi"];
    for f in host.fields {
        let def: String = f.ty.chars().filter(|c| !c.is_whitespace()).collect();
        if !def.contains("fn(") {
            continue;
        }
        let (params, ret) = g.signature(f.ty);
        let desc = g.descriptor(&params, &ret);
        let critical = CRITICAL.contains(&f.name);
        let mut variants = vec![(f.name.to_owned(), critical)];
        if critical {
            variants.push((format!("{}_transition", f.name), false));
        }
        for (name, crit) in variants {
            let _ = writeln!(
                out,
                "    private static final class H_{name} {{\n        static final MethodHandle H = Native.link(Abi.HostApi.{}, {desc}, {crit});\n    }}",
                ident(f.name)
            );
            let decl = params
                .iter()
                .enumerate()
                .map(|(i, p)| format!("{} a{i}", g.carrier(p)))
                .collect::<Vec<_>>()
                .join(", ");
            let pass = (0..params.len())
                .map(|i| format!("a{i}"))
                .collect::<Vec<_>>()
                .join(", ");
            let (rt, call) = match &ret {
                Some(r) => (
                    g.carrier(r),
                    format!("return ({}) H_{name}.H.invokeExact({pass});", g.carrier(r)),
                ),
                None => ("void", format!("H_{name}.H.invokeExact({pass});")),
            };
            // A call that may change the world or call back into plugins starts and ends a new
            // epoch: values read from the host before it are stale afterwards.
            let (enter, leave) = if critical {
                ("", "")
            } else {
                (
                    "Native.epoch++;\n        ",
                    " finally {\n            Native.epoch++;\n        }",
                )
            };
            let _ = writeln!(
                out,
                "    /** Host function {} ({}). */\n    public static {rt} {}({decl}) {{\n        {enter}try {{\n            {call}\n        }} catch (Throwable t) {{\n            throw Native.rethrow(t);\n        }}{leave}\n    }}\n",
                f.name,
                if crit { "critical" } else { "normal" },
                ident(&name)
            );
        }
    }
    out.push_str("}\n");
    out
}

fn main() {
    let mut args = std::env::args().skip(1);
    let mut check = false;
    let mut dir = None;
    for a in args.by_ref() {
        if a == "--check" {
            check = true;
        } else {
            dir = Some(PathBuf::from(a));
        }
    }
    let dir = dir
        .expect("usage: vanillite-jvm-bindgen [--check] <java source dir>")
        .join("dev/vanillite/jvm/abi");
    let g = Gen {
        aliases: layout::TYPES
            .iter()
            .map(|t| (t.name, t.definition.to_owned()))
            .collect(),
        structs: layout::STRUCTS.iter().map(|s| (s.name, s)).collect(),
    };
    let files = [("Abi.java", abi_java(&g)), ("Host.java", host_java(&g))];
    let mut stale = false;
    for (name, text) in files {
        let path = dir.join(name);
        if check {
            if std::fs::read_to_string(&path).ok().as_deref() != Some(text.as_str()) {
                eprintln!("{} is out of date: run scripts/bindgen.sh", path.display());
                stale = true;
            }
        } else {
            std::fs::create_dir_all(&dir).expect("create output directory");
            std::fs::write(&path, text).expect("write binding");
        }
    }
    if stale {
        std::process::exit(1);
    }
}
