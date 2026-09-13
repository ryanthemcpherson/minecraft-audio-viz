//! Isolated multi-zone comparison driver. No production transport or runtime API.
use super::*;
use std::collections::HashSet;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Zone {
    pub name: String,
    pub pattern: String,
    pub entity_count: usize,
    pub seed: i64,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub zones: Vec<Zone>,
}

impl Config {
    pub fn read(path: &str) -> Result<Self> {
        if fs::metadata(path)?.len() > 16_384 {
            return Err("matrix config exceeds 16 KiB".into());
        }
        let config: Self = serde_json::from_reader(File::open(path)?)?;
        config.validate()?;
        Ok(config)
    }

    fn validate(&self) -> Result<()> {
        if !(1..=4).contains(&self.zones.len()) {
            return Err("matrix requires 1..4 zones".into());
        }
        let mut names = HashSet::new();
        for zone in &self.zones {
            if zone.name.is_empty()
                || zone.name.len() > 64
                || !zone.name.as_bytes()[0].is_ascii_lowercase()
                || !zone
                    .name
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
                || !names.insert(&zone.name)
            {
                return Err("zone names must be unique lowercase ASCII identifiers of 1..64 characters, starting with a letter".into());
            }
            if !(0..=2_147_483_647).contains(&zone.seed) {
                return Err("seed must be 0..2147483647".into());
            }
            if !(1..=256).contains(&zone.entity_count) {
                return Err("entity count must be 1..256 per zone".into());
            }
            runtime::pattern_source(&zone.pattern)?;
        }
        Ok(())
    }
}

#[derive(Serialize)]
struct Batch<'a> {
    #[serde(rename = "type")]
    kind: &'static str,
    zone: &'a str,
    entities: &'a [runtime::Entity],
}

#[derive(Serialize)]
struct ZoneRecord<'a> {
    zone: &'a str,
    pattern: &'a str,
    entities: Vec<runtime::Entity>,
    runtime_us: f64,
    serialize_us: f64,
    send_us: f64,
    bytes: usize,
}

#[derive(Serialize)]
struct Record<'a> {
    frame: usize,
    #[serde(flatten)]
    feature: Feature,
    zones: Vec<ZoneRecord<'a>>,
    lateness_us: f64,
    frame_work_us: f64,
    send_offset_ms: f64,
}

pub fn render(
    frame_count: usize,
    mut feature_at: impl FnMut(usize) -> Feature,
    config: &Config,
    output: &str,
    port: Option<u16>,
) -> Result<()> {
    config.validate()?;
    if frame_count == 0 || frame_count > 7200 {
        return Err("feature count must be 1..7200".into());
    }
    let mut runtimes = config
        .zones
        .iter()
        .map(|zone| Runtime::with_pattern(zone.entity_count, &zone.pattern, zone.seed))
        .collect::<mlua::Result<Vec<_>>>()?;
    let mut socket = open_socket(port)?;
    // Maximum 7200 frames x 4 zones x 256 entities. Typed records avoid the
    // additional per-field JSON allocations in the legacy single-zone driver.
    let mut records = Vec::with_capacity(frame_count);
    let epoch = Instant::now();
    for index in 0..frame_count {
        let deadline = epoch + Duration::from_secs_f64(index as f64 * DT);
        if socket.is_some() {
            std::thread::sleep(deadline.saturating_duration_since(Instant::now()));
        }
        let lateness_us = if socket.is_some() {
            Instant::now()
                .saturating_duration_since(deadline)
                .as_secs_f64()
                * 1e6
        } else {
            0.0
        };
        let mut rendered = Vec::with_capacity(runtimes.len());
        let frame_start = Instant::now();
        let feature = feature_at(index);
        for (zone, runtime) in config.zones.iter().zip(&mut runtimes) {
            let start = Instant::now();
            let entities = runtime.render(&feature.audio, if index == 0 { 0.016 } else { DT })?;
            let runtime_us = start.elapsed().as_secs_f64() * 1e6;
            let start = Instant::now();
            let payload = serde_json::to_string(&Batch {
                kind: "batch_update",
                zone: &zone.name,
                entities: &entities,
            })?;
            let serialize_us = start.elapsed().as_secs_f64() * 1e6;
            let bytes = payload.len();
            let start = Instant::now();
            if let Some(socket) = &mut socket {
                socket.send(tungstenite::Message::Text(payload.into()))?;
            }
            let send_us = if socket.is_some() {
                start.elapsed().as_secs_f64() * 1e6
            } else {
                0.0
            };
            rendered.push((entities, runtime_us, serialize_us, send_us, bytes));
        }
        let completed = Instant::now();
        let frame_work_us = completed.duration_since(frame_start).as_secs_f64() * 1e6;
        let send_offset_ms = completed.duration_since(epoch).as_secs_f64() * 1000.0;
        // Evidence assembly and later disk writes are outside frame_work_us.
        let zones = config
            .zones
            .iter()
            .zip(rendered)
            .map(
                |(zone, (entities, runtime_us, serialize_us, send_us, bytes))| ZoneRecord {
                    zone: &zone.name,
                    pattern: &zone.pattern,
                    entities,
                    runtime_us,
                    serialize_us,
                    send_us,
                    bytes,
                },
            )
            .collect();
        records.push(Record {
            frame: index,
            feature,
            zones,
            lateness_us,
            frame_work_us,
            send_offset_ms,
        });
    }
    finish_socket(&mut socket)?;
    let mut writer = BufWriter::new(File::create(output)?);
    for record in records {
        serde_json::to_writer(&mut writer, &record)?;
        writeln!(writer)?;
    }
    writer.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn zone(name: &str) -> Zone {
        Zone {
            name: name.into(),
            pattern: "spectrum".into(),
            entity_count: 64,
            seed: 1234,
        }
    }

    #[test]
    fn validates_zone_budget_names_patterns_and_counts() {
        assert!(Config { zones: vec![] }.validate().is_err());
        assert!(
            Config {
                zones: (0..5).map(|i| zone(&format!("matrix_{i}"))).collect()
            }
            .validate()
            .is_err()
        );
        assert!(
            Config {
                zones: vec![zone("same"), zone("same")]
            }
            .validate()
            .is_err()
        );
        for name in [
            "",
            "../escape",
            "not a zone",
            "é",
            "Upper",
            "1zone",
            "_zone",
        ] {
            assert!(
                Config {
                    zones: vec![zone(name)]
                }
                .validate()
                .is_err()
            );
        }
        let mut config = Config {
            zones: vec![zone("matrix_0_main_stage")],
        };
        assert!(config.validate().is_ok());
        config.zones[0].entity_count = 257;
        assert!(config.validate().is_err());
        config.zones[0].entity_count = 0;
        assert!(config.validate().is_err());
        config.zones[0].entity_count = 1;
        config.zones[0].pattern = "../../custom".into();
        assert!(config.validate().is_err());
        config.zones[0].pattern = "shockwave".into();
        assert!(config.validate().is_ok());
        for seed in [-1, 2_147_483_648] {
            config.zones[0].seed = seed;
            assert!(config.validate().is_err());
        }
        config.zones[0].seed = 2_147_483_647;
        assert!(config.validate().is_ok());
    }
}
