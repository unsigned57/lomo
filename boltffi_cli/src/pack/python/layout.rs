use std::path::{Path, PathBuf};

use crate::cli::{CliError, Result};
use crate::target::NativeHostPlatform;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PythonPackageLayout {
    pub root_directory: PathBuf,
    pub package_directory: PathBuf,
    pub wheel_directory: PathBuf,
    pub pyproject_path: PathBuf,
    pub setup_script_path: PathBuf,
    pub package_init_path: PathBuf,
    pub package_stub_path: PathBuf,
    pub typed_marker_path: PathBuf,
    pub native_bridge_source_path: PathBuf,
}

impl PythonPackageLayout {
    pub fn new(root_directory: impl AsRef<Path>, module_name: &str) -> Self {
        let root_directory = root_directory.as_ref().to_path_buf();
        let wheel_directory = root_directory.join("wheelhouse");
        Self::with_wheel_directory(root_directory, wheel_directory, module_name)
    }

    pub fn with_wheel_directory(
        root_directory: impl AsRef<Path>,
        wheel_directory: impl AsRef<Path>,
        module_name: &str,
    ) -> Self {
        let root_directory = root_directory.as_ref().to_path_buf();
        let package_directory = root_directory.join(module_name);
        let wheel_directory = wheel_directory.as_ref().to_path_buf();

        Self {
            wheel_directory,
            pyproject_path: root_directory.join("pyproject.toml"),
            setup_script_path: root_directory.join("setup.py"),
            package_init_path: package_directory.join("__init__.py"),
            package_stub_path: package_directory.join("__init__.pyi"),
            typed_marker_path: package_directory.join("py.typed"),
            native_bridge_source_path: package_directory.join("_native.c"),
            root_directory,
            package_directory,
        }
    }

    pub fn packaged_shared_library_path(
        &self,
        host_platform: NativeHostPlatform,
        artifact_name: &str,
    ) -> PathBuf {
        self.package_directory
            .join(host_platform.shared_library_filename(artifact_name))
    }

    pub fn validate_generated_sources(&self) -> Result<()> {
        [
            &self.pyproject_path,
            &self.setup_script_path,
            &self.package_init_path,
            &self.package_stub_path,
            &self.typed_marker_path,
            &self.native_bridge_source_path,
        ]
        .into_iter()
        .find(|path| !path.exists())
        .cloned()
        .map_or(Ok(()), |path| Err(CliError::FileNotFound(path)))
    }

    pub fn validate_wheel_directory_safety(&self) -> Result<()> {
        if self.root_directory.starts_with(&self.wheel_directory) {
            return Err(CliError::CommandFailed {
                command: format!(
                    "targets.python.wheel.output '{}' must not be the same as or contain targets.python.output '{}'",
                    self.wheel_directory.display(),
                    self.root_directory.display()
                ),
                status: None,
            });
        }

        if self.package_directory.starts_with(&self.wheel_directory) {
            return Err(CliError::CommandFailed {
                command: format!(
                    "targets.python.wheel.output '{}' must not be the same as or contain the generated Python package directory '{}'",
                    self.wheel_directory.display(),
                    self.package_directory.display()
                ),
                status: None,
            });
        }

        if self.wheel_directory.starts_with(&self.package_directory) {
            return Err(CliError::CommandFailed {
                command: format!(
                    "targets.python.wheel.output '{}' must not be inside the generated Python package directory '{}'",
                    self.wheel_directory.display(),
                    self.package_directory.display()
                ),
                status: None,
            });
        }

        Ok(())
    }

    pub fn prepare_wheel_directory(&self) -> Result<()> {
        self.validate_wheel_directory_safety()?;

        match std::fs::remove_dir_all(&self.wheel_directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(source) => {
                return Err(CliError::WriteFailed {
                    path: self.wheel_directory.clone(),
                    source,
                });
            }
        }

        std::fs::create_dir_all(&self.wheel_directory).map_err(|source| {
            CliError::CreateDirectoryFailed {
                path: self.wheel_directory.clone(),
                source,
            }
        })
    }

    pub fn remove_packaged_native_libraries(&self) -> Result<()> {
        std::fs::read_dir(&self.package_directory)
            .map_err(|source| CliError::ReadFailed {
                path: self.package_directory.clone(),
                source,
            })?
            .map(|entry| entry.map(|entry| entry.path()))
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|source| CliError::ReadFailed {
                path: self.package_directory.clone(),
                source,
            })?
            .into_iter()
            .filter(|path| Self::is_packaged_native_library(path))
            .try_for_each(|path| match std::fs::remove_file(&path) {
                Ok(()) => Ok(()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
                Err(source) => Err(CliError::WriteFailed { path, source }),
            })
    }

    fn is_packaged_native_library(path: &Path) -> bool {
        path.is_file()
            && path
                .extension()
                .and_then(|extension| extension.to_str())
                .is_some_and(|extension| matches!(extension, "dll" | "dylib" | "so"))
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::PythonPackageLayout;
    use crate::cli::CliError;
    use crate::target::NativeHostPlatform;

    #[test]
    fn builds_expected_python_package_paths() {
        let layout = PythonPackageLayout::new(PathBuf::from("dist/python"), "demo_lib");

        assert_eq!(layout.root_directory, PathBuf::from("dist/python"));
        assert_eq!(
            layout.package_directory,
            PathBuf::from("dist/python/demo_lib")
        );
        assert_eq!(
            layout.wheel_directory,
            PathBuf::from("dist/python/wheelhouse")
        );
        assert_eq!(
            layout.pyproject_path,
            PathBuf::from("dist/python/pyproject.toml")
        );
        assert_eq!(
            layout.setup_script_path,
            PathBuf::from("dist/python/setup.py")
        );
        assert_eq!(
            layout.package_init_path,
            PathBuf::from("dist/python/demo_lib/__init__.py")
        );
        assert_eq!(
            layout.package_stub_path,
            PathBuf::from("dist/python/demo_lib/__init__.pyi")
        );
        assert_eq!(
            layout.typed_marker_path,
            PathBuf::from("dist/python/demo_lib/py.typed")
        );
        assert_eq!(
            layout.native_bridge_source_path,
            PathBuf::from("dist/python/demo_lib/_native.c")
        );
        assert_eq!(
            layout.packaged_shared_library_path(NativeHostPlatform::DarwinArm64, "demo_lib"),
            PathBuf::from("dist/python/demo_lib/libdemo_lib.dylib")
        );
    }

    #[test]
    fn rejects_wheel_directory_that_matches_source_root() {
        let layout =
            PythonPackageLayout::with_wheel_directory("dist/python", "dist/python", "demo_lib");

        let error = layout
            .validate_wheel_directory_safety()
            .expect_err("expected unsafe wheel directory rejection");

        assert!(matches!(
            error,
            CliError::CommandFailed { command, status: None }
                if command.contains("targets.python.wheel.output")
        ));
    }

    #[test]
    fn rejects_wheel_directory_that_matches_package_directory() {
        let layout = PythonPackageLayout::with_wheel_directory(
            "dist/python",
            "dist/python/demo_lib",
            "demo_lib",
        );

        let error = layout
            .validate_wheel_directory_safety()
            .expect_err("expected unsafe package wheel directory rejection");

        assert!(matches!(
            error,
            CliError::CommandFailed { command, status: None }
                if command.contains("generated Python package directory")
        ));
    }

    #[test]
    fn rejects_wheel_directory_nested_inside_package_directory() {
        let layout = PythonPackageLayout::with_wheel_directory(
            "dist/python",
            "dist/python/demo_lib/wheelhouse",
            "demo_lib",
        );

        let error = layout
            .validate_wheel_directory_safety()
            .expect_err("expected nested package wheel directory rejection");

        assert!(matches!(
            error,
            CliError::CommandFailed { command, status: None }
                if command.contains("must not be inside the generated Python package directory")
        ));
    }

    #[test]
    fn removes_packaged_native_libraries_without_touching_sources() {
        let unique_suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system time before unix epoch")
            .as_nanos();
        let root_directory =
            std::env::temp_dir().join(format!("boltffi-python-package-layout-{unique_suffix}"));
        let layout = PythonPackageLayout::new(&root_directory, "demo_lib");

        fs::create_dir_all(&layout.package_directory).expect("create generated package directory");
        fs::write(layout.package_directory.join("__init__.py"), []).expect("write package init");
        fs::write(layout.package_directory.join("libold.dylib"), []).expect("write stale dylib");
        fs::write(layout.package_directory.join("libother.so"), []).expect("write stale so");
        fs::write(layout.package_directory.join("demo.dll"), []).expect("write stale dll");

        layout
            .remove_packaged_native_libraries()
            .expect("remove stale packaged native libraries");

        assert!(layout.package_directory.join("__init__.py").exists());
        assert!(!layout.package_directory.join("libold.dylib").exists());
        assert!(!layout.package_directory.join("libother.so").exists());
        assert!(!layout.package_directory.join("demo.dll").exists());

        fs::remove_dir_all(root_directory).expect("cleanup generated package directory");
    }
}
