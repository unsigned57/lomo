use boltffi_ffi_rules::naming;
use heck::{ToLowerCamelCase, ToShoutySnakeCase, ToUpperCamelCase};

pub struct NamingConvention;

impl NamingConvention {
    pub fn class_name(name: &str) -> String {
        name.to_upper_camel_case()
    }

    pub fn method_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        Self::escape_keyword(&converted)
    }

    pub fn param_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        Self::escape_keyword(&converted)
    }

    pub fn property_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        Self::escape_keyword(&converted)
    }

    pub fn enum_entry_name(name: &str) -> String {
        name.to_shouty_snake_case()
    }

    pub fn escape_keyword(name: &str) -> String {
        if Self::is_kotlin_keyword(name) {
            format!("`{}`", name)
        } else {
            name.to_string()
        }
    }

    fn is_kotlin_keyword(name: &str) -> bool {
        matches!(
            name,
            "as" | "break"
                | "class"
                | "continue"
                | "do"
                | "else"
                | "false"
                | "for"
                | "fun"
                | "if"
                | "in"
                | "interface"
                | "is"
                | "null"
                | "object"
                | "package"
                | "return"
                | "super"
                | "this"
                | "throw"
                | "true"
                | "try"
                | "typealias"
                | "typeof"
                | "val"
                | "var"
                | "when"
                | "while"
                | "by"
                | "catch"
                | "constructor"
                | "delegate"
                | "dynamic"
                | "field"
                | "file"
                | "finally"
                | "get"
                | "import"
                | "init"
                | "param"
                | "property"
                | "receiver"
                | "set"
                | "setparam"
                | "value"
                | "where"
                | "actual"
                | "abstract"
                | "annotation"
                | "companion"
                | "const"
                | "crossinline"
                | "data"
                | "enum"
                | "expect"
                | "external"
                | "final"
                | "infix"
                | "inline"
                | "inner"
                | "internal"
                | "lateinit"
                | "noinline"
                | "open"
                | "operator"
                | "out"
                | "override"
                | "private"
                | "protected"
                | "public"
                | "reified"
                | "sealed"
                | "suspend"
                | "tailrec"
                | "vararg"
        )
    }

    pub fn ffi_prefix() -> String {
        naming::ffi_prefix().to_string()
    }

    pub fn class_ffi_prefix(class_name: &str) -> String {
        naming::class_ffi_prefix(class_name).into_string()
    }

    pub fn ffi_module_name(crate_name: &str) -> String {
        naming::ffi_module_name(crate_name)
    }

    pub fn jni_class_path(package: &str, class_name: &str) -> String {
        format!("{}.{}", package, class_name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KOTLIN_KEYWORDS: &[&str] = &[
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
        "by",
        "catch",
        "constructor",
        "delegate",
        "dynamic",
        "field",
        "file",
        "finally",
        "get",
        "import",
        "init",
        "param",
        "property",
        "receiver",
        "set",
        "setparam",
        "value",
        "where",
        "actual",
        "abstract",
        "annotation",
        "companion",
        "const",
        "crossinline",
        "data",
        "enum",
        "expect",
        "external",
        "final",
        "infix",
        "inline",
        "inner",
        "internal",
        "lateinit",
        "noinline",
        "open",
        "operator",
        "out",
        "override",
        "private",
        "protected",
        "public",
        "reified",
        "sealed",
        "suspend",
        "tailrec",
        "vararg",
    ];

    #[test]
    fn test_all_keywords_escaped() {
        for keyword in KOTLIN_KEYWORDS {
            let escaped = NamingConvention::escape_keyword(keyword);
            assert_eq!(
                escaped,
                format!("`{}`", keyword),
                "keyword '{}' should be escaped",
                keyword
            );
        }
    }

    #[test]
    fn test_non_keywords_not_escaped() {
        let non_keywords = ["count", "name", "sensor", "handle", "result", "buffer"];
        for word in non_keywords {
            let escaped = NamingConvention::escape_keyword(word);
            assert_eq!(escaped, word, "'{}' should not be escaped", word);
        }
    }

    #[test]
    fn test_param_name_escapes_keywords() {
        assert_eq!(NamingConvention::param_name("value"), "`value`");
        assert_eq!(NamingConvention::param_name("data"), "`data`");
        assert_eq!(NamingConvention::param_name("object"), "`object`");
    }

    #[test]
    fn test_method_name_escapes_keywords() {
        assert_eq!(NamingConvention::method_name("get"), "`get`");
        assert_eq!(NamingConvention::method_name("set"), "`set`");
        assert_eq!(NamingConvention::method_name("init"), "`init`");
    }

    #[test]
    fn test_snake_case_conversion_then_escape() {
        assert_eq!(NamingConvention::param_name("get_value"), "getValue");
        assert_eq!(NamingConvention::method_name("set_data"), "setData");
    }
}
