use boltffi_ffi_rules::naming::{LibraryName, Name};

use crate::ir::codec::VecLayout;
use crate::ir::ids::CallbackId;

#[derive(Clone)]
pub struct KotlinModule {
    pub package_name: String,
    pub prefix: String,
    pub extra_imports: Vec<String>,
    pub custom_types: Vec<KotlinCustomType>,
    pub enums: Vec<KotlinEnum>,
    pub data_enum_codecs: Vec<KotlinDataEnumCodec>,
    pub records: Vec<KotlinRecord>,
    pub record_readers: Vec<KotlinRecordReader>,
    pub record_writers: Vec<KotlinRecordWriter>,
    pub closures: Vec<KotlinClosureInterface>,
    pub functions: Vec<KotlinFunction>,
    pub classes: Vec<KotlinClass>,
    pub callbacks: Vec<KotlinCallbackTrait>,
    pub native: KotlinNative,
    pub api_style: KotlinApiStyle,
    pub module_object_name: Option<String>,
    pub has_async_runtime: bool,
    pub has_streams: bool,
}

#[derive(Clone, Copy)]
pub enum KotlinApiStyle {
    TopLevel,
    ModuleObject,
}

#[derive(Clone)]
pub struct KotlinCustomType {
    pub class_name: String,
    pub native_type: Option<String>,
    pub repr_kotlin_type: String,
    pub repr_size_expr: String,
    pub repr_encode_expr: String,
    pub repr_decode_expr: String,
    pub native_decode_expr: Option<String>,
    pub native_encode_expr: Option<String>,
    pub has_native_mapping: bool,
}

#[derive(Clone)]
pub struct KotlinEnum {
    pub class_name: String,
    pub variants: Vec<KotlinEnumVariant>,
    pub kind: KotlinEnumKind,
    pub c_style_value_type: Option<String>,
    pub constructors: Vec<KotlinConstructor>,
    pub methods: Vec<KotlinMethod>,
    pub doc: Option<String>,
}

#[derive(Clone, Copy)]
pub enum KotlinEnumKind {
    CStyle,
    Sealed,
    Error,
}

impl KotlinEnum {
    pub fn is_c_style(&self) -> bool {
        matches!(self.kind, KotlinEnumKind::CStyle)
    }

    pub fn is_error(&self) -> bool {
        matches!(self.kind, KotlinEnumKind::Error)
    }
}

#[derive(Clone)]
pub struct KotlinEnumVariant {
    pub name: String,
    pub tag: i128,
    pub fields: Vec<KotlinEnumField>,
    pub doc: Option<String>,
}

#[derive(Clone)]
pub struct KotlinEnumField {
    pub name: String,
    pub kotlin_type: String,
    pub overrides_message: bool,
    pub wire_decode_expr: String,
    pub wire_size_expr: String,
    pub wire_encode: String,
}

#[derive(Clone)]
pub struct KotlinDataEnumCodec {
    pub class_name: String,
    pub codec_name: String,
    pub struct_size: usize,
    pub payload_offset: usize,
    pub variants: Vec<KotlinDataEnumVariant>,
}

#[derive(Clone)]
pub struct KotlinDataEnumVariant {
    pub name: String,
    pub const_name: String,
    pub tag_value: i128,
    pub fields: Vec<KotlinDataEnumField>,
}

#[derive(Clone)]
pub struct KotlinDataEnumField {
    pub param_name: String,
    pub value_expr: String,
    pub offset: usize,
    pub getter: String,
    pub putter: String,
    pub conversion: String,
}

#[derive(Clone)]
pub struct KotlinRecord {
    pub class_name: String,
    pub fields: Vec<KotlinRecordField>,
    pub is_blittable: bool,
    pub is_error: bool,
    pub struct_size: usize,
    pub constructors: Vec<KotlinConstructor>,
    pub methods: Vec<KotlinMethod>,
    pub doc: Option<String>,
}

impl KotlinRecord {
    pub fn message_field(&self) -> Option<&KotlinRecordField> {
        self.fields.iter().find(|field| field.name == "message")
    }
}

#[derive(Clone)]
pub struct KotlinRecordField {
    pub name: String,
    pub kotlin_type: String,
    pub default_value: Option<String>,
    pub wire_decode_expr: String,
    pub wire_size_expr: String,
    pub wire_encode: String,
    pub padding_after: usize,
    pub doc: Option<String>,
}

impl KotlinRecordField {
    pub fn has_default(&self) -> bool {
        self.default_value.is_some()
    }

    pub fn default_expr(&self) -> &str {
        self.default_value.as_deref().unwrap_or("")
    }
}

#[derive(Clone)]
pub struct KotlinRecordReader {
    pub reader_name: String,
    pub class_name: String,
    pub struct_size: usize,
    pub fields: Vec<KotlinRecordReaderField>,
}

#[derive(Clone)]
pub struct KotlinRecordReaderField {
    pub name: String,
    pub const_name: String,
    pub offset: usize,
    pub getter: String,
    pub conversion: String,
}

#[derive(Clone)]
pub struct KotlinRecordWriter {
    pub writer_name: String,
    pub class_name: String,
    pub struct_size: usize,
    pub fields: Vec<KotlinRecordWriterField>,
}

#[derive(Clone)]
pub struct KotlinRecordWriterField {
    pub const_name: String,
    pub offset: usize,
    pub putter: String,
    pub value_expr: String,
}

#[derive(Clone)]
pub struct KotlinClosureInterface {
    pub interface_name: String,
    pub params: Vec<KotlinSignatureParam>,
    pub return_type: Option<String>,
}

impl KotlinClosureInterface {
    pub fn is_void_return(&self) -> bool {
        self.return_type.is_none()
    }

    pub fn return_type(&self) -> &str {
        self.return_type.as_deref().unwrap_or("Unit")
    }
}

#[derive(Clone)]
pub struct KotlinFunction {
    pub func_name: String,
    pub signature_params: Vec<KotlinSignatureParam>,
    pub return_type: Option<String>,
    pub wire_writers: Vec<KotlinWireWriter>,
    pub wire_writer_closes: Vec<String>,
    pub native_args: Vec<String>,
    pub throws: bool,
    pub err_type: String,
    pub ffi_name: String,
    pub return_is_unit: bool,
    pub return_is_direct: bool,
    pub direct_return_is_nullable: bool,
    pub return_cast: String,
    pub async_call: Option<KotlinAsyncCall>,
    pub decode_expr: String,
    pub is_blittable_return: bool,
    pub doc: Option<String>,
}

impl KotlinFunction {
    pub fn is_async(&self) -> bool {
        self.async_call.is_some()
    }
}

#[derive(Clone)]
pub struct KotlinClass {
    pub class_name: String,
    pub doc: Option<String>,
    pub prefix: String,
    pub ffi_free: String,
    pub constructors: Vec<KotlinConstructor>,
    pub methods: Vec<KotlinMethod>,
    pub streams: Vec<KotlinStream>,
    pub use_companion_methods: bool,
}

impl KotlinClass {
    pub fn has_companion_factories(&self) -> bool {
        self.constructors
            .iter()
            .any(KotlinConstructor::renders_in_companion)
    }

    pub fn has_static_methods(&self) -> bool {
        self.methods.iter().any(|m| m.is_static)
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum KotlinConstructorSurface {
    Constructor,
    CompanionFactory,
}

#[derive(Clone)]
pub struct KotlinConstructor {
    pub name: String,
    pub surface: KotlinConstructorSurface,
    pub is_fallible: bool,
    pub return_type: Option<String>,
    pub throws: bool,
    pub err_type: String,
    pub return_is_direct: bool,
    pub return_cast: String,
    pub decode_expr: String,
    pub is_blittable_return: bool,
    pub signature_params: Vec<KotlinSignatureParam>,
    pub wire_writers: Vec<KotlinWireWriter>,
    pub wire_writer_closes: Vec<String>,
    pub native_args: Vec<String>,
    pub ffi_name: String,
    pub doc: Option<String>,
}

impl KotlinConstructor {
    pub fn renders_as_constructor(&self) -> bool {
        matches!(self.surface, KotlinConstructorSurface::Constructor)
    }

    pub fn renders_in_companion(&self) -> bool {
        matches!(self.surface, KotlinConstructorSurface::CompanionFactory)
    }
}

#[derive(Clone)]
pub struct KotlinMethod {
    pub impl_: KotlinMethodImpl,
    pub is_static: bool,
}

#[derive(Clone)]
pub enum KotlinMethodImpl {
    AsyncMethod(String),
    SyncMethod(String),
}

#[derive(Clone)]
pub struct KotlinCallbackTrait {
    pub interface_name: String,
    pub handle_map_name: String,
    pub callbacks_object: String,
    pub bridge_name: String,
    pub proxy_class_name: String,
    pub supports_proxy_wrap: bool,
    pub proxy_release_name: String,
    pub proxy_methods: Vec<String>,
    pub proxy_native_methods: Vec<KotlinNativeSyncMethod>,
    pub doc: Option<String>,
    pub is_closure: bool,
    pub sync_methods: Vec<KotlinCallbackMethod>,
    pub async_methods: Vec<KotlinAsyncCallbackMethod>,
}

#[derive(Clone)]
pub struct KotlinStream {
    pub name: String,
    pub mode: KotlinStreamMode,
    pub item_type: String,
    pub pop_batch_items_expr: String,
    pub subscribe: String,
    pub poll: String,
    pub pop_batch: String,
    pub wait: String,
    pub unsubscribe: String,
    pub free: String,
}

#[derive(Clone)]
pub enum KotlinStreamMode {
    Async,
    Batch {
        class_name: String,
        method_name_pascal: String,
    },
    Callback {
        class_name: String,
        method_name_pascal: String,
    },
}

#[derive(Clone)]
pub struct KotlinCallbackMethod {
    pub name: String,
    pub ffi_name: String,
    pub params: Vec<KotlinCallbackParam>,
    pub return_info: Option<KotlinCallbackReturn>,
    pub doc: Option<String>,
}

#[derive(Clone)]
pub struct KotlinAsyncCallbackMethod {
    pub name: String,
    pub ffi_name: String,
    pub complete_name: String,
    pub fail_name: String,
    pub invoker_name: String,
    pub params: Vec<KotlinCallbackParam>,
    pub return_info: Option<KotlinCallbackReturn>,
    pub doc: Option<String>,
}

#[derive(Clone)]
pub struct KotlinCallbackParam {
    pub name: String,
    pub kotlin_type: String,
    pub jni_type: String,
    pub conversion: String,
}

#[derive(Clone)]
pub struct KotlinCallbackReturn {
    pub kotlin_type: String,
    pub jni_type: String,
    pub default_value: String,
    pub to_jni: String,
    pub to_jni_result: Option<String>,
    pub error_type: Option<String>,
    pub error_is_throwable: bool,
}

#[derive(Clone)]
pub struct KotlinNative {
    pub android_lib_name: Name<LibraryName>,
    pub desktop_jni_lib_name: Name<LibraryName>,
    pub desktop_fallback_lib_name: Name<LibraryName>,
    pub desktop_loader_bundled: bool,
    pub desktop_loader_system: bool,
    pub prefix: String,
    pub functions: Vec<KotlinNativeFunction>,
    pub wire_functions: Vec<KotlinNativeWireFunction>,
    pub classes: Vec<KotlinNativeClass>,
    pub async_callback_invokers: Vec<KotlinAsyncCallbackInvoker>,
}

#[derive(Clone)]
pub struct KotlinNativeFunction {
    pub ffi_name: String,
    pub params: Vec<KotlinNativeParam>,
    pub return_jni_type: String,
    pub async_ffi: Option<KotlinNativeAsyncFfi>,
}

#[derive(Clone)]
pub struct KotlinNativeAsyncFfi {
    pub ffi_poll: String,
    pub ffi_complete: String,
    pub ffi_cancel: String,
    pub ffi_free: String,
    pub complete_return_jni_type: String,
}

impl KotlinNativeFunction {
    pub fn is_async(&self) -> bool {
        self.async_ffi.is_some()
    }

    pub fn ffi_poll(&self) -> &str {
        self.async_ffi
            .as_ref()
            .map(|a| a.ffi_poll.as_str())
            .unwrap_or("")
    }

    pub fn ffi_complete(&self) -> &str {
        self.async_ffi
            .as_ref()
            .map(|a| a.ffi_complete.as_str())
            .unwrap_or("")
    }

    pub fn ffi_cancel(&self) -> &str {
        self.async_ffi
            .as_ref()
            .map(|a| a.ffi_cancel.as_str())
            .unwrap_or("")
    }

    pub fn ffi_free(&self) -> &str {
        self.async_ffi
            .as_ref()
            .map(|a| a.ffi_free.as_str())
            .unwrap_or("")
    }

    pub fn complete_return_jni_type(&self) -> &str {
        self.async_ffi
            .as_ref()
            .map(|a| a.complete_return_jni_type.as_str())
            .unwrap_or("")
    }
}

#[derive(Clone)]
pub struct KotlinNativeWireFunction {
    pub ffi_name: String,
    pub params: Vec<KotlinNativeParam>,
    pub return_jni_type: String,
}

#[derive(Clone)]
pub struct KotlinNativeClass {
    pub ffi_free: String,
    pub ctors: Vec<KotlinNativeCtor>,
    pub async_methods: Vec<KotlinNativeAsyncMethod>,
    pub sync_methods: Vec<KotlinNativeSyncMethod>,
    pub streams: Vec<KotlinNativeStream>,
}

#[derive(Clone)]
pub struct KotlinNativeCtor {
    pub ffi_name: String,
    pub params: Vec<KotlinNativeParam>,
}

#[derive(Clone)]
pub struct KotlinNativeAsyncMethod {
    pub ffi_name: String,
    pub ffi_poll: String,
    pub ffi_complete: String,
    pub ffi_cancel: String,
    pub ffi_free: String,
    pub include_handle: bool,
    pub params: Vec<KotlinNativeParam>,
    pub return_jni_type: String,
}

#[derive(Clone)]
pub struct KotlinNativeSyncMethod {
    pub ffi_name: String,
    pub include_handle: bool,
    pub params: Vec<KotlinNativeParam>,
    pub return_jni_type: String,
}

#[derive(Clone)]
pub struct KotlinNativeStream {
    pub subscribe: String,
    pub poll: String,
    pub pop_batch: String,
    pub wait: String,
    pub unsubscribe: String,
    pub free: String,
}

#[derive(Clone)]
pub struct KotlinNativeParam {
    pub name: String,
    pub jni_type: String,
}

#[derive(Clone)]
pub struct KotlinAsyncCallbackInvoker {
    pub name: String,
    pub result_jni_type: Option<String>,
}

impl KotlinAsyncCallbackInvoker {
    pub fn has_result(&self) -> bool {
        self.result_jni_type.is_some()
    }

    pub fn jni_type(&self) -> &str {
        self.result_jni_type.as_deref().unwrap_or("")
    }
}

#[derive(Clone)]
pub struct KotlinSignatureParam {
    pub name: String,
    pub kotlin_type: String,
}

#[derive(Clone)]
pub enum KotlinWireWriter {
    WireBuffer {
        binding_name: String,
        size_expr: String,
        encode_expr: String,
    },
    PackedBuffer {
        binding_name: String,
        pack_expr: String,
    },
}

impl KotlinWireWriter {
    pub fn binding_name(&self) -> &str {
        match self {
            Self::WireBuffer { binding_name, .. } | Self::PackedBuffer { binding_name, .. } => {
                binding_name
            }
        }
    }

    pub fn setup_code(&self) -> String {
        match self {
            Self::WireBuffer {
                binding_name,
                size_expr,
                encode_expr,
            } => format!(
                "val {binding_name} = WireWriterPool.acquire({size_expr})\n        kotlin.run {{\n            val wire = {binding_name}.writer\n            {encode_expr}\n        }}"
            ),
            Self::PackedBuffer {
                binding_name,
                pack_expr,
            } => format!("val {binding_name} = {pack_expr}"),
        }
    }

    pub fn cleanup_code(&self) -> Option<String> {
        match self {
            Self::WireBuffer { binding_name, .. } => Some(format!("{binding_name}.close()")),
            Self::PackedBuffer { .. } => None,
        }
    }

    pub fn native_buffer_expr(&self) -> String {
        match self {
            Self::WireBuffer { binding_name, .. } => format!("{binding_name}.buffer"),
            Self::PackedBuffer { binding_name, .. } => binding_name.clone(),
        }
    }
}

#[derive(Clone)]
pub struct KotlinAsyncCall {
    pub poll: String,
    pub complete: String,
    pub cancel: String,
    pub free: String,
    pub return_is_unit: bool,
    pub return_is_direct: bool,
    pub direct_return_is_nullable: bool,
    pub return_cast: String,
    pub decode_expr: String,
    pub is_blittable_return: bool,
}

#[derive(Clone)]
pub struct KotlinVecLayout {
    pub layout: VecLayout,
    pub element_type: String,
}

#[derive(Clone)]
pub struct KotlinCallbackHandle {
    pub callback_id: CallbackId,
    pub class_name: String,
}
