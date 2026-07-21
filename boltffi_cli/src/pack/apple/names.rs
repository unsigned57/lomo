use crate::config::Config;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct AppleNames {
    library_name: String,
    swift_module_name: String,
    xcframework_name: String,
    ffi_module_name: String,
}

impl AppleNames {
    pub(super) fn from_config(config: &Config) -> Self {
        let xcframework_name = config.xcframework_name();

        Self {
            library_name: config.library_name().to_string(),
            swift_module_name: config.swift_module_name(),
            ffi_module_name: format!("{xcframework_name}FFI"),
            xcframework_name,
        }
    }

    pub(super) fn library_name(&self) -> &str {
        &self.library_name
    }

    pub(super) fn swift_module_name(&self) -> &str {
        &self.swift_module_name
    }

    pub(super) fn xcframework_name(&self) -> &str {
        &self.xcframework_name
    }

    pub(super) fn ffi_module_name(&self) -> &str {
        &self.ffi_module_name
    }
}
