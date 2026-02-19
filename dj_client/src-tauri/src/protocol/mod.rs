//! WebSocket protocol implementation for VJ server communication

mod client;
mod messages;

pub use client::{DjClient, DjClientConfig};
pub use messages::*;
