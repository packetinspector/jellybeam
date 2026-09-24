//! Standard UniFFI bindgen binary; requires the `cli` feature (`cargo run
//! --features cli --bin uniffi-bindgen -- ...`), kept off the default build.

fn main() {
    uniffi::uniffi_bindgen_main();
}
