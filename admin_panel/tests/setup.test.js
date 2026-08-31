// @vitest-environment jsdom

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { startSetup, validatePassword, validateUsername } from '../js/setup.js';

function response(status, payload) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn(async () => payload),
  };
}

function installSetupDocument() {
  document.title = 'MCAV setup';
  document.body.textContent = '';
  const main = document.createElement('main');
  main.innerHTML = `
    <ol id="setup-phases">
      <li data-phase="link"></li>
      <li data-phase="account"></li>
      <li data-phase="ready"></li>
    </ol>
    <section id="setup-form-panel" hidden>
      <form id="setup-form" novalidate>
        <label for="setup-username">Username</label>
        <input id="setup-username" name="username">
        <label for="setup-password">Password</label>
        <input id="setup-password" name="password" type="password">
        <span id="password-byte-count"></span>
        <button id="setup-submit" type="submit">Create administrator</button>
      </form>
    </section>
    <p id="setup-status" tabindex="-1"></p>
  `;
  document.body.append(main);
}

beforeEach(() => {
  installSetupDocument();
  localStorage.clear();
  sessionStorage.clear();
  history.replaceState({}, '', '/setup/');
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('first-run setup controller', () => {
  it('removes the setup fragment before verifying and never persists it', async () => {
    history.replaceState({}, '', '/setup/#token=secret-value');
    const calls = [];
    const fakeFetch = vi.fn(async (url, options) => {
      calls.push({ url, options, hash: location.hash });
      return response(200, { valid: true });
    });
    const storageWrite = vi.spyOn(Storage.prototype, 'setItem');

    await startSetup({ fetch: fakeFetch });

    expect(location.hash).toBe('');
    expect(location.pathname).toBe('/setup/');
    expect(calls[0].hash).toBe('');
    expect(calls[0].url).toBe('/setup/verify');
    expect(calls[0].options.body).toContain('secret-value');
    expect(storageWrite).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.querySelector('#setup-form-panel').hidden).toBe(false);
    expect(document.activeElement).toBe(document.querySelector('#setup-username'));
  });

  it('turns an expired token into a focused recovery instruction', async () => {
    history.replaceState({}, '', '/setup/#token=expired-value');
    const fakeFetch = vi
      .fn()
      .mockResolvedValueOnce(response(401, { error: 'invalid or expired setup token' }))
      .mockResolvedValueOnce(response(200, { status: 'expired' }));

    await startSetup({ fetch: fakeFetch });

    const status = document.querySelector('#setup-status');
    expect(status.textContent).toMatch(/expired/i);
    expect(status.dataset.tone).toBe('error');
    expect(document.activeElement).toBe(status);
    expect(document.querySelector('#setup-form-panel').hidden).toBe(true);
  });

  it('enforces normalized username and UTF-8 password bounds before submission', async () => {
    history.replaceState({}, '', '/setup/#token=valid-token');
    const fakeFetch = vi.fn(async () => response(200, { valid: true }));
    await startSetup({ fetch: fakeFetch });
    const username = document.querySelector('#setup-username');
    const password = document.querySelector('#setup-password');

    username.value = 'not valid';
    password.value = 'short';
    document.querySelector('#setup-form').dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    await vi.waitFor(() => expect(document.querySelector('#setup-status').textContent).toMatch(/username/i));

    expect(fakeFetch).toHaveBeenCalledTimes(1);
    expect(validateUsername('VJ_Admin')).toEqual({ valid: true, normalized: 'vj_admin' });
    expect(validateUsername('pånel').valid).toBe(false);
    expect(validatePassword('correct horse battery').valid).toBe(true);
    expect(validatePassword('a'.repeat(73)).valid).toBe(false);
    expect(validatePassword('valid-length\u0000bad').valid).toBe(false);
  });

  it('disables submission, clears credentials, confirms success, then navigates', async () => {
    history.replaceState({}, '', '/setup/#token=valid-token');
    let resolveCreate;
    const createResponse = new Promise((resolve) => {
      resolveCreate = resolve;
    });
    const fakeFetch = vi
      .fn()
      .mockResolvedValueOnce(response(200, { valid: true }))
      .mockReturnValueOnce(createResponse);
    const navigate = vi.fn();
    await startSetup({ fetch: fakeFetch, navigate, successDelayMs: 0 });
    const username = document.querySelector('#setup-username');
    const password = document.querySelector('#setup-password');
    const submit = document.querySelector('#setup-submit');
    username.value = 'VJ_Admin';
    password.value = 'correct horse battery';

    document.querySelector('#setup-form').dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    expect(submit.disabled).toBe(true);
    resolveCreate(response(201, { status: 'complete', username: 'vj_admin' }));

    await vi.waitFor(() => expect(navigate).toHaveBeenCalledWith('/'));
    const submitted = JSON.parse(fakeFetch.mock.calls[1][1].body);
    expect(submitted).toEqual({
      token: 'valid-token',
      username: 'vj_admin',
      password: 'correct horse battery',
    });
    expect(username.value).toBe('');
    expect(password.value).toBe('');
    expect(document.querySelector('#setup-status').textContent).toMatch(/ready/i);
  });
});

describe('setup document accessibility and containment', () => {
  it('uses semantic local assets, ordered controls, and explicit autocomplete', () => {
    const html = readFileSync(resolve('admin_panel/setup.html'), 'utf8');
    const parsed = new DOMParser().parseFromString(html, 'text/html');
    const controls = [...parsed.querySelectorAll('input, button')].map((node) => node.id);

    expect(parsed.querySelector('main')).not.toBeNull();
    expect(parsed.querySelector('label[for="setup-username"]')).not.toBeNull();
    expect(parsed.querySelector('label[for="setup-password"]')).not.toBeNull();
    expect(parsed.querySelector('#setup-status[aria-live="polite"]')).not.toBeNull();
    expect(parsed.querySelector('#setup-username').getAttribute('autocomplete')).toBe('username');
    expect(parsed.querySelector('#setup-password').getAttribute('autocomplete')).toBe('new-password');
    expect(controls).toEqual(['setup-username', 'setup-password', 'setup-submit']);
    expect(html).not.toMatch(/https?:\/\//);
  });

  it('defines visible focus, 44px targets, responsive layout, and reduced motion', () => {
    const css = readFileSync(resolve('admin_panel/css/setup.css'), 'utf8');

    expect(css).toMatch(/:focus-visible/);
    expect(css).toMatch(/min-height:\s*(?:4[4-9]|[5-9][0-9])px/);
    expect(css).toMatch(/@media\s*\(max-width:/);
    expect(css).toMatch(/prefers-reduced-motion:\s*reduce/);
  });
});
