package com.audioviz;

import com.audioviz.bedrock.BedrockPlayerListener;
import com.audioviz.bedrock.BedrockSupport;
import com.audioviz.beatsync.BeatSyncManager;
import com.audioviz.latency.LatencyTracker;
import com.audioviz.recording.RecordingManager;
import com.audioviz.bitmap.BitmapPatternManager;
import com.audioviz.bitmap.BitmapRendererBackend;
import com.audioviz.bitmap.composition.CompositionManager;
import com.audioviz.bitmap.effects.EffectsProcessor;
import com.audioviz.commands.AudioVizCommand;
import com.audioviz.connection.ConnectionStateListener;
import com.audioviz.metrics.MetricsDisplay;
import com.audioviz.decorators.StageDecoratorManager;
import com.audioviz.effects.BeatEventManager;
import com.audioviz.entities.EntityPoolManager;
import com.audioviz.lighting.AmbientLightManager;
import com.audioviz.entities.EntityUpdateStats;
import com.audioviz.listeners.AudioVizEventListener;
import com.audioviz.gui.ChatInputManager;
import com.audioviz.gui.MenuManager;
import com.audioviz.particles.ParticleVisualizationManager;
import com.audioviz.render.RendererRegistry;
import com.audioviz.sequence.SequenceManager;
import com.audioviz.sidecar.VjSidecarLaunchPlan;
import com.audioviz.sidecar.VjSidecarManager;
import com.audioviz.stages.StageManager;
import com.audioviz.stages.StageZonePlacementManager;
import com.audioviz.voice.VoicechatIntegration;
import com.audioviz.websocket.VizWebSocketServer;
import com.audioviz.websocket.WebSocketSecurityPolicy;
import com.audioviz.websocket.WebSocketSecretManager;
import com.audioviz.zones.ZoneBoundaryRenderer;
import com.audioviz.zones.ZoneEditor;
import com.audioviz.zones.ZoneManager;
import com.audioviz.zones.ZoneSelectionManager;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;

public class AudioVizPlugin extends JavaPlugin {

    private static AudioVizPlugin instance;
    private ZoneManager zoneManager;
    private EntityPoolManager entityPoolManager;
    private volatile WebSocketStartupManager<VizWebSocketServer> webSocketStartupManager;
    private volatile VjSidecarManager vjSidecarManager;
    private MenuManager menuManager;
    private ChatInputManager chatInputManager;
    private BeatEventManager beatEventManager;
    private ZoneEditor zoneEditor;
    private EntityUpdateStats entityUpdateStats;
    private ParticleVisualizationManager particleVisualizationManager;
    private RendererRegistry rendererRegistry;
    private StageManager stageManager;
    private StageDecoratorManager decoratorManager;
    private BedrockSupport bedrockSupport;
    private VoicechatIntegration voicechatIntegration;
    private ZoneBoundaryRenderer zoneBoundaryRenderer;
    private ZoneSelectionManager zoneSelectionManager;
    private StageZonePlacementManager zonePlacementManager;
    private BitmapRendererBackend bitmapRenderer;
    private BitmapPatternManager bitmapPatternManager;
    private CompositionManager compositionManager;
    private AmbientLightManager ambientLightManager;
    private ConnectionStateListener connectionStateListener;
    private MetricsDisplay metricsDisplay;
    private SequenceManager sequenceManager;
    private BeatSyncManager beatSyncManager;
    private LatencyTracker latencyTracker;
    private RecordingManager recordingManager;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();
        WebSocketSecretManager.SecretResolution webSocketSecret = prepareWebSocketSecret();

        // Initialize core managers
        this.zoneManager = new ZoneManager(this);
        this.entityPoolManager = new EntityPoolManager(this);
        this.ambientLightManager = new AmbientLightManager(getLogger());
        this.entityUpdateStats = new EntityUpdateStats();

        // Initialize GUI and effects managers
        this.menuManager = new MenuManager(this);
        this.chatInputManager = new ChatInputManager(this);
        this.beatEventManager = new BeatEventManager(this);
        this.zoneEditor = new ZoneEditor(this);
        this.zoneBoundaryRenderer = new ZoneBoundaryRenderer(this);
        this.zoneBoundaryRenderer.start();
        this.zoneSelectionManager = new ZoneSelectionManager(this);
        this.zoneSelectionManager.start();

        // Detect Geyser/Floodgate for Bedrock player support
        this.bedrockSupport = new BedrockSupport(getLogger(), getConfig());
        this.bedrockSupport.detect();

        // Initialize particle visualization manager (for Bedrock compatibility)
        this.particleVisualizationManager = new ParticleVisualizationManager(this, bedrockSupport);
        this.particleVisualizationManager.start();

        // Initialize renderer backend registry (backend selection + capability reporting)
        this.rendererRegistry = new RendererRegistry(this);

        // Initialize bitmap rendering subsystem
        this.bitmapRenderer = new BitmapRendererBackend(this, entityPoolManager);
        this.bitmapPatternManager = new BitmapPatternManager(this, bitmapRenderer);
        this.bitmapPatternManager.start();
        this.compositionManager = new CompositionManager();

        // Initialize connection state listener (DJ connect/disconnect + audio staleness)
        this.connectionStateListener = new ConnectionStateListener(this);
        this.connectionStateListener.start();

        // Initialize performance metrics display (scoreboard sidebar)
        this.metricsDisplay = new MetricsDisplay(this);
        this.metricsDisplay.start();

        // Initialize pattern sequence manager
        this.sequenceManager = new SequenceManager(this);
        this.sequenceManager.loadSequences();
        this.sequenceManager.start();

        // Initialize beat sync manager
        this.beatSyncManager = new BeatSyncManager(this);
        this.beatSyncManager.load();

        // Initialize latency tracker (pure Java, no Bukkit deps)
        this.latencyTracker = new LatencyTracker();

        // Initialize recording manager (record & replay audio sessions)
        this.recordingManager = new RecordingManager(this);

        // Register event listeners
        getServer().getPluginManager().registerEvents(new AudioVizEventListener(this), this);
        getServer().getPluginManager().registerEvents(menuManager, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(zoneEditor, this);
        getServer().getPluginManager().registerEvents(zoneSelectionManager, this);
        getServer().getPluginManager().registerEvents(new BedrockPlayerListener(bedrockSupport), this);

        // Initialize stage manager
        this.stageManager = new StageManager(this);

        // Initialize zone placement manager
        this.zonePlacementManager = new StageZonePlacementManager(this);

        // Initialize stage decorator manager
        this.decoratorManager = new StageDecoratorManager(this);
        this.decoratorManager.start();

        // Load zones and stages from config
        zoneManager.loadZones();
        stageManager.loadStages();

        // Register commands
        AudioVizCommand commandExecutor = new AudioVizCommand(this);
        getCommand("audioviz").setExecutor(commandExecutor);
        getCommand("audioviz").setTabCompleter(commandExecutor);

        // Register /stage shortcut (delegates to "audioviz stage <args>")
        var stageCmd = getCommand("stage");
        if (stageCmd != null) {
            stageCmd.setExecutor((sender, cmd, label, args) -> {
                String[] newArgs = new String[args.length + 1];
                newArgs[0] = "stage";
                System.arraycopy(args, 0, newArgs, 1, args.length);
                return commandExecutor.onCommand(sender, cmd, label, newArgs);
            });
            stageCmd.setTabCompleter((sender, cmd, label, args) -> {
                String[] newArgs = new String[args.length + 1];
                newArgs[0] = "stage";
                System.arraycopy(args, 0, newArgs, 1, args.length);
                return commandExecutor.onTabComplete(sender, cmd, label, newArgs);
            });
        }

        // Detect Simple Voice Chat for audio streaming support
        // Delay by 1 tick so SVC has time to register its BukkitVoicechatService
        // (softdepend doesn't guarantee load order on Paper)
        getServer().getScheduler().runTask(this, () -> {
            try {
                var voicechatService = getServer().getServicesManager().load(
                        Class.forName("de.maxhenkel.voicechat.api.BukkitVoicechatService"));
                if (voicechatService != null) {
                    voicechatIntegration = new VoicechatIntegration(this);
                    var registerMethod = voicechatService.getClass().getMethod("registerPlugin",
                            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin"));
                    registerMethod.invoke(voicechatService, voicechatIntegration);
                    getLogger().info("Simple Voice Chat detected - audio streaming enabled");
                } else {
                    getLogger().info("Simple Voice Chat not installed - audio streaming disabled");
                }
            } catch (ClassNotFoundException e) {
                getLogger().info("Simple Voice Chat not installed - audio streaming disabled");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to initialize Simple Voice Chat integration", e);
            }
        });

        startWebSocketListener(webSocketSecret);
        startVjSidecar(webSocketSecret, System.getenv(), System.getProperty("os.arch", ""));

        getLogger().info("AudioViz plugin enabled!");
    }

    WebSocketSecretManager.SecretResolution prepareWebSocketSecret() {
        WebSocketSecretManager.SecretResolution resolution =
            new WebSocketSecretManager(new SecureRandom()).resolve(
                getConfig().getString("ws-secret", "")
            );
        if (!resolution.generated()) {
            return resolution;
        }

        getConfig().set("ws-secret", resolution.secret());
        try {
            saveConfig();
        } catch (RuntimeException persistenceFailure) {
            getLogger().severe(
                "Unable to persist the WebSocket pairing secret; " +
                "the WebSocket listener will remain offline."
            );
            return null;
        }

        getLogger().info(
            "Generated a WebSocket pairing secret in plugins/AudioViz/config.yml. " +
            "Set MINECRAFT_WS_SECRET to that value before starting the VJ server."
        );
        return resolution;
    }

    void startWebSocketListener(WebSocketSecretManager.SecretResolution secretResolution) {
        if (secretResolution == null) {
            return;
        }

        int wsPort = getConfig().getInt("websocket.port", 8765);
        String wsAddress = getConfig().getString("websocket.address", "127.0.0.1");
        if (WebSocketSecurityPolicy.isSafeConfiguration(wsAddress, secretResolution.secret())) {
            startWebSocketWithRetry(wsAddress.strip(), wsPort, 5, 2000);
        } else {
            getLogger().severe(
                "AudioViz WebSocket listener is offline: bind to a loopback address " +
                "(127.0.0.1, localhost, or ::1). For a remote VJ server, use an " +
                "encrypted tunnel whose Minecraft-side endpoint is loopback."
            );
        }
    }

    /**
     * Start the WebSocket server with retries to handle port still held by a
     * previous process (e.g. zombie Java after restart). Each attempt waits
     * {@code delayMs} before retrying, up to {@code maxRetries} times.
     */
    void startWebSocketWithRetry(
        String bindAddress,
        int port,
        int maxRetries,
        long delayMs
    ) {
        WebSocketStartupManager<VizWebSocketServer> startupManager =
            new WebSocketStartupManager<>(
                maxRetries,
                delayMs,
                () -> {
                    VizWebSocketServer server = new VizWebSocketServer(
                        this,
                        bindAddress,
                        port
                    );
                    return new WebSocketStartupManager.Candidate<>() {
                        @Override
                        public VizWebSocketServer value() {
                            return server;
                        }

                        @Override
                        public void start() {
                            server.start();
                        }

                        @Override
                        public CompletionStage<Void> startupCompletion() {
                            return server.startupCompletion();
                        }

                        @Override
                        public void shutdown() {
                            server.shutdown();
                        }
                    };
                },
                task -> {
                    Thread thread = new Thread(task, "AudioViz-WebSocket-Startup");
                    thread.setDaemon(true);
                    thread.start();
                    return thread;
                },
                Thread::sleep,
                new WebSocketStartupManager.Events<>() {
                    @Override
                    public void onStarted(VizWebSocketServer server, int attempt) {
                        getLogger().info("WebSocket server started on port " + port +
                            (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                    }

                    @Override
                    public void onRetry(
                        int attempt,
                        int maxAttempts,
                        long retryDelayMillis,
                        Throwable failure
                    ) {
                        getLogger().warning("WebSocket bind attempt " + attempt + "/" + maxAttempts +
                            " failed: " + failureMessage(failure) + " — retrying in " +
                            (retryDelayMillis / 1000) + "s");
                    }

                    @Override
                    public void onExhausted(int maxAttempts, Throwable failure) {
                        getLogger().log(
                            Level.SEVERE,
                            "Failed to start WebSocket server after " + maxAttempts + " attempts",
                            failure
                        );
                    }

                    @Override
                    public void onShutdownFailure(Throwable failure) {
                        getLogger().warning(
                            "Error stopping WebSocket server: " + failureMessage(failure));
                    }
                }
            );
        webSocketStartupManager = startupManager;
        startupManager.start();
    }

    private static String failureMessage(Throwable failure) {
        if (failure.getMessage() != null) {
            return failure.getMessage();
        }
        return failure.getClass().getSimpleName();
    }

    void startVjSidecar(
        WebSocketSecretManager.SecretResolution secretResolution,
        Map<String, String> environment,
        String architecture
    ) {
        if (secretResolution == null) {
            return;
        }
        try {
            VjSidecarLaunchPlan plan = createVjSidecarLaunchPlan(
                getDataFolder().toPath(),
                architecture,
                environment,
                secretResolution.secret()
            );
            VjSidecarManager manager = createVjSidecarManager(plan);
            vjSidecarManager = manager;
            manager.start();
        } catch (IOException | IllegalArgumentException failure) {
            getLogger().info("Bundled VJ sidecar not started: " + failure.getMessage());
        }
    }

    VjSidecarLaunchPlan createVjSidecarLaunchPlan(
        Path pluginDataDirectory,
        String architecture,
        Map<String, String> environment,
        String sharedSecret
    ) throws IOException {
        return VjSidecarLaunchPlan.create(
            pluginDataDirectory,
            architecture,
            environment,
            sharedSecret
        );
    }

    VjSidecarManager createVjSidecarManager(VjSidecarLaunchPlan plan) {
        return new VjSidecarManager(
            plan,
            message -> getLogger().info("[MCAV VJ] " + message)
        );
    }

    @Override
    public void onDisable() {
        VjSidecarManager sidecarManager = vjSidecarManager;
        if (sidecarManager != null) {
            sidecarManager.stop();
        }

        // Close the startup boundary first so no candidate can publish, retry,
        // or schedule server tasks while the rest of plugin teardown runs.
        WebSocketStartupManager<VizWebSocketServer> startupManager = webSocketStartupManager;
        if (startupManager != null) {
            if (startupManager.stop()) {
                getLogger().info("WebSocket server stopped");
            }
        }

        // Unregister all event listeners to prevent leaks on plugin reload
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);

        // Clear menu manager sessions (prevent stale player references)
        if (menuManager != null) {
            menuManager.clearAllSessions();
        }

        // Cancel active placement sessions
        if (zonePlacementManager != null) {
            zonePlacementManager.cancelAll();
        }

        // Save zones and stages
        if (stageManager != null) {
            stageManager.saveStages();
        }
        if (zoneManager != null) {
            zoneManager.saveZones();
        }

        // Stop decorator manager before entity cleanup
        if (decoratorManager != null) {
            decoratorManager.stop();
        }

        // Teardown ambient lights
        if (ambientLightManager != null) ambientLightManager.teardownAll();

        // Shutdown bitmap pattern manager
        if (bitmapPatternManager != null) {
            bitmapPatternManager.shutdown();
        }

        // Stop recording manager
        if (recordingManager != null) {
            recordingManager.stop();
        }

        // Save beat sync config
        if (beatSyncManager != null) {
            beatSyncManager.save();
        }

        // Stop sequence manager and save state
        if (sequenceManager != null) {
            sequenceManager.stop();
            sequenceManager.saveSequences();
        }

        // Stop metrics display
        if (metricsDisplay != null) {
            metricsDisplay.stop();
        }

        // Stop connection state listener
        if (connectionStateListener != null) {
            connectionStateListener.stop();
        }

        // Shutdown voice chat integration
        if (voicechatIntegration != null) {
            try {
                voicechatIntegration.shutdown();
            } catch (Exception e) {
                getLogger().warning("Error shutting down voice chat integration: " + e.getMessage());
            }
        }

        // Stop zone selection manager
        if (zoneSelectionManager != null) {
            zoneSelectionManager.stop();
        }

        // Stop zone boundary renderer
        if (zoneBoundaryRenderer != null) {
            zoneBoundaryRenderer.stop();
        }

        // Stop particle visualization manager
        if (particleVisualizationManager != null) {
            particleVisualizationManager.stop();
        }

        if (rendererRegistry != null) {
            rendererRegistry.clearAll();
        }

        // Cleanup entity pools synchronously - scheduling new tasks while disabled
        // can throw IllegalPluginAccessException during reload.
        if (entityPoolManager != null) {
            entityPoolManager.cleanupAllSync();
        }

        getLogger().info("AudioViz plugin disabled!");
    }

    public static AudioVizPlugin getInstance() {
        return instance;
    }

    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    public EntityPoolManager getEntityPoolManager() {
        return entityPoolManager;
    }

    public VizWebSocketServer getWebSocketServer() {
        WebSocketStartupManager<VizWebSocketServer> startupManager = webSocketStartupManager;
        return startupManager == null ? null : startupManager.active();
    }

    public MenuManager getMenuManager() {
        return menuManager;
    }

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    public BeatEventManager getBeatEventManager() {
        return beatEventManager;
    }

    public ZoneEditor getZoneEditor() {
        return zoneEditor;
    }

    public EntityUpdateStats getEntityUpdateStats() {
        return entityUpdateStats;
    }

    public ParticleVisualizationManager getParticleVisualizationManager() {
        return particleVisualizationManager;
    }

    public RendererRegistry getRendererRegistry() {
        return rendererRegistry;
    }

    public StageManager getStageManager() {
        return stageManager;
    }

    public StageDecoratorManager getDecoratorManager() {
        return decoratorManager;
    }

    public BedrockSupport getBedrockSupport() {
        return bedrockSupport;
    }

    public VoicechatIntegration getVoicechatIntegration() {
        return voicechatIntegration;
    }

    public ZoneBoundaryRenderer getZoneBoundaryRenderer() {
        return zoneBoundaryRenderer;
    }

    public ZoneSelectionManager getZoneSelectionManager() {
        return zoneSelectionManager;
    }

    public StageZonePlacementManager getZonePlacementManager() {
        return zonePlacementManager;
    }

    public BitmapRendererBackend getBitmapRenderer() {
        return bitmapRenderer;
    }

    public BitmapPatternManager getBitmapPatternManager() {
        return bitmapPatternManager;
    }

    public CompositionManager getCompositionManager() {
        return compositionManager;
    }

    public AmbientLightManager getAmbientLightManager() {
        return ambientLightManager;
    }

    public ConnectionStateListener getConnectionStateListener() {
        return connectionStateListener;
    }

    public MetricsDisplay getMetricsDisplay() {
        return metricsDisplay;
    }

    public SequenceManager getSequenceManager() {
        return sequenceManager;
    }

    public BeatSyncManager getBeatSyncManager() {
        return beatSyncManager;
    }

    public LatencyTracker getLatencyTracker() {
        return latencyTracker;
    }

    public RecordingManager getRecordingManager() {
        return recordingManager;
    }

    /**
     * Convenience getter for the global bitmap effects processor.
     * Delegates to BitmapPatternManager which owns the EffectsProcessor.
     */
    public EffectsProcessor getGlobalBitmapEffects() {
        return bitmapPatternManager != null ? bitmapPatternManager.getEffectsProcessor() : null;
    }
}
