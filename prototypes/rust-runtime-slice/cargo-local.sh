#!/usr/bin/env bash
set -euo pipefail
slice_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ -x "$slice_dir/.tools/cargo/bin/cargo" ]]; then
    export CARGO_HOME="$slice_dir/.tools/cargo"
    export RUSTUP_HOME="$slice_dir/.tools/rustup"
    if [[ "${1:-}" == "--version" ]]; then exec "$CARGO_HOME/bin/cargo" --version; fi
    exec "$CARGO_HOME/bin/cargo" "$@" --manifest-path "$slice_dir/Cargo.toml"
fi
if [[ "${1:-}" == "--version" ]]; then exec cargo --version; fi
exec cargo "$@" --manifest-path "$slice_dir/Cargo.toml"
