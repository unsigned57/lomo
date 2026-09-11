/// Responsive layout thresholds from the multiplatform contract.
pub const TRIPLE_MIN_COLS: u16 = 120;
pub const DUAL_MIN_COLS: u16 = 80;

/// Width-driven pane arrangement.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum LayoutMode {
    Triple,
    Dual,
    Single,
}

/// Keyboard focus among the three logical panes.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Focus {
    Navigation,
    List,
    Preview,
}

/// Dual-width navigation drawer.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NavPresence {
    Hidden,
    Shown,
}

/// Integer rectangle used by layout tests and mapped into Ratatui at draw time.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Pane {
    pub x: u16,
    pub y: u16,
    pub width: u16,
    pub height: u16,
}

impl Pane {
    #[must_use]
    pub const fn empty() -> Self {
        Self {
            x: 0,
            y: 0,
            width: 0,
            height: 0,
        }
    }
}

/// Inputs that fully determine pane geometry.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LayoutRequest {
    pub mode: LayoutMode,
    pub focus: Focus,
    pub nav: NavPresence,
    pub search_open: bool,
}

/// Assigned panes. Missing panes are `None` so callers cannot draw into the wrong region.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PaneMap {
    pub navigation: Option<Pane>,
    pub list: Option<Pane>,
    pub preview: Option<Pane>,
    pub search: Option<Pane>,
    pub status: Pane,
}

/// Classifies a terminal width into the contract's three layout modes.
#[must_use]
pub const fn layout_mode(width: u16) -> LayoutMode {
    if width >= TRIPLE_MIN_COLS {
        LayoutMode::Triple
    } else if width >= DUAL_MIN_COLS {
        LayoutMode::Dual
    } else {
        LayoutMode::Single
    }
}

/// Splits `area` into panes. Focus is preserved by the caller; this function only maps geometry.
#[must_use]
pub fn split_panes(area: Pane, request: LayoutRequest) -> PaneMap {
    if area.width == 0 || area.height == 0 {
        return PaneMap {
            navigation: None,
            list: None,
            preview: None,
            search: None,
            status: Pane::empty(),
        };
    }
    let status_h = 1_u16.min(area.height);
    let status = Pane {
        x: area.x,
        y: area.y.saturating_add(area.height.saturating_sub(status_h)),
        width: area.width,
        height: status_h,
    };
    let mut work = Pane {
        x: area.x,
        y: area.y,
        width: area.width,
        height: area.height.saturating_sub(status_h),
    };
    let search = if request.search_open {
        let height = 3_u16.min(work.height);
        let pane = Pane {
            x: work.x,
            y: work.y,
            width: work.width,
            height,
        };
        work.y = work.y.saturating_add(height);
        work.height = work.height.saturating_sub(height);
        Some(pane)
    } else {
        None
    };
    let mut map = match request.mode {
        LayoutMode::Triple | LayoutMode::Dual => {
            split_columns(work, request.nav == NavPresence::Shown)
        }
        LayoutMode::Single => split_single(work, request.focus),
    };
    map.search = search;
    map.status = status;
    map
}

/// Cycles focus Nav → List → Preview → Nav without depending on which panes are drawn.
#[must_use]
pub const fn next_focus(focus: Focus) -> Focus {
    match focus {
        Focus::Navigation => Focus::List,
        Focus::List => Focus::Preview,
        Focus::Preview => Focus::Navigation,
    }
}

fn split_columns(work: Pane, show_nav: bool) -> PaneMap {
    if show_nav {
        let nav_w = 22_u16.min(work.width);
        let rest_w = work.width.saturating_sub(nav_w);
        let list_w = rest_w / 2;
        let preview_w = rest_w.saturating_sub(list_w);
        PaneMap {
            navigation: Some(Pane {
                x: work.x,
                y: work.y,
                width: nav_w,
                height: work.height,
            }),
            list: Some(Pane {
                x: work.x.saturating_add(nav_w),
                y: work.y,
                width: list_w,
                height: work.height,
            }),
            preview: Some(Pane {
                x: work.x.saturating_add(nav_w).saturating_add(list_w),
                y: work.y,
                width: preview_w,
                height: work.height,
            }),
            search: None,
            status: Pane::empty(),
        }
    } else {
        let list_w = work.width / 2;
        PaneMap {
            navigation: None,
            list: Some(Pane {
                x: work.x,
                y: work.y,
                width: list_w,
                height: work.height,
            }),
            preview: Some(Pane {
                x: work.x.saturating_add(list_w),
                y: work.y,
                width: work.width.saturating_sub(list_w),
                height: work.height,
            }),
            search: None,
            status: Pane::empty(),
        }
    }
}

const fn split_single(work: Pane, focus: Focus) -> PaneMap {
    let pane = Some(work);
    match focus {
        Focus::Navigation => PaneMap {
            navigation: pane,
            list: None,
            preview: None,
            search: None,
            status: Pane::empty(),
        },
        Focus::List => PaneMap {
            navigation: None,
            list: pane,
            preview: None,
            search: None,
            status: Pane::empty(),
        },
        Focus::Preview => PaneMap {
            navigation: None,
            list: None,
            preview: pane,
            search: None,
            status: Pane::empty(),
        },
    }
}
