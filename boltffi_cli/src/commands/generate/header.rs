use boltffi_bindgen::CHeaderLowerer;

use boltffi_bindgen::target::Target;

use crate::cli::{CliError, Result};
use crate::commands::generate::generator::{GenerateRequest, LanguageGenerator, ScanPointerWidth};

pub struct HeaderGenerator;

impl LanguageGenerator for HeaderGenerator {
    const TARGET: Target = Target::Header;

    fn generate(request: &GenerateRequest<'_>) -> Result<()> {
        if !request.config().is_apple_enabled() && !request.config().is_android_enabled() {
            return Err(CliError::CommandFailed {
                command: "both targets.apple.enabled and targets.android.enabled are false"
                    .to_string(),
                status: None,
            });
        }

        let output_directory = request
            .output_override()
            .map(ToOwned::to_owned)
            .unwrap_or_else(|| {
                if request.config().is_apple_enabled() {
                    request.config().apple_header_output()
                } else {
                    request.config().android_header_output()
                }
            });
        let output_path = output_directory.join(format!("{}.h", request.config().library_name()));

        request.ensure_output_directory(&output_directory)?;

        let scan_pointer_width =
            if request.config().is_apple_enabled() && !request.config().is_android_enabled() {
                ScanPointerWidth::Fixed(64)
            } else {
                ScanPointerWidth::Flexible
            };
        let lowered_crate = request.lowered_crate(scan_pointer_width)?;
        let header_source =
            CHeaderLowerer::new(&lowered_crate.ffi_contract, &lowered_crate.abi_contract)
                .generate();

        request.write_output(&output_path, header_source)
    }
}
