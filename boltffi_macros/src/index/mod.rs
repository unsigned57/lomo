use boltffi_ffi_rules::cargo_graph;
use proc_macro2::Span;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

pub(crate) mod callback_traits;
pub(crate) mod class_types;
pub(crate) mod custom_types;
pub(crate) mod data_types;
mod path_resolver;
mod reexports;
mod source_tree;
pub(crate) mod type_paths;

pub(crate) use path_resolver::PathResolver;
pub(crate) use source_tree::{IndexedCrateSource, ModulePath, SourceModule, SourceTree};

#[derive(Clone)]
pub(crate) struct CrateIndex {
    custom_types: custom_types::CustomTypeRegistry,
    class_types: class_types::ClassTypeRegistry,
    data_types: data_types::DataTypeRegistry,
    callback_traits: callback_traits::CallbackTraitRegistry,
    path_resolver: PathResolver,
}

static CRATE_INDEX_CACHE: OnceLock<Mutex<HashMap<PathBuf, CrateIndex>>> = OnceLock::new();

impl CrateIndex {
    pub(crate) fn for_current_crate() -> syn::Result<Self> {
        let source_tree = SourceTree::for_current_crate()?;
        let manifest_dir = source_tree.manifest_dir().to_path_buf();

        let cache = CRATE_INDEX_CACHE.get_or_init(|| Mutex::new(HashMap::new()));
        if let Some(crate_index) = cache
            .lock()
            .map_err(|_| syn::Error::new(Span::call_site(), "crate index lock poisoned"))?
            .get(&manifest_dir)
            .cloned()
        {
            return Ok(crate_index);
        }

        let source_modules = source_tree.modules()?;
        let path_resolver = PathResolver::build(&source_modules);
        let indexed_sources = Self::indexed_sources(source_modules.clone(), &manifest_dir)?;
        let crate_index = Self {
            custom_types: custom_types::build_custom_type_registry(&indexed_sources)?,
            class_types: class_types::build_class_type_registry(
                &indexed_sources,
                path_resolver.clone(),
            )?,
            data_types: data_types::build_data_type_registry(
                &indexed_sources,
                path_resolver.clone(),
            )?,
            callback_traits: callback_traits::build_callback_trait_registry(&indexed_sources)?,
            path_resolver,
        };

        cache
            .lock()
            .map_err(|_| syn::Error::new(Span::call_site(), "crate index lock poisoned"))?
            .insert(manifest_dir, crate_index.clone());

        Ok(crate_index)
    }

    fn indexed_sources(
        source_modules: Vec<SourceModule>,
        manifest_dir: &Path,
    ) -> syn::Result<Vec<IndexedCrateSource>> {
        let dependency_sources = cargo_graph::PackageGraph::load(manifest_dir)
            .map_err(|error| syn::Error::new(Span::call_site(), error.to_string()))?
            .map(|graph| graph.reachable_exported_dependencies(graph.root_id()))
            .unwrap_or_default()
            .into_iter()
            .map(|package| {
                Ok(IndexedCrateSource::dependency(
                    package.root_path(),
                    SourceTree::for_manifest_dir(package.manifest_dir())?.modules()?,
                ))
            })
            .collect::<syn::Result<Vec<_>>>()?;

        Ok(std::iter::once(IndexedCrateSource::current(source_modules))
            .chain(dependency_sources)
            .collect())
    }

    pub(crate) fn custom_types(&self) -> &custom_types::CustomTypeRegistry {
        &self.custom_types
    }

    pub(crate) fn data_types(&self) -> &data_types::DataTypeRegistry {
        &self.data_types
    }

    pub(crate) fn callback_traits(&self) -> &callback_traits::CallbackTraitRegistry {
        &self.callback_traits
    }

    pub(crate) fn class_types(&self) -> &class_types::ClassTypeRegistry {
        &self.class_types
    }

    pub(crate) fn path_resolver(&self) -> &PathResolver {
        &self.path_resolver
    }
}
