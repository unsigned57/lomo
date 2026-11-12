//! Test-only `MemoCreateObserver` for contracts that assert only a create's returned commit;
//! the mid-command publications are pinned by `session_ffi_contract`.

use lomo_native::{MemoCreateObserver, StoreMemoCommit};

struct ReturnedCommitOnly;

impl MemoCreateObserver for ReturnedCommitOnly {
    fn accepted(&self, _publication: StoreMemoCommit) {}

    fn withdrawn(&self, _publication: StoreMemoCommit) {}
}

pub fn returned_commit_only() -> Box<dyn MemoCreateObserver> {
    Box::new(ReturnedCommitOnly)
}
