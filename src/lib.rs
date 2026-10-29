pub mod app;
pub mod http;
pub mod job;
pub mod media;
pub mod queue;

// Android loads this library and calls into `Pocket` through the embedded host.
rivium_jni::export!(class = "dev/kouragita/pocket/RiviumBridge", app = crate::app::Pocket);
