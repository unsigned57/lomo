//! Shared fail-closed XML document walker over `quick-xml` (B-14 P-50).
//!
//! Replaces the handwritten string scanners previously used by the S3 `ListObjectsV2`,
//! `CreateMultipartUpload`, and `WebDAV` Multi-Status parsers:
//!
//! - Local names strip namespace prefixes via `local_name()`, preserving the previous
//!   namespace-insensitive matching contract.
//! - `Text`/`CData` payloads, predefined entity references (`amp`, `lt`, `gt`, `quot`,
//!   `apos`), and numeric character references surface as decoded character data.
//! - `DOCTYPE` declarations and undeclared general entity references fail closed.
//! - Structure is enforced: mismatched end tags (reader `check_end_names`), a second root
//!   element, or EOF inside an open element are all malformed.
//! - [`MAX_XML_DEPTH`] caps element nesting so hostile depth cannot exhaust the stack.

use crate::error::validation;
use lomo_core::LomoError;
use quick_xml::events::Event;
use quick_xml::reader::Reader;

/// Hard cap on element nesting — legitimate S3/WebDAV responses stay under a dozen levels.
const MAX_XML_DEPTH: u32 = 128;

/// Parsed element tree for one XML document.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct XmlElement {
    /// Local element name (namespace prefix stripped).
    pub local: String,
    /// Character data flattened from the whole subtree in document order.
    pub text: String,
    /// Child elements in document order.
    pub children: Vec<Self>,
}

impl XmlElement {
    /// Depth-first preorder search (self included) for the first element named `local`.
    /// `case_insensitive` preserves the `WebDAV` walker's ASCII-insensitive matching.
    #[must_use]
    pub fn find_first(&self, local: &str, case_insensitive: bool) -> Option<&Self> {
        if self.matches(local, case_insensitive) {
            return Some(self);
        }
        self.children
            .iter()
            .find_map(|child| child.find_first(local, case_insensitive))
    }

    /// Collects every element named `local` into `out`, preorder (self included).
    pub fn find_all<'a>(&'a self, local: &str, case_insensitive: bool, out: &mut Vec<&'a Self>) {
        if self.matches(local, case_insensitive) {
            out.push(self);
        }
        for child in &self.children {
            child.find_all(local, case_insensitive, out);
        }
    }

    /// Trimmed character data of this element subtree.
    #[must_use]
    pub fn trimmed_text(&self) -> &str {
        self.text.trim()
    }

    fn matches(&self, local: &str, case_insensitive: bool) -> bool {
        if case_insensitive {
            self.local.eq_ignore_ascii_case(local)
        } else {
            self.local == local
        }
    }
}

/// One normalized node from a walked XML stream.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum XmlNode {
    /// Start tag; carries the local name with any namespace prefix stripped.
    Start(String),
    /// End tag; carries the local name.
    End(String),
    /// Self-closing tag; carries the local name.
    Empty(String),
    /// Character data — decoded text, CDATA payload, or a resolved reference.
    Text(String),
}

/// Parses `text` into its root element tree.
///
/// # Errors
///
/// `malformed_code` on structural XML errors (unclosed/mismatched/extra root elements);
/// `entities_code` on `DOCTYPE` declarations or undeclared general entity references.
pub fn parse_document(
    text: &str,
    malformed_code: &'static str,
    malformed_message: &'static str,
    entities_code: &'static str,
    entities_message: &'static str,
) -> Result<Option<XmlElement>, LomoError> {
    let mut walker = XmlWalker::new(
        text,
        malformed_code,
        malformed_message,
        entities_code,
        entities_message,
    );
    let mut root: Option<XmlElement> = None;
    while let Some(node) = walker.next()? {
        match node {
            XmlNode::Start(local) => {
                root = Some(read_element(&mut walker, local)?);
            }
            XmlNode::Empty(local) => {
                root = Some(XmlElement {
                    local,
                    ..XmlElement::default()
                });
            }
            XmlNode::End(_) | XmlNode::Text(_) => {}
        }
    }
    Ok(root)
}

/// Reads one open element's subtree; `walker` is positioned just after its start tag.
/// EOF inside the element is rejected by the walker's depth tracking.
fn read_element(walker: &mut XmlWalker<'_>, local: String) -> Result<XmlElement, LomoError> {
    let mut element = XmlElement {
        local,
        ..XmlElement::default()
    };
    while let Some(node) = walker.next()? {
        match node {
            XmlNode::Start(child_local) => {
                element.children.push(read_element(walker, child_local)?);
            }
            XmlNode::Empty(child_local) => {
                element.children.push(XmlElement {
                    local: child_local,
                    ..XmlElement::default()
                });
            }
            XmlNode::Text(text) => element.text.push_str(&text),
            XmlNode::End(_) => return Ok(element),
        }
    }
    Ok(element)
}

/// Fail-closed, depth-tracking event stream over [`quick_xml::reader::Reader`].
///
/// Error codes/messages are caller-provided so each protocol surface keeps its contract.
pub struct XmlWalker<'a> {
    reader: Reader<&'a [u8]>,
    depth: u32,
    root_done: bool,
    malformed_code: &'static str,
    malformed_message: &'static str,
    entities_code: &'static str,
    entities_message: &'static str,
}

impl<'a> XmlWalker<'a> {
    /// Creates a walker over `text`. No event is emitted until [`Self::next`].
    #[must_use]
    pub fn new(
        text: &'a str,
        malformed_code: &'static str,
        malformed_message: &'static str,
        entities_code: &'static str,
        entities_message: &'static str,
    ) -> Self {
        Self {
            reader: Reader::from_str(text),
            depth: 0,
            root_done: false,
            malformed_code,
            malformed_message,
            entities_code,
            entities_message,
        }
    }

    /// Reads the next normalized node; `Ok(None)` at clean end of document.
    ///
    /// # Errors
    ///
    /// `malformed_code` on structural errors; `entities_code` on DTD/entity vectors.
    pub fn next(&mut self) -> Result<Option<XmlNode>, LomoError> {
        loop {
            match self.reader.read_event() {
                Ok(Event::Start(start)) => {
                    if self.root_done {
                        return Err(self.malformed());
                    }
                    self.depth = self.depth.saturating_add(1);
                    if self.depth > MAX_XML_DEPTH {
                        return Err(self.malformed());
                    }
                    return Ok(Some(XmlNode::Start(
                        start.local_name().into_inner().to_owned(),
                    )));
                }
                Ok(Event::Empty(empty)) => {
                    if self.root_done {
                        return Err(self.malformed());
                    }
                    if self.depth == 0 {
                        self.root_done = true;
                    }
                    return Ok(Some(XmlNode::Empty(
                        empty.local_name().into_inner().to_owned(),
                    )));
                }
                Ok(Event::End(end)) => {
                    if self.depth == 0 {
                        return Err(self.malformed());
                    }
                    self.depth = self.depth.saturating_sub(1);
                    if self.depth == 0 {
                        self.root_done = true;
                    }
                    return Ok(Some(XmlNode::End(end.local_name().into_inner().to_owned())));
                }
                Ok(Event::Text(text)) => {
                    return Ok(Some(XmlNode::Text(text.xml10_content().into_owned())));
                }
                Ok(Event::CData(cdata)) => {
                    return Ok(Some(XmlNode::Text(cdata.xml10_content().into_owned())));
                }
                Ok(Event::GeneralRef(reference)) => {
                    return self.resolve_reference(&reference);
                }
                Ok(Event::DocType(_)) => return Err(self.entities()),
                Ok(Event::Decl(_) | Event::PI(_) | Event::Comment(_)) => {}
                Ok(Event::Eof) => {
                    if self.depth > 0 {
                        return Err(self.malformed());
                    }
                    return Ok(None);
                }
                Err(_error) => return Err(self.malformed()),
            }
        }
    }

    /// Resolves a `&name;` reference: predefined XML entities and numeric character
    /// references decode; anything else is an undeclared entity and fails closed.
    fn resolve_reference(
        &self,
        reference: &quick_xml::events::BytesRef<'_>,
    ) -> Result<Option<XmlNode>, LomoError> {
        if let Some(resolved) = quick_xml::escape::resolve_predefined_entity(reference) {
            return Ok(Some(XmlNode::Text(resolved.to_owned())));
        }
        if reference.is_char_ref() {
            match reference.resolve_char_ref() {
                Ok(Some(character)) => return Ok(Some(XmlNode::Text(character.to_string()))),
                Ok(None) => return Err(self.entities()),
                Err(_error) => return Err(self.entities()),
            }
        }
        Err(self.entities())
    }

    fn malformed(&self) -> LomoError {
        validation(self.malformed_code, self.malformed_message)
    }

    fn entities(&self) -> LomoError {
        validation(self.entities_code, self.entities_message)
    }
}
