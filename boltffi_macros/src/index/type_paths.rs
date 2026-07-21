use syn::{Path, Type};

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct TypePathKey {
    segments: Vec<String>,
}

impl TypePathKey {
    pub fn from_path(path: &Path) -> Self {
        let mut segments = path
            .segments
            .iter()
            .map(|segment| segment.ident.to_string())
            .collect::<Vec<_>>();
        if segments
            .first()
            .is_some_and(|segment| matches!(segment.as_str(), "crate" | "self" | "super"))
        {
            segments.remove(0);
        }
        Self { segments }
    }

    pub fn from_type(ty: &Type) -> Option<Self> {
        match ty {
            Type::Path(type_path) if type_path.qself.is_none() => {
                Some(Self::from_path(&type_path.path))
            }
            Type::Group(group) => Self::from_type(group.elem.as_ref()),
            Type::Paren(paren) => Self::from_type(paren.elem.as_ref()),
            _ => None,
        }
    }

    pub fn segments(&self) -> &[String] {
        &self.segments
    }

    pub fn into_segments(self) -> Vec<String> {
        self.segments
    }

    pub fn is_single_segment(&self) -> bool {
        self.segments.len() == 1
    }

    pub fn first_segment(&self) -> Option<&String> {
        self.segments.first()
    }

    pub fn has_suffix(&self, suffix: &[String]) -> bool {
        self.segments.ends_with(suffix)
    }
}
