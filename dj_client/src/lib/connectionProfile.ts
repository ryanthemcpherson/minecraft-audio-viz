export const TLS_FINGERPRINT_STORAGE_KEY = 'mcav.tlsFingerprint';

export type ConnectionErrorCode =
  | 'connection_failed'
  | 'authentication_failed'
  | 'websocket_error'
  | 'send_error'
  | 'already_connected'
  | 'not_connected'
  | 'invalid_tls_fingerprint'
  | 'missing_peer_certificate'
  | 'tls_fingerprint_mismatch'
  | 'tls_certificate_host_mismatch'
  | 'tls_handshake';

interface StorageReader {
  getItem: (key: string) => string | null;
}

interface StorageWriter {
  setItem: (key: string, value: string) => void;
}

type FingerprintStorage = StorageReader & StorageWriter;

export interface TlsFingerprintFieldState {
  normalizedValue: string;
  isValid: boolean;
  ariaInvalid: boolean;
  describedBy: string;
  validationMessage: string | null;
}

export interface DirectConnectionArgs extends Record<string, unknown> {
  djName: string;
  serverHost: string;
  serverPort: number;
  tlsFingerprint: string | null;
}

export interface CodeConnectionArgs extends DirectConnectionArgs {
  code: string;
  blockPalette: Array<string | null> | null;
  djSessionId: string | null;
}

const TLS_ERROR_MESSAGES: Record<
  Extract<
    ConnectionErrorCode,
    | 'invalid_tls_fingerprint'
    | 'missing_peer_certificate'
    | 'tls_fingerprint_mismatch'
    | 'tls_certificate_host_mismatch'
    | 'tls_handshake'
  >,
  string
> = {
  invalid_tls_fingerprint:
    'Server certificate fingerprint must contain exactly 64 hexadecimal characters.',
  missing_peer_certificate:
    'The server did not provide a TLS certificate. Check the secure server address.',
  tls_fingerprint_mismatch:
    'The server certificate changed or does not match the configured fingerprint. Ask your VJ operator to verify the certificate, then update the configured fingerprint.',
  tls_certificate_host_mismatch:
    'The server certificate is for a different host. Use the hostname or IP address listed in the certificate.',
  tls_handshake:
    'Secure connection failed during the TLS handshake. Check the server address and certificate configuration.',
};

export function normalizeTlsFingerprint(value: string): string {
  return value.replace(/[:\s]/g, '').toUpperCase();
}

export function isTlsFingerprintValid(value: string): boolean {
  const normalized = normalizeTlsFingerprint(value);
  return normalized.length === 0 || /^[0-9A-F]{64}$/.test(normalized);
}

export function getTlsFingerprintFieldState(value: string): TlsFingerprintFieldState {
  const normalizedValue = normalizeTlsFingerprint(value);
  const isValid = isTlsFingerprintValid(normalizedValue);
  return {
    normalizedValue,
    isValid,
    ariaInvalid: !isValid,
    describedBy: isValid
      ? 'tls-fingerprint-help'
      : 'tls-fingerprint-help tls-fingerprint-error',
    validationMessage: isValid
      ? null
      : 'Enter exactly 64 hexadecimal characters, or leave this field blank.',
  };
}

export function loadTlsFingerprint(storage: FingerprintStorage): string {
  const storedValue = storage.getItem(TLS_FINGERPRINT_STORAGE_KEY);
  if (storedValue === null) {
    return '';
  }
  const normalizedValue = normalizeTlsFingerprint(storedValue);
  if (storedValue !== normalizedValue) {
    storage.setItem(TLS_FINGERPRINT_STORAGE_KEY, normalizedValue);
  }
  return normalizedValue;
}

export function saveTlsFingerprint(storage: StorageWriter, value: string): string {
  const normalizedValue = normalizeTlsFingerprint(value);
  storage.setItem(TLS_FINGERPRINT_STORAGE_KEY, normalizedValue);
  return normalizedValue;
}

function tlsFingerprintPayload(value: string): string | null {
  return normalizeTlsFingerprint(value) || null;
}

export function buildDirectConnectionArgs(input: {
  djName: string;
  serverHost: string;
  serverPort: number;
  tlsFingerprint: string;
}): DirectConnectionArgs {
  return {
    djName: input.djName,
    serverHost: input.serverHost,
    serverPort: input.serverPort,
    tlsFingerprint: tlsFingerprintPayload(input.tlsFingerprint),
  };
}

export function buildCodeConnectionArgs(input: {
  code: string;
  djName: string;
  serverHost: string;
  serverPort: number;
  tlsFingerprint: string;
  blockPalette: Array<string | null> | null;
  djSessionId: string | null;
}): CodeConnectionArgs {
  return {
    code: input.code,
    djName: input.djName,
    serverHost: input.serverHost,
    serverPort: input.serverPort,
    tlsFingerprint: tlsFingerprintPayload(input.tlsFingerprint),
    blockPalette: input.blockPalette,
    djSessionId: input.djSessionId,
  };
}

function tlsErrorCodeFromText(errorText: string): keyof typeof TLS_ERROR_MESSAGES | null {
  const normalizedError = errorText.toLowerCase();
  if (
    normalizedError.includes('invalid tls certificate fingerprint') ||
    normalizedError.includes('fingerprint must contain exactly 64 hexadecimal')
  ) {
    return 'invalid_tls_fingerprint';
  }
  if (normalizedError.includes('did not provide a tls certificate')) {
    return 'missing_peer_certificate';
  }
  if (
    normalizedError.includes('tls certificate fingerprint mismatch') ||
    normalizedError.includes('certificate changed or does not match the configured fingerprint')
  ) {
    return 'tls_fingerprint_mismatch';
  }
  if (
    normalizedError.includes('certificate is not valid for the requested server host') ||
    normalizedError.includes('certificate is for a different host')
  ) {
    return 'tls_certificate_host_mismatch';
  }
  if (normalizedError.includes('tls handshake') || normalizedError.includes('certificate')) {
    return 'tls_handshake';
  }
  return null;
}

export function formatConnectionError(
  error: unknown,
  errorCode?: ConnectionErrorCode | null,
): string {
  if (errorCode && errorCode in TLS_ERROR_MESSAGES) {
    return TLS_ERROR_MESSAGES[errorCode as keyof typeof TLS_ERROR_MESSAGES];
  }

  const errorText = String(error);
  const tlsErrorCode = tlsErrorCodeFromText(errorText);
  if (tlsErrorCode) {
    return TLS_ERROR_MESSAGES[tlsErrorCode];
  }

  const normalizedError = errorText.toLowerCase();
  if (
    normalizedError.includes('timeout') ||
    normalizedError.includes('timed out') ||
    normalizedError.includes('connection refused')
  ) {
    return "Can't reach server. Check that the VJ server is running.";
  }
  if (
    normalizedError.includes('auth') ||
    normalizedError.includes('invalid') ||
    normalizedError.includes('unauthorized')
  ) {
    return 'Authentication failed. Ask your VJ operator for a new code.';
  }
  return errorText;
}

export function sanitizeConnectionStatus<
  T extends { error: string | null; error_code?: ConnectionErrorCode | null },
>(status: T): T {
  if (!status.error && !status.error_code) {
    return status;
  }
  return {
    ...status,
    error: formatConnectionError(status.error ?? '', status.error_code),
  };
}
