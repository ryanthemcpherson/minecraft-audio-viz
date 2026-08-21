/**
 * WebSocket Service for AudioViz Admin Panel
 * Handles connection to the audio_processor backend with automatic reconnection
 */

export class WebSocketService extends EventTarget {
    constructor(options = {}) {
        super();

        this.host = options.host || 'localhost';
        this.port = options.port || 8766;
        this.username = options.username || '';
        this.password = options.password || '';
        this.pageProtocol = options.pageProtocol || globalThis.location?.protocol || 'http:';
        this.reconnectInterval = options.reconnectInterval || 1000;
        this.maxReconnectAttempts = options.maxReconnectAttempts || 50;

        this.ws = null;
        this.reconnectAttempts = 0;
        this.isConnecting = false;
        this.shouldReconnect = true;
        this.pingInterval = null;
        this.lastPong = Date.now();
        this.lastSuccessfulMessage = 0;
        this.isFailed = false;
        this.isAuthenticated = false;
        this._sessionEstablished = false;
        this._awaitingAuth = false;
        this._negotiatingAuth = false;
        this._socketGeneration = 0;

        // Message queue for when disconnected (with max size to prevent memory bloat)
        this.messageQueue = [];
        this.maxQueueSize = 500;
        this._lastQueueWarnAt = 0;
        this._queueWarnIntervalMs = 2000;
        this._queuedDrops = 0;
    }

    /**
     * Replace the in-memory operator credentials used for the next connection.
     */
    setCredentials(username, password) {
        const nextUsername = username || '';
        const nextPassword = password || '';
        if (nextUsername !== this.username || nextPassword !== this.password) {
            this._endSession();
        }
        this.username = nextUsername;
        this.password = nextPassword;
    }

    /**
     * Manually reconnect after failed state
     */
    manualReconnect() {
        console.log('[WS] Manual reconnect triggered');
        this.isFailed = false;
        this.reconnectAttempts = 0;
        this.shouldReconnect = true;
        if (this.ws) {
            try { this.ws.close(); } catch (_) { /* ignore */ }
            this.ws = null;
        }
        this.isConnecting = false;
        this.connect();
    }

    /**
     * Connect to the WebSocket server
     */
    connect() {
        if (this.ws?.readyState === WebSocket.OPEN || this.isConnecting) {
            return;
        }

        this.isConnecting = true;
        this.shouldReconnect = true;

        const scheme = this.pageProtocol === 'https:' ? 'wss' : 'ws';
        const url = `${scheme}://${this.host}:${this.port}`;
        console.log(`[WS] Connecting to ${url}...`);

        this._emit('connecting', {
            attempt: this.reconnectAttempts,
            maxAttempts: this.maxReconnectAttempts
        });

        try {
            const generation = ++this._socketGeneration;
            const socket = new WebSocket(url);
            this.ws = socket;

            socket.onopen = () => this._onOpen(socket, generation);
            socket.onclose = (event) => this._onClose(event, socket, generation);
            socket.onerror = (error) => this._onError(error, socket, generation);
            socket.onmessage = (event) => this._onMessage(event, socket, generation);

        } catch (error) {
            console.error('[WS] Connection error:', error);
            this.isConnecting = false;
            this._scheduleReconnect();
        }
    }

    /**
     * Disconnect from the server
     */
    disconnect() {
        this.shouldReconnect = false;
        this._stopPingInterval();
        this._endSession({ clearCredentials: true });

        if (this.ws) {
            this.ws.close(1000, 'Client disconnect');
            this.ws = null;
        }
    }

    /**
     * Send a message to the server
     * @param {Object} message - Message object to send
     */
    send(message) {
        if (this.isAuthenticated && this.ws?.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(message));
            return true;
        }

        // Only a previously authenticated session may carry controls through
        // a transient reconnect. Pre-auth and signed-out commands are dropped.
        if (this._sessionEstablished && this.shouldReconnect) {
            if (this.messageQueue.length >= this.maxQueueSize) {
                // Remove oldest messages to make room
                this.messageQueue.shift();
                this._queuedDrops++;
            }
            this.messageQueue.push(message);
            const now = Date.now();
            if (now - this._lastQueueWarnAt >= this._queueWarnIntervalMs) {
                console.warn(
                    '[WS] Not connected, message queued (' +
                    this.messageQueue.length + '/' + this.maxQueueSize +
                    ', dropped=' + this._queuedDrops + ')'
                );
                this._lastQueueWarnAt = now;
            }
            return false;
        }
        return false;
    }

    /**
     * Send a safety-critical command only on the currently authenticated socket.
     * Unlike send(), this path never queues or replays commands after reconnect.
     */
    sendImmediate(message) {
        if (!this.isAuthenticated || this.ws?.readyState !== WebSocket.OPEN) {
            return false;
        }

        try {
            this.ws.send(JSON.stringify(message));
            return true;
        } catch (error) {
            console.error('[WS] Immediate send failed:', error);
            return false;
        }
    }

    /**
     * Check if connected
     */
    get isConnected() {
        return this.ws?.readyState === WebSocket.OPEN;
    }

    // === Private Methods ===

    _onOpen(socket = this.ws, generation = this._socketGeneration) {
        if (!this._isCurrentSocket(socket, generation)) return;
        console.log('[WS] Connected');
        this.isConnecting = false;
        this.reconnectAttempts = 0;
        this.isAuthenticated = false;
        this._negotiatingAuth = true;

        // Send credentials immediately for compatibility with older secured
        // servers while still accepting the explicit server negotiation.
        if (this.username && this.password) {
            socket.send(JSON.stringify({
                type: 'vj_auth',
                username: this.username,
                password: this.password,
            }));
            // Wait for auth_success or auth_error before proceeding
            this._awaitingAuth = true;
        }
    }

    /**
     * Complete connection setup after auth succeeds (or is skipped)
     */
    _completeConnection() {
        const reconnectingSameSession = this._sessionEstablished;
        if (!reconnectingSameSession) {
            this._clearPendingCommands();
        }
        this.isAuthenticated = true;
        this._sessionEstablished = true;
        this._awaitingAuth = false;
        this._negotiatingAuth = false;
        this._emit('connected');
        this._startPingInterval();

        // Request initial state (get_state response includes patterns in vj_state)
        this.send({ type: 'get_state' });

        // Flush message queue
        while (this.messageQueue.length > 0) {
            const msg = this.messageQueue.shift();
            this.send(msg);
        }
    }

    _onClose(event, socket = this.ws, generation = this._socketGeneration) {
        if (!this._isCurrentSocket(socket, generation)) return;
        console.log(`[WS] Disconnected (code: ${event.code})`);
        this.isConnecting = false;
        this.isAuthenticated = false;
        this._awaitingAuth = false;
        this._negotiatingAuth = false;
        this._stopPingInterval();

        this._emit('disconnected', { code: event.code, reason: event.reason });

        if (this.shouldReconnect) {
            this._scheduleReconnect();
        }
    }

    _onError(error, socket = this.ws, generation = this._socketGeneration) {
        if (!this._isCurrentSocket(socket, generation)) return;
        console.error('[WS] Error:', error);
        this._emit('error', { error });
    }

    _onMessage(event, socket = this.ws, generation = this._socketGeneration) {
        if (!this._isCurrentSocket(socket, generation)) return;
        try {
            const data = JSON.parse(event.data);

            if (data.type === 'auth_required') {
                if (!this._awaitingAuth && this.username && this.password) {
                    socket.send(JSON.stringify({
                        type: 'vj_auth',
                        username: this.username,
                        password: this.password,
                    }));
                    this._awaitingAuth = true;
                } else if (!this._awaitingAuth) {
                    this.shouldReconnect = false;
                    this._endSession();
                    this._emit('auth_required');
                    socket.close(1000, 'Credentials required');
                }
                return;
            }

            if (data.type === 'auth_success') {
                console.log('[WS] VJ auth succeeded');
                this._completeConnection();
                return;
            }

            if (data.type === 'auth_error') {
                console.error('[WS] VJ auth failed:', data.error);
                this.shouldReconnect = false;
                this._endSession({ clearCredentials: true });
                this._emit('auth_failed', data);
                return;
            }

            // Older no-auth servers begin with state instead of an auth
            // result. Accept that only when this client sent no credentials.
            if (this._negotiatingAuth && !this._awaitingAuth && !this.username && !this.password) {
                this._completeConnection();
            } else if (!this.isAuthenticated) {
                return;
            }

            // Track successful message exchange and reset backoff
            // This ensures backoff resets after the connection is actually working, not just opened
            this.lastSuccessfulMessage = Date.now();
            this.reconnectAttempts = 0;

            // Handle pong (response to our ping)
            if (data.type === 'pong') {
                this.lastPong = Date.now();
                return;
            }

            // Handle server-initiated ping - respond with pong
            if (data.type === 'ping') {
                this.lastPong = Date.now(); // Server ping proves connection is alive
                this.send({ type: 'pong' });
                return;
            }

            // Emit message event with parsed data
            this._emit('message', data);

            // Also emit specific event for message type
            this._emit(data.type, data);

        } catch (error) {
            console.error('[WS] Failed to parse message:', error);
        }
    }

    _emit(eventName, detail = null) {
        this.dispatchEvent(new CustomEvent(eventName, { detail }));
    }

    _isCurrentSocket(socket, generation) {
        return socket === this.ws && generation === this._socketGeneration;
    }

    _clearPendingCommands() {
        this.messageQueue.length = 0;
        this._queuedDrops = 0;
    }

    _endSession({ clearCredentials = false } = {}) {
        this.isAuthenticated = false;
        this._sessionEstablished = false;
        this._awaitingAuth = false;
        this._negotiatingAuth = false;
        this._clearPendingCommands();
        if (clearCredentials) {
            this.username = '';
            this.password = '';
        }
    }

    _scheduleReconnect() {
        if (!this.shouldReconnect) return;

        this.reconnectAttempts++;

        if (this.reconnectAttempts > this.maxReconnectAttempts) {
            console.error('[WS] Max reconnect attempts reached');
            this.isFailed = true;
            this._emit('reconnect_failed');
            return;
        }

        const delay = Math.min(
            this.reconnectInterval * Math.pow(2, this.reconnectAttempts - 1),
            60000 // Max 60 seconds
        );

        console.log(`[WS] Reconnecting in ${Math.round(delay / 1000)}s (attempt ${this.reconnectAttempts})`);

        setTimeout(() => {
            if (this.shouldReconnect) {
                this.connect();
            }
        }, delay);
    }

    _startPingInterval() {
        this._stopPingInterval();

        this.pingInterval = setInterval(() => {
            if (this.ws?.readyState === WebSocket.OPEN) {
                this.send({ type: 'ping' });

                // Check for timeout (20s accounts for network jitter and server load)
                if (Date.now() - this.lastPong > 20000) {
                    console.warn('[WS] Ping timeout, reconnecting...');
                    this.ws.close();
                }
            }
        }, 5000);
    }

    _stopPingInterval() {
        if (this.pingInterval) {
            clearInterval(this.pingInterval);
            this.pingInterval = null;
        }
    }
}

// Export as singleton for easy access
export const wsService = new WebSocketService();
