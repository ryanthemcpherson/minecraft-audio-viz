//! Windows WASAPI per-application audio capture
//!
//! Uses the Windows Audio Session API (WASAPI) to enumerate audio sessions
//! and identify which applications are currently producing audio.
//! On Windows 10 Build 20348+, provides TRUE per-process audio capture
//! via the Process Loopback API (ActivateAudioInterfaceAsync).
//! Falls back to system loopback on older Windows.

use crate::audio::sources::{AudioSource, SourceType};
use crate::voice::VoiceStreamer;

use parking_lot::Mutex;
use std::sync::mpsc as std_mpsc;
use std::sync::Arc;
use std::thread;

use windows::core::{Interface, HRESULT, HSTRING, PCWSTR};
use windows::Win32::Foundation::{CloseHandle, E_FAIL, S_OK};
use windows::Win32::Media::Audio::{
    eConsole, eRender, ActivateAudioInterfaceAsync, AudioSessionStateActive,
    IAudioCaptureClient, IAudioClient, IAudioSessionControl, IAudioSessionControl2,
    IAudioSessionEnumerator, IAudioSessionManager2, IMMDeviceEnumerator, MMDeviceEnumerator,
    AUDIOCLIENT_ACTIVATION_PARAMS, AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
    AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS,
    AUDCLNT_SHAREMODE_SHARED,
    AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM, AUDCLNT_STREAMFLAGS_LOOPBACK,
    AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, IActivateAudioInterfaceAsyncOperation,
    IActivateAudioInterfaceCompletionHandler,
    PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE, WAVEFORMATEX,
};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, CLSCTX_ALL, COINIT_MULTITHREADED,
};
use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
use windows::Win32::System::ProcessStatus::GetModuleBaseNameW;
use windows::Win32::System::SystemInformation::GetVersionExW;
use windows::Win32::System::Threading::{
    CreateEventW, OpenProcess, SetEvent, WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION,
};

/// Minimum Windows build number that supports Process Loopback API
const MIN_PROCESS_LOOPBACK_BUILD: u32 = 20348;

/// REFTIMES per second for WASAPI timing
#[allow(dead_code)]
const REFTIMES_PER_SEC: i64 = 10_000_000;

/// WAVE_FORMAT_IEEE_FLOAT tag value
const WAVE_FORMAT_IEEE_FLOAT: u16 = 0x0003;

/// Check if the current Windows build supports Process Loopback capture.
///
/// Process Loopback requires Windows 10 Build 20348+ (21H2 / Windows Server 2022).
pub fn supports_process_loopback() -> bool {
    unsafe {
        let mut osvi = std::mem::zeroed::<windows::Win32::System::SystemInformation::OSVERSIONINFOW>();
        osvi.dwOSVersionInfoSize = std::mem::size_of::<windows::Win32::System::SystemInformation::OSVERSIONINFOW>() as u32;
        if GetVersionExW(&mut osvi).is_ok() {
            let build = osvi.dwBuildNumber;
            log::info!("Windows build number: {}", build);
            return build >= MIN_PROCESS_LOOPBACK_BUILD;
        }
        // Fall back to registry
        if let Some(build) = get_build_from_registry() {
            log::info!("Windows build number (registry): {}", build);
            return build >= MIN_PROCESS_LOOPBACK_BUILD;
        }
        false
    }
}

/// Read the CurrentBuildNumber from the registry as a fallback for version detection.
fn get_build_from_registry() -> Option<u32> {
    let output = std::process::Command::new("reg")
        .args([
            "query",
            r"HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion",
            "/v",
            "CurrentBuildNumber",
        ])
        .output()
        .ok()?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    for line in stdout.lines() {
        if line.contains("CurrentBuildNumber") {
            if let Some(val) = line.split_whitespace().last() {
                return val.parse().ok();
            }
        }
    }
    None
}

// --- Completion handler COM implementation (manual vtable) ---
//
// Since we can't use the #[implement] macro without an extra crate,
// we build a minimal IActivateAudioInterfaceCompletionHandler using
// raw COM vtable construction. This is the standard pattern for
// implementing COM interfaces in Rust without proc macros.

use windows::Win32::Foundation::HANDLE;

#[repr(C)]
struct CompletionHandlerVtbl {
    query_interface: unsafe extern "system" fn(
        *mut CompletionHandler,
        *const windows::core::GUID,
        *mut *mut std::ffi::c_void,
    ) -> HRESULT,
    add_ref: unsafe extern "system" fn(*mut CompletionHandler) -> u32,
    release: unsafe extern "system" fn(*mut CompletionHandler) -> u32,
    activate_completed: unsafe extern "system" fn(
        *mut CompletionHandler,
        *mut std::ffi::c_void, // IActivateAudioInterfaceAsyncOperation
    ) -> HRESULT,
}

#[repr(C)]
struct CompletionHandler {
    vtbl: *const CompletionHandlerVtbl,
    ref_count: std::sync::atomic::AtomicU32,
    event: HANDLE,
}

unsafe extern "system" fn ch_query_interface(
    this: *mut CompletionHandler,
    riid: *const windows::core::GUID,
    ppv: *mut *mut std::ffi::c_void,
) -> HRESULT {
    let iid = &*riid;
    if *iid == IActivateAudioInterfaceCompletionHandler::IID
        || *iid == windows::core::IUnknown::IID
    {
        (*this).ref_count.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        *ppv = this as *mut std::ffi::c_void;
        S_OK
    } else {
        *ppv = std::ptr::null_mut();
        windows::Win32::Foundation::E_NOINTERFACE
    }
}

unsafe extern "system" fn ch_add_ref(this: *mut CompletionHandler) -> u32 {
    (*this).ref_count.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1
}

unsafe extern "system" fn ch_release(this: *mut CompletionHandler) -> u32 {
    let prev = (*this).ref_count.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
    if prev == 1 {
        // Last reference; drop the allocation
        let _ = Box::from_raw(this);
        0
    } else {
        prev - 1
    }
}

unsafe extern "system" fn ch_activate_completed(
    this: *mut CompletionHandler,
    _operation: *mut std::ffi::c_void,
) -> HRESULT {
    let _ = SetEvent((*this).event);
    S_OK
}

static COMPLETION_HANDLER_VTBL: CompletionHandlerVtbl = CompletionHandlerVtbl {
    query_interface: ch_query_interface,
    add_ref: ch_add_ref,
    release: ch_release,
    activate_completed: ch_activate_completed,
};

/// Create a completion handler that signals the given event.
unsafe fn create_completion_handler(
    event: HANDLE,
) -> IActivateAudioInterfaceCompletionHandler {
    let handler = Box::new(CompletionHandler {
        vtbl: &COMPLETION_HANDLER_VTBL,
        ref_count: std::sync::atomic::AtomicU32::new(1),
        event,
    });
    let raw = Box::into_raw(handler);
    std::mem::transmute(raw)
}

// --- End completion handler ---

/// Handle to a process loopback capture stream running on a dedicated thread.
pub struct ProcessLoopbackHandle {
    stop_tx: std_mpsc::Sender<()>,
    thread_handle: Option<thread::JoinHandle<()>>,
}

impl ProcessLoopbackHandle {
    pub fn stop(&mut self) {
        let _ = self.stop_tx.send(());
        if let Some(handle) = self.thread_handle.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for ProcessLoopbackHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Start capturing audio from a specific process via the Process Loopback API.
///
/// Returns a `ProcessLoopbackHandle` plus the sample rate and channel count.
pub fn start_process_loopback(
    pid: u32,
    buffer: Arc<Mutex<super::super::capture::AudioBuffer>>,
    voice_streamer: Option<Arc<VoiceStreamer>>,
) -> Result<(ProcessLoopbackHandle, u32, u16), String> {
    let (audio_client, sample_rate, channels) = unsafe { activate_process_loopback(pid)? };

    let (stop_tx, stop_rx) = std_mpsc::channel();

    // Transfer IAudioClient across thread boundary using raw pointer
    let client_ptr = unsafe {
        let ptr = std::mem::transmute_copy::<IAudioClient, usize>(&audio_client);
        std::mem::forget(audio_client);
        ptr
    };

    let thread_handle = thread::Builder::new()
        .name(format!("process-loopback-{}", pid))
        .spawn(move || {
            unsafe {
                let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            }

            let audio_client: IAudioClient =
                unsafe { std::mem::transmute_copy::<usize, IAudioClient>(&client_ptr) };

            if let Err(e) = run_process_capture_loop(
                &audio_client,
                channels as usize,
                buffer,
                voice_streamer,
                stop_rx,
            ) {
                log::error!("Process loopback capture error: {}", e);
            }

            unsafe {
                let _ = audio_client.Stop();
                CoUninitialize();
            }
        })
        .map_err(|e| format!("Failed to spawn process loopback thread: {}", e))?;

    Ok((
        ProcessLoopbackHandle {
            stop_tx,
            thread_handle: Some(thread_handle),
        },
        sample_rate,
        channels,
    ))
}

/// Activate an IAudioClient for process loopback capture.
unsafe fn activate_process_loopback(pid: u32) -> Result<(IAudioClient, u32, u16), String> {
    let _ = CoInitializeEx(None, COINIT_MULTITHREADED);

    let event = CreateEventW(None, true, false, None)
        .map_err(|e| format!("CreateEvent failed: {}", e))?;

    // Build activation parameters
    let loopback_params = AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS {
        TargetProcessId: pid,
        ProcessLoopbackMode: PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
    };

    let activation_params = AUDIOCLIENT_ACTIVATION_PARAMS {
        ActivationType: AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
        Anonymous: windows::Win32::Media::Audio::AUDIOCLIENT_ACTIVATION_PARAMS_0 {
            ProcessLoopbackParams: loopback_params,
        },
    };

    // Build PROPVARIANT with VT_BLOB pointing to our activation params.
    // PROPVARIANT layout: vt(u16) + 6 reserved bytes + union data
    // VT_BLOB has cbSize(u32) + pBlobData(*mut u8) in the union
    let mut prop_bytes = vec![0u8; std::mem::size_of::<PROPVARIANT>()];
    // VT_BLOB = 0x0041 = 65
    prop_bytes[0] = 65;
    prop_bytes[1] = 0;
    // Union starts at offset 8
    let blob_size = std::mem::size_of::<AUDIOCLIENT_ACTIVATION_PARAMS>() as u32;
    prop_bytes[8..12].copy_from_slice(&blob_size.to_le_bytes());
    // Pointer at offset 16 on 64-bit (8 + 4 cbSize + 4 padding for alignment)
    let ptr_offset = if std::mem::size_of::<usize>() == 8 { 16 } else { 12 };
    let ptr_val = &activation_params as *const _ as usize;
    prop_bytes[ptr_offset..ptr_offset + std::mem::size_of::<usize>()]
        .copy_from_slice(&ptr_val.to_le_bytes());

    let prop_variant: &PROPVARIANT = &*(prop_bytes.as_ptr() as *const PROPVARIANT);

    // Create completion handler
    let handler = create_completion_handler(event);

    let device_id = HSTRING::from("VAD\\Process_Loopback");

    let operation: IActivateAudioInterfaceAsyncOperation = ActivateAudioInterfaceAsync(
        PCWSTR(device_id.as_ptr()),
        &IAudioClient::IID,
        Some(prop_variant),
        &handler,
    )
    .map_err(|e| format!("ActivateAudioInterfaceAsync failed: {}", e))?;

    // Wait for completion (5 second timeout)
    let wait_result = WaitForSingleObject(event, 5000);
    let _ = CloseHandle(event);

    if wait_result.0 != 0 {
        return Err("ActivateAudioInterfaceAsync timed out".to_string());
    }

    let mut activate_result: HRESULT = E_FAIL;
    let mut activated_interface: Option<windows::core::IUnknown> = None;
    operation
        .GetActivateResult(&mut activate_result, &mut activated_interface)
        .map_err(|e| format!("GetActivateResult failed: {}", e))?;

    if activate_result != S_OK {
        return Err(format!(
            "Audio interface activation failed: HRESULT 0x{:08X}",
            activate_result.0
        ));
    }

    let unknown = activated_interface.ok_or("No audio interface returned")?;
    let audio_client: IAudioClient = unknown
        .cast()
        .map_err(|e| format!("Failed to cast to IAudioClient: {}", e))?;

    // Get the device's mix format
    let mix_format_ptr = audio_client
        .GetMixFormat()
        .map_err(|e| format!("GetMixFormat failed: {}", e))?;

    let mix_format = &*mix_format_ptr;
    let sample_rate = mix_format.nSamplesPerSec;
    let channels = mix_format.nChannels;
    let bits_per_sample = mix_format.wBitsPerSample;

    log::info!(
        "Process loopback mix format: {}Hz, {} channels, {} bits",
        sample_rate,
        channels,
        bits_per_sample
    );

    // Create desired format: f32
    let block_align = channels * 4;
    let desired_format = WAVEFORMATEX {
        wFormatTag: WAVE_FORMAT_IEEE_FLOAT,
        nChannels: channels,
        nSamplesPerSec: sample_rate,
        nAvgBytesPerSec: sample_rate * block_align as u32,
        nBlockAlign: block_align,
        wBitsPerSample: 32,
        cbSize: 0,
    };

    // Initialize with loopback + auto-convert flags, 20ms buffer
    let buffer_duration = REFTIMES_PER_SEC / 50;
    audio_client
        .Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK
                | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM
                | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
            buffer_duration,
            0,
            &desired_format,
            None,
        )
        .map_err(|e| format!("IAudioClient::Initialize failed: {}", e))?;

    audio_client
        .Start()
        .map_err(|e| format!("IAudioClient::Start failed: {}", e))?;

    log::info!(
        "Process loopback capture started for PID {} ({}Hz, {} ch)",
        pid,
        sample_rate,
        channels
    );

    CoTaskMemFree(Some(mix_format_ptr as *const _ as *const _));

    Ok((audio_client, sample_rate, channels as u16))
}

/// Read audio packets from the process loopback IAudioClient.
fn run_process_capture_loop(
    audio_client: &IAudioClient,
    channels: usize,
    buffer: Arc<Mutex<super::super::capture::AudioBuffer>>,
    voice_streamer: Option<Arc<VoiceStreamer>>,
    stop_rx: std_mpsc::Receiver<()>,
) -> Result<(), String> {
    let capture_client: IAudioCaptureClient = unsafe {
        audio_client
            .GetService::<IAudioCaptureClient>()
            .map_err(|e| format!("GetService<IAudioCaptureClient> failed: {}", e))?
    };

    let channels = channels.max(1);

    loop {
        match stop_rx.try_recv() {
            Ok(()) | Err(std_mpsc::TryRecvError::Disconnected) => {
                log::info!("Process loopback capture stopping");
                return Ok(());
            }
            Err(std_mpsc::TryRecvError::Empty) => {}
        }

        let packet_size = unsafe { capture_client.GetNextPacketSize().unwrap_or(0) };

        if packet_size == 0 {
            std::thread::sleep(std::time::Duration::from_millis(2));
            continue;
        }

        let mut data_ptr = std::ptr::null_mut::<u8>();
        let mut num_frames: u32 = 0;
        let mut flags: u32 = 0;

        let hr = unsafe {
            capture_client.GetBuffer(
                &mut data_ptr,
                &mut num_frames,
                &mut flags,
                None,
                None,
            )
        };

        if hr.is_err() {
            std::thread::sleep(std::time::Duration::from_millis(2));
            continue;
        }

        if num_frames > 0 && !data_ptr.is_null() {
            let total_samples = num_frames as usize * channels;
            let is_silent = (flags & 0x2) != 0; // AUDCLNT_BUFFERFLAGS_SILENT

            if is_silent {
                let silence = vec![0.0f32; total_samples / channels];
                buffer.lock().push_samples(&silence);
            } else {
                let f32_slice = unsafe {
                    std::slice::from_raw_parts(data_ptr as *const f32, total_samples)
                };

                if let Some(ref streamer) = voice_streamer {
                    streamer.push_samples(f32_slice, channels);
                }

                let mono: Vec<f32> = f32_slice
                    .chunks(channels)
                    .map(|frame| {
                        let sum: f32 = frame.iter().sum();
                        sum / channels as f32
                    })
                    .collect();

                buffer.lock().push_samples(&mono);
            }
        }

        let _ = unsafe { capture_client.ReleaseBuffer(num_frames) };
    }
}

/// List audio applications currently playing audio on Windows.
pub fn list_audio_applications() -> Result<Vec<AudioSource>, String> {
    unsafe { list_audio_applications_impl() }
}

unsafe fn list_audio_applications_impl() -> Result<Vec<AudioSource>, String> {
    let com_result = CoInitializeEx(None, COINIT_MULTITHREADED);
    let com_initialized = com_result.is_ok();

    let result = enumerate_sessions();

    if com_initialized {
        CoUninitialize();
    }

    result
}

unsafe fn enumerate_sessions() -> Result<Vec<AudioSource>, String> {
    let enumerator: IMMDeviceEnumerator =
        CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)
            .map_err(|e| format!("Failed to create device enumerator: {}", e))?;

    let device = enumerator
        .GetDefaultAudioEndpoint(eRender, eConsole)
        .map_err(|e| format!("Failed to get default audio endpoint: {}", e))?;

    let session_manager: IAudioSessionManager2 = device
        .Activate::<IAudioSessionManager2>(CLSCTX_ALL, None)
        .map_err(|e| format!("Failed to activate session manager: {}", e))?;

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

        let session2: IAudioSessionControl2 = match session.cast() {
            Ok(s) => s,
            Err(_) => continue,
        };

        let state = match session.GetState() {
            Ok(s) => s,
            Err(_) => continue,
        };

        if state != AudioSessionStateActive {
            continue;
        }

        if session2.IsSystemSoundsSession().is_ok() {
            continue;
        }

        let pid = match session2.GetProcessId() {
            Ok(p) => p,
            Err(_) => continue,
        };

        if pid == 0 || seen_pids.contains(&pid) {
            continue;
        }
        seen_pids.insert(pid);

        let process_name = get_process_name(pid).unwrap_or_else(|| format!("PID {}", pid));

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
                let name = process_name.trim_end_matches(".exe");
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
