export function websocketScheme(pageProtocol) {
    return pageProtocol === 'https:' ? 'wss' : 'ws';
}

export function buildAuthMessage(username, password) {
    return { type: 'vj_auth', username, password };
}

export class PreviewAuthSession {
    constructor({ clearPending = () => {} } = {}) {
        this.clearPending = clearPending;
        this.credentials = null;
        this.authenticated = false;
        this.sessionEstablished = false;
        this.authSubmitted = false;
    }

    setCredentials(username, password) {
        this._endSession();
        this.credentials = { username, password };
    }

    onOpen(send) {
        this.authenticated = false;
        this.authSubmitted = false;
        if (this.credentials) {
            send(buildAuthMessage(this.credentials.username, this.credentials.password));
            this.authSubmitted = true;
        }
    }

    handleProtocolMessage(data, callbacks = {}) {
        if (data?.type === 'auth_required') {
            if (this.credentials && !this.authSubmitted) {
                callbacks.send?.(
                    buildAuthMessage(this.credentials.username, this.credentials.password),
                );
                this.authSubmitted = true;
            } else if (!this.credentials) {
                this._endSession();
                callbacks.onAuthRequired?.();
            }
            return true;
        }

        if (data?.type === 'auth_success') {
            this.authenticated = true;
            this.sessionEstablished = true;
            this.authSubmitted = false;
            callbacks.onAuthenticated?.();
            return true;
        }

        if (data?.type === 'auth_error') {
            this._endSession({ clearCredentials: true });
            callbacks.onAuthFailed?.(data.error);
            return true;
        }

        return false;
    }

    acceptLegacyNoAuth(callbacks = {}) {
        if (this.authenticated || this.authSubmitted || this.credentials) return false;
        this.authenticated = true;
        this.sessionEstablished = true;
        callbacks.onAuthenticated?.();
        return true;
    }

    canSendControls() {
        return this.authenticated;
    }

    shouldReconnect() {
        return this.sessionEstablished;
    }

    logout() {
        this._endSession({ clearCredentials: true });
    }

    _endSession({ clearCredentials = false } = {}) {
        this.authenticated = false;
        this.sessionEstablished = false;
        this.authSubmitted = false;
        this.clearPending();
        if (clearCredentials) this.credentials = null;
    }
}
