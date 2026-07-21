use crate::{core::Result, target::python::syntax::Identifier};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct NativeFutureMethods {
    start: Identifier,
    poll: Identifier,
    complete: Identifier,
    cancel: Identifier,
    free: Identifier,
    panic_message: Identifier,
}

impl NativeFutureMethods {
    pub fn new(start: Identifier) -> Result<Self> {
        Ok(Self {
            poll: Self::method(&start, "poll")?,
            complete: Self::method(&start, "complete")?,
            cancel: Self::method(&start, "cancel")?,
            free: Self::method(&start, "free")?,
            panic_message: Self::method(&start, "panic_message")?,
            start,
        })
    }

    pub fn start(&self) -> &Identifier {
        &self.start
    }

    pub fn poll(&self) -> &Identifier {
        &self.poll
    }

    pub fn complete(&self) -> &Identifier {
        &self.complete
    }

    pub fn cancel(&self) -> &Identifier {
        &self.cancel
    }

    pub fn free(&self) -> &Identifier {
        &self.free
    }

    pub fn panic_message(&self) -> &Identifier {
        &self.panic_message
    }

    fn method(start: &Identifier, suffix: &'static str) -> Result<Identifier> {
        Identifier::parse(format!("{start}__{suffix}"))
    }
}
