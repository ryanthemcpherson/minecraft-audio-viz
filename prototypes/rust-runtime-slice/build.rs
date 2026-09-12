use std::{env, fs, path::PathBuf};

fn main() {
    // Compile production DSP, without linking the Tauri/audio-device application.
    // Extract its data types verbatim so changes cannot silently leave stale copies.
    let root = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../..");
    let config_path = root.join("dj_client/src-tauri/src/audio/mod.rs");
    let result_path = root.join("dj_client/src-tauri/src/audio/capture.rs");
    for path in [&config_path, &result_path] {
        println!("cargo:rerun-if-changed={}", path.display());
    }
    let config = fs::read_to_string(config_path).unwrap();
    let result = fs::read_to_string(result_path).unwrap();
    let config = &config[config.find("#[derive(Debug, Clone)]").unwrap()..];
    let start = result.find("#[derive(Debug, Clone, Default)]").unwrap();
    let end = start + result[start..].find("\n}").unwrap() + 2;
    fs::write(
        PathBuf::from(env::var("OUT_DIR").unwrap()).join("dsp_types.rs"),
        format!(
            "{config}\npub mod capture {{ {} }}\n#[path = {:?}] pub mod fft;",
            &result[start..end],
            root.join("dj_client/src-tauri/src/audio/fft.rs")
                .canonicalize()
                .unwrap()
        ),
    )
    .unwrap();
}
