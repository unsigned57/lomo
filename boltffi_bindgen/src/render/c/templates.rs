use askama::Template;

use super::plan::{CCallbackMethod, CEnumVariant, CField};

#[derive(Template)]
#[template(path = "c_preamble.txt", escape = "none")]
pub struct PreambleTemplate<'a> {
    pub prefix: &'a str,
    pub has_async: bool,
    pub has_streams: bool,
}

#[derive(Template)]
#[template(path = "c_composite_struct.txt", escape = "none")]
pub struct CompositeStructTemplate<'a> {
    pub name: &'a str,
    pub fields: &'a [CField],
}

#[derive(Template)]
#[template(path = "c_enum.txt", escape = "none")]
pub struct EnumTemplate<'a> {
    pub name: &'a str,
    pub tag_c_type: &'a str,
    pub variants: &'a [CEnumVariant<'a>],
}

#[derive(Template)]
#[template(path = "c_sync_function.txt", escape = "none")]
pub struct SyncFunctionTemplate<'a> {
    pub return_type: &'a str,
    pub symbol: &'a str,
    pub params: &'a str,
}

#[derive(Template)]
#[template(path = "c_async_function.txt", escape = "none")]
pub struct AsyncFunctionTemplate<'a> {
    pub symbol: &'a str,
    pub params: &'a str,
    pub poll: &'a str,
    pub complete: &'a str,
    pub complete_return_type: &'a str,
    pub cancel: &'a str,
    pub free: &'a str,
}

#[derive(Template)]
#[template(path = "c_callback_vtable.txt", escape = "none")]
pub struct CallbackVtableTemplate<'a> {
    pub vtable_type: &'a str,
    pub register_fn: &'a str,
    pub create_fn: &'a str,
    pub methods: &'a [CCallbackMethod],
}

#[derive(Template)]
#[template(path = "c_stream.txt", escape = "none")]
pub struct StreamTemplate<'a> {
    pub handle_type: &'a str,
    pub subscribe: &'a str,
    pub pop_batch_decl: &'a str,
    pub wait: &'a str,
    pub poll: &'a str,
    pub unsubscribe: &'a str,
    pub free: &'a str,
}

#[derive(Template)]
#[template(path = "c_class_destructor.txt", escape = "none")]
pub struct ClassDestructorTemplate<'a> {
    pub symbol: &'a str,
    pub handle_type: &'a str,
}
