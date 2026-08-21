#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORIGINAL_WRAPPER="$SOURCE_DIR/start-mcav.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

make_fixture() {
  local fixture="$1"
  mkdir -p "$fixture/mcav-vj/bin/linux-amd64" "$fixture/mcav-vj/bin/linux-arm64" "$fixture/plugins"
  cp "$ORIGINAL_WRAPPER" "$fixture/mcav-vj/start-mcav.sh"
  chmod +x "$fixture/mcav-vj/start-mcav.sh"
  printf '26.1\n' > "$fixture/mcav-vj/VERSION"

  cat > "$fixture/mcav-vj/fake-vj" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "--bootstrap-pterodactyl" ]]; then
  if [[ "${FAKE_BOOTSTRAP_FAIL:-0}" == "1" ]]; then exit 17; fi
  mkdir -p "$MCAV_ROOT/state"
  printf 'MINECRAFT_WS_SECRET=test-secret-not-logged\n' > "$MCAV_ROOT/state/runtime.env"
  : > "$MCAV_ROOT/state/dj_auth.json"
  : > "$MCAV_ROOT/state/tls.crt"
  : > "$MCAV_ROOT/state/tls.key"
  exit 0
fi
printf '%s\n' "$@" > "$VJ_CAPTURE"
if [[ "${FAKE_VJ_FAIL:-0}" == "1" ]]; then exit 23; fi
sleep 1
SCRIPT
  chmod +x "$fixture/mcav-vj/fake-vj"
  cp "$fixture/mcav-vj/fake-vj" "$fixture/mcav-vj/bin/linux-amd64/audioviz-vj"
  cp "$fixture/mcav-vj/fake-vj" "$fixture/mcav-vj/bin/linux-arm64/audioviz-vj"

  cat > "$fixture/paper" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$0" "$@" > "$PAPER_CAPTURE"
SCRIPT
  chmod +x "$fixture/paper"
}

run_wrapper() {
  local fixture="$1"
  shift
  MCAV_ROOT="$fixture/mcav-vj" \
  MCAV_ARCH_OVERRIDE="${MCAV_ARCH_OVERRIDE:-x86_64}" \
  MCAV_STARTUP_WAIT_SECONDS="0.1" \
  PAPER_CAPTURE="$fixture/paper.args" \
  VJ_CAPTURE="$fixture/vj.args" \
  "$fixture/mcav-vj/start-mcav.sh" -- "$@"
}

test_exact_paper_arguments_and_secure_vj_flags() {
  local fixture="$TEST_ROOT/exact"
  make_fixture "$fixture"
  run_wrapper "$fixture" "$fixture/paper" -Xms128M -jar 'server file.jar' nogui

  mapfile -t paper_args < "$fixture/paper.args"
  [[ "${paper_args[1]}" == '-Xms128M' ]] || fail 'Paper JVM argument changed'
  [[ "${paper_args[3]}" == 'server file.jar' ]] || fail 'Paper quoted argument changed'
  [[ "${paper_args[4]}" == 'nogui' ]] || fail 'Paper final argument changed'
  grep -Fx -- '--tls-cert' "$fixture/vj.args" >/dev/null || fail 'TLS certificate flag missing'
  grep -Fx -- '--tls-key' "$fixture/vj.args" >/dev/null || fail 'TLS key flag missing'
  grep -Fx -- '--http-host' "$fixture/vj.args" >/dev/null || fail 'HTTP bind flag missing'
  ! grep -Fx -- '--no-auth' "$fixture/vj.args" >/dev/null || fail 'insecure --no-auth present'
}

test_arm64_runtime_selection() {
  local fixture="$TEST_ROOT/arm64"
  make_fixture "$fixture"
  printf '#!/usr/bin/env bash\nprintf arm64 > "$ARCH_CAPTURE"\nexit 1\n' \
    > "$fixture/mcav-vj/bin/linux-arm64/audioviz-vj"
  chmod +x "$fixture/mcav-vj/bin/linux-arm64/audioviz-vj"
  ARCH_CAPTURE="$fixture/arch" MCAV_ARCH_OVERRIDE=aarch64 run_wrapper "$fixture" "$fixture/paper"
  [[ "$(cat "$fixture/arch")" == 'arm64' ]] || fail 'ARM64 runtime was not selected'
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after ARM64 bootstrap failure'
}

test_bootstrap_failure_still_starts_paper() {
  local fixture="$TEST_ROOT/bootstrap-failure"
  make_fixture "$fixture"
  FAKE_BOOTSTRAP_FAIL=1 run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after bootstrap failure'
}

test_vj_bind_failure_still_starts_paper() {
  local fixture="$TEST_ROOT/vj-failure"
  make_fixture "$fixture"
  FAKE_VJ_FAIL=1 run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after VJ failure'
}

test_missing_paper_command_is_rejected() {
  local fixture="$TEST_ROOT/missing-paper"
  make_fixture "$fixture"
  if "$fixture/mcav-vj/start-mcav.sh" --; then
    fail 'empty Paper command was accepted'
  fi
  [[ ! -f "$fixture/paper.args" ]] || fail 'Paper sentinel unexpectedly ran'
}

test_exact_paper_arguments_and_secure_vj_flags
test_arm64_runtime_selection
test_bootstrap_failure_still_starts_paper
test_vj_bind_failure_still_starts_paper
test_missing_paper_command_is_rejected
printf 'start-mcav.sh integration tests passed\n'
