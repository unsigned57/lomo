//! Adversarial usecase-reachability probes: what source text must and must
//! not count as a consumer. Merged from the numbered re-audit rounds — each
//! module pins the surface its round documented (gate basics, mention
//! positions, declaration-name exclusions).

#[cfg(test)]
mod tests {

    // adversarial re-audit (round 2) of the verification DAG and usecase-reachability gate
    // landed by 05-F8. Probes construct production-shaped inputs and run the shipped gate
    // (`lomo_xtask::check_usecase_reachability`, `VerificationPlan::build`, `execute`).
    // A RED result documents a live bypass shape; GREEN results lock fail-closed behavior.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures pin exact shapes and fail closed"
    )]
    mod gate_basics {
        use std::fs;
        use tempfile::TempDir;

        fn fixture(files: &[(&str, &str)]) -> TempDir {
            let dir = tempfile::tempdir().expect("fixture dir");
            for (path, content) in files {
                let absolute = dir.path().join(path);
                fs::create_dir_all(absolute.parent().expect("fixture parent"))
                    .expect("fixture mkdir");
                fs::write(&absolute, content).expect("fixture file");
            }
            dir
        }

        const DEAD_USECASE_DECL: &str =
            "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = 1; }\n";

        fn dead_usecase_fixture(consumer_name: &str, consumer_content: &str) -> TempDir {
            fixture(&[
                (
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    DEAD_USECASE_DECL,
                ),
                (
                    &format!("apps/android/app/src/{consumer_name}"),
                    consumer_content,
                ),
            ])
        }

        // ------------------------------------------------------------------
        // `contains_identifier_usage` skips only lines whose first token is literally
        // `import`/`package`/`typealias` — a visibility/annotation modifier or a value
        // continued on the next line leaves the identifier counting as a consumer.
        // ------------------------------------------------------------------

        #[test]
        fn prefixed_or_split_typealias_must_not_count_as_a_consumer() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                "package com.lomo.app\nprivate typealias DeadAlias = com.lomo.domain.usecase.DeadUseCase\n",
                "package com.lomo.app\ninternal typealias DeadAlias = com.lomo.domain.usecase.DeadUseCase\n",
                "package com.lomo.app\n@Deprecated(\"unused\") typealias DeadAlias = com.lomo.domain.usecase.DeadUseCase\n",
                "package com.lomo.app\ntypealias DeadAlias =\n    com.lomo.domain.usecase.DeadUseCase\n",
            ] {
                let dir = dead_usecase_fixture("Alias.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: these typealias shapes satisfied the consumer obligation:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        /// `extract_usecase_decl_from_line` only matches `class `/`interface ` — an `object`
        /// singleton named `*UseCase` never enters the declaration inventory, so dead code in
        /// that shape carries no reachability obligation at all.
        #[test]
        fn object_named_usecase_must_carry_the_reachability_obligation() {
            let dir = fixture(&[
                (
                    "apps/android/domain/src/usecase/RealUseCase.kt",
                    "package com.lomo.domain.usecase\nclass RealUseCase { operator fun invoke() = 1 }\n",
                ),
                (
                    "apps/android/domain/src/usecase/Phantom.kt",
                    "package com.lomo.domain.usecase\nobject PhantomUseCase { fun run() = 1 }\n",
                ),
                (
                    "apps/android/app/src/Consumer.kt",
                    "package com.lomo.app\nfun f() = com.lomo.domain.usecase.RealUseCase()\n",
                ),
            ]);
            let result = lomo_xtask::check_usecase_reachability(dir.path());
            assert!(
                result.is_err(),
                "BLIND SPOT: `object PhantomUseCase` escapes the declaration inventory — only \
                 `class `/`interface ` lines are declarations"
            );
        }

        /// Kotlin permits a newline between the `class` keyword and the name; the line-scoped
        /// keyword search requires `class ` (trailing space) on the same line, so a split
        /// header escapes the inventory.
        #[test]
        fn a_usecase_declared_with_a_split_class_header_must_not_escape() {
            let dir = fixture(&[
                (
                    "apps/android/domain/src/usecase/RealUseCase.kt",
                    "package com.lomo.domain.usecase\nclass RealUseCase { operator fun invoke() = 1 }\n",
                ),
                (
                    "apps/android/domain/src/usecase/Split.kt",
                    "package com.lomo.domain.usecase\nclass\n    SplitUseCase { operator fun invoke() = 1 }\n",
                ),
                (
                    "apps/android/app/src/Consumer.kt",
                    "package com.lomo.app\nfun f() = com.lomo.domain.usecase.RealUseCase()\n",
                ),
            ]);
            let result = lomo_xtask::check_usecase_reachability(dir.path());
            assert!(
                result.is_err(),
                "BLIND SPOT: `class` on its own line escapes the line-scoped declaration scan"
            );
        }

        // ------------------------------------------------------------------
        // Fail-closed controls — the shipped gate must still reject.
        // ------------------------------------------------------------------

        /// Green lock: an ordinary dead usecase is still rejected.
        #[test]
        fn an_unconsumed_usecase_is_still_rejected() {
            let dir = fixture(&[(
                "apps/android/domain/src/usecase/DeadUseCase.kt",
                DEAD_USECASE_DECL,
            )]);
            assert!(lomo_xtask::check_usecase_reachability(dir.path()).is_err());
        }

        /// Green lock: `*Module.kt` files are excluded from the consumer scan anywhere in the
        /// tree, not only under `di/` — a usecase consumed solely from `app/src/WiringModule.kt`
        /// (outside `di/`) must still be rejected rather than silently unrooted.
        #[test]
        fn a_module_suffixed_filename_cannot_satisfy_reachability() {
            let dir = fixture(&[
                (
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    DEAD_USECASE_DECL,
                ),
                (
                    "apps/android/app/src/feature/WiringModule.kt",
                    "package com.lomo.app.feature\nval bound = com.lomo.domain.usecase.DeadUseCase()\n",
                ),
            ]);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "a `*Module.kt` file outside di/ must not count as a consumer"
            );
        }

        /// Green lock: a domain file that declares no usecase cannot bridge consumers — a
        /// reachable-from-app helper mentioning the usecase still leaves it unrooted.
        #[test]
        fn domain_helper_without_declarations_cannot_root_a_usecase() {
            let dir = fixture(&[
                (
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    DEAD_USECASE_DECL,
                ),
                (
                    "apps/android/domain/src/helper/Bridge.kt",
                    "package com.lomo.domain.helper\nfun bridge() = com.lomo.domain.usecase.DeadUseCase()\n",
                ),
                (
                    "apps/android/app/src/Consumer.kt",
                    "package com.lomo.app\nfun f() = com.lomo.domain.helper.bridge()\n",
                ),
            ]);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "a non-declaring domain file must not mint reachability"
            );
        }
    }

    // adversarial re-audit (round 3) of the usecase-reachability token scanner rebuilt by
    // 12-修复-工程门禁 (F5/F6). Probes run the shipped `lomo_xtask::check_usecase_reachability`
    // on production-shaped fixtures; a RED result documents a live bypass shape.
    //
    // Probed invariants:
    //  - `skip_typealias` returns the index *past* the terminating newline, so the first
    //    token of the next construct is consumed as `prev_significant` and never scanned —
    //    a second `typealias` line's right-hand side therefore counts as a consumer.
    //  - Consumer detection is bare-identifier matching: a `val`/`fun`/`label` shadowing the
    //    usecase name in app code mints consumption without binding the domain type.
    //  - Comments, strings, `::class` literals and single typealiases still do not count.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures pin exact shapes and fail closed"
    )]
    mod mention_positions {
        use std::fs;
        use tempfile::TempDir;

        fn fixture(files: &[(&str, &str)]) -> TempDir {
            let dir = tempfile::tempdir().expect("fixture dir");
            for (path, content) in files {
                let absolute = dir.path().join(path);
                fs::create_dir_all(absolute.parent().expect("fixture parent"))
                    .expect("fixture mkdir");
                fs::write(&absolute, content).expect("fixture file");
            }
            dir
        }

        const DEAD_USECASE_DECL: &str =
            "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = 1; }\n";

        fn dead_usecase_fixture(consumer_name: &str, consumer_content: &str) -> TempDir {
            fixture(&[
                (
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    DEAD_USECASE_DECL,
                ),
                (
                    &format!("apps/android/app/src/{consumer_name}"),
                    consumer_content,
                ),
            ])
        }

        // ------------------------------------------------------------------
        // skip_typealias off-by-one: the token right after a typealias is swallowed as
        // `prev_significant`, so a *second* `typealias` (or an `import`) on the next
        // line is never dispatched — its payload is scanned as ordinary usage.
        // ------------------------------------------------------------------

        /// Two consecutive typealiases are a common alias-file shape. The first line is
        /// skipped correctly; the second `typealias` token is consumed as the previous
        /// token without triggering `skip_typealias`, so `DeadUseCase` on its right-hand
        /// side counts as a binding consumer.
        #[test]
        fn a_typealias_following_a_typealias_must_not_count_as_a_consumer() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                "package com.lomo.app\ntypealias First = kotlin.String\ntypealias Second = com.lomo.domain.usecase.DeadUseCase\n",
                "package com.lomo.app\ntypealias First = kotlin.String\ntypealias Second = kotlin.collections.List<DeadUseCase>\n",
                "package com.lomo.app\ntypealias First = kotlin.String\ntypealias Second =\n    DeadUseCase\n",
            ] {
                let dir = dead_usecase_fixture("Aliases.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: the second of two consecutive typealiases counts as a consumer \
                 — the token after `skip_typealias` is consumed as prev_significant instead \
                 of being dispatched:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        /// Same desync family: an `import` directly after a `typealias` is never routed
        /// through `skip_header` — the imported name counts as a consumer. (Imports after
        /// declarations are not compilable Kotlin, but the token stream must not depend on
        /// that ordering for correctness.)
        #[test]
        fn an_import_directly_after_a_typealias_must_not_count_as_a_consumer() {
            let consumer = "package com.lomo.app\ntypealias First = kotlin.String\nimport com.lomo.domain.usecase.DeadUseCase\n";
            let dir = dead_usecase_fixture("Aliases.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "BLIND SPOT: `import` after a typealias skips dispatch — the imported \
                 symbol counts as a consumer"
            );
        }

        // ------------------------------------------------------------------
        // Bare-identifier matching: `DeadUseCase` is counted wherever the token appears
        // unless the previous token is `::`/`class`/`object`/`interface`. Declaration
        // names of other kinds (`val`, `fun`), labels and lambda parameters shadow the
        // name without binding `com.lomo.domain.usecase.DeadUseCase` at all.
        // ------------------------------------------------------------------

        #[test]
        fn same_named_declarations_and_labels_must_not_count_as_consumers() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                // An app-side property declaration name — never binds the domain class.
                "package com.lomo.app\nval DeadUseCase = 0\n",
                // A function name — same shape: declaration identifier, not a reference.
                "package com.lomo.app\nfun DeadUseCase() = 0\n",
                // A loop label `DeadUseCase@` and its `break@DeadUseCase` target.
                "package com.lomo.app\nfun f() { DeadUseCase@ for (i in 0..1) { break@DeadUseCase } }\n",
                // A lambda parameter named DeadUseCase shadows the name in the body.
                "package com.lomo.app\nval xs = listOf(1).map { DeadUseCase -> DeadUseCase }\n",
                // A *different package's* `DeadUseCase` referenced unqualified — the token
                // match cannot tell com.lomo.other.DeadUseCase from the domain usecase.
                "package com.lomo.app\nfun f(x: com.lomo.other.DeadUseCase) = x\n",
            ] {
                let dir = dead_usecase_fixture("Shade.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: bare-token matching counts same-named declarations/labels as \
                 consumers without any binding to the domain type:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        // ------------------------------------------------------------------
        // Fail-closed controls — the shipped gate must still reject.
        // ------------------------------------------------------------------

        /// Green lock: a *first-position* typealias still routes through `skip_typealias`
        /// and does not consume.
        #[test]
        fn a_first_position_typealias_still_does_not_consume() {
            let consumer =
                "package com.lomo.app\ntypealias Alias = com.lomo.domain.usecase.DeadUseCase\n";
            let dir = dead_usecase_fixture("Alias.kt", consumer);
            assert!(lomo_xtask::check_usecase_reachability(dir.path()).is_err());
        }

        /// Green lock: generic type arguments inside a typealias right-hand side are
        /// swallowed by the alias skip — `DeadUseCase` inside `Map<..., DeadUseCase>`
        /// is a declaration payload, not a consumer.
        #[test]
        fn a_generic_typealias_rhs_still_does_not_consume() {
            let consumer = "package com.lomo.app\ntypealias Alias = kotlin.collections.Map<kotlin.String, com.lomo.domain.usecase.DeadUseCase>\n";
            let dir = dead_usecase_fixture("Alias.kt", consumer);
            assert!(lomo_xtask::check_usecase_reachability(dir.path()).is_err());
        }

        /// Green lock: `DeadUseCase::class` and `::DeadUseCase` reference shapes stay
        /// excluded on both sides of `RefColon`.
        #[test]
        fn class_literals_and_callable_references_still_do_not_consume() {
            let mut consumed = Vec::new();
            for consumer in [
                "package com.lomo.app\nval k = DeadUseCase::class\n",
                "package com.lomo.app\nval k = com.lomo.domain.usecase.DeadUseCase::class\n",
                "package com.lomo.app\nval f = ::DeadUseCase\n",
            ] {
                let dir = dead_usecase_fixture("Ref.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    consumed.push(consumer);
                }
            }
            assert!(
                consumed.is_empty(),
                "reference values must not satisfy the consumer obligation:\n{}",
                consumed.join("\n")
            );
        }

        /// Green control: a real construction `DeadUseCase()` inside an anonymous object
        /// expression still counts as a consumer — the reference-shape exclusions do not
        /// over-exclude.
        #[test]
        fn an_anonymous_object_still_consumes_a_usecase() {
            // The grammar requires the anonymous object's `{ }` body (`object : X()`
            // bare is a parse error — the gate fails closed on it either way).
            let consumer =
                "package com.lomo.app\nval o = object : com.lomo.domain.usecase.DeadUseCase() {}\n";
            let dir = dead_usecase_fixture("Anon.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "an anonymous-object supertype call is a real binding and must satisfy"
            );
        }

        /// Green control: a generic type position `get<DeadUseCase>()` still counts —
        /// the fix kept `<>` occurrences as honest bindings.
        #[test]
        fn a_generic_type_argument_still_consumes() {
            let consumer = "package com.lomo.app\ninline fun <reified T> get(): T? = null\nval u = get<com.lomo.domain.usecase.DeadUseCase>()\n";
            let dir = dead_usecase_fixture("Generic.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "a generic type argument is a real binding and must satisfy"
            );
        }
    }

    // adversarial re-audit (round 5) of the usecase-reachability declaration-position
    // exclusions landed by 14-修复-门禁降维残留 (N6/N7). Probes run the shipped
    // `lomo_xtask::check_usecase_reachability` on production-shaped fixtures; a RED
    // result documents a live bypass shape.
    //
    // Probed invariants:
    //  - N7 excluded `val`/`var`/`fun`/`class`/`object`/`interface` declaration names,
    //    `::` references, `@` labels, lambda parameter heads and other-package
    //    qualified tails — but several *remaining* declaration/alias positions still
    //    mint consumers: parameter names, loop variables, destructured names, enum
    //    entries, type parameters, named-argument labels, an imported same-named
    //    other-package type used unqualified (the doc comment claims this exact
    //    shape is treated as non-binding — the code does not), and a lambda
    //    parameter shadow *used* in its own body.
    //  - Direction: every shape below is a false consumer → the reachability
    //    obligation is satisfied without binding `com.lomo.domain.usecase.DeadUseCase`
    //    — fail-open.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures pin exact shapes and fail closed"
    )]
    mod declaration_names {
        use std::fs;
        use tempfile::TempDir;

        fn fixture(files: &[(&str, &str)]) -> TempDir {
            let dir = tempfile::tempdir().expect("fixture dir");
            for (path, content) in files {
                let absolute = dir.path().join(path);
                fs::create_dir_all(absolute.parent().expect("fixture parent"))
                    .expect("fixture mkdir");
                fs::write(&absolute, content).expect("fixture file");
            }
            dir
        }

        const DEAD_USECASE_DECL: &str =
            "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = 1; }\n";

        fn dead_usecase_fixture(consumer_name: &str, consumer_content: &str) -> TempDir {
            fixture(&[
                (
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    DEAD_USECASE_DECL,
                ),
                (
                    &format!("apps/android/app/src/{consumer_name}"),
                    consumer_content,
                ),
            ])
        }

        // ------------------------------------------------------------------
        // N7 residual: declaration positions not covered by the exclusion list.
        // Each `DeadUseCase` token names a *declaration/alias* — none binds the
        // domain type — yet bare-token matching counts it as a consumer.
        // ------------------------------------------------------------------

        #[test]
        fn remaining_declaration_positions_must_not_count_as_consumers() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                // A parameter name — declaration position, prev token `(`.
                "package com.lomo.app\nfun f(DeadUseCase: Int) = DeadUseCase\n",
                // A `for` loop variable — declaration position.
                "package com.lomo.app\nfun f() { for (DeadUseCase in listOf(1)) {} }\n",
                // A destructured component name.
                "package com.lomo.app\nfun f() { val (DeadUseCase) = Pair(1, 2) }\n",
                // An enum entry — declaration position inside the enum body.
                "package com.lomo.app\nenum class Things { DeadUseCase }\n",
                // A type parameter declaration `<DeadUseCase>`.
                "package com.lomo.app\nfun <DeadUseCase> f() = 0\n",
                // A named-argument label `DeadUseCase = ...`.
                "package com.lomo.app\nfun g(DeadUseCase: Int = 0) = DeadUseCase\nval r = g(DeadUseCase = 1)\n",
            ] {
                let dir = dead_usecase_fixture("Shade.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: declaration positions the N7 exclusion list missed still \
                 mint consumers — parameter/loop-variable/destructure/enum-entry/\
                 type-parameter/named-argument names never bind the domain type:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        /// The `contains_identifier_usage` doc comment claims an "imported-but-same-named
        /// other-package type used unqualified — is treated as non-binding". The code
        /// cannot honour that claim: the import line is skipped whole, so the scanner
        /// has no record that bare `DeadUseCase` resolves to `com.lomo.other.*` — the
        /// unqualified use still mints a consumer. This is the most common Kotlin
        //  shadowing shape (import then bare use).
        #[test]
        fn an_imported_same_named_type_used_unqualified_must_not_count_as_a_consumer() {
            let consumer =
                "package com.lomo.app\nimport com.lomo.other.DeadUseCase\nval x = DeadUseCase\n";
            let dir = dead_usecase_fixture("Shade.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "BLIND SPOT: `import com.lomo.other.DeadUseCase` + bare `DeadUseCase` \
                 still counts as a consumer — the doc's non-binding claim for this \
                 shape is not implemented (imports are skipped, not modelled)"
            );
        }

        /// The lambda-parameter exclusion only rejects the head (`DeadUseCase ->`).
        /// The shadowed parameter *used* in the body escapes: `DeadUseCase()` has
        /// next `(` and `DeadUseCase.field` has next `.` — both pass the
        /// `prev == Arrow` carve-out and count as consumers while binding only the
        //  lambda parameter.
        #[test]
        fn a_lambda_parameter_shadow_used_in_its_body_must_not_count_as_a_consumer() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                "package com.lomo.app\nval xs = listOf(1).map { DeadUseCase -> DeadUseCase() }\n",
                "package com.lomo.app\nval xs = listOf(1).map { DeadUseCase -> DeadUseCase.field }\n",
            ] {
                let dir = dead_usecase_fixture("Shade.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: a lambda parameter's own use sites bind the parameter, not \
                 the usecase type — `{{ DeadUseCase -> DeadUseCase() }}` mints a fake \
                 consumer:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        // ------------------------------------------------------------------
        // Fail-closed controls — real bindings must still satisfy the obligation.
        // ------------------------------------------------------------------

        /// Green control: a real constructor call still counts.
        #[test]
        fn a_real_construction_still_consumes() {
            let consumer = "package com.lomo.app\nval x = com.lomo.domain.usecase.DeadUseCase()\n";
            let dir = dead_usecase_fixture("Real.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "a real constructor binding must satisfy the consumer obligation"
            );
        }

        /// Green control: `when`/`is` type checks are real bindings.
        #[test]
        fn a_type_check_still_consumes() {
            let consumer =
                "package com.lomo.app\nfun f(x: Any) = x is com.lomo.domain.usecase.DeadUseCase\n";
            let dir = dead_usecase_fixture("Real.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "an `is` check binds the type and must satisfy"
            );
        }
    }
}
