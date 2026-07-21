use syn::{Item, UseTree};

#[derive(Clone)]
pub(crate) struct ReExport {
    target: Vec<String>,
    alias: String,
}

impl ReExport {
    pub(crate) fn from_item(item: &Item) -> Vec<Self> {
        let Item::Use(item_use) = item else {
            return Vec::new();
        };
        if !matches!(item_use.vis, syn::Visibility::Public(_)) {
            return Vec::new();
        }
        Self::from_tree(Vec::new(), &item_use.tree)
    }

    pub(crate) fn target(&self) -> &[String] {
        &self.target
    }

    pub(crate) fn alias(&self) -> &str {
        &self.alias
    }

    fn from_tree(prefix: Vec<String>, use_tree: &UseTree) -> Vec<Self> {
        match use_tree {
            UseTree::Path(path) => {
                let mut next_prefix = prefix;
                next_prefix.push(path.ident.to_string());
                Self::from_tree(next_prefix, &path.tree)
            }
            UseTree::Name(name) => {
                let mut target = prefix;
                target.push(name.ident.to_string());
                vec![Self {
                    target: Self::normalize_target(target),
                    alias: name.ident.to_string(),
                }]
            }
            UseTree::Rename(rename) => {
                let mut target = prefix;
                target.push(rename.ident.to_string());
                vec![Self {
                    target: Self::normalize_target(target),
                    alias: rename.rename.to_string(),
                }]
            }
            UseTree::Group(group) => group
                .items
                .iter()
                .flat_map(|item| Self::from_tree(prefix.clone(), item))
                .collect(),
            UseTree::Glob(_) => Vec::new(),
        }
    }

    fn normalize_target(mut target: Vec<String>) -> Vec<String> {
        if target
            .first()
            .is_some_and(|segment| matches!(segment.as_str(), "crate" | "self" | "super"))
        {
            target.remove(0);
        }
        target
    }
}
