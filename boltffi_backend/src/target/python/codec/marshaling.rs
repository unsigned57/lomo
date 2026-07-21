use crate::target::python::cpython::render::{direct_vector, primitive};

/// The conversions one value needs to cross the FFI boundary.
///
/// A value reports the primitive, direct-vector, string, bytes, and raw-wire
/// conversions it marshals through so the module emitter includes the matching
/// runtime converters.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Marshaling {
    primitive: Option<primitive::Runtime>,
    wire_primitive: Option<primitive::Runtime>,
    direct_vector: Option<direct_vector::Element>,
    string: bool,
    bytes: bool,
    raw_wire: bool,
}

impl Marshaling {
    pub fn none() -> Self {
        Self::default()
    }

    pub fn direct(primitive: Option<primitive::Runtime>) -> Self {
        Self {
            primitive,
            ..Self::default()
        }
    }

    pub fn wire(primitive: primitive::Runtime) -> Self {
        Self {
            wire_primitive: Some(primitive),
            ..Self::default()
        }
    }

    pub fn direct_vector(element: direct_vector::Element) -> Self {
        Self {
            direct_vector: Some(element),
            ..Self::default()
        }
    }
}

impl Marshaling {
    pub fn primitive(&self) -> Option<primitive::Runtime> {
        self.primitive
    }

    pub fn wire_primitive(&self) -> Option<primitive::Runtime> {
        self.wire_primitive
    }

    pub fn direct_vector_element(&self) -> Option<direct_vector::Element> {
        self.direct_vector.clone()
    }

    pub fn has_string(&self) -> bool {
        self.string
    }

    pub fn has_bytes(&self) -> bool {
        self.bytes
    }

    pub fn has_raw_wire(&self) -> bool {
        self.raw_wire
    }

    pub fn or(&self, other: &Self) -> Self {
        Self {
            primitive: self.primitive.or(other.primitive),
            wire_primitive: self.wire_primitive.or(other.wire_primitive),
            direct_vector: self
                .direct_vector
                .clone()
                .or_else(|| other.direct_vector.clone()),
            string: self.string || other.string,
            bytes: self.bytes || other.bytes,
            raw_wire: self.raw_wire || other.raw_wire,
        }
    }
}
