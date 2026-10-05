// adversarial-audit: Ready-image demotion re-enters the hydration queue, so an
// over-budget image set never settles (decode thrash); identity guards are locked too.
//
// Claims under test:
//  * `READY_IMAGE_BUDGET_BYTES` bounds the resident working set without starving it —
//    demoted images must not be re-requested on the very next hydration tick.
//  * `ImageRequest` identity (version/path/geometry/picker) guards late replies.
//  * Resident bytes are the real fitted raster a `TerminalImage` retains, not a
//    declared size — the test sizes requests so decodes genuinely cross the budget.

#[cfg(test)]
pub mod support;

#[cfg(test)]
#[expect(
    clippy::expect_used,
    reason = "Adversarial fixtures must be constructed successfully before probing"
)]
mod tests {
    use super::support::{model_with_memos, ready_graphics};
    use lomo_core::RelativeWorkspacePath;
    use lomo_tui::{
        effects::Effect,
        graphics::{self, ImageState, READY_IMAGE_BUDGET_BYTES, TerminalImage},
        model::{AppModel, CancelToken, TextAnchor, View},
    };
    use ratatui_image::picker::{Picker, ProtocolType};
    use std::{collections::BTreeMap, sync::Arc};

    fn reader_model(paths: &[&str]) -> AppModel {
        let mut model = model_with_memos(1, 80, 24).expect("fixture");
        let mut memo = model.selected_memo().expect("memo").clone();
        memo.attachments = paths
            .iter()
            .map(|path| RelativeWorkspacePath::parse(path).expect("attachment path"))
            .collect();
        model.view = View::Reader {
            memo,
            anchor: TextAnchor::default(),
        };
        model.graphics = ready_graphics(ProtocolType::Halfblocks);
        model
    }

    /// A real decoded image whose fitted raster lands near `bytes` — the budget
    /// accounts the raster `TerminalImage` retains, so the test drives the true
    /// accounting path instead of declaring a size.
    fn resident(columns: u16, rows: u16, font: (u16, u16)) -> TerminalImage {
        let mut picker = Picker::from_fontsize(font);
        picker.set_protocol_type(ProtocolType::Halfblocks);
        let png =
            lomo_tui::media::rgba_to_png(16, 16, &[255, 80, 20, 255].repeat(256)).expect("png");
        graphics::prepare_image(&png, columns, rows, &picker, &CancelToken::live())
            .expect("image fixture")
    }

    /// Three images that cannot all fit the aggregate budget are loaded in a loop the
    /// host runs every tick: hydrate → apply → (budget demotes oldest to `Evicted`) →
    /// hydrate must not pick the demoted image again. A bounded cache settles: each
    /// request is decoded at most once per identity; re-issuing the same path proves
    /// thrash.
    #[test]
    fn over_budget_image_set_reaches_quiescence() {
        let mut model = reader_model(&["media/a.png", "media/b.png", "media/c.png"]);
        // Each ~23 MiB raster (2400² px fitted) fits alone; two fit together;
        // all three cannot.
        let probe = resident(150, 80, (16, 32));
        assert!(
            probe.bytes() * 3 > READY_IMAGE_BUDGET_BYTES
                && probe.bytes() * 2 < READY_IMAGE_BUDGET_BYTES,
            "fixture sizing: three admissions evict, two fit: {}",
            probe.bytes()
        );
        let mut loads: BTreeMap<String, u32> = BTreeMap::new();
        for _ in 0..12 {
            let Some(Effect::LoadImage { request, .. }) = graphics::hydrate_images(&mut model)
            else {
                break;
            };
            *loads.entry(request.path.as_str().to_owned()).or_default() += 1;
            graphics::apply_image(
                &mut model,
                &request,
                Ok(Arc::new(resident(150, 80, (16, 32)))),
            );
        }
        assert!(
            !loads.is_empty(),
            "the reader must request its attachment images"
        );
        assert!(
            loads.values().all(|count| *count == 1),
            "a bounded cache loads each image once per identity; re-issues prove thrash: {loads:?}"
        );
    }

    /// Late replies for a dead request are dropped; an over-budget single raster is a
    /// visible failure rather than a resident image; an in-flight load is not duplicated.
    #[test]
    fn stale_epoch_replies_and_oversize_payloads_do_not_settle_state() {
        let mut model = reader_model(&["media/a.png"]);
        let Some(Effect::LoadImage { request, .. }) = graphics::hydrate_images(&mut model) else {
            panic!("a pending image must request a load");
        };
        assert!(
            graphics::hydrate_images(&mut model).is_none(),
            "an in-flight load must not be re-issued"
        );
        // The image reply is matched by full request identity: a request that
        // no longer sits in `model.images` cannot resolve the in-flight load.
        let mut stale = request.clone();
        stale.rows += 1;
        graphics::apply_image(&mut model, &stale, Ok(Arc::new(resident(4, 2, (8, 16)))));
        assert!(
            model
                .images
                .first()
                .is_some_and(|image| matches!(image.state, ImageState::Loading(_))),
            "a reply for a dead request must not resolve the in-flight image"
        );
        let oversized = resident(150, 150, (32, 32));
        assert!(
            oversized.bytes() > READY_IMAGE_BUDGET_BYTES,
            "fixture raster must exceed the budget: {}",
            oversized.bytes()
        );
        graphics::apply_image(&mut model, &request, Ok(Arc::new(oversized)));
        assert!(
            model
                .images
                .first()
                .is_some_and(|image| matches!(image.state, ImageState::Failed(_))),
            "a payload over the whole budget is a visible failure, never resident"
        );
    }
}
