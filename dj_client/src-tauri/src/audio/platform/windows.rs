//! Windows WASAPI per-application audio capture
//!
//! Uses the Windows Audio Session API (WASAPI) to enumerate audio sessions
//! and identify which applications are currently producing audio.
//! System audio loopback capture is handled by cpal; this module provides
//! application enumeration so users can see which apps are playing audio.

use crate::audio::sources::{AudioSource, SourceType};

use windows::core::Interface;
use windows::Win32::Foundation::CloseHandle;
use windows::Win32::Media::Audio::{
    eConsole, eRender, AudioSessionStateActive, IAudioSessionControl, IAudioSessionControl2,
    IAudioSessionEnumerator, IAudioSessionManager2, IMMDeviceEnumerator, MMDeviceEnumerator,
};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_ALL, COINIT_MULTITHREADED,
};
use windows::Win32::System::ProcessStatus::GetModuleBaseNameW;
use windows::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};

/// List audio applications currently playing audio on Windows.
///
/// Enumerates WASAPI audio sessions on the default render endpoint,
/// returns a list of applications that have active audio sessions.
/// The actual capture still uses cpal loopback (system audio);
/// this function is for UI display purposes so users can see what's playing.
pub fn list_audio_applications() -> Result<Vec<AudioSource>, String> {
    unsafe { list_audio_applications_impl() }
}

unsafe fn list_audio_applications_impl() -> Result<Vec<AudioSource>, String> {
    // Initialize COM (MULTITHREADED for background thread compatibility)
    let com_result = CoInitializeEx(None, COINIT_MULTITHREADED);
    let com_initialized = com_result.is_ok();
    // S_FALSE means already initialized - that's fine

    let result = enumerate_sessions();

    if com_initialized {
        CoUninitialize();
    }

    result
}

unsafe fn enumerate_sessions() -> Result<Vec<AudioSource>, String> {
    // Step 1: Create device enumerator
    let enumerator: IMMDeviceEnumerator =
        CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)
            .map_err(|e| format!("Failed to create device enumerator: {}", e))?;

    // Step 2: Get default render (output) endpoint
    let device = enumerator
        .GetDefaultAudioEndpoint(eRender, eConsole)
        .map_err(|e| format!("Failed to get default audio endpoint: {}", e))?;

    // Step 3: Activate the audio session manager via IMMDevice::Activate
    let session_manager: IAudioSessionManager2 = device
        .Activate::<IAudioSessionManager2>(CLSCTX_ALL, None)
        .map_err(|e| format!("Failed to activate session manager: {}", e))?;

    // Step 4: Get session enumerator
    let session_enum: IAudioSessionEnumerator = session_manager
        .GetSessionEnumerator()
        .map_err(|e| format!("Failed to get session enumerator: {}", e))?;

    let count = session_enum
        .GetCount()
        .map_err(|e| format!("Failed to get session count: {}", e))?;

    let mut sources = Vec::new();
    let mut seen_pids = std::collections::HashSet::new();

    for i in 0..count {
        let session: IAudioSessionControl = match session_enum.GetSession(i) {
            Ok(s) => s,
            Err(_) => continue,
        };

        // Get the IAudioSessionControl2 interface for process info
        let session2: IAudioSessionControl2 = match session.cast() {
            Ok(s) => s,
            Err(_) => continue,
        };

        // Check if session is active (currently producing audio)
        let state = match session.GetState() {
            Ok(s) => s,
            Err(_) => continue,
        };

        if state != AudioSessionStateActive {
            continue;
        }

        // Skip system sounds session
        if session2.IsSystemSoundsSession().is_ok() {
            continue;
        }

        // Get the process ID for this session
        let pid = match session2.GetProcessId() {
            Ok(p) => p,
            Err(_) => continue,
        };

        // Skip PID 0 (system) and duplicates
        if pid == 0 || seen_pids.contains(&pid) {
            continue;
        }
        seen_pids.insert(pid);

        // Get process name from PID
        let process_name = get_process_name(pid).unwrap_or_else(|| format!("PID {}", pid));

        // Try to get display name from the session first
        // GetDisplayName returns a PWSTR; convert to String safely
        let display_name = session
            .GetDisplayName()
            .ok()
            .and_then(|pwstr| {
                let name_str = pwstr.to_string().ok()?;
                if name_str.is_empty() || name_str.starts_with('@') {
                    None
                } else {
                    Some(name_str)
                }
            })
            .unwrap_or_else(|| {
                // Clean up process name for display
                let name = process_name.trim_end_matches(".exe");
                // Capitalize first letter
                let mut chars = name.chars();
                match chars.next() {
                    None => name.to_string(),
                    Some(c) => {
                        let upper: String = c.to_uppercase().collect();
                        format!("{}{}", upper, chars.as_str())
                    }
                }
            });

        sources.push(AudioSource {
            id: format!("app:{}:{}", pid, process_name),
            name: display_name,
            source_type: SourceType::Application,
        });
    }

    // Sort by display name for consistent UI
    sources.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));

    Ok(sources)
}

/// Get the process name (executable name) from a process ID
unsafe fn get_process_name(pid: u32) -> Option<String> {
    let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;

    let mut name_buf = [0u16; 260]; // MAX_PATH
    let len = GetModuleBaseNameW(handle, None, &mut name_buf);

    let _ = CloseHandle(handle);

    if len == 0 {
        return None;
    }

    Some(String::from_utf16_lossy(&name_buf[..len as usize]))
}
