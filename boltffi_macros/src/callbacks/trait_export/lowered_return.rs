use boltffi_ffi_rules::transport::{
    DirectBufferReturnMethod, EncodedReturnStrategy, ReturnInvocationContext, ReturnPlatform,
    ValueReturnMethod, ValueReturnStrategy,
};
use syn::Type;

use crate::lowering::returns::model::{ResolvedReturn, ReturnLoweringContext};

pub(super) struct LoweredCallbackReturn {
    resolved_return: ResolvedReturn,
}

impl LoweredCallbackReturn {
    pub(super) fn new(ty: &Type, return_lowering: &ReturnLoweringContext<'_>) -> Self {
        Self {
            resolved_return: return_lowering.lower_type(ty),
        }
    }

    pub(super) fn value_return_method(
        &self,
        context: ReturnInvocationContext,
        platform: ReturnPlatform,
    ) -> ValueReturnMethod {
        self.resolved_return.value_return_method(context, platform)
    }

    pub(super) fn encoded_return_strategy(&self) -> Option<EncodedReturnStrategy> {
        self.resolved_return.encoded_return_strategy()
    }

    pub(super) fn direct_buffer_return_method(
        &self,
        context: ReturnInvocationContext,
        platform: ReturnPlatform,
    ) -> Option<DirectBufferReturnMethod> {
        self.resolved_return
            .direct_buffer_return_method(context, platform)
    }

    pub(super) fn uses_wire_payload(&self) -> bool {
        !matches!(
            self.resolved_return.value_return_strategy(),
            ValueReturnStrategy::Scalar(_)
        )
    }
}
