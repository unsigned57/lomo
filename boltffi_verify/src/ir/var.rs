use std::fmt;
use std::sync::atomic::{AtomicU32, Ordering};

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct VarId(u32);

impl VarId {
    pub fn new(id: u32) -> Self {
        Self(id)
    }

    pub fn as_u32(self) -> u32 {
        self.0
    }
}

impl fmt::Debug for VarId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "v{}", self.0)
    }
}

impl fmt::Display for VarId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "v{}", self.0)
    }
}

pub struct VarIdGenerator {
    next_id: AtomicU32,
}

impl VarIdGenerator {
    pub fn new() -> Self {
        Self {
            next_id: AtomicU32::new(0),
        }
    }

    pub fn next(&self) -> VarId {
        VarId(self.next_id.fetch_add(1, Ordering::Relaxed))
    }
}

impl Default for VarIdGenerator {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Clone, PartialEq, Eq, Hash)]
pub struct VarName(String);

impl VarName {
    pub fn new(name: impl Into<String>) -> Self {
        Self(name.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl From<&str> for VarName {
    fn from(s: &str) -> Self {
        Self(s.to_owned())
    }
}

impl From<String> for VarName {
    fn from(s: String) -> Self {
        Self(s)
    }
}

impl fmt::Debug for VarName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl fmt::Display for VarName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_var_id_generator() {
        let generator = VarIdGenerator::new();

        let first = generator.next();
        let second = generator.next();
        let third = generator.next();

        assert_eq!(first.as_u32(), 0);
        assert_eq!(second.as_u32(), 1);
        assert_eq!(third.as_u32(), 2);
    }

    #[test]
    fn test_var_id_display() {
        let var = VarId::new(42);
        assert_eq!(format!("{}", var), "v42");
    }
}
