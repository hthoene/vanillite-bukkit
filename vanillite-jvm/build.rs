//! Records the target triple, which the host compares with its own before loading the plugin.

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    println!("cargo:rustc-env=VANILLITE_JVM_TARGET={target}");
    println!("cargo:rerun-if-changed=build.rs");
}
