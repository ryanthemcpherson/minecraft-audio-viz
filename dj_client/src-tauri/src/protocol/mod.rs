//! WebSocket protocol implementation for VJ server communication

mod client;
mod messages;
mod tls;

pub use client::{ClientError, ConnectionState, DjClient, DjClientConfig};
pub use messages::*;
pub use tls::{connect_verified, normalize_sha256_fingerprint};
