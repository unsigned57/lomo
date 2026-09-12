//! Bounded 216-color Sixel encoder. It consumes the worker's already resized RGB pixels.
use crate::error::TuiError;
use image::RgbImage;
use std::io::Write;

/// # Errors
/// Output buffer writes propagate through the image preparation task.
pub fn encode(image: &RgbImage) -> Result<Vec<u8>, TuiError> {
    let mut out = format!("\x1bPq\"1;1;{};{}", image.width(), image.height()).into_bytes();
    for color in 0..216_u16 {
        let red = color / 36 * 20;
        let green = color / 6 % 6 * 20;
        let blue = color % 6 * 20;
        write!(out, "#{color};2;{red};{green};{blue}")?;
    }
    for y in (0..image.height()).step_by(6) {
        for color in 0..216_u16 {
            let masks: Vec<_> = (0..image.width())
                .map(|x| column_mask(image, x, y, color))
                .collect();
            if masks.iter().all(|mask| *mask == 0) {
                continue;
            }
            write!(out, "#{color}")?;
            for mask in masks {
                out.push(63 + mask);
            }
            out.push(b'$');
        }
        out.push(b'-');
    }
    out.extend_from_slice(b"\x1b\\");
    Ok(out)
}

fn column_mask(image: &RgbImage, x: u32, y: u32, color: u16) -> u8 {
    let mut mask = 0;
    for bit in 0..6_u32 {
        if y + bit >= image.height() {
            break;
        }
        let pixel = image.get_pixel(x, y + bit).0;
        let mut channels = pixel
            .into_iter()
            .map(|channel| u16::from(channel) * 5 / 255);
        let value = channels
            .by_ref()
            .fold(0, |value, channel| value * 6 + channel);
        if value == color {
            mask |= 1 << bit;
        }
    }
    mask
}
