const USERNAME_PATTERN = /^[a-z0-9][a-z0-9_-]{2,31}$/;
const TOKEN_LIMIT = 512;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]/u;
const SURROGATES = /[\ud800-\udfff]/u;

export function validateUsername(value) {
  if (typeof value !== 'string') {
    return { valid: false, normalized: '' };
  }
  let normalized;
  try {
    normalized = value.normalize('NFKC').toLowerCase();
  } catch {
    return { valid: false, normalized: '' };
  }
  return { valid: USERNAME_PATTERN.test(normalized), normalized };
}

export function validatePassword(value) {
  if (
    typeof value !== 'string'
    || CONTROL_CHARACTERS.test(value)
    || SURROGATES.test(value)
  ) {
    return { valid: false, bytes: 0 };
  }
  const bytes = new TextEncoder().encode(value).byteLength;
  return { valid: bytes >= 12 && bytes <= 72, bytes };
}

function required(root, selector) {
  const element = root.querySelector(selector);
  if (!element) {
    throw new Error(`Missing setup control: ${selector}`);
  }
  return element;
}

function tokenFromFragment(fragment) {
  const parameters = new URLSearchParams(fragment.replace(/^#/, ''));
  const token = parameters.get('token');
  return token && token.length <= TOKEN_LIMIT ? token : null;
}

async function jsonPayload(response) {
  try {
    const payload = await response.json();
    return payload && typeof payload === 'object' ? payload : {};
  } catch {
    return {};
  }
}

function requestOptions(payload) {
  return {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  };
}

function wait(milliseconds) {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds));
}

export async function startSetup({
  fetch: fetchRequest = globalThis.fetch?.bind(globalThis),
  navigate = (path) => globalThis.location.assign(path),
  successDelayMs = 850,
  root = document,
} = {}) {
  if (typeof fetchRequest !== 'function') {
    throw new Error('Setup requires the Fetch API');
  }

  const formPanel = required(root, '#setup-form-panel');
  const form = required(root, '#setup-form');
  const usernameInput = required(root, '#setup-username');
  const passwordInput = required(root, '#setup-password');
  const passwordByteCount = required(root, '#password-byte-count');
  const submitButton = required(root, '#setup-submit');
  const status = required(root, '#setup-status');
  const phases = new Map(
    [...root.querySelectorAll('[data-phase]')].map((element) => [element.dataset.phase, element]),
  );
  let token = tokenFromFragment(globalThis.location.hash);
  globalThis.history.replaceState({}, root.title || document.title, '/setup/');

  const setStatus = (message, tone, shouldFocus = false) => {
    status.textContent = message;
    status.dataset.tone = tone;
    if (shouldFocus) {
      status.focus({ preventScroll: true });
    }
  };

  const setPhase = (name, state) => {
    const phase = phases.get(name);
    if (phase) {
      phase.dataset.state = state;
    }
  };

  const terminalFailure = (message) => {
    token = null;
    passwordInput.value = '';
    formPanel.hidden = true;
    setPhase('link', 'error');
    setStatus(message, 'error', true);
  };

  const setupState = async () => {
    try {
      const response = await fetchRequest('/setup/status', {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      return response.ok ? (await jsonPayload(response)).status : null;
    } catch {
      return null;
    }
  };

  const explainInvalidToken = async () => {
    const state = await setupState();
    if (state === 'complete') {
      terminalFailure('Setup is already complete. Open the Control Center to sign in.');
    } else if (state === 'expired') {
      terminalFailure('This setup link expired. Run /audioviz setup in the server console.');
    } else {
      terminalFailure('This setup link is invalid. Generate a new link from the server console.');
    }
  };

  passwordInput.addEventListener('input', () => {
    const password = validatePassword(passwordInput.value);
    passwordByteCount.textContent = `${password.bytes} / 72 bytes`;
    passwordByteCount.dataset.valid = String(password.valid);
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!token || submitButton.disabled) {
      return;
    }
    const username = validateUsername(usernameInput.value);
    if (!username.valid) {
      setStatus('Use 3–32 lowercase letters, numbers, underscores, or hyphens for the username.', 'error', true);
      usernameInput.focus();
      return;
    }
    const password = validatePassword(passwordInput.value);
    if (!password.valid) {
      setStatus('Use a password containing 12–72 UTF-8 bytes and no control characters.', 'error', true);
      passwordInput.focus();
      return;
    }

    submitButton.disabled = true;
    submitButton.textContent = 'Creating administrator…';
    setStatus('Securing your operator account…', 'working');
    try {
      const response = await fetchRequest('/setup/admin', requestOptions({
        token,
        username: username.normalized,
        password: passwordInput.value,
      }));
      const payload = await jsonPayload(response);
      if (response.status === 401) {
        await explainInvalidToken();
        return;
      }
      if (!response.ok) {
        const serverMessage = typeof payload.error === 'string' && payload.error.length <= 160
          ? payload.error
          : 'Administrator creation failed. Check the fields and try again.';
        setStatus(serverMessage, 'error', true);
        submitButton.disabled = false;
        submitButton.textContent = 'Create administrator';
        return;
      }

      token = null;
      usernameInput.value = '';
      passwordInput.value = '';
      passwordByteCount.textContent = '0 / 72 bytes';
      formPanel.hidden = true;
      setPhase('account', 'complete');
      setPhase('ready', 'active');
      setStatus('Control Center ready. Opening the live workspace…', 'success', true);
      await wait(Math.max(0, successDelayMs));
      navigate('/');
    } catch {
      terminalFailure('Setup could not reach the server. Generate a fresh link and try again.');
    }
  });

  if (!token) {
    const state = await setupState();
    if (state === 'complete') {
      terminalFailure('Setup is already complete. Open the Control Center to sign in.');
    } else {
      terminalFailure('No setup token was found. Run /audioviz setup in the server console.');
    }
    return;
  }

  try {
    const response = await fetchRequest('/setup/verify', requestOptions({ token }));
    if (!response.ok) {
      await explainInvalidToken();
      return;
    }
  } catch {
    terminalFailure('Setup could not reach the server. Generate a fresh link and try again.');
    return;
  }

  setPhase('link', 'complete');
  setPhase('account', 'active');
  formPanel.hidden = false;
  setStatus('Secure link accepted. Create your operator account.', 'ready');
  usernameInput.focus({ preventScroll: true });
}

if (typeof document !== 'undefined' && document.querySelector('script[data-mcav-setup]')) {
  void startSetup();
}
