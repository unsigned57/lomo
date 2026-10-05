//! Attachment decode, protocol preparation and the reader image state machine.
//!
//! Terminal capability is a query/response fact, never an environment guess:
//! `StdioProber` runs `ratatui_image`'s picker probe, the verdict lands in
//! `model.graphics`, and only a `Ready` picker can mint image requests.
//! `ratatui-image` owns payload encoding, placement metadata and the
//! transmit-once lifecycle — this module only owns request identity,
//! cancellation and the resident-bytes budget.
use image::{ImageReader, imageops::FilterType};
use lomo_core::RelativeWorkspacePath;
use ratatui::layout::Rect;
use ratatui_image::{
    Resize, ResizeEncodeRender,
    picker::{Picker, ProtocolType},
    protocol::StatefulProtocol,
};
use std::{
    hash::{Hash, Hasher},
    io::Cursor,
    ops::Deref,
    sync::{Arc, Mutex, MutexGuard, PoisonError},
};

use crate::{
    effects::Effect,
    error::TuiError,
    media::{MediaKind, media_kind_for_path},
    model::{AppModel, BodyState, MemoVersion, PendingKind, View},
    ops::TuiRuntime,
};

/// The terminal graphics verdict one probe produced.
///
/// `Ready` carries a [`Picker`] — including the `Halfblocks` answer a terminal
/// gives when it reports no image protocol, which still renders pixel-true
/// pictures without emitting a single proprietary escape. `Unsupported` is
/// only a *probe failure*: no escapes are ever emitted, placeholders render.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum GraphicsVerdict {
    /// The capability query is still in flight — placeholders render meanwhile.
    Probing,
    /// The terminal answered; the picker carries the real protocol choice and
    /// the queried cell metrics.
    Ready(SharedPicker),
    /// The query failed (no TTY answer, no font metrics) — text placeholders
    /// only; nothing is ever written to the image layer.
    Unsupported { diagnostic: String },
}

impl GraphicsVerdict {
    /// The probed picker when the terminal answered.
    #[must_use]
    pub const fn picker(&self) -> Option<&SharedPicker> {
        match self {
            Self::Ready(picker) => Some(picker),
            Self::Probing | Self::Unsupported { .. } => None,
        }
    }

    /// Whether this is the loop's own deadline answer rather than a
    /// probe-*reported* verdict — the `PROBE_BUDGET` watchdog emits exactly
    /// this diagnostic (host.rs) when the probe outlives its budget.
    ///
    /// The distinction matters at the message seam: a probe-reported
    /// verdict is the terminal's answer and terminal once landed, but once
    /// the loop itself declared the probe dead the gate already lifted for
    /// text-only operation — a wedged probe's late report must not reopen
    /// it (11-T-01).
    #[must_use]
    pub fn is_probe_expired(&self) -> bool {
        matches!(
            self,
            Self::Unsupported { diagnostic } if diagnostic == PROBE_EXPIRED_DIAGNOSTIC
        )
    }

    /// Whether this is an outbox holder's dying declaration — the stand-in
    /// `Outbox::drop` synthesizes while a thread unwinds a panic
    /// (executor.rs), shared as [`PROBE_DIED_DIAGNOSTIC`].
    ///
    /// It is NOT terminal and NOT evidence the probe died: every outbox
    /// holder — lane tails, the watcher, the detached player monitor —
    /// fabricates the same declaration on unwind. It may lift a still-
    /// `Probing` gate fail-closed, but only the probe's real answer
    /// supersedes it, and it must never overwrite a landed one (13-T-01).
    #[must_use]
    pub fn is_dying_declaration(&self) -> bool {
        matches!(
            self,
            Self::Unsupported { diagnostic } if diagnostic == PROBE_DIED_DIAGNOSTIC
        )
    }

    /// Whether this verdict is the probe's real answer — `Ready`, or an
    /// `Unsupported` whose diagnostic the loop itself could not have
    /// synthesized (`Probing` is the in-flight marker; the deadline answer
    /// and the dying declaration are the two loop-side literals).
    ///
    /// The probe reports exactly once (host.rs), so a real answer is
    /// terminal evidence: every `GraphicsDetected` that still arrives after
    /// it is fabricated noise and must degrade, never overwrite (13-T-01).
    #[must_use]
    pub fn is_probe_answer(&self) -> bool {
        !matches!(self, Self::Probing) && !self.is_probe_expired() && !self.is_dying_declaration()
    }
}

/// The diagnostic the event loop synthesizes when it answers a wedged
/// graphics probe itself.
///
/// The verdict's identity, shared by the watchdog (host.rs) that emits it
/// and `GraphicsVerdict::is_probe_expired` that recognizes it, so neither
/// side can drift apart.
pub const PROBE_EXPIRED_DIAGNOSTIC: &str = "graphics probe did not answer in time";

/// The diagnostic `Outbox::drop` synthesizes when a thread unwinds a panic
/// while holding an outbox (executor.rs) — a stand-in for a verdict that
/// may never arrive.
///
/// Shared by emitter and recognizer the same way [`PROBE_EXPIRED_DIAGNOSTIC`]
/// is, and deliberately a different literal: the declaration is fabricated
/// by ANY outbox holder's death, not only the probe's, so it is provisional —
/// the surviving probe's real answer still supersedes it — while the loop's
/// own deadline answer is absorbing. Conflating the two literals would let
/// a fabricated death notice seal the gate forever, or a wedged probe's
/// late report reopen a terminal verdict (13-T-01).
pub const PROBE_DIED_DIAGNOSTIC: &str = "graphics probe died before reporting a verdict";

/// A probed picker shared between the model and in-flight image requests.
///
/// Request identity compares the *terminal configuration* the picker encodes —
/// protocol choice and cell metrics — because a reprobe to the same answer
/// produces equivalent image work, not new work.
#[derive(Clone)]
pub struct SharedPicker(Arc<Picker>);

impl SharedPicker {
    /// Share a probed picker between the model and in-flight requests.
    #[must_use]
    pub fn new(picker: Picker) -> Self {
        Self(Arc::new(picker))
    }
}

impl Deref for SharedPicker {
    type Target = Picker;
    fn deref(&self) -> &Picker {
        &self.0
    }
}

impl std::fmt::Debug for SharedPicker {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SharedPicker")
            .field("protocol", &self.0.protocol_type())
            .field("font_size", &self.0.font_size())
            .finish()
    }
}

impl PartialEq for SharedPicker {
    fn eq(&self, other: &Self) -> bool {
        self.0.protocol_type() == other.0.protocol_type()
            && self.0.font_size() == other.0.font_size()
    }
}
impl Eq for SharedPicker {}

/// One terminal graphics probe. `probe` performs the real query/response —
/// injected so tests deliver a verdict without touching stdio.
pub trait TerminalProber: Send + Sync {
    fn probe(&self) -> GraphicsVerdict;
}

/// Production probe over real stdio query/response.
///
/// [`Picker::from_query_stdio`] writes the capability query (kitty, sixel,
/// cell-size, iTerm2, DSR) and parses the replies with an internal timeout;
/// multiplexers are detected inside the picker. Environment variables are only
/// hints the terminal's real answer can override.
#[derive(Clone, Copy, Debug, Default)]
pub struct StdioProber;

impl TerminalProber for StdioProber {
    fn probe(&self) -> GraphicsVerdict {
        // A non-terminal stdin can never answer the query — and would leave
        // the crate's leaked reader thread spinning on EOF — so the probe
        // refuses before touching stdio (D-03).
        use std::io::IsTerminal;
        if !std::io::stdin().is_terminal() || !std::io::stdout().is_terminal() {
            return GraphicsVerdict::Unsupported {
                diagnostic: "stdio is not a terminal".to_owned(),
            };
        }
        match Picker::from_query_stdio() {
            Ok(picker) => GraphicsVerdict::Ready(SharedPicker::new(picker)),
            Err(error) => GraphicsVerdict::Unsupported {
                diagnostic: error.to_string(),
            },
        }
    }
}

/// The full identity of one reader image decode.
///
/// Owner version, target geometry and the probed terminal together are the
/// request's identity — the reply is correlated by the pending `req`, never by
/// a shared epoch.
#[derive(Clone, Debug)]
pub struct ImageRequest {
    pub version: MemoVersion,
    pub path: RelativeWorkspacePath,
    /// Bounding box in terminal cells — the worker fits the raster to it.
    pub columns: u16,
    pub rows: u16,
    pub picker: SharedPicker,
}

impl PartialEq for ImageRequest {
    fn eq(&self, other: &Self) -> bool {
        self.version == other.version
            && self.path == other.path
            && self.columns == other.columns
            && self.rows == other.rows
            && self.picker == other.picker
    }
}
impl Eq for ImageRequest {}

/// One image prepared for the probed protocol.
///
/// `protocol` is the `ratatui-image` stateful payload: it owns the image
/// source, the encoded bytes, the last-encoded area and (for Kitty) the
/// transmit-once flag. Encoding runs inside [`prepare_image`] on a worker;
/// the UI thread's `StatefulImage` render only emits the prepared cells, so
/// a placement change redraws without re-encoding or retransmitting (D-07).
/// `bytes` accounts the resident raster the ready-budget enforces.
pub struct TerminalImage {
    /// The stateful protocol behind a mutex: `Arc<TerminalImage>` lives in
    /// model state while the draw path needs `&mut` for `StatefulImage`.
    protocol: Mutex<StatefulProtocol>,
    /// The cell rect the image occupies inside its request box — resolved at
    /// prepare time so the reader can reserve rows before the first render.
    area: Rect,
    /// Resident raster bytes held by the protocol's image source.
    bytes: usize,
    /// Content identity of the fitted raster — used only for message
    /// equality in tests, never a render decision.
    fingerprint: u64,
}

impl TerminalImage {
    /// Exclusive access to the protocol state for in-frame `StatefulImage`
    /// rendering. A poisoned mutex still yields the state — a panic mid-draw
    /// must not wedge the reader permanently.
    pub fn lock_protocol(&self) -> MutexGuard<'_, StatefulProtocol> {
        self.protocol.lock().unwrap_or_else(PoisonError::into_inner)
    }
    /// Resident raster bytes used for the aggregate ready budget.
    #[must_use]
    pub const fn bytes(&self) -> usize {
        self.bytes
    }
    /// The fitted placement in cells the protocol prepared for.
    #[must_use]
    pub const fn area(&self) -> Rect {
        self.area
    }
}

impl PartialEq for TerminalImage {
    fn eq(&self, other: &Self) -> bool {
        self.fingerprint == other.fingerprint
            && self.area == other.area
            && self.bytes == other.bytes
    }
}
impl Eq for TerminalImage {}

impl std::fmt::Debug for TerminalImage {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TerminalImage")
            .field("area", &self.area)
            .field("bytes", &self.bytes)
            .field("fingerprint", &self.fingerprint)
            // The mutex-guarded protocol state is opaque to Debug.
            .finish_non_exhaustive()
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ImageState {
    Pending,
    /// A decode is in flight under this request identity — if the request is
    /// reconciled out or leaves the live registry, the pending entry is
    /// cancelled so the lane drops the job before it executes (I3).
    Loading(crate::model::Req),
    Ready(Arc<TerminalImage>),
    Failed(String),
    /// Budget-evicted: bytes left memory and the image stays out of the load
    /// queue until the request identity changes — eviction must never requeue
    /// work it just admitted, or a full cache would decode forever.
    Evicted,
}

impl ImageState {
    /// Whether this image still needs a decode issued: `Pending` always, and
    /// `Loading` when its request left the live registry — the reply either
    /// landed or the request was cancelled; neither outcome is signaled
    /// in-place (same contract as `BodyState::needs_load`).
    #[must_use]
    pub fn needs_load(&self, pending: &crate::model::Pending) -> bool {
        match self {
            Self::Pending => true,
            Self::Loading(req) => !pending.contains(*req),
            Self::Ready(_) | Self::Failed(_) | Self::Evicted => false,
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReaderImage {
    pub request: ImageRequest,
    pub state: ImageState,
}

/// Aggregate byte ceiling for resident ready images.
///
/// Ready images are evicted oldest-first when the working set reaches the
/// ceiling; a single image larger than the ceiling is a visible failure,
/// never silently kept.
pub const READY_IMAGE_BUDGET_BYTES: usize = 64 * 1024 * 1024;

/// Cell ceiling of a `Halfblocks` encode: ratatui-image 8.x sizes its cell
/// buffer with a `u16` `width * height` product and truncates the same index
/// to `u16` in `render` — a fitted box above `u16::MAX` cells panics inside
/// the dependency where no `Result` exists (F-IMG-5). The bound is a property
/// of that protocol; pixel-stream protocols (Kitty, Sixel, iTerm2) encode the
/// raster itself and pass through unchanged.
const MAX_HALFBLOCKS_CELLS: u64 = 65_535;

/// The fitted cell rect clamped into the encoder's representable range —
/// scaled down proportionally so `width * height ≤ u16::MAX`, never zero on
/// either axis. Scaling preserves the fitted aspect (each halfblock cell is
/// `1×2` source pixels), so the clamped encode is the same picture bounded to
/// the largest area the protocol can address.
fn bounded_encode_area(protocol: ProtocolType, area: Rect) -> Rect {
    if protocol != ProtocolType::Halfblocks {
        return area;
    }
    let cells = u64::from(area.width) * u64::from(area.height);
    if cells <= MAX_HALFBLOCKS_CELLS {
        return area;
    }
    // `floor(side · sqrt(MAX / cells))` computed as the exact integer
    // `isqrt(side² · MAX / cells)` — floor rounding only ever shrinks.
    let scaled = |side: u16| -> u16 {
        let scaled = (u64::from(side) * u64::from(side) * MAX_HALFBLOCKS_CELLS / cells).isqrt();
        u16::try_from(scaled).map_or(1, |value| value.max(1))
    };
    let mut width = scaled(area.width);
    let mut height = scaled(area.height);
    // Verify the stated bound on the pair, not just each side — two floors
    // can pair to one cell over the ceiling on an adversarial aspect.
    while u64::from(width) * u64::from(height) > MAX_HALFBLOCKS_CELLS {
        if width >= height {
            width = width.saturating_sub(1);
        } else {
            height = height.saturating_sub(1);
        }
    }
    Rect {
        width,
        height,
        ..area
    }
}

/// # Errors
/// Capability-bound reads, decoder limits and invalid image formats remain visible per attachment.
/// `token` is the pending registry's cancellation flag — a decode whose request
/// was revoked stops before the expensive protocol pass (I3).
pub fn load_image(
    runtime: &TuiRuntime,
    request: &ImageRequest,
    token: &crate::model::CancelToken,
) -> Result<TerminalImage, TuiError> {
    let snapshot = runtime
        .session
        .projected_memo(request.version.id.as_str())?
        .ok_or_else(|| TuiError::config("image owner disappeared"))?;
    if snapshot.summary.content_revision != request.version.revision
        || snapshot.summary.file_fingerprint != request.version.fingerprint
    {
        return Err(TuiError::config("image owner changed while loading"));
    }
    let bytes = lomo_application::rebuild::read_workspace_file(
        &runtime.session_config,
        &runtime.executor,
        &request.path,
    )?;
    prepare_image(
        &bytes,
        request.columns,
        request.rows,
        &request.picker,
        token,
    )
}

/// Decodes `bytes` and prepares the probed protocol payload, fitted to the
/// request's cell box in real pixel metrics.
///
/// # Errors
/// Zero-sized targets, corrupt images and decoder limits stay visible.
/// `token` is checked between decode and encode so a revoked request stops
/// mid-work (I3).
pub fn prepare_image(
    bytes: &[u8],
    columns: u16,
    rows: u16,
    picker: &Picker,
    token: &crate::model::CancelToken,
) -> Result<TerminalImage, TuiError> {
    if columns == 0 || rows == 0 {
        return Err(TuiError::config(
            "image needs a visible graphics-capable region",
        ));
    }
    let mut reader = ImageReader::new(Cursor::new(bytes)).with_guessed_format()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(16_384);
    limits.max_image_height = Some(16_384);
    limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let decoded = reader
        .decode()
        .map_err(|error| TuiError::io(format!("image decode: {error}")))?;
    if token.is_cancelled() {
        return Err(TuiError::config("image decode cancelled"));
    }
    // Bound the resident raster to the display box in queried pixel metrics —
    // the protocol's image source keeps this fitted raster, so the ready
    // budget measures exactly what stays alive.
    let (font_w, font_h) = picker.font_size();
    let fitted = decoded.resize(
        u32::from(columns).saturating_mul(u32::from(font_w)),
        u32::from(rows).saturating_mul(u32::from(font_h)),
        FilterType::Triangle,
    );
    let resident = fitted.as_bytes().len();
    let fingerprint = {
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        fitted.as_bytes().hash(&mut hasher);
        hasher.finish()
    };
    let mut protocol = picker.new_resize_protocol(fitted);
    // The cell rect the fitted image occupies inside the request box —
    // clamped into the encoder's representable range so a render box wider
    // than the protocol's `u16` cell arithmetic produces a bounded image
    // instead of a panic inside the dependency (F-IMG-5).
    let area = bounded_encode_area(
        picker.protocol_type(),
        protocol.size_for(Resize::Fit(None), Rect::new(0, 0, columns, rows)),
    );
    // Encode on this worker: `resize_encode` produces the protocol payload
    // (sixel string, kitty transmit, iTerm2 blob, halfblock cells) here so
    // the first `StatefulImage` render only writes prepared cells — the draw
    // path never performs the O(pixels) encode (B-06).
    protocol.resize_encode(&Resize::Fit(None), area);
    if let Some(Err(error)) = protocol.last_encoding_result() {
        return Err(TuiError::io(format!("image encode: {error}")));
    }
    Ok(TerminalImage {
        protocol: Mutex::new(protocol),
        area,
        bytes: resident,
        fingerprint,
    })
}

/// Revoke the pending intent behind every in-flight decode — the registry
/// trips each token so a queued `LoadImage` never executes for an image that
/// left the request set (I3).
fn retire_images(model: &mut AppModel) {
    for image in &model.images {
        if let ImageState::Loading(req) = image.state {
            drop(model.pending.cancel(req));
        }
    }
    model.images.clear();
}

#[must_use]
pub fn hydrate_images(model: &mut AppModel) -> Option<Effect> {
    let layout = crate::ui::layout_for(model);
    let dimensions = (
        layout.content.width.saturating_sub(2),
        layout.content.height.saturating_sub(4) / 2,
    );
    let View::Reader { memo, .. } = &model.view else {
        retire_images(model);
        return None;
    };
    // No image work before the probe answers or after it found nothing —
    // the placeholder text is the complete presentation for both states.
    let Some(picker) = model.graphics.picker().cloned() else {
        if !matches!(model.graphics, GraphicsVerdict::Probing) {
            retire_images(model);
        }
        return None;
    };
    if dimensions.0 == 0 || dimensions.1 == 0 || !matches!(memo.body, BodyState::Ready(_)) {
        return None;
    }
    let requests: Vec<_> = memo
        .attachments
        .iter()
        .filter(|path| media_kind_for_path(path.as_str()) == MediaKind::Image)
        .map(|path| ImageRequest {
            version: memo.version(),
            path: path.clone(),
            columns: dimensions.0,
            rows: dimensions.1,
            picker: picker.clone(),
        })
        .collect();
    if !model
        .images
        .iter()
        .map(|image| &image.request)
        .eq(requests.iter())
    {
        // Reconcile per request: an identical request keeps its state (a Ready
        // image survives a no-op resize), while a changed request — new
        // geometry, protocol or owner — re-enters the queue as Pending. A
        // dropped request that was mid-decode has its pending intent revoked
        // so the lane drops the stale job before it executes.
        let mut retained = std::mem::take(&mut model.images);
        model.images = requests
            .into_iter()
            .map(|request| {
                let state = retained
                    .iter()
                    .position(|image| image.request == request)
                    .map_or(ImageState::Pending, |index| retained.remove(index).state);
                ReaderImage { request, state }
            })
            .collect();
        for image in retained {
            if let ImageState::Loading(req) = image.state {
                drop(model.pending.cancel(req));
            }
        }
    }
    let request = model
        .images
        .iter()
        .find(|image| image.state.needs_load(&model.pending))?
        .request
        .clone();
    let req = model.request(PendingKind::Image);
    if let Some(image) = model
        .images
        .iter_mut()
        .find(|image| image.request == request)
    {
        image.state = ImageState::Loading(req);
    }
    Some(Effect::LoadImage { req, request })
}

/// Land an image reply on the request that still names it.
///
/// The reply is claimed upstream (`pending` already verified this request is
/// live); landing resolves the image whose request identity still matches —
/// a request reconciled out of the set cannot be rewritten by a stale decode.
pub fn apply_image(
    model: &mut AppModel,
    request: &ImageRequest,
    result: Result<Arc<TerminalImage>, String>,
) {
    if let Some(image) = model
        .images
        .iter_mut()
        .find(|image| &image.request == request)
    {
        image.state = match result {
            Ok(image) if image.bytes() > READY_IMAGE_BUDGET_BYTES => ImageState::Failed(format!(
                "image payload exceeds the {READY_IMAGE_BUDGET_BYTES} byte budget"
            )),
            Ok(image) => ImageState::Ready(image),
            Err(error) => ImageState::Failed(error),
        };
    }
    enforce_ready_budget(model, request);
}

/// Evicts the oldest resident images until the aggregate ready raster fits
/// the budget. The image that just arrived is never evicted by its own
/// admission. Eviction removes the request from the load queue: a demoted
/// image stays `Evicted` while its request identity is unchanged, so a cache
/// that cannot hold every image settles instead of decoding in a loop.
fn enforce_ready_budget(model: &mut AppModel, admitted: &ImageRequest) {
    loop {
        let total: usize = model
            .images
            .iter()
            .map(|image| match &image.state {
                ImageState::Ready(image) => image.bytes(),
                ImageState::Pending
                | ImageState::Loading(_)
                | ImageState::Failed(_)
                | ImageState::Evicted => 0,
            })
            .sum();
        if total < READY_IMAGE_BUDGET_BYTES {
            return;
        }
        let Some(oldest) = model.images.iter_mut().find(|image| {
            image.request != *admitted && matches!(image.state, ImageState::Ready(_))
        }) else {
            return;
        };
        oldest.state = ImageState::Evicted;
    }
}
