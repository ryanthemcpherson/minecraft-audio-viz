/**
 * WebSocket Service for AudioViz Admin Panel
 * Handles connection to the audio_processor backend with automatic reconnection
 */

export class WebSocketService extends EventTarget {
    constructor(options = {}) {
        super();

        this.host = options.host || 'localhost';
        this.port = options.port || 8766;
        this.reconnectInterval = options.reconnectInterval || 2000;
        this.maxReconnectAttempts = options.maxReconnectAttempts || 10;

        this.ws = null;
        this.reconnectAttempts = 0;
        this.isConnecting = false;
        this.shouldReconnect = true;
        this.pingInterval = null;
        this.lastPong = Date.now();
        this.lastSuccessfulMessage = 0;
        this.isFailed = false;

        // Message queue for when disconnected (with max size to prevent memory bloat)
        this.messageQueue = [];
        this.maxQueueSize = 500;
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

        const url = `ws://${this.host}:${this.port}`;
        console.log(`[WS] Connecting to ${url}...`);

        this._emit('connecting', {
            attempt: this.reconnectAttempts,
            maxAttempts: this.maxReconnectAttempts
        });

        try {
            this.ws = new WebSocket(url);

            this.ws.onopen = () => this._onOpen();
            this.ws.onclose = (e) => this._onClose(e);
            this.ws.onerror = (e) => this._onError(e);
            this.ws.onmessage = (e) => this._onMessage(e);

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
        if (this.ws?.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(message));
            return true;
        } else {
            // Queue message for when reconnected (with FIFO eviction)
            if (this.messageQueue.length >= this.maxQueueSize) {
                // Remove oldest messages to make room
                this.messageQueue.shift();
            }
            this.messageQueue.push(message);
            console.warn('[WS] Not connected, message queued (' + this.messageQueue.length + '/' + this.maxQueueSize + ')');
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

    _onOpen() {
        console.log('[WS] Connected');
        this.isConnecting = false;
        this.reconnectAttempts = 0;

        this._emit('connected');
        this._startPingInterval();

        // Request initial state
        this.send({ type: 'get_patterns' });
        this.send({ type: 'get_state' });

        // Flush message queue
        while (this.messageQueue.length > 0) {
            const msg = this.messageQueue.shift();
            this.send(msg);
        }
    }

    _onClose(event) {
        console.log(`[WS] Disconnected (code: ${event.code})`);
        this.isConnecting = false;
        this._stopPingInterval();

        this._emit('disconnected', { code: event.code, reason: event.reason });

        if (this.shouldReconnect) {
            this._scheduleReconnect();
        }
    }

    _onError(error) {
        console.error('[WS] Error:', error);
        this._emit('error', { error });
    }

    _onMessage(event) {
        try {
            const data = JSON.parse(event.data);

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
            this.reconnectInterval * Math.pow(1.5, this.reconnectAttempts - 1),
            30000 // Max 30 seconds
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
