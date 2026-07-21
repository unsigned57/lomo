use proc_macro2::Span;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Clone)]
pub(crate) struct SourceTree {
    manifest_dir: PathBuf,
    src_root: PathBuf,
}

#[derive(Clone)]
pub(crate) struct IndexedCrateSource {
    root_path: Vec<String>,
    modules: Vec<SourceModule>,
}

#[derive(Clone)]
pub(crate) struct SourceFile {
    path: PathBuf,
}

#[derive(Clone)]
pub(crate) struct SourceModule {
    module_path: ModulePath,
    syntax: syn::File,
}

#[derive(Clone, Default)]
pub(crate) struct ModulePath {
    segments: Vec<String>,
}

impl SourceTree {
    pub(crate) fn for_current_crate() -> syn::Result<Self> {
        let manifest_dir = env::var("CARGO_MANIFEST_DIR")
            .map(PathBuf::from)
            .map_err(|_| syn::Error::new(Span::call_site(), "CARGO_MANIFEST_DIR not set"))?;
        Self::for_manifest_dir(manifest_dir)
    }

    pub(crate) fn for_manifest_dir(manifest_dir: impl Into<PathBuf>) -> syn::Result<Self> {
        let manifest_dir = manifest_dir.into();
        Ok(Self {
            manifest_dir: manifest_dir.clone(),
            src_root: manifest_dir.join("src"),
        })
    }

    pub(crate) fn manifest_dir(&self) -> &Path {
        &self.manifest_dir
    }

    pub(crate) fn rust_files(&self) -> syn::Result<Vec<SourceFile>> {
        let mut rust_files = Vec::new();
        Self::collect_rust_files(&self.src_root, &mut rust_files)?;
        Ok(rust_files
            .into_iter()
            .map(|path| SourceFile { path })
            .collect())
    }

    pub(crate) fn modules(&self) -> syn::Result<Vec<SourceModule>> {
        self.rust_files()?
            .into_iter()
            .map(|source_file| {
                Ok(SourceModule {
                    module_path: source_file.module_path(self)?,
                    syntax: source_file.syntax()?,
                })
            })
            .collect()
    }

    fn collect_rust_files(directory: &Path, rust_files: &mut Vec<PathBuf>) -> syn::Result<()> {
        let entries = fs::read_dir(directory).map_err(|error| {
            syn::Error::new(
                Span::call_site(),
                format!("read_dir {}: {}", directory.display(), error),
            )
        })?;

        entries
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .try_for_each(|path| {
                if path.is_dir() {
                    return Self::collect_rust_files(&path, rust_files);
                }

                path.extension()
                    .is_some_and(|extension| extension == "rs")
                    .then(|| rust_files.push(path));
                Ok(())
            })
    }
}

impl IndexedCrateSource {
    pub(crate) fn current(modules: Vec<SourceModule>) -> Self {
        Self {
            root_path: Vec::new(),
            modules,
        }
    }

    pub(crate) fn dependency(root_path: Vec<String>, modules: Vec<SourceModule>) -> Self {
        Self { root_path, modules }
    }

    pub(crate) fn root_path(&self) -> &[String] {
        &self.root_path
    }

    pub(crate) fn modules(&self) -> &[SourceModule] {
        &self.modules
    }
}

impl SourceFile {
    pub(crate) fn syntax(&self) -> syn::Result<syn::File> {
        let content = fs::read_to_string(&self.path).map_err(|error| {
            syn::Error::new(
                Span::call_site(),
                format!("read {}: {}", self.path.display(), error),
            )
        })?;
        syn::parse_file(&content)
    }

    pub(crate) fn module_path(&self, source_tree: &SourceTree) -> syn::Result<ModulePath> {
        let relative_path = self
            .path
            .strip_prefix(&source_tree.src_root)
            .map_err(|_| syn::Error::new(Span::call_site(), "path not under src"))?;
        let mut path_parts = relative_path
            .components()
            .map(|component| component.as_os_str().to_string_lossy().to_string())
            .collect::<Vec<_>>();

        let file_name = path_parts.pop().unwrap_or_default();
        let mut module_segments = path_parts;

        match file_name.as_str() {
            "lib.rs" | "main.rs" | "mod.rs" => {}
            _ if file_name.ends_with(".rs") => {
                module_segments.push(file_name.trim_end_matches(".rs").to_string());
            }
            _ => {}
        }

        Ok(ModulePath {
            segments: module_segments
                .into_iter()
                .filter(|segment| !segment.is_empty())
                .collect(),
        })
    }
}

impl SourceModule {
    pub(crate) fn module_path(&self) -> &ModulePath {
        &self.module_path
    }

    pub(crate) fn syntax(&self) -> &syn::File {
        &self.syntax
    }
}

impl ModulePath {
    pub(crate) fn from_syn_path(path: &syn::Path) -> Self {
        let mut segments = path
            .segments
            .iter()
            .map(|segment| segment.ident.to_string())
            .collect::<Vec<_>>();
        if let Some(first_segment) = segments.first()
            && matches!(first_segment.as_str(), "crate" | "self" | "super")
        {
            segments.remove(0);
        }
        Self { segments }
    }

    pub(crate) fn as_strings(&self) -> &[String] {
        &self.segments
    }

    pub(crate) fn into_strings(self) -> Vec<String> {
        self.segments
    }

    pub(crate) fn into_idents(self) -> Vec<syn::Ident> {
        self.segments
            .into_iter()
            .map(|segment| syn::Ident::new(&segment, Span::call_site()))
            .collect()
    }
}
