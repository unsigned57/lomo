//! Bridge contract trait.

use boltffi_binding::Surface;

use crate::core::BridgeCapabilities;

pub(crate) mod sealed {
    pub trait BridgeBackend {}
    pub trait BridgeContract {}
    pub trait BridgeStack {}
    pub trait HostBackend {}
}

/// Contract produced by a bridge stack.
#[allow(private_bounds)]
pub trait BridgeContract: sealed::BridgeContract {
    /// Binding surface this bridge contract serves.
    type Surface: Surface;

    /// Returns the bridge capabilities this contract provides.
    fn capabilities(&self) -> &BridgeCapabilities;
}
