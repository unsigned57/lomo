//! Kotlin source dual view backed by the tree-sitter parse tree: `code`
//! masks comments and literal contents with spaces while `${ }`
//! interpolations stay visible (their `}` still separates the payload from
//! whatever follows the literal); `evidence` masks comments only, so names
//! that legitimately arrive as string arguments remain checkable there.
//! Gates that must not be satisfied by retained doc strings or literal
//! payloads read `code`.

use tree_sitter::{Node, Parser};

/// One Kotlin source under the masked-code / evidence dual view.
pub struct KotlinText {
    /// The source with comments and literal contents blanked to spaces —
    /// except `${ }` interpolation expressions, which are real code and stay
    /// visible (the closing `}` keeps the boundary). `$name` shorthands are
    /// literal content in this grammar and are masked like the rest.
    /// Newlines are preserved so positions stay stable.
    pub code: String,
    /// The source with comments blanked to spaces.
    pub evidence: String,
}

const COMMENT_KINDS: &[&str] = &["line_comment", "block_comment", "multiline_comment"];

const LITERAL_KINDS: &[&str] = &[
    "string_literal",
    "multiline_string_literal",
    "character_literal",
];

fn blank(node: Node, bytes: &mut [u8]) {
    if let Some(slice) = bytes.get_mut(node.byte_range()) {
        for byte in slice {
            if *byte != b'\n' {
                *byte = b' ';
            }
        }
    }
}

fn mask_code(node: Node, source: &str, bytes: &mut [u8]) {
    let kind = node.kind();
    if COMMENT_KINDS.contains(&kind) || kind == "character_literal" {
        blank(node, bytes);
        return;
    }
    if LITERAL_KINDS.contains(&kind) {
        let mut cursor = node.walk();
        for child in node.children(&mut cursor) {
            // `${ expr }` is real code with its own `}` boundary; the
            // `$name` shorthand form is literal content — it must not glue
            // to a `(` following the literal, so it masks like the rest.
            if child.kind() == "interpolation"
                && source
                    .as_bytes()
                    .get(child.byte_range())
                    .is_some_and(|text| text.starts_with(b"${"))
            {
                mask_code(child, source, bytes);
            } else {
                blank(child, bytes);
            }
        }
        return;
    }
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        mask_code(child, source, bytes);
    }
}

fn mask_comments(node: Node, bytes: &mut [u8]) {
    if COMMENT_KINDS.contains(&node.kind()) {
        blank(node, bytes);
        return;
    }
    let mut cursor = node.walk();
    for child in node.children(&mut cursor) {
        mask_comments(child, bytes);
    }
}

/// Parses `source` and returns its masked-code / evidence dual view.
/// A source the grammar cannot account for yields empty views so checks
/// fail closed rather than reading raw text.
#[must_use]
pub fn kotlin_code_view(source: &str) -> KotlinText {
    let mut parser = Parser::new();
    let mut code = Vec::new();
    let mut evidence = Vec::new();
    if parser
        .set_language(&tree_sitter_kotlin_ng::LANGUAGE.into())
        .is_ok()
        && let Some(tree) = parser.parse(source, None)
        && !tree.root_node().has_error()
    {
        code = source.as_bytes().to_vec();
        mask_code(tree.root_node(), source, &mut code);
        evidence = source.as_bytes().to_vec();
        mask_comments(tree.root_node(), &mut evidence);
    }
    KotlinText {
        code: String::from_utf8_lossy(&code).into_owned(),
        evidence: String::from_utf8_lossy(&evidence).into_owned(),
    }
}
