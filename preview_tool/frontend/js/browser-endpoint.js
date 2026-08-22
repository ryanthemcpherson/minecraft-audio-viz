/**
 * Resolve the browser's visualization WebSocket endpoint from the trusted
 * runtime configuration, preserving the standalone legacy transport.
 */
export function resolveBrowserWebSocketUrl(locationLike, searchParams, runtimeConfig = {}) {
    const scheme = locationLike.protocol === 'https:' ? 'wss' : 'ws';
    if (runtimeConfig.browserWebSocketMode === 'same-origin') {
        const path = runtimeConfig.browserWebSocketPath;
        if (typeof path === 'string' && /^\/[A-Za-z0-9/_-]*$/.test(path)) {
            return `${scheme}://${locationLike.host}${path}`;
        }
    }

    const override = Number.parseInt(searchParams.get('port'), 10);
    const configured = Number.parseInt(runtimeConfig.browserWebSocketPort, 10);
    const port = Number.isInteger(override) && override > 0 && override <= 65535
        ? override
        : (Number.isInteger(configured) ? configured : 8766);
    return `${scheme}://${locationLike.hostname}:${port}/`;
}
