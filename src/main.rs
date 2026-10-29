use rivium_host::Code;

/// Laptop entry point. The same application is exported as a JNI library for
/// Android in `lib.rs`; nothing below the host boundary knows the difference.
fn main() -> Code {
    rivium_host::process::run::<pocket::app::Pocket>()
}
