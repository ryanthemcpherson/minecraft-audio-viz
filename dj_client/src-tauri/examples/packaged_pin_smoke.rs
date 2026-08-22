use dj_client_lib::protocol::{
    AudioFrameMessage, ClientError, DjClient, DjClientConfig, connect_verified,
};
use serde::Serialize;
use std::ffi::OsString;
use std::process::ExitCode;
use std::time::Duration;
use tokio_tungstenite::tungstenite::{Message, protocol::WebSocketConfig};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum Mode {
    Match,
    Mismatch,
    Plaintext,
}

#[derive(Debug, PartialEq, Eq)]
struct Arguments {
    mode: Mode,
    host: String,
    port: u16,
    fingerprint: String,
    dj_id: String,
    dj_key: String,
}

#[derive(Serialize)]
struct SmokeResult {
    schema_version: u8,
    mode: Mode,
    status: &'static str,
    process_id: u32,
    executable: String,
    production_path: &'static str,
    connected: bool,
    authenticated: bool,
    audio_frame_queued: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error_code: Option<&'static str>,
}

fn parse_mode(value: &str) -> Result<Mode, &'static str> {
    match value {
        "match" => Ok(Mode::Match),
        "mismatch" => Ok(Mode::Mismatch),
        "plaintext" => Ok(Mode::Plaintext),
        _ => Err("--mode must be match, mismatch, or plaintext"),
    }
}

fn parse_arguments<I, S>(arguments: I) -> Result<Arguments, &'static str>
where
    I: IntoIterator<Item = S>,
    S: Into<OsString>,
{
    let mut arguments = arguments.into_iter();
    let _program = arguments.next();
    let mut mode = None;
    let mut host = None;
    let mut port = None;
    let mut fingerprint = None;
    let mut dj_id = None;
    let mut dj_key = None;

    while let Some(flag) = arguments.next() {
        let flag = flag.into().into_string().map_err(|_| "invalid argument")?;
        let value = arguments
            .next()
            .ok_or("each option requires a value")?
            .into()
            .into_string()
            .map_err(|_| "invalid argument value")?;
        match flag.as_str() {
            "--mode" if mode.is_none() => mode = Some(parse_mode(&value)?),
            "--host" if host.is_none() => host = Some(value),
            "--port" if port.is_none() => {
                port = Some(value.parse::<u16>().map_err(|_| "invalid --port")?)
            }
            "--fingerprint" if fingerprint.is_none() => fingerprint = Some(value),
            "--dj-id" if dj_id.is_none() => dj_id = Some(value),
            "--dj-key" if dj_key.is_none() => dj_key = Some(value),
            "--mode" | "--host" | "--port" | "--fingerprint" | "--dj-id" | "--dj-key" => {
                return Err("duplicate option");
            }
            _ => return Err("unknown option"),
        }
    }

    Ok(Arguments {
        mode: mode.ok_or("missing --mode")?,
        host: host.ok_or("missing --host")?,
        port: port.ok_or("missing --port")?,
        fingerprint: fingerprint.ok_or("missing --fingerprint")?,
        dj_id: dj_id.ok_or("missing --dj-id")?,
        dj_key: dj_key.ok_or("missing --dj-key")?,
    })
}

fn is_expected_error(mode: Mode, error: ClientError) -> bool {
    matches!(
        (mode, error),
        (Mode::Mismatch, ClientError::TlsFingerprintMismatch)
            | (Mode::Plaintext, ClientError::MissingPeerCertificate)
    )
}

fn executable_identity() -> String {
    std::env::current_exe()
        .map(|path| path.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "unavailable".to_string())
}

fn result(
    mode: Mode,
    status: &'static str,
    production_path: &'static str,
    connected: bool,
    authenticated: bool,
    audio_frame_queued: bool,
    error: Option<ClientError>,
) -> SmokeResult {
    SmokeResult {
        schema_version: 1,
        mode,
        status,
        process_id: std::process::id(),
        executable: executable_identity(),
        production_path,
        connected,
        authenticated,
        audio_frame_queued,
        error_code: error.map(|value| value.code().as_str()),
    }
}

async fn run_dj_client(arguments: &Arguments) -> Result<SmokeResult, SmokeResult> {
    let config = DjClientConfig {
        server_host: arguments.host.clone(),
        server_port: arguments.port,
        dj_name: "Packaged production smoke".to_string(),
        connect_code: None,
        dj_id: Some(arguments.dj_id.clone()),
        dj_key: Some(arguments.dj_key.clone()),
        dj_session_id: None,
        tls_fingerprint: Some(arguments.fingerprint.clone()),
        max_reconnect_attempts: 0,
        reconnect_delay: 0.1,
        heartbeat_interval: 60.0,
    };
    let mut client = DjClient::new(config);

    match client.connect().await {
        Ok(()) if arguments.mode == Mode::Match => {
            let state = client.get_state();
            if !state.connected || !state.authenticated {
                let _ = client.disconnect().await;
                return Err(result(
                    arguments.mode,
                    "failed",
                    "DjClient::connect",
                    state.connected,
                    state.authenticated,
                    false,
                    Some(ClientError::AuthenticationFailed),
                ));
            }

            let audio_frame = AudioFrameMessage::new(
                1,
                [0.91, 0.72, 0.53, 0.34, 0.15],
                0.91,
                true,
                0.87,
                128.0,
                0.96,
                0.25,
                0.89,
                true,
            );
            let serialized = serde_json::to_string(&audio_frame).map_err(|_| {
                result(
                    arguments.mode,
                    "failed",
                    "DjClient::connect + DjClient::try_send",
                    true,
                    true,
                    false,
                    Some(ClientError::SendError),
                )
            })?;
            let queued = client.get_tx_clone().is_some_and(|sender| {
                DjClient::try_send(&sender, Message::Text(serialized.into()))
            });
            tokio::time::sleep(Duration::from_millis(750)).await;
            let _ = client.disconnect().await;
            if !queued {
                return Err(result(
                    arguments.mode,
                    "failed",
                    "DjClient::connect + DjClient::try_send",
                    true,
                    true,
                    false,
                    Some(ClientError::SendError),
                ));
            }
            Ok(result(
                arguments.mode,
                "passed",
                "DjClient::connect + DjClient::try_send",
                true,
                true,
                true,
                None,
            ))
        }
        Ok(()) => {
            let state = client.get_state();
            let _ = client.disconnect().await;
            Err(result(
                arguments.mode,
                "failed",
                "DjClient::connect",
                state.connected,
                state.authenticated,
                false,
                None,
            ))
        }
        Err(error) if is_expected_error(arguments.mode, error) => Ok(result(
            arguments.mode,
            "passed",
            "DjClient::connect",
            false,
            false,
            false,
            Some(error),
        )),
        Err(error) => Err(result(
            arguments.mode,
            "failed",
            "DjClient::connect",
            false,
            false,
            false,
            Some(error),
        )),
    }
}

async fn run_plaintext(arguments: &Arguments) -> Result<SmokeResult, SmokeResult> {
    let url = format!("ws://{}:{}", arguments.host, arguments.port);
    match connect_verified(
        &url,
        WebSocketConfig::default(),
        Some(&arguments.fingerprint),
    )
    .await
    {
        Err(error) if is_expected_error(arguments.mode, error) => Ok(result(
            arguments.mode,
            "passed",
            "connect_verified",
            false,
            false,
            false,
            Some(error),
        )),
        Err(error) => Err(result(
            arguments.mode,
            "failed",
            "connect_verified",
            false,
            false,
            false,
            Some(error),
        )),
        Ok(_) => Err(result(
            arguments.mode,
            "failed",
            "connect_verified",
            true,
            false,
            false,
            None,
        )),
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    let arguments = match parse_arguments(std::env::args_os()) {
        Ok(arguments) => arguments,
        Err(message) => {
            eprintln!("packaged pin smoke: {message}");
            return ExitCode::from(2);
        }
    };
    let outcome = match arguments.mode {
        Mode::Match | Mode::Mismatch => run_dj_client(&arguments).await,
        Mode::Plaintext => run_plaintext(&arguments).await,
    };
    let (output, exit_code) = match outcome {
        Ok(output) => (output, ExitCode::SUCCESS),
        Err(output) => (output, ExitCode::FAILURE),
    };
    match serde_json::to_string(&output) {
        Ok(serialized) => println!("{serialized}"),
        Err(_) => return ExitCode::FAILURE,
    }
    exit_code
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_match_mode_without_normalizing_security_inputs() {
        let arguments = parse_arguments([
            "packaged-pin-smoke",
            "--mode",
            "match",
            "--host",
            "127.0.0.1",
            "--port",
            "25809",
            "--fingerprint",
            "ab:cd",
            "--dj-id",
            "smoke-dj",
            "--dj-key",
            "secret",
        ])
        .expect("valid arguments");

        assert_eq!(arguments.mode, Mode::Match);
        assert_eq!(arguments.host, "127.0.0.1");
        assert_eq!(arguments.port, 25809);
        assert_eq!(arguments.fingerprint, "ab:cd");
        assert_eq!(arguments.dj_id, "smoke-dj");
        assert_eq!(arguments.dj_key, "secret");
    }

    #[test]
    fn expected_failures_are_mode_specific_and_fail_closed() {
        assert!(is_expected_error(
            Mode::Mismatch,
            ClientError::TlsFingerprintMismatch
        ));
        assert!(is_expected_error(
            Mode::Plaintext,
            ClientError::MissingPeerCertificate
        ));
        assert!(!is_expected_error(
            Mode::Mismatch,
            ClientError::AuthenticationFailed
        ));
        assert!(!is_expected_error(
            Mode::Plaintext,
            ClientError::ConnectionFailed
        ));
    }
}
