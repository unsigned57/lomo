use super::effects::{Capacity, Effect, EffectTrace};
use super::flow::{BranchDivergence, BranchState, check_branch_consistency, merge_branch_states};
use crate::ir::{Expression, Literal, Statement, VarId, VerifyUnit};
use crate::source::SourceSpan;

pub struct EffectCollector {
    trace: EffectTrace,
    deferred: Vec<DeferredBlock>,
    branch_state: BranchState,
    divergences: Vec<BranchDivergence>,
}

struct DeferredBlock {
    effects: Vec<(Effect, SourceSpan)>,
}

pub struct CollectionResult {
    pub trace: EffectTrace,
    pub divergences: Vec<BranchDivergence>,
}

impl EffectCollector {
    pub fn new() -> Self {
        Self {
            trace: EffectTrace::new(),
            deferred: Vec::new(),
            branch_state: BranchState::new(),
            divergences: Vec::new(),
        }
    }

    pub fn collect(unit: &VerifyUnit) -> EffectTrace {
        Self::collect_with_flow(unit).trace
    }

    pub fn collect_with_flow(unit: &VerifyUnit) -> CollectionResult {
        let mut collector = Self::new();
        collector.visit_statements(&unit.body);
        collector.execute_all_defers();
        CollectionResult {
            trace: collector.trace,
            divergences: collector.divergences,
        }
    }

    fn visit_statements(&mut self, statements: &[Statement]) {
        statements
            .iter()
            .for_each(|stmt| self.visit_statement(stmt));
    }

    fn visit_statement(&mut self, stmt: &Statement) {
        match stmt {
            Statement::Allocate {
                target_var,
                element_type,
                capacity,
                span,
                ..
            } => {
                self.branch_state.allocate(*target_var);
                self.trace.push(
                    Effect::Allocate {
                        pointer: *target_var,
                        element_type: element_type.clone(),
                        capacity: self.expr_to_capacity(capacity),
                    },
                    span.clone(),
                );
            }

            Statement::Deallocate { pointer_var, span } => {
                self.branch_state.free(*pointer_var);
                self.trace.push(
                    Effect::Free {
                        pointer: *pointer_var,
                    },
                    span.clone(),
                );
            }

            Statement::PassRetained {
                object_var,
                opaque_var,
                span,
            } => {
                self.branch_state.retain(*opaque_var);
                self.trace.push(
                    Effect::Retain {
                        object: *object_var,
                        opaque_handle: *opaque_var,
                    },
                    span.clone(),
                );
            }

            Statement::TakeRetainedValue {
                opaque_var,
                result_var,
                span,
            } => {
                self.branch_state.release(*opaque_var);
                self.trace.push(
                    Effect::TakeRetained {
                        opaque_handle: *opaque_var,
                        result: *result_var,
                    },
                    span.clone(),
                );
            }

            Statement::Release { opaque_var, span } => {
                self.branch_state.release(*opaque_var);
                self.trace.push(
                    Effect::Release {
                        opaque_handle: *opaque_var,
                    },
                    span.clone(),
                );
            }

            Statement::FfiCall {
                function_name,
                arguments,
                out_params,
                span,
                ..
            } => {
                let arg_vars: Vec<VarId> = arguments.iter().filter_map(Self::expr_to_var).collect();

                self.trace.push(
                    Effect::FfiCall {
                        function_name: function_name.clone(),
                        arguments: arg_vars.clone(),
                        out_params: out_params.clone(),
                    },
                    span.clone(),
                );

                if function_name.contains("copy_into")
                    && let Some(ptr_var) = arg_vars.first()
                {
                    let capacity = arg_vars
                        .get(2)
                        .map(|v| Capacity::Variable(*v))
                        .unwrap_or(Capacity::Unknown);

                    self.trace.push(
                        Effect::BufferWrite {
                            pointer: *ptr_var,
                            size: capacity,
                        },
                        span.clone(),
                    );
                }

                out_params.iter().for_each(|var| {
                    self.trace
                        .push(Effect::StatusProduced { status_var: *var }, span.clone());
                });
            }

            Statement::StatusCheck {
                status_var, span, ..
            } => {
                self.trace.push(
                    Effect::StatusChecked {
                        status_var: *status_var,
                    },
                    span.clone(),
                );
            }

            Statement::Defer { body, span } => {
                let deferred_effects = self.collect_deferred(body);
                self.deferred.push(DeferredBlock {
                    effects: deferred_effects,
                });

                let effects_copy: Vec<Effect> = self
                    .deferred
                    .last()
                    .map(|b| b.effects.iter().map(|(e, _)| e.clone()).collect())
                    .unwrap_or_default();

                self.trace.push(
                    Effect::DeferRegistered {
                        deferred_effects: effects_copy,
                    },
                    span.clone(),
                );
            }

            Statement::IfStatement {
                then_branch,
                else_branch,
                span,
                ..
            } => {
                let pre_branch = self.branch_state.clone();

                self.visit_statements(then_branch);
                let then_state = self.branch_state.clone();

                self.branch_state = BranchState::from_parent(&pre_branch);

                if let Some(else_stmts) = else_branch {
                    self.visit_statements(else_stmts);
                }
                let else_state = self.branch_state.clone();

                let new_divergences =
                    check_branch_consistency(&then_state, &else_state, &pre_branch, span);
                self.divergences.extend(new_divergences);

                self.branch_state = merge_branch_states(&then_state, &else_state);
            }

            Statement::Return { span, .. } => {
                self.execute_defers_at(span.clone());
            }

            Statement::LetBinding { value, span, .. }
            | Statement::VarBinding {
                value: Some(value),
                span,
                ..
            } => {
                self.visit_expression_effects(value, span);
            }

            Statement::Assignment { value, span, .. } => {
                self.visit_expression_effects(value, span);
            }

            Statement::BufferAccess { body, .. } => {
                self.visit_statements(body);
            }

            Statement::Expression { expression, span } => {
                self.visit_expression_effects(expression, span);
            }

            _ => {}
        }
    }

    fn visit_expression_effects(&mut self, expr: &Expression, span: &SourceSpan) {
        if let Expression::FfiCallExpr { function_name, .. } = expr {
            self.trace.push(
                Effect::FfiCall {
                    function_name: function_name.clone(),
                    arguments: vec![],
                    out_params: vec![],
                },
                span.clone(),
            );
        }
    }

    fn collect_deferred(&mut self, body: &[Statement]) -> Vec<(Effect, SourceSpan)> {
        let mut effects = Vec::new();

        body.iter().for_each(|stmt| match stmt {
            Statement::Deallocate { pointer_var, span } => {
                effects.push((
                    Effect::Free {
                        pointer: *pointer_var,
                    },
                    span.clone(),
                ));
            }
            Statement::Release { opaque_var, span } => {
                effects.push((
                    Effect::Release {
                        opaque_handle: *opaque_var,
                    },
                    span.clone(),
                ));
            }
            Statement::FfiCall {
                function_name,
                span,
                ..
            } => {
                effects.push((
                    Effect::FfiCall {
                        function_name: function_name.clone(),
                        arguments: vec![],
                        out_params: vec![],
                    },
                    span.clone(),
                ));
            }
            Statement::Expression {
                expression: Expression::FfiCallExpr { function_name, .. },
                span,
            } => {
                effects.push((
                    Effect::FfiCall {
                        function_name: function_name.clone(),
                        arguments: vec![],
                        out_params: vec![],
                    },
                    span.clone(),
                ));
            }
            _ => {}
        });

        effects
    }

    fn execute_defers_at(&mut self, span: SourceSpan) {
        self.deferred
            .drain(..)
            .rev()
            .flat_map(|block| block.effects)
            .for_each(|(effect, effect_span)| {
                self.trace.push(effect, effect_span);
            });

        self.trace.push(Effect::DeferExecuted, span);
    }

    fn execute_all_defers(&mut self) {
        if !self.deferred.is_empty() {
            let span = self
                .trace
                .entries()
                .last()
                .map(|e| e.span.clone())
                .unwrap_or_else(|| {
                    use crate::source::SourceFile;
                    use std::sync::Arc;
                    let file = Arc::new(SourceFile::new("unknown", ""));
                    SourceSpan::new(file, 0u32, 0u32)
                });

            self.execute_defers_at(span);
        }
    }

    fn expr_to_capacity(&self, expr: &Expression) -> Capacity {
        match expr {
            Expression::Literal(Literal::Integer(n)) => Capacity::Literal(*n as u64),
            Expression::Variable(var_id) => Capacity::Variable(*var_id),
            Expression::FfiCallExpr { function_name, .. } => Capacity::FfiResult {
                function_name: function_name.clone(),
                arguments: vec![],
            },
            _ => Capacity::Unknown,
        }
    }

    fn expr_to_var(expr: &Expression) -> Option<VarId> {
        match expr {
            Expression::Variable(var_id) => Some(*var_id),
            _ => None,
        }
    }
}

impl Default for EffectCollector {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ir::{PointerType, UnitKind, VarId};
    use crate::source::SourceFile;
    use std::sync::Arc;

    fn test_span() -> SourceSpan {
        let file = Arc::new(SourceFile::new("test.swift", "test content"));
        SourceSpan::new(file, 0u32, 4u32)
    }

    #[test]
    fn test_collect_alloc_free() {
        let ptr = VarId::new(0);

        let unit = VerifyUnit {
            name: "test".to_string(),
            kind: UnitKind::FreeFunction,
            params: vec![],
            body: vec![
                Statement::Allocate {
                    target_var: ptr,
                    pointer_type: PointerType::Mutable,
                    element_type: "Int32".to_string(),
                    capacity: Expression::Literal(Literal::Integer(10)),
                    span: test_span(),
                },
                Statement::Deallocate {
                    pointer_var: ptr,
                    span: test_span(),
                },
            ],
            span: test_span(),
        };

        let trace = EffectCollector::collect(&unit);

        assert_eq!(trace.allocations().count(), 1);
        assert_eq!(trace.deallocations().count(), 1);
    }

    #[test]
    fn test_collect_defer() {
        let ptr = VarId::new(0);

        let unit = VerifyUnit {
            name: "test".to_string(),
            kind: UnitKind::FreeFunction,
            params: vec![],
            body: vec![
                Statement::Allocate {
                    target_var: ptr,
                    pointer_type: PointerType::Mutable,
                    element_type: "Int32".to_string(),
                    capacity: Expression::Literal(Literal::Integer(10)),
                    span: test_span(),
                },
                Statement::Defer {
                    body: vec![Statement::Deallocate {
                        pointer_var: ptr,
                        span: test_span(),
                    }],
                    span: test_span(),
                },
            ],
            span: test_span(),
        };

        let trace = EffectCollector::collect(&unit);

        assert_eq!(trace.allocations().count(), 1);
        assert_eq!(trace.deallocations().count(), 1);
    }

    #[test]
    fn test_collect_retain_release() {
        let obj = VarId::new(0);
        let handle = VarId::new(1);

        let unit = VerifyUnit {
            name: "test".to_string(),
            kind: UnitKind::FreeFunction,
            params: vec![],
            body: vec![
                Statement::PassRetained {
                    object_var: obj,
                    opaque_var: handle,
                    span: test_span(),
                },
                Statement::Release {
                    opaque_var: handle,
                    span: test_span(),
                },
            ],
            span: test_span(),
        };

        let trace = EffectCollector::collect(&unit);

        assert_eq!(trace.retains().count(), 1);
        assert_eq!(trace.releases().count(), 1);
    }
}
