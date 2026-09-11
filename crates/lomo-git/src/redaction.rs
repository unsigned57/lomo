//! Secret and URL redaction for Git diagnostics (never log tokens / userinfo).

/// Redacts common secret patterns from a free-form diagnostic string.
#[must_use]
pub fn redact_diagnostic(raw: &str) -> String {
    let with_urls = redact_url_userinfo(raw);
    let mut out = redact_kv_secrets(&with_urls);
    if out.len() > 2_048 {
        out.truncate(2_048);
    }
    out
}

fn redact_url_userinfo(input: &str) -> String {
    // Replace `scheme://userinfo@host` with `scheme://***@host` via char scan (no indexing).
    let mut out = String::with_capacity(input.len());
    let mut chars = input.chars().peekable();
    while let Some(ch) = chars.next() {
        out.push(ch);
        if ch == ':' && chars.peek() == Some(&'/') {
            out.push(chars.next().unwrap_or('/'));
            if chars.peek() == Some(&'/') {
                out.push(chars.next().unwrap_or('/'));
                // Now at potential userinfo.
                let mut userinfo = String::new();
                let mut saw_at = false;
                while let Some(&next) = chars.peek() {
                    match next {
                        '@' => {
                            saw_at = true;
                            let _at = chars.next();
                            break;
                        }
                        '/' | '?' | '#' | ' ' | '\n' | '\r' | '\t' => break,
                        _ => {
                            userinfo.push(chars.next().unwrap_or(next));
                        }
                    }
                }
                if saw_at {
                    out.push_str("***@");
                } else {
                    out.push_str(&userinfo);
                }
            }
        }
    }
    out
}

fn redact_kv_secrets(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for token in input.split_whitespace() {
        if !out.is_empty() {
            out.push(' ');
        }
        if let Some((key, _value)) = token.split_once('=') {
            let key_l = key.to_ascii_lowercase();
            if key_l.contains("token")
                || key_l.contains("password")
                || key_l.contains("secret")
                || key_l.contains("passwd")
                || key_l == "authorization"
            {
                out.push_str(key);
                out.push_str("=<redacted>");
                continue;
            }
        }
        if token.to_ascii_lowercase().starts_with("bearer") {
            out.push_str("Bearer <redacted>");
            continue;
        }
        out.push_str(token);
    }
    out
}
