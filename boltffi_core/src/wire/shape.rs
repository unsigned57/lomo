#[cfg(feature = "chrono")]
use chrono::{DateTime, Utc};
use std::time::{Duration, SystemTime};
#[cfg(feature = "url")]
use url::Url;
#[cfg(feature = "uuid")]
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FixedWireShape {
    Primitive {
        bytes: usize,
    },
    Bool,
    PointerInteger,
    Duration,
    SystemTime,
    #[cfg(feature = "chrono")]
    DateTimeUtc,
    #[cfg(feature = "uuid")]
    Uuid,
    Unit,
}

impl FixedWireShape {
    pub(crate) const fn bytes(self) -> usize {
        match self {
            Self::Primitive { bytes } => bytes,
            Self::Bool => 1,
            Self::PointerInteger => 8,
            Self::Duration | Self::SystemTime => 12,
            #[cfg(feature = "chrono")]
            Self::DateTimeUtc => 12,
            #[cfg(feature = "uuid")]
            Self::Uuid => 16,
            Self::Unit => 0,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum VariableWireShape {
    Utf8String,
    #[cfg(feature = "url")]
    Url,
    Option,
    Vec,
    Result,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WireLayout {
    Fixed(FixedWireShape),
    Variable(VariableWireShape),
}

impl WireLayout {
    pub(crate) const fn is_fixed_size(self) -> bool {
        matches!(self, Self::Fixed(_))
    }

    pub(crate) const fn fixed_size(self) -> Option<usize> {
        match self {
            Self::Fixed(shape) => Some(shape.bytes()),
            Self::Variable(_) => None,
        }
    }
}

pub(crate) trait WireShape {
    const LAYOUT: WireLayout;
}

macro_rules! impl_primitive_wire_shape {
    ($($ty:ty),* $(,)?) => {
        $(
            impl WireShape for $ty {
                const LAYOUT: WireLayout =
                    WireLayout::Fixed(FixedWireShape::Primitive { bytes: core::mem::size_of::<$ty>() });
            }
        )*
    };
}

impl_primitive_wire_shape!(i8, i16, i32, i64, u8, u16, u32, u64, f32, f64);

impl WireShape for bool {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::Bool);
}

impl WireShape for isize {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::PointerInteger);
}

impl WireShape for usize {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::PointerInteger);
}

impl WireShape for str {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Utf8String);
}

impl WireShape for String {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Utf8String);
}

impl WireShape for Duration {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::Duration);
}

impl WireShape for SystemTime {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::SystemTime);
}

#[cfg(feature = "uuid")]
impl WireShape for Uuid {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::Uuid);
}

#[cfg(feature = "url")]
impl WireShape for Url {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Url);
}

#[cfg(feature = "chrono")]
impl WireShape for DateTime<Utc> {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::DateTimeUtc);
}

impl<T> WireShape for Option<T> {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Option);
}

impl<T> WireShape for Vec<T> {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Vec);
}

impl<T> WireShape for [T] {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Vec);
}

impl<T, E> WireShape for Result<T, E> {
    const LAYOUT: WireLayout = WireLayout::Variable(VariableWireShape::Result);
}

impl WireShape for () {
    const LAYOUT: WireLayout = WireLayout::Fixed(FixedWireShape::Unit);
}

impl<T: WireShape + ?Sized> WireShape for &T {
    const LAYOUT: WireLayout = T::LAYOUT;
}
