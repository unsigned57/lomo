use boltffi_ast::FnSig;

use crate::{ClosureSignature, ImportModule, ImportSymbol, NativeSymbol, SymbolName, wasm32};

use super::{
    LowerError,
    symbol::{self, SymbolAllocator},
};

pub fn incoming_registration(
    closure: &FnSig,
) -> Result<wasm32::IncomingClosureRegistration, LowerError> {
    let module = ImportModule::parse(symbol::WASM_CALLBACK_IMPORT_MODULE.to_owned())?;
    let signature = ClosureSignature::from_fn_signature(closure).symbol_part();
    let call = import_symbol(module.clone(), &signature, "call")?;
    let free = import_symbol(module, &signature, "free")?;
    Ok(wasm32::IncomingClosureRegistration::new(call, free))
}

pub fn outgoing_registration(
    allocator: &mut SymbolAllocator,
    closure: &FnSig,
) -> Result<wasm32::OutgoingClosureRegistration, LowerError> {
    let signature = ClosureSignature::from_fn_signature(closure).symbol_part();
    let group_id = allocator.next_group_id();
    let call = export_symbol(allocator, group_id, &signature, "call")?;
    let free = export_symbol(allocator, group_id, &signature, "free")?;
    Ok(wasm32::OutgoingClosureRegistration::new(call, free))
}

fn import_symbol(
    module: ImportModule,
    signature: &str,
    action: &str,
) -> Result<ImportSymbol, LowerError> {
    let name = symbol::wasm_callback_import_name("closure", signature, action);
    Ok(ImportSymbol::new(module, SymbolName::parse(name)?))
}

fn export_symbol(
    allocator: &mut SymbolAllocator,
    group_id: u32,
    signature: &str,
    action: &str,
) -> Result<NativeSymbol, LowerError> {
    allocator.mint(symbol::wasm_closure_export_name(
        group_id, signature, action,
    ))
}

#[cfg(test)]
mod tests {
    use boltffi_ast::{FnSig, Primitive as AstPrimitive, ReturnDef, TypeExpr};

    use super::*;

    fn closure(parameters: Vec<TypeExpr>, returns: ReturnDef) -> FnSig {
        FnSig::new(parameters, returns)
    }

    #[test]
    fn registration_uses_closure_signature_import_names() {
        let closure = closure(
            vec![TypeExpr::Primitive(AstPrimitive::F64)],
            ReturnDef::Void,
        );
        let registration = incoming_registration(&closure).expect("valid closure registration");

        assert_eq!(
            registration.call().module().as_str(),
            symbol::WASM_CALLBACK_IMPORT_MODULE
        );
        assert_eq!(
            registration.call().name().as_str(),
            "__boltffi_callback_closure____closure__f64_call"
        );
        assert_eq!(
            registration.free().name().as_str(),
            "__boltffi_callback_closure____closure__f64_free"
        );
    }

    #[test]
    fn outgoing_registration_uses_closure_signature_export_names() {
        let closure = closure(
            vec![TypeExpr::Primitive(AstPrimitive::F64)],
            ReturnDef::Void,
        );
        let mut allocator = SymbolAllocator::new();
        let registration =
            outgoing_registration(&mut allocator, &closure).expect("valid closure registration");

        assert_eq!(
            registration.call().name().as_str(),
            "boltffi_closure_0____closure__f64_call"
        );
        assert_eq!(
            registration.free().name().as_str(),
            "boltffi_closure_0____closure__f64_free"
        );
    }
}
