//! libmpv client-API bridge for the GPU-next playback backend.
//!
//! Kotlin receives opaque registry tokens, never raw `mpv_handle` pointers.
//! Tokens cannot be reused after close, double-freed, or destroyed concurrently.
//! A Surface JNI global reference is retained until the matching instance has
//! fully stopped, including a failed create/initialize path.
//!
//! This module does not enable `vo=gpu-next`, does not call the render API, and
//! does not treat a loadable `libmpv` as proof that rendering works. Real JNI
//! and libmpv linkage are not exercised by the unit tests below.

use std::collections::HashMap;
#[cfg(target_os = "android")]
use std::sync::atomic::AtomicBool;
use std::sync::atomic::{AtomicU64, Ordering};
#[cfg(target_os = "android")]
use std::sync::OnceLock;
use std::sync::{Arc, Mutex};
use std::thread::ThreadId;
use std::time::Duration;
#[cfg(target_os = "android")]
use std::time::Instant;

/// Client-API format values from include/mpv/client.h at
/// mpv-player/mpv b2c255c13e8e37952dbac6c34da56a690560378b.
pub const MPV_FORMAT_INT64: i32 = 4;
pub const MPV_FORMAT_DOUBLE: i32 = 5;

pub const MPV_EVENT_NONE: i32 = 0;
pub const MPV_EVENT_SHUTDOWN: i32 = 1;
pub const MPV_EVENT_LOG_MESSAGE: i32 = 2;
pub const MPV_EVENT_GET_PROPERTY_REPLY: i32 = 3;
pub const MPV_EVENT_SET_PROPERTY_REPLY: i32 = 4;
pub const MPV_EVENT_COMMAND_REPLY: i32 = 5;
pub const MPV_EVENT_START_FILE: i32 = 6;
pub const MPV_EVENT_END_FILE: i32 = 7;
pub const MPV_EVENT_FILE_LOADED: i32 = 8;
pub const MPV_EVENT_VIDEO_RECONFIG: i32 = 17;
pub const MPV_EVENT_AUDIO_RECONFIG: i32 = 18;
pub const MPV_EVENT_SEEK: i32 = 20;
pub const MPV_EVENT_PLAYBACK_RESTART: i32 = 21;
pub const MPV_EVENT_PROPERTY_CHANGE: i32 = 22;
pub const MPV_EVENT_QUEUE_OVERFLOW: i32 = 24;

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const MPV_ERROR_SUCCESS: i32 = 0;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
const MPV_ERROR_NOMEM: i32 = -2;
const MPV_ERROR_GENERIC: i32 = -20;

/// Upper bound for one synchronous native call. libmpv documents that some
/// synchronous calls can wait without a bound; this bridge does not do that.
pub const NATIVE_CALL_BUDGET: Duration = Duration::from_secs(2);

/// Upper bound for draining events during destroy. `mpv_wait_event` itself is
/// always called with a finite timeout.
pub const DESTROY_EVENT_BUDGET: Duration = Duration::from_secs(2);
const DESTROY_EVENT_SLICE: f64 = 0.05;

const TOKEN_EPOCH: u64 = 1 << 32;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BridgeError {
    InvalidArgument(&'static str),
    NotOpen,
    Closing,
    Busy,
    MainThreadBlocked,
    DeadlineExceeded,
    Mpv(i32),
    Jni(&'static str),
    Resource(&'static str),
}

impl BridgeError {
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn code(&self) -> i32 {
        match self {
            BridgeError::InvalidArgument(_) => -1001,
            BridgeError::NotOpen => -1002,
            BridgeError::Closing => -1003,
            BridgeError::Busy => -1004,
            BridgeError::MainThreadBlocked => -1005,
            BridgeError::DeadlineExceeded => -1006,
            BridgeError::Mpv(code) => *code,
            BridgeError::Jni(_) => -1007,
            BridgeError::Resource(_) => -1008,
        }
    }
}

impl std::fmt::Display for BridgeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            BridgeError::InvalidArgument(what) => write!(f, "invalid argument: {what}"),
            BridgeError::NotOpen => write!(f, "mpv instance is not open"),
            BridgeError::Closing => write!(f, "mpv instance is closing"),
            BridgeError::Busy => write!(f, "mpv instance is busy"),
            BridgeError::MainThreadBlocked => {
                write!(f, "refusing unbounded native wait on the main thread")
            }
            BridgeError::DeadlineExceeded => write!(f, "native operation exceeded its wait budget"),
            BridgeError::Mpv(code) => write!(f, "libmpv error {code}"),
            BridgeError::Jni(what) => write!(f, "jni error: {what}"),
            BridgeError::Resource(what) => write!(f, "resource error: {what}"),
        }
    }
}

pub type BridgeResult<T> = Result<T, BridgeError>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum InstancePhase {
    /// `mpv_create` succeeded. Options may still be set. Not initialized.
    Created,
    /// `mpv_initialize` succeeded. Properties, commands, and wid are valid.
    Initialized,
    Closing,
    Closed,
}

struct SurfaceBinding<S> {
    surface: S,
}

struct MpvInstance<S> {
    phase: InstancePhase,
    surface: Option<SurfaceBinding<S>>,
    native: Box<dyn MpvClient>,
    /// Properties registered with `mpv_observe_property`, in order.
    /// Kept for tests; production never reads it back.
    #[cfg(test)]
    observed: Vec<String>,
}

struct RegistryState<S> {
    instances: HashMap<i64, MpvInstance<S>>,
}

/// Process-wide token table. Tokens are never raw pointers.
pub struct HandleRegistry<S> {
    next_token: AtomicU64,
    blocked_threads: Mutex<Vec<ThreadId>>,
    state: Mutex<RegistryState<S>>,
}

impl<S> HandleRegistry<S> {
    pub fn new() -> Self {
        Self {
            next_token: AtomicU64::new(TOKEN_EPOCH),
            blocked_threads: Mutex::new(Vec::new()),
            state: Mutex::new(RegistryState {
                instances: HashMap::new(),
            }),
        }
    }

    /// Marks the current thread as one that must not run synchronous mpv calls.
    pub fn block_current_thread(&self) {
        if let Ok(mut blocked) = self.blocked_threads.lock() {
            let id = std::thread::current().id();
            if !blocked.contains(&id) {
                blocked.push(id);
            }
        }
    }

    fn on_blocked_thread(&self) -> bool {
        self.blocked_threads
            .lock()
            .ok()
            .is_some_and(|blocked| blocked.contains(&std::thread::current().id()))
    }

    pub fn open_count(&self) -> usize {
        self.state
            .lock()
            .map(|state| {
                state
                    .instances
                    .values()
                    .filter(|instance| instance.phase != InstancePhase::Closed)
                    .count()
            })
            .unwrap_or(0)
    }

    /// Test-only view of the properties registered through
    /// [HandleRegistry::observe_property], in registration order.
    #[cfg(test)]
    pub fn observed_names(&self, token: i64) -> Vec<String> {
        self.state
            .lock()
            .ok()
            .and_then(|state| state.instances.get(&token).map(|i| i.observed.clone()))
            .unwrap_or_default()
    }

    fn lock(&self) -> BridgeResult<std::sync::MutexGuard<'_, RegistryState<S>>> {
        self.state
            .lock()
            .map_err(|_| BridgeError::Resource("registry lock poisoned"))
    }

    fn allocate(native: Box<dyn MpvClient>) -> MpvInstance<S> {
        MpvInstance {
            phase: InstancePhase::Created,
            surface: None,
            native,
            #[cfg(test)]
            observed: Vec::new(),
        }
    }

    /// Publishes a created-but-not-initialized client. Dropping it before
    /// [HandleRegistry::insert_created] must destroy the native client.
    pub(crate) fn insert_created(&self, native: Box<dyn MpvClient>) -> BridgeResult<i64> {
        let mut state = self.lock()?;
        let token = self.next_token.fetch_add(1, Ordering::Relaxed);
        if token == 0 || token == -1i64 as u64 {
            return Err(BridgeError::Resource("token space exhausted"));
        }
        state.instances.insert(token as i64, Self::allocate(native));
        Ok(token as i64)
    }

    fn with_phase<T>(
        &self,
        token: i64,
        allowed: &[InstancePhase],
        body: impl FnOnce(&mut dyn MpvClient) -> BridgeResult<T>,
    ) -> BridgeResult<T> {
        if self.on_blocked_thread() {
            return Err(BridgeError::MainThreadBlocked);
        }
        // The registry mutex is held for the whole native call. That serializes
        // close, surface replacement, and commands. It does not interrupt a
        // call that libmpv itself blocks inside; callers must use APIs whose
        // libmpv contract is non-blocking or explicitly timed.
        let mut state = self.lock()?;
        let instance = state
            .instances
            .get_mut(&token)
            .ok_or(BridgeError::NotOpen)?;
        if !allowed.contains(&instance.phase) {
            return Err(if instance.phase == InstancePhase::Created {
                BridgeError::InvalidArgument("not-initialized")
            } else {
                BridgeError::Closing
            });
        }
        body(instance.native.as_mut())
    }

    pub fn set_option(&self, token: i64, name: &str, value: &str) -> BridgeResult<()> {
        require_name(name)?;
        self.with_phase(token, &[InstancePhase::Created], |native| {
            native.set_option_string(name, value)
        })
    }

    pub fn initialize(&self, token: i64) -> BridgeResult<()> {
        self.with_phase(token, &[InstancePhase::Created], |native| {
            native.initialize(NATIVE_CALL_BUDGET)
        })?;
        let mut state = self.lock()?;
        let instance = state
            .instances
            .get_mut(&token)
            .ok_or(BridgeError::NotOpen)?;
        if instance.phase == InstancePhase::Created {
            instance.phase = InstancePhase::Initialized;
        }
        Ok(())
    }

    ///
    /// `mpv_observe_property`。属性变化才会进入事件队列，
    /// 因此这一组必须在起播前注册（bv 的 `observePlaybackProperties`，
    /// player/.../mpv/MpvMediaPlayer.kt:760-786）。
    ///
    /// 未初始化时返回 `InvalidArgument("not-initialized")`：
    /// libmpv 只在 mpv_initialize 之后才接受 observe 调用。
    pub fn observe_property(&self, token: i64, name: &str, format: i32) -> BridgeResult<()> {
        require_name(name)?;
        self.with_phase(token, &[InstancePhase::Initialized], |native| {
            native.observe_property(name, format)
        })?;
        #[cfg(test)]
        if let Ok(mut state) = self.lock() {
            if let Some(instance) = state.instances.get_mut(&token) {
                instance.observed.push(name.to_string());
            }
        }
        Ok(())
    }

    pub fn set_property(&self, token: i64, name: &str, value: &str) -> BridgeResult<()> {
        require_name(name)?;
        self.with_phase(token, &[InstancePhase::Initialized], |native| {
            native.set_property_string(name, value)
        })
    }

    pub fn string_property(&self, token: i64, name: &str) -> BridgeResult<String> {
        require_name(name)?;
        self.with_phase(token, &[InstancePhase::Initialized], |native| {
            native.get_property_string(name)
        })
    }

    pub fn double_property(&self, token: i64, name: &str) -> BridgeResult<f64> {
        require_name(name)?;
        self.with_phase(token, &[InstancePhase::Initialized], |native| {
            native.get_property_double(name)
        })
    }

    pub fn command(&self, token: i64, args: &[String]) -> BridgeResult<()> {
        if args.is_empty() || args.iter().any(|arg| arg.is_empty()) {
            return Err(BridgeError::InvalidArgument("command"));
        }
        self.with_phase(token, &[InstancePhase::Initialized], |native| {
            native.command(args)
        })
    }

    /// One `mpv_wait_event` whose timeout is finite and capped.
    /// The wait cannot be cancelled; a timed-out caller does not drop the token.
    pub fn poll_event(&self, token: i64, timeout_seconds: f64) -> BridgeResult<i32> {
        if !timeout_seconds.is_finite() || timeout_seconds < 0.0 {
            return Err(BridgeError::InvalidArgument("timeout"));
        }
        let capped = timeout_seconds.min(NATIVE_CALL_BUDGET.as_secs_f64());
        self.with_phase(token, &[InstancePhase::Initialized], |native| {
            native.wait_event(capped)
        })
    }

    /// Replaces the wid and the retained surface under one registry lock.
    /// `Ok(previous)` means `set_wid` succeeded and `previous` is no longer used.
    /// `Err((error, surface))` returns the new surface untouched; the old one
    /// stays installed.
    pub fn bind_surface(
        &self,
        token: i64,
        surface: S,
        wid: i64,
    ) -> Result<Option<S>, (BridgeError, S)> {
        if wid == 0 {
            return Err((BridgeError::InvalidArgument("surface"), surface));
        }
        if self.on_blocked_thread() {
            return Err((BridgeError::MainThreadBlocked, surface));
        }
        let mut state = match self.lock() {
            Ok(state) => state,
            Err(error) => return Err((error, surface)),
        };
        let Some(instance) = state.instances.get_mut(&token) else {
            return Err((BridgeError::NotOpen, surface));
        };
        if instance.phase != InstancePhase::Initialized {
            let error = if instance.phase == InstancePhase::Created {
                BridgeError::InvalidArgument("not-initialized")
            } else {
                BridgeError::Closing
            };
            return Err((error, surface));
        }
        if let Err(error) = instance.native.set_wid(wid) {
            return Err((error, surface));
        }
        let previous = instance.surface.replace(SurfaceBinding { surface });
        Ok(previous.map(|binding| binding.surface))
    }

    /// Clears wid first. The retained surface is removed only after that succeeds.
    pub fn clear_surface(&self, token: i64) -> BridgeResult<Option<S>> {
        if self.on_blocked_thread() {
            return Err(BridgeError::MainThreadBlocked);
        }
        let mut state = self.lock()?;
        let instance = state
            .instances
            .get_mut(&token)
            .ok_or(BridgeError::NotOpen)?;
        if instance.phase != InstancePhase::Initialized {
            return Err(if instance.phase == InstancePhase::Created {
                BridgeError::InvalidArgument("not-initialized")
            } else {
                BridgeError::Closing
            });
        }
        instance.native.set_wid(0)?;
        Ok(instance.surface.take().map(|binding| binding.surface))
    }

    /// One destroy. The surface is returned only after `stop` returns, which
    /// for libmpv means `mpv_terminate_destroy` has completed. A stop error
    /// still returns the surface only when the client reports `stopped()`.
    pub fn close(&self, token: i64) -> BridgeResult<Option<S>> {
        if self.on_blocked_thread() {
            return Err(BridgeError::MainThreadBlocked);
        }
        let mut state = self.lock()?;
        let instance = state
            .instances
            .get_mut(&token)
            .ok_or(BridgeError::NotOpen)?;
        match instance.phase {
            InstancePhase::Created | InstancePhase::Initialized => {}
            InstancePhase::Closing | InstancePhase::Closed => return Err(BridgeError::Closing),
        }
        instance.phase = InstancePhase::Closing;
        let stop_result = instance
            .native
            .stop(DESTROY_EVENT_BUDGET, DESTROY_EVENT_SLICE);
        let fully_stopped = instance.native.stopped();
        if !fully_stopped {
            // Leave the instance and its surface registered. Another release
            // can retry. Do not drop the GlobalRef while mpv may still read wid.
            instance.phase = InstancePhase::Initialized;
            return stop_result.map(|_| None);
        }
        instance.phase = InstancePhase::Closed;
        let removed = state.instances.remove(&token);
        drop(state);
        let surface = removed.and_then(|closed| closed.surface.map(|binding| binding.surface));
        stop_result?;
        Ok(surface)
    }
}

impl<S> Default for HandleRegistry<S> {
    fn default() -> Self {
        Self::new()
    }
}

fn require_name(name: &str) -> BridgeResult<()> {
    if name.is_empty() || name.as_bytes().contains(&0) {
        Err(BridgeError::InvalidArgument("name"))
    } else {
        Ok(())
    }
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn mpv_result(code: i32) -> BridgeResult<()> {
    if code >= MPV_ERROR_SUCCESS {
        Ok(())
    } else {
        Err(BridgeError::Mpv(code))
    }
}

/// Operations the bridge needs from libmpv. Tests substitute a fake client.
#[cfg_attr(not(test), allow(dead_code))]
trait MpvClient: Send {
    fn set_option_string(&mut self, name: &str, value: &str) -> BridgeResult<()>;
    fn initialize(&mut self, budget: Duration) -> BridgeResult<()>;
    fn set_property_string(&mut self, name: &str, value: &str) -> BridgeResult<()>;
    fn get_property_string(&mut self, name: &str) -> BridgeResult<String>;
    fn get_property_double(&mut self, name: &str) -> BridgeResult<f64>;
    fn command(&mut self, args: &[String]) -> BridgeResult<()>;
    fn set_wid(&mut self, wid: i64) -> BridgeResult<()>;
    /// `mpv_observe_property`：属性变化才会进事件队列。
    /// 没有它，`mpv_wait_event` 只会偶发地返回文件事件，
    /// 调用方拿不到 time-pos/pause/缓冲状态的连续更新。
    fn observe_property(&mut self, name: &str, format: i32) -> BridgeResult<()>;
    fn wait_event(&mut self, timeout_seconds: f64) -> BridgeResult<i32>;
    /// Must request shutdown, wait with `slice_seconds`, then destroy.
    /// [MpvClient::stopped] is true only after terminate/destroy has returned.
    fn stop(&mut self, budget: Duration, slice_seconds: f64) -> BridgeResult<()>;
    fn stopped(&self) -> bool;
}

#[derive(Default)]
#[cfg(any(test, not(target_os = "android")))]
struct FakeMpv {
    options: Vec<(String, String)>,
    properties: HashMap<String, String>,
    doubles: HashMap<String, f64>,
    commands: Vec<Vec<String>>,
    wid: i64,
    fail_initialize: bool,
    fail_stop: bool,
    fail_wid: bool,
    initialized: bool,
    fully_stopped: bool,
    quit_requested: bool,
    events_before_shutdown: u32,
}

#[cfg(any(test, not(target_os = "android")))]
impl FakeMpv {
    fn new() -> Self {
        let mut doubles = HashMap::new();
        doubles.insert("time-pos".to_string(), 1.25);
        doubles.insert("duration".to_string(), 12.5);
        let mut properties = HashMap::new();
        properties.insert("pause".to_string(), "yes".to_string());
        let mut client = Self::default();
        client.doubles = doubles;
        client.properties = properties;
        client
    }
}

#[cfg(any(test, not(target_os = "android")))]
impl Drop for FakeMpv {
    fn drop(&mut self) {
        self.wid = 0;
    }
}

#[cfg(any(test, not(target_os = "android")))]
impl MpvClient for FakeMpv {
    fn set_option_string(&mut self, name: &str, value: &str) -> BridgeResult<()> {
        if self.initialized {
            return Err(BridgeError::InvalidArgument("option-after-initialize"));
        }
        self.options.push((name.to_string(), value.to_string()));
        Ok(())
    }

    fn initialize(&mut self, _budget: Duration) -> BridgeResult<()> {
        if self.fail_initialize {
            return Err(BridgeError::Mpv(MPV_ERROR_GENERIC));
        }
        self.initialized = true;
        Ok(())
    }

    fn observe_property(&mut self, name: &str, _format: i32) -> BridgeResult<()> {
        self.observed.push(name.to_string());
        Ok(())
    }

    fn set_property_string(&mut self, name: &str, value: &str) -> BridgeResult<()> {
        self.properties.insert(name.to_string(), value.to_string());
        Ok(())
    }

    fn get_property_string(&mut self, name: &str) -> BridgeResult<String> {
        self.properties
            .get(name)
            .cloned()
            .ok_or(BridgeError::Mpv(-8))
    }

    fn get_property_double(&mut self, name: &str) -> BridgeResult<f64> {
        self.doubles.get(name).copied().ok_or(BridgeError::Mpv(-8))
    }

    fn command(&mut self, args: &[String]) -> BridgeResult<()> {
        self.commands.push(args.to_vec());
        Ok(())
    }

    fn set_wid(&mut self, wid: i64) -> BridgeResult<()> {
        if self.fail_wid && wid != 0 {
            return Err(BridgeError::Mpv(MPV_ERROR_GENERIC));
        }
        self.wid = wid;
        Ok(())
    }

    fn wait_event(&mut self, timeout_seconds: f64) -> BridgeResult<i32> {
        if !timeout_seconds.is_finite() || timeout_seconds < 0.0 {
            return Err(BridgeError::InvalidArgument("timeout"));
        }
        if timeout_seconds > NATIVE_CALL_BUDGET.as_secs_f64() {
            return Err(BridgeError::DeadlineExceeded);
        }
        if self.events_before_shutdown == 0 {
            Ok(MPV_EVENT_SHUTDOWN)
        } else {
            self.events_before_shutdown -= 1;
            Ok(MPV_EVENT_NONE)
        }
    }

    fn stop(&mut self, budget: Duration, slice_seconds: f64) -> BridgeResult<()> {
        if !slice_seconds.is_finite()
            || slice_seconds <= 0.0
            || slice_seconds > budget.as_secs_f64()
        {
            return Err(BridgeError::InvalidArgument("timeout"));
        }
        self.quit_requested = true;
        if self.fail_stop {
            self.fully_stopped = false;
            return Err(BridgeError::Mpv(MPV_ERROR_GENERIC));
        }
        self.fully_stopped = true;
        self.wid = 0;
        Ok(())
    }

    fn stopped(&self) -> bool {
        self.fully_stopped
    }
}

#[cfg(any(test, not(target_os = "android")))]
fn open_fake(registry: &HandleRegistry<i64>, fail_initialize: bool) -> BridgeResult<i64> {
    let mut fake = FakeMpv::new();
    fake.fail_initialize = fail_initialize;
    let token = registry.insert_created(Box::new(fake))?;
    if let Err(error) = registry.initialize(token) {
        let _ = registry.close(token);
        return Err(error);
    }
    Ok(token)
}

#[cfg(any(test, not(target_os = "android")))]
fn open_created(registry: &HandleRegistry<i64>) -> BridgeResult<i64> {
    registry.insert_created(Box::new(FakeMpv::new()))
}

#[cfg(any(test, not(target_os = "android")))]
fn open_fake_with(
    registry: &HandleRegistry<i64>,
    configure: impl FnOnce(&mut FakeMpv),
) -> BridgeResult<i64> {
    let mut fake = FakeMpv::new();
    configure(&mut fake);
    let fail_initialize = fake.fail_initialize;
    let token = registry.insert_created(Box::new(fake))?;
    if !fail_initialize {
        registry.initialize(token)?;
    }
    Ok(token)
}

#[cfg(target_os = "android")]
mod ffi {
    use super::*;
    use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
    use jni::sys::{jint, jlong};
    use jni::JNIEnv;
    use std::ffi::{CStr, CString};
    use std::os::raw::{c_char, c_int, c_void};

    type MpvHandle = c_void;

    // include/mpv/client.h @ b2c255c13e8e37952dbac6c34da56a690560378b
    extern "C" {
        fn mpv_create() -> *mut MpvHandle;
        fn mpv_initialize(ctx: *mut MpvHandle) -> c_int;
        fn mpv_set_option_string(
            ctx: *mut MpvHandle,
            name: *const c_char,
            data: *const c_char,
        ) -> c_int;
        fn mpv_set_property_string(
            ctx: *mut MpvHandle,
            name: *const c_char,
            data: *const c_char,
        ) -> c_int;
        fn mpv_get_property_string(ctx: *mut MpvHandle, name: *const c_char) -> *mut c_char;
        fn mpv_get_property(
            ctx: *mut MpvHandle,
            name: *const c_char,
            format: c_int,
            data: *mut c_void,
        ) -> c_int;
        fn mpv_command(ctx: *mut MpvHandle, args: *const *const c_char) -> c_int;
        fn mpv_wait_event(ctx: *mut MpvHandle, timeout: f64) -> *mut MpvEvent;
        fn mpv_wakeup(ctx: *mut MpvHandle);
        fn mpv_terminate_destroy(ctx: *mut MpvHandle);
        fn mpv_free(data: *mut c_void);
        fn mpv_error_string(error: c_int) -> *const c_char;
        fn mpv_observe_property(
            ctx: *mut MpvHandle,
            reply_userdata: u64,
            name: *const c_char,
            format: c_int,
        ) -> c_int;
    }

    // FFmpeg libavcodec/jni.h. Android builds register the JVM this way.
    extern "C" {
        fn av_jni_set_java_vm(vm: *mut c_void, log_ctx: *mut c_void) -> c_int;
    }

    #[repr(C)]
    struct MpvEvent {
        event_id: c_int,
        _error: c_int,
        _reply_userdata: u64,
        _data: *mut c_void,
    }

    struct LinkedMpv {
        handle: *mut MpvHandle,
        destroyed: bool,
    }

    unsafe impl Send for LinkedMpv {}

    impl LinkedMpv {
        fn create() -> BridgeResult<Self> {
            let handle = unsafe { mpv_create() };
            if handle.is_null() {
                return Err(BridgeError::Mpv(MPV_ERROR_NOMEM));
            }
            Ok(Self {
                handle,
                destroyed: false,
            })
        }
    }

    impl Drop for LinkedMpv {
        fn drop(&mut self) {
            if !self.destroyed && !self.handle.is_null() {
                unsafe { mpv_terminate_destroy(self.handle) };
                self.handle = std::ptr::null_mut();
                self.destroyed = true;
            }
        }
    }

    impl MpvClient for LinkedMpv {
        fn set_option_string(&mut self, name: &str, value: &str) -> BridgeResult<()> {
            let name = c_string(name)?;
            let value = c_string(value)?;
            mpv_result(unsafe { mpv_set_option_string(self.handle, name.as_ptr(), value.as_ptr()) })
        }

        fn initialize(&mut self, _budget: Duration) -> BridgeResult<()> {
            mpv_result(unsafe { mpv_initialize(self.handle) })
        }

        fn observe_property(&mut self, name: &str, format: i32) -> BridgeResult<()> {
            let name = c_string(name)?;
            mpv_result(unsafe { mpv_observe_property(self.handle, 0, name.as_ptr(), format) })
        }

        fn set_property_string(&mut self, name: &str, value: &str) -> BridgeResult<()> {
            let name = c_string(name)?;
            let value = c_string(value)?;
            mpv_result(unsafe {
                mpv_set_property_string(self.handle, name.as_ptr(), value.as_ptr())
            })
        }

        fn get_property_string(&mut self, name: &str) -> BridgeResult<String> {
            let name = c_string(name)?;
            let raw = unsafe { mpv_get_property_string(self.handle, name.as_ptr()) };
            if raw.is_null() {
                return Err(BridgeError::Mpv(MPV_ERROR_GENERIC));
            }
            let owned = unsafe { CStr::from_ptr(raw) }
                .to_string_lossy()
                .into_owned();
            unsafe { mpv_free(raw.cast()) };
            Ok(owned)
        }

        fn get_property_double(&mut self, name: &str) -> BridgeResult<f64> {
            let name = c_string(name)?;
            let mut value = 0f64;
            mpv_result(unsafe {
                mpv_get_property(
                    self.handle,
                    name.as_ptr(),
                    MPV_FORMAT_DOUBLE,
                    (&mut value as *mut f64).cast(),
                )
            })?;
            Ok(value)
        }

        fn command(&mut self, args: &[String]) -> BridgeResult<()> {
            let mut owned = Vec::with_capacity(args.len());
            for arg in args {
                owned.push(c_string(arg)?);
            }
            let mut ptrs: Vec<*const c_char> = owned.iter().map(|arg| arg.as_ptr()).collect();
            ptrs.push(std::ptr::null());
            mpv_result(unsafe { mpv_command(self.handle, ptrs.as_ptr()) })
        }

        fn set_wid(&mut self, wid: i64) -> BridgeResult<()> {
            let name = c_string("wid")?;
            let mut value = wid;
            mpv_result(unsafe { mpv_set_option_string_wid(self.handle, name.as_ptr(), &mut value) })
        }

        fn wait_event(&mut self, timeout_seconds: f64) -> BridgeResult<i32> {
            if self.handle.is_null() {
                return Err(BridgeError::NotOpen);
            }
            if !timeout_seconds.is_finite()
                || timeout_seconds < 0.0
                || timeout_seconds > NATIVE_CALL_BUDGET.as_secs_f64()
            {
                return Err(BridgeError::InvalidArgument("timeout"));
            }
            let event = unsafe { mpv_wait_event(self.handle, timeout_seconds) };
            if event.is_null() {
                return Err(BridgeError::Mpv(MPV_ERROR_GENERIC));
            }
            Ok(unsafe { (*event).event_id })
        }

        fn stop(&mut self, budget: Duration, slice_seconds: f64) -> BridgeResult<()> {
            if self.destroyed || self.handle.is_null() {
                self.destroyed = true;
                return Ok(());
            }
            if !slice_seconds.is_finite() || slice_seconds <= 0.0 {
                return Err(BridgeError::InvalidArgument("timeout"));
            }
            // Request core shutdown before waiting. wakeup alone does not
            // produce MPV_EVENT_SHUTDOWN.
            self.command(&["quit".to_string()])?;
            unsafe { mpv_wakeup(self.handle) };
            let started = Instant::now();
            loop {
                let remaining = budget.saturating_sub(started.elapsed());
                if remaining.is_zero() {
                    return Err(BridgeError::DeadlineExceeded);
                }
                let slice = slice_seconds.min(remaining.as_secs_f64());
                if slice <= 0.0 {
                    return Err(BridgeError::DeadlineExceeded);
                }
                let event = unsafe { mpv_wait_event(self.handle, slice) };
                if event.is_null() {
                    return Err(BridgeError::Mpv(MPV_ERROR_GENERIC));
                }
                let event_id = unsafe { (*event).event_id };
                if event_id == MPV_EVENT_SHUTDOWN {
                    break;
                }
            }
            unsafe { mpv_terminate_destroy(self.handle) };
            self.handle = std::ptr::null_mut();
            self.destroyed = true;
            Ok(())
        }

        fn stopped(&self) -> bool {
            self.destroyed
        }
    }

    unsafe fn mpv_set_option_string_wid(
        ctx: *mut MpvHandle,
        name: *const c_char,
        wid: *mut i64,
    ) -> c_int {
        // mpv_set_option(ctx, name, MPV_FORMAT_INT64, wid) from the same header.
        // Declared locally so this crate does not invent a string wid path.
        extern "C" {
            fn mpv_set_option(
                ctx: *mut MpvHandle,
                name: *const c_char,
                format: c_int,
                data: *mut c_void,
            ) -> c_int;
        }
        mpv_set_option(ctx, name, MPV_FORMAT_INT64, wid.cast())
    }

    fn c_string(value: &str) -> BridgeResult<CString> {
        CString::new(value).map_err(|_| BridgeError::InvalidArgument("string"))
    }

    fn static_error(error: c_int) -> String {
        let raw = unsafe { mpv_error_string(error) };
        if raw.is_null() {
            format!("libmpv error {error}")
        } else {
            unsafe { CStr::from_ptr(raw) }
                .to_string_lossy()
                .into_owned()
        }
    }

    struct AndroidState {
        registry: HandleRegistry<GlobalRef>,
        jvm_registered: AtomicBool,
    }

    fn android_state() -> &'static AndroidState {
        static STATE: OnceLock<AndroidState> = OnceLock::new();
        STATE.get_or_init(|| AndroidState {
            registry: HandleRegistry::new(),
            jvm_registered: AtomicBool::new(false),
        })
    }

    fn register_jvm(env: &JNIEnv<'_>) -> BridgeResult<()> {
        let state = android_state();
        if state.jvm_registered.load(Ordering::Acquire) {
            return Ok(());
        }
        let vm = env
            .get_java_vm()
            .map_err(|_| BridgeError::Jni("GetJavaVM"))?;
        let code =
            unsafe { av_jni_set_java_vm(vm.get_java_vm_pointer().cast(), std::ptr::null_mut()) };
        if code < 0 {
            return Err(BridgeError::Jni("av_jni_set_java_vm"));
        }
        state.jvm_registered.store(true, Ordering::Release);
        Ok(())
    }

    fn throw(env: &mut JNIEnv<'_>, error: BridgeError) {
        let message = error.to_string();
        let _ = env.exception_clear();
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }

    fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> BridgeResult<String> {
        env.get_string(value)
            .map(|text| text.into())
            .map_err(|_| BridgeError::Jni("GetStringUTFChars"))
    }

    fn require_surface(env: &mut JNIEnv<'_>, surface: &JObject<'_>) -> BridgeResult<()> {
        if surface.is_null() {
            return Err(BridgeError::InvalidArgument("surface"));
        }
        let class = env
            .find_class("android/view/Surface")
            .map_err(|_| BridgeError::Jni("FindClass(Surface)"))?;
        if !env
            .is_instance_of(surface, &class)
            .map_err(|_| BridgeError::Jni("IsInstanceOf(Surface)"))?
        {
            return Err(BridgeError::InvalidArgument("surface"));
        }
        Ok(())
    }

    fn create_instance(env: &mut JNIEnv<'_>) -> BridgeResult<i64> {
        register_jvm(env)?;
        let created = LinkedMpv::create()?;
        match android_state().registry.insert_created(Box::new(created)) {
            Ok(token) => Ok(token),
            Err(error) => {
                // insert_created failed before publication; LinkedMpv::drop
                // still calls mpv_terminate_destroy.
                Err(error)
            }
        }
    }

    fn release_binding(surface: GlobalRef) {
        // jni 0.21.1 GlobalRef has no as_raw(). The jobject lives behind as_obj().
        // Drop deletes the global ref via DeleteGlobalRef. Callers may invoke
        // this only after mpv_terminate_destroy or a failed wid install that
        // never published the ref.
        let raw = surface.as_obj().as_raw();
        if raw.is_null() {
            return;
        }
        drop(surface);
    }

    fn reject_main_thread(env: &mut JNIEnv<'_>) -> BridgeResult<()> {
        let looper_class = env
            .find_class("android/os/Looper")
            .map_err(|_| BridgeError::Jni("FindClass(Looper)"))?;
        let mine = env
            .call_static_method(&looper_class, "myLooper", "()Landroid/os/Looper;", &[])
            .map_err(|_| BridgeError::Jni("Looper.myLooper"))?
            .l()
            .map_err(|_| BridgeError::Jni("Looper.myLooper"))?;
        let main = env
            .call_static_method(&looper_class, "getMainLooper", "()Landroid/os/Looper;", &[])
            .map_err(|_| BridgeError::Jni("Looper.getMainLooper"))?
            .l()
            .map_err(|_| BridgeError::Jni("Looper.getMainLooper"))?;
        if env
            .is_same_object(&mine, &main)
            .map_err(|_| BridgeError::Jni("IsSameObject"))?
        {
            return Err(BridgeError::MainThreadBlocked);
        }
        Ok(())
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeCreate<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jlong {
        match reject_main_thread(&mut env).and_then(|_| create_instance(&mut env)) {
            Ok(token) => token,
            Err(error) => {
                throw(&mut env, error);
                0
            }
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeSetOption<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        name: JString<'local>,
        value: JString<'local>,
    ) {
        let result = (|| {
            reject_main_thread(&mut env)?;
            let name = read_string(&mut env, &name)?;
            let value = read_string(&mut env, &value)?;
            android_state().registry.set_option(token, &name, &value)
        })();
        if let Err(error) = result {
            throw(&mut env, error);
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeSetProperty<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        name: JString<'local>,
        value: JString<'local>,
    ) {
        let result = (|| {
            reject_main_thread(&mut env)?;
            let name = read_string(&mut env, &name)?;
            let value = read_string(&mut env, &value)?;
            android_state().registry.set_property(token, &name, &value)
        })();
        if let Err(error) = result {
            throw(&mut env, error);
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeStringProperty<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        name: JString<'local>,
    ) -> JString<'local> {
        let result = (|| {
            reject_main_thread(&mut env)?;
            let name = read_string(&mut env, &name)?;
            android_state().registry.string_property(token, &name)
        })();
        match result {
            Ok(value) => match env.new_string(value) {
                Ok(text) => text.into(),
                Err(_) => {
                    throw(&mut env, BridgeError::Jni("NewStringUTF"));
                    JObject::null().into()
                }
            },
            Err(error) => {
                throw(&mut env, error);
                JObject::null().into()
            }
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeDoubleProperty<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        name: JString<'local>,
    ) -> f64 {
        let result = (|| {
            reject_main_thread(&mut env)?;
            let name = read_string(&mut env, &name)?;
            android_state().registry.double_property(token, &name)
        })();
        match result {
            Ok(value) => value,
            Err(error) => {
                throw(&mut env, error);
                f64::NAN
            }
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeCommand<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        args: jni::objects::JObjectArray<'local>,
    ) {
        let result = (|| {
            reject_main_thread(&mut env)?;
            let len = env
                .get_array_length(&args)
                .map_err(|_| BridgeError::Jni("GetArrayLength"))?;
            if len < 0 {
                return Err(BridgeError::InvalidArgument("command"));
            }
            let mut owned = Vec::with_capacity(len as usize);
            for index in 0..len {
                let element = env
                    .get_object_array_element(&args, index)
                    .map_err(|_| BridgeError::Jni("GetObjectArrayElement"))?;
                let text = JString::from(element);
                owned.push(read_string(&mut env, &text)?);
            }
            android_state().registry.command(token, &owned)
        })();
        if let Err(error) = result {
            throw(&mut env, error);
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeAttachSurface<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        surface: JObject<'local>,
    ) {
        let result = (|| {
            reject_main_thread(&mut env)?;
            require_surface(&mut env, &surface)?;
            let global = env
                .new_global_ref(&surface)
                .map_err(|_| BridgeError::Jni("NewGlobalRef"))?;
            let wid = global.as_obj().as_raw() as usize as i64;
            if wid == 0 {
                drop(global);
                return Err(BridgeError::InvalidArgument("surface"));
            }
            match android_state().registry.bind_surface(token, global, wid) {
                Ok(previous) => {
                    if let Some(old) = previous {
                        release_binding(old);
                    }
                    Ok(())
                }
                Err((error, rejected)) => {
                    release_binding(rejected);
                    Err(error)
                }
            }
        })();
        if let Err(error) = result {
            throw(&mut env, error);
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeDetachSurface<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
    ) {
        if let Err(error) = reject_main_thread(&mut env) {
            throw(&mut env, error);
            return;
        }
        match android_state().registry.clear_surface(token) {
            Ok(previous) => {
                if let Some(surface) = previous {
                    release_binding(surface);
                }
            }
            Err(error) => throw(&mut env, error),
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeRelease<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
    ) {
        if let Err(error) = reject_main_thread(&mut env) {
            throw(&mut env, error);
            return;
        }
        match android_state().registry.close(token) {
            // close returns the surface only after terminate_destroy.
            Ok(previous) => {
                if let Some(surface) = previous {
                    release_binding(surface);
                }
            }
            Err(error) => throw(&mut env, error),
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativePollEvent<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        timeout_ms: jint,
    ) -> jint {
        let timeout = (timeout_ms.max(0) as f64 / 1000.0).min(NATIVE_CALL_BUDGET.as_secs_f64());
        if let Err(error) = reject_main_thread(&mut env) {
            throw(&mut env, error);
            return -1;
        }
        match android_state().registry.poll_event(token, timeout) {
            Ok(event_id) => event_id,
            Err(error) => {
                throw(&mut env, error);
                -1
            }
        }
    }

    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeInitialize<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
    ) {
        let result = (|| {
            reject_main_thread(&mut env)?;
            android_state().registry.initialize(token)
        })();
        if let Err(error) = result {
            throw(&mut env, error);
        }
    }

    ///
    /// `mpv_observe_property`（bv observePlaybackProperties 对应入口）。
    /// 必须在 [.._nativeInitialize] 之后调用；format 传 MPV_FORMAT_* 常量。
    #[no_mangle]
    pub extern "C" fn Java_com_example_piliai_playback_backend_RustGpuNextBridge_nativeObserveProperty<
        'local,
    >(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        token: jlong,
        name: JString<'local>,
        format: jint,
    ) {
        let result = (|| {
            reject_main_thread(&mut env)?;
            let name = read_string(&mut env, &name)?;
            android_state()
                .registry
                .observe_property(token, &name, format)
        })();
        if let Err(error) = result {
            throw(&mut env, error);
        }
    }
}

#[cfg(any(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use std::sync::Barrier;
    use std::thread;

    #[test]
    fn open_set_and_read_properties_without_libmpv() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_created(&registry).expect("create");
        registry
            .set_option(token, "hwdec", "auto-safe")
            .expect("option");
        registry.initialize(token).expect("initialize");
        registry
            .set_property(token, "pause", "no")
            .expect("property");
        assert_eq!(registry.string_property(token, "pause").unwrap(), "no");
        assert_eq!(registry.double_property(token, "time-pos").unwrap(), 1.25);
        registry
            .command(
                token,
                &[
                    "seek".to_string(),
                    "1.5".to_string(),
                    "absolute".to_string(),
                ],
            )
            .expect("command");
        assert_eq!(registry.open_count(), 1);
    }

    #[test]
    fn initialize_failure_does_not_publish_a_token() {
        let registry = HandleRegistry::<i64>::new();
        let error = open_fake(&registry, true).expect_err("must fail");
        assert_eq!(error, BridgeError::Mpv(MPV_ERROR_GENERIC));
        assert_eq!(registry.open_count(), 0);
        assert!(matches!(
            registry.string_property(1, "pause"),
            Err(BridgeError::NotOpen)
        ));
    }

    #[test]
    fn closed_token_cannot_be_reused_or_double_freed() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        let surface = registry.close(token).expect("close");
        assert!(surface.is_none());
        assert_eq!(registry.open_count(), 0);
        assert_eq!(registry.close(token).unwrap_err(), BridgeError::NotOpen);
        assert_eq!(
            registry.set_property(token, "pause", "yes").unwrap_err(),
            BridgeError::NotOpen
        );
    }

    #[test]
    fn surface_binding_survives_until_stop() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        assert_eq!(registry.bind_surface(token, 42, 42).expect("bind"), None);
        let released = registry.close(token).expect("close");
        assert_eq!(released, Some(42));
        assert_eq!(
            registry.clear_surface(token).unwrap_err(),
            BridgeError::NotOpen
        );
    }

    #[test]
    fn failed_wid_keeps_the_previous_surface_binding() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake_with(&registry, |fake| fake.fail_wid = true).unwrap();
        let (error, returned) = registry.bind_surface(token, 22, 22).unwrap_err();
        assert_eq!(error, BridgeError::Mpv(MPV_ERROR_GENERIC));
        assert_eq!(returned, 22);
        assert_eq!(registry.clear_surface(token).unwrap(), None);
        assert_eq!(registry.open_count(), 1);
    }

    #[test]
    fn bounded_event_wait_rejects_unbounded_timeout() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        assert_eq!(
            registry.poll_event(token, -1.0).unwrap_err(),
            BridgeError::InvalidArgument("timeout")
        );
        assert_eq!(registry.poll_event(token, 0.0).unwrap(), MPV_EVENT_SHUTDOWN);
        assert_eq!(
            registry
                .poll_event(token, NATIVE_CALL_BUDGET.as_secs_f64() + 5.0)
                .unwrap(),
            MPV_EVENT_SHUTDOWN
        );
        assert!(registry.poll_event(token, f64::INFINITY).is_err());
    }

    #[test]
    fn stop_uses_a_finite_event_slice() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        registry.close(token).unwrap();
        assert_eq!(registry.open_count(), 0);
    }

    #[test]
    fn options_precede_initialize_and_fail_after_it() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_created(&registry).unwrap();
        registry.set_option(token, "hwdec", "auto-safe").unwrap();
        assert_eq!(
            registry.set_property(token, "pause", "no").unwrap_err(),
            BridgeError::InvalidArgument("not-initialized")
        );
        registry.initialize(token).unwrap();
        assert!(registry.set_option(token, "vo", "gpu-next").is_err());
        registry.set_property(token, "pause", "no").unwrap();
    }

    #[test]
    fn observe_property_requires_initialization() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_created(&registry).unwrap();
        assert_eq!(
            registry
                .observe_property(token, "time-pos", MPV_FORMAT_DOUBLE)
                .unwrap_err(),
            BridgeError::InvalidArgument("not-initialized")
        );
        registry.initialize(token).unwrap();
        registry
            .observe_property(token, "time-pos", MPV_FORMAT_DOUBLE)
            .unwrap();
        registry
            .observe_property(token, "pause", MPV_FORMAT_INT64)
            .unwrap();
        assert_eq!(registry.observed_names(token), vec!["time-pos", "pause"]);
    }

    #[test]
    fn failed_stop_keeps_surface_until_destroy_completes() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake_with(&registry, |fake| fake.fail_stop = true).unwrap();
        assert_eq!(registry.bind_surface(token, 9, 9).unwrap(), None);
        let error = registry.close(token).unwrap_err();
        assert_eq!(error, BridgeError::Mpv(MPV_ERROR_GENERIC));
        assert_eq!(registry.open_count(), 1);
        assert_eq!(registry.clear_surface(token).unwrap(), Some(9));
    }

    #[test]
    fn detach_returns_surface_only_after_wid_clear() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        assert_eq!(registry.bind_surface(token, 7, 99).unwrap(), None);
        assert_eq!(registry.clear_surface(token).unwrap(), Some(7));
        assert_eq!(registry.clear_surface(token).unwrap(), None);
    }

    #[test]
    fn rejects_empty_names_commands_and_zero_wid() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        assert!(registry.set_option(token, "", "x").is_err());
        assert!(registry.command(token, &[]).is_err());
        assert!(registry.command(token, &[String::new()]).is_err());
        let (error, returned) = registry.bind_surface(token, 1, 0).unwrap_err();
        assert_eq!(error, BridgeError::InvalidArgument("surface"));
        assert_eq!(returned, 1);
    }

    #[test]
    fn concurrent_close_allows_only_one_destroy() {
        let registry = Arc::new(HandleRegistry::<i64>::new());
        let token = open_fake(&registry, false).unwrap();
        let barrier = Arc::new(Barrier::new(2));
        let mut joins = Vec::new();
        for _ in 0..2 {
            let registry = Arc::clone(&registry);
            let barrier = Arc::clone(&barrier);
            joins.push(thread::spawn(move || {
                barrier.wait();
                registry.close(token)
            }));
        }
        let results: Vec<_> = joins.into_iter().map(|join| join.join().unwrap()).collect();
        let ok = results.iter().filter(|result| result.is_ok()).count();
        let rejected = results
            .iter()
            .filter(|result| {
                matches!(
                    result,
                    Err(BridgeError::NotOpen) | Err(BridgeError::Closing)
                )
            })
            .count();
        assert_eq!(ok, 1, "{results:?}");
        assert_eq!(rejected, 1, "{results:?}");
        assert_eq!(registry.open_count(), 0);
    }

    #[test]
    fn blocked_thread_rejects_synchronous_native_call() {
        let registry = HandleRegistry::<i64>::new();
        let token = open_fake(&registry, false).unwrap();
        registry.block_current_thread();
        assert_eq!(
            registry.set_property(token, "pause", "no").unwrap_err(),
            BridgeError::MainThreadBlocked
        );
    }

    #[test]
    fn format_constants_match_client_header() {
        assert_eq!(MPV_FORMAT_INT64, 4);
        assert_eq!(MPV_FORMAT_DOUBLE, 5);
        assert_eq!(MPV_EVENT_SHUTDOWN, 1);
    }

    #[test]
    fn operation_failure_code_is_explicit() {
        let error = BridgeError::Mpv(-15);
        assert_eq!(error.code(), -15);
        assert!(!error.to_string().is_empty());
    }
}
