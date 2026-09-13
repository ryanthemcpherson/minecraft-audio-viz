use mlua::{Function, HookTriggers, Lua, Table, VmState};
use serde::{Deserialize, Serialize};
use std::{cell::Cell, collections::HashMap, rc::Rc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Audio {
    pub bands: [f64; 5],
    pub amplitude: f64,
    pub is_beat: bool,
    pub beat_intensity: f64,
    pub frame: usize,
    pub bpm: f64,
    pub beat_phase: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Entity {
    pub id: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub scale: f64,
    pub rotation: f64,
    pub band: i32,
    pub visible: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub glow: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brightness: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub material: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interpolation: Option<i32>,
}

pub fn pattern_source(name: &str) -> mlua::Result<&'static str> {
    match name {
        "spectrum" => Ok(include_str!("../../../patterns/spectrum.lua")),
        "bars" => Ok(include_str!("../../../patterns/bars.lua")),
        "aurora" => Ok(include_str!("../../../patterns/aurora.lua")),
        "shockwave" => Ok(include_str!("../../../patterns/shockwave.lua")),
        _ => Err(mlua::Error::runtime(
            "pattern must be spectrum, bars, aurora, or shockwave",
        )),
    }
}

pub struct Runtime {
    _lua: Lua,
    calculate: Function,
    audio: Table,
    bands: Table,
    config: Table,
    instructions: Rc<Cell<usize>>,
    previous: HashMap<String, Entity>,
    count: usize,
}

impl Runtime {
    pub fn new(count: usize) -> mlua::Result<Self> {
        Self::with_pattern(count, "spectrum", 1234)
    }

    pub fn with_pattern(count: usize, pattern: &str, seed: i64) -> mlua::Result<Self> {
        if !(1..=256).contains(&count) {
            return Err(mlua::Error::runtime("entity count must be 1..256"));
        }
        let source = pattern_source(pattern)?;
        let lua = Lua::new();
        lua.set_memory_limit(16 * 1024 * 1024)?;
        let instructions = Rc::new(Cell::new(0));
        let counter = instructions.clone();
        lua.set_hook(
            HookTriggers::new().every_nth_instruction(1000),
            move |_, _| {
                counter.set(counter.get() + 1000);
                if counter.get() >= 1_000_000 {
                    return Err(mlua::Error::runtime("pattern exceeded instruction limit"));
                }
                Ok(VmState::Continue)
            },
        )?;
        lua.load("os=nil; io=nil; debug=nil; package=nil; coroutine=nil; require=nil; load=nil; loadfile=nil; dofile=nil; collectgarbage=nil; rawget=nil; rawset=nil; pcall=nil; xpcall=nil; rawequal=nil; rawlen=nil; string.dump=nil; string.rep=nil").exec()?;
        lua.load(include_str!("../../../patterns/lib.lua")).exec()?;
        // The production Python bridge bounds the result before trusted flat_pack,
        // normalizes IDs by index, and retains tables for optional fields.
        let wrapper: Function = lua
            .load(
                r#"
            local pack = flat_pack
            return function(original)
                return function(audio, config, dt)
                    local result = original(audio, config, dt)
                    local bounded = {}
                    if result then
                        for i = 1, config.entity_count do
                            local entity = result[i]
                            if entity == nil then break end
                            bounded[i] = entity
                        end
                    end
                    local flat, count = pack(bounded)
                    return flat, bounded, count
                end
            end
        "#,
            )
            .eval()?;
        lua.load(source).exec()?;
        let randomseed: Function = lua.globals().get::<Table>("math")?.get("randomseed")?;
        randomseed.call::<()>((seed, 0))?;
        let calculate = wrapper.call(lua.globals().get::<Function>("calculate")?)?;
        let audio = lua.create_table()?;
        let bands = lua.create_table()?;
        audio.set("bands", bands.clone())?;
        let config = lua.create_table()?;
        config.set("entity_count", count)?;
        for (key, value) in [
            ("zone_size", 10.0),
            ("beat_boost", 1.5),
            ("base_scale", 0.2),
            ("max_scale", 1.0),
        ] {
            config.set(key, value)?;
        }
        Ok(Self {
            _lua: lua,
            calculate,
            audio,
            bands,
            config,
            instructions,
            previous: HashMap::new(),
            count,
        })
    }

    pub fn render(&mut self, audio: &Audio, dt: f64) -> mlua::Result<Vec<Entity>> {
        if !dt.is_finite()
            || dt <= 0.0
            || dt > 0.05
            || !audio
                .bands
                .iter()
                .chain([
                    &audio.amplitude,
                    &audio.beat_intensity,
                    &audio.bpm,
                    &audio.beat_phase,
                ])
                .all(|value| value.is_finite())
        {
            return Err(mlua::Error::runtime("invalid audio/dt"));
        }
        for (index, value) in audio.bands.iter().enumerate() {
            self.bands.set(index + 1, *value)?;
        }
        for (key, value) in [
            ("amplitude", audio.amplitude),
            ("peak", audio.amplitude),
            ("beat_intensity", audio.beat_intensity),
            ("bpm", audio.bpm),
            ("beat_phase", audio.beat_phase),
        ] {
            self.audio.set(key, value)?;
        }
        self.audio.set("frame", audio.frame)?;
        self.audio.set("is_beat", audio.is_beat)?;
        self.audio.set("beat", audio.is_beat)?;
        self.instructions.set(0);
        let (flat, result, returned): (Table, Table, usize) =
            self.calculate.call((&self.audio, &self.config, dt))?;
        let returned = returned.min(self.count);
        let mut entities = Vec::with_capacity(self.count);
        for index in 0..returned {
            let entry: Table = result.get(index + 1)?;
            let offset = index * 7;
            let mut entity = Entity {
                id: format!("block_{index}"),
                x: flat.get(offset + 1)?,
                y: flat.get(offset + 2)?,
                z: flat.get(offset + 3)?,
                scale: flat.get(offset + 4)?,
                rotation: flat.get::<f64>(offset + 5)?.rem_euclid(360.0),
                band: flat.get::<f64>(offset + 6)? as i32,
                visible: flat.get::<f64>(offset + 7)? != 0.0,
                glow: entry.get("glow")?,
                brightness: entry.get("brightness")?,
                material: entry.get("material")?,
                interpolation: entry.get("interpolation")?,
            };
            if ![entity.x, entity.y, entity.z, entity.scale, entity.rotation]
                .iter()
                .all(|value| value.is_finite())
            {
                return Err(mlua::Error::runtime("invalid entity output"));
            }
            if let Some(previous) = self.previous.get(&entity.id) {
                smooth(&mut entity, previous, dt);
            }
            entities.push(entity);
        }
        for index in 0..self.count - returned {
            entities.push(Entity {
                id: format!("__pad_{index}"),
                x: 0.5,
                y: 0.5,
                z: 0.5,
                scale: 0.0,
                rotation: 0.0,
                band: 0,
                visible: false,
                glow: None,
                brightness: None,
                material: None,
                interpolation: None,
            });
        }
        self.previous = entities
            .iter()
            .map(|entity| (entity.id.clone(), entity.clone()))
            .collect();
        Ok(entities)
    }
}

// Exact post-Lua behavior of LuaPattern._smooth_entity, checked against that
// production implementation over every fixture frame (not only Lua targets).
fn smooth(entity: &mut Entity, previous: &Entity, dt: f64) {
    let ratio = dt / 0.016;
    let delta = (entity.x - previous.x)
        .abs()
        .max((entity.y - previous.y).abs())
        .max((entity.z - previous.z).abs());
    let position_alpha = 1.0 - (if delta > 0.035 { 0.22_f64 } else { 0.52_f64 }).powf(ratio);
    for (value, old) in [
        (&mut entity.x, previous.x),
        (&mut entity.y, previous.y),
        (&mut entity.z, previous.z),
    ] {
        *value = old + (*value - old) * position_alpha;
        if (*value - old).abs() < 0.0015 {
            *value = old;
        }
    }
    let scale_alpha = 1.0
        - (if entity.scale > previous.scale {
            0.16_f64
        } else {
            0.44_f64
        })
        .powf(ratio);
    entity.scale = previous.scale + (entity.scale - previous.scale) * scale_alpha;
    let current = previous.rotation.rem_euclid(360.0);
    let delta = (entity.rotation.rem_euclid(360.0) - current + 180.0).rem_euclid(360.0) - 180.0;
    entity.rotation = (current + delta * (1.0 - 0.48_f64.powf(ratio))).rem_euclid(360.0);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(frame: usize) -> Audio {
        Audio {
            bands: [0.8, 0.6, 0.3, 0.2, 0.7],
            amplitude: 0.8,
            is_beat: frame % 12 == 0,
            beat_intensity: 0.9,
            frame,
            bpm: 120.0,
            beat_phase: (frame % 30) as f64 / 30.0,
        }
    }

    #[test]
    fn independent_lua_state_and_seeded_randomness() {
        for pattern in ["spectrum", "bars", "aurora", "shockwave"] {
            let mut first = Runtime::with_pattern(64, pattern, 1234).unwrap();
            let mut same = Runtime::with_pattern(64, pattern, 1234).unwrap();
            let mut different = Runtime::with_pattern(64, pattern, 5678).unwrap();
            let mut noisy_neighbor = Runtime::with_pattern(128, pattern, 1234).unwrap();
            let mut seeds_differ = false;
            for frame in 0..90 {
                let expected = first.render(&fixture(frame), 0.016).unwrap();
                // Other zones advance by unrelated amounts between equal-state calls.
                for extra in 0..3 {
                    noisy_neighbor
                        .render(&fixture(frame * 3 + extra), 0.016)
                        .unwrap();
                }
                assert_eq!(
                    expected,
                    same.render(&fixture(frame), 0.016).unwrap(),
                    "{pattern}, frame {frame}"
                );
                seeds_differ |= expected != different.render(&fixture(frame), 0.016).unwrap();
            }
            assert_eq!(
                seeds_differ,
                matches!(pattern, "aurora" | "shockwave"),
                "{pattern}"
            );
        }
    }

    #[test]
    fn normalized_lua_padding_has_flat_ids_and_keeps_zero_scale() {
        for (pattern, last_live) in [("bars", 59), ("aurora", 62)] {
            let mut runtime = Runtime::with_pattern(64, pattern, 1234).unwrap();
            let entities = runtime.render(&fixture(0), 0.016).unwrap();
            assert_eq!(entities.len(), 64);
            for (index, entity) in entities.iter().enumerate() {
                assert_eq!(entity.id, format!("block_{index}"));
                if index > last_live {
                    assert_eq!(entity.scale, 0.0);
                    assert!(!entity.visible);
                }
            }
        }
    }

    #[test]
    fn flat_defaults_and_short_results_do_not_mix_padding_smoothing_state() {
        let mut runtime = Runtime::with_pattern(3, "bars", 1234).unwrap();
        runtime
            ._lua
            .load(
                r#"
            normalize_entities = function()
                return {{id="ignored", x=0, scale=0, rotation=-10, visible=false}}
            end
        "#,
            )
            .exec()
            .unwrap();
        let first = runtime.render(&fixture(0), 0.016).unwrap();
        assert_eq!(first[0].id, "block_0");
        assert_eq!(
            (first[0].x, first[0].y, first[0].scale, first[0].rotation),
            (0.0, 0.5, 0.0, 350.0)
        );
        assert!(!first[0].visible);
        assert_eq!(first[1].id, "__pad_0");
        assert_eq!(first[2].id, "__pad_1");
        runtime
            ._lua
            .load(
                r#"
            normalize_entities = function()
                return {{x=1}, {x=0.2, rotation=10}}
            end
        "#,
            )
            .exec()
            .unwrap();
        let second = runtime.render(&fixture(1), 0.016).unwrap();
        assert_eq!(second[0].x, 0.78);
        assert_eq!(second[1].id, "block_1");
        assert_eq!(second[1].x, 0.2); // New ID must not smooth from __pad_0.
        assert_eq!(second[1].scale, 0.2);
        assert!(second[1].visible);
        assert!(!runtime.previous.contains_key("__pad_1"));
    }

    #[test]
    fn shockwave_preserves_optional_fields_and_omits_them_when_idle() {
        let mut runtime = Runtime::with_pattern(64, "shockwave", 1234).unwrap();
        let mut audio = fixture(1);
        let idle = runtime.render(&audio, 0.016).unwrap();
        for entity in idle {
            let json = serde_json::to_value(entity).unwrap();
            for key in ["glow", "brightness", "material", "interpolation"] {
                assert!(json.get(key).is_none());
            }
        }
        audio.is_beat = true;
        let active = runtime.render(&audio, 0.016).unwrap();
        for entity in active {
            assert_eq!(entity.glow, Some(true));
            assert_eq!(entity.brightness, Some(14));
            assert_eq!(entity.material.as_deref(), Some("SEA_LANTERN"));
            assert_eq!(entity.interpolation, Some(1));
        }
    }

    #[test]
    fn bounds_all_pattern_outputs_at_small_and_maximum_budgets() {
        assert!(Runtime::with_pattern(1, "../custom", 0).is_err());
        for pattern in ["spectrum", "bars", "aurora", "shockwave"] {
            for count in [1, 2, 64, 128, 256] {
                let mut runtime = Runtime::with_pattern(count, pattern, 1234).unwrap();
                for frame in 0..3 {
                    let entities = runtime.render(&fixture(frame), 0.016).unwrap();
                    assert_eq!(entities.len(), count);
                    assert_eq!(runtime.previous.len(), count);
                    for entity in entities {
                        assert!(
                            [entity.x, entity.y, entity.z, entity.scale, entity.rotation]
                                .iter()
                                .all(|value| value.is_finite())
                        );
                    }
                }
            }
        }
    }

    #[test]
    fn rejects_invalid_inputs_and_bounds_execution() {
        assert!(Runtime::new(0).is_err());
        assert!(Runtime::new(257).is_err());
        let mut runtime = Runtime::new(8).unwrap();
        for global in ["os", "io", "debug", "require", "load", "package"] {
            assert!(matches!(
                runtime._lua.globals().get::<mlua::Value>(global).unwrap(),
                mlua::Value::Nil
            ));
        }
        assert!(
            runtime
                ._lua
                .create_table_with_capacity(4 * 1024 * 1024, 0)
                .is_err()
        );
        let audio = Audio {
            bands: [0.0; 5],
            amplitude: 0.0,
            is_beat: false,
            beat_intensity: 0.0,
            frame: 0,
            bpm: 0.0,
            beat_phase: 0.0,
        };
        assert!(runtime.render(&audio, f64::NAN).is_err());
        runtime.calculate = runtime
            ._lua
            .load("return function() while true do end end")
            .eval()
            .unwrap();
        assert!(
            runtime
                .render(&audio, 0.016)
                .unwrap_err()
                .to_string()
                .contains("instruction limit")
        );
    }
}
