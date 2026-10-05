//! Adversarial usecase-reachability probes: which identifiers enter the
//! file's binding environment and which are only name positions — locals
//! and declaration shadows, setter/parameter slots, use-site targets,
//! backtick names, interpolation boundaries. Merged from the numbered
//! re-audit rounds.

#[cfg(test)]
mod tests {

    // adversarial re-audit (round 6) of the P5-F2 usecase-reachability FileScope fix
    // landed by 16-修复-门禁纵深残留 (R5) and of the shared `lex_kotlin` dual view (R3).
    // Probes run the shipped `lomo_xtask::check_usecase_reachability` /
    // `lomo_xtask::lex_kotlin` on production-shaped fixtures; a RED result documents
    // a live bypass shape.
    //
    // Probed invariants (new adversarial surfaces, not re-runs of the R5 shapes):
    //  - R5 residual: `FileScope.locals` records *anonymous* declaration positions
    //    (parameters, loop variables, destructured components, type parameters,
    //    lambda parameters) but never records a *named* declaration — a local
    //    `val`/`var`, a member property, a constructor property parameter, a
    //    nested class or an enum entry *used unqualified inside its own enum body*
    //    all shadow the usecase name for every later bare occurrence while staying
    //    out of `locals`. The declaration occurrence itself is excluded by the
    //    prev-keyword rule; every subsequent bare use binds the shadow — and still
    //    mints a consumer.
    //  - R3 residual: `lex_kotlin` does not model `${ }` interpolation — a nested
    //    literal inside `"${ "..." }"` desynchronises the quote walk, so string
    //    payload (`"DeadUseCase"` as content) lands in the `code` view as an
    //    identifier and mints a consumer.
    //  - Direction: every shape below is a false consumer → the reachability
    //    obligation is satisfied without binding `com.lomo.domain.usecase.DeadUseCase`
    //    — fail-open.

    #[cfg(test)]
    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures pin exact shapes and fail closed"
    )]
    mod named_shadows {
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
        // R5 residual: *named* declarations shadow the usecase name without ever
        // entering `locals` — the declaration occurrence is excluded by the
        // prev-keyword rule, then every later bare use binds the shadow while the
        // scanner counts it as binding the domain type.
        // ------------------------------------------------------------------

        /// `val`/`var` declarations introduce a binding exactly like a lambda or
        /// `for` parameter — but `collect_destructured` only handles the `(a, b)`
        /// form and the name-slot rule only fires after `(`/`,`. A local or member
        /// `val DeadUseCase` therefore shadows the usecase for the whole scope
        /// while every bare reuse still mints a consumer.
        #[test]
        fn a_named_val_var_declaration_must_shadow_bare_uses() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                // local `val` — binds the local for the whole function scope
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 fun f() { val DeadUseCase = Any()\n  DeadUseCase.toString() }\n",
                // local `var` — same shadowing, plus a reassignment use
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 fun f() { var DeadUseCase = 0\n  DeadUseCase = 1\n  DeadUseCase.inc() }\n",
                // member property — binds the property for the class scope
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 class Holder { val DeadUseCase = 0\n  fun f() = DeadUseCase }\n",
                // constructor `val` parameter — declares a property named DeadUseCase
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 class Holder(val DeadUseCase: Int) { fun f() = DeadUseCase }\n",
            ] {
                let dir = dead_usecase_fixture("Shade.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: named `val`/`var`/property declarations shadow the usecase \
                 name in Kotlin scope rules but never enter `locals` — every later \
                 bare use binds the shadow and still mints a consumer:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        /// A nested `class DeadUseCase` shadows the import for the whole enclosing
        /// class body — Kotlin's inner-scope declarations win over file imports —
        /// and an enum entry used *unqualified inside its own enum body* binds the
        /// entry, not the domain type. Neither name enters `locals`.
        #[test]
        fn nested_type_and_enum_entry_shadows_must_not_count_as_consumers() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                // nested class — `DeadUseCase()` inside `Outer` constructs the nested class
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 class Outer { class DeadUseCase\n  fun f() = DeadUseCase() }\n",
                // nested object — same shadowing via `object`
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 class Outer { object DeadUseCase\n  fun f() = DeadUseCase.x }\n",
                // enum entry referenced unqualified inside the enum's own body
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 enum class Things { DeadUseCase; fun f() = DeadUseCase }\n",
            ] {
                let dir = dead_usecase_fixture("Shade.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: nested class/object declarations and enum entries used \
                 unqualified inside the enum body shadow the import — bare uses bind \
                 the file-local declaration, not the domain type:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        /// The name-slot rule keys on `(`/`,` immediately before the name — a
        /// parameter modifier (`vararg`/`noinline`/`crossinline`) between them
        /// breaks the lookup, so a modified parameter name never enters `locals`
        /// and its shadowed uses still mint consumers.
        #[test]
        fn parameter_modifiers_must_not_unbind_the_name_slot() {
            let mut falsely_satisfied = Vec::new();
            for consumer in [
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 fun f(vararg DeadUseCase: Int) = DeadUseCase.size\n",
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 fun g(x: () -> Unit) {}\nfun f(noinline DeadUseCase: () -> Unit) = DeadUseCase()\n",
            ] {
                let dir = dead_usecase_fixture("Shade.kt", consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    falsely_satisfied.push(consumer);
                }
            }
            assert!(
                falsely_satisfied.is_empty(),
                "BLIND SPOT: `vararg`/`noinline`/`crossinline` between `(`/`,` and the \
                 parameter name defeats the name-slot rule — the parameter never \
                 enters `locals` and its uses mint fake consumers:\n{}",
                falsely_satisfied.join("\n")
            );
        }

        // ------------------------------------------------------------------
        // R3 residual on the usecase scan: `${ }` interpolation desyncs the quote
        // walk — `"${ "DeadUseCase" }"` is pure string payload yet the nested
        // literal's content lands in the `code` view as an identifier.
        // ------------------------------------------------------------------

        /// `"${ "DeadUseCase" }"` contains the name only as *string content* — the
        /// interpolation executes an expression whose value is a literal, so the
        /// name never binds the domain type. The quote desync leaks it into `code`.
        #[test]
        fn a_usecase_name_inside_a_template_nested_literal_must_not_consume() {
            let consumer = "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 val s = \"${ \"DeadUseCase\" }\"\n";
            let dir = dead_usecase_fixture("Shade.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "BLIND SPOT: `\"${{ \"DeadUseCase\" }}\"` is pure string payload — the \
                 `${{ }}` quote desync leaks the name into the `code` view and mints a \
                 consumer for a name that is never code"
            );
        }

        // ------------------------------------------------------------------
        // Strict-direction boundaries — documented approximations that must keep
        // over-reporting rather than silently passing.
        // ------------------------------------------------------------------

        /// Two wildcard imports that could each supply the name are ambiguous —
        /// Kotlin resolves neither bare — so the non-binding direction must hold.
        #[test]
        fn an_ambiguous_wildcard_stays_non_binding() {
            let consumer = "package com.lomo.app\nimport com.lomo.domain.usecase.*\n\
                 import com.other.*\nval x = DeadUseCase()\n";
            let dir = dead_usecase_fixture("Shade.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "documented boundary: an ambiguous wildcard pair resolves nothing — \
                 non-binding (fail-closed)"
            );
        }

        /// An `as` alias of the usecase is a real consumer the `FileScope` model
        /// cannot credit — `Alias()` binds `DeadUseCase` in Kotlin but the binding
        /// record keys on the introduced name. Over-reporting, never silent.
        #[test]
        fn an_import_alias_stays_fail_closed() {
            let consumer = "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase as Alias\n\
                 val x = Alias()\n";
            let dir = dead_usecase_fixture("Shade.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "documented boundary: `import … as Alias` + `Alias()` is a real consumer \
                 the scanner cannot credit — over-reporting (fail-closed)"
            );
        }

        // ------------------------------------------------------------------
        // Fail-closed controls — real bindings must still satisfy the obligation.
        // ------------------------------------------------------------------

        /// A semicolon-joined line is legal Kotlin — `import a.b.C; val x = …`
        /// compiles — so `skip_header`/`header_pieces` end the header at the `;`
        /// and the same-line statement scans normally: the use binds.
        /// (Round-14 reclassification, N14-3: the previous "the `;`-joined tail is
        /// swallowed with the header — fail-closed on uncompilable input"
        /// registration was falsified — the input compiles, so the swallow was a
        /// misreport, not a conservative boundary.)
        #[test]
        fn semicolon_joined_uses_still_bind() {
            let consumer = "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase; \
                 val x = DeadUseCase()\n";
            let dir = dead_usecase_fixture("Real.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "a semicolon-joined real use binds — `;` ends the header, and the \
                 tail's tokens scan as ordinary code"
            );
        }

        /// Green control: an explicit import + bare constructor call still binds.
        #[test]
        fn an_explicit_import_bare_construction_still_consumes() {
            let consumer = "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                 val x = DeadUseCase()\n";
            let dir = dead_usecase_fixture("Real.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "an explicit import + bare construction must satisfy the obligation"
            );
        }

        /// Green control: a fully-qualified construction still binds.
        #[test]
        fn a_qualified_construction_still_consumes() {
            let consumer = "package com.lomo.app\nval x = com.lomo.domain.usecase.DeadUseCase()\n";
            let dir = dead_usecase_fixture("Real.kt", consumer);
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "a qualified construction must satisfy the obligation"
            );
        }
    }

    // adversarial re-audit (round 7) of the N7 use-case reachability fix from
    // 18-修复-门禁三层纵深, plus cross-surface probes the earlier rounds did not
    // touch. Fixture harness mirrors reaudit6. RED rows document live coverage
    // holes in the N7 exclusion model; GREEN rows lock fail-closed directions.
    //
    // Probed surfaces:
    //  - `name_slot_is_param` walks backward over a *flat* name → it recognises
    //    `@Anno` (annotation applied to the name) but NOT Kotlin's use-site-target
    //    form `@get:Anno`/`@setparam:Anno` — the annotation's name is `get`, and
    //    the walk hits the `:` and gives up, so the parameter never enters
    //    `locals` while its own name occurrence still mints.
    //  - `name_slot_is_param` requires `next == ':'` after the name — but Kotlin
    //    permits UNTYPED parameters in property setters (`set(x)`) and in
    //    `catch (e)`-style positions, so `set(DeadUseCase) { ... }` leaks the
    //    parameter name as a consumer.
    //  - `for` headers collect EVERY identifier between the parens as a local —
    //    including the iterable expression: `for (x in DeadUseCase)` binds the
    //    use-case class for real but the name is collected as a "declaration".
    //  - `tokenize_kotlin` splits backtick-escaped names at the backtick, so a
    //    declared `` `DeadUseCase` `` never joins `locals` and the bare occurrence
    //    still mints.
    //  - `lex_kotlin`'s `$name` shorthand pushes the identifier into `code`
    //    verbatim, so `"$DeadUseCase"` mints an occurrence — but `$name` resolves
    //    in Kotlin's *property* namespace; it can never name a class without a
    //    companion object, so the occurrence cannot bind the declaration.

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod name_slots {
        use std::fs;
        use std::path::Path;

        fn write(root: &Path, relative: &str, content: &str) {
            let path = root.join(relative);
            fs::create_dir_all(path.parent().expect("fixture parent")).expect("fixture mkdir");
            fs::write(&path, content).expect("fixture file");
        }

        fn usecase_fixture(root: &Path, consumer: &str) {
            write(
                root,
                "apps/android/domain/src/usecase/DeadUseCase.kt",
                "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
            );
            write(
                root,
                "apps/android/app/src/Consumer.kt",
                &format!(
                    "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n{consumer}\n"
                ),
            );
        }

        /// `name_slot_is_param` requires `next == ':'` after the candidate name —
        /// an untyped setter parameter `set(DeadUseCase) { ... }` binds the
        /// parameter for real yet never enters `locals`, so both the parameter
        /// slot and the body use mint phantom consumers.
        #[test]
        fn an_untyped_setter_parameter_must_stay_bound() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "class Registry {\n  var value = 0\n    set(DeadUseCase) { field = DeadUseCase }\n}\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "BLIND SPOT: `set(DeadUseCase) {{ field = DeadUseCase }}` binds an \
                 untyped setter parameter — `name_slot_is_param` demands a `:` \
                 after the name, so the binding never enters `locals` and the \
                 uses mint a phantom consumer"
            );
        }

        /// Kotlin's use-site annotation targets — `@get:X`, `@param:X`,
        /// `@field:X`, `@setparam:X` — put the annotation's *target keyword* where
        /// `annotation_start_back` expects a name: the walk hits `:` and gives up,
        /// so the annotated parameter never joins `locals` and its own name mints.
        #[test]
        fn use_site_target_annotations_must_not_unbind_the_parameter() {
            let mut leaked = Vec::new();
            for consumer in [
                // `@param:`/`@field:` use-site targets on constructor parameters —
                // legal Kotlin; the parameter name never reaches `locals`
                "class C(@param:Anno DeadUseCase: Int) { fun run() = DeadUseCase.inc() }",
                "class C(@field:Anno DeadUseCase: Int) { fun run() = DeadUseCase.inc() }",
                "class C(@param:Anno @d.E DeadUseCase: Int) { fun run() = DeadUseCase.inc() }",
                // `@Anno @param:B` stacked targets — text-shape robustness
                "fun f(@Anno @param:B DeadUseCase: Int) = DeadUseCase.inc()",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a use-site-target annotation (`@param:`/`@field:`/`@setparam:`...) \
                 leaves the parameter out of `locals` — `annotation_start_back` hits \
                 the `:` after the target keyword and gives up:\n{}",
                leaked.join("\n")
            );
        }

        /// Green control: `for` header collection stops at `in`, so the iterable
        /// expression is NOT folded into `locals` — `for (x in DeadUseCase)` binds
        /// the use-case class for real and must keep minting a consumer.
        #[test]
        fn the_for_iterable_position_binds_for_real() {
            let mut dropped = Vec::new();
            for consumer in [
                "fun f() { for (x in DeadUseCase) {} }",
                "fun f() { for (i in DeadUseCase.indices) {} }",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "`for` iterable positions are real bindings and must keep minting:\n{}",
                dropped.join("\n")
            );
        }

        /// Backtick-escaped identifiers are one Kotlin identifier — `tokenize_kotlin`
        /// splits `` `DeadUseCase` `` at the backtick, so the declared name never
        /// joins `locals` while the bare use still mints.
        #[test]
        fn backtick_escaped_declarations_must_still_shadow() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "class Registry {\n  val `DeadUseCase` = { 0 }\n  fun run() = `DeadUseCase`()\n}\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "BLIND SPOT: `tokenize_kotlin` splits backtick-escaped names at the \
                 backtick — a declared `` `DeadUseCase` `` never joins `locals`, \
                 and the later `` `DeadUseCase`() `` use mints a phantom consumer"
            );
        }

        /// `$name` shorthand interpolation resolves in Kotlin's property
        /// namespace — `"$DeadUseCase"` can only mean a property or a companion
        /// object, never a bare class. `lex_kotlin` pushes the identifier into
        /// `code` verbatim, so the occurrence mints a consumer nothing can satisfy.
        #[test]
        fn shorthand_interpolation_names_must_not_consume_the_class() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(dir.path(), "fun f() = \"$DeadUseCase\"");
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "BLIND SPOT: `\"$DeadUseCase\"` resolves in Kotlin's property \
                 namespace — it can never bind a bare class — yet the `$name` \
                 shorthand pushes the identifier into `code` and it mints a consumer"
            );
        }

        /// Green controls: real binding positions must keep minting — the
        /// exclusion table must not over-match into the opposite blind spot.
        #[test]
        fn real_binding_positions_still_mint() {
            let mut dropped = Vec::new();
            for consumer in [
                // supertype binding on an anonymous object — multi-line form:
                // `tree-sitter-kotlin-ng` requires a separator before a closing
                // `}`, so `class C { val x = … }` one-liners are a parse bail
                "class C {\n  val x = object : DeadUseCase() {}\n}",
                // type position still binds
                "fun f(x: DeadUseCase) = x",
                // generic argument position
                "fun f(xs: List<DeadUseCase>) = xs",
                // construction
                "fun f() = DeadUseCase()",
                // plain `is` type check
                "fun f(x: Any) = x is DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "the N7 exclusion model must not swallow real binding positions — \
                 a real consumer satisfies the obligation and the check passes:\n{}",
                dropped.join("\n")
            );
        }

        /// `is DeadUseCase ->` is a real type-test binding — the identifier sits
        /// inside a `type_test`'s `user_type`, which is a binding position like
        /// any other type use. The old lexer over-reported it (its `->`
        /// lambda-parameter rule dropped every identifier before the arrow); the
        /// AST parses `is X` structurally and binds it, which is the correct,
        /// honest verdict — a real consumer satisfies the obligation.
        #[test]
        fn when_branch_is_checks_bind_the_tested_type() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "fun f(x: Any) = when (x) { is DeadUseCase -> 1 else -> 0 }",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`is DeadUseCase` is a real type dependency — the AST binds the \
                 `type_test` operand (the old token scan over-reported it via \
                 its `X ->` exclusion)"
            );
        }

        /// Green controls: named declarations the fix *does* cover must keep
        /// shadowing — a same-name `fun`, a `companion object`, a `catch` param
        /// (typed — `:` present), a `when` subject `val`, a destructured `val`,
        /// and nested annotation argument lists on a parameter.
        #[test]
        fn covered_declarations_keep_shadowing() {
            let mut leaked = Vec::new();
            for consumer in [
                "fun DeadUseCase() = 0\nfun f() = DeadUseCase()",
                "class C {\n  companion object DeadUseCase\n  fun f() = DeadUseCase\n}",
                "fun f() { try {} catch (DeadUseCase: Exception) {} }",
                "fun f(x: Any) = when (val DeadUseCase = x) { else -> DeadUseCase }",
                "fun f(p: Pair<Int, Int>) { val (_, DeadUseCase) = p; DeadUseCase.inc() }",
                "fun f(@Anno(g(1)) DeadUseCase: Int) = DeadUseCase.inc()",
                "fun f(@a.b.C(1) @d.E(2) DeadUseCase: Int) = DeadUseCase.inc()",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "REGRESSION: declarations the N7 fix covers must keep shadowing:\n{}",
                leaked.join("\n")
            );
        }

        /// Green control: `enum class` headers without a body bail `Err` — a
        /// malformed Kotlin file can never slip a half-parsed declaration through.
        #[test]
        fn a_headerless_enum_still_bails_fail_closed() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(dir.path(), "enum class Registry\nfun f() = DeadUseCase");
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "a `enum class` header without `{{` must bail fail-closed"
            );
        }

        /// Green control: `$`-prefixed names *inside* `${}` keep their boundary —
        /// `"${ x }"` contributes a `}` so a following `(` cannot glue; and a bare
        /// `${ DeadUseCase() }` construction must keep minting.
        #[test]
        fn braced_interpolation_binding_positions_still_mint() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(dir.path(), "fun f() = \"${ DeadUseCase() }\"");
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`${{ DeadUseCase() }}` is a real construction — it must consume"
            );
        }
    }

    // adversarial re-audit (round 8) of the P7-F2 four-layer gate fixes landed by
    // 20-修复-门禁四层纵深 (F1–F7 + B1/B2). Fixture harness mirrors reaudit7.
    // RED rows document live coverage holes in the repaired exclusion model;
    // GREEN rows lock fail-closed directions and honest binding positions.
    //
    // Probed surfaces:
    //  - F4 residual: `untyped_setter_slot` matches ANY bare `set(` call, not
    //    only a property setter — `set(DeadUseCase())` invoking a function named
    //    `set` binds the argument as a "declared parameter" and suppresses the
    //    real construction consumer (fail-closed false positive on legal
    //    Kotlin).
    //  - F1 cross-face: the `}` boundary sentinel enters the `code` token
    //    stream — `collect_enum_entries` counts `{`/`}` depth without guarding
    //    for parens, so a `"$x"` inside an enum-entry argument list ends the
    //    entry region early: later entry names never reach `enum_entries`/
    //    `locals` and their bare uses inside the enum body mint phantom
    //    consumers (fail-open).
    //  - F5 cross-face: the `:`-walk must accept every use-site target keyword,
    //    not only the four named in the fix doc.
    //  - F6/F7 residual checks: backtick declarations in `fun` position,
    //    `$` chains, and char-literal `$` staying masked.

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod setter_calls {
        use std::fs;
        use std::path::Path;

        fn write(root: &Path, relative: &str, content: &str) {
            let path = root.join(relative);
            fs::create_dir_all(path.parent().expect("fixture parent")).expect("fixture mkdir");
            fs::write(&path, content).expect("fixture file");
        }

        fn usecase_fixture(root: &Path, consumer: &str) {
            write(
                root,
                "apps/android/domain/src/usecase/DeadUseCase.kt",
                "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
            );
            write(
                root,
                "apps/android/app/src/Consumer.kt",
                &format!(
                    "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n{consumer}\n"
                ),
            );
        }

        // ------------------------------------------------------------------
        // F4 residual: `untyped_setter_slot` fires on any `(`-slot preceded by a
        // bare `set` — but `set(X)` is also just a call to a function named
        // `set`. The argument is an expression, not a declaration — binding it
        // into `locals` suppresses a REAL consumer (over-report / fail-closed:
        // the gate reports a wired use-case as unreachable). The doc's own
        // exclusion list covers `obj.set`/`::set` but not the bare-call shape.
        // ------------------------------------------------------------------

        /// `set(DeadUseCase())` calling a plain function named `set` — legal
        /// Kotlin, real construction consumer — must keep minting instead of
        /// shadowing the argument as a setter parameter.
        #[test]
        fn a_bare_set_call_argument_is_not_a_setter_parameter() {
            let mut dropped = Vec::new();
            for consumer in [
                // a top-level function literally named `set`
                "private fun set(x: Any) {}\nfun dispatch() = set(DeadUseCase())",
                // a `set` lambda parameter invoked like a function
                "fun install(set: (Any) -> Unit) {\n  set(DeadUseCase())\n}",
                // two-argument call — a setter never has two parameters, yet the
                // `(`-slot argument is still bound
                "private fun set(x: Any, y: Any) {}\nfun dispatch() = set(DeadUseCase(), 1)",
                // `set` inside a receiver block — resolves to `this.set`, still
                // a call, not a setter declaration (multi-line body — the grammar
                // requires a separator before `}`)
                "class Ctx {\n  fun set(x: Any) {}\n}\nfun dispatch(c: Ctx) = c.run { set(DeadUseCase()) }",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "FALSE POSITIVE: a `set(...)` CALL binds its first argument as a \
                 setter parameter — the real `DeadUseCase()` consumer inside the \
                 parens is suppressed by a phantom declaration:\n{}",
                dropped.join("\n---\n")
            );
        }

        /// Green controls: the REAL setter keeps binding (unreachable → Err),
        /// and member/reference `set` forms never minted locals in the first
        /// place — `obj.set(DeadUseCase())` and `fieldset(DeadUseCase())` are
        /// calls whose arguments are real consumers.
        #[test]
        fn real_setters_bind_and_member_calls_keep_minting() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "class Registry {\n  var value = 0\n    set(DeadUseCase) { field = DeadUseCase }\n}\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "a real `set(DeadUseCase)` setter still binds the parameter — the \
                 uses bind the local, no consumer mints, the use-case stays \
                 unreachable"
            );
            let mut dropped = Vec::new();
            for consumer in [
                // `obj.set(...)` — member call, argument is a real consumer
                "class Ctx {\n  fun set(x: Any) {}\n}\nfun dispatch(c: Ctx) = c.set(DeadUseCase())",
                // `fieldset(...)` — `set` is a strict token match, not a suffix
                "fun dispatch() = fieldset(DeadUseCase())",
                // `reset(`/`inset(`-style prefixes cannot collide either
                "fun dispatch() = reset(DeadUseCase())",
                // `::set` callable reference — the name after `::` is already a
                // reference position
                "fun dispatch(c: Ctx, x: Any) = c::set\nfun use() = DeadUseCase()",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "member calls and `set`-suffixed names must keep minting real \
                 consumers:\n{}",
                dropped.join("\n")
            );
        }

        // ------------------------------------------------------------------
        // F1 cross-face: the `}` shorthand sentinel lands in the `code` token
        // stream. `collect_enum_entries` counts `{`/`}` depth with NO paren
        // guard — a `"$x"` inside an enum-entry argument list (or an entry
        // class body) decrements the depth to zero early: every later entry
        // name is never collected, so a same-named entry's bare use inside the
        // enum body binds the DOMAIN type instead of the entry — a phantom
        // consumer (fail-open).
        // ------------------------------------------------------------------

        /// `enum class R { A("$x"), DeadUseCase; fun f() = DeadUseCase }` — the
        /// sentinel in `A("$x")` ends the entry scan before `DeadUseCase` is
        /// collected, so the member use binds the import: a consumer no real
        /// Kotlin program has.
        #[test]
        fn enum_entries_after_a_template_arg_must_stay_bound() {
            let mut leaked = Vec::new();
            for consumer in [
                // shorthand inside an entry's argument parens — the `}` sentinel
                // drops the depth to zero inside the parens
                "val x = \"v\"\nenum class Registry(val tag: String) {\n  A(\"$x\"),\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
                // shorthand inside an entry's class body — `{`+sentinel balance
                // ends the entry region at the body's real `}`
                "val x = \"v\"\nenum class Registry {\n  A { fun k() = \"$x\" },\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a `\"$x\"` inside an enum-entry argument list ends the \
                 entry region early — the `DeadUseCase` entry is never collected, \
                 so its bare use inside the enum body mints a phantom consumer \
                 (no real binding exists):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// Green control: without a shorthand literal in the way, entry names
        /// keep shadowing — `DeadUseCase` inside the enum binds the entry, no
        /// consumer mints, the use-case stays unreachable.
        #[test]
        fn plain_enum_entries_keep_shadowing() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "enum class Registry {\n  A,\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "`DeadUseCase` inside the enum binds the entry — the domain type \
                 stays unreachable (the shadowing model itself is sound)"
            );
        }

        // ------------------------------------------------------------------
        // F5 cross-face: the `:`-walk accepts `ident:name` generically — every
        // use-site target keyword Kotlin defines (and even non-targets in a
        // parameter slot) must keep the parameter bound.
        // ------------------------------------------------------------------

        /// `@receiver:`/`@property:`/`@get:`/`@set:`/`@file:`/`@delegate:` are
        /// not in the four-target list the fix doc names — the generic `:`-walk
        /// covers them; each shape must leave the parameter in `locals`.
        #[test]
        fn every_use_site_target_keeps_the_parameter_bound() {
            let mut leaked = Vec::new();
            for target in [
                "receiver", "property", "get", "set", "file", "delegate", "setparam", "param",
                "field", "bogus",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(
                    dir.path(),
                    &format!("fun f(@{target}:Anno DeadUseCase: Int) = DeadUseCase.inc()"),
                );
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(target);
                }
            }
            assert!(
                leaked.is_empty(),
                "any `@target:Anno` prefix in a parameter slot must keep the \
                 parameter bound — the `:`-walk is target-agnostic:\n{}",
                leaked.join(", ")
            );
        }

        /// `@param:Anno(1)` with arguments, then a second annotation — the
        /// composed walk must still land on `@` and keep the parameter bound.
        #[test]
        fn use_site_targets_with_arguments_and_stacks_stay_bound() {
            let mut leaked = Vec::new();
            for consumer in [
                "class C(@param:Anno(1) @field:B DeadUseCase: Int) { fun run() = DeadUseCase.inc() }",
                "fun f(@setparam:X(1, 2) @get:Y DeadUseCase: Int) = DeadUseCase.inc()",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "stacked/argument-carrying use-site annotations must keep the \
                 parameter bound:\n{}",
                leaked.join("\n")
            );
        }

        // ------------------------------------------------------------------
        // F6 residual checks: backtick declarations and uses must agree on ONE
        // identifier in every position — `fun` names, `val` names, and call
        // shapes.
        // ------------------------------------------------------------------

        /// `` fun `DeadUseCase`() `` declares the function — a later
        /// `` `DeadUseCase`() `` call binds the local, never the class.
        #[test]
        fn backtick_escaped_fun_names_still_shadow() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "fun `DeadUseCase`() = 0\nfun f() = `DeadUseCase`()",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "a declared `` `DeadUseCase` `` function must shadow the use — the \
                 backtick form of `fun` is the same declaration"
            );
        }

        /// `` `a b` `` (spaced) is one identifier — the `val` binds it and a real
        /// `DeadUseCase()` elsewhere keeps minting.
        #[test]
        fn spaced_backtick_names_and_real_calls_stay_consistent() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "class Registry {\n  val `a b` = 0\n  fun run() = DeadUseCase()\n}\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`` `a b` `` binds only itself — `DeadUseCase()` still mints"
            );
        }

        // ------------------------------------------------------------------
        // F7 residual checks: `$` prefixes reach `code` ONLY through shorthand
        // interpolation — `'$'` char literals are masked, `$$`/chain shapes all
        // resolve in the property namespace, and real consumers after a
        // shorthand keep minting.
        // ------------------------------------------------------------------

        /// `'$'` contributes no `$` to `code`, `$$x` is a literal `$` plus a
        /// shorthand, and `"$x$DeadUseCase"`/`"${x}$DeadUseCase"` chains all
        /// resolve in the property namespace — none consume the class.
        #[test]
        fn dollar_prefixes_outside_shorthands_never_reach_code() {
            let mut leaked = Vec::new();
            for consumer in [
                // char literal `$` — masked, only the shorthand remains
                "val c = '$'\nfun f() = \"c$c$DeadUseCase\"",
                // `$$` — literal `$` then a shorthand read
                "fun f() = \"$$DeadUseCase\"",
                // `${x}` then a shorthand — still property namespace
                "fun f(x: Int) = \"${x}$DeadUseCase\"",
                // shorthand chain — `x` then `DeadUseCase`, both property reads
                "fun f(x: Int) = \"$x$DeadUseCase\"",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "every `$`-adjacent name resolves in Kotlin's property namespace — \
                 none may mint a class consumer:\n{}",
                leaked.join("\n")
            );
        }

        /// Green controls: real binding positions after/around a shorthand —
        /// the `}` sentinel must not swallow them.
        #[test]
        fn real_consumers_around_shorthands_keep_minting() {
            let mut dropped = Vec::new();
            for consumer in [
                "fun f(x: Int) = \"$x\" + DeadUseCase()",
                "fun f(x: Int) = \"$x\".let { DeadUseCase() }",
                "fun f() = \"${ DeadUseCase() }\"",
                // `as?` cast — `?` before the name is not an exclusion
                "fun f(x: Any) = \"$x\" as? DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "real consumers adjacent to a shorthand literal must keep minting:\n{}",
                dropped.join("\n")
            );
        }
    }

    // adversarial re-audit (round 9) of the P8-F2 five-layer gate fixes landed by
    // 22-修复-门禁五层 (R8-1..R8-5 + B3). Fixture harness mirrors reaudit8.
    // RED rows document live coverage holes; GREEN rows lock fail-closed
    // directions and honest binding positions.
    //
    // Probed surfaces:
    //  - `untyped_setter_slot` exclusivity: `set(X)` must own the whole slot —
    //    but Kotlin's setter grammar is `'(' parameter (',' | ')')'` — a
    //    TRAILING COMMA `set(value,)` is legal (kotlinc 2.4.20 verified) and the
    //    name still binds the parameter. The `)`-exclusive check unbinds it →
    //    a bare use mints a phantom consumer (fail-open).
    //  - `InterpolationBoundary` in `prev/next_significant`: a dotted tail cut
    //    by ANY non-identifier token (`)`, `]`, IB) still binds when the tail
    //    spells the package — `f(0).com.pkg.X`/`x[0].com.pkg.X`/`"$x".com.pkg.X`
    //    are MEMBER chains, not qualified names — a phantom consumer (fail-open,
    //    pre-existing in `qualified_tail_binds`; IB is one more terminator).
    //  - `enum_body_start` keyword bail: `enum class E constructor(…)` /
    //    `private constructor(…)` is legal Kotlin (kotlinc verified) — the
    //    `constructor` keyword hits the next-declaration break at paren-depth 0
    //    → "never opens a body" bail on a parseable file (fail-closed
    //    misreport: the gate errors for the wrong reason).
    //  - B3 direction lock: the `set(Ident)` residual must keep failing CLOSED
    //    (a suppressed real consumer reports unreachable — loud noise, never a
    //    silent pass).

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod slot_boundaries {
        use std::fs;
        use std::path::Path;

        fn write(root: &Path, relative: &str, content: &str) {
            let path = root.join(relative);
            fs::create_dir_all(path.parent().expect("fixture parent")).expect("fixture mkdir");
            fs::write(&path, content).expect("fixture file");
        }

        fn usecase_fixture(root: &Path, consumer: &str) {
            write(
                root,
                "apps/android/domain/src/usecase/DeadUseCase.kt",
                "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
            );
            write(
                root,
                "apps/android/app/src/Consumer.kt",
                &format!(
                    "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n{consumer}\n"
                ),
            );
        }

        // ------------------------------------------------------------------
        // `set(X,)` — a LEGAL Kotlin setter declaration (setter grammar accepts
        // `(',' | ')')` after the parameter; verified against kotlinc 2.4.20:
        // `set(DeadUseCase,) { field = DeadUseCase }` compiles clean). The
        // token scan's `)`-exclusivity check unbound the parameter and minted
        // a phantom consumer (fail-open). `tree-sitter-kotlin-ng` rejects the
        // shape outright — the parse bail keeps the fail-closed verdict; only
        // unparametered-gap shapes (`set (X)`) still exercise the binding path.
        // ------------------------------------------------------------------

        /// `set(DeadUseCase,)` — trailing-comma setter parameter. Kotlin binds
        /// the parameter; under the token scan the gate minted the name as a
        /// call argument instead. `tree-sitter-kotlin-ng` does not accept a
        /// trailing comma in a *setter* parameter list at all, so those shapes
        /// now bail on the parse error — still the fail-closed direction (the
        /// file can never mint a phantom consumer). `set (DeadUseCase)` spaced
        /// keeps exercising the real `setter` binding path.
        #[test]
        fn a_setter_parameter_with_a_trailing_comma_still_binds() {
            let mut leaked = Vec::new();
            for consumer in [
                // legal Kotlin trailing-comma setters — `tree-sitter-kotlin-ng`
                // rejects them outright, so the honest verdict is the parse bail
                "class Registry {\n  var v: Any = 0\n    set(DeadUseCase,) { field = DeadUseCase }\n}\n",
                "class Registry {\n  var v: Any = 0\n    set(DeadUseCase ,) { field = DeadUseCase }\n}\n",
                // space before the paren is also legal setter syntax — parses
                // and binds the parameter (unreachable → Err for real)
                "class Registry {\n  var v: Any = 0\n    set (DeadUseCase) { field = DeadUseCase }\n}\n",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: `set(param,)` is a legal Kotlin setter — the \
                 `)`-exclusivity check unbinds the parameter and the bare name \
                 mints a phantom consumer (the class is never consumed):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// `set(DeadUseCase)` as a CALL binds its argument for real — the old
        /// token scan misread any bare `set(Ident)` as a setter declaration and
        /// suppressed the consumer (the documented B3 fail-closed residual).
        /// The AST distinguishes `setter` nodes from call sites structurally:
        /// inside a `call_expression`'s `value_argument` the identifier is an
        /// ordinary expression operand, so the consumer mints and the gate
        /// answers `Ok` — the honest verdict for a wired usecase.
        #[test]
        fn a_bare_set_ident_call_binds_its_argument() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "private fun set(x: Any) {}\nfun dispatch() = set(DeadUseCase)",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`set(DeadUseCase)` as a CALL binds the argument — the AST reads \
                 it as a `value_argument`, not a `setter` parameter, so the real \
                 consumer must mint"
            );
        }

        /// Green controls: argument expressions around `set` keep minting —
        /// two-argument calls, calls with expressions, named-argument values,
        /// and `set("${DeadUseCase()}")` interpolations.
        #[test]
        fn set_call_shapes_keep_minting_real_consumers() {
            let mut dropped = Vec::new();
            for consumer in [
                "private fun set(x: Any, y: Any) {}\nfun dispatch() = set(DeadUseCase, 1)",
                "private fun set(x: Any) {}\nfun dispatch() = set(DeadUseCase())",
                "private fun set(x: Any) {}\nfun dispatch() = set(v = DeadUseCase)",
                "private fun set(x: Any) {}\nfun dispatch() = set(\"${DeadUseCase()}\")",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "real consumers in `set` argument position must keep minting:\n{}",
                dropped.join("\n")
            );
        }

        /// Green controls: positions that never bind the class stay unreachable —
        /// a named-argument LABEL (`DeadUseCase = …` binds the parameter name,
        /// not the type), a `"$DeadUseCase"` shorthand (property namespace), and
        /// a `::class` reference.
        #[test]
        fn set_adjacent_positions_stay_non_binding() {
            let mut minted = Vec::new();
            for consumer in [
                "private fun set(x: Any) {}\nfun dispatch() = set(DeadUseCase = 1)",
                "private fun set(x: Any) {}\nfun dispatch() = set(\"$DeadUseCase\")",
                "private fun set(x: Any) {}\nfun dispatch() = DeadUseCase::class",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    minted.push(consumer);
                }
            }
            assert!(
                minted.is_empty(),
                "labels, shorthand reads and references never mint a consumer:\n{}",
                minted.join("\n")
            );
        }

        // ------------------------------------------------------------------
        // `qualified_tail_binds` walks the dotted tail backward from the name —
        // stopping at the first non-identifier token. `f(0).com.pkg.X`,
        // `x[0].com.pkg.X`, `"$x".com.pkg.X` are *member chains*: Kotlin binds
        // each segment as a member of the receiver, never as package elements.
        // The walk cannot see the receiver — the tail still spells the package
        // and the name mints a phantom consumer (fail-open). The
        // `InterpolationBoundary` is one more cut terminator — same mechanism,
        // exercised on the token this round introduced.
        // ------------------------------------------------------------------

        /// `f(0).com.lomo.domain.usecase.DeadUseCase` — every segment after the
        /// receiver is a member access; the domain class is never bound. The
        /// tail `com.lomo.domain.usecase` still spells the package, so the walk
        /// mints a consumer Kotlin never produced.
        #[test]
        fn an_expression_rooted_dotted_tail_must_not_mint_a_consumer() {
            let mut leaked = Vec::new();
            for consumer in [
                // call receiver
                "fun f(i: Int): Any = i\nfun dispatch() = f(0).com.lomo.domain.usecase.DeadUseCase",
                // index receiver
                "fun dispatch(xs: List<Any>) = xs[0].com.lomo.domain.usecase.DeadUseCase",
                // string receiver cut by the InterpolationBoundary token
                "fun dispatch(x: Int) = \"$x\".com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a member chain rooted at an expression still mints \
                 when the tail spells the package — `expr.com.pkg.X` binds \
                 members, never the domain type (phantom consumer):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// Green control: an identifier-anchored chain does NOT mint — the walk
        /// includes the receiver name, so `x.com.pkg.X` ≠ the package.
        #[test]
        fn an_identifier_anchored_dotted_tail_stays_non_binding() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "fun dispatch(x: Any) = x.com.lomo.domain.usecase.DeadUseCase",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "`x.com.pkg.X` — the chain includes `x`, the tail ≠ package — \
                 member access must stay non-binding"
            );
        }

        // ------------------------------------------------------------------
        // `enum_body_start` keyword bail: `constructor` is in the
        // next-declaration break list — but `enum class E constructor(…)` and
        // `enum class E private constructor(…)` carry the constructor INSIDE
        // the header (kotlinc-verified legal Kotlin). The bail reports "header
        // never opens a body" on a parseable file — a fail-closed misreport:
        // the gate must answer the use-case question, not an unrelated bail.
        // ------------------------------------------------------------------

        /// `enum class Registry constructor(…)` — entries still bind, `DeadUseCase`
        /// inside the enum resolves to the entry — the expected failure is the
        /// *use-case violation*, not an unaccountable-shape bail.
        #[test]
        fn an_explicit_enum_constructor_must_not_bail_the_entry_scan() {
            let mut bailed = Vec::new();
            for consumer in [
                "enum class Registry constructor(val tag: String) {\n  A(\"a\"),\n  DeadUseCase(\"d\");\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry private constructor(val tag: String) {\n  A(\"a\"),\n  DeadUseCase(\"d\");\n  fun f() = DeadUseCase\n}\n",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => bailed.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                bailed.is_empty(),
                "FAIL-CLOSED MISREPORT: `enum class E constructor(…)` is legal \
                 Kotlin — `constructor` must not end the header scan; the gate \
                 should report the use-case violation, not bail:\n{}",
                bailed.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // `InterpolationBoundary` structural-consumer matrix: `"$x"`/`"${x}"`
        // inside every enum entry shape must keep the entry region open — the
        // inert marker carries no pairing power anywhere it can appear.
        // ------------------------------------------------------------------

        /// Entries after every interpolation-bearing shape stay bound —
        /// argument parens and entry class bodies, `${}`-interpolations
        /// included.
        #[test]
        fn entries_after_every_interpolation_shape_stay_bound() {
            let mut leaked = Vec::new();
            for consumer in [
                // `${}` inside entry args — the real `}` token must balance
                "val x = \"v\"\nenum class Registry(val tag: String) {\n  A(\"${x}\"),\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
                // `${}` inside an entry class body
                "val x = \"v\"\nenum class Registry {\n  A { fun k() = \"${x}\" },\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
                // shorthand in the enum header's own parens
                "val y = \"v\"\nenum class Registry(val tag: String = \"$y\") {\n  A,\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
                // interleaved shorthand + `${}` in one arg list
                "val x = \"v\"\nenum class Registry(val tag: String) {\n  A(\"$x\", \"${x}\"),\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "entries after interpolation-bearing shapes must stay bound — the \
                 `DeadUseCase` entry shadows the bare use in the enum body:\n{}",
                leaked.join("\n---\n")
            );
        }

        /// A `const`-string annotation on an entry (no interpolation — Kotlin
        /// annotations accept const expressions only) still lets the entry
        /// after it bind.
        #[test]
        fn an_annotated_entry_still_lets_the_next_entry_bind() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "enum class Registry {\n  @Deprecated(\"x\")\n  A,\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "`DeadUseCase` binds the entry — the class stays unreachable"
            );
        }

        /// `for` iterable containing a shorthand — the `in` header ends the
        /// name collection; the loop body's real consumer keeps minting.
        #[test]
        fn a_shorthand_in_the_iterable_does_not_hide_the_body_consumer() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "val x = \"v\"\nfun dispatch() {\n  for (i in listOf(\"$x\")) {\n    DeadUseCase()\n  }\n}",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`DeadUseCase()` inside the loop body is a real consumer — the \
                 `in` boundary must not swallow it"
            );
        }
    }
}
