//! `RenderDocumentV1` projection from constrained source bytes.
//!
//! One pulldown offset stream builds typed blocks/inlines; Lomo extensions (tags, reminder, wiki,
//! highlight, wiki-image attachments) are classified on that same inline stream. Storage analysis
//! reuses this pipeline — there is no second body token authority.

use std::collections::BTreeSet;
use std::ops::Range;

use lomo_core::LomoError;
use pulldown_cmark::{CodeBlockKind, CowStr, Event, Options, Parser, Tag, TagEnd};
use serde::{Deserialize, Serialize};

use crate::limits::{ResourceBudget, validation};
use crate::source::{ByteSpan, SourceBytes};
use crate::tags::iter_tag_matches;

/// Wire schema version for [`RenderDocumentV1`].
pub const RENDER_DOCUMENT_SCHEMA_V1: u32 = 1;

/// UI-neutral render IR for one Markdown source (memo, draft, history, share card, etc.).
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RenderDocumentV1 {
    schema_version: u32,
    blocks: Vec<RenderBlock>,
    plain_text: String,
    node_count: u32,
    tag_names: Vec<String>,
    attachment_destinations: Vec<String>,
    semantic_facts: Vec<SemanticFact>,
}

impl RenderDocumentV1 {
    /// Rejects an unknown render schema at the boundary.
    ///
    /// # Errors
    ///
    /// Returns a validation error when `schema` is not [`RENDER_DOCUMENT_SCHEMA_V1`].
    pub fn reject_unknown_schema(schema: u32) -> Result<(), LomoError> {
        if schema != RENDER_DOCUMENT_SCHEMA_V1 {
            return Err(validation(
                "unknown_render_schema",
                "render schema must be RenderDocumentV1",
            ));
        }
        Ok(())
    }

    #[must_use]
    pub const fn schema_version(&self) -> u32 {
        self.schema_version
    }

    #[must_use]
    pub fn blocks(&self) -> &[RenderBlock] {
        &self.blocks
    }

    #[must_use]
    pub fn plain_text(&self) -> &str {
        &self.plain_text
    }

    #[must_use]
    pub const fn node_count(&self) -> u32 {
        self.node_count
    }

    #[must_use]
    pub fn tag_names(&self) -> &[String] {
        &self.tag_names
    }

    #[must_use]
    pub fn attachment_destinations(&self) -> &[String] {
        &self.attachment_destinations
    }

    /// Source-addressed semantic facts projected by the same parse as the render tree.
    #[must_use]
    pub fn semantic_facts(&self) -> &[SemanticFact] {
        &self.semantic_facts
    }
}

/// Semantic fact kinds shared by storage analysis, reminders, patching, and UI IR.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum SemanticFactKind {
    Tag,
    Link,
    Attachment,
    WikiReference,
    Reminder,
    TaskItem,
}

/// One semantic fact tied to the exact source bytes that produced it.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct SemanticFact {
    kind: SemanticFactKind,
    value: String,
    source_span: ByteSpan,
}

impl SemanticFact {
    #[must_use]
    pub const fn kind(&self) -> SemanticFactKind {
        self.kind
    }

    #[must_use]
    pub fn value(&self) -> &str {
        &self.value
    }

    #[must_use]
    pub const fn source_span(&self) -> ByteSpan {
        self.source_span
    }

    /// Verifies that a source slice is consistent with this typed fact.
    #[must_use]
    pub fn matches_source_slice(&self, source_slice: &str) -> bool {
        match self.kind {
            SemanticFactKind::Tag => source_slice == format!("#{}", self.value),
            SemanticFactKind::Reminder => source_slice == self.value,
            SemanticFactKind::Link
            | SemanticFactKind::Attachment
            | SemanticFactKind::WikiReference
            | SemanticFactKind::TaskItem => source_slice.contains(&self.value),
        }
    }
}

/// Block-level render node.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum RenderBlock {
    Paragraph {
        source_span: ByteSpan,
        inlines: Vec<RenderInline>,
    },
    Heading {
        source_span: ByteSpan,
        level: u8,
        inlines: Vec<RenderInline>,
    },
    BlockQuote {
        source_span: ByteSpan,
        blocks: Vec<Self>,
    },
    List {
        source_span: ByteSpan,
        ordered: bool,
        start: u64,
        items: Vec<RenderListItem>,
    },
    CodeBlock {
        source_span: ByteSpan,
        language: Option<String>,
        literal: String,
    },
    ThematicBreak {
        source_span: ByteSpan,
    },
    Table {
        source_span: ByteSpan,
        header: Vec<RenderTableCell>,
        rows: Vec<Vec<RenderTableCell>>,
    },
    HtmlBlock {
        source_span: ByteSpan,
        literal: String,
    },
}

/// One list item, optionally a task.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RenderListItem {
    pub source_span: ByteSpan,
    pub task_span: Option<ByteSpan>,
    pub checked: Option<bool>,
    pub blocks: Vec<RenderBlock>,
}

/// One table cell.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct RenderTableCell {
    pub source_span: ByteSpan,
    pub inlines: Vec<RenderInline>,
}

/// Inline-level render node.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum RenderInline {
    Text {
        source_span: ByteSpan,
        text: String,
    },
    Strong {
        source_span: ByteSpan,
        children: Vec<Self>,
    },
    Emphasis {
        source_span: ByteSpan,
        children: Vec<Self>,
    },
    Strikethrough {
        source_span: ByteSpan,
        children: Vec<Self>,
    },
    Highlight {
        source_span: ByteSpan,
        children: Vec<Self>,
    },
    Code {
        source_span: ByteSpan,
        text: String,
    },
    Link {
        source_span: ByteSpan,
        destination: String,
        title: Option<String>,
        children: Vec<Self>,
    },
    Image {
        source_span: ByteSpan,
        destination: String,
        title: Option<String>,
        alt: String,
    },
    Tag {
        source_span: ByteSpan,
        name: String,
    },
    Reminder {
        source_span: ByteSpan,
        token: String,
    },
    WikiReference {
        source_span: ByteSpan,
        target: String,
        children: Vec<Self>,
    },
    SoftBreak {
        source_span: ByteSpan,
    },
    HardBreak {
        source_span: ByteSpan,
    },
    HtmlInline {
        source_span: ByteSpan,
        text: String,
    },
}

/// Projects one [`RenderDocumentV1`] from constrained source bytes.
///
/// This is the single Markdown semantic pipeline for inline/non-workspace text and for workspace
/// document IR ownership. Workspace parse stores the resulting IR; it does not re-tokenize the body.
///
/// # Errors
///
/// Returns `resource_limit` when inline size, nesting, node count, or IR string budgets are exceeded.
pub fn render_markdown(source: &SourceBytes) -> Result<RenderDocumentV1, LomoError> {
    ResourceBudget::check_inline_render_bytes(source.len())?;
    render_markdown_core(source)
}

/// Shared IR projection without the inline-request size gate.
///
/// Workspace documents may exceed the 1 MiB inline render budget; they still share this node-fact
/// pipeline and the nesting/node/IR-string limits.
pub fn render_markdown_core(source: &SourceBytes) -> Result<RenderDocumentV1, LomoError> {
    let mut builder = RenderBuilder::new(source.len());
    for (event, range) in offset_events(source.as_str()) {
        builder.feed(event, range)?;
    }
    builder.finish()
}

/// Shared pulldown offset-event iterator configuration (tables, task lists, strikethrough).
fn offset_events(text: &str) -> impl Iterator<Item = (Event<'_>, Range<usize>)> {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    options.insert(Options::ENABLE_TASKLISTS);
    options.insert(Options::ENABLE_STRIKETHROUGH);
    Parser::new_ext(text, options).into_offset_iter()
}

struct RenderBuilder {
    stack: Vec<Frame>,
    root_blocks: Vec<RenderBlock>,
    node_count: u32,
    max_depth: u32,
    source_len: usize,
}

enum Frame {
    BlockQuote {
        source_span: ByteSpan,
        blocks: Vec<RenderBlock>,
        depth: u32,
    },
    List {
        source_span: ByteSpan,
        ordered: bool,
        start: u64,
        items: Vec<RenderListItem>,
        depth: u32,
    },
    Item {
        source_span: ByteSpan,
        task_span: Option<ByteSpan>,
        checked: Option<bool>,
        blocks: Vec<RenderBlock>,
        depth: u32,
    },
    Paragraph {
        source_span: ByteSpan,
        inlines: Vec<RenderInline>,
        depth: u32,
    },
    Heading {
        source_span: ByteSpan,
        level: u8,
        inlines: Vec<RenderInline>,
        depth: u32,
    },
    Emphasis {
        source_span: ByteSpan,
        children: Vec<RenderInline>,
    },
    Strong {
        source_span: ByteSpan,
        children: Vec<RenderInline>,
    },
    Strikethrough {
        source_span: ByteSpan,
        children: Vec<RenderInline>,
    },
    Link {
        source_span: ByteSpan,
        destination: String,
        title: Option<String>,
        children: Vec<RenderInline>,
    },
    Image {
        source_span: ByteSpan,
        destination: String,
        title: Option<String>,
        alt_parts: Vec<String>,
    },
    Table {
        source_span: ByteSpan,
        header: Vec<RenderTableCell>,
        rows: Vec<Vec<RenderTableCell>>,
        in_head: bool,
        current_row: Vec<RenderTableCell>,
        depth: u32,
    },
    TableCell {
        source_span: ByteSpan,
        inlines: Vec<RenderInline>,
    },
    CodeBlock {
        source_span: ByteSpan,
        language: Option<String>,
        literal: String,
        depth: u32,
    },
    HtmlBlock {
        source_span: ByteSpan,
        literal: String,
        depth: u32,
    },
}

impl RenderBuilder {
    const fn new(source_len: usize) -> Self {
        Self {
            stack: Vec::new(),
            root_blocks: Vec::new(),
            node_count: 0,
            max_depth: 0,
            source_len,
        }
    }

    fn feed(&mut self, event: Event<'_>, range: Range<usize>) -> Result<(), LomoError> {
        let source_span = self.checked_span(range)?;
        match event {
            Event::Start(tag) => self.start_tag(tag, source_span),
            Event::End(tag_end) => self.end_tag(tag_end, source_span),
            Event::Text(text) => self.push_text(&text, source_span),
            Event::Code(text) => {
                self.count_node()?;
                check_ir_string(text.as_ref())?;
                self.push_inline(RenderInline::Code {
                    source_span,
                    text: text.into_string(),
                })
            }
            Event::Html(html) | Event::InlineHtml(html) => {
                if let Some(Frame::HtmlBlock { literal, .. }) = self.stack.last_mut() {
                    literal.push_str(html.as_ref());
                    return Ok(());
                }
                if self.in_inline_context() {
                    self.count_node()?;
                    check_ir_string(html.as_ref())?;
                    self.push_inline(RenderInline::HtmlInline {
                        source_span,
                        text: html.into_string(),
                    })
                } else {
                    self.count_node()?;
                    check_ir_string(html.as_ref())?;
                    self.push_block(RenderBlock::HtmlBlock {
                        source_span,
                        literal: html.into_string(),
                    });
                    Ok(())
                }
            }
            Event::SoftBreak => {
                self.count_node()?;
                self.push_inline(RenderInline::SoftBreak { source_span })
            }
            Event::HardBreak => {
                self.count_node()?;
                self.push_inline(RenderInline::HardBreak { source_span })
            }
            Event::Rule => {
                self.count_node()?;
                self.push_block(RenderBlock::ThematicBreak { source_span });
                Ok(())
            }
            Event::TaskListMarker(checked) => {
                if let Some(Frame::Item {
                    checked: slot,
                    task_span,
                    ..
                }) = self.stack.last_mut()
                {
                    *slot = Some(checked);
                    *task_span = Some(source_span);
                }
                Ok(())
            }
            Event::FootnoteReference(_) | Event::InlineMath(_) | Event::DisplayMath(_) => Ok(()),
        }
    }

    #[expect(
        clippy::too_many_lines,
        reason = "pulldown Tag dispatch is intentionally one match over the event enum"
    )]
    fn start_tag(&mut self, tag: Tag<'_>, source_span: ByteSpan) -> Result<(), LomoError> {
        let depth = self.current_block_depth().saturating_add(1);
        self.note_depth(depth)?;
        match tag {
            Tag::Paragraph => {
                self.stack.push(Frame::Paragraph {
                    source_span,
                    inlines: Vec::new(),
                    depth,
                });
            }
            Tag::Heading { level, .. } => {
                self.stack.push(Frame::Heading {
                    source_span,
                    level: level as u8,
                    inlines: Vec::new(),
                    depth,
                });
            }
            Tag::BlockQuote(_) => {
                self.stack.push(Frame::BlockQuote {
                    source_span,
                    blocks: Vec::new(),
                    depth,
                });
            }
            Tag::List(start) => {
                self.stack.push(Frame::List {
                    source_span,
                    ordered: start.is_some(),
                    start: start.unwrap_or(1),
                    items: Vec::new(),
                    depth,
                });
            }
            Tag::Item => {
                self.stack.push(Frame::Item {
                    source_span,
                    task_span: None,
                    checked: None,
                    blocks: Vec::new(),
                    depth,
                });
            }
            Tag::CodeBlock(kind) => {
                let language = match kind {
                    CodeBlockKind::Indented => None,
                    CodeBlockKind::Fenced(lang) => {
                        let text = lang.into_string();
                        if text.is_empty() { None } else { Some(text) }
                    }
                };
                self.stack.push(Frame::CodeBlock {
                    source_span,
                    language,
                    literal: String::new(),
                    depth,
                });
            }
            Tag::Table(_) => {
                self.stack.push(Frame::Table {
                    source_span,
                    header: Vec::new(),
                    rows: Vec::new(),
                    in_head: false,
                    current_row: Vec::new(),
                    depth,
                });
            }
            Tag::TableHead => {
                if let Some(Frame::Table { in_head, .. }) = self.stack.last_mut() {
                    *in_head = true;
                }
            }
            Tag::TableRow => {
                if let Some(Frame::Table { current_row, .. }) = self.stack.last_mut() {
                    current_row.clear();
                }
            }
            Tag::TableCell => {
                self.stack.push(Frame::TableCell {
                    source_span,
                    inlines: Vec::new(),
                });
            }
            Tag::Emphasis => {
                self.stack.push(Frame::Emphasis {
                    source_span,
                    children: Vec::new(),
                });
            }
            Tag::Strong => {
                self.stack.push(Frame::Strong {
                    source_span,
                    children: Vec::new(),
                });
            }
            Tag::Strikethrough => {
                self.stack.push(Frame::Strikethrough {
                    source_span,
                    children: Vec::new(),
                });
            }
            Tag::Link {
                dest_url, title, ..
            } => {
                self.stack.push(Frame::Link {
                    source_span,
                    destination: dest_url.into_string(),
                    title: title_opt(title),
                    children: Vec::new(),
                });
            }
            Tag::Image {
                dest_url, title, ..
            } => {
                self.stack.push(Frame::Image {
                    source_span,
                    destination: dest_url.into_string(),
                    title: title_opt(title),
                    alt_parts: Vec::new(),
                });
            }
            Tag::HtmlBlock => {
                self.stack.push(Frame::HtmlBlock {
                    source_span,
                    literal: String::new(),
                    depth,
                });
            }
            Tag::FootnoteDefinition(_)
            | Tag::DefinitionList
            | Tag::DefinitionListTitle
            | Tag::DefinitionListDefinition
            | Tag::MetadataBlock(_)
            | Tag::Superscript
            | Tag::Subscript => {}
        }
        Ok(())
    }

    #[expect(
        clippy::too_many_lines,
        clippy::cognitive_complexity,
        reason = "pulldown TagEnd dispatch is intentionally one match over the event enum"
    )]
    fn end_tag(&mut self, tag_end: TagEnd, end_span: ByteSpan) -> Result<(), LomoError> {
        match tag_end {
            TagEnd::Paragraph => {
                let Some(Frame::Paragraph {
                    source_span,
                    inlines,
                    ..
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                let inlines = classify_extensions(inlines, self.source_len)?;
                self.push_block(RenderBlock::Paragraph {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    inlines,
                });
                Ok(())
            }
            TagEnd::Heading(_level) => {
                let Some(Frame::Heading {
                    level: stacked_level,
                    source_span,
                    inlines,
                    ..
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                let inlines = classify_extensions(inlines, self.source_len)?;
                self.push_block(RenderBlock::Heading {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    level: stacked_level,
                    inlines,
                });
                Ok(())
            }
            TagEnd::BlockQuote(_) => {
                let Some(Frame::BlockQuote {
                    source_span,
                    blocks,
                    ..
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                self.push_block(RenderBlock::BlockQuote {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    blocks,
                });
                Ok(())
            }
            TagEnd::List(_) => {
                let Some(Frame::List {
                    ordered,
                    start,
                    items,
                    source_span,
                    ..
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                self.push_block(RenderBlock::List {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    ordered,
                    start,
                    items,
                });
                Ok(())
            }
            TagEnd::Item => {
                let Some(Frame::Item {
                    checked,
                    blocks,
                    source_span,
                    task_span,
                    ..
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                // Tight list items may accumulate bare inlines into synthetic paragraphs without
                // going through Paragraph end — classify only those deferred paragraphs.
                // Idempotent on already-classified loose-list paragraphs; required for tight-list
                // synthetic paragraphs that never saw TagEnd::Paragraph.
                let blocks = classify_blocks_extensions(blocks, self.source_len)?;
                self.count_node()?;
                if let Some(Frame::List { items, .. }) = self.stack.last_mut() {
                    items.push(RenderListItem {
                        source_span: merge_spans(source_span, end_span, self.source_len)?,
                        task_span,
                        checked,
                        blocks,
                    });
                }
                Ok(())
            }
            TagEnd::CodeBlock => match self.stack.pop() {
                Some(Frame::CodeBlock {
                    source_span,
                    language,
                    literal,
                    ..
                }) => {
                    self.count_node()?;
                    check_ir_string(&literal)?;
                    self.push_block(RenderBlock::CodeBlock {
                        source_span: merge_spans(source_span, end_span, self.source_len)?,
                        language,
                        literal,
                    });
                    Ok(())
                }
                _ => Ok(()),
            },
            TagEnd::Table => {
                let Some(Frame::Table {
                    source_span,
                    header,
                    rows,
                    ..
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                self.push_block(RenderBlock::Table {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    header,
                    rows,
                });
                Ok(())
            }
            TagEnd::TableHead => {
                if let Some(Frame::Table {
                    header,
                    in_head,
                    current_row,
                    ..
                }) = self.stack.last_mut()
                {
                    if header.is_empty() {
                        *header = std::mem::take(current_row);
                    }
                    *in_head = false;
                }
                Ok(())
            }
            TagEnd::TableRow => {
                if let Some(Frame::Table {
                    header,
                    rows,
                    in_head,
                    current_row,
                    ..
                }) = self.stack.last_mut()
                {
                    let row = std::mem::take(current_row);
                    if *in_head && header.is_empty() {
                        *header = row;
                    } else if !*in_head {
                        rows.push(row);
                    }
                }
                Ok(())
            }
            TagEnd::TableCell => {
                let Some(Frame::TableCell {
                    source_span,
                    inlines,
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                let inlines = classify_extensions(inlines, self.source_len)?;
                if let Some(Frame::Table { current_row, .. }) = self.stack.last_mut() {
                    current_row.push(RenderTableCell {
                        source_span: merge_spans(source_span, end_span, self.source_len)?,
                        inlines,
                    });
                }
                Ok(())
            }
            TagEnd::Emphasis => {
                let Some(Frame::Emphasis {
                    source_span,
                    children,
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                self.push_inline(RenderInline::Emphasis {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    children,
                })
            }
            TagEnd::Strong => {
                let Some(Frame::Strong {
                    source_span,
                    children,
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                self.push_inline(RenderInline::Strong {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    children,
                })
            }
            TagEnd::Strikethrough => {
                let Some(Frame::Strikethrough {
                    source_span,
                    children,
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                self.push_inline(RenderInline::Strikethrough {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    children,
                })
            }
            TagEnd::Link => {
                let Some(Frame::Link {
                    source_span,
                    destination,
                    title,
                    children,
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                check_ir_string(&destination)?;
                self.push_inline(RenderInline::Link {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    destination,
                    title,
                    children,
                })
            }
            TagEnd::Image => {
                let Some(Frame::Image {
                    source_span,
                    destination,
                    title,
                    alt_parts,
                }) = self.stack.pop()
                else {
                    return Ok(());
                };
                self.count_node()?;
                check_ir_string(&destination)?;
                let alt = alt_parts.join("");
                check_ir_string(&alt)?;
                self.push_inline(RenderInline::Image {
                    source_span: merge_spans(source_span, end_span, self.source_len)?,
                    destination,
                    title,
                    alt,
                })
            }
            TagEnd::HtmlBlock => {
                if let Some(Frame::HtmlBlock {
                    source_span,
                    literal,
                    ..
                }) = self.stack.pop()
                {
                    self.count_node()?;
                    check_ir_string(&literal)?;
                    self.push_block(RenderBlock::HtmlBlock {
                        source_span: merge_spans(source_span, end_span, self.source_len)?,
                        literal,
                    });
                }
                Ok(())
            }
            TagEnd::FootnoteDefinition
            | TagEnd::DefinitionList
            | TagEnd::DefinitionListTitle
            | TagEnd::DefinitionListDefinition
            | TagEnd::MetadataBlock(_)
            | TagEnd::Superscript
            | TagEnd::Subscript => Ok(()),
        }
    }

    fn push_text(&mut self, text: &CowStr<'_>, source_span: ByteSpan) -> Result<(), LomoError> {
        check_ir_string(text.as_ref())?;
        if let Some(Frame::CodeBlock { literal, .. } | Frame::HtmlBlock { literal, .. }) =
            self.stack.last_mut()
        {
            literal.push_str(text);
            return Ok(());
        }
        if let Some(Frame::Image { alt_parts, .. }) = self.stack.last_mut() {
            alt_parts.push(text.to_string());
            return Ok(());
        }
        self.count_node()?;
        self.push_inline(RenderInline::Text {
            source_span,
            text: text.to_string(),
        })
    }

    fn push_inline(&mut self, inline: RenderInline) -> Result<(), LomoError> {
        enum Target {
            Inlines,
            Children,
            ImageAlt,
            ItemWrap,
            RootWrap,
        }
        let target = match self.stack.last() {
            Some(Frame::Paragraph { .. } | Frame::Heading { .. } | Frame::TableCell { .. }) => {
                Target::Inlines
            }
            Some(
                Frame::Emphasis { .. }
                | Frame::Strong { .. }
                | Frame::Strikethrough { .. }
                | Frame::Link { .. },
            ) => Target::Children,
            Some(Frame::Image { .. }) => Target::ImageAlt,
            Some(Frame::Item { .. }) => Target::ItemWrap,
            _ => Target::RootWrap,
        };
        match target {
            Target::Inlines => {
                if let Some(
                    Frame::Paragraph { inlines, .. }
                    | Frame::Heading { inlines, .. }
                    | Frame::TableCell { inlines, .. },
                ) = self.stack.last_mut()
                {
                    inlines.push(inline);
                }
                Ok(())
            }
            Target::Children => {
                if let Some(
                    Frame::Emphasis { children, .. }
                    | Frame::Strong { children, .. }
                    | Frame::Strikethrough { children, .. }
                    | Frame::Link { children, .. },
                ) = self.stack.last_mut()
                {
                    children.push(inline);
                }
                Ok(())
            }
            Target::ImageAlt => {
                if let (Some(Frame::Image { alt_parts, .. }), RenderInline::Text { text, .. }) =
                    (self.stack.last_mut(), inline)
                {
                    alt_parts.push(text);
                }
                Ok(())
            }
            Target::ItemWrap => {
                // Tight list items may emit bare inlines; accumulate into one paragraph so
                // SoftBreak stays inside the item plain-text stream (not extra block joins).
                if let Some(Frame::Item { blocks, .. }) = self.stack.last_mut()
                    && let Some(RenderBlock::Paragraph { inlines, .. }) = blocks.last_mut()
                {
                    inlines.push(inline);
                    return Ok(());
                }
                self.count_node()?;
                if let Some(Frame::Item { blocks, .. }) = self.stack.last_mut() {
                    blocks.push(RenderBlock::Paragraph {
                        source_span: inline_source_span(&inline),
                        inlines: vec![inline],
                    });
                }
                Ok(())
            }
            Target::RootWrap => {
                self.count_node()?;
                self.root_blocks.push(RenderBlock::Paragraph {
                    source_span: inline_source_span(&inline),
                    inlines: vec![inline],
                });
                Ok(())
            }
        }
    }

    fn push_block(&mut self, block: RenderBlock) {
        match self.stack.last_mut() {
            Some(Frame::BlockQuote { blocks, .. } | Frame::Item { blocks, .. }) => {
                blocks.push(block);
            }
            _ => {
                self.root_blocks.push(block);
            }
        }
    }

    fn in_inline_context(&self) -> bool {
        self.stack.iter().rev().any(|frame| {
            matches!(
                frame,
                Frame::Paragraph { .. }
                    | Frame::Heading { .. }
                    | Frame::Emphasis { .. }
                    | Frame::Strong { .. }
                    | Frame::Strikethrough { .. }
                    | Frame::Link { .. }
                    | Frame::Image { .. }
                    | Frame::TableCell { .. }
            )
        })
    }

    fn current_block_depth(&self) -> u32 {
        self.stack
            .iter()
            .rev()
            .find_map(|frame| match frame {
                Frame::BlockQuote { depth, .. }
                | Frame::List { depth, .. }
                | Frame::Item { depth, .. }
                | Frame::Paragraph { depth, .. }
                | Frame::Heading { depth, .. }
                | Frame::Table { depth, .. }
                | Frame::CodeBlock { depth, .. }
                | Frame::HtmlBlock { depth, .. } => Some(*depth),
                Frame::Emphasis { .. }
                | Frame::Strong { .. }
                | Frame::Strikethrough { .. }
                | Frame::Link { .. }
                | Frame::Image { .. }
                | Frame::TableCell { .. } => None,
            })
            .unwrap_or(0)
    }

    fn note_depth(&mut self, depth: u32) -> Result<(), LomoError> {
        if depth > self.max_depth {
            self.max_depth = depth;
        }
        ResourceBudget::check_semantic_nesting_depth(depth)
    }

    fn count_node(&mut self) -> Result<(), LomoError> {
        self.node_count = self.node_count.saturating_add(1);
        ResourceBudget::check_render_document_nodes(self.node_count)
    }

    fn checked_span(&self, range: Range<usize>) -> Result<ByteSpan, LomoError> {
        ByteSpan::try_new(range.start, range.end, self.source_len)
    }

    fn finish(self) -> Result<RenderDocumentV1, LomoError> {
        ResourceBudget::check_semantic_nesting_depth(self.max_depth)?;
        let final_node_count = validate_final_ir(&self.root_blocks, self.source_len)?;
        let plain_text = blocks_plain_text(&self.root_blocks);
        check_ir_string(&plain_text)?;

        let mut tag_names = Vec::new();
        let mut attachment_destinations = Vec::new();
        let mut seen_tags = BTreeSet::new();
        let mut seen_attachments = BTreeSet::new();
        let mut semantic_facts = Vec::new();
        collect_from_blocks(
            &self.root_blocks,
            &mut tag_names,
            &mut seen_tags,
            &mut attachment_destinations,
            &mut seen_attachments,
            &mut semantic_facts,
        );

        Ok(RenderDocumentV1 {
            schema_version: RENDER_DOCUMENT_SCHEMA_V1,
            blocks: self.root_blocks,
            plain_text,
            node_count: final_node_count,
            tag_names,
            attachment_destinations,
            semantic_facts,
        })
    }
}

fn title_opt(title: CowStr<'_>) -> Option<String> {
    let text = title.into_string();
    if text.is_empty() { None } else { Some(text) }
}

fn check_ir_string(text: &str) -> Result<(), LomoError> {
    ResourceBudget::check_ir_string_bytes(text.len())
}

fn merge_spans(
    first: ByteSpan,
    second: ByteSpan,
    source_len: usize,
) -> Result<ByteSpan, LomoError> {
    ByteSpan::try_new(
        first.start().min(second.start()),
        first.end().max(second.end()),
        source_len,
    )
}

fn subspan(
    parent: ByteSpan,
    local_start: usize,
    local_end: usize,
    source_len: usize,
) -> Result<ByteSpan, LomoError> {
    if local_end < local_start || local_end > parent.len() {
        return Err(validation(
            "semantic_span_not_source_addressable",
            "semantic extension span is outside its pulldown source event",
        ));
    }
    ByteSpan::try_new(
        parent.start() + local_start,
        parent.start() + local_end,
        source_len,
    )
}

const fn inline_source_span(inline: &RenderInline) -> ByteSpan {
    match inline {
        RenderInline::Text { source_span, .. }
        | RenderInline::Strong { source_span, .. }
        | RenderInline::Emphasis { source_span, .. }
        | RenderInline::Strikethrough { source_span, .. }
        | RenderInline::Highlight { source_span, .. }
        | RenderInline::Code { source_span, .. }
        | RenderInline::Link { source_span, .. }
        | RenderInline::Image { source_span, .. }
        | RenderInline::Tag { source_span, .. }
        | RenderInline::Reminder { source_span, .. }
        | RenderInline::WikiReference { source_span, .. }
        | RenderInline::SoftBreak { source_span }
        | RenderInline::HardBreak { source_span }
        | RenderInline::HtmlInline { source_span, .. } => *source_span,
    }
}

fn classify_extensions(
    inlines: Vec<RenderInline>,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    let flattened = flatten_text_nodes(inlines);
    let merged = merge_adjacent_text(flattened, source_len)?;
    let with_wiki = classify_wiki(merged, source_len)?;
    let with_highlight = classify_highlight(with_wiki, source_len)?;
    let with_tags = classify_tags(with_highlight, source_len)?;
    classify_reminders(with_tags, source_len)
}

fn classify_blocks_extensions(
    blocks: Vec<RenderBlock>,
    source_len: usize,
) -> Result<Vec<RenderBlock>, LomoError> {
    let mut out = Vec::with_capacity(blocks.len());
    for block in blocks {
        match block {
            RenderBlock::Paragraph {
                source_span,
                inlines,
            } => out.push(RenderBlock::Paragraph {
                source_span,
                inlines: classify_extensions(inlines, source_len)?,
            }),
            RenderBlock::Heading {
                source_span,
                level,
                inlines,
            } => out.push(RenderBlock::Heading {
                source_span,
                level,
                inlines: classify_extensions(inlines, source_len)?,
            }),
            RenderBlock::BlockQuote {
                source_span,
                blocks,
            } => out.push(RenderBlock::BlockQuote {
                source_span,
                blocks: classify_blocks_extensions(blocks, source_len)?,
            }),
            RenderBlock::List {
                ordered,
                start,
                items,
                source_span,
            } => {
                let mut classified_items = Vec::with_capacity(items.len());
                for item in items {
                    classified_items.push(RenderListItem {
                        source_span: item.source_span,
                        task_span: item.task_span,
                        checked: item.checked,
                        blocks: classify_blocks_extensions(item.blocks, source_len)?,
                    });
                }
                out.push(RenderBlock::List {
                    source_span,
                    ordered,
                    start,
                    items: classified_items,
                });
            }
            RenderBlock::Table {
                source_span,
                header,
                rows,
            } => {
                let header = header
                    .into_iter()
                    .map(|cell| {
                        Ok(RenderTableCell {
                            source_span: cell.source_span,
                            inlines: classify_extensions(cell.inlines, source_len)?,
                        })
                    })
                    .collect::<Result<Vec<_>, LomoError>>()?;
                let mut classified_rows = Vec::with_capacity(rows.len());
                for row in rows {
                    let cells = row
                        .into_iter()
                        .map(|cell| {
                            Ok(RenderTableCell {
                                source_span: cell.source_span,
                                inlines: classify_extensions(cell.inlines, source_len)?,
                            })
                        })
                        .collect::<Result<Vec<_>, LomoError>>()?;
                    classified_rows.push(cells);
                }
                out.push(RenderBlock::Table {
                    source_span,
                    header,
                    rows: classified_rows,
                });
            }
            RenderBlock::CodeBlock { .. }
            | RenderBlock::ThematicBreak { .. }
            | RenderBlock::HtmlBlock { .. } => out.push(block),
        }
    }
    Ok(out)
}

fn merge_adjacent_text(
    inlines: Vec<RenderInline>,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    let mut out = Vec::new();
    for inline in inlines {
        match inline {
            RenderInline::Text { source_span, text } => {
                if let Some(RenderInline::Text {
                    source_span: previous_span,
                    text: prev,
                }) = out.last_mut()
                    && previous_span.end() == source_span.start()
                {
                    prev.push_str(&text);
                    *previous_span = merge_spans(*previous_span, source_span, source_len)?;
                } else {
                    out.push(RenderInline::Text { source_span, text });
                }
            }
            RenderInline::Strong {
                source_span,
                children,
            } => out.push(RenderInline::Strong {
                source_span,
                children: merge_adjacent_text(children, source_len)?,
            }),
            RenderInline::Emphasis {
                source_span,
                children,
            } => out.push(RenderInline::Emphasis {
                source_span,
                children: merge_adjacent_text(children, source_len)?,
            }),
            RenderInline::Strikethrough {
                source_span,
                children,
            } => out.push(RenderInline::Strikethrough {
                source_span,
                children: merge_adjacent_text(children, source_len)?,
            }),
            RenderInline::Highlight {
                source_span,
                children,
            } => out.push(RenderInline::Highlight {
                source_span,
                children: merge_adjacent_text(children, source_len)?,
            }),
            RenderInline::Link {
                destination,
                title,
                children,
                source_span,
            } => out.push(RenderInline::Link {
                source_span,
                destination,
                title,
                children: merge_adjacent_text(children, source_len)?,
            }),
            RenderInline::WikiReference {
                source_span,
                target,
                children,
            } => {
                out.push(RenderInline::WikiReference {
                    source_span,
                    target,
                    children: merge_adjacent_text(children, source_len)?,
                });
            }
            RenderInline::Code { .. }
            | RenderInline::Image { .. }
            | RenderInline::Tag { .. }
            | RenderInline::Reminder { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. } => out.push(inline),
        }
    }
    Ok(out)
}

fn flatten_text_nodes(inlines: Vec<RenderInline>) -> Vec<RenderInline> {
    // Keep structure; only recurse into styled containers.
    inlines
        .into_iter()
        .map(|inline| match inline {
            RenderInline::Strong {
                source_span,
                children,
            } => RenderInline::Strong {
                source_span,
                children: flatten_text_nodes(children),
            },
            RenderInline::Emphasis {
                source_span,
                children,
            } => RenderInline::Emphasis {
                source_span,
                children: flatten_text_nodes(children),
            },
            RenderInline::Strikethrough {
                source_span,
                children,
            } => RenderInline::Strikethrough {
                source_span,
                children: flatten_text_nodes(children),
            },
            RenderInline::Highlight {
                source_span,
                children,
            } => RenderInline::Highlight {
                source_span,
                children: flatten_text_nodes(children),
            },
            RenderInline::Link {
                destination,
                title,
                children,
                source_span,
            } => RenderInline::Link {
                source_span,
                destination,
                title,
                children: flatten_text_nodes(children),
            },
            RenderInline::WikiReference {
                source_span,
                target,
                children,
            } => RenderInline::WikiReference {
                source_span,
                target,
                children: flatten_text_nodes(children),
            },
            RenderInline::Text { .. }
            | RenderInline::Code { .. }
            | RenderInline::Image { .. }
            | RenderInline::Tag { .. }
            | RenderInline::Reminder { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. } => inline,
        })
        .collect()
}

fn classify_wiki(
    inlines: Vec<RenderInline>,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    // Handle wiki images `![[target|alt]]` before plain wiki `[[target]]` so the bang is not left
    // as text while the brackets become a reference.
    map_text_segments(inlines, |text, source_span| {
        classify_wiki_text(text, source_span, source_len)
    })
}

#[expect(
    clippy::too_many_lines,
    reason = "wiki image and plain wiki openers share one sequential scanner"
)]
fn classify_wiki_text(
    text: &str,
    source_span: ByteSpan,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    let (mut out, mut rest, mut base) = (Vec::new(), text, 0usize);
    while !rest.is_empty() {
        let image_at = rest.find("![[");
        let wiki_at = rest.find("[[");
        let Some(choose_image) = choose_wiki_marker(image_at, wiki_at) else {
            push_wiki_tail(text, rest, base, source_span, source_len, &mut out)?;
            break;
        };
        if choose_image {
            let Some(open) = image_at else {
                return Err(wiki_classifier_state_error());
            };
            push_wiki_prefix(rest, base, open, source_span, source_len, &mut out)?;
            let after = rest
                .get(open + 3..)
                .ok_or_else(wiki_classifier_state_error)?;
            if let Some(close) = after.find("]]") {
                let inner = after.get(..close).ok_or_else(wiki_classifier_state_error)?;
                let target = inner.split('|').next().unwrap_or(inner).trim();
                let alt = inner
                    .split_once('|')
                    .map_or("", |(_, alt)| alt.trim())
                    .to_owned();
                if target.is_empty() {
                    let consumed = open + 3 + close + 2;
                    out.push(RenderInline::Text {
                        source_span: subspan(
                            source_span,
                            base + open,
                            base + consumed,
                            source_len,
                        )?,
                        text: rest
                            .get(open..consumed)
                            .ok_or_else(wiki_classifier_state_error)?
                            .to_owned(),
                    });
                } else {
                    check_ir_string(target)?;
                    check_ir_string(&alt)?;
                    out.push(RenderInline::Image {
                        source_span: subspan(
                            source_span,
                            base + open,
                            base + open + 3 + close + 2,
                            source_len,
                        )?,
                        destination: target.to_owned(),
                        title: None,
                        alt,
                    });
                }
                base += open + 3 + close + 2;
                rest = after
                    .get(close + 2..)
                    .ok_or_else(wiki_classifier_state_error)?;
            } else {
                push_wiki_unclosed(text, rest, base, open, source_span, source_len, &mut out)?;
                break;
            }
        } else {
            let Some(open) = wiki_at else {
                return Err(wiki_classifier_state_error());
            };
            push_wiki_prefix(rest, base, open, source_span, source_len, &mut out)?;
            let after = rest
                .get(open + 2..)
                .ok_or_else(wiki_classifier_state_error)?;
            if let Some(close) = after.find("]]") {
                let inner = after.get(..close).ok_or_else(wiki_classifier_state_error)?;
                let target = inner.split('|').next().unwrap_or(inner).trim();
                if target.is_empty() {
                    let consumed = open + 2 + close + 2;
                    out.push(RenderInline::Text {
                        source_span: subspan(
                            source_span,
                            base + open,
                            base + consumed,
                            source_len,
                        )?,
                        text: rest
                            .get(open..consumed)
                            .ok_or_else(wiki_classifier_state_error)?
                            .to_owned(),
                    });
                } else {
                    check_ir_string(target)?;
                    let fact_span = subspan(
                        source_span,
                        base + open,
                        base + open + 2 + close + 2,
                        source_len,
                    )?;
                    out.push(RenderInline::WikiReference {
                        source_span: fact_span,
                        target: target.to_owned(),
                        children: vec![RenderInline::Text {
                            source_span: fact_span,
                            text: target.to_owned(),
                        }],
                    });
                }
                base += open + 2 + close + 2;
                rest = after
                    .get(close + 2..)
                    .ok_or_else(wiki_classifier_state_error)?;
            } else {
                push_wiki_unclosed(text, rest, base, open, source_span, source_len, &mut out)?;
                break;
            }
        }
    }
    Ok(out)
}

const fn choose_wiki_marker(image_at: Option<usize>, wiki_at: Option<usize>) -> Option<bool> {
    match (image_at, wiki_at) {
        (Some(image), Some(wiki)) => Some(image <= wiki),
        (Some(_), None) => Some(true),
        (None, Some(_)) => Some(false),
        (None, None) => None,
    }
}

fn wiki_classifier_state_error() -> LomoError {
    validation(
        "wiki_classifier_state_invalid",
        "wiki classifier selected a missing source marker",
    )
}

fn push_wiki_prefix(
    rest: &str,
    base: usize,
    open: usize,
    source_span: ByteSpan,
    source_len: usize,
    out: &mut Vec<RenderInline>,
) -> Result<(), LomoError> {
    if open > 0 {
        out.push(RenderInline::Text {
            source_span: subspan(source_span, base, base + open, source_len)?,
            text: rest
                .get(..open)
                .ok_or_else(wiki_classifier_state_error)?
                .to_owned(),
        });
    }
    Ok(())
}

fn push_wiki_tail(
    text: &str,
    rest: &str,
    base: usize,
    source_span: ByteSpan,
    source_len: usize,
    out: &mut Vec<RenderInline>,
) -> Result<(), LomoError> {
    out.push(RenderInline::Text {
        source_span: subspan(source_span, base, text.len(), source_len)?,
        text: rest.to_owned(),
    });
    Ok(())
}

fn push_wiki_unclosed(
    text: &str,
    rest: &str,
    base: usize,
    open: usize,
    source_span: ByteSpan,
    source_len: usize,
    out: &mut Vec<RenderInline>,
) -> Result<(), LomoError> {
    out.push(RenderInline::Text {
        source_span: subspan(source_span, base + open, text.len(), source_len)?,
        text: rest
            .get(open..)
            .ok_or_else(wiki_classifier_state_error)?
            .to_owned(),
    });
    Ok(())
}

fn split_highlight_markers(
    text: &str,
    source_span: ByteSpan,
    source_len: usize,
    split: &mut Vec<RenderInline>,
) -> Result<(), LomoError> {
    let mut start = 0usize;
    let bytes = text.as_bytes();
    let mut idx = 0usize;
    while idx + 1 < bytes.len() {
        if bytes.get(idx) == Some(&b'=') && bytes.get(idx + 1) == Some(&b'=') {
            if idx > start {
                split.push(RenderInline::Text {
                    source_span: subspan(source_span, start, idx, source_len)?,
                    text: owned_str_slice(text, start, idx)?,
                });
            }
            split.push(RenderInline::Text {
                source_span: subspan(source_span, idx, idx + 2, source_len)?,
                text: "==".to_owned(),
            });
            idx += 2;
            start = idx;
        } else {
            idx += 1;
        }
    }
    if start < text.len() {
        split.push(RenderInline::Text {
            source_span: subspan(source_span, start, text.len(), source_len)?,
            text: owned_str_tail(text, start)?,
        });
    }
    Ok(())
}

fn owned_str_slice(text: &str, start: usize, end: usize) -> Result<String, LomoError> {
    text.get(start..end).map(str::to_owned).ok_or_else(|| {
        validation(
            "highlight_slice_invalid",
            "highlight marker split left a non-boundary range",
        )
    })
}

fn owned_str_tail(text: &str, start: usize) -> Result<String, LomoError> {
    text.get(start..).map(str::to_owned).ok_or_else(|| {
        validation(
            "highlight_slice_invalid",
            "highlight marker split left a non-boundary range",
        )
    })
}

fn classify_highlight(
    inlines: Vec<RenderInline>,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    // Split text on == then pair markers across the flat list (same algorithm as Kotlin).
    let mut split: Vec<RenderInline> = Vec::new();
    for inline in inlines {
        match inline {
            RenderInline::Text { source_span, text } if text.contains("==") => {
                split_highlight_markers(&text, source_span, source_len, &mut split)?;
            }
            RenderInline::Strong {
                source_span,
                children,
            } => split.push(RenderInline::Strong {
                source_span,
                children: classify_highlight(children, source_len)?,
            }),
            RenderInline::Emphasis {
                source_span,
                children,
            } => split.push(RenderInline::Emphasis {
                source_span,
                children: classify_highlight(children, source_len)?,
            }),
            RenderInline::Strikethrough {
                source_span,
                children,
            } => split.push(RenderInline::Strikethrough {
                source_span,
                children: classify_highlight(children, source_len)?,
            }),
            RenderInline::Link {
                destination,
                title,
                children,
                source_span,
            } => split.push(RenderInline::Link {
                source_span,
                destination,
                title,
                children: classify_highlight(children, source_len)?,
            }),
            RenderInline::WikiReference {
                source_span,
                target,
                children,
            } => {
                split.push(RenderInline::WikiReference {
                    source_span,
                    target,
                    children: classify_highlight(children, source_len)?,
                });
            }
            RenderInline::Text { .. }
            | RenderInline::Code { .. }
            | RenderInline::Image { .. }
            | RenderInline::Tag { .. }
            | RenderInline::Reminder { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. }
            | RenderInline::Highlight { .. } => split.push(inline),
        }
    }

    pair_highlight_markers(&split, source_len)
}

fn pair_highlight_markers(
    split: &[RenderInline],
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    let mut result = Vec::new();
    let mut i = 0usize;
    while i < split.len() {
        let Some(current) = split.get(i) else {
            break;
        };
        if matches!(current, RenderInline::Text { text, .. } if text == "==") {
            let mut match_idx = None;
            for (j, candidate) in split.iter().enumerate().skip(i + 1) {
                if matches!(candidate, RenderInline::Text { text, .. } if text == "==") {
                    match_idx = Some(j);
                    break;
                }
            }
            if let Some(j) = match_idx {
                let inner = split.get(i + 1..j).unwrap_or(&[]).to_vec();
                let left = split.get(i).ok_or_else(|| {
                    validation(
                        "highlight_pair_missing",
                        "highlight open marker disappeared during pairing",
                    )
                })?;
                let right = split.get(j).ok_or_else(|| {
                    validation(
                        "highlight_pair_missing",
                        "highlight close marker disappeared during pairing",
                    )
                })?;
                let source_span = merge_spans(
                    inline_source_span(left),
                    inline_source_span(right),
                    source_len,
                )?;
                result.push(RenderInline::Highlight {
                    source_span,
                    children: classify_highlight(inner, source_len)?,
                });
                i = j + 1;
            } else {
                result.push(current.clone());
                i += 1;
            }
        } else {
            result.push(current.clone());
            i += 1;
        }
    }
    Ok(result)
}

fn classify_tags(
    inlines: Vec<RenderInline>,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    // Shared tag scanner with storage analysis (`tags::iter_tag_matches`).
    map_text_segments(inlines, |text, source_span| {
        let mut out = Vec::new();
        let mut cursor = 0usize;
        for (name, start, end) in iter_tag_matches(text) {
            if start > cursor {
                out.push(RenderInline::Text {
                    source_span: subspan(source_span, cursor, start, source_len)?,
                    text: text
                        .get(cursor..start)
                        .ok_or_else(|| {
                            validation(
                                "tag_slice_invalid",
                                "tag scanner produced a non-boundary range",
                            )
                        })?
                        .to_owned(),
                });
            }
            check_ir_string(&name)?;
            out.push(RenderInline::Tag {
                source_span: subspan(source_span, start, end, source_len)?,
                name,
            });
            cursor = end;
        }
        if cursor < text.len() {
            out.push(RenderInline::Text {
                source_span: subspan(source_span, cursor, text.len(), source_len)?,
                text: text
                    .get(cursor..)
                    .ok_or_else(|| {
                        validation(
                            "tag_slice_invalid",
                            "tag scanner produced a non-boundary range",
                        )
                    })?
                    .to_owned(),
            });
        }
        Ok(out)
    })
}

fn classify_reminders(
    inlines: Vec<RenderInline>,
    source_len: usize,
) -> Result<Vec<RenderInline>, LomoError> {
    map_text_segments(inlines, |text, source_span| {
        let mut out = Vec::new();
        let mut cursor = 0usize;
        for (start, end) in strict_reminder_matches(text) {
            if start > cursor {
                out.push(RenderInline::Text {
                    source_span: subspan(source_span, cursor, start, source_len)?,
                    text: text
                        .get(cursor..start)
                        .ok_or_else(|| {
                            validation(
                                "reminder_slice_invalid",
                                "reminder scanner produced a non-boundary range",
                            )
                        })?
                        .to_owned(),
                });
            }
            let token = text.get(start..end).ok_or_else(|| {
                validation(
                    "reminder_slice_invalid",
                    "reminder scanner produced a non-boundary range",
                )
            })?;
            check_ir_string(token)?;
            out.push(RenderInline::Reminder {
                source_span: subspan(source_span, start, end, source_len)?,
                token: token.to_owned(),
            });
            cursor = end;
        }
        if cursor < text.len() {
            out.push(RenderInline::Text {
                source_span: subspan(source_span, cursor, text.len(), source_len)?,
                text: text
                    .get(cursor..)
                    .ok_or_else(|| {
                        validation(
                            "reminder_slice_invalid",
                            "reminder scanner produced a non-boundary range",
                        )
                    })?
                    .to_owned(),
            });
        }
        Ok(out)
    })
}

fn strict_reminder_matches(text: &str) -> Vec<(usize, usize)> {
    let mut matches = Vec::new();
    let mut cursor = 0usize;
    while let Some(relative) = text.get(cursor..).and_then(|tail| tail.find('@')) {
        let start = cursor + relative;
        let left_boundary = start == 0
            || text
                .get(..start)
                .and_then(|prefix| prefix.chars().next_back())
                .is_some_and(char::is_whitespace);
        if left_boundary && let Some(end) = parse_strict_reminder_at(text, start) {
            matches.push((start, end));
            cursor = end;
        } else {
            cursor = start + 1;
        }
    }
    matches
}

fn parse_strict_reminder_at(text: &str, start: usize) -> Option<usize> {
    let input = text.get(start..)?;
    let bytes = input.as_bytes();
    if bytes.len() < 17 || bytes.first() != Some(&b'@') {
        return None;
    }
    let date = input.get(1..11)?;
    let time = input.get(12..17)?;
    if !is_ymd(date) || bytes.get(11) != Some(&b'-') || !is_hm(time) {
        return None;
    }

    let mut offset = 17usize;
    let mut repeat_count = 1u64;
    if bytes.get(offset) == Some(&b'x') {
        let (value, end) = parse_positive_decimal(input, offset + 1)?;
        repeat_count = value;
        offset = end;
    }
    if bytes.get(offset) == Some(&b'i') {
        let (_value, end) = parse_positive_decimal(input, offset + 1)?;
        offset = end;
    }
    if bytes.get(offset) == Some(&b'r') {
        if !matches!(bytes.get(offset + 1), Some(b'd' | b'w')) {
            return None;
        }
        offset += 2;
    }
    if bytes.get(offset) == Some(&b'.') {
        if input
            .get(offset + 1..)
            .is_some_and(|tail| tail.starts_with("done"))
        {
            offset += 5;
        } else {
            let (fired, end) = parse_decimal(input, offset + 1)?;
            if fired > repeat_count {
                return None;
            }
            offset = end;
        }
    }

    let right = input.get(offset..).and_then(|tail| tail.chars().next());
    if right.is_some_and(|character| {
        !character.is_whitespace() && !matches!(character, ',' | ';' | '!' | '?' | ')' | ']' | '}')
    }) {
        return None;
    }
    Some(start + offset)
}

pub fn validate_reminder_token(token: &str) -> Result<(), LomoError> {
    if parse_strict_reminder_at(token, 0) != Some(token.len()) {
        return Err(validation(
            "invalid_reminder_token",
            "reminder token must match the strict stage-2 grammar",
        ));
    }
    Ok(())
}

pub struct ReminderTokenFacts {
    pub due_at_local: String,
    pub repeat_count: u32,
    pub fired_count: u32,
    pub done: bool,
    pub interval_minutes: u32,
    pub recurrence_code: String,
}

/// Constructs one canonical reminder token from typed owner facts.
///
/// # Errors
///
/// Returns validation when the composed token fails the strict stage-2 grammar.
pub fn build_reminder_token(
    due_at_local: &str,
    repeat_count: u32,
    fired_count: u32,
    done: bool,
    interval_minutes: u32,
    recurrence_code: &str,
) -> Result<String, LomoError> {
    if !is_ymd_hm(due_at_local) {
        return Err(validation(
            "invalid_reminder_token",
            "reminder due_at_local must be yyyy-MM-dd-HH:mm",
        ));
    }
    if repeat_count == 0 {
        return Err(validation(
            "invalid_reminder_token",
            "reminder repeat count must be positive",
        ));
    }
    if fired_count > repeat_count {
        return Err(validation(
            "invalid_reminder_token",
            "reminder fired count cannot exceed repeat count",
        ));
    }
    if !matches!(recurrence_code, "" | "d" | "w") {
        return Err(validation(
            "invalid_reminder_token",
            "reminder recurrence code must be empty, d, or w",
        ));
    }
    let mut token = format!("@{due_at_local}");
    if repeat_count > 1 {
        token.push('x');
        token.push_str(&repeat_count.to_string());
    }
    if repeat_count > 1 && interval_minutes != 10 {
        token.push('i');
        token.push_str(&interval_minutes.to_string());
    }
    if !recurrence_code.is_empty() {
        token.push('r');
        token.push_str(recurrence_code);
    }
    if done {
        token.push_str(".done");
    } else if repeat_count > 1 && fired_count > 0 {
        token.push('.');
        token.push_str(&fired_count.to_string());
    }
    validate_reminder_token(&token)?;
    Ok(token)
}

/// Owner mutation kinds that produce a Rust-canonical replacement token.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReminderTokenMutation {
    MarkDone,
    RecordFired,
}

/// Plans a Rust-canonical replacement token for one owner-owned reminder mutation.
///
/// # Errors
///
/// Returns validation when the current token is invalid or the mutation is not applicable.
pub fn plan_reminder_token_mutation(
    current_token: &str,
    mutation: ReminderTokenMutation,
) -> Result<String, LomoError> {
    let facts = reminder_token_facts(current_token)?;
    match mutation {
        ReminderTokenMutation::MarkDone => {
            if facts.done {
                return Ok(current_token.to_owned());
            }
            if matches!(facts.recurrence_code.as_str(), "d" | "w") {
                let next_due = advance_due_at_local(&facts.due_at_local, &facts.recurrence_code)?;
                return build_reminder_token(
                    &next_due,
                    facts.repeat_count,
                    0,
                    false,
                    facts.interval_minutes,
                    &facts.recurrence_code,
                );
            }
            build_reminder_token(
                &facts.due_at_local,
                facts.repeat_count,
                facts.fired_count,
                true,
                facts.interval_minutes,
                &facts.recurrence_code,
            )
        }
        ReminderTokenMutation::RecordFired => {
            if facts.done {
                return Ok(current_token.to_owned());
            }
            let new_fired = facts.fired_count.saturating_add(1).min(facts.repeat_count);
            let exhausted = new_fired >= facts.repeat_count;
            if exhausted && matches!(facts.recurrence_code.as_str(), "d" | "w") {
                let next_due = advance_due_at_local(&facts.due_at_local, &facts.recurrence_code)?;
                return build_reminder_token(
                    &next_due,
                    facts.repeat_count,
                    0,
                    false,
                    facts.interval_minutes,
                    &facts.recurrence_code,
                );
            }
            build_reminder_token(
                &facts.due_at_local,
                facts.repeat_count,
                new_fired,
                exhausted,
                facts.interval_minutes,
                &facts.recurrence_code,
            )
        }
    }
}

fn is_ymd_hm(text: &str) -> bool {
    let bytes = text.as_bytes();
    if bytes.len() != 16
        || bytes.get(4).copied() != Some(b'-')
        || bytes.get(7).copied() != Some(b'-')
        || bytes.get(10).copied() != Some(b'-')
        || bytes.get(13).copied() != Some(b':')
    {
        return false;
    }
    let Some(date) = text.get(..10) else {
        return false;
    };
    if !is_ymd(date) {
        return false;
    }
    let Some(hour) = bytes.get(11..13).and_then(decimal_u32) else {
        return false;
    };
    let Some(minute) = bytes.get(14..16).and_then(decimal_u32) else {
        return false;
    };
    hour <= 23 && minute <= 59
}

fn advance_due_at_local(due_at_local: &str, recurrence_code: &str) -> Result<String, LomoError> {
    let bytes = due_at_local.as_bytes();
    if !is_ymd_hm(due_at_local) {
        return Err(validation(
            "invalid_reminder_token",
            "cannot advance an invalid due_at_local",
        ));
    }
    let year = bytes
        .get(0..4)
        .and_then(decimal_u32)
        .ok_or_else(|| validation("invalid_reminder_token", "reminder year is invalid"))?;
    let month = bytes
        .get(5..7)
        .and_then(decimal_u32)
        .ok_or_else(|| validation("invalid_reminder_token", "reminder month is invalid"))?;
    let day = bytes
        .get(8..10)
        .and_then(decimal_u32)
        .ok_or_else(|| validation("invalid_reminder_token", "reminder day is invalid"))?;
    let hour = bytes
        .get(11..13)
        .and_then(decimal_u32)
        .ok_or_else(|| validation("invalid_reminder_token", "reminder hour is invalid"))?;
    let minute = bytes
        .get(14..16)
        .and_then(decimal_u32)
        .ok_or_else(|| validation("invalid_reminder_token", "reminder minute is invalid"))?;
    let days = match recurrence_code {
        "d" => 1i32,
        "w" => 7i32,
        _ => {
            return Err(validation(
                "invalid_reminder_token",
                "only d/w recurrence can advance due_at_local",
            ));
        }
    };
    let (year, month, day) = add_days(year, month, day, days)?;
    Ok(format!(
        "{year:04}-{month:02}-{day:02}-{hour:02}:{minute:02}"
    ))
}

fn add_days(year: u32, month: u32, day: u32, delta: i32) -> Result<(u32, u32, u32), LomoError> {
    let mut y = i64::from(year);
    let mut m = i64::from(month);
    let mut d = i64::from(day) + i64::from(delta);
    while d < 1 {
        m -= 1;
        if m < 1 {
            m = 12;
            y -= 1;
        }
        d += i64::from(days_in_month(
            u32::try_from(y).map_err(|_error| {
                validation("invalid_reminder_token", "reminder year underflow")
            })?,
            u32::try_from(m).map_err(|_error| {
                validation("invalid_reminder_token", "reminder month underflow")
            })?,
        )?);
    }
    loop {
        let dim = i64::from(days_in_month(
            u32::try_from(y)
                .map_err(|_error| validation("invalid_reminder_token", "reminder year overflow"))?,
            u32::try_from(m).map_err(|_error| {
                validation("invalid_reminder_token", "reminder month overflow")
            })?,
        )?);
        if d <= dim {
            break;
        }
        d -= dim;
        m += 1;
        if m > 12 {
            m = 1;
            y += 1;
        }
    }
    Ok((
        u32::try_from(y)
            .map_err(|_error| validation("invalid_reminder_token", "reminder year out of range"))?,
        u32::try_from(m).map_err(|_error| {
            validation("invalid_reminder_token", "reminder month out of range")
        })?,
        u32::try_from(d)
            .map_err(|_error| validation("invalid_reminder_token", "reminder day out of range"))?,
    ))
}

fn days_in_month(year: u32, month: u32) -> Result<u32, LomoError> {
    Ok(match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year(year) => 29,
        2 => 28,
        _ => {
            return Err(validation(
                "invalid_reminder_token",
                "reminder month is invalid",
            ));
        }
    })
}

/// Parses a validated reminder token into structured facts.
///
/// # Errors
///
/// Returns validation when the token is not a strict Lomo reminder form.
pub fn reminder_token_facts(token: &str) -> Result<ReminderTokenFacts, LomoError> {
    validate_reminder_token(token)?;
    let bytes = token.as_bytes();
    let mut offset = 17usize;
    let mut repeat_count = 1u64;
    let mut interval_minutes = 10u64;
    let mut recurrence_code = String::new();
    let mut done = false;
    let mut fired_count = 0u64;
    if bytes.get(offset) == Some(&b'x') {
        let (value, end) = parse_positive_decimal(token, offset + 1).ok_or_else(|| {
            validation("invalid_reminder_token", "reminder repeat count is invalid")
        })?;
        repeat_count = value;
        offset = end;
    }
    if bytes.get(offset) == Some(&b'i') {
        let (value, end) = parse_positive_decimal(token, offset + 1)
            .ok_or_else(|| validation("invalid_reminder_token", "reminder interval is invalid"))?;
        interval_minutes = value;
        offset = end;
    }
    if bytes.get(offset) == Some(&b'r') {
        token
            .get(offset + 1..offset + 2)
            .ok_or_else(|| validation("invalid_reminder_token", "reminder recurrence is invalid"))?
            .clone_into(&mut recurrence_code);
        offset += 2;
    }
    if bytes.get(offset) == Some(&b'.') {
        if token
            .get(offset + 1..)
            .is_some_and(|tail| tail.starts_with("done"))
        {
            done = true;
        } else {
            let (value, _end) = parse_decimal(token, offset + 1).ok_or_else(|| {
                validation("invalid_reminder_token", "reminder fired count is invalid")
            })?;
            fired_count = value;
        }
    }
    Ok(ReminderTokenFacts {
        due_at_local: token
            .get(1..17)
            .ok_or_else(|| {
                validation("invalid_reminder_token", "reminder due_at_local is invalid")
            })?
            .to_owned(),
        repeat_count: u32::try_from(repeat_count).map_err(|_error| {
            validation(
                "invalid_reminder_token",
                "reminder repeat count exceeds u32",
            )
        })?,
        fired_count: u32::try_from(fired_count).map_err(|_error| {
            validation("invalid_reminder_token", "reminder fired count exceeds u32")
        })?,
        done,
        interval_minutes: u32::try_from(interval_minutes).map_err(|_error| {
            validation("invalid_reminder_token", "reminder interval exceeds u32")
        })?,
        recurrence_code,
    })
}

fn parse_positive_decimal(input: &str, start: usize) -> Option<(u64, usize)> {
    let (value, end) = parse_decimal(input, start)?;
    (value > 0).then_some((value, end))
}

fn parse_decimal(input: &str, start: usize) -> Option<(u64, usize)> {
    let bytes = input.as_bytes();
    let mut end = start;
    while bytes.get(end).is_some_and(u8::is_ascii_digit) {
        end += 1;
    }
    if end == start {
        return None;
    }
    let value = decimal_value(input.get(start..end)?.as_bytes())?;
    Some((value, end))
}

fn decimal_value(bytes: &[u8]) -> Option<u64> {
    bytes.iter().try_fold(0u64, |value, byte| {
        value
            .checked_mul(10)?
            .checked_add(u64::from(byte.checked_sub(b'0')?))
    })
}

fn is_ymd(text: &str) -> bool {
    let b = text.as_bytes();
    if !(b.len() == 10
        && b.get(4).copied() == Some(b'-')
        && b.get(7).copied() == Some(b'-')
        && b.get(0..4)
            .is_some_and(|slice| slice.iter().all(u8::is_ascii_digit))
        && b.get(5..7)
            .is_some_and(|slice| slice.iter().all(u8::is_ascii_digit))
        && b.get(8..10)
            .is_some_and(|slice| slice.iter().all(u8::is_ascii_digit)))
    {
        return false;
    }
    let Some(year) = b.get(0..4).and_then(decimal_u32) else {
        return false;
    };
    let Some(month) = b.get(5..7).and_then(decimal_u32) else {
        return false;
    };
    let Some(day) = b.get(8..10).and_then(decimal_u32) else {
        return false;
    };
    let max_day = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_leap_year(year) => 29,
        2 => 28,
        _ => return false,
    };
    (1..=max_day).contains(&day)
}

fn decimal_u32(bytes: &[u8]) -> Option<u32> {
    let value = decimal_value(bytes)?;
    let Ok(value) = u32::try_from(value) else {
        return None;
    };
    Some(value)
}

fn is_hm(text: &str) -> bool {
    let b = text.as_bytes();
    let Some(left) = text.get(0..2) else {
        return false;
    };
    let Some(right) = text.get(3..5) else {
        return false;
    };
    if !(b.len() == 5
        && b.get(2).copied() == Some(b':')
        && is_two_digits(left)
        && is_two_digits(right))
    {
        return false;
    }
    let hour = b.get(0..2).and_then(two_digit_value);
    let minute = b.get(3..5).and_then(two_digit_value);
    hour.is_some_and(|value| value < 24) && minute.is_some_and(|value| value < 60)
}

fn two_digit_value(bytes: &[u8]) -> Option<u8> {
    let [tens, ones] = bytes else {
        return None;
    };
    Some(tens.checked_sub(b'0')?.checked_mul(10)? + ones.checked_sub(b'0')?)
}

const fn is_leap_year(year: u32) -> bool {
    year.is_multiple_of(4) && (!year.is_multiple_of(100) || year.is_multiple_of(400))
}

fn is_two_digits(text: &str) -> bool {
    text.len() == 2 && text.bytes().all(|b| b.is_ascii_digit())
}

fn map_text_segments(
    inlines: Vec<RenderInline>,
    map_text: impl Fn(&str, ByteSpan) -> Result<Vec<RenderInline>, LomoError> + Copy,
) -> Result<Vec<RenderInline>, LomoError> {
    let mut out = Vec::new();
    for inline in inlines {
        match inline {
            RenderInline::Text { source_span, text } => {
                out.extend(map_text(&text, source_span)?);
            }
            RenderInline::Strong {
                source_span,
                children,
            } => out.push(RenderInline::Strong {
                source_span,
                children: map_text_segments(children, map_text)?,
            }),
            RenderInline::Emphasis {
                source_span,
                children,
            } => out.push(RenderInline::Emphasis {
                source_span,
                children: map_text_segments(children, map_text)?,
            }),
            RenderInline::Strikethrough {
                source_span,
                children,
            } => out.push(RenderInline::Strikethrough {
                source_span,
                children: map_text_segments(children, map_text)?,
            }),
            RenderInline::Highlight {
                source_span,
                children,
            } => out.push(RenderInline::Highlight {
                source_span,
                children: map_text_segments(children, map_text)?,
            }),
            RenderInline::Link {
                destination,
                title,
                children,
                source_span,
            } => out.push(RenderInline::Link {
                source_span,
                destination,
                title,
                children: map_text_segments(children, map_text)?,
            }),
            RenderInline::WikiReference {
                source_span,
                target,
                children,
            } => {
                out.push(RenderInline::WikiReference {
                    source_span,
                    target,
                    children: map_text_segments(children, map_text)?,
                });
            }
            RenderInline::Code { .. }
            | RenderInline::Image { .. }
            | RenderInline::Tag { .. }
            | RenderInline::Reminder { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. } => out.push(inline),
        }
    }
    Ok(out)
}

fn collect_from_blocks(
    blocks: &[RenderBlock],
    tags: &mut Vec<String>,
    seen_tags: &mut BTreeSet<String>,
    attachments: &mut Vec<String>,
    seen_attachments: &mut BTreeSet<String>,
    facts: &mut Vec<SemanticFact>,
) {
    for block in blocks {
        match block {
            RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
                collect_from_inlines(
                    inlines,
                    tags,
                    seen_tags,
                    attachments,
                    seen_attachments,
                    facts,
                );
            }
            RenderBlock::BlockQuote { blocks, .. } => {
                collect_from_blocks(
                    blocks,
                    tags,
                    seen_tags,
                    attachments,
                    seen_attachments,
                    facts,
                );
            }
            RenderBlock::List { items, .. } => {
                for item in items {
                    collect_from_blocks(
                        &item.blocks,
                        tags,
                        seen_tags,
                        attachments,
                        seen_attachments,
                        facts,
                    );
                    if let (Some(task_span), Some(checked)) = (item.task_span, item.checked) {
                        facts.push(SemanticFact {
                            kind: SemanticFactKind::TaskItem,
                            value: if checked { "[x]" } else { "[ ]" }.to_owned(),
                            source_span: task_span,
                        });
                    }
                }
            }
            RenderBlock::Table { header, rows, .. } => {
                for cell in header.iter().chain(rows.iter().flatten()) {
                    collect_from_inlines(
                        &cell.inlines,
                        tags,
                        seen_tags,
                        attachments,
                        seen_attachments,
                        facts,
                    );
                }
            }
            RenderBlock::CodeBlock { .. }
            | RenderBlock::ThematicBreak { .. }
            | RenderBlock::HtmlBlock { .. } => {}
        }
    }
}

fn collect_from_inlines(
    inlines: &[RenderInline],
    tags: &mut Vec<String>,
    seen_tags: &mut BTreeSet<String>,
    attachments: &mut Vec<String>,
    seen_attachments: &mut BTreeSet<String>,
    facts: &mut Vec<SemanticFact>,
) {
    for inline in inlines {
        match inline {
            RenderInline::Tag { source_span, name } => {
                facts.push(SemanticFact {
                    kind: SemanticFactKind::Tag,
                    value: name.clone(),
                    source_span: *source_span,
                });
                if seen_tags.insert(name.clone()) {
                    tags.push(name.clone());
                }
            }
            RenderInline::Image {
                source_span,
                destination,
                ..
            } => {
                facts.push(SemanticFact {
                    kind: SemanticFactKind::Attachment,
                    value: destination.clone(),
                    source_span: *source_span,
                });
                if seen_attachments.insert(destination.clone()) {
                    attachments.push(destination.clone());
                }
            }
            RenderInline::Link {
                source_span,
                destination,
                children,
                ..
            } => {
                facts.push(SemanticFact {
                    kind: SemanticFactKind::Link,
                    value: destination.clone(),
                    source_span: *source_span,
                });
                if is_audio_target(destination) {
                    facts.push(SemanticFact {
                        kind: SemanticFactKind::Attachment,
                        value: destination.clone(),
                        source_span: *source_span,
                    });
                    if seen_attachments.insert(destination.clone()) {
                        attachments.push(destination.clone());
                    }
                }
                collect_from_inlines(
                    children,
                    tags,
                    seen_tags,
                    attachments,
                    seen_attachments,
                    facts,
                );
            }
            RenderInline::Reminder { source_span, token } => facts.push(SemanticFact {
                kind: SemanticFactKind::Reminder,
                value: token.clone(),
                source_span: *source_span,
            }),
            RenderInline::WikiReference {
                source_span,
                target,
                children,
            } => {
                facts.push(SemanticFact {
                    kind: SemanticFactKind::WikiReference,
                    value: target.clone(),
                    source_span: *source_span,
                });
                collect_from_inlines(
                    children,
                    tags,
                    seen_tags,
                    attachments,
                    seen_attachments,
                    facts,
                );
            }
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. } => {
                collect_from_inlines(
                    children,
                    tags,
                    seen_tags,
                    attachments,
                    seen_attachments,
                    facts,
                );
            }
            RenderInline::Text { .. }
            | RenderInline::Code { .. }
            | RenderInline::SoftBreak { .. }
            | RenderInline::HardBreak { .. }
            | RenderInline::HtmlInline { .. } => {}
        }
    }
}

fn is_audio_target(target: &str) -> bool {
    std::path::Path::new(target)
        .extension()
        .and_then(|ext| ext.to_str())
        .is_some_and(|ext| {
            matches!(
                ext.to_ascii_lowercase().as_str(),
                "m4a" | "mp3" | "ogg" | "wav" | "aac"
            )
        })
}

fn validate_final_ir(blocks: &[RenderBlock], source_len: usize) -> Result<u32, LomoError> {
    let mut count = 0u32;
    validate_blocks(blocks, 1, source_len, &mut count)?;
    Ok(count)
}

fn validate_blocks(
    blocks: &[RenderBlock],
    depth: u32,
    source_len: usize,
    count: &mut u32,
) -> Result<(), LomoError> {
    ResourceBudget::check_semantic_nesting_depth(depth)?;
    for block in blocks {
        count_final_node(count)?;
        validate_span(block_source_span(block), source_len)?;
        match block {
            RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
                validate_inlines(inlines, depth.saturating_add(1), source_len, count)?;
            }
            RenderBlock::BlockQuote { blocks, .. } => {
                validate_blocks(blocks, depth.saturating_add(1), source_len, count)?;
            }
            RenderBlock::List { items, .. } => {
                for item in items {
                    count_final_node(count)?;
                    validate_span(item.source_span, source_len)?;
                    if let Some(task_span) = item.task_span {
                        validate_span(task_span, source_len)?;
                    }
                    validate_blocks(&item.blocks, depth.saturating_add(1), source_len, count)?;
                }
            }
            RenderBlock::CodeBlock {
                language, literal, ..
            } => {
                if let Some(language) = language {
                    check_ir_string(language)?;
                }
                check_ir_string(literal)?;
            }
            RenderBlock::Table { header, rows, .. } => {
                for cell in header.iter().chain(rows.iter().flatten()) {
                    count_final_node(count)?;
                    validate_span(cell.source_span, source_len)?;
                    validate_inlines(&cell.inlines, depth.saturating_add(1), source_len, count)?;
                }
            }
            RenderBlock::HtmlBlock { literal, .. } => check_ir_string(literal)?,
            RenderBlock::ThematicBreak { .. } => {}
        }
    }
    Ok(())
}

fn validate_inlines(
    inlines: &[RenderInline],
    depth: u32,
    source_len: usize,
    count: &mut u32,
) -> Result<(), LomoError> {
    ResourceBudget::check_semantic_nesting_depth(depth)?;
    for inline in inlines {
        count_final_node(count)?;
        validate_span(inline_source_span(inline), source_len)?;
        match inline {
            RenderInline::Text { text, .. }
            | RenderInline::Code { text, .. }
            | RenderInline::HtmlInline { text, .. } => check_ir_string(text)?,
            RenderInline::Strong { children, .. }
            | RenderInline::Emphasis { children, .. }
            | RenderInline::Strikethrough { children, .. }
            | RenderInline::Highlight { children, .. }
            | RenderInline::WikiReference { children, .. } => {
                validate_inlines(children, depth.saturating_add(1), source_len, count)?;
            }
            RenderInline::Link {
                destination,
                title,
                children,
                ..
            } => {
                check_ir_string(destination)?;
                if let Some(title) = title {
                    check_ir_string(title)?;
                }
                validate_inlines(children, depth.saturating_add(1), source_len, count)?;
            }
            RenderInline::Image {
                destination,
                title,
                alt,
                ..
            } => {
                check_ir_string(destination)?;
                if let Some(title) = title {
                    check_ir_string(title)?;
                }
                check_ir_string(alt)?;
            }
            RenderInline::Tag { name, .. } => check_ir_string(name)?,
            RenderInline::Reminder { token, .. } => check_ir_string(token)?,
            RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => {}
        }
    }
    Ok(())
}

fn count_final_node(count: &mut u32) -> Result<(), LomoError> {
    *count = count.checked_add(1).ok_or_else(|| {
        crate::limits::resource_limit(
            "render_document_too_large",
            "RenderDocumentV1 node count cannot be represented",
        )
    })?;
    ResourceBudget::check_render_document_nodes(*count)
}

fn validate_span(span: ByteSpan, source_len: usize) -> Result<(), LomoError> {
    let _validated = ByteSpan::try_new(span.start(), span.end(), source_len)?;
    Ok(())
}

const fn block_source_span(block: &RenderBlock) -> ByteSpan {
    match block {
        RenderBlock::Paragraph { source_span, .. }
        | RenderBlock::Heading { source_span, .. }
        | RenderBlock::BlockQuote { source_span, .. }
        | RenderBlock::List { source_span, .. }
        | RenderBlock::CodeBlock { source_span, .. }
        | RenderBlock::ThematicBreak { source_span }
        | RenderBlock::Table { source_span, .. }
        | RenderBlock::HtmlBlock { source_span, .. } => *source_span,
    }
}

fn blocks_plain_text(blocks: &[RenderBlock]) -> String {
    blocks
        .iter()
        .map(block_plain_text)
        .collect::<Vec<_>>()
        .join("\n")
}

fn block_plain_text(block: &RenderBlock) -> String {
    match block {
        RenderBlock::Paragraph { inlines, .. } | RenderBlock::Heading { inlines, .. } => {
            inlines_plain_text(inlines)
        }
        RenderBlock::BlockQuote { blocks, .. } => blocks_plain_text(blocks),
        RenderBlock::List { items, .. } => items
            .iter()
            .map(|item| blocks_plain_text(&item.blocks))
            .collect::<Vec<_>>()
            .join("\n"),
        RenderBlock::CodeBlock { literal, .. } => {
            // UI semantic plain joins blocks with `\n`. Fenced code events include a trailing
            // newline in the literal; strip one trailing newline so join does not create a blank
            // line before the next block (matches JetBrains code-block plain projection).
            literal.strip_suffix('\n').unwrap_or(literal).to_owned()
        }
        RenderBlock::ThematicBreak { .. } => String::new(),
        RenderBlock::Table { header, rows, .. } => {
            let mut lines = Vec::new();
            lines.push(
                header
                    .iter()
                    .map(|cell| inlines_plain_text(&cell.inlines))
                    .collect::<Vec<_>>()
                    .join(" | "),
            );
            for row in rows {
                lines.push(
                    row.iter()
                        .map(|cell| inlines_plain_text(&cell.inlines))
                        .collect::<Vec<_>>()
                        .join(" | "),
                );
            }
            lines.join("\n")
        }
        RenderBlock::HtmlBlock { literal, .. } => literal.clone(),
    }
}

fn inlines_plain_text(inlines: &[RenderInline]) -> String {
    inlines.iter().map(inline_plain_text).collect()
}

fn inline_plain_text(inline: &RenderInline) -> String {
    match inline {
        // Keep raw HTML text in plain projection so share/search fingerprints stay compatible with
        // the UI semantic characterization corpus (typed node still carries the markup).
        RenderInline::Text { text, .. }
        | RenderInline::Code { text, .. }
        | RenderInline::HtmlInline { text, .. } => text.clone(),
        RenderInline::Strong { children, .. }
        | RenderInline::Emphasis { children, .. }
        | RenderInline::Strikethrough { children, .. }
        | RenderInline::Highlight { children, .. }
        | RenderInline::Link { children, .. } => inlines_plain_text(children),
        RenderInline::Image { alt, .. } => alt.clone(),
        RenderInline::Tag { name, .. } => format!("#{name}"),
        RenderInline::Reminder { token, .. } => token.clone(),
        RenderInline::WikiReference {
            target, children, ..
        } => {
            if children.is_empty() {
                target.clone()
            } else {
                inlines_plain_text(children)
            }
        }
        RenderInline::SoftBreak { .. } | RenderInline::HardBreak { .. } => "\n".to_owned(),
    }
}
