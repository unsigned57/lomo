use boltffi::*;

use demo_multicrate_session::{
    ForeignCode, ForeignKind, ForeignLabeler, ForeignPoint, ForeignSession, ForeignState,
    ForeignUser, SessionBook, SessionEvent, kind_label, model_point_sum, session_event_summary,
    session_make, session_summary, session_total_age, session_try_make,
};

#[export]
pub fn multi_echo_kind(kind: ForeignKind) -> ForeignKind {
    kind
}

#[export]
pub fn multi_kind_label(kind: ForeignKind) -> String {
    kind_label(kind)
}

#[export]
pub fn multi_shift_point(point: ForeignPoint, dx: f64, dy: f64) -> ForeignPoint {
    ForeignPoint {
        x: point.x + dx,
        y: point.y + dy,
    }
}

#[export]
pub fn multi_point_sum(point: &ForeignPoint) -> f64 {
    model_point_sum(point)
}

#[export]
pub fn multi_user_summary(user: ForeignUser) -> String {
    format!("{}#{}", user.name, user.age)
}

#[export]
pub fn multi_echo_code(code: ForeignCode) -> ForeignCode {
    code
}

#[export]
pub fn multi_code_value(code: ForeignCode) -> String {
    code.value().to_string()
}

#[export]
pub fn multi_state_summary(state: ForeignState) -> String {
    match state {
        ForeignState::Ready => "ready".to_string(),
        ForeignState::Busy { reason } => format!("busy:{reason}"),
    }
}

#[export]
pub fn multi_make_session(id: u32, user: ForeignUser, kind: ForeignKind) -> ForeignSession {
    session_make(id, user, kind)
}

#[export]
pub fn multi_session_summary(session: ForeignSession) -> String {
    session_summary(session)
}

#[export]
pub fn multi_total_age(sessions: Vec<ForeignSession>) -> u32 {
    session_total_age(sessions)
}

#[export]
pub fn multi_optional_user_name(session: Option<ForeignSession>) -> Option<String> {
    session.map(|session| session.user.name)
}

#[export]
pub fn multi_event_summary(event: SessionEvent) -> String {
    session_event_summary(event)
}

#[export]
pub fn multi_try_session(
    id: u32,
    user: ForeignUser,
    kind: ForeignKind,
) -> Result<ForeignSession, String> {
    session_try_make(id, user, kind)
}

#[export]
pub fn multi_borrowed_summary(
    user: &ForeignUser,
    session: &ForeignSession,
    kind: &ForeignKind,
) -> String {
    SessionBook::with_session(session.clone()).summarize_borrowed(user, session, kind)
}

#[export]
pub fn multi_format_with_labeler(
    labeler: impl ForeignLabeler,
    user: ForeignUser,
    kind: ForeignKind,
) -> String {
    labeler.label(user, kind)
}
