//! Unicode-safe text input shared by quick capture and filter fields.
use unicode_segmentation::UnicodeSegmentation;

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct TextBuffer {
    text: String,
    cursor: usize,
    undo: Vec<(String, usize)>,
    redo: Vec<(String, usize)>,
}
impl TextBuffer {
    #[must_use]
    pub const fn new(text: String) -> Self {
        let cursor = text.len();
        Self {
            text,
            cursor,
            undo: Vec::new(),
            redo: Vec::new(),
        }
    }
    #[must_use]
    pub fn text(&self) -> &str {
        &self.text
    }
    #[must_use]
    pub const fn cursor(&self) -> usize {
        self.cursor
    }
    pub fn insert(&mut self, value: &str) {
        if value.is_empty() {
            return;
        }
        self.checkpoint();
        let value = value.replace("\r\n", "\n").replace('\r', "\n");
        self.text.insert_str(self.cursor, &value);
        self.cursor += value.len();
    }
    pub fn backspace(&mut self) {
        let Some((previous, _)) = self.before_cursor().grapheme_indices(true).next_back() else {
            return;
        };
        self.checkpoint();
        self.text.replace_range(previous..self.cursor, "");
        self.cursor = previous;
    }
    pub fn delete(&mut self) {
        let Some(length) = self.after_cursor().graphemes(true).next().map(str::len) else {
            return;
        };
        self.checkpoint();
        self.text
            .replace_range(self.cursor..self.cursor + length, "");
    }
    pub fn left(&mut self) {
        if let Some((index, _)) = self.before_cursor().grapheme_indices(true).next_back() {
            self.cursor = index;
        }
    }
    pub fn right(&mut self) {
        if let Some(length) = self.after_cursor().graphemes(true).next().map(str::len) {
            self.cursor += length;
        }
    }
    pub fn home(&mut self) {
        self.cursor = self
            .before_cursor()
            .rfind('\n')
            .map_or(0, |index| index + 1);
    }
    pub fn end(&mut self) {
        self.cursor += self
            .after_cursor()
            .find('\n')
            .unwrap_or_else(|| self.after_cursor().len());
    }
    pub fn set_cursor(&mut self, byte: usize) {
        self.cursor = self
            .text
            .grapheme_indices(true)
            .map(|(index, _)| index)
            .chain(std::iter::once(self.text.len()))
            .take_while(|index| *index <= byte)
            .last()
            .unwrap_or(0);
    }
    pub fn undo(&mut self) {
        if let Some((text, cursor)) = self.undo.pop() {
            self.redo
                .push((std::mem::replace(&mut self.text, text), self.cursor));
            self.cursor = cursor;
        }
    }
    pub fn redo(&mut self) {
        if let Some((text, cursor)) = self.redo.pop() {
            self.undo
                .push((std::mem::replace(&mut self.text, text), self.cursor));
            self.cursor = cursor;
        }
    }
    #[must_use]
    pub fn tag_prefix(&self) -> Option<(usize, &str)> {
        let start = self.before_cursor().rfind('#')?;
        if start > 0
            && !self
                .before_cursor()
                .get(..start)?
                .chars()
                .next_back()?
                .is_ascii_whitespace()
        {
            return None;
        }
        let prefix = self.text.get(start + 1..self.cursor)?;
        (!prefix.chars().any(char::is_whitespace)).then_some((start, prefix))
    }
    pub fn complete_tag(&mut self, tag: &str) {
        if let Some((start, _)) = self.tag_prefix() {
            self.checkpoint();
            let value = format!("#{tag} ");
            self.text.replace_range(start..self.cursor, &value);
            self.cursor = start + value.len();
        }
    }
    #[must_use]
    pub fn before_cursor(&self) -> &str {
        self.text.split_at(self.cursor).0
    }
    fn after_cursor(&self) -> &str {
        self.text.split_at(self.cursor).1
    }
    fn checkpoint(&mut self) {
        self.undo.push((self.text.clone(), self.cursor));
        self.redo.clear();
        if self.undo.len() > 100 {
            self.undo.remove(0);
        }
    }
}
