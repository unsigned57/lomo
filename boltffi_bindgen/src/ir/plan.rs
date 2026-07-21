use boltffi_ffi_rules::naming::{GlobalSymbol, Name, VtableField};
use boltffi_ffi_rules::transport::{
    EncodedReturnStrategy, ParamContract, ScalarReturnStrategy, ValueReturnStrategy,
};

use crate::ir::codec::CodecPlan;
use crate::ir::ids::{CallbackId, ClassId, EnumId, FieldName, ParamName, RecordId};
use crate::ir::types::PrimitiveType;

pub type PointerType = PrimitiveType;

#[derive(Debug, Clone)]
pub struct CallPlan {
    pub target: CallTarget,
    pub params: Vec<ParamPlan>,
    pub kind: CallPlanKind,
}

#[derive(Debug, Clone)]
pub enum CallTarget {
    GlobalSymbol(Name<GlobalSymbol>),
    VtableField(Name<VtableField>),
}

#[derive(Debug, Clone)]
pub enum CallPlanKind {
    Sync { returns: ReturnPlan },
    Async { async_plan: AsyncPlan },
}

#[derive(Debug, Clone)]
pub struct AsyncPlan {
    pub completion_callback: CompletionCallback,
    pub result: ReturnPlan,
}

#[derive(Debug, Clone)]
pub struct CompletionCallback {
    pub param_name: ParamName,
    pub abi_type: AbiType,
}

#[derive(Debug, Clone)]
pub struct ParamPlan {
    pub name: ParamName,
    pub contract: ParamContract,
    pub transport: Transport,
    pub mutability: Mutability,
}

#[derive(Debug, Clone)]
pub enum Transport {
    Scalar(ScalarOrigin),
    Composite(CompositeLayout),
    Span(SpanContent),
    Handle {
        class_id: ClassId,
        nullable: bool,
    },
    Callback {
        callback_id: CallbackId,
        style: CallbackStyle,
        nullable: bool,
    },
}

#[derive(Debug, Clone)]
pub enum SpanContent {
    Scalar(ScalarOrigin),
    Composite(CompositeLayout),
    Utf8,
    Encoded(CodecPlan),
}

#[derive(Debug, Clone)]
pub enum ScalarOrigin {
    Primitive(PrimitiveType),
    CStyleEnum {
        tag_type: PrimitiveType,
        enum_id: EnumId,
    },
}

impl ScalarOrigin {
    pub fn primitive(&self) -> PrimitiveType {
        match self {
            Self::Primitive(p) => *p,
            Self::CStyleEnum { tag_type, .. } => *tag_type,
        }
    }
}

impl Transport {
    pub fn value_return_strategy(&self) -> ValueReturnStrategy {
        match self {
            Self::Scalar(ScalarOrigin::Primitive(_)) => {
                ValueReturnStrategy::Scalar(ScalarReturnStrategy::PrimitiveValue)
            }
            Self::Scalar(ScalarOrigin::CStyleEnum { .. }) => {
                ValueReturnStrategy::Scalar(ScalarReturnStrategy::CStyleEnumTag)
            }
            Self::Composite(_) => ValueReturnStrategy::CompositeValue,
            Self::Span(SpanContent::Scalar(_)) | Self::Span(SpanContent::Composite(_)) => {
                ValueReturnStrategy::Buffer(EncodedReturnStrategy::DirectVec)
            }
            Self::Span(SpanContent::Utf8) => {
                ValueReturnStrategy::Buffer(EncodedReturnStrategy::Utf8String)
            }
            Self::Span(SpanContent::Encoded(_)) => {
                ValueReturnStrategy::Buffer(EncodedReturnStrategy::WireEncoded)
            }
            Self::Handle { .. } => ValueReturnStrategy::ObjectHandle,
            Self::Callback { .. } => ValueReturnStrategy::CallbackHandle,
        }
    }
}

#[derive(Debug, Clone)]
pub struct CompositeLayout {
    pub record_id: RecordId,
    pub total_size: usize,
    pub fields: Vec<CompositeField>,
}

#[derive(Debug, Clone)]
pub struct CompositeField {
    pub name: FieldName,
    pub offset: usize,
    pub primitive: PrimitiveType,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AbiType {
    Void,
    Bool,
    I8,
    U8,
    I16,
    U16,
    I32,
    U32,
    I64,
    U64,
    ISize,
    USize,
    F32,
    F64,
    Pointer(PointerType),
    OwnedBuffer,
    InlineCallbackFn {
        params: Vec<AbiType>,
        return_type: Box<AbiType>,
    },
    Handle(ClassId),
    CallbackHandle,
    Struct(RecordId),
}

impl From<PrimitiveType> for AbiType {
    fn from(p: PrimitiveType) -> Self {
        match p {
            PrimitiveType::Bool => Self::Bool,
            PrimitiveType::I8 => Self::I8,
            PrimitiveType::U8 => Self::U8,
            PrimitiveType::I16 => Self::I16,
            PrimitiveType::U16 => Self::U16,
            PrimitiveType::I32 => Self::I32,
            PrimitiveType::U32 => Self::U32,
            PrimitiveType::I64 => Self::I64,
            PrimitiveType::U64 => Self::U64,
            PrimitiveType::ISize => Self::ISize,
            PrimitiveType::USize => Self::USize,
            PrimitiveType::F32 => Self::F32,
            PrimitiveType::F64 => Self::F64,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mutability {
    Shared,
    Mutable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CallbackStyle {
    ImplTrait,
    BoxedDyn,
}

#[derive(Debug, Clone)]
pub enum ReturnPlan {
    Void,
    Value(Transport),
    Fallible { ok: Transport, err_codec: CodecPlan },
}
