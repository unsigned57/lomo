use boltffi_binding::NativeSymbol;

#[derive(Clone, Debug)]
pub struct PollHandleSymbols {
    pub start: NativeSymbol,
    pub poll: NativeSymbol,
    pub complete: NativeSymbol,
    pub cancel: NativeSymbol,
    pub free: NativeSymbol,
    pub panic_message: NativeSymbol,
}

impl PollHandleSymbols {
    pub fn new(
        start: &NativeSymbol,
        poll: &NativeSymbol,
        complete: &NativeSymbol,
        cancel: &NativeSymbol,
        free: &NativeSymbol,
        panic_message: &NativeSymbol,
    ) -> Self {
        Self {
            start: start.clone(),
            poll: poll.clone(),
            complete: complete.clone(),
            cancel: cancel.clone(),
            free: free.clone(),
            panic_message: panic_message.clone(),
        }
    }
}
