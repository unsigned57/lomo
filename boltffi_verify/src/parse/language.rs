use std::path::Path;

use super::ParseError;
use crate::ir::VerifyUnit;

pub trait LanguageParser: Send {
    fn language_name(&self) -> &'static str;

    fn file_extensions(&self) -> &'static [&'static str];

    fn parse_file(&mut self, path: &Path) -> Result<Vec<VerifyUnit>, ParseError> {
        let content = std::fs::read_to_string(path)?;
        self.parse_source(path, &content)
    }

    fn parse_source(&mut self, path: &Path, source: &str) -> Result<Vec<VerifyUnit>, ParseError>;

    fn can_parse(&self, path: &Path) -> bool {
        path.extension()
            .and_then(|ext| ext.to_str())
            .map(|ext| self.file_extensions().contains(&ext))
            .unwrap_or(false)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Language {
    Swift,
    Kotlin,
}

impl Language {
    pub fn from_extension(ext: &str) -> Option<Self> {
        match ext {
            "swift" => Some(Self::Swift),
            "kt" | "kts" => Some(Self::Kotlin),
            _ => None,
        }
    }

    pub fn from_path(path: &Path) -> Option<Self> {
        path.extension()
            .and_then(|e| e.to_str())
            .and_then(Self::from_extension)
    }

    pub fn name(&self) -> &'static str {
        match self {
            Self::Swift => "Swift",
            Self::Kotlin => "Kotlin",
        }
    }

    pub fn extensions(&self) -> &'static [&'static str] {
        match self {
            Self::Swift => &["swift"],
            Self::Kotlin => &["kt", "kts"],
        }
    }
}
