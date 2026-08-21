export function deriveControlState({ connected, minecraftConnected }) {
    return {
        connectionState: connected ? 'connected' : 'stale',
        disableNetworkControls: !connected,
        disableMinecraftControls: !connected || !minecraftConnected,
    };
}
