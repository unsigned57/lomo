use std::path::PathBuf;

use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::target::RustTarget;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DartConfig {
    #[serde(default = "default_dart_output")]
    pub output: PathBuf,
    #[serde(default)]
    pub enabled: bool,
    #[serde(
        default,
        serialize_with = "DartConfig::serialize_native_architectures",
        deserialize_with = "DartConfig::deserialize_native_architectures"
    )]
    pub native_architectures: Option<Vec<RustTarget>>,
}

impl Default for DartConfig {
    fn default() -> Self {
        Self {
            output: default_dart_output(),
            enabled: false,
            native_architectures: None,
        }
    }
}

impl DartConfig {
    fn deserialize_native_architectures<'de, D>(
        deserializer: D,
    ) -> Result<Option<Vec<RustTarget>>, D::Error>
    where
        D: Deserializer<'de>,
    {
        Option::<Vec<String>>::deserialize(deserializer)?
            .map(|architectures| {
                architectures
                    .into_iter()
                    .map(|architecture| {
                        RustTarget::from_dart_native_name(&architecture).ok_or_else(|| {
                            <D::Error as serde::de::Error>::custom(format!(
                                "unsupported Dart native architecture {architecture}"
                            ))
                        })
                    })
                    .collect()
            })
            .transpose()
    }

    fn serialize_native_architectures<S>(
        architectures: &Option<Vec<RustTarget>>,
        serializer: S,
    ) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        architectures
            .as_deref()
            .map(|architectures| {
                architectures
                    .iter()
                    .map(|target| {
                        target.dart_native_name().ok_or_else(|| {
                            <S::Error as serde::ser::Error>::custom(format!(
                                "unsupported Dart native target {}",
                                target.triple()
                            ))
                        })
                    })
                    .collect::<Result<Vec<_>, S::Error>>()
            })
            .transpose()?
            .serialize(serializer)
    }
}

fn default_dart_output() -> PathBuf {
    PathBuf::from("dist/dart")
}
