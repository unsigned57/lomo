use std::path::Path;

use super::types::{CallbackBridge, FfiClass, FfiContract, FfiFunction, FfiOutput, FfiType};
use crate::parse::FfiPatterns;

pub struct ContractLoader;

impl ContractLoader {
    pub fn from_source(source: &str, prefix: &str) -> FfiContract {
        Self::from_source_with_patterns(source, prefix, &FfiPatterns::swift())
    }

    pub fn from_source_with_patterns(
        source: &str,
        prefix: &str,
        patterns: &FfiPatterns,
    ) -> FfiContract {
        let mut contract = FfiContract::new("detected", prefix);

        Self::detect_classes(source, prefix, patterns, &mut contract);
        Self::detect_callback_bridges(source, patterns, &mut contract);
        Self::detect_vec_patterns(source, prefix, &mut contract);

        contract
    }

    pub fn from_json(path: &Path) -> Result<FfiContract, std::io::Error> {
        let _content = std::fs::read_to_string(path)?;
        Ok(FfiContract::default())
    }

    fn detect_classes(
        source: &str,
        prefix: &str,
        patterns: &FfiPatterns,
        contract: &mut FfiContract,
    ) {
        source.lines().for_each(|line| {
            if patterns.is_class_decl(line)
                && !patterns.is_bridge_class(line)
                && let Some(class_name) = patterns.extract_class_name(line)
            {
                let snake_name = Self::to_snake_case(class_name);
                let class = FfiClass::new(class_name)
                    .with_constructor(format!("{}_{}_new", prefix, snake_name))
                    .with_destructor(format!("{}_{}_free", prefix, snake_name));

                contract.add_class(class);
            }
        });
    }

    fn detect_callback_bridges(source: &str, patterns: &FfiPatterns, contract: &mut FfiContract) {
        source.lines().for_each(|line| {
            if patterns.is_bridge_class(line)
                && let Some(bridge_name) = patterns.extract_class_name(line)
            {
                let trait_name = patterns
                    .bridge_markers
                    .iter()
                    .find_map(|marker| bridge_name.strip_suffix(marker))
                    .unwrap_or(bridge_name);

                contract.add_callback_bridge(CallbackBridge::new(trait_name, bridge_name));
            }
        });
    }

    fn detect_vec_patterns(source: &str, prefix: &str, contract: &mut FfiContract) {
        let len_pattern = format!("{}_", prefix);
        let copy_pattern = "_copy_into";

        source.lines().for_each(|line| {
            if line.contains(&len_pattern) && line.contains("_len(") {
                let func_name = line
                    .split(|c: char| !c.is_alphanumeric() && c != '_')
                    .find(|s| s.starts_with(&len_pattern) && s.ends_with("_len"))
                    .unwrap_or("");

                if !func_name.is_empty() {
                    let base_name = func_name.strip_suffix("_len").unwrap_or(func_name);
                    let copy_fn = format!("{}{}", base_name, copy_pattern);

                    contract.add_function(FfiFunction::new(base_name, func_name).with_output(
                        FfiOutput::VecPattern {
                            len_fn: func_name.to_string(),
                            copy_fn,
                            element_type: FfiType::Void,
                        },
                    ));
                }
            }
        });
    }

    fn to_snake_case(s: &str) -> String {
        let mut result = String::new();

        s.chars().for_each(|c| {
            if c.is_uppercase() {
                if !result.is_empty() {
                    result.push('_');
                }
                result.push(c.to_lowercase().next().unwrap_or(c));
            } else {
                result.push(c);
            }
        });

        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_detect_classes() {
        let source = r#"
public class DataStore {
    private let handle: UInt64
    
    public init() {
        self.handle = boltffi_data_store_new()
    }
    
    deinit {
        _ = boltffi_data_store_free(handle)
    }
}
"#;

        let contract = ContractLoader::from_source(source, "boltffi");

        assert!(contract.get_class("DataStore").is_some());
        let class = contract.get_class("DataStore").unwrap();
        assert_eq!(class.destructor.as_deref(), Some("boltffi_data_store_free"));
    }

    #[test]
    fn test_detect_callback_bridges() {
        let source = r#"
private class AsyncDataFetcherBridge {
    let impl: AsyncDataFetcher
    
    static func create(_ impl: AsyncDataFetcher) -> UnsafeMutableRawPointer {
        let box = AsyncDataFetcherBridge(impl)
        return Unmanaged.passRetained(box).toOpaque()
    }
}
"#;

        let contract = ContractLoader::from_source(source, "boltffi");

        assert!(contract.is_callback_bridge_retain("AsyncDataFetcherBridge"));
    }

    #[test]
    fn test_detect_vec_patterns() {
        let source = r#"
public func generateLocations(count: Int32) -> [Location] {
    let len = boltffi_generate_locations_len(count)
    let ptr = UnsafeMutablePointer<Location>.allocate(capacity: Int(len))
    defer { ptr.deallocate() }
    var written: UInt = 0
    let status = boltffi_generate_locations_copy_into(count, ptr, len, &written)
    ensureOk(status)
    return Array(UnsafeBufferPointer(start: ptr, count: Int(written)))
}
"#;

        let contract = ContractLoader::from_source(source, "boltffi");

        assert!(
            contract
                .get_function("boltffi_generate_locations_len")
                .is_some()
        );
    }

    #[test]
    fn test_snake_case() {
        assert_eq!(ContractLoader::to_snake_case("DataStore"), "data_store");
        assert_eq!(
            ContractLoader::to_snake_case("HTTPClient"),
            "h_t_t_p_client"
        );
        assert_eq!(ContractLoader::to_snake_case("Simple"), "simple");
    }
}
