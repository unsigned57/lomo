use std::path::{Path, PathBuf};
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct LineNumber(u32);

impl LineNumber {
    pub fn new(line: u32) -> Self {
        Self(line)
    }

    pub fn as_u32(self) -> u32 {
        self.0
    }

    pub fn as_usize(self) -> usize {
        self.0 as usize
    }
}

impl From<u32> for LineNumber {
    fn from(value: u32) -> Self {
        Self(value)
    }
}

impl From<usize> for LineNumber {
    fn from(value: usize) -> Self {
        Self(value as u32)
    }
}

impl std::fmt::Display for LineNumber {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ColumnNumber(u32);

impl ColumnNumber {
    pub fn new(column: u32) -> Self {
        Self(column)
    }

    pub fn as_u32(self) -> u32 {
        self.0
    }

    pub fn as_usize(self) -> usize {
        self.0 as usize
    }
}

impl From<u32> for ColumnNumber {
    fn from(value: u32) -> Self {
        Self(value)
    }
}

impl From<usize> for ColumnNumber {
    fn from(value: usize) -> Self {
        Self(value as u32)
    }
}

impl std::fmt::Display for ColumnNumber {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ByteOffset(u32);

impl ByteOffset {
    pub fn new(offset: u32) -> Self {
        Self(offset)
    }

    pub fn as_u32(self) -> u32 {
        self.0
    }

    pub fn as_usize(self) -> usize {
        self.0 as usize
    }
}

impl From<u32> for ByteOffset {
    fn from(value: u32) -> Self {
        Self(value)
    }
}

impl From<usize> for ByteOffset {
    fn from(value: usize) -> Self {
        Self(value as u32)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct ByteLength(u32);

impl ByteLength {
    pub fn new(length: u32) -> Self {
        Self(length)
    }

    pub fn as_u32(self) -> u32 {
        self.0
    }

    pub fn as_usize(self) -> usize {
        self.0 as usize
    }
}

impl From<u32> for ByteLength {
    fn from(value: u32) -> Self {
        Self(value)
    }
}

impl From<usize> for ByteLength {
    fn from(value: usize) -> Self {
        Self(value as u32)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct SourcePosition {
    pub line: LineNumber,
    pub column: ColumnNumber,
    pub offset: ByteOffset,
}

impl SourcePosition {
    pub fn new(
        line: impl Into<LineNumber>,
        column: impl Into<ColumnNumber>,
        offset: impl Into<ByteOffset>,
    ) -> Self {
        Self {
            line: line.into(),
            column: column.into(),
            offset: offset.into(),
        }
    }
}

impl std::fmt::Display for SourcePosition {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.line, self.column)
    }
}

#[derive(Clone)]
pub struct SourceFile {
    path: PathBuf,
    content: Arc<str>,
    line_starts: Arc<[ByteOffset]>,
}

impl SourceFile {
    pub fn new(path: impl AsRef<Path>, content: impl Into<String>) -> Self {
        let content: Arc<str> = content.into().into();
        let line_starts = Self::compute_line_starts(&content);

        Self {
            path: path.as_ref().to_path_buf(),
            content,
            line_starts: line_starts.into(),
        }
    }

    fn compute_line_starts(content: &str) -> Vec<ByteOffset> {
        std::iter::once(ByteOffset::new(0))
            .chain(
                content
                    .bytes()
                    .enumerate()
                    .filter(|(_, byte)| *byte == b'\n')
                    .map(|(index, _)| ByteOffset::new((index + 1) as u32)),
            )
            .collect()
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn content(&self) -> &str {
        &self.content
    }

    pub fn line_count(&self) -> usize {
        self.line_starts.len()
    }

    pub fn position_at_offset(&self, offset: ByteOffset) -> SourcePosition {
        let line_index = self
            .line_starts
            .iter()
            .rposition(|&start| start.as_u32() <= offset.as_u32())
            .unwrap_or(0);

        let line_start = self.line_starts[line_index];
        let column = offset.as_u32() - line_start.as_u32();

        SourcePosition {
            line: LineNumber::new((line_index + 1) as u32),
            column: ColumnNumber::new(column + 1),
            offset,
        }
    }

    pub fn line_content(&self, line: LineNumber) -> Option<&str> {
        let line_index = line.as_usize().checked_sub(1)?;
        let start = self.line_starts.get(line_index)?.as_usize();
        let end = self
            .line_starts
            .get(line_index + 1)
            .map(|o| o.as_usize())
            .unwrap_or(self.content.len());

        self.content
            .get(start..end)
            .map(|s| s.trim_end_matches('\n'))
    }

    pub fn slice(&self, start: ByteOffset, length: ByteLength) -> Option<&str> {
        let start_index = start.as_usize();
        let end_index = start_index + length.as_usize();
        self.content.get(start_index..end_index)
    }
}

impl std::fmt::Debug for SourceFile {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SourceFile")
            .field("path", &self.path)
            .field("lines", &self.line_starts.len())
            .finish()
    }
}

#[derive(Clone)]
pub struct SourceSpan {
    file: Arc<SourceFile>,
    start: ByteOffset,
    length: ByteLength,
}

impl SourceSpan {
    pub fn new(
        file: Arc<SourceFile>,
        start: impl Into<ByteOffset>,
        length: impl Into<ByteLength>,
    ) -> Self {
        Self {
            file,
            start: start.into(),
            length: length.into(),
        }
    }

    pub fn file(&self) -> &SourceFile {
        &self.file
    }

    pub fn start_position(&self) -> SourcePosition {
        self.file.position_at_offset(self.start)
    }

    pub fn end_offset(&self) -> ByteOffset {
        ByteOffset::new(self.start.as_u32() + self.length.as_u32())
    }

    pub fn end_position(&self) -> SourcePosition {
        self.file.position_at_offset(self.end_offset())
    }

    pub fn text(&self) -> Option<&str> {
        self.file.slice(self.start, self.length)
    }

    pub fn line_number(&self) -> LineNumber {
        self.start_position().line
    }

    pub fn column_number(&self) -> ColumnNumber {
        self.start_position().column
    }

    pub fn source_line(&self) -> Option<&str> {
        self.file.line_content(self.line_number())
    }

    pub fn display_location(&self) -> String {
        let pos = self.start_position();
        format!("{}:{}:{}", self.file.path().display(), pos.line, pos.column)
    }

    pub fn merge(&self, other: &SourceSpan) -> Option<SourceSpan> {
        if !Arc::ptr_eq(&self.file, &other.file) {
            return None;
        }

        let start = self.start.as_u32().min(other.start.as_u32());
        let end = self.end_offset().as_u32().max(other.end_offset().as_u32());

        Some(SourceSpan {
            file: Arc::clone(&self.file),
            start: ByteOffset::new(start),
            length: ByteLength::new(end - start),
        })
    }
}

impl std::fmt::Debug for SourceSpan {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Span({})", self.display_location())
    }
}

impl std::fmt::Display for SourceSpan {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.display_location())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_line_starts_computation() {
        let source = SourceFile::new("test.swift", "line1\nline2\nline3");
        assert_eq!(source.line_count(), 3);
    }

    #[test]
    fn test_position_at_offset() {
        let source = SourceFile::new("test.swift", "abc\ndef\nghi");

        let pos = source.position_at_offset(ByteOffset::new(0));
        assert_eq!(pos.line.as_u32(), 1);
        assert_eq!(pos.column.as_u32(), 1);

        let pos = source.position_at_offset(ByteOffset::new(4));
        assert_eq!(pos.line.as_u32(), 2);
        assert_eq!(pos.column.as_u32(), 1);
    }

    #[test]
    fn test_line_content() {
        let source = SourceFile::new("test.swift", "first\nsecond\nthird");

        assert_eq!(source.line_content(LineNumber::new(1)), Some("first"));
        assert_eq!(source.line_content(LineNumber::new(2)), Some("second"));
        assert_eq!(source.line_content(LineNumber::new(3)), Some("third"));
    }
}
