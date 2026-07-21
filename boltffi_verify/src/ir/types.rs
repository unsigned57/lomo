use super::var::{VarId, VarName};
use crate::source::SourceSpan;

#[derive(Debug, Clone)]
pub struct VerifyUnit {
    pub name: String,
    pub kind: UnitKind,
    pub params: Vec<Param>,
    pub body: Vec<Statement>,
    pub span: SourceSpan,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum UnitKind {
    FreeFunction,
    Method { class_name: String },
    Initializer { class_name: String },
    Deinitializer { class_name: String },
    Closure,
}

impl UnitKind {
    pub fn is_method(&self) -> bool {
        matches!(self, Self::Method { .. })
    }

    pub fn is_initializer(&self) -> bool {
        matches!(self, Self::Initializer { .. })
    }

    pub fn is_deinitializer(&self) -> bool {
        matches!(self, Self::Deinitializer { .. })
    }

    pub fn class_name(&self) -> Option<&str> {
        match self {
            Self::Method { class_name }
            | Self::Initializer { class_name }
            | Self::Deinitializer { class_name } => Some(class_name),
            Self::FreeFunction | Self::Closure => None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Param {
    pub name: VarName,
    pub var_id: VarId,
    pub param_type: String,
    pub span: SourceSpan,
}

#[derive(Debug, Clone)]
pub enum Statement {
    LetBinding {
        var_id: VarId,
        name: VarName,
        value: Expression,
        span: SourceSpan,
    },

    VarBinding {
        var_id: VarId,
        name: VarName,
        value: Option<Expression>,
        span: SourceSpan,
    },

    Assignment {
        target: VarId,
        value: Expression,
        span: SourceSpan,
    },

    Defer {
        body: Vec<Statement>,
        span: SourceSpan,
    },

    FfiCall {
        function_name: String,
        arguments: Vec<Expression>,
        result_var: Option<VarId>,
        out_params: Vec<VarId>,
        span: SourceSpan,
    },

    StatusCheck {
        status_var: VarId,
        check_kind: StatusCheckKind,
        span: SourceSpan,
    },

    Allocate {
        target_var: VarId,
        pointer_type: PointerType,
        element_type: String,
        capacity: Expression,
        span: SourceSpan,
    },

    Deallocate {
        pointer_var: VarId,
        span: SourceSpan,
    },

    PassRetained {
        object_var: VarId,
        opaque_var: VarId,
        span: SourceSpan,
    },

    TakeRetainedValue {
        opaque_var: VarId,
        result_var: VarId,
        span: SourceSpan,
    },

    Release {
        opaque_var: VarId,
        span: SourceSpan,
    },

    BufferAccess {
        buffer_var: VarId,
        access_kind: BufferKind,
        body: Vec<Statement>,
        span: SourceSpan,
    },

    IfStatement {
        condition: Expression,
        then_branch: Vec<Statement>,
        else_branch: Option<Vec<Statement>>,
        span: SourceSpan,
    },

    Return {
        value: Option<Expression>,
        span: SourceSpan,
    },

    Expression {
        expression: Expression,
        span: SourceSpan,
    },

    Other {
        description: String,
        span: SourceSpan,
    },
}

impl Statement {
    pub fn span(&self) -> &SourceSpan {
        match self {
            Self::LetBinding { span, .. }
            | Self::VarBinding { span, .. }
            | Self::Assignment { span, .. }
            | Self::Defer { span, .. }
            | Self::FfiCall { span, .. }
            | Self::StatusCheck { span, .. }
            | Self::Allocate { span, .. }
            | Self::Deallocate { span, .. }
            | Self::PassRetained { span, .. }
            | Self::TakeRetainedValue { span, .. }
            | Self::Release { span, .. }
            | Self::BufferAccess { span, .. }
            | Self::IfStatement { span, .. }
            | Self::Return { span, .. }
            | Self::Expression { span, .. }
            | Self::Other { span, .. } => span,
        }
    }

    pub fn is_memory_operation(&self) -> bool {
        matches!(
            self,
            Self::Allocate { .. }
                | Self::Deallocate { .. }
                | Self::PassRetained { .. }
                | Self::TakeRetainedValue { .. }
                | Self::Release { .. }
        )
    }

    pub fn is_control_flow(&self) -> bool {
        matches!(self, Self::IfStatement { .. } | Self::Return { .. })
    }

    pub fn defined_vars(&self) -> Vec<VarId> {
        match self {
            Self::LetBinding { var_id, .. } | Self::VarBinding { var_id, .. } => vec![*var_id],
            Self::Allocate { target_var, .. } => vec![*target_var],
            Self::TakeRetainedValue { result_var, .. } => vec![*result_var],
            Self::PassRetained { opaque_var, .. } => vec![*opaque_var],
            Self::FfiCall {
                result_var,
                out_params,
                ..
            } => result_var
                .iter()
                .copied()
                .chain(out_params.iter().copied())
                .collect(),
            _ => vec![],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StatusCheckKind {
    TryCheckStatus,
    EnsureOk,
    IfNotZero,
    GuardStatus,
}

impl StatusCheckKind {
    pub fn is_throwing(&self) -> bool {
        matches!(self, Self::TryCheckStatus | Self::GuardStatus)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PointerType {
    Mutable,
    Immutable,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BufferKind {
    WithCString { closure_param: VarName },
    WithUnsafeBytes { closure_param: VarName },
    WithUnsafeBufferPointer { closure_param: VarName },
    WithUnsafeMutableBufferPointer { closure_param: VarName },
}

impl BufferKind {
    pub fn closure_param(&self) -> &VarName {
        match self {
            Self::WithCString { closure_param }
            | Self::WithUnsafeBytes { closure_param }
            | Self::WithUnsafeBufferPointer { closure_param }
            | Self::WithUnsafeMutableBufferPointer { closure_param } => closure_param,
        }
    }

    pub fn is_mutable(&self) -> bool {
        matches!(self, Self::WithUnsafeMutableBufferPointer { .. })
    }
}

#[derive(Debug, Clone)]
pub enum Expression {
    Variable(VarId),
    Literal(Literal),
    FfiCallExpr {
        function_name: String,
        arguments: Vec<Expression>,
    },
    FieldAccess {
        base: Box<Expression>,
        field_name: String,
    },
    MethodCall {
        receiver: Box<Expression>,
        method_name: String,
        arguments: Vec<Expression>,
    },
    BinaryOperation {
        left: Box<Expression>,
        operator: BinaryOp,
        right: Box<Expression>,
    },
    UnaryOperation {
        operator: String,
        operand: Box<Expression>,
    },
    Cast {
        expression: Box<Expression>,
        target_type: String,
    },
    ArrayLiteral {
        elements: Vec<Expression>,
    },
    Closure {
        params: Vec<VarName>,
        body: Vec<Statement>,
    },
    AddressOf(VarId),
    Dereference(Box<Expression>),
    Other {
        description: String,
    },
}

impl Expression {
    pub fn is_ffi_call(&self) -> bool {
        matches!(self, Self::FfiCallExpr { .. })
    }

    pub fn referenced_vars(&self) -> Vec<VarId> {
        match self {
            Self::Variable(var_id) => vec![*var_id],
            Self::AddressOf(var_id) => vec![*var_id],
            Self::FieldAccess { base, .. } => base.referenced_vars(),
            Self::MethodCall {
                receiver,
                arguments,
                ..
            } => receiver
                .referenced_vars()
                .into_iter()
                .chain(arguments.iter().flat_map(|arg| arg.referenced_vars()))
                .collect(),
            Self::BinaryOperation { left, right, .. } => left
                .referenced_vars()
                .into_iter()
                .chain(right.referenced_vars())
                .collect(),
            Self::UnaryOperation { operand, .. } => operand.referenced_vars(),
            Self::Cast { expression, .. } => expression.referenced_vars(),
            Self::Dereference(inner) => inner.referenced_vars(),
            Self::FfiCallExpr { arguments, .. } => arguments
                .iter()
                .flat_map(|arg| arg.referenced_vars())
                .collect(),
            Self::ArrayLiteral { elements } => {
                elements.iter().flat_map(|e| e.referenced_vars()).collect()
            }
            Self::Closure { body, .. } => body
                .iter()
                .flat_map(|stmt| match stmt {
                    Statement::Expression { expression, .. } => expression.referenced_vars(),
                    _ => vec![],
                })
                .collect(),
            Self::Literal(_) | Self::Other { .. } => vec![],
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Literal {
    Integer(i64),
    Float(f64),
    String(String),
    Bool(bool),
    Nil,
}

impl Literal {
    pub fn as_integer(&self) -> Option<i64> {
        match self {
            Self::Integer(value) => Some(*value),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BinaryOp {
    Add,
    Subtract,
    Multiply,
    Divide,
    Modulo,
    Equal,
    NotEqual,
    LessThan,
    LessThanOrEqual,
    GreaterThan,
    GreaterThanOrEqual,
    LogicalAnd,
    LogicalOr,
    BitwiseAnd,
    BitwiseOr,
    BitwiseXor,
}

impl BinaryOp {
    pub fn is_comparison(&self) -> bool {
        matches!(
            self,
            Self::Equal
                | Self::NotEqual
                | Self::LessThan
                | Self::LessThanOrEqual
                | Self::GreaterThan
                | Self::GreaterThanOrEqual
        )
    }

    pub fn is_logical(&self) -> bool {
        matches!(self, Self::LogicalAnd | Self::LogicalOr)
    }

    pub fn is_arithmetic(&self) -> bool {
        matches!(
            self,
            Self::Add | Self::Subtract | Self::Multiply | Self::Divide | Self::Modulo
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::SourceFile;
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_unit_kind_class_name() {
        let method = UnitKind::Method {
            class_name: "MyClass".to_string(),
        };
        assert_eq!(method.class_name(), Some("MyClass"));

        let free_fn = UnitKind::FreeFunction;
        assert_eq!(free_fn.class_name(), None);
    }

    #[test]
    fn test_statement_defined_vars() {
        let var_id = VarId::new(0);
        let stmt = Statement::LetBinding {
            var_id,
            name: "x".into(),
            value: Expression::Literal(Literal::Integer(42)),
            span: test_span(),
        };

        assert_eq!(stmt.defined_vars(), vec![var_id]);
    }
}
