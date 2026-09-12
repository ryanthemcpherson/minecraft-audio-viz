use mlua::{Function, HookTriggers, Lua, Table, VmState};
use serde::{Deserialize, Serialize};
use std::{cell::Cell, rc::Rc};

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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub scale: f64,
    pub rotation: f64,
    pub band: i32,
    pub visible: bool,
}

pub struct Runtime {
    _lua: Lua,
    calculate: Function,
    audio: Table,
    bands: Table,
    config: Table,
    instructions: Rc<Cell<usize>>,
    previous: Vec<Entity>,
    count: usize,
}

impl Runtime {
    pub fn new(count: usize) -> mlua::Result<Self> {
        if !(1..=256).contains(&count) {
            return Err(mlua::Error::runtime("entity count must be 1..256"));
        }
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
        lua.load(include_str!("../../../patterns/spectrum.lua"))
            .exec()?;
        let calculate = lua.globals().get("calculate")?;
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
            previous: Vec::new(),
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
        let result: Table = self.calculate.call((&self.audio, &self.config, dt))?;
        if result.raw_len() != self.count {
            return Err(mlua::Error::runtime("unexpected entity count"));
        }
        let mut entities = Vec::with_capacity(self.count);
        for index in 0..self.count {
            let entry: Table = result.get(index + 1)?;
            let mut entity = Entity {
                id: entry.get("id")?,
                x: entry.get("x")?,
                y: entry.get("y")?,
                z: entry.get("z")?,
                scale: entry.get("scale")?,
                rotation: entry.get::<Option<f64>>("rotation")?.unwrap_or(0.0),
                band: entry.get("band")?,
                visible: entry.get("visible")?,
            };
            if entity.id != format!("block_{index}")
                || ![entity.x, entity.y, entity.z, entity.scale, entity.rotation]
                    .iter()
                    .all(|value| value.is_finite())
            {
                return Err(mlua::Error::runtime("invalid entity output"));
            }
            if let Some(previous) = self.previous.get(index) {
                smooth(&mut entity, previous, dt);
            }
            entities.push(entity);
        }
        self.previous.clone_from(&entities);
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
