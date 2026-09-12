//! Images are erased before any geometry or view change, then drawn after the text frame.
use crate::{error::TuiError, media::GraphicsProtocol, model::AppModel, reader::ImagePlacement};
use crossterm::{
    cursor::{MoveTo, RestorePosition, SavePosition},
    queue,
};
use ratatui::{Terminal, backend::CrosstermBackend};
use std::io::{self, Write};

#[derive(Default)]
pub struct ImageSurface {
    visible: Vec<ImagePlacement>,
}

impl ImageSurface {
    /// # Errors
    /// Terminal output failures remain visible to the host lifecycle guard.
    pub fn draw(
        &mut self,
        terminal: &mut Terminal<CrosstermBackend<io::Stdout>>,
        model: &AppModel,
    ) -> Result<(), TuiError> {
        let placements = crate::reader::page(model).map_or_else(Vec::new, |page| page.pictures);
        let changed = self.visible != placements;
        if changed {
            erase(terminal.backend_mut(), &self.visible)?;
            terminal.clear()?;
            self.visible = placements;
        }
        terminal.draw(|frame| crate::ui::draw(frame, model))?;
        if changed {
            for placement in &self.visible {
                queue!(
                    terminal.backend_mut(),
                    SavePosition,
                    MoveTo(placement.rect.x, placement.rect.y)
                )?;
                terminal.backend_mut().write_all(&placement.image.payload)?;
                queue!(terminal.backend_mut(), RestorePosition)?;
            }
            terminal.backend_mut().flush()?;
        }
        Ok(())
    }

    pub fn reset(&mut self) {
        self.visible.clear();
    }
}

fn erase(output: &mut impl Write, placements: &[ImagePlacement]) -> Result<(), TuiError> {
    for placement in placements {
        if placement.image.protocol == GraphicsProtocol::Kitty {
            write!(output, "\x1b_Ga=d,d=I,i={},q=2\x1b\\", placement.image.id)?;
        }
    }
    Ok(())
}
