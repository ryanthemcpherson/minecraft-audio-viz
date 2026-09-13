mod matrix;
mod runtime;

#[allow(dead_code)]
mod audio {
    include!(concat!(env!("OUT_DIR"), "/dsp_types.rs"));
}

use runtime::{Audio, Runtime};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    error::Error,
    fs::{self, File},
    io::{BufRead, BufReader, BufWriter, Write},
    net::{Ipv4Addr, SocketAddrV4, TcpStream},
    time::{Duration, Instant},
};

type Result<T> = std::result::Result<T, Box<dyn Error>>;
const HOP: usize = 800;
const DT: f64 = HOP as f64 / 48000.0;
const ZONE: &str = "acceptance_main_stage";

#[derive(Clone, Serialize, Deserialize)]
struct Feature {
    audio: Audio,
    instant_bass: f32,
    instant_kick: bool,
    dsp_us: f64,
}

fn load_pcm(path: &str) -> Result<Vec<f32>> {
    let bytes = fs::read(path)?;
    if bytes.len() % 4 != 0 || bytes.len() < 4096 || bytes.len() > 48_000 * 4 * 120 {
        return Err("PCM must be 1024 samples to 120 seconds of f32le mono at 48 kHz".into());
    }
    let pcm: Vec<f32> = bytes
        .chunks_exact(4)
        .map(|chunk| f32::from_le_bytes(chunk.try_into().unwrap()))
        .collect();
    if !pcm
        .iter()
        .all(|sample| sample.is_finite() && sample.abs() <= 1.0)
    {
        return Err("PCM contains nonfinite or out-of-range samples".into());
    }
    Ok(pcm)
}

fn process_window(
    window: &[f32],
    frame: usize,
    fft: &mut audio::fft::FftAnalyzer,
    bass: &mut audio::fft::BassLane,
) -> Feature {
    let start = Instant::now();
    let (instant_bass, instant_kick) = bass.process(window);
    let mut result = fft.analyze(window);
    if instant_kick && !result.is_beat {
        result.is_beat = true;
        result.beat_intensity = result.beat_intensity.max(0.5);
    }
    let dsp_us = start.elapsed().as_secs_f64() * 1e6;
    Feature {
        audio: Audio {
            bands: result.bands.map(f64::from),
            amplitude: result.peak.into(),
            is_beat: result.is_beat,
            beat_intensity: result.beat_intensity.into(),
            frame,
            bpm: result.bpm.into(),
            beat_phase: result.beat_phase.into(),
        },
        instant_bass,
        instant_kick,
        dsp_us,
    }
}

fn analyze(path: &str) -> Result<Vec<Feature>> {
    let pcm = load_pcm(path)?;
    let mut fft = audio::fft::FftAnalyzer::new(audio::AudioConfig::default());
    let mut bass = audio::fft::BassLane::new(48_000.0);
    Ok((0..=pcm.len() - 1024)
        .step_by(HOP)
        .enumerate()
        .map(|(frame, offset)| {
            process_window(&pcm[offset..offset + 1024], frame, &mut fft, &mut bass)
        })
        .collect())
}

fn read_features(path: &str) -> Result<Vec<Feature>> {
    BufReader::new(File::open(path)?)
        .lines()
        .map(|line| Ok(serde_json::from_str(&line?)?))
        .collect()
}

type Socket = tungstenite::WebSocket<TcpStream>;

fn open_socket(port: Option<u16>) -> Result<Option<Socket>> {
    if let Some(port) = port {
        let stream = TcpStream::connect_timeout(
            &SocketAddrV4::new(Ipv4Addr::LOCALHOST, port).into(),
            Duration::from_secs(5),
        )?;
        stream.set_nodelay(true)?;
        stream.set_read_timeout(Some(Duration::from_secs(5)))?;
        stream.set_write_timeout(Some(Duration::from_secs(5)))?;
        Ok(Some(
            tungstenite::client(format!("ws://127.0.0.1:{port}"), stream)?.0,
        ))
    } else {
        Ok(None)
    }
}

fn finish_socket(socket: &mut Option<Socket>) -> Result<()> {
    if let Some(socket) = socket {
        // Receipt fence only; a pong does not prove server-tick application.
        socket.send(tungstenite::Message::Ping(b"slice-fence".to_vec().into()))?;
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let remaining = deadline
                .checked_duration_since(Instant::now())
                .ok_or("Paper did not acknowledge the receipt fence within five seconds")?;
            socket.get_mut().set_read_timeout(Some(remaining))?;
            match socket.read()? {
                tungstenite::Message::Pong(data) if data.as_ref() == b"slice-fence" => break,
                tungstenite::Message::Text(data) => {
                    let message: serde_json::Value = serde_json::from_str(&data)?;
                    if message["type"] == "error" {
                        return Err(format!("Paper rejected stream: {message}").into());
                    }
                }
                tungstenite::Message::Close(_) => return Err("Paper closed before fence".into()),
                _ => {}
            }
        }
        std::thread::sleep(Duration::from_millis(150));
        socket.close(None)?;
    }
    Ok(())
}

fn render(
    frame_count: usize,
    mut feature_at: impl FnMut(usize) -> Feature,
    output: &str,
    count: usize,
    port: Option<u16>,
) -> Result<()> {
    if frame_count == 0 || frame_count > 7200 {
        return Err("feature count must be 1..7200".into());
    }
    let mut runtime = Runtime::new(count)?;
    let mut socket = open_socket(port)?;
    // Retain bounded fixture outputs; disk writes are outside measured/paced work.
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
        let feature = feature_at(index);
        let start = Instant::now();
        let entities = runtime.render(&feature.audio, if index == 0 { 0.016 } else { DT })?;
        let runtime_us = start.elapsed().as_secs_f64() * 1e6;
        let serialization_start = Instant::now();
        let payload = serde_json::to_string(
            &json!({"type": "batch_update", "zone": ZONE, "entities": entities}),
        )?;
        let serialize_us = serialization_start.elapsed().as_secs_f64() * 1e6;
        let send_start = Instant::now();
        if let Some(socket) = &mut socket {
            socket.send(tungstenite::Message::Text(payload.clone().into()))?;
        }
        let send_us = if socket.is_some() {
            send_start.elapsed().as_secs_f64() * 1e6
        } else {
            0.0
        };
        records.push(json!({"frame": index, "audio": feature.audio, "instant_bass": feature.instant_bass, "instant_kick": feature.instant_kick, "dsp_us": feature.dsp_us, "entities": entities, "runtime_us": runtime_us, "serialize_us": serialize_us,
            "send_us": send_us, "lateness_us": lateness_us, "bytes": payload.len(), "send_offset_ms": epoch.elapsed().as_secs_f64() * 1000.0}));
    }
    finish_socket(&mut socket)?;
    let mut writer = BufWriter::new(File::create(output)?);
    for record in records {
        writeln!(writer, "{}", serde_json::to_string(&record)?)?;
    }
    writer.flush()?;
    Ok(())
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("analyze") if args.len() == 4 => {
            let features = analyze(&args[2])?;
            let mut writer = BufWriter::new(File::create(&args[3])?);
            for feature in features { writeln!(writer, "{}", serde_json::to_string(&feature)?)?; }
            writer.flush()?;
        }
        Some("render") if args.len() == 5 || args.len() == 6 => {
            let features = read_features(&args[2])?;
            render(features.len(), |index| features[index].clone(), &args[3], args[4].parse()?, args.get(5).map(|port| port.parse()).transpose()?)?;
        }
        Some("stream-pcm") if args.len() == 6 => {
            let pcm = load_pcm(&args[2])?;
            let mut fft = audio::fft::FftAnalyzer::new(audio::AudioConfig::default());
            let mut bass = audio::fft::BassLane::new(48_000.0);
            render((pcm.len() - 1024) / HOP + 1,
                |index| process_window(&pcm[index * HOP..index * HOP + 1024], index, &mut fft, &mut bass),
                &args[3], args[4].parse()?, Some(args[5].parse()?))?;
        }
        Some("matrix") if args.len() == 5 || args.len() == 6 => {
            let features = read_features(&args[2])?;
            let config = matrix::Config::read(&args[3])?;
            matrix::render(features.len(), |index| features[index].clone(), &config,
                &args[4], args.get(5).map(|port| port.parse()).transpose()?)?;
        }
        Some("matrix-pcm") if args.len() == 6 => {
            let pcm = load_pcm(&args[2])?;
            let config = matrix::Config::read(&args[3])?;
            let mut fft = audio::fft::FftAnalyzer::new(audio::AudioConfig::default());
            let mut bass = audio::fft::BassLane::new(48_000.0);
            matrix::render((pcm.len() - 1024) / HOP + 1,
                |index| process_window(&pcm[index * HOP..index * HOP + 1024], index, &mut fft, &mut bass),
                &config, &args[4], Some(args[5].parse()?))?;
        }
        _ => return Err("Usage: mcav-runtime-slice analyze PCM FEATURES | render FEATURES OUTPUT COUNT [LOOPBACK_PORT] | stream-pcm PCM OUTPUT COUNT LOOPBACK_PORT | matrix FEATURES CONFIG OUTPUT [LOOPBACK_PORT] | matrix-pcm PCM CONFIG OUTPUT LOOPBACK_PORT".into()),
    }
    Ok(())
}
