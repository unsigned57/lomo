use crate::{
    ir::CustomTypeDef,
    render::dart::{DartCustomType, DartType},
};

impl<'a> super::DartLowerer<'a> {
    fn lower_one_custom_type(&self, custom: &CustomTypeDef) -> DartCustomType {
        DartCustomType {
            name: custom.id.to_string(),
            ty: DartType::from_type_expr(&custom.repr, &self.ffi.catalog),
        }
    }

    pub(super) fn lower_custom_types(&self) -> Vec<DartCustomType> {
        self.ffi
            .catalog
            .all_custom_types()
            .map(|t| self.lower_one_custom_type(t))
            .collect()
    }
}
