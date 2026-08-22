/**
 * Owns the protected control-panel gate and starts WebSocket negotiation
 * immediately so an explicit no-auth server can open the panel safely.
 */
export function setupAdminLogin({
    root = document,
    createApp,
    getApp,
    setApp,
}) {
    const authGate = root.getElementById('auth-gate');
    const authForm = root.getElementById('auth-form');
    const authError = root.getElementById('auth-error');
    const usernameInput = root.getElementById('auth-username');
    const passwordInput = root.getElementById('auth-password');
    const submitButton = root.getElementById('auth-submit');
    const appElement = root.getElementById('app');
    const logoutButton = root.getElementById('btn-logout');

    const showLogin = (message = '') => {
        authGate.hidden = false;
        appElement.hidden = true;
        appElement.setAttribute('aria-hidden', 'true');
        authError.textContent = message;
        passwordInput.value = '';
        submitButton.disabled = false;
        submitButton.textContent = 'Open Control Center';
        (usernameInput.value ? passwordInput : usernameInput).focus();
    };

    const showApp = () => {
        authGate.hidden = true;
        appElement.hidden = false;
        appElement.removeAttribute('aria-hidden');
        authError.textContent = '';
        submitButton.disabled = false;
        submitButton.textContent = 'Open Control Center';
    };

    authForm.addEventListener('submit', (event) => {
        event.preventDefault();
        const username = usernameInput.value.trim();
        const password = passwordInput.value;
        if (!username || !password) {
            authError.textContent = 'Enter the username and password from FIRST_LOGIN.txt.';
            return;
        }

        authError.textContent = '';
        submitButton.disabled = true;
        submitButton.textContent = 'Authenticating…';
        const app = getApp();
        app.ws.setCredentials(username, password);
        app.ws.manualReconnect();
    });

    logoutButton.addEventListener('click', () => {
        getApp()?.ws.disconnect();
        usernameInput.value = '';
        showLogin('Signed out.');
    });

    showLogin();
    const app = createApp({
        username: '',
        password: '',
        onAuthenticated: showApp,
        onAuthRequired: () => showLogin(),
        onAuthFailed: () => showLogin('Invalid username or password.'),
    });
    setApp(app);

    return { showApp, showLogin };
}
