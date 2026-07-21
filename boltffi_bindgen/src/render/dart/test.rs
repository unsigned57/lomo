use crate::{
    ir::{FfiContract, Lowerer, PackageInfo},
    render::dart::{DartLibrary, DartLowerer},
};

pub fn empty_contract() -> FfiContract {
    FfiContract {
        package: PackageInfo {
            name: "test".to_string(),
            version: None,
        },
        functions: vec![],
        catalog: Default::default(),
    }
}

pub fn lower(ffi: &FfiContract) -> DartLibrary {
    let abi = Lowerer::new(ffi).to_abi_contract();

    DartLowerer::new(ffi, &abi, "test").library()
}
