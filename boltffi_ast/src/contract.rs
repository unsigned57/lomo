use serde::{Deserialize, Serialize};

use crate::{
    ClassDef, ConstantDef, CustomTypeDef, EnumDef, FunctionDef, RecordDef, StreamDef, TraitDef,
};

/// Package metadata for the crate that produced a source contract.
///
/// The package name and version travel with the declarations, so a serialized
/// contract can still say which Rust crate produced it.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct PackageInfo {
    /// Cargo package name.
    pub name: String,
    /// Cargo package version, when the scanner has it.
    pub version: Option<String>,
}

impl PackageInfo {
    /// Builds package metadata.
    ///
    /// The `name` parameter is the Cargo package name. The `version` parameter
    /// is the optional Cargo package version.
    ///
    /// Returns package information suitable for the top-level source contract.
    pub fn new(name: impl Into<String>, version: Option<String>) -> Self {
        Self {
            name: name.into(),
            version,
        }
    }
}

/// The source contract produced by scanning one Rust crate.
///
/// This is the top-level value of `boltffi_ast`: every exported declaration
/// grouped by kind, with package metadata next to it. Separate lists keep each
/// declaration family in its own strongly typed collection.
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct SourceContract {
    /// Package that owns the exported declarations.
    pub package: PackageInfo,
    /// Records exported by the crate.
    pub records: Vec<RecordDef>,
    /// Enums exported by the crate.
    pub enums: Vec<EnumDef>,
    /// Free functions exported by the crate.
    pub functions: Vec<FunctionDef>,
    /// Class-style objects exported by the crate.
    pub classes: Vec<ClassDef>,
    /// Traits exported by the crate (used as callback targets at the FFI
    /// boundary).
    pub traits: Vec<TraitDef>,
    /// Streams exported by the crate.
    pub streams: Vec<StreamDef>,
    /// Constants exported by the crate.
    pub constants: Vec<ConstantDef>,
    /// Custom type declarations exported by the crate.
    pub customs: Vec<CustomTypeDef>,
}

impl SourceContract {
    /// Builds an empty source contract for a package.
    ///
    /// The `package` parameter identifies the Cargo package that produced the
    /// contract.
    ///
    /// Returns a contract ready for the scanner to fill with declarations.
    pub fn new(package: PackageInfo) -> Self {
        Self {
            package,
            records: Vec::new(),
            enums: Vec::new(),
            functions: Vec::new(),
            classes: Vec::new(),
            traits: Vec::new(),
            streams: Vec::new(),
            constants: Vec::new(),
            customs: Vec::new(),
        }
    }
}
