//! Attachment IO, bounded image decoding and terminal protocol encoding run in the worker.
use base64::{Engine, engine::general_purpose::STANDARD};
use image::{ImageFormat, ImageReader, imageops::FilterType};
use lomo_core::RelativeWorkspacePath;
use sha2::{Digest, Sha256};
use std::{io::Cursor, sync::Arc};

use crate::{
    effects::Effect,
    error::TuiError,
    media::{GraphicsProtocol, MediaKind, media_kind_for_path},
    model::{AppModel, BodyState, MemoVersion, View},
    ops::TuiRuntime,
};

/// A positive pixel sampling grid. Sixel requires a terminal-reported grid;
/// cell-addressed protocols can enforce their bounds with nominal sampling.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct CellSize {
    width: u16,
    height: u16,
}

impl CellSize {
    #[must_use]
    pub const fn reported(width: u16, height: u16, columns: u16, rows: u16) -> Option<Self> {
        let (Some(width), Some(height)) = (width.checked_div(columns), height.checked_div(rows))
        else {
            return None;
        };
        if width == 0 || height == 0 {
            None
        } else {
            Some(Self { width, height })
        }
    }
}

#[must_use]
pub fn image_cells(protocol: GraphicsProtocol, reported: Option<CellSize>) -> Option<CellSize> {
    match protocol {
        GraphicsProtocol::None => None,
        GraphicsProtocol::Sixel => reported,
        GraphicsProtocol::Kitty | GraphicsProtocol::ITerm2 => Some(reported.unwrap_or(CellSize {
            width: 8,
            height: 16,
        })),
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ImageRequest {
    pub epoch: u64,
    pub version: MemoVersion,
    pub path: RelativeWorkspacePath,
    pub columns: u16,
    pub rows: u16,
    pub protocol: GraphicsProtocol,
    pub cells: CellSize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TerminalImage {
    pub id: u32,
    pub columns: u16,
    pub rows: u16,
    pub protocol: GraphicsProtocol,
    pub payload: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ImageState {
    Pending,
    Loading,
    Ready(Arc<TerminalImage>),
    Failed(String),
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ReaderImage {
    pub request: ImageRequest,
    pub state: ImageState,
}

/// # Errors
/// Capability-bound reads, decoder limits and invalid image formats remain visible per attachment.
pub fn load_image(runtime: &TuiRuntime, request: &ImageRequest) -> Result<TerminalImage, TuiError> {
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
        request.protocol,
        request.cells,
    )
}

/// Raster bounds use the validated sampling grid for the current terminal.
/// # Errors
/// Unsupported graphics, zero cells, corrupt images or allocation limits.
pub fn prepare_image(
    bytes: &[u8],
    columns: u16,
    rows: u16,
    protocol: GraphicsProtocol,
    cells: CellSize,
) -> Result<TerminalImage, TuiError> {
    if columns == 0 || rows == 0 || protocol == GraphicsProtocol::None {
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
    let fitted = decoded.resize(
        u32::from(columns) * u32::from(cells.width),
        u32::from(rows) * u32::from(cells.height),
        FilterType::Triangle,
    );
    let columns = u16::try_from(fitted.width().div_ceil(u32::from(cells.width)))
        .map_err(|error| TuiError::io(error.to_string()))?;
    let rows = u16::try_from(fitted.height().div_ceil(u32::from(cells.height)))
        .map_err(|error| TuiError::io(error.to_string()))?;
    let mut png = Cursor::new(Vec::new());
    fitted
        .write_to(&mut png, ImageFormat::Png)
        .map_err(|error| TuiError::io(error.to_string()))?;
    let png = png.into_inner();
    let id = Sha256::digest(&png)
        .iter()
        .take(4)
        .fold(0_u32, |value, byte| (value << 8) | u32::from(*byte))
        .max(1);
    let payload = match protocol {
        GraphicsProtocol::Kitty => kitty_payload(&png, id, columns, rows),
        GraphicsProtocol::ITerm2 => format!("\x1b]1337;File=inline=1;width={columns};height={rows};preserveAspectRatio=1;doNotMoveCursor=1:{}\x07", STANDARD.encode(&png)).into_bytes(),
        GraphicsProtocol::Sixel => crate::sixel::encode(&fitted.to_rgb8())?,
        GraphicsProtocol::None => return Err(TuiError::config("terminal has no image protocol")),
    };
    Ok(TerminalImage {
        id,
        columns,
        rows,
        protocol,
        payload,
    })
}

fn kitty_payload(png: &[u8], id: u32, columns: u16, rows: u16) -> Vec<u8> {
    let encoded = STANDARD.encode(png);
    let chunks = encoded.as_bytes().chunks(4096);
    let count = chunks.len();
    let mut out = Vec::new();
    for (index, chunk) in chunks.enumerate() {
        let more = u8::from(index + 1 < count);
        let header = if index == 0 {
            format!("\x1b_Ga=T,f=100,t=d,i={id},c={columns},r={rows},C=1,q=2,m={more};")
        } else {
            format!("\x1b_Gm={more};")
        };
        out.extend_from_slice(header.as_bytes());
        out.extend_from_slice(chunk);
        out.extend_from_slice(b"\x1b\\");
    }
    out
}

#[must_use]
pub fn hydrate_images(model: &mut AppModel) -> Option<Effect> {
    let layout = crate::ui::layout_for(model);
    let dimensions = (
        layout.content.width.saturating_sub(2),
        layout.content.height.saturating_sub(4) / 2,
    );
    let View::Reader { memo, .. } = &model.view else {
        model.images.clear();
        return None;
    };
    if model.graphics == GraphicsProtocol::None
        || dimensions.0 == 0
        || dimensions.1 == 0
        || !matches!(memo.body, BodyState::Ready(_))
    {
        return None;
    }
    let Some(cells) = image_cells(model.graphics, model.cell_size) else {
        model.images.clear();
        return None;
    };
    let requests: Vec<_> = memo
        .attachments
        .iter()
        .filter(|path| media_kind_for_path(path.as_str()) == MediaKind::Image)
        .map(|path| ImageRequest {
            epoch: model.epoch,
            version: memo.version(),
            path: path.clone(),
            columns: dimensions.0,
            rows: dimensions.1,
            protocol: model.graphics,
            cells,
        })
        .collect();
    if !model
        .images
        .iter()
        .map(|image| &image.request)
        .eq(requests.iter())
    {
        model.images = requests
            .into_iter()
            .map(|request| ReaderImage {
                request,
                state: ImageState::Pending,
            })
            .collect();
    }
    let image = model
        .images
        .iter_mut()
        .find(|image| image.state == ImageState::Pending)?;
    image.state = ImageState::Loading;
    Some(Effect::LoadImage(image.request.clone()))
}

pub fn apply_image(
    model: &mut AppModel,
    request: &ImageRequest,
    result: Result<TerminalImage, String>,
) {
    if request.epoch != model.epoch {
        return;
    }
    if let Some(image) = model
        .images
        .iter_mut()
        .find(|image| &image.request == request)
    {
        image.state = match result {
            Ok(image) => ImageState::Ready(Arc::new(image)),
            Err(error) => ImageState::Failed(error),
        };
    }
}
