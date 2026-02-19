//! Lua-based pattern engine for dual-mode direct rendering
//!
//! Loads .lua pattern scripts and executes them each frame to produce
//! entity positions for batch_update messages to Minecraft.

use mlua::prelude::*;
use std::collections::HashMap;

/// Pattern configuration matching VJ server's PatternConfig
#[derive(Debug, Clone)]
pub struct PatternConfig {
    pub entity_count: u32,
    pub zone_size: f32,
    pub beat_boost: f32,
    pub base_scale: f32,
    pub max_scale: f32,
}

impl Default for PatternConfig {
    fn default() -> Self {
        Self {
            entity_count: 16,
            zone_size: 10.0,
            beat_boost: 1.5,
            base_scale: 0.2,
            max_scale: 1.0,
        }
    }
}

/// Lua pattern engine - executes Lua pattern scripts to generate entities
pub struct PatternEngine {
    lua: Lua,
    current_pattern: String,
    loaded_patterns: HashMap<String, String>,
    config: PatternConfig,
    band_sensitivity: [f32; 5],
    lib_source: Option<String>,
    pattern_loaded: bool,
}

impl PatternEngine {
    pub fn new() -> Self {
        let lua = Lua::new();
        Self {
            lua,
            current_pattern: String::new(),
            loaded_patterns: HashMap::new(),
            config: PatternConfig::default(),
            band_sensitivity: [1.0; 5],
            lib_source: None,
            pattern_loaded: false,
        }
    }

    /// Load lib.lua shared utilities
    pub fn load_lib(&mut self, source: &str) -> Result<(), String> {
        self.lib_source = Some(source.to_string());
        self.lua
            .load(source)
            .exec()
            .map_err(|e| format!("lib.lua load error: {}", e))
    }

    /// Store a pattern's source code
    pub fn load_pattern(&mut self, name: &str, source: &str) {
        self.loaded_patterns.insert(name.to_string(), source.to_string());
    }

    /// Switch to a different pattern
    pub fn set_pattern(&mut self, name: &str) -> Result<(), String> {
        let source = self
            .loaded_patterns
            .get(name)
            .ok_or_else(|| format!("Pattern '{}' not loaded", name))?
            .clone();

        // Reset Lua state for clean pattern switch
        self.lua = Lua::new();

        // Reload lib
        if let Some(ref lib) = self.lib_source {
            self.lua
                .load(lib.as_str())
                .exec()
                .map_err(|e| format!("lib.lua reload error: {}", e))?;
        }

        // Load pattern
        self.lua
            .load(source.as_str())
            .exec()
            .map_err(|e| format!("Pattern '{}' load error: {}", name, e))?;

        self.current_pattern = name.to_string();
        self.pattern_loaded = true;
        log::info!("Pattern engine: switched to '{}'", name);
        Ok(())
    }

    pub fn set_config(&mut self, config: PatternConfig) {
        self.config = config;
    }

    pub fn set_band_sensitivity(&mut self, sensitivity: [f32; 5]) {
        self.band_sensitivity = sensitivity;
    }

    /// Calculate entities by running the current Lua pattern
    pub fn calculate_entities(
        &self,
        analysis: &crate::audio::AnalysisResult,
        seq: u64,
    ) -> Vec<serde_json::Value> {
        if !self.pattern_loaded {
            return Vec::new();
        }

        match self.run_lua_pattern(analysis, seq) {
            Ok(entities) => entities,
            Err(e) => {
                log::warn!("Pattern calculation error: {}", e);
                Vec::new()
            }
        }
    }

    fn run_lua_pattern(
        &self,
        analysis: &crate::audio::AnalysisResult,
        _seq: u64,
    ) -> Result<Vec<serde_json::Value>, String> {
        let lua = &self.lua;

        // Build audio table with band sensitivity applied
        let audio_table = lua.create_table().map_err(|e| e.to_string())?;
        let bands_table = lua.create_table().map_err(|e| e.to_string())?;
        for i in 0..5 {
            let adjusted = (analysis.bands[i] * self.band_sensitivity[i]).clamp(0.0, 2.0);
            bands_table
                .set(i + 1, adjusted as f64)
                .map_err(|e| e.to_string())?; // Lua 1-indexed
        }
        audio_table
            .set("bands", bands_table)
            .map_err(|e| e.to_string())?;
        audio_table
            .set("amplitude", analysis.peak as f64)
            .map_err(|e| e.to_string())?;
        // Alias: some patterns use "peak" instead of "amplitude"
        audio_table
            .set("peak", analysis.peak as f64)
            .map_err(|e| e.to_string())?;
        audio_table
            .set("is_beat", analysis.is_beat)
            .map_err(|e| e.to_string())?;
        // Alias: some patterns use "beat" instead of "is_beat"
        audio_table
            .set("beat", analysis.is_beat)
            .map_err(|e| e.to_string())?;
        audio_table
            .set("beat_intensity", analysis.beat_intensity as f64)
            .map_err(|e| e.to_string())?;
        audio_table.set("frame", _seq).map_err(|e| e.to_string())?;

        // Build config table
        let config_table = lua.create_table().map_err(|e| e.to_string())?;
        config_table
            .set("entity_count", self.config.entity_count)
            .map_err(|e| e.to_string())?;
        config_table
            .set("zone_size", self.config.zone_size as f64)
            .map_err(|e| e.to_string())?;
        config_table
            .set("beat_boost", self.config.beat_boost as f64)
            .map_err(|e| e.to_string())?;
        config_table
            .set("base_scale", self.config.base_scale as f64)
            .map_err(|e| e.to_string())?;
        config_table
            .set("max_scale", self.config.max_scale as f64)
            .map_err(|e| e.to_string())?;

        // Call calculate(audio, config, dt)
        let calculate: LuaFunction = lua.globals().get("calculate").map_err(|e| e.to_string())?;
        let result: LuaTable = calculate
            .call((audio_table, config_table, 0.016f64))
            .map_err(|e| format!("calculate() error: {}", e))?;

        // Convert Lua table of entities to Vec<serde_json::Value>
        let mut entities = Vec::new();
        for pair in result.pairs::<i64, LuaTable>() {
            let (_, entity) = pair.map_err(|e| e.to_string())?;

            let id: String = entity
                .get("id")
                .unwrap_or_else(|_| "block_0".to_string());
            let x: f64 = entity.get("x").unwrap_or(0.5);
            let y: f64 = entity.get("y").unwrap_or(0.5);
            let z: f64 = entity.get("z").unwrap_or(0.5);
            let scale: f64 = entity.get("scale").unwrap_or(0.2);
            let band: i64 = entity.get("band").unwrap_or(0);
            let visible: bool = entity.get("visible").unwrap_or(true);

            entities.push(serde_json::json!({
                "id": id,
                "x": x,
                "y": y,
                "z": z,
                "scale": scale,
                "band": band,
                "visible": visible,
                "interpolation": 2
            }));
        }

        Ok(entities)
    }
}
