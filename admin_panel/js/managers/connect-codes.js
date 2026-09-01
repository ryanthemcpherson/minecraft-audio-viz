const CONNECT_CODE_PATTERN = /^[A-Z]{4}-[A-HJ-KM-NP-Z2-9]{4}$/;
const FINGERPRINT_PATTERN = /^[0-9A-F]{64}$/;
const MAX_INVITE_TTL_SECONDS = 86_400;
const MAX_CLOCK_SKEW_SECONDS = 300;

function requireObject(value, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${label} is required`);
  }
  return value;
}

export function normalizeDjServerUrl(publicUrl) {
  if (typeof publicUrl !== 'string' || publicUrl.length === 0) {
    throw new TypeError('A configured public URL is required');
  }

  let parsed;
  try {
    parsed = new URL(publicUrl);
  } catch {
    throw new TypeError('The configured public URL is invalid');
  }
  if (
    parsed.protocol !== 'https:'
    || !parsed.hostname
    || parsed.username
    || parsed.password
    || parsed.search
    || parsed.hash
    || (parsed.pathname !== '' && parsed.pathname !== '/')
  ) {
    throw new TypeError('The configured public URL must be a credential-free HTTPS origin');
  }

  parsed.protocol = 'wss:';
  parsed.pathname = '/ws/dj';
  return parsed.toString();
}

export function buildInvite(runtimeValue, codeValue, options = {}) {
  const runtime = requireObject(runtimeValue, 'Runtime information');
  const code = requireObject(codeValue, 'Connect code');
  const nowSeconds = options.nowSeconds ?? Math.floor(Date.now() / 1000);
  if (!Number.isInteger(nowSeconds) || nowSeconds < 1) {
    throw new TypeError('Current time is invalid');
  }
  if (typeof code.code !== 'string' || !CONNECT_CODE_PATTERN.test(code.code)) {
    throw new TypeError('Connect code format is invalid');
  }
  if (
    !Number.isInteger(code.ttl_minutes)
    || code.ttl_minutes < 1
    || code.ttl_minutes > MAX_INVITE_TTL_SECONDS / 60
  ) {
    throw new TypeError('Connect code lifetime is invalid');
  }
  const ttlSeconds = code.ttl_minutes * 60;
  if (
    !Number.isInteger(code.expires_at)
    || code.expires_at <= nowSeconds - MAX_CLOCK_SKEW_SECONDS
    || code.expires_at > nowSeconds + ttlSeconds + MAX_CLOCK_SKEW_SECONDS
  ) {
    throw new TypeError('Connect code expiration is invalid');
  }

  const invite = {
    schema_version: 1,
    server_url: normalizeDjServerUrl(runtime.public_url),
    connect_code: code.code,
    expires_at: code.expires_at,
  };
  if (runtime.certificate_sha256 !== null && runtime.certificate_sha256 !== undefined) {
    if (
      typeof runtime.certificate_sha256 !== 'string'
      || !FINGERPRINT_PATTERN.test(runtime.certificate_sha256)
    ) {
      throw new TypeError('Certificate fingerprint must be 64 uppercase hexadecimal characters');
    }
    invite.certificate_sha256 = runtime.certificate_sha256;
  }
  return invite;
}

function encodeBase64Url(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
}

function decodeBase64Url(value) {
  if (!/^[A-Za-z0-9_-]+$/u.test(value)) {
    throw new TypeError('Invite payload is not base64url');
  }
  const padding = '='.repeat((4 - (value.length % 4)) % 4);
  const binary = atob(value.replaceAll('-', '+').replaceAll('_', '/') + padding);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function encodeInviteLink(inviteValue) {
  const invite = requireObject(inviteValue, 'Invite');
  return `mcav://connect?invite=${encodeBase64Url(JSON.stringify(invite))}`;
}

export function decodeInviteLink(link) {
  let parsed;
  try {
    parsed = new URL(link);
  } catch {
    throw new TypeError('Invite link is invalid');
  }
  if (
    parsed.protocol !== 'mcav:'
    || parsed.hostname !== 'connect'
    || parsed.pathname !== ''
    || parsed.username
    || parsed.password
    || parsed.hash
  ) {
    throw new TypeError('Invite link is invalid');
  }
  const inviteParameters = parsed.searchParams.getAll('invite');
  if (
    inviteParameters.length !== 1
    || [...parsed.searchParams.keys()].some((key) => key !== 'invite')
  ) {
    throw new TypeError('Invite link payload is missing');
  }
  return JSON.parse(decodeBase64Url(inviteParameters[0]));
}

export function formatFingerprint(fingerprint) {
  if (typeof fingerprint !== 'string' || !FINGERPRINT_PATTERN.test(fingerprint)) {
    throw new TypeError('Certificate fingerprint is invalid');
  }
  return fingerprint.match(/.{1,2}/gu).join(' ');
}

export function renderInvite(inviteValue, elementsValue) {
  const invite = requireObject(inviteValue, 'Invite');
  const elements = requireObject(elementsValue, 'Invite elements');
  elements.serverUrl.textContent = String(invite.server_url);
  elements.expiresAt.textContent = new Date(invite.expires_at * 1000).toLocaleString();
  const hasFingerprint = typeof invite.certificate_sha256 === 'string';
  elements.fingerprintRow.hidden = !hasFingerprint;
  elements.fingerprint.textContent = hasFingerprint
    ? formatFingerprint(invite.certificate_sha256)
    : '';
  elements.container.hidden = false;
}

export async function copyInviteLink(invite, { clipboard, fallbackField }) {
  const link = encodeInviteLink(invite);
  if (clipboard && typeof clipboard.writeText === 'function') {
    try {
      await clipboard.writeText(link);
      fallbackField.hidden = true;
      return { copied: true, link };
    } catch {
      // The explicit manual fallback below handles denied clipboard access.
    }
  }

  fallbackField.readOnly = true;
  fallbackField.value = link;
  fallbackField.hidden = false;
  fallbackField.focus();
  fallbackField.select();
  return { copied: false, link };
}
