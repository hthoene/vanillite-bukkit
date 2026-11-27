//! `vanillite-jvm`: hosts a Java virtual machine inside Vanillite as an ordinary native plugin.
//!
//! The library talks to the server only through the public plugin ABI (`vanillite-abi`). On
//! `load` it opens `libjvm` of a Java runtime, starts a JVM in the server process and hands the
//! host's function table to `dev.vanillite.jvm.Boot`. From there on Java calls the host
//! directly through the Foreign Function & Memory API with bindings generated from the ABI's
//! layout data (`vanillite-jvm-bindgen`), and the host calls Java through upcall stubs. Nothing
//! of this library knows Bukkit: which Java code runs is configured in `config.toml`.
//!
//! Without this plugin installed no Java code, no JVM and no Java library is part of the
//! server process.

use std::ffi::{CString, c_void};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};

use jni_sys::{JNI_OK, JNIEnv, JavaVM, JavaVMInitArgs, JavaVMOption, jint, jvalue};
use vanillite_abi::{self as abi, Status, status};

/// The ABI version and target this library was built for.
struct Info(abi::PluginInfo);

// SAFETY: the record only holds pointers to static strings and functions.
unsafe impl Sync for Info {}

/// The oldest host ABI minor version this library runs on. Java checks the host table's size
/// before it links a function, so newer functions fail only when they are called.
const MIN_ABI_MINOR: u16 = 5;

const TARGET: &str = env!("VANILLITE_JVM_TARGET");
const VERSION: &str = env!("CARGO_PKG_VERSION");

static INFO: Info = Info(abi::PluginInfo {
    size: size_of::<abi::PluginInfo>() as u32,
    abi_major: abi::ABI_MAJOR,
    abi_minor: MIN_ABI_MINOR,
    panic_strategy: abi::panic_strategy::UNWIND,
    data_version: 0,
    sdk_version: abi::Str {
        ptr: VERSION.as_ptr(),
        len: VERSION.len(),
    },
    target: abi::Str {
        ptr: TARGET.as_ptr(),
        len: TARGET.len(),
    },
    load,
    enable,
    disable,
    save_state: None,
    restore_state: None,
});

/// The plugin's entry point.
#[unsafe(no_mangle)]
pub extern "C" fn vanillite_plugin_abi() -> *const abi::PluginInfo {
    &INFO.0
}

/// Calls shared with Java: `enable` and `disable` of the Java side (upcall stubs Java writes
/// in `Boot.load`), then [`noop`] and [`upcall_loop`] for measurements.
static CALLS: [AtomicUsize; 4] = [
    AtomicUsize::new(0),
    AtomicUsize::new(0),
    AtomicUsize::new(0),
    AtomicUsize::new(0),
];

/// A native function that does nothing, to measure the cost of a Java→native call.
extern "C" fn noop(x: i32) -> i32 {
    x
}

/// Calls `f` `n` times and returns the elapsed nanoseconds, to measure native→Java calls.
extern "C" fn upcall_loop(f: extern "C" fn(i32) -> i32, n: u64) -> u64 {
    let start = std::time::Instant::now();
    let mut x = 0;
    for _ in 0..n {
        x = f(x);
    }
    std::hint::black_box(x);
    start.elapsed().as_nanos() as u64
}

/// Defaults of `plugins/<id>/config.toml`.
const DEFAULT_CONFIG: &str = r#"# vanillite-jvm: the Java virtual machine inside Vanillite.
[jvm]
# Java home of a Java 25 runtime; empty: $JAVA_HOME, then `java` on the PATH.
java-home = ""
# Jars on the JVM's class path, relative to this directory (separated by ':'; `dir/**` adds
# every jar below `dir`). For an AOT cache (-XX:AOTCache) put everything here, e.g.
# "vanillite-jvm.jar:vanillite-bukkit.jar:libraries/**".
class-path = "vanillite-jvm.jar"
# Options of the JVM (separated by spaces). -Xrs is always added: signals stay with the server.
options = "-XX:+UseSerialGC -Xms16m"
# Jar and class of the Java plugin Boot starts (implements dev.vanillite.jvm.JvmPlugin).
main-jar = "vanillite-bukkit.jar"
main-class = "dev.vanillite.bukkit.boot.Bootstrap"
"#;

/// The host table, kept for logging.
static HOST: AtomicUsize = AtomicUsize::new(0);

fn log(plugin: abi::PluginId, level: u32, message: &str) {
    let host = HOST.load(Ordering::Relaxed) as *const abi::HostApi;
    // SAFETY: the host table outlives the plugin.
    if let Some(host) = unsafe { host.as_ref() } {
        // SAFETY: the string is valid for the call.
        unsafe { (host.log)(plugin, level, str(message)) };
    }
}

fn str(s: &str) -> abi::Str {
    abi::Str {
        ptr: s.as_ptr(),
        len: s.len(),
    }
}

/// Reads a string through a `StrBuf` call, growing the buffer when it was too small.
fn read_string(call: impl Fn(*mut abi::StrBuf) -> Status) -> Option<String> {
    let mut buf = vec![0u8; 256];
    loop {
        let mut out = abi::StrBuf {
            ptr: buf.as_mut_ptr(),
            cap: buf.len(),
            len: 0,
        };
        if call(&mut out) != status::OK {
            return None;
        }
        if out.len <= buf.len() {
            buf.truncate(out.len);
            return String::from_utf8(buf).ok();
        }
        buf.resize(out.len, 0);
    }
}

/// Finds the Java home: the configured one, `$JAVA_HOME`, or the one of `java` on the `PATH`.
fn java_home(configured: &str) -> Option<PathBuf> {
    if !configured.is_empty() {
        return Some(PathBuf::from(configured));
    }
    if let Some(home) = std::env::var_os("JAVA_HOME").filter(|h| !h.is_empty()) {
        return Some(PathBuf::from(home));
    }
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join("java"))
        .find(|java| java.is_file())
        .and_then(|java| std::fs::canonicalize(java).ok())
        .and_then(|java| java.parent()?.parent().map(Path::to_path_buf))
}

/// `libjvm`, loaded once and never unloaded (a JVM cannot be started twice in one process).
static LIBJVM: std::sync::OnceLock<libloading::Library> = std::sync::OnceLock::new();

type CreateJavaVm =
    unsafe extern "system" fn(*mut *mut JavaVM, *mut *mut c_void, *mut c_void) -> jint;

unsafe extern "C" fn load(host: *const abi::HostApi, plugin: abi::PluginId) -> Status {
    match std::panic::catch_unwind(|| start(host, plugin)) {
        Ok(Ok(())) => status::OK,
        Ok(Err(message)) => {
            log(plugin, abi::log_level::ERROR, &message);
            status::ERROR
        }
        Err(_) => status::PANICKED,
    }
}

fn start(host: *const abi::HostApi, plugin: abi::PluginId) -> Result<(), String> {
    // SAFETY: the host passes a valid table that outlives the plugin.
    let api = unsafe { host.as_ref() }.ok_or("no host table")?;
    HOST.store(host as usize, Ordering::Relaxed);
    // SAFETY: valid strings for the calls.
    unsafe { (api.config_load)(plugin, str(DEFAULT_CONFIG)) };
    let config = |key: &str| {
        read_string(|out| unsafe { (api.config_string)(plugin, str(key), out) }).unwrap_or_default()
    };
    let dir = PathBuf::from(
        read_string(|out| unsafe { (api.data_dir)(plugin, out) }).ok_or("no data directory")?,
    );
    let home = java_home(&config("jvm.java-home"))
        .ok_or("no Java runtime found: set jvm.java-home in config.toml or JAVA_HOME")?;
    let libjvm = home
        .join("lib/server")
        .join(libloading::library_filename("jvm"));
    let lib = match LIBJVM.get() {
        Some(_) => return Err("a JVM was already started in this process".to_owned()),
        None => {
            // SAFETY: libjvm's initialisers are safe to run.
            let lib = unsafe { libloading::Library::new(&libjvm) }
                .map_err(|e| format!("cannot load {}: {e}", libjvm.display()))?;
            LIBJVM.get_or_init(|| lib)
        }
    };
    // SAFETY: the symbol has the JNI invocation signature.
    let create: libloading::Symbol<CreateJavaVm> = unsafe { lib.get(b"JNI_CreateJavaVM\0") }
        .map_err(|e| format!("{}: {e}", libjvm.display()))?;

    let mut entries = Vec::new();
    for entry in config("jvm.class-path")
        .split(':')
        .filter(|p| !p.is_empty())
    {
        match entry.strip_suffix("/**") {
            Some(sub) => jars(&dir.join(sub), &mut entries),
            None => entries.push(dir.join(entry)),
        }
    }
    let class_path = entries
        .iter()
        .map(|p| p.to_string_lossy())
        .collect::<Vec<_>>()
        .join(":");
    let mut options = vec![
        format!("-Djava.class.path={class_path}"),
        "-Xrs".to_owned(),
        "--enable-native-access=ALL-UNNAMED".to_owned(),
        format!("-Dvanillite.jvm.dir={}", dir.display()),
        format!(
            "-Dvanillite.jvm.main-jar={}",
            dir.join(config("jvm.main-jar")).display()
        ),
        format!("-Dvanillite.jvm.main-class={}", config("jvm.main-class")),
    ];
    options.extend(config("jvm.options").split_whitespace().map(str::to_owned));
    if let Ok(extra) = std::env::var("VANILLITE_JVM_OPTIONS") {
        options.extend(extra.split_whitespace().map(str::to_owned));
    }
    let strings = options
        .iter()
        .map(|o| CString::new(o.as_str()).map_err(|_| format!("bad JVM option {o:?}")))
        .collect::<Result<Vec<_>, _>>()?;
    let mut raw = strings
        .iter()
        .map(|s| JavaVMOption {
            optionString: s.as_ptr().cast_mut(),
            extraInfo: std::ptr::null_mut(),
        })
        .collect::<Vec<_>>();
    let mut args = JavaVMInitArgs {
        version: 0x0015_0000, // JNI_VERSION_21
        nOptions: raw.len() as jint,
        options: raw.as_mut_ptr(),
        ignoreUnrecognized: false,
    };
    let mut vm: *mut JavaVM = std::ptr::null_mut();
    let mut env: *mut c_void = std::ptr::null_mut();
    let started = std::time::Instant::now();
    // SAFETY: valid arguments for the call.
    let rc = unsafe { create(&mut vm, &mut env, (&raw mut args).cast()) };
    if rc != JNI_OK {
        return Err(format!(
            "JNI_CreateJavaVM failed ({rc}), options {options:?}"
        ));
    }
    let env = env.cast::<JNIEnv>();
    CALLS[2].store(noop as *const () as usize, Ordering::Relaxed);
    CALLS[3].store(upcall_loop as *const () as usize, Ordering::Release);
    // SAFETY: a valid environment of the current thread.
    let result = unsafe { call_boot(env, host, plugin) };
    log(
        plugin,
        abi::log_level::INFO,
        &format!(
            "JVM from {} started in {} ms",
            home.display(),
            started.elapsed().as_millis()
        ),
    );
    result
}

/// Adds the jars below `dir` in a stable order (class path entries `<dir>/**`).
fn jars(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(read) = std::fs::read_dir(dir) else {
        return;
    };
    let mut paths: Vec<PathBuf> = read.filter_map(|e| e.ok().map(|e| e.path())).collect();
    paths.sort();
    for path in paths {
        if path.is_dir() {
            jars(&path, out);
        } else if path.extension().is_some_and(|e| e == "jar") {
            out.push(path);
        }
    }
}

/// Calls `dev.vanillite.jvm.Boot.load(host, plugin, calls)`.
unsafe fn call_boot(
    env: *mut JNIEnv,
    host: *const abi::HostApi,
    plugin: abi::PluginId,
) -> Result<(), String> {
    // SAFETY: `env` is valid; the JNI table is filled by the JVM.
    unsafe {
        let f = &**env;
        let class = (f.v1_1.FindClass)(env, c"dev/vanillite/jvm/Boot".as_ptr());
        if class.is_null() {
            (f.v1_1.ExceptionDescribe)(env);
            return Err("class dev.vanillite.jvm.Boot not found on jvm.class-path".to_owned());
        }
        let method = (f.v1_1.GetStaticMethodID)(env, class, c"load".as_ptr(), c"(JIJ)I".as_ptr());
        if method.is_null() {
            (f.v1_1.ExceptionDescribe)(env);
            return Err("dev.vanillite.jvm.Boot.load(long, int, long) not found".to_owned());
        }
        let args = [
            jvalue { j: host as i64 },
            jvalue { i: plugin as i32 },
            jvalue {
                j: CALLS.as_ptr() as i64,
            },
        ];
        let rc = (f.v1_1.CallStaticIntMethodA)(env, class, method, args.as_ptr());
        if (f.v1_2.ExceptionCheck)(env) {
            (f.v1_1.ExceptionDescribe)(env);
            return Err("dev.vanillite.jvm.Boot.load threw".to_owned());
        }
        if rc != status::OK {
            return Err(format!("dev.vanillite.jvm.Boot.load returned {rc}"));
        }
    }
    Ok(())
}

type Lifecycle = unsafe extern "C" fn(*mut abi::Context) -> Status;

unsafe fn call(slot: usize, ctx: *mut abi::Context) -> Status {
    let f = CALLS[slot].load(Ordering::Acquire);
    if f == 0 {
        return status::OK;
    }
    // SAFETY: Java stored an upcall stub of this signature.
    unsafe { std::mem::transmute::<usize, Lifecycle>(f)(ctx) }
}

unsafe extern "C" fn enable(ctx: *mut abi::Context) -> Status {
    // SAFETY: forwards the callback's context.
    unsafe { call(0, ctx) }
}

unsafe extern "C" fn disable(ctx: *mut abi::Context) -> Status {
    // SAFETY: forwards the callback's context.
    unsafe { call(1, ctx) }
}
