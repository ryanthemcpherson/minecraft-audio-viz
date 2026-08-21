/**
 * ConnectionLifecycle - Delegates WebSocket lifecycle events to control-panel
 * managers while retaining server state as the source of truth.
 */

export function setupConnectionLifecycle(app) {
    app.ws.addEventListener('connecting', (event) => {
        const detail = event.detail || {};
        app.ui.setConnectionStatus('connecting', detail.attempt, detail.maxAttempts);
    });

    app.ws.addEventListener('connected', () => {
        app.state.connected = true;
        app.ui.setConnectionStatus('connected');
        app.ui.showToast('Connected to server', 'success');

        const root = document.getElementById('app');
        root?.classList.add('just-connected');
        setTimeout(() => root?.classList.remove('just-connected'), 900);

        app.ws.send({ type: 'get_particle_effects' });
        app.ws.send({ type: 'get_stages' });
        app.ws.send({ type: 'get_zones' });
        app.ws.send({ type: 'get_zone', zone: app.state.zone.name });
        app.ws.send({ type: 'get_connect_codes' });
        app.ws.send({ type: 'get_pending_djs' });
        app.ws.send({ type: 'get_voice_status' });
        app.ws.send({ type: 'list_scenes' });

        app.bitmap.fetchBitmapData();

        if (!app.preview.previewInitialized && !app.preview.previewFailed) {
            app.preview.initPreview().then(() => {
                if (app.preview.previewInitialized && !app.preview.previewStripCollapsed) {
                    app.preview.startAnimation();
                }
            });
        }
    });

    app.ws.addEventListener('disconnected', () => {
        app.state.connected = false;
        app.state.minecraftConnected = false;
        app.state.bitmap.dataFetched = false;
        app.ui.setConnectionStatus('disconnected');
        app.ui.updateServiceIndicators();
        app.connectCodes.resetGenerateButton();
    });

    app.ws.addEventListener('auth_failed', (event) => {
        const detail = event.detail || {};
        const message = detail.error || 'Authentication failed';
        app.ui.setConnectionStatus('disconnected');
        const newPassword = prompt(`VJ Auth Failed: ${message}\nEnter VJ password:`);
        if (newPassword) {
            localStorage.setItem('mcav_vj_password', newPassword);
            app.ws.vjPassword = newPassword;
            app.ws.manualReconnect();
        }
    });

    app.ws.addEventListener('error', () => {
        app.ui.setConnectionStatus('error');
    });

    app.ws.addEventListener('reconnect_failed', () => {
        app.ui.setConnectionStatus('failed');
        app.ui.showToast('Connection failed. Click Reconnect to retry.', 'error', 0);
    });

    app.ws.addEventListener('message', (event) => {
        app.router.handleMessage(event.detail);
    });
}
