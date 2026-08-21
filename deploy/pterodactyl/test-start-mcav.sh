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
  printf '%s\n' "$@" > "$BOOTSTRAP_CAPTURE"
  if [[ "${FAKE_BOOTSTRAP_FAIL:-0}" == "1" ]]; then exit 17; fi
  identity_dir="$MCAV_ROOT/state/identity-generations/test-generation"
  mkdir -p "$identity_dir"
  printf 'MINECRAFT_WS_SECRET=test-secret-not-logged\n' > "$identity_dir/runtime.env"
  : > "$identity_dir/dj_auth.json"
  : > "$identity_dir/tls.crt"
  : > "$identity_dir/tls.key"
  canonical_host="${FAKE_CANONICAL_HOST:-8.8.8.8}"
  endpoint_host="$canonical_host"
  if [[ "$canonical_host" == *:* ]]; then endpoint_host="[$canonical_host]"; fi
  printf 'ADMIN_URL=https://%s:8080/\nPREVIEW_URL=https://%s:8080/preview/\nDJ_ENDPOINT=wss://%s:25808\n' \
    "$endpoint_host" "$endpoint_host" "$endpoint_host" > "$identity_dir/FIRST_LOGIN.txt"
  printf '{"public_host":"%s"}\n' "$canonical_host" > "$identity_dir/identity.json"
  ln -s 'identity-generations/test-generation' "$MCAV_ROOT/state/current-identity"
  if [[ "${FAKE_BOOTSTRAP_FAIL_AFTER_IDENTITY:-0}" == "1" ]]; then exit 18; fi
  exit 0
fi
printf '%s\n' "$@" > "$VJ_CAPTURE"
printf '%s' "${MINECRAFT_WS_SECRET:-}" > "$SECRET_CAPTURE"
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
  BOOTSTRAP_CAPTURE="$fixture/bootstrap.args" \
  VJ_CAPTURE="$fixture/vj.args" \
  SECRET_CAPTURE="$fixture/secret.env" \
  MCAV_PUBLIC_HOST="${TEST_MCAV_PUBLIC_HOST-8.8.8.8}" \
  FAKE_CANONICAL_HOST="${FAKE_CANONICAL_HOST-${TEST_MCAV_PUBLIC_HOST-8.8.8.8}}" \
  "$fixture/mcav-vj/start-mcav.sh" -- "$@"
}

assert_arg_value() {
  local capture="$1"
  local expected_flag="$2"
  local expected_value="$3"
  mapfile -t captured_args < "$capture"
  local index
  for ((index = 0; index < ${#captured_args[@]}; index++)); do
    if [[ "${captured_args[$index]}" == "$expected_flag" ]]; then
      [[ "${captured_args[$((index + 1))]:-}" == "$expected_value" ]] || \
        fail "$expected_flag did not receive $expected_value"
      return
    fi
  done
  fail "$expected_flag was not supplied"
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
  assert_arg_value "$fixture/bootstrap.args" '--public-host' '8.8.8.8'
  assert_arg_value "$fixture/bootstrap.args" '--http-port' '8080'
  assert_arg_value "$fixture/bootstrap.args" '--port' '25808'
  grep -Fx -- '--unified-web' "$fixture/bootstrap.args" >/dev/null || \
    fail 'bootstrap unified web flag missing'
  assert_arg_value "$fixture/vj.args" '--http-port' '8080'
  assert_arg_value "$fixture/vj.args" '--port' '25808'
  local identity_dir="$fixture/mcav-vj/state/identity-generations/test-generation"
  assert_arg_value "$fixture/vj.args" '--auth-file' "$identity_dir/dj_auth.json"
  assert_arg_value "$fixture/vj.args" '--tls-cert' "$identity_dir/tls.crt"
  assert_arg_value "$fixture/vj.args" '--tls-key' "$identity_dir/tls.key"
  assert_arg_value "$fixture/vj.args" '--public-origin' 'https://8.8.8.8:8080'
  grep -Fx -- '--unified-web' "$fixture/vj.args" >/dev/null || fail 'unified web flag missing'
  ! grep -Fx -- '--broadcast-port' "$fixture/vj.args" >/dev/null || \
    fail 'broadcast port must not be supplied in unified mode'
  ! grep -Fx -- '--no-auth' "$fixture/vj.args" >/dev/null || fail 'insecure --no-auth present'
  [[ "$(cat "$fixture/secret.env")" == 'test-secret-not-logged' ]] || \
    fail 'shared secret environment was not passed to VJ'
}

test_ipv6_public_origin_is_bracketed() {
  local fixture="$TEST_ROOT/ipv6"
  make_fixture "$fixture"
  TEST_MCAV_PUBLIC_HOST='2606:4700:4700::1111' run_wrapper "$fixture" "$fixture/paper"

  assert_arg_value \
    "$fixture/vj.args" \
    '--public-origin' \
    'https://[2606:4700:4700::1111]:8080'
}

test_runtime_origin_uses_canonical_generation_endpoints() {
  local fixture="$TEST_ROOT/canonical-whitespace-ipv4"
  make_fixture "$fixture"
  TEST_MCAV_PUBLIC_HOST=' 8.8.8.8 ' FAKE_CANONICAL_HOST='8.8.8.8' \
    run_wrapper "$fixture" "$fixture/paper"
  assert_arg_value "$fixture/vj.args" '--public-origin' 'https://8.8.8.8:8080'

  fixture="$TEST_ROOT/canonical-expanded-ipv6"
  make_fixture "$fixture"
  TEST_MCAV_PUBLIC_HOST='2606:4700:4700:0:0:0:0:1111' \
    FAKE_CANONICAL_HOST='2606:4700:4700::1111' \
    run_wrapper "$fixture" "$fixture/paper"
  assert_arg_value \
    "$fixture/vj.args" \
    '--public-origin' \
    'https://[2606:4700:4700::1111]:8080'
}

test_missing_public_host_still_starts_paper() {
  local fixture="$TEST_ROOT/missing-public-host"
  make_fixture "$fixture"
  TEST_MCAV_PUBLIC_HOST='' run_wrapper "$fixture" "$fixture/paper" --nogui

  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start without MCAV_PUBLIC_HOST'
  [[ ! -f "$fixture/bootstrap.args" ]] || fail 'bootstrap ran without MCAV_PUBLIC_HOST'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ ran without MCAV_PUBLIC_HOST'
}

test_public_port_collision_still_starts_paper() {
  local fixture="$TEST_ROOT/port-collision"
  make_fixture "$fixture"
  HTTP_PORT=25808 VJ_SERVER_PORT=25808 run_wrapper "$fixture" "$fixture/paper" --nogui

  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after public port collision'
  [[ ! -f "$fixture/bootstrap.args" ]] || fail 'bootstrap ran with colliding public ports'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ ran with colliding public ports'
}

test_topology_overrides_fail_closed_and_still_start_paper() {
  local fixture="$TEST_ROOT/http-override"
  make_fixture "$fixture"
  HTTP_PORT=8081 run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after HTTP port override'
  [[ ! -f "$fixture/bootstrap.args" ]] || fail 'bootstrap accepted HTTP port override'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ accepted HTTP port override'

  fixture="$TEST_ROOT/dj-override"
  make_fixture "$fixture"
  VJ_SERVER_PORT=9000 run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after DJ port override'
  [[ ! -f "$fixture/bootstrap.args" ]] || fail 'bootstrap accepted DJ port override'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ accepted DJ port override'

  fixture="$TEST_ROOT/unified-disabled"
  make_fixture "$fixture"
  UNIFIED_WEB=false run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start with unified mode disabled'
  [[ ! -f "$fixture/bootstrap.args" ]] || fail 'bootstrap accepted split web mode'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ accepted split web mode'

  fixture="$TEST_ROOT/broadcast-override"
  make_fixture "$fixture"
  BROADCAST_PORT=8766 run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start with broadcast override'
  [[ ! -f "$fixture/bootstrap.args" ]] || fail 'bootstrap accepted broadcast override'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ accepted broadcast override'
}

test_example_environment_uses_two_public_ports() {
  local fixture="$TEST_ROOT/example-environment"
  make_fixture "$fixture"
  cp "$SOURCE_DIR/mcav.env.example" "$fixture/mcav-vj/mcav.env"
  run_wrapper "$fixture" "$fixture/paper"

  assert_arg_value "$fixture/vj.args" '--http-port' '8080'
  assert_arg_value "$fixture/vj.args" '--port' '25808'
  grep -Fx -- '--unified-web' "$fixture/vj.args" >/dev/null || \
    fail 'example environment did not enable unified web mode'
  ! grep -Fx -- '--broadcast-port' "$fixture/vj.args" >/dev/null || \
    fail 'example environment exposed the legacy broadcast port'
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
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ started after bootstrap failure'
}

test_post_identity_bootstrap_failure_starts_only_paper() {
  local fixture="$TEST_ROOT/post-identity-bootstrap-failure"
  make_fixture "$fixture"
  FAKE_BOOTSTRAP_FAIL_AFTER_IDENTITY=1 run_wrapper "$fixture" "$fixture/paper" --nogui
  [[ -f "$fixture/paper.args" ]] || fail 'Paper did not start after identity recovery failure'
  [[ ! -f "$fixture/vj.args" ]] || fail 'VJ started after identity recovery failure'
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
test_ipv6_public_origin_is_bracketed
test_runtime_origin_uses_canonical_generation_endpoints
test_missing_public_host_still_starts_paper
test_public_port_collision_still_starts_paper
test_topology_overrides_fail_closed_and_still_start_paper
test_example_environment_uses_two_public_ports
test_arm64_runtime_selection
test_bootstrap_failure_still_starts_paper
test_post_identity_bootstrap_failure_starts_only_paper
test_vj_bind_failure_still_starts_paper
test_missing_paper_command_is_rejected
printf 'start-mcav.sh integration tests passed\n'
