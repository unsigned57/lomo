use boltffi_ffi_rules::naming;
use heck::{ToLowerCamelCase, ToUpperCamelCase};

pub struct NamingConvention;

impl NamingConvention {
    pub fn class_name(name: &str) -> String {
        name.to_upper_camel_case()
    }

    pub fn function_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        Self::escape_keyword(&converted)
    }

    pub fn param_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        Self::escape_keyword(&converted)
    }

    pub fn local_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        format!("l${}", converted)
    }

    pub fn record_struct_name(name: &str) -> String {
        format!("_${}$Struct", Self::class_name(name))
    }

    pub fn property_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        Self::escape_keyword(&converted)
    }

    pub fn priv_const_name(name: &str) -> String {
        let converted = name.to_lower_camel_case();
        format!("_k${}", converted)
    }

    pub fn escape_keyword(name: &str) -> String {
        if Self::is_dart_keyword(name) {
            format!("${}", name)
        } else {
            name.to_string()
        }
    }

    fn is_dart_keyword(name: &str) -> bool {
        matches!(
            name,
            "abstract"
                | "as"
                | "assert"
                | "await"
                | "break"
                | "case"
                | "catch"
                | "class"
                | "const"
                | "continue"
                | "covariant"
                | "default"
                | "deferred"
                | "do"
                | "dynamic"
                | "else"
                | "enum"
                | "export"
                | "extends"
                | "extension"
                | "external"
                | "factory"
                | "false"
                | "final"
                | "finally"
                | "for"
                | "get"
                | "if"
                | "implements"
                | "import"
                | "in"
                | "interface"
                | "is"
                | "late"
                | "library"
                | "mixin"
                | "new"
                | "null"
                | "operator"
                | "part"
                | "required"
                | "rethrow"
                | "return"
                | "set"
                | "static"
                | "super"
                | "switch"
                | "this"
                | "throw"
                | "true"
                | "try"
                | "type"
                | "typedef"
                | "var"
                | "void"
                | "with"
                | "while"
                | "yield"
                | "bool"
                | "int"
                | "double"
                | "num"
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
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(NamingConvention::param_name("part"), "$part");
        assert_eq!(NamingConvention::param_name("for"), "$for");
        assert_eq!(NamingConvention::param_name("as"), "$as");
    }

    #[test]
    fn test_method_name_escapes_keywords() {
        assert_eq!(NamingConvention::function_name("get"), "$get");
        assert_eq!(NamingConvention::function_name("set"), "$set");
        assert_eq!(NamingConvention::function_name("final"), "$final");
    }
}
