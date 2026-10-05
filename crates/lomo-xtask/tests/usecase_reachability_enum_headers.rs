//! Adversarial usecase-reachability probes on Kotlin structure the gate
//! must parse honestly: qualified reference tails, enum-body capture under
//! `by` delegates and other `{` producers, operand-boundary runs, and the
//! import/package preamble. Merged from the numbered re-audit rounds.

#[cfg(test)]
mod tests {

    // adversarial re-audit (round 10) of the P9-F2 six-layer gate fixes landed by
    // 24-修复-门禁六层 (N9-1..N9-3). Fixture harness mirrors reaudit9.
    // RED rows document live coverage holes; GREEN rows lock fail-closed
    // directions, honest binding positions, and the residual semantics the fix
    // round documented.
    //
    // Probed surfaces:
    //  - `qualified_tail_binds` (N9-2 fix): the chain root must be a bare
    //    identifier — but Kotlin's real rule is that the root must resolve in the
    //    PACKAGE namespace. A bare identifier bound in file scope (`val com`,
    //    `fun f(com: T)`, `class com`, `import a.B as com`) still roots a MEMBER
    //    chain — kotlinc 2.4.20 verified: `val com = …; com.lomo…X` resolves the
    //    local and fails "unresolved reference" on the next segment (never falls
    //    back to the package), and a `com` whose members spell the package text
    //    compiles while binding member values, never the domain class
    //    (fail-open phantom consumer).
    //  - `enum_body_start` (N9-3 fix): `constructor` left the break list, but the
    //    header's `{`-at-depth-0 match still assumes no brace can precede the
    //    body — `by` delegates take EXPRESSIONS: `by if (c) d else { d }`,
    //    `by try { d } catch …`, `by when (c) { … }` all compile (kotlinc
    //    2.4.20 verified) and the delegate's `{` is grabbed as the enum body →
    //    real entries never bind → entry-name uses mint (fail-open). `by object
    //    : I {}` is also legal and hits `object` in the break list → bail
    //    (fail-closed misreport — same family N9-3 fixed for `constructor`).
    //  - `untyped_setter_slot` (N9-1 fix): `,` closes the slot only when its next
    //    significant token is `)` — multiline/spaced/annotated setter shapes must
    //    keep binding; argument-list shapes keep minting.

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod qualified_tails {
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
        // N10-1 — package-root shadowing (fail-open, N9-2 fix incomplete).
        // `qualified_tail_binds` verifies the root is an *identifier token*, but
        // Kotlin requires the root to resolve in the package namespace: any
        // file-scope binding of the root identifier captures the whole chain as
        // member access. kotlinc 2.4.20:
        //   `val com = Any()` + `com.lomo.domain.usecase.X`
        //     → error: unresolved reference 'lomo' on receiver of type 'Any'
        //     (the local won; no package fallback is attempted)
        //   `val com = C(L(D()))` with a nested member chain spelling the package
        //     → COMPILES — `X` binds the member val, never the domain class.
        // ------------------------------------------------------------------

        /// A file-scope `val`/`param`/`class`/`import-as` binding of the package's
        /// root segment makes the qualified tail a member chain — the gate must
        /// not mint a consumer Kotlin never produced.
        #[test]
        fn a_shadowed_package_root_never_resolves_the_qualified_tail() {
            let mut leaked = Vec::new();
            for consumer in [
                // top-level `val com` shadows the package root segment — the
                // exact shape kotlinc 2.4.20 rejected with "unresolved
                // reference 'lomo' on receiver of type 'Any'" (the local won;
                // the package was never consulted)
                "val com = Any()\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // a parameter does the same inside its body
                "fun dispatch(com: Any) = com.lomo.domain.usecase.DeadUseCase",
                // a lowercase class name is a classifier binding of the root —
                // `com.lomo` resolves into its companion/nested members
                "class com { companion object { val lomo = 0 } }\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // an import alias binds the root identifier to a foreign type —
                // this shadow is invisible to `locals` (alias names live in the
                // imports table), so even a locals-aware fix must look here too
                "import com.lomo.app.Holder as com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a file-scope binding of the package's root segment \
                 turns `com.lomo.domain.usecase.X` into a member chain — Kotlin \
                 binds the value/classifier, never the package (phantom \
                 consumer):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// Green control: only the ROOT segment shadows — a local named like a
        /// middle segment cannot capture the chain (`com` still resolves as the
        /// package's first segment), so the qualified tail mints a real consumer.
        #[test]
        fn a_middle_segment_shadow_does_not_break_the_qualified_tail() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "val lomo = 0\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`com.lomo.domain.usecase.DeadUseCase` with only `lomo` shadowed \
                 is still a real package-qualified consumer — must keep minting"
            );
        }

        // ------------------------------------------------------------------
        // N10-2 — `by` delegate expressions inside enum headers (fail-open +
        // fail-closed misreport). Kotlin's delegation specifier takes an
        // arbitrary EXPRESSION; `by` forms verified legal on kotlinc 2.4.20:
        //   enum class E : I by if (c) d else { d } { A; … }      // compiles
        //   enum class E : I by try { d } catch (e: T) { d } { …} // compiles
        //   enum class E : I by when (c) { true -> d else -> d }… // compiles
        //   enum class E : I by object : I { … } { A; … }         // compiles
        // `enum_body_start` grabs the first `{` at depth 0 — the delegate's block
        // — so the real body is never scanned as entries. `object` additionally
        // sits in the break list and bails outright (the N9-3 family left over).
        // ------------------------------------------------------------------

        /// `{ }` inside a `by` delegate expression is grabbed as the enum body —
        /// the real entry list is never bound, so `DeadUseCase` in a real entry
        /// (and its shadowed body use) mint a phantom consumer (fail-open).
        #[test]
        fn a_delegate_expression_brace_must_not_end_the_enum_header() {
            let mut leaked = Vec::new();
            for consumer in [
                // `by if … else { … }` — the else-block is an expression brace
                // (`interface`/`override` bodies multi-line: the grammar needs a
                // separator before `}`)
                "interface Iface {\n  fun k()\n}\nval d: Iface = TODO()\nenum class Registry : Iface by if (true) d else { d } {\n  DeadUseCase;\n  override fun k() { DeadUseCase }\n}\n",
                // `by try { … } catch …` — the try-block brace is an expression
                "interface Iface {\n  fun k()\n}\nval d: Iface = TODO()\nenum class Registry : Iface by try { d } catch (e: Exception) { d } {\n  DeadUseCase;\n  override fun k() { DeadUseCase }\n}\n",
                // `by when (c) { … }` — the when-body brace is an expression
                "interface Iface {\n  fun k()\n}\nval d: Iface = TODO()\nenum class Registry : Iface by when (true) { true -> d else -> d } {\n  DeadUseCase;\n  override fun k() { DeadUseCase }\n}\n",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a `by` delegate expression's `{{` is grabbed as the \
                 enum body — the real entry never binds, so the entry name mints \
                 a phantom consumer on legal Kotlin (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// `by object : I { … }` is legal Kotlin (kotlinc-verified); `object`
        /// still sits in `enum_body_start`'s break list — the scan bails with
        /// "header never opens a body" instead of answering the use-case
        /// question. Fail-closed, but the wrong failure on a parseable file —
        /// the same defect family N9-3 fixed for `constructor`.
        #[test]
        fn an_anonymous_object_delegate_must_not_bail_the_entry_scan() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "interface Iface {\n  fun k()\n}\nenum class Registry : Iface by object : Iface {\n  override fun k() {}\n} {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            );
            match lomo_xtask::check_usecase_reachability(dir.path()) {
                Err(error) => assert!(
                    format!("{error:#}").contains("production consumers"),
                    "FAIL-CLOSED MISREPORT: `enum class E : I by object : I {{}}` \
                     is legal Kotlin — the gate should report the use-case \
                     violation, not bail: {error:#}"
                ),
                other => panic!("expected the use-case violation, got {other:?}"),
            }
        }

        /// Green control: a parenthesized delegate keeps the `{` inside parens —
        /// the real body is found and entries bind (fail-closed violation).
        #[test]
        fn a_parenthesized_delegate_still_binds_the_real_entries() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "interface Iface\nenum class Registry : Iface by (run { TODO() }) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            );
            match lomo_xtask::check_usecase_reachability(dir.path()) {
                Err(error) if format!("{error:#}").contains("production consumers") => {}
                other => panic!("the real entry must bind and shadow — got {other:?}"),
            }
        }

        // ------------------------------------------------------------------
        // N9-1 residual matrix: every legal single-parameter setter shape keeps
        // binding (Err), every argument-expression shape keeps minting (Ok).
        // ------------------------------------------------------------------

        /// Multiline/spaced/annotated trailing-comma setters — the comma's next
        /// significant token is `)` in every legal form, so the name keeps
        /// binding the parameter (fail-closed on the usecase).
        #[test]
        fn every_legal_trailing_comma_setter_shape_stays_bound() {
            let mut leaked = Vec::new();
            for consumer in [
                // ktlint's canonical multiline form
                "class Registry {\n  var v: Any = 0\n    set(\n      DeadUseCase,\n    ) { field = DeadUseCase }\n}\n",
                // space after the comma
                "class Registry {\n  var v: Any = 0\n    set(DeadUseCase, ) { field = DeadUseCase }\n}\n",
                // annotation-prefixed parameter with trailing comma
                "class Registry {\n  var v: Any = 0\n    set(@Suppress(\"x\") DeadUseCase,) { field = DeadUseCase }\n}\n",
                // visibility modifier on the setter itself
                "class Registry {\n  var v: Any = 0\n      private set(DeadUseCase,) { field = DeadUseCase }\n}\n",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "legal trailing-comma setter shapes must keep the parameter \
                 bound (fail-closed):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// Argument-list shapes keep minting: member calls, named arguments with
        /// a trailing comma (legal call syntax), two-argument lists — and the
        /// illegal `set(X,,)` whose comma does not close the list (the file can
        /// never compile; minting is the documented direction).
        #[test]
        fn comma_forms_that_do_not_close_the_slot_keep_minting() {
            let mut dropped = Vec::new();
            for consumer in [
                "class Registry {\n  var v = 0\n}\nfun dispatch(r: Registry) = r.set(DeadUseCase, 1)",
                "private fun set(x: Any, y: Any) {}\nfun dispatch() = set(DeadUseCase, 1)",
                // named-argument call with trailing comma — `name =` breaks the
                // slot walk, `DeadUseCase` is the argument value
                "private fun set(name: Any) {}\nfun dispatch() = set(name = DeadUseCase,)",
                // member call with a legal trailing comma
                "class Registry {\n  fun set(vararg x: Any) {}\n}\nfun dispatch(r: Registry) { r.set(DeadUseCase,) }",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "argument-list `set` shapes must keep minting real consumers:\n{}",
                dropped.join("\n---\n")
            );
        }

        /// `set(X,,)` — a double comma is not a slot-closing comma; the source
        /// can never compile. Under the token scan the argument still minted
        /// (the loud direction); under the AST the file fails to parse and bails
        /// fail-closed with the file name — the strictest possible answer.
        #[test]
        fn an_uncompilable_double_comma_call_bails_on_parse() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "private fun set(x: Any, y: Any) {}\nfun dispatch() = set(DeadUseCase,,)",
            );
            let error = lomo_xtask::check_usecase_reachability(dir.path())
                .expect_err("uncompilable Kotlin must bail");
            assert!(
                format!("{error:#}").contains("Kotlin parse error"),
                "the bail names the file and its unaccountable syntax: {error:#}"
            );
        }

        // ------------------------------------------------------------------
        // N9-2 residual matrix: every non-identifier chain root stays
        // non-binding; real package-qualified tails keep minting.
        // ------------------------------------------------------------------

        /// Every expression-rooted chain shape keeps failing closed — safe-call,
        /// not-null, parenthesized receivers and line-leading continuation dots
        /// all root the chain on a member access, never a package.
        #[test]
        fn every_expression_root_shape_stays_non_binding() {
            let mut leaked = Vec::new();
            for consumer in [
                // parenthesized receiver
                "fun dispatch(x: Any) = (x).com.lomo.domain.usecase.DeadUseCase",
                // safe-call receiver
                "fun dispatch(x: Any?) = x?.com.lomo.domain.usecase.DeadUseCase",
                // not-null receiver
                "fun dispatch(x: Any?) = x!!.com.lomo.domain.usecase.DeadUseCase",
                // `${}`-interpolation receiver (the `}` boundary)
                "fun dispatch(x: Int) = \"${x}\".com.lomo.domain.usecase.DeadUseCase",
                // line-leading continuation dot on an expression receiver
                "fun dispatch(x: Any) = x\n  .com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "expression-rooted dotted tails must stay non-binding:\n{}",
                leaked.join("\n---\n")
            );
        }

        /// Green controls: honest qualified tails keep minting — a parenthesized
        /// qualified name (the `(x).com…` counterpart), an `is` check, a
        /// wildcard-import bare use, and a `return`-position tail.
        #[test]
        fn honest_qualified_tails_keep_minting() {
            let mut dropped = Vec::new();
            for consumer in [
                "fun dispatch() = (com.lomo.domain.usecase.DeadUseCase)::class.simpleName",
                "fun dispatch(x: Any) = x is com.lomo.domain.usecase.DeadUseCase",
                "fun dispatch(): Any = com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    dropped.push(consumer);
                }
            }
            assert!(
                dropped.is_empty(),
                "real package-qualified consumers must keep minting:\n{}",
                dropped.join("\n")
            );
        }

        /// A `typealias` right-hand side is a non-binding mention — the
        /// qualified tail in `typealias T = com.pkg.X` must not mint (the
        /// declaration is skipped, not the tail's semantics).
        #[test]
        fn a_typealias_rhs_tail_stays_non_binding() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "typealias Alias = com.lomo.domain.usecase.DeadUseCase",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_err(),
                "`typealias` mentions never mint a consumer"
            );
        }

        // ------------------------------------------------------------------
        // N9-3 residual matrix: header components flow, next-declaration
        // keywords still bail.
        // ------------------------------------------------------------------

        /// Annotation-prefixed and secondary constructors keep the scan honest:
        /// `@Anno constructor(…)` is a legal header component (kotlinc-verified)
        /// and a secondary `constructor` inside the body is never reached — both
        /// must still report the use-case violation, not bail.
        #[test]
        fn constructor_header_variants_keep_the_entry_scan_alive() {
            let mut bailed = Vec::new();
            for consumer in [
                // annotated primary constructor
                "annotation class Anno\nenum class Registry @Anno constructor(val tag: String) {\n  DeadUseCase(\"d\");\n  fun f() = DeadUseCase\n}\n",
                // secondary constructor inside the body — `{` ends the header
                // before it, so its presence must not disturb the entry scan
                "enum class Registry(val tag: String) {\n  DeadUseCase(\"d\");\n  constructor() : this(\"x\")\n  fun f() = DeadUseCase\n}\n",
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
                "constructor variants must not disturb the entry scan — the gate \
                 should report the use-case violation:\n{}",
                bailed.join("\n---\n")
            );
        }

        /// The bail semantics survive: a header that never opens its enum body
        /// is an unaccountable declaration shape. `enum class Registry\nfun f()`
        /// parses cleanly (a bodyless `enum class` header followed by a `fun`)
        /// and is rejected by the gate's structural `enum_class_body` check;
        /// `enum class Registry val x = …` is malformed even to the grammar
        /// (two declarations on one line need a separator) and fails on the
        /// parse error. Both directions fail closed, naming the file.
        #[test]
        fn a_headerless_enum_still_bails_fail_closed() {
            for (consumer, expected) in [
                (
                    "enum class Registry\nfun f() = DeadUseCase\n",
                    "without a visible enum-class body",
                ),
                (
                    "enum class Registry val x = DeadUseCase\n",
                    "Kotlin parse error",
                ),
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                let error = match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) => format!("{error:#}"),
                    Ok(()) => panic!("headerless enum must keep failing closed"),
                };
                assert!(
                    error.contains("Consumer.kt") && error.contains(expected),
                    "the bail must name the file and its unaccountable shape, \
                     got: {error}"
                );
            }
        }
    }

    // adversarial re-audit (round 11) of the P10-F2 seven-layer gate fixes landed by
    // 26-修复-门禁七层 (N10-1 `qualified_tail_binds` scope check, N10-2
    // `DelegateContext`/`expr_brace`/`object_stack` state machine). Fixture harness
    // mirrors reaudit10. RED rows document live coverage holes verified against
    // kotlinc 2.4.20 (/tmp/kprobe11); GREEN rows lock fail-closed directions,
    // honest binding positions, and documented residuals.
    //
    // Probed surfaces:
    //  - `${ }` template interpolation inside a `by` delegate expression: the
    //    lexer emits `${`/`}` as real Punct tokens; a `{` at expression depth
    //    zero whose previous token is `$` is NOT a block keyword and NOT an
    //    `object` body — the state machine grabs it as the enum body anyway
    //    (kotlinc-verified compiling shapes, fail-open phantom entries).
    //  - Keyword-named members / supertypes / enum names / callables: the new
    //    block-keyword sets (`else|try|finally|do|when` after an identifier,
    //    `if|when|catch|for|while` owning a `(`) never check whether the keyword
    //    sits in member/type/name position — `d.finally`, `d.`when``, `catch(1)`,
    //    `interface `when``, `enum class `finally`` are all COMPILING Kotlin
    //    (kotlinc-verified) that the pre-N10-2 `first-{`-rule answered correctly;
    //    they now converge to the EOF bail (fail-closed misreport, regression).
    //  - `qualified_tail_binds` root binding: the `scope.imports` name check can
    //    never match `*`, but `import a.b.*` introduces every public name of a.b
    //    — including a top-level `val com` that captures the chain root
    //    (kotlinc-verified: `com.lomo…X` fails "unresolved reference 'lomo' on
    //    receiver of type 'Any'" — the star-imported value won). Mint direction
    //    stays optimistic (fail-open on unknowable data).
    //  - Documented residuals re-pinned: cross-file `val com` (per-file scope,
    //    declared), non-value root bindings (`fun com`, member `val com`,
    //    enum-entry `com` …) all suppress the tail — the documented file-wide
    //    over-report direction. `object : finally` was reclassified by round 11
    //    as an N11-3 family member and now reports the violation.

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod delegate_braces {
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

        /// Shared Kotlin prelude. `tree-sitter-kotlin-ng` requires a separator
        /// (newline or `;`) between a member and its enclosing `}` — the bodies
        /// below are written multi-line so the fixtures parse cleanly.
        fn enum_prelude(member_decls: &str, enum_decl: &str) -> String {
            format!(
                "interface Iface {{\n  fun k()\n}}\n\
                 val d: Iface = object : Iface {{\n  override fun k() {{}}\n}}\n\
                 {member_decls}\n{enum_decl}\n"
            )
        }

        /// True iff the gate reports the use-case violation (the honest answer),
        /// false for a minted phantom (Ok) or a structural bail.
        fn reports_violation(result: &anyhow::Result<()>) -> bool {
            match result {
                Err(error) => format!("{error:#}").contains("production consumers"),
                Ok(()) => false,
            }
        }

        // ------------------------------------------------------------------
        // N11-1 — `${ }` template interpolation inside a `by` delegate
        // expression (fail-open). `lex_kotlin` keeps `${`/`}` interpolation
        // braces as real code tokens; at expression-brace depth zero the `{`
        // after `$` matches neither `delegate_expression_block` (prev is `$`,
        // not a keyword or `)`) nor the object-body path in `Expression`
        // context — so `enum_brace_is_body` declares it the enum body.
        // kotlinc 2.4.20: all shapes below COMPILE — the delegate's string is a
        // legal CharSequence (or Iface via an interface the literal type can
        // satisfy); the enum body that follows is real.
        // ------------------------------------------------------------------

        /// `by "${d}"` — the `${` brace is grabbed as the enum body, `d` is
        /// scanned as a fake entry, and the REAL `{ DeadUseCase; … }` body is
        /// never entered: its entries mint as ordinary uses (fail-open).
        #[test]
        fn a_template_interpolation_in_a_delegate_steals_the_enum_body() {
            let mut leaked = Vec::new();
            for consumer in [
                // plain string template delegate — `enum class E : CharSequence
                // by "${d}" { A }` compiles (String <: CharSequence)
                "val d = \"x\"\nenum class Registry : CharSequence by \"${d}\" {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // deeper template payload — `${d.let({ it })}` (kotlinc: a bare
                // trailing lambda `let { }` inside `${}` is illegal even in a
                // delegate, the parenthesized call compiles) still opens at `$`
                "val d = \"x\"\nenum class Registry : CharSequence by \"${d.let({ it })}\" {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // ObjectExpression context — `object : CharSequence by "${d}"`:
                // the `${` pops as the anonymous-class body, the real object
                // `{ }` is then grabbed as the enum body, and the real body mints
                "val d = \"x\"\nenum class Registry : CharSequence by object : CharSequence by \"${d}\" { } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // interpolation inside a delegate call's parens is safe — GREEN
                // control: `"${x}"` inside parens rides paren depth, the real
                // body is found
                "fun f(s: CharSequence): Iface = TODO()\ninterface Iface {\n  fun k()\n}\nenum class Registry : Iface by f(\"${\"x\"}\") {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a `${{ }}` interpolation inside a `by` delegate \
                 expression is grabbed as the enum body — the real entry list \
                 never binds, minting phantom consumers on legal Kotlin \
                 (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N11-2 — `import a.b.*` wildcard channel for root capture (fail-open,
        // optimistic). `scope.imports` stores `("*","a.b")` for the wildcard —
        // `name == root` can never match `*`, but a wildcard introduces every
        // public name of the target package. kotlinc 2.4.20 verified
        // (/tmp/kprobe11, package a.b exporting `val com`):
        //   `com.lomo.domain.usecase.X` → error "unresolved reference 'lomo' on
        //   receiver of type 'Any'" — the star-imported value captures the
        //   chain exactly like the fixed `import a.B as com` alias did.
        // The gate cannot enumerate a.b's exports from this file, so the
        // wildcard is an unbounded introduced-name source checked by a rule
        // that structurally cannot see it — minting stays optimistic.
        // ------------------------------------------------------------------

        /// `import a.b.*` — a wildcard-borne `com` captures the qualified-tail
        /// root identically to the fixed alias channel, but the imports check
        /// can never match `*`: the tail still mints (fail-open).
        #[test]
        fn a_wildcard_import_can_still_capture_the_package_root() {
            let mut leaked = Vec::new();
            for consumer in [
                // wildcard importing a package that exports a top-level `com`
                // value — the chain is captured member-wise (kotlinc-verified)
                "import a.b.*\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // wildcard on a classifier — `import a.b.Holder.*` imports the
                // holder's members, `com` among them: same unbounded channel
                "import a.b.Holder.*\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    leaked.push(consumer);
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: `import a.b.*` can introduce a `com` binding the \
                 imports-name check can never see (`*` ≠ `com`) — the qualified \
                 tail keeps minting a phantom consumer (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N11-3 — keyword-named members/types/names mis-classified by the new
        // block-keyword sets (fail-closed misreport, REGRESSION). Neither
        // `delegate_expression_block` nor `delegate_context_step` checks member
        // position: `d.`kw``, `x.`kw``, `` `kw` `` supertypes, enum names and
        // `kw(…)` callables are all COMPILING Kotlin (kotlinc-verified batch)
        // that the pre-N10-2 `first-{`-as-body rule answered correctly. Now the
        // keyword read as expression syntax converges the scan to the EOF bail.
        // ------------------------------------------------------------------

        /// `d.<kw>` member names hit the bare-block keyword set or the break
        /// list — every shape below compiles (kotlinc-verified) yet the scan
        /// ends in "header never opens a body" instead of reporting entries.
        /// `d.finally` needs no backticks (soft keyword — real member names like
        /// `flow.catch`/`promise.finally` exist); the rest are backticked.
        #[test]
        fn a_keyword_named_member_in_a_delegate_still_bails() {
            let mut bailed = Vec::new();
            for member_name in [
                // soft keyword — legal WITHOUT backticks (regression: pre-fix
                // the first `{` was the body and the verdict was right)
                "finally",
                // backticked hard keywords — the new bare-block set
                "`when`",
                "`try`",
                "`do`",
                "`else`",
                // `object` — push/pop misclassifies member literals (same
                // bail verdict as the pre-fix break list, new mechanism)
                "`object`",
                // the break list — member-blind before and after
                "`val`",
                "`var`",
                "`class`",
                "`interface`",
                "`enum`",
                "`fun`",
                "`typealias`",
                "`import`",
                "`package`",
            ] {
                let consumer = format!(
                    "interface Iface {{\n  fun k()\n}}\n\
                     class Holder {{\n  val {member_name}: Iface = TODO()\n}}\n\
                     val h = Holder()\n\
                     enum class Registry : Iface by h.{member_name} {{\n  DeadUseCase;\n  fun f() = DeadUseCase\n}}\n"
                );
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => bailed.push(format!("{member_name} -> {other:?}")),
                }
            }
            assert!(
                bailed.is_empty(),
                "MISREPORT: a member named after a block/break keyword in the \
                 delegate position bails on compiling Kotlin — the pre-N10-2 \
                 scan answered these correctly (regression family):\n{}",
                bailed.join("\n---\n")
            );
        }

        /// `kw(…)` callables hit the `)`-arm keyword check: `by `when`(1)` and
        /// `by catch(1)` (catch is a SOFT keyword — `fun catch` needs no
        /// backticks) are compiling Kotlin whose `{` is the enum body, not a
        /// `when`/`catch` block — the parens belong to the call, not a control
        /// construct. Pre-N10-2 answered correctly; now EOF bail.
        #[test]
        fn a_keyword_named_callable_in_a_delegate_still_bails() {
            let mut bailed = Vec::new();
            for callable in [
                "`when`", // fun `when`(x: Int): Iface — compiles
                "`if`", "`for`", "`while`", "catch",    // soft keyword — legal unbackticked
                "`object`", // fun `object`(x) — `object` push + `(`-arm misread
            ] {
                let consumer = format!(
                    "interface Iface {{\n  fun k()\n}}\n\
                     fun {callable}(x: Int): Iface = TODO()\n\
                     enum class Registry : Iface by {callable}(1) {{\n  DeadUseCase;\n  fun f() = DeadUseCase\n}}\n"
                );
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => bailed.push(format!("{callable}(…) -> {other:?}")),
                }
            }
            assert!(
                bailed.is_empty(),
                "MISREPORT: a callable named after a `)`-arm keyword bails on \
                 compiling Kotlin — the parens are call args, not a control \
                 construct's condition (regression family):\n{}",
                bailed.join("\n---\n")
            );
        }

        /// The bare-block set is not even safe for the keywords' own lexical
        /// class: `finally`/`when`/`else`/`try`/`do` are usable as ordinary
        /// *expression* identifiers (soft `finally` needs no backticks) —
        /// `by finally`, ``by `when` ``, `by if (c) d else finally` all compile
        /// (kotlinc-verified) with `{` opening the enum body, yet the prev-token
        /// keyword read sends the scan into `expr_brace` and out to EOF bail.
        #[test]
        fn a_keyword_used_as_a_bare_delegate_identifier_still_bails() {
            let mut bailed = Vec::new();
            for (prelude, decl) in [
                // soft keyword as a plain expression identifier — unbackticked
                (
                    "val finally: Iface = TODO()",
                    "enum class Registry : Iface by finally {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                ),
                (
                    "val finally: Iface = TODO()",
                    "enum class Registry : Iface by if (true) d else finally {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                ),
                // backticked hard keywords as bare identifiers
                (
                    "val `when`: Iface = TODO()",
                    "enum class Registry : Iface by `when` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                ),
                (
                    "val `try`: Iface = TODO()",
                    "enum class Registry : Iface by `try` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                ),
                (
                    "val `do`: Iface = TODO()",
                    "enum class Registry : Iface by `do` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                ),
                (
                    "val `else`: Iface = TODO()",
                    "enum class Registry : Iface by `else` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                ),
            ] {
                let consumer = enum_prelude(prelude, decl);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => bailed.push(format!("{decl} -> {other:?}")),
                }
            }
            assert!(
                bailed.is_empty(),
                "MISREPORT: soft/backticked keywords as bare delegate identifiers \
                 are expression idents, not block keywords — the brace is the enum \
                 body, not a block, but the scan still bails on compiling Kotlin \
                 (regression family):\n{}",
                bailed.join("\n---\n")
            );
        }

        /// Fixture rows for `a_keyword_named_supertype_or_enum_name_still_bails`
        /// — every `(prelude, decl)` pair compiles under kotlinc and must report
        /// the usecase violation.
        const KEYWORD_SUPERTYPE_SHAPES: &[(&str, &str)] = &[
            // bare-block keyword supertypes — regression (pre-fix `{` = body)
            (
                "interface `when` {
      fun k()
    }",
                "enum class Registry : `when` {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            (
                "interface `else` {
      fun k()
    }",
                "enum class Registry : `else` {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            (
                "interface `try` {
      fun k()
    }",
                "enum class Registry : `try` {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            (
                "interface `do` {
      fun k()
    }",
                "enum class Registry : `do` {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            (
                "interface finally {
      fun k()
    }",
                "enum class Registry : finally {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            // break-list keyword supertypes — pre-existing same-verdict
            (
                "interface `object` {
      fun k()
    }",
                "enum class Registry : `object` {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            (
                "interface `val` {
      fun k()
    }",
                "enum class Registry : `val` {
      DeadUseCase;
      override fun k() {}
    }",
            ),
            // enum NAMES after block keywords — name_index resolves the
            // name but `{` still reads the previous Ident — regression
            (
                "",
                "enum class `when` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
            ),
            (
                "",
                "enum class `else` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
            ),
            (
                "",
                "enum class finally {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
            ),
            // object-supertype keyword names inside a delegate — `object :
            // `when` {}` compiles; the bare-block set misclassifies
            (
                "interface `when` : Iface",
                "enum class Registry : Iface by object : `when` {
      override fun k() {}
    } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
            ),
            (
                "interface finally : Iface",
                "enum class Registry : Iface by object : finally {
      override fun k() {}
    } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
            ),
        ];

        /// `` `kw` `` supertypes and enum names hit the same sets: `enum class
        /// E : `when``/`finally`/`object`/`val`` and `enum class `when`/
        /// `finally` { }` are compiling Kotlin (kotlinc-verified) that bail —
        /// the bare-block set regresses the keyword names it didn't see before,
        /// the break set keeps its pre-existing member-blindness.
        #[test]
        fn a_keyword_named_supertype_or_enum_name_still_bails() {
            let mut bailed = Vec::new();
            for &(prelude, decl) in KEYWORD_SUPERTYPE_SHAPES {
                let consumer = enum_prelude(prelude, decl);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => bailed.push(format!("{decl} -> {other:?}")),
                }
            }
            assert!(
                bailed.is_empty(),
                "MISREPORT: `` `kw` `` supertypes/enum names hit the keyword sets \
                 without a position check — legal Kotlin bails (regression for \
                 the bare-block set, pre-existing for the break list):\n{}",
                bailed.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // Documented residuals — GREEN locks of the declared directions.
        // ------------------------------------------------------------------

        /// `object : finally { }` — the round-11 fix's reclassified shape: the
        /// 26-round fix documented this as an isolated residual, but 27-终验
        /// pinned it as a member of the keyword-position family (N11-3). With
        /// the `}`-preceded rule for soft `finally`, the anonymous class body
        /// now binds correctly and the real entry list reports the violation.
        /// Direction lock updated from the documented bail to the violation —
        /// the residual no longer exists.
        #[test]
        fn the_reclassified_object_finally_shape_reports_the_violation() {
            let consumer = enum_prelude(
                "interface finally : Iface",
                "enum class Registry : Iface by object : finally {
      override fun k() {}
    } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
            );
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(dir.path(), &consumer);
            match lomo_xtask::check_usecase_reachability(dir.path()) {
                Err(error) => assert!(
                    format!("{error:#}").contains("production consumers"),
                    "reclassified shape: `object : finally` now binds the \
                     anonymous class body — the violation must report: {error:#}"
                ),
                other => panic!("expected the use-case violation, got {other:?}"),
            }
        }

        /// Cross-file package-root shadow — the declared per-file residual:
        /// a top-level `val com` in ANOTHER file of the same package captures
        /// `com` (kotlinc-verified: "unresolved reference 'lomo' on receiver of
        /// type 'Any'"), but `FileScope` is per-file — the tail keeps minting.
        /// Pin the declared fail-open direction.
        #[test]
        fn the_documented_cross_file_shadow_residual_still_mints() {
            let dir = tempfile::tempdir().expect("fixture");
            write(
                dir.path(),
                "apps/android/domain/src/usecase/DeadUseCase.kt",
                "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
            );
            // the shadow lives in a DIFFERENT file of the same package
            write(
                dir.path(),
                "apps/android/app/src/Shadow.kt",
                "package com.lomo.app\nval com = Any()\n",
            );
            write(
                dir.path(),
                "apps/android/app/src/Consumer.kt",
                "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase\n",
            );
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "documented residual: a cross-file `val com` stays invisible to \
                 the per-file scope — the tail still mints (declared fail-open)"
            );
        }

        // ------------------------------------------------------------------
        // File-wide `locals` over-report — consistent strict direction. A root
        // binding that does NOT capture the chain in Kotlin semantics still
        // suppresses the tail: `fun com` (a function name is not a value —
        // kotlinc-verified `com.lomo…X` COMPILES and binds the package), a
        // member `val com`, a `catch`/`for`/lambda/destructure/ctor param, an
        // enum entry `com`, a type parameter `com` — all fold into the
        // file-wide `locals` set and over-report (fail-closed, documented).
        // ------------------------------------------------------------------

        /// Non-capturing `com` bindings suppress the qualified tail — the
        /// documented file-wide over-report direction. `fun com()` compiles
        /// AND binds the package in Kotlin (kotlinc-verified); the gate still
        /// reports the violation — loud noise, never a silent pass.
        #[test]
        fn non_capturing_root_bindings_suppress_the_tail_fail_closed() {
            let mut minted = Vec::new();
            for consumer in [
                // `fun com()` — function names do NOT capture the chain
                // (kotlinc: `com.lomo…X` compiles and binds the package)
                "fun com() {}\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // member `val com` — file-wide locals over-reports
                "class Holder { val com = Any() }\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // `catch`/`for`/lambda/destructure parameter positions
                "fun dispatch() { try {} catch (com: Exception) {} }\nfun other() = com.lomo.domain.usecase.DeadUseCase",
                "fun dispatch(xs: List<Any>) { for (com in xs) {} }\nfun other() = com.lomo.domain.usecase.DeadUseCase",
                "val f = { com: Any -> }\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                "fun dispatch() { val (com, x) = Any() to Any() }\nfun other() = com.lomo.domain.usecase.DeadUseCase",
                // constructor parameter / type parameter / enum entry
                "class Holder(com: Any)\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                "fun <com> f(x: com) {}\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                "enum class Shade { com, other }\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // extension function name — `fun T.com()` binds `com` in locals
                // but never in the file's value namespace
                "fun Any.com() {}\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    minted.push(consumer);
                }
            }
            assert!(
                minted.is_empty(),
                "non-capturing `com` bindings must keep suppressing the tail — \
                 the documented file-wide over-report direction:\n{}",
                minted.join("\n---\n")
            );
        }

        /// Real capture channels keep suppressing — the N10-1 fix's own
        /// coverage, re-locked on the new shapes it read: `typealias com`
        /// (classifier), `import a.b.com` (member import name).
        #[test]
        fn capturing_root_bindings_stay_suppressed() {
            let mut minted = Vec::new();
            for consumer in [
                "class Holder\ntypealias com = Holder\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                "import a.b.com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                "import a.b.Holder as com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_ok() {
                    minted.push(consumer);
                }
            }
            assert!(
                minted.is_empty(),
                "capturing `com` bindings must keep suppressing the tail:\n{}",
                minted.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // State-machine correctness — the DelegateContext surface that works.
        // Every shape below COMPILES (kotlinc-verified batch) and must keep
        // reporting the violation: the `{` classification is exact at every
        // position the state machine was designed for.
        // ------------------------------------------------------------------

        /// Fixture shapes for `delegate_expression_shapes_still_find_the_real_body`
        /// — every delegate expression compiles under kotlinc and parses under
        /// tree-sitter-kotlin-ng; the `{` that follows is the enum body.
        const DELEGATE_EXPRESSION_SHAPES: &[&str] = &[
                // `throw` is an expression — `by (throw X())` keeps the
                // delegate-expression shape the grammar can account for; the
                // unparenthesized `by throw X()` (also compiling Kotlin) is a
                // tree-sitter-kotlin-ng residual pinned in
                // `a_bare_throw_delegate_bails_on_the_grammar`.
                "enum class Registry : Iface by (throw RuntimeException()) {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // index / not-null receivers
                "val arr = arrayOf<Iface>()\nenum class Registry : Iface by arr[0] {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "enum class Registry(val x: Iface?) : Iface by x!! { DeadUseCase(null) }",
                // nested object delegation — object_stack
                "enum class Registry : Iface by object : Iface by object : Iface {
      override fun k() {}
    } { } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // object literal inside when/if branches
                "enum class Registry : Iface by when (true) { true -> object : Iface {
      override fun k() {}
    } else -> d } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "enum class Registry : Iface by if (true) object : Iface {
      override fun k() {}
    } else d {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // try/finally without catch; multi-supertype object
                "enum class Registry : Iface by try { d } finally { d } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "enum class Registry : Iface, CharSequence by object : Iface, CharSequence {\n  override fun k() {}\n  override val length get() = 0\n  override fun get(i: Int) = ' '\n  override fun subSequence(s: Int, e: Int) = \"\"\n} {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // parenthesized / string-template-free delegates
                "enum class Registry : Iface by (d) {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // a plain string literal emits NO code tokens — `{` follows `by`
                "enum class Registry : CharSequence by \"x\" {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // shorthand `$x` template — no braces in code view
                "enum class Registry : CharSequence by \"$d\" {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // trailing lambda inside a `when` block — rides expr_brace
                "enum class Registry : Iface by when (true) { true -> run { d } else -> d } {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // keyword members that dodge both sets — `catch`/`by`/`if`/
                // `for`/`while` are not in the bare-block set
                "class Holder {\n  val catch: Iface = TODO()\n  val by: Iface = TODO()\n  val `if`: Iface = TODO()\n  val `for`: Iface = TODO()\n  val `while`: Iface = TODO()\n}\nval h = Holder()\nenum class Registry : Iface by h.catch {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "class Holder {\n  val catch: Iface = TODO()\n  val by: Iface = TODO()\n  val `if`: Iface = TODO()\n  val `for`: Iface = TODO()\n  val `while`: Iface = TODO()\n}\nval h = Holder()\nenum class Registry : Iface by h.by {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "class Holder {\n  val catch: Iface = TODO()\n  val by: Iface = TODO()\n  val `if`: Iface = TODO()\n  val `for`: Iface = TODO()\n  val `while`: Iface = TODO()\n}\nval h = Holder()\nenum class Registry : Iface by h.`if` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "class Holder {\n  val catch: Iface = TODO()\n  val by: Iface = TODO()\n  val `if`: Iface = TODO()\n  val `for`: Iface = TODO()\n  val `while`: Iface = TODO()\n}\nval h = Holder()\nenum class Registry : Iface by h.`for` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "class Holder {\n  val catch: Iface = TODO()\n  val by: Iface = TODO()\n  val `if`: Iface = TODO()\n  val `for`: Iface = TODO()\n  val `while`: Iface = TODO()\n}\nval h = Holder()\nenum class Registry : Iface by h.`while` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // `)`-arm misses `try`/`do`/`else`/`finally` — callables named
                // them resolve to the body correctly
                "fun `try`(x: Int): Iface = TODO()\nenum class Registry : Iface by `try`(1) {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "fun `do`(x: Int): Iface = TODO()\nenum class Registry : Iface by `do`(1) {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "fun finally(x: Int): Iface = TODO()\nenum class Registry : Iface by finally(1) {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "fun `else`(x: Int): Iface = TODO()\nenum class Registry : Iface by `else`(1) {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // enum names that dodge the bare-block set
                "enum class `object` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "enum class `val` {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "enum class catch {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                "enum class by {
      DeadUseCase;
      fun f() = DeadUseCase
    }",
                // `if`/`catch` supertypes are not in the bare-block set either
                "interface `if` {
      fun k()
    }\nenum class Registry : `if` {
      DeadUseCase;
      override fun k() {}
    }",
                "interface `catch` {
      fun k()
    }\nenum class Registry : `catch` {
      DeadUseCase;
      override fun k() {}
    }",
        ];

        /// Delegate expressions whose `{` is the enum body or rides
        /// `expr_brace`/`object_stack` — the whole legal grammar surface the
        /// state machine covers.
        #[test]
        fn delegate_expression_shapes_still_find_the_real_body() {
            let mut failed = Vec::new();
            for &decl in DELEGATE_EXPRESSION_SHAPES {
                let consumer = enum_prelude("", decl);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                if !reports_violation(&lomo_xtask::check_usecase_reachability(dir.path())) {
                    failed.push(format!(
                        "{decl} -> {:?}",
                        lomo_xtask::check_usecase_reachability(dir.path())
                    ));
                }
            }
            assert!(
                failed.is_empty(),
                "delegate-expression shapes the state machine covers must keep \
                 reporting the violation:\n{}",
                failed.join("\n---\n")
            );
        }

        /// `by throw X()` without parens is compiling Kotlin (throw is an
        /// expression) that tree-sitter-kotlin-ng cannot account for — the
        /// grammar recovers with a spurious MISSING `++`. The gate cannot model
        /// a file it cannot parse, so it fails closed naming the file: loud,
        /// never a silent pass or misreported body.
        #[test]
        fn a_bare_throw_delegate_bails_on_the_grammar() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(
                dir.path(),
                "enum class Registry : Iface by throw RuntimeException() {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}",
            );
            let error = lomo_xtask::check_usecase_reachability(dir.path())
                .expect_err("grammar-unaccountable Kotlin must fail closed");
            assert!(
                format!("{error:#}").contains("Kotlin parse error"),
                "the bail names the file and its unaccountable syntax: {error:#}"
            );
        }
    }

    // adversarial re-audit (round 12) of the P11-F1 eight-layer gate fixes landed by
    // 28-修复-门禁八层 (N11-1 `${ }` template hole via `Punct('$')`, N11-2 wildcard
    // `*` suppression in `qualified_tail_binds`, N11-3 `Tok::EscapedIdent` +
    // `identifier_name_position` keyword-by-position). Fixture harness mirrors
    // reaudit11. All probed shapes verified against kotlinc 2.4.20
    // (/tmp/kprobe12): every RED row is COMPILING Kotlin — the gate answer is
    // the wrong one.
    //
    // Probed surfaces:
    //  - expr-depth-0 `{` producers: the P11-F1 invariant enumerates
    //    {block keyword, `)`-owned block, `${` hole, object body, enum body} —
    //    but a LAMBDA literal is a fifth producer: `by { }` / `by l@{ }` compile
    //    as delegates for `fun interface` (kotlinc-verified), and `[]` is NOT a
    //    tracked depth, so `by idx[{ d }]` / `by idx[f { d }]` / `by idx[0, { d }]`
    //    leak real lambdas and trailing lambdas to depth zero. The `{` is
    //    grabbed as the enum body; the real body mints phantom consumers
    //    (fail-open).
    //  - `delegate_context_step`'s break set fires on SPELLING in every context:
    //    inside `Expression`/`ObjectExpression` the same tokens are ordinary
    //    *expression* positions — `import`/`enum` are usable bare identifiers
    //    (soft / modifier keyword — `val import`/`val enum`/`interface import`
    //    all compile), `fun` opens an anonymous-function literal
    //    (`by fun(x) = x` compiles for a `fun interface`), `object` inside `[ ]`
    //    is an object-literal index argument. All converge to the EOF bail —
    //    the same "header never opens a body" misreport the N11-3 fix was
    //    supposed to close (fail-closed).
    //  - `analyze_file_scope`/`contains_identifier_usage` dispatch `import` on
    //    any bare `Tok::Ident` — but `import` is a soft keyword usable as a
    //    plain identifier: `val import = DeadUseCase()` compiles, the header
    //    skip then eats the REST OF THE LINE including the real consumer —
    //    over-report (fail-closed, same-line only).
    //  - `parse_import` splits `as` on joined header *pieces*: a backticked
    //    `` `as` `` path segment (`import `as`.com`, `import a.`as`.com` —
    //    kotlinc-verified: the import DOES capture the `com` chain root)
    //    matches the alias keyword positionally, recording `(".", target)` —
    //    the `com` introduction is invisible to `qualified_tail_binds`
    //    (fail-open, N11-2 family variant).

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod lambda_and_import_positions {
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

        /// Shared Kotlin prelude. `tree-sitter-kotlin-ng` requires a separator
        /// (newline or `;`) between a member and its enclosing `}` — the bodies
        /// below are written multi-line so the fixtures parse cleanly.
        fn enum_prelude(member_decls: &str, enum_decl: &str) -> String {
            format!(
                "interface Iface {{\n  fun k()\n}}\n\
                 val d: Iface = object : Iface {{\n  override fun k() {{}}\n}}\n\
                 {member_decls}\n{enum_decl}\n"
            )
        }

        /// True iff the gate reports the use-case violation (the honest answer),
        /// false for a minted phantom (Ok) or a structural bail.
        fn reports_violation(result: &anyhow::Result<()>) -> bool {
            match result {
                Err(error) => format!("{error:#}").contains("production consumers"),
                Ok(()) => false,
            }
        }

        // ------------------------------------------------------------------
        // N12-1 — a `{` at delegate-expression depth zero can open a LAMBDA
        // literal, not only a block/hole/object-body (fail-open). kotlinc
        // 2.4.20 verified COMPILING:
        //   `enum class E : H by { } { A }`           (fun-interface lambda delegate)
        //   `enum class E : H by label@{ x -> x } { A }` (labeled lambda)
        //   `enum class E : I by idx[{ d }] { A }`    (lambda as indexer arg —
        //     `[`/`]` are not a tracked depth, the lambda leaks to expr0)
        //   `enum class E : I by idx[take { d }] { A }` (trailing lambda inside `[]`
        //     — the delegation-position lambda ban only applies to the OUTER call)
        //   `enum class E : I by idx[0, { d }] { A }`  (`,` inside `[]` too)
        //   `enum class E : H by object : H by { x -> x } { } { A }`
        //     (nested lambda wrongly pops the object_stack — the object body is
        //     then grabbed as the enum body)
        // The stolen `{` runs the fake entry list; the REAL `{ DeadUseCase; … }`
        // body is orphaned and its bare uses mint phantom consumers.
        // ------------------------------------------------------------------

        /// Lambda-literal `{` producers at expression depth zero all mint
        /// phantom consumers today (fail-open).
        #[test]
        fn a_lambda_literal_in_a_delegate_steals_the_enum_body() {
            let mut leaked = Vec::new();
            for consumer in [
                // bare lambda as the delegate itself — `fun interface` SAM
                "fun interface Handler {
      fun k()
    }\nenum class Registry : Handler by { } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // parameterized lambda
                "fun interface Handler {
      fun k(x: Int): Int
    }\nenum class Registry : Handler by { x -> x } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // label-prefixed lambda — `{` prev is `@`, still not a block
                "fun interface Handler {
      fun k(x: Int): Int
    }\nenum class Registry : Handler by label@{ x -> x } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // lambda literal inside an indexer — `[]` is not a tracked depth
                "class Idx {
      operator fun get(k: () -> Iface): Iface = d
    }\nval idx = Idx()\nenum class Registry : Iface by idx[{ d }] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // trailing lambda inside an indexer — the delegation-position
                // trailing-lambda ban does not apply inside `[ ]`
                "fun take(l: () -> Iface): Iface = l()\nclass Idx {\n  operator fun get(k: Iface): Iface = k\n}\nval idx = Idx()\nenum class Registry : Iface by idx[take { d }] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `,` inside `[]` — multi-arg indexer, second arg a lambda
                "class Idx3 {
      operator fun get(a: Int, b: () -> Iface): Iface = d
    }\nval idx3 = Idx3()\nenum class Registry : Iface by idx3[0, { d }] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `,` inside `[]` also ends the specifier context — but `,` marks
                // the next ident as name-position, so `object` does not break;
                // its body `{` is grabbed as the enum body instead
                "class Idx3 {
      operator fun get(a: Int, b: Any): Iface = d
    }\nval idx3 = Idx3()\nenum class Registry : Iface by idx3[0, object : Iface {
      override fun k() {}
    }] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // lambda inside an object's delegate — the `{` pops the
                // object_stack as a fake object body, then the real object body
                // `{ }` is grabbed as the enum body and `{ DeadUseCase; … }` mints
                "fun interface Handler {
      fun k(x: Int): Int
    }\nenum class Registry : Handler by object : Handler by { x -> x } { } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude("", consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a lambda literal at delegate-expression depth zero \
                 opens with `{{` classified as the enum body — producers `by {{ }}`, \
                 `by l@{{ }}`, `by idx[{{ }}]`, `by idx[f {{ }}]` are missed; the real \
                 body mints phantom consumers (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// GREEN controls — the same lambda value *parenthesized* rides paren
        /// depth, and `{`-free delegates keep answering correctly.
        #[test]
        fn parenthesized_lambda_delegates_still_find_the_real_body() {
            let mut failed = Vec::new();
            for consumer in [
                // the same lambda in parens — `{` rides paren depth, body found
                "fun interface Handler {
      fun k(x: Int): Int
    }\nenum class Registry : Handler by ({ x -> x }) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // lambda inside parens inside an indexer is equally safe
                "class Idx {
      operator fun get(k: () -> Iface): Iface = d
    }\nval idx = Idx()\nenum class Registry : Iface by idx[({ d })] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude("", consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                if !reports_violation(&lomo_xtask::check_usecase_reachability(dir.path())) {
                    failed.push(format!(
                        "{consumer} -> {:?}",
                        lomo_xtask::check_usecase_reachability(dir.path())
                    ));
                }
            }
            assert!(
                failed.is_empty(),
                "parenthesized lambda delegates ride paren depth — the real body \
                 must be found:\n{}",
                failed.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N12-2 — `delegate_context_step` break/transition sets fire on
        // SPELLING in every context (fail-closed misreport). Inside
        // `Expression`/`ObjectExpression` the tokens are *expression* positions:
        // `import`/`enum` are usable bare identifiers (soft / modifier keyword —
        // `val import`/`val enum`/`interface import`/`enum class E : import` all
        // compile, kotlinc-verified), `fun` opens an anonymous-function literal
        // (`enum class E : H by fun(x) = x { A }` compiles), and `[]` is
        // untracked so `idx[enum]`/`idx[import]` reach the step too. A `,`
        // inside `[]` flips the context to `Header` early enough that
        // `idx[0, object : I { }]`'s object literal hits the break set as well.
        // All converge to the "header never opens a body" EOF bail — the
        // misreport family N11-3 was supposed to close.
        // ------------------------------------------------------------------

        /// Soft/modifier keywords and `fun` literals in *expression* position
        /// inside the enum header bail on compiling Kotlin.
        #[test]
        fn an_expression_position_declaration_keyword_still_bails() {
            let mut bailed = Vec::new();
            for consumer in [
                // `import` as the bare delegate value — `val import` compiles
                "val import: Iface = d\nenum class Registry : Iface by import {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `enum` as the bare delegate value — modifier keyword ident
                "val enum: Iface = d\nenum class Registry : Iface by enum {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `fun import(x)` — a callable literally named `import`
                "fun import(x: Int): Iface = d\nenum class Registry : Iface by import(1) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // anonymous function literal as the delegate — compiles for
                // a `fun interface`
                "fun interface Handler {
      fun k(x: Int): Int
    }\nenum class Registry : Handler by fun(x: Int) = x {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `enum`/`import` as indexer arguments — `[]` is untracked,
                // the ident reaches the step at depth zero
                "class Idx2 {
      operator fun get(k: Iface): Iface = k
    }\nval idx2 = Idx2()\nval enum: Iface = d\nenum class Registry : Iface by idx2[enum] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "class Idx2 {
      operator fun get(k: Iface): Iface = k
    }\nval idx2 = Idx2()\nval import: Iface = d\nenum class Registry : Iface by idx2[import] {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `import` as an object's delegate value (ObjectExpression)
                "val import: Iface = d\nenum class Registry : Iface by object : Iface by import {
      override fun k() {}
    } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `import` in an else-branch — still expression position
                "val import: Iface = d\nval c = true\nenum class Registry : Iface by if (c) d else import {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude("", consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => bailed.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                bailed.is_empty(),
                "MISREPORT: `import`/`enum`/`fun` in *expression* position are not \
                 declarations — the break set must not fire inside the delegate \
                 expression context (kotlinc-verified compiling Kotlin, N11-3 \
                 residual family):\n{}",
                bailed.join("\n---\n")
            );
        }

        /// GREEN controls — the same spellings in name/member/paren positions,
        //  and `by`-expression shapes the state machine answers correctly.
        #[test]
        fn soft_keyword_names_and_other_position_reads_still_bind() {
            let mut failed = Vec::new();
            for consumer in [
                // parenthesized soft keyword — paren gates the step
                "val import: Iface = d\nenum class Registry : Iface by (import) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // member position — `.` makes it a name
                "class Holder {
      val import: Iface = d
    }\nval h = Holder()\nenum class Registry : Iface by h.import {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // backticked — `EscapedIdent` is never syntax
                "val `import`: Iface = d\nenum class Registry : Iface by `import` {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `import` as a supertype name — `,` makes it a name position
                "interface import {
      fun k()
    }\nval di: import = object : import {
      override fun k() {}
    }\nenum class Registry : Iface, import {\n  DeadUseCase;\n  override fun k() {}\n  fun f() = DeadUseCase\n}\n",
                // `import` as the enum NAME — `class` makes it a name position
                "enum class `import` {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `by` itself as the delegate identifier — legal soft keyword
                "val by: Iface = d\nenum class Registry : Iface by by {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // no-subject `when`, `as` cast, annotated delegate, `by`-chain
                "val c = true\nenum class Registry : Iface by when { c -> d else -> d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by d as Iface {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by @Suppress(\"x\") d {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `try`/`catch`/`finally` chain — `}`-preceded rule path
                "enum class Registry : Iface by try { d } catch (e: Exception) { d } finally { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `try`/`finally` nested inside an `if` branch
                "val c2 = true\nenum class Registry : Iface by if (c2) try { d } finally { d } else d {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude("", consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                if !reports_violation(&lomo_xtask::check_usecase_reachability(dir.path())) {
                    failed.push(format!(
                        "{consumer} -> {:?}",
                        lomo_xtask::check_usecase_reachability(dir.path())
                    ));
                }
            }
            assert!(
                failed.is_empty(),
                "keyword spellings in name/member/paren position and covered \
                 `by`-expression shapes must keep reporting the violation:\n{}",
                failed.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N12-3 — bare `import` as an identifier eats the rest of the line
        // (fail-closed misreport). `import` is a *soft* keyword: `val import =
        // DeadUseCase()` compiles (kotlinc-verified). `analyze_file_scope` and
        // `contains_identifier_usage` dispatch on any bare `Tok::Ident`
        // spelling `import` — the `.`/`::` member exclusion does not cover
        // expression position — so `skip_header` swallows the remainder of the
        // line, including the real `DeadUseCase` consumer. Honest answer: the
        // usecase IS consumed → Ok. The gate reports the violation → misreport.
        // ------------------------------------------------------------------

        /// A line carrying a bare `import` identifier is consumed as an import
        /// header; a real usecase consumer on that line never mints.
        #[test]
        fn a_bare_import_identifier_eats_the_consumer_on_its_line() {
            let mut suppressed = Vec::new();
            for consumer in [
                // `import` as a property name — `= DeadUseCase()` is swallowed
                "val import = DeadUseCase()",
                // `import` as a parameter name — `: DeadUseCase` is swallowed
                "fun consume(import: DeadUseCase) = import",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    suppressed.push(format!(
                        "{consumer} -> {:?}",
                        lomo_xtask::check_usecase_reachability(dir.path())
                    ));
                }
            }
            assert!(
                suppressed.is_empty(),
                "MISREPORT: a bare `import` identifier is not a header keyword — \
                 the header skip eats the rest of the line and a real consumer \
                 on it is lost (fail-closed misreport):\n{}",
                suppressed.join("\n---\n")
            );
        }

        /// GREEN control — `` `import` `` spelled escaped is an `EscapedIdent`,
        /// never dispatched as a header; the same line mints the consumer.
        #[test]
        fn an_escaped_import_name_does_not_eat_the_line() {
            let dir = tempfile::tempdir().expect("fixture");
            usecase_fixture(dir.path(), "val `import` = DeadUseCase()");
            assert!(
                lomo_xtask::check_usecase_reachability(dir.path()).is_ok(),
                "`val `import` = DeadUseCase()` — the escaped name is identifier \
                 data, not a header: the consumer on the line must mint"
            );
        }

        // ------------------------------------------------------------------
        // N12-4 — `parse_import` matches the alias keyword on joined *pieces*:
        // a backticked `` `as` `` path segment is `Tok::EscapedIdent("as")`,
        // pushed as piece text `"as"` and positionally matched as the alias
        // keyword — `import `as`.com` / `import a.`as`.com` record `(".", …)`
        // and the `com` introduction is lost. kotlinc-verified: both imports
        // capture the qualified-tail root (`com.lomo…X` → "unresolved
        // reference 'lomo'"). N11-2 family variant — an import channel the
        // root check cannot see (fail-open).
        // ------------------------------------------------------------------

        /// Backticked `as` segments inside an import path break the alias
        /// split — the imported `com` is invisible to the root check.
        #[test]
        fn an_escaped_as_segment_hides_an_imported_root_binding() {
            let mut leaked = Vec::new();
            for consumer in [
                // package literally named `as` — `import `as`.com` introduces `com`
                "import `as`.com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // `as` as a middle package segment — `import a.`as`.com`
                "import a.`as`.com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // `as` as the first segment — `import `as`.b.com`
                "import `as`.b.com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
                // GREEN control — the same capture through a plain path is
                // suppressed (N10-1/N11 coverage, kept as contrast)
                "import a.b.com\nfun dispatch() = com.lomo.domain.usecase.DeadUseCase",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: `` `as` `` path segments break `parse_import`'s alias \
                 split — the imported `com` is invisible to the root check and \
                 the qualified tail mints a phantom consumer (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }
    }

    // adversarial re-audit (round 13) of the P12-F2 nine-layer gate fixes landed by
    // 30-修复-门禁九层 (N12-1 `EnumHeaderScan.bracket` + lambda-literal producers +
    // `DelegateStep::FunSignature`, N12-2 expression-position soft-keyword break
    // set + `by_opener`, N12-3 `import_header_position`, N12-4 `HeaderPiece.escaped`,
    // plus the `SHORTHAND_BOUNDARY` literal-marker regression fix). Fixture harness
    // mirrors reaudit12. All probed shapes verified against kotlinc 2.4.20
    // (/tmp/kprobe13): every RED row is COMPILING Kotlin — the gate answer is the
    // wrong one.
    //
    // Probed surfaces:
    //  - The `{`-at-expression-depth-zero producer enumeration is STILL not
    //    exhaustive: a lambda literal can be the RIGHT OPERAND of a binary or
    //    infix expression — `by a + { d }`, `by a * { d }`, `by a - { d }`,
    //    `by a / { d }`, `by a % { d }`, `by a..{ d }`, `by a..<{ d }`,
    //    `by a x { d }` / `by a and { d }` / `by a or { d }` / `by a `x` { d }`
    //    (infix calls — the delegation trailing-lambda ban does NOT apply to an
    //    infix argument, kotlinc-verified: `by run { d }` is rejected while
    //    `by a x { d }` compiles) and `by fun(x): T = a + { d }` / `by if (c) a +
    //    { d } else d` (the infix lambda nested inside an anonymous-function
    //    expression body or an `if` branch). The `{` after `+`/`.`/an identifier
    //    is not in `delegate_expression_block`'s producer list, so it is grabbed
    //    as the enum body — the real `{ DeadUseCase; … }` body mints phantom
    //    consumers (fail-open, N12-1 residual family). Nested inside an object
    //    delegate (`by object : I by a + { d } { … } { … }`) the lambda `{`
    //    wrongly pops `object_stack`, so the OBJECT's body is grabbed as the
    //    enum body instead — same leak through the second slot.
    //  - `import_header_position` reads "line start / `{` `}` `;` boundary" as
    //    directive position — but an `import` at a statement head INSIDE a block
    //    or on a continuation line is a function CALL, never a directive:
    //    `fun f() { import(x) }` / `foo(); import(x)` / `val g =\nimport(x)` /
    //    `f(1,\nimport(x))` / `arr[\nimport(x)]` / `"${import(x)}"` /
    //    `if (c) 0 else\nimport(x)` / `for (x in\nimport(xs))` / `when (\nimport(x))`
    //    all compile (kotlinc-verified — `import` is a soft keyword, and Kotlin
    //    only allows the directive at file top level, where the `{`/`}`/`;`
    //    boundary can never precede it). The header skip then EATS the rest of
    //    the line — including real consumers — and `parse_import` additionally
    //    records a BOGUS `("DeadUseCase", "importDeadUseCase")` binding that
    //    shadows every remaining bare `DeadUseCase` in the file: both channels
    //    converge to `0 production consumers` (fail-closed misreport, N12-3
    //    residual family).

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod operator_lambdas {
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

        /// Shared Kotlin prelude. `tree-sitter-kotlin-ng` requires a separator
        /// (newline or `;`) between a member and its enclosing `}` — the bodies
        /// below are written multi-line so the fixtures parse cleanly.
        fn enum_prelude(member_decls: &str, enum_decl: &str) -> String {
            format!(
                "interface Iface {{\n  fun k()\n}}\n\
                 val d: Iface = object : Iface {{\n  override fun k() {{}}\n}}\n\
                 {member_decls}\n{enum_decl}\n"
            )
        }

        /// Operator declarations for the infix/binary-lambda probes. `rangeTo`/
        /// `rangeUntil` return the delegate interface itself — `a..{ d }` desugars
        /// to `a.rangeTo({ d })` whose return type is unconstrained.
        const OPS: &str = "class Op {\n\
             operator fun plus(f: () -> Iface): Iface = d\n\
             operator fun minus(f: () -> Iface): Iface = d\n\
             operator fun times(f: () -> Iface): Iface = d\n\
             operator fun div(f: () -> Iface): Iface = d\n\
             operator fun rem(f: () -> Iface): Iface = d\n\
             operator fun rangeTo(f: () -> Iface): Iface = d\n\
             operator fun rangeUntil(f: () -> Iface): Iface = d\n\
             infix fun x(f: () -> Iface): Iface = d\n\
             infix fun `x y`(f: () -> Iface): Iface = d\n\
             infix fun and(f: () -> Iface): Iface = d\n\
             infix fun or(f: () -> Iface): Iface = d\n\
             infix fun finally(f: () -> Iface): Iface = d\n\
             infix fun by(f: () -> Iface): Iface = d\n\
             infix fun `catch`(f: () -> Iface): Iface = d\n\
             infix fun `when`(f: () -> Iface): Iface = d\n\
             infix fun `as`(f: () -> Iface): Iface = d\n\
             fun y(f: () -> Iface): Iface = d\n\
             }\nval a = Op()\nannotation class Anno\nval c = true\n";

        /// True iff the gate reports the use-case violation (the honest answer),
        /// false for a minted phantom (Ok) or a structural bail.
        fn reports_violation(result: &anyhow::Result<()>) -> bool {
            match result {
                Err(error) => format!("{error:#}").contains("production consumers"),
                Ok(()) => false,
            }
        }

        // ------------------------------------------------------------------
        // N13-1 — a `{` at delegate-expression depth zero can open a lambda that
        // is the RIGHT OPERAND of a binary or infix expression, not only a bare
        // lambda after `by`/`@`/`=`/`?:` (fail-open). kotlinc 2.4.20 verified
        // COMPILING: `by a + { d }`, `by a * { d }`, `by a - { d }`, `by a / { d }`,
        // `by a % { d }`, `by a..{ d }`, `by a..<{ d }`, `by a x { d }`,
        // `by a `x y` { d }`, `by a and { d }`, `by a or { d }`,
        // `by a + { x -> d }`, `by if (c) a + { d } else d`,
        // `by fun(x): Iface = a + { d }`, and the object-delegate nested shape
        // `by object : Iface by a + { d } { … } { … }` where the lambda `{`
        // pops `object_stack` and the OBJECT body is grabbed instead. The
        // delegation trailing-lambda ban (`by run { d }`, `by let { d }`,
        // `by d.also { d }` all rejected — verified) does NOT reach infix
        // arguments or operator operands.
        // ------------------------------------------------------------------

        /// Binary-operator and infix right-operand lambdas all mint phantom
        /// consumers today (fail-open).
        #[test]
        fn an_infix_or_operator_rhs_lambda_steals_the_enum_body() {
            let mut leaked = Vec::new();
            for consumer in [
                // `+` RHS lambda — `a.plus({ d })` returns the delegate
                "enum class Registry : Iface by a + { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `*` RHS lambda
                "enum class Registry : Iface by a * { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `-` RHS lambda
                "enum class Registry : Iface by a - { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `/` RHS lambda
                "enum class Registry : Iface by a / { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `%` (`rem`) RHS lambda
                "enum class Registry : Iface by a % { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `..` RHS lambda — `a.rangeTo({ d })`, free return type
                "enum class Registry : Iface by a..{ d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `..<` RHS lambda — `a.rangeUntil({ d })`
                "enum class Registry : Iface by a..<{ d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // infix call — `a.x({ d })`; the ban only covers OUTER trailing
                // lambdas, not infix arguments (kotlinc-verified)
                "enum class Registry : Iface by a x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // backticked infix name — same slot, EscapedIdent
                "enum class Registry : Iface by a `x y` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // stdlib-flavoured infix names
                "enum class Registry : Iface by a and { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by a or { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // soft keyword as the infix name — `a.finally({ d })`; `finally`
                // is a soft keyword, so the bare spelling binds the function
                "enum class Registry : Iface by a finally { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `by` itself as the infix name — `a.by({ d })`; the second `by`
                // is an infix call, not the opener
                "enum class Registry : Iface by a by { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // escaped hard keywords as infix names — `` `catch` ``/` `when` ``/
                // ` `as` ``
                "enum class Registry : Iface by a `catch` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by a `when` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by a `as` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // parameterised lambda after `+` — `plus(f: (Int) -> Iface)`
                "class Op2 {
      operator fun plus(f: (Int) -> Iface): Iface = d
    }\nval a2 = Op2()\nenum class Registry : Iface by a2 + { x -> d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // the infix lambda nested in an `if` branch — `if`/`else` do not
                // gate the operand's `{`
                "enum class Registry : Iface by if (c) a + { d } else d {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by if (c) d else a + { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // the infix lambda inside an anonymous-function expression body —
                // `fun(x): Iface = a + { d }` (fun-interface delegate)
                "fun interface Handler {
      fun k(x: Int): Iface
    }\nenum class Registry : Handler by fun(x: Int): Iface = a + { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // the same lambda inside an OBJECT's delegate — the `{` pops
                // `object_stack`, then the object's real body is grabbed as the
                // enum body and the real enum body mints anyway. Parenthesized:
                // the unparenthesized `by object : I by a + { d } { … }` is a
                // tree-sitter-kotlin-ng residual pinned in
                // `an_object_delegate_infix_lambda_bails_on_the_grammar`.
                "enum class Registry : Iface by object : Iface by (a + { d }) {
      override fun k() {}
    } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude(OPS, consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a lambda as the RIGHT OPERAND of a binary/infix \
                 delegate expression opens with `{{` classified as the enum body — \
                 producers `by a + {{ }}`, `by a..{{ }}`, `by a x {{ }}` are missed \
                 (the infix/trailing-lambda ban does not reach operator operands \
                 or infix arguments); the real body mints phantom consumers \
                 (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// `by object : I by a + { d } { … }` unparenthesized is compiling
        /// Kotlin (kotlinc-verified) that tree-sitter-kotlin-ng cannot account
        /// for — the infix lambda operand followed by the object's own body is
        /// an ERROR under the grammar even outside enum headers (`val o =
        /// object : I by a + { d } { … }` fails the same way). The gate cannot
        /// model a file it cannot parse, so it fails closed naming the file.
        #[test]
        fn an_object_delegate_infix_lambda_bails_on_the_grammar() {
            let dir = tempfile::tempdir().expect("fixture");
            let consumer = enum_prelude(
                OPS,
                "enum class Registry : Iface by object : Iface by a + { d } {\n  override fun k() {}\n} {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            );
            usecase_fixture(dir.path(), &consumer);
            let error = lomo_xtask::check_usecase_reachability(dir.path())
                .expect_err("grammar-unaccountable Kotlin must fail closed");
            assert!(
                format!("{error:#}").contains("Kotlin parse error"),
                "the bail names the file and its unaccountable syntax: {error:#}"
            );
        }

        /// GREEN controls — the same lambda values under paren depth, under
        /// label/annotation prefixes, or inside an explicit call's argument list
        /// ride tracked structure and still find the real body.
        #[test]
        fn parenthesized_labeled_and_annotated_operator_lambdas_still_find_the_body() {
            let mut failed = Vec::new();
            for consumer in [
                // the whole binary expression in parens — `{` rides paren depth
                "enum class Registry : Iface by (a + { d }) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // only the lambda operand in parens — `{` inside `(...)`
                "enum class Registry : Iface by a + ({ d }) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // labeled lambda after `+` — `{` prev is `@`, a producer the fix
                // already enumerates
                "enum class Registry : Iface by a + label@{ d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // annotated lambda after `+` — `{` prev is the annotation name
                "enum class Registry : Iface by a + @Anno { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // explicit call with the lambda inside the argument list
                "enum class Registry : Iface by a.y({ d }) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude(OPS, consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                if !reports_violation(&lomo_xtask::check_usecase_reachability(dir.path())) {
                    failed.push(format!(
                        "{consumer} -> {:?}",
                        lomo_xtask::check_usecase_reachability(dir.path())
                    ));
                }
            }
            assert!(
                failed.is_empty(),
                "parenthesized/labeled/annotated operator lambdas ride tracked \
                 structure — the real body must be found:\n{}",
                failed.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N13-2 — `import` at a statement head INSIDE a block or on an
        // expression-continuation line is a function call / plain identifier,
        // never a directive (fail-closed misreport). kotlinc 2.4.20 verified
        // COMPILING: `fun f() { import(x) }`, `foo(); import(x)`,
        // `val g =\nimport(x)`, `"" +\nimport(x)`, `f(1,\nimport(x))`,
        // `arr[\nimport(x)]`, `"${import(x)}"`, `else\nimport(x)`, `run {\nimport(x)}`,
        // `for (x in\nimport(xs))`, `when (\nimport(x))`, `try {\nimport(x)}`,
        // `\nimport as DeadUseCase` — and `enum class E { import }` (a real enum
        // entry NAME). `import_header_position`'s line-start/`{`/`}`/`;` boundary
        // cannot distinguish them: the header skip eats the rest of the line —
        // consumers on it are lost — and `parse_import` records a bogus
        // `("DeadUseCase", "importDeadUseCase")` binding that shadows every
        // remaining bare `DeadUseCase` (over-report, N12-3 residual family).
        // ------------------------------------------------------------------

        /// A block-body statement head or continuation line starting with
        /// `import` is a CALL — the gate must not swallow the line or mint a
        /// shadowing import record.
        #[test]
        fn a_block_or_continuation_import_call_is_eaten_as_a_header() {
            let mut suppressed = Vec::new();
            for consumer in [
                // `import(...)` as a statement inside a function body — a call
                // to the function `import`, never a directive
                "fun import(x: Any) = x\nfun f() {\n    import(DeadUseCase())\n}",
                // the same call on the `{`-boundary line itself
                "fun import(x: Any) = x\nfun f() { import(DeadUseCase()) }",
                // the same call after a `;` statement boundary inside a body
                "fun import(x: Any) = x\nfun foo() {}\nfun f() { foo(); import(DeadUseCase()) }",
                // a second statement inside a body, at line start
                "fun import(x: Any) = x\nfun f() { foo()\nimport(DeadUseCase()) }\nfun foo() {}",
                // `=` continuation — the previous line cannot end the statement
                "fun import(x: Any) = x\nval g =\n    import(DeadUseCase())",
                // binary-operator continuation
                "fun import(x: Any) = x\nval g = \"\" +\n    import(DeadUseCase())",
                // argument-list continuation inside `(...)`
                "fun f(x: Any, y: Any) {}\nfun import(x: Any): Any = x\nval g = f(1,\n    import(DeadUseCase()))",
                // index-argument continuation inside `[...]`
                "val arr = arrayOfNulls<Any>(4)\nfun import(x: Any): Int = 0\nval g = arr[\n    import(DeadUseCase())\n]",
                // `${ }` template hole — `import` is the hole's expression
                "fun import(x: Any) = x\nval s = \"${import(DeadUseCase())}\"",
                // `else`-branch value at line start
                "fun import(x: Any) = x\nval cc = true\nval g = if (cc) 0 else\n    import(DeadUseCase())",
                // lambda body statement
                "fun import(x: Any) = x\nval g = run {\n    import(DeadUseCase())\n}",
                // `try` block value
                "fun import(x: Any) = x\nval g = try {\n    import(DeadUseCase())\n} catch (e: Exception) { d }",
                // `for`-header iterable at line start inside the parens — the
                // constructor sits on the eaten line itself
                "fun import(x: Any): List<Any> = listOf(x)\nfun f() { for (x in\n    import(DeadUseCase())) {} }",
                // `when` subject inside the parens
                "fun import(x: Any): Any = x\nval g = when (\n    import(DeadUseCase())) { else -> 0 }",
                // `import` as an enum ENTRY name — `{ import }` is a member
                // declaration line, not a directive; `; f() = DeadUseCase()` on
                // the same line is eaten with it
                "enum class E { import }; fun f() = DeadUseCase()",
                // `import` as a cast operand on a continuation line — the
                // `as`-split additionally mints `("DeadUseCase", "import")`
                "val import = DeadUseCase()\nval g =\n    import as DeadUseCase",
                // `import` in a parameter default at a continuation line head
                "fun import(x: Any): Any = x\nfun f(x: Any =\n    import(DeadUseCase())) {}",
                // `import` as a local `var` read at a line head — assignment lvalue
                "var import = DeadUseCase()\nfun f() {\n    import = DeadUseCase()\n}",
                // `import` as a parameter NAME at a continuation line head inside
                // a signature — `import: DeadUseCase` loses the type reference
                "fun f(\n    import: DeadUseCase) {}",
                // `import` as the `for` loop variable at a continuation line head
                "fun f() { for (\n    import in listOf(DeadUseCase())) {} }",
                // `import` as an enum ENTRY name with a constructor argument —
                // `import(DeadUseCase())` at the entry-line head eats the only
                // consumer in the file
                "enum class Registry(val v: Any) {\n    import(DeadUseCase()),\n    X(Unit);\n    fun f() = v\n}",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                if lomo_xtask::check_usecase_reachability(dir.path()).is_err() {
                    suppressed.push(format!(
                        "{consumer} -> {:?}",
                        lomo_xtask::check_usecase_reachability(dir.path())
                    ));
                }
            }
            assert!(
                suppressed.is_empty(),
                "MISREPORT: `import` at a block/continuation line head is a call, \
                 not a directive — `import_header_position` swallows the rest of \
                 the line and `parse_import` mints a shadowing import record; the \
                 real consumer is lost (fail-closed misreport):\n{}",
                suppressed.join("\n---\n")
            );
        }

        /// GREEN controls — `import` identifiers in name/member/paren/argument
        /// positions keep minting the consumer on their line.
        #[test]
        fn import_identifier_positions_outside_line_heads_still_mint() {
            let mut failed = Vec::new();
            for consumer in [
                // `import` as a property name — N12-3 lock
                "val import = DeadUseCase()",
                // `import` as a parameter name — N12-3 lock
                "fun consume(import: DeadUseCase) = import",
                // escaped — `EscapedIdent` is never dispatched
                "val `import` = DeadUseCase()",
                // member position — `.` makes it a name
                "class Holder {
      val import = DeadUseCase()
    }\nval g = Holder().import",
                // same-line argument inside `(...)` — prev is `(`, not a boundary
                "fun import(x: Any): Any = x\nfun foo(x: Any) {}\nval g = foo(import(DeadUseCase()))",
                // same-line index argument — prev is `[`, not a boundary
                "val arr = arrayOfNulls<Any>(4)\nfun import(x: Any): Int = 0\nval g = arr[import(DeadUseCase())]",
                // named-argument label inside `(...)` — prev is `(`
                "fun f2(import: DeadUseCase) {}\nval g = f2(import = DeadUseCase())",
                // member call mid-line — `import` after `.` is identifier data
                "class Holder2 {
      fun import(x: Any) = x
    }\nval h = Holder2()\nval g = h.import(DeadUseCase())",
                // `import` after `->` inside a lambda — arrow is not a boundary
                "fun import(x: Any): Any = x\nval g = { x: Int -> import(DeadUseCase()) }",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Ok(()) => {}
                    other => failed.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                failed.is_empty(),
                "`import` in non-line-head positions is identifier data — the \
                 consumer on its line must keep minting:\n{}",
                failed.join("\n---\n")
            );
        }
    }

    // adversarial re-audit (round 14) of the ten-layer gate fixes landed by
    // 32-修复-门禁十层 (N13-1 `token_ends_operand`/`paren_close_ends_operand`
    // run-alternation operand-end classifier, N13-2 `import_header_position`
    // preamble walk + `import_misplaced_header` + `HARD_KEYWORDS`). Fixture
    // harness mirrors reaudit13. Every RED row below is COMPILING Kotlin —
    // verified against kotlinc 2.4.20 in /tmp/kprobe14 — so the gate answer
    // is the wrong one, not an unparseable input.
    //
    // Probed surfaces and findings:
    //
    //  - N14-1 (`token_ends_operand` run-alternation ground, fail-open/bail):
    //    the run walks back over consecutive non-name identifiers and grounds
    //    the parity on the first non-identifier — `)`/`]`/`}`/
    //    `InterpolationBoundary` ground "ends an operand", every other token
    //    grounds "does not". The postfix `!!` — `a!!`, `f()!!`, `a[i]!!`,
    //    `"s"!!`, `(a)!!` — is the one expression punct that DOES end an
    //    operand but grounds false: an identifier after `a!!` sits in
    //    infix-NAME (operator) position — `a!! x { d }` is `(a!!).x({ d })`,
    //    kotlinc-verified — yet `token_ends_operand` calls it an operand end,
    //    so the `{` is grabbed as the enum body and the real body mints
    //    phantom consumers (fail-open). The same flipped ground puts `a!! y
    //    d`'s *argument* `d` in "does not end" position, swallowing the real
    //    enum body into `expr_brace` until `enum_body_start` bails.
    //
    //  - N14-2 (annotation-name ground, bail/fail-open): every name-position
    //    identifier grounds the run as "ends an operand", but an *annotation
    //    name* prefixes the operand it annotates — `enum class E : @TA Iface`
    //    (kotlinc-verified with `@Target(TYPE)`) leaves `Iface` judged
    //    "does not end", so the enum `{` is swallowed as an expression block
    //    and `enum_body_start` bails on a file that may hold real consumers.
    //    Inside a delegate expression, `@EA a x { d }` (kotlinc-verified with
    //    `@Retention(SOURCE) @Target(EXPRESSION)`) counts the annotated `a`
    //    as run token 1, so the infix `x` inherits "ends an operand" and its
    //    `{` is stolen (fail-open); `@EA a` alone flips the same hole to a
    //    body swallow (bail). `@TA(0) Iface` survives only because the
    //    `)`-owner annotation check exists — the bare `@TA T` shape has no
    //    equivalent path.
    //
    //  - N14-3 (same-line `;`-separated constructs eaten by `skip_header`/
    //    `header_pieces`, MISREPORT): a header ends at its *line's* end, so
    //    every `;`-separated directive or declaration on the same line rides
    //    the skip — `package p; import x`, `import a; import b`, and
    //    `import a; <decl>` are all legal Kotlin (kotlinc-verified — the
    //    preamble tolerates `;`, and every top-level decl kind parses on the
    //    import line). Worse, `header_pieces` collects identifier pieces
    //    across the `;`: `import a.b.C; val g = X` mints a `("g",
    //    "a.b.CvalgX")`-shaped binding whose *name* is the decl's last
    //    identifier — `import a.b.C; val g = DeadUseCase()` mints
    //    `("DeadUseCase", <garbage>)`, which `bare_name_binds` counts as an
    //    *explicit shadow*: every bare `DeadUseCase` in the file is then
    //    non-binding, even with a correct `import com.lomo...DeadUseCase` on
    //    a later line. In the declaration inventory,
    //    `package com.lomo.domain.usecase; class DeadUseCase` poisons the
    //    usecase's own `package_name` with the line tail, so qualified tails
    //    can never bind it. Real consumers are missed on compilable Kotlin —
    //    the gate reports fabricated violations on reachable usecases.

    #[expect(
        clippy::expect_used,
        reason = "adversarial fixtures fail closed with explicit diagnostics"
    )]
    mod operand_runs {
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

        fn enum_prelude(member_decls: &str, enum_decl: &str) -> String {
            format!(
                "interface Iface {{\n  fun k()\n}}\n\
                 val d: Iface = object : Iface {{\n  override fun k() {{}}\n}}\n\
                 {member_decls}\n{enum_decl}\n"
            )
        }

        /// Operators for the postfix-`!!` probes. `x`/`and`/`or`/`finally`/
        /// `` `by` ``/`it`/`` `x y` `` are infix names taking a lambda argument
        /// — the `(a!!).x({ d })` shapes — and `y` takes a plain operand for
        /// the reverse-parity row.
        const OPS: &str = "class Op {\n\
             infix fun x(f: () -> Iface): Iface = d\n\
             infix fun `x y`(f: () -> Iface): Iface = d\n\
             infix fun and(f: () -> Iface): Iface = d\n\
             infix fun or(f: () -> Iface): Iface = d\n\
             infix fun finally(f: () -> Iface): Iface = d\n\
             infix fun by(f: () -> Iface): Iface = d\n\
             infix fun it(f: () -> Iface): Iface = d\n\
             infix fun y(f: Iface): Iface = d\n\
             operator fun get(i: Int): Op = this\n\
             operator fun plus(f: () -> Iface): Iface = d\n\
             }\n\
             val a = Op()\n\
             val an: Op? = a\n\
             fun f(): Op = a\n\
             infix fun String.x(f: () -> Iface): Iface = d\n\
             val `import`: Op = a\n\
             val `enum`: Op = a\n\
             val c = true\n";

        /// True iff the gate reports the use-case violation (the honest answer),
        /// false for a minted phantom (Ok) or a structural bail.
        fn reports_violation(result: &anyhow::Result<()>) -> bool {
            match result {
                Err(error) => format!("{error:#}").contains("production consumers"),
                Ok(()) => false,
            }
        }

        // ------------------------------------------------------------------
        // N14-1 — the postfix `!!` grounds `token_ends_operand`'s run as "does
        // not end an operand", so an identifier AFTER `a!!` is judged an
        // operand end instead of the infix name it really is (fail-open).
        // kotlinc 2.4.20 verified COMPILING: `by a!! x { d }` / `by a!! `x y`
        // { d }` / `by a!! `by` { d }` / `by a!! finally { d }` / `by a!! and
        // { d }` / `by a!! or { d }` / `by a!! it { d }` / `by f()!! x { d }`
        // / `by a[0]!! x { d }` / `by "s"!! x { d }` / `by (a)!! x { d }` /
        // `by an?.get(0)!! x { d }` / `by a + a!! x { d }` / `by if (c) a else
        // a!! x { d }` / `by fun(p): Iface = a!! x { d }` / `by object : Iface
        // by a!! x { d } { … } { … }` / `by a!! x { d }, J { … }` — the `{`
        // after the infix name is that call's lambda argument. The gate grabs
        // it as the enum body; the real `{ DeadUseCase; … }` body mints
        // phantom consumers.
        // ------------------------------------------------------------------

        /// Every identifier after a postfix `!!` sits in operator (infix-name)
        /// position — the gate calls it an operand end and steals the lambda's
        /// `{` (fail-open).
        #[test]
        fn a_postfix_bang_makes_the_next_identifier_an_infix_name() {
            let mut leaked = Vec::new();
            for consumer in [
                // infix name on `a!!` — `(a!!).x({ d })`
                "enum class Registry : Iface by a!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // escaped infix name — `(a!!).`x y`({ d })`
                "enum class Registry : Iface by a!! `x y` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `by` itself as the infix name — `(a!!).`by`({ d })`
                "enum class Registry : Iface by a!! `by` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // soft keywords as infix names after `!!`
                "enum class Registry : Iface by a!! finally { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by a!! and { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by a!! or { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `it` is a plain identifier — `(a!!).it({ d })`
                "enum class Registry : Iface by a!! it { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // call-result postfix — `(f()!!).x({ d })`
                "enum class Registry : Iface by f()!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // index postfix — `(a[0]!!).x({ d })`
                "enum class Registry : Iface by a[0]!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // literal postfix — `("s"!!).x({ d })`
                "enum class Registry : Iface by \"s\"!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // parenthesised postfix — `((a)!!).x({ d })`
                "enum class Registry : Iface by (a)!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // safe-call + `!!` — `(an?.get(0)!!).x({ d })`
                "enum class Registry : Iface by an?.get(0)!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `!!` deeper inside the operand — `(a + a!!).x({ d })`
                "enum class Registry : Iface by a + a!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // inside an `else` branch — `if (c) a else (a!!).x({ d })`
                "enum class Registry : Iface by if (c) a else a!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // inside an anonymous-`fun` expression body —
                // `fun(p): Iface = (a!!).x({ d })`
                "fun interface Handler {
      fun k(x: Int): Iface
    }\nenum class Registry : Handler by fun(p: Int): Iface = a!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // the `!!`-infix lambda inside an OBJECT's delegate — the `{`
                // steals the object's own `{` path via `ObjectExpression`'s pop.
                // Parenthesized: the unparenthesized `by a!! x { d } { … }`
                // object delegate is a tree-sitter-kotlin-ng residual pinned in
                // `an_object_delegate_postfix_infix_lambda_bails_on_the_grammar`.
                "enum class Registry : Iface by object : Iface by (a!! x { d }) {\n  override fun k() {}\n} {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // `import`/`enum` identifiers take `!!` too —
                // `(`import`!!).x({ d })`
                "enum class Registry : Iface by `import`!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                "enum class Registry : Iface by `enum`!! x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // a `,`-separated supertype still follows the stolen lambda
                "enum class Registry : Iface by a!! x { d }, java.io.Serializable {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // REVERSE PARITY on the same `!` ground — `d` is `y`'s plain
                // *argument* (an operand end, `{` = real body), yet the run
                // judges it "does not end an operand" and the body is swallowed
                // into `expr_brace`: `enum_body_start` bails instead of finding
                // the violation
                "enum class Registry : Iface by a!! y d {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude(OPS, consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: a postfix `!!` ends an operand but grounds \
                 `token_ends_operand`'s run as \"does not end\" — the identifier \
                 after `a!!` is an infix NAME (`(a!!).x({{ d }})` compiles), yet \
                 its `{{` is grabbed as the enum body and the real body mints \
                 phantom consumers (fail-open); the same ground flips `a!! y d`'s \
                 argument `d` to \"does not end\" and swallows the real body \
                 (bail):\n{}",
                leaked.join("\n---\n")
            );
        }

        /// `by object : I by a!! x { d } { … }` unparenthesized is compiling
        /// Kotlin (kotlinc-verified) that tree-sitter-kotlin-ng cannot account
        /// for — a postfix-`!!` operand's infix lambda followed by the object's
        /// own body is an ERROR under the grammar even outside enum headers
        /// (`val o = object : I by a!! x { d } { … }` fails the same way). The
        /// gate cannot model a file it cannot parse, so it fails closed naming
        /// the file.
        #[test]
        fn an_object_delegate_postfix_infix_lambda_bails_on_the_grammar() {
            let dir = tempfile::tempdir().expect("fixture");
            let consumer = enum_prelude(
                OPS,
                "enum class Registry : Iface by object : Iface by a!! x { d } {\n  override fun k() {}\n} {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            );
            usecase_fixture(dir.path(), &consumer);
            let error = lomo_xtask::check_usecase_reachability(dir.path())
                .expect_err("grammar-unaccountable Kotlin must fail closed");
            assert!(
                format!("{error:#}").contains("Kotlin parse error"),
                "the bail names the file and its unaccountable syntax: {error:#}"
            );
        }

        /// GREEN controls — `!!` postfix operand followed directly by the enum
        /// body (`by a!! { … }` — the trailing-lambda ban shape), `!!` before a
        /// binary operator's lambda, member access after `!!`, and the
        /// non-`!!` infix/lambda paths all classify correctly.
        #[test]
        fn postfix_bang_operand_and_operator_positions_still_hold() {
            let mut failed = Vec::new();
            for (consumer, expect_violation) in [
                // `a!!` is a complete operand — `{` is the enum body
                (
                    "enum class Registry : Iface by a!! {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // `f()!!` operand — `{` is the enum body
                (
                    "enum class Registry : Iface by f()!! {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // binary operator after `!!` — `(a!!).plus({ d })` — the `{` is
                // the operator's right-operand lambda, then the real body
                (
                    "enum class Registry : Iface by a!! + { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // member access after `!!` — `(a!!).x` — `{` is the enum body
                (
                    "enum class Registry : Iface by a!!.x {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // call argument inside parens — `an!!.x({ d })` — `{` after `)`
                // is the enum body
                (
                    "enum class Registry : Iface by an!!.x({ d }) {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // non-`!!` infix — `a.x({ d })` — `{` is the infix arg, real body
                // after — still classified correctly
                (
                    "enum class Registry : Iface by a x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // `y` takes an operand arg — `a y {d}` is `a.y({d})` with `{d}`
                // as `y`'s infix arg? — no: `y` takes `Iface`, `{d}` binds as
                // `y`'s arg only if `y` is infix-name — `a y {d}` = `a.y({d})`
                // — `y` is the infix NAME — `{` expression — then real body
                (
                    "enum class Registry : Iface by a y { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                    true,
                ),
                // a clean enum with a real consumer elsewhere — Ok
                (
                    "enum class Registry : Iface by a!! {\n  X;\n  fun f() = 0\n}\nval used = DeadUseCase()",
                    false,
                ),
            ] {
                let consumer = enum_prelude(OPS, consumer);
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                let result = lomo_xtask::check_usecase_reachability(dir.path());
                if reports_violation(&result) != expect_violation {
                    failed.push(format!("{consumer} -> {result:?}"));
                }
            }
            assert!(
                failed.is_empty(),
                "postfix-`!!` operand/operator positions must keep their honest \
                 classification:\n{}",
                failed.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N14-2 — an *annotation name* grounds `token_ends_operand`'s run as
        // "ends an operand" (it is name-position via `@`), so the operand it
        // prefixes — the annotated supertype or the annotated delegate
        // expression — is judged "does not end" and the enum's `{` is
        // swallowed as an expression block: `enum_body_start` bails.
        // kotlinc-verified: `enum class E : @TA Iface`, `enum class E : I,
        // @TA J`, `enum class E : I by object : @TA Iface {}`, `by @EA d`,
        // `by @EA a x { d }` all compile (`@Target(TYPE)` for supertypes,
        // `@Retention(SOURCE) @Target(EXPRESSION)` on the operand).
        // ------------------------------------------------------------------

        /// Annotated supertypes steal the enum `{` into `expr_brace` — the
        /// header scan bails on compilable Kotlin, burying a real consumer
        /// under the bail.
        #[test]
        fn an_annotation_name_grounds_the_operand_run_wrong() {
            let mut suppressed = Vec::new();
            for consumer in [
                // `@TA` on the enum's own supertype — `{` after `Iface`
                "annotation class TA\nenum class Registry : @TA Iface {\n  X;\n  fun f() = 0\n}\nval used = DeadUseCase()",
                // `@TA` on a later `,`-separated supertype — `J` is a single
                // segment so it grounds the run
                "annotation class TA\ninterface J\nenum class Registry : Iface, @TA J {\n  X;\n  fun f() = 0\n}\nval used = DeadUseCase()",
                // qualified annotation name — `@a.b.TA Iface` — the run still
                // grounds on `TA`
                "annotation class TA\nenum class Registry : @a.b.TA Iface {\n  X;\n  fun f() = 0\n}\nval used = DeadUseCase()",
                // `@TA` on the anonymous OBJECT's supertype inside a delegate —
                // the object's `{` is swallowed and the enum's `{` pops the
                // stale `ObjectTypes` context instead of opening the body
                "annotation class TA\nenum class Registry : Iface by object : @TA Iface {
      override fun k() {}
    } {\n  X;\n  fun f() = 0\n}\nval used = DeadUseCase()",
                // `@EA` on the delegate *operand* — `by @EA d { … }` — `d` is
                // the operand end and `{` is the real enum body, yet the run
                // grounds on `EA` and swallows the body
                "annotation class EA\nenum class Registry : Iface by @EA d {\n  X;\n  fun f() = 0\n}\nval used = DeadUseCase()",
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                write(
                    dir.path(),
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
                );
                write(
                    dir.path(),
                    "apps/android/app/src/Consumer.kt",
                    &format!(
                        "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\n\
                         interface Iface {{\n  fun k()\n}}\n\
                         val d: Iface = object : Iface {{\n  override fun k() {{}}\n}}\n\
                         {consumer}\n"
                    ),
                );
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Ok(()) => {}
                    other => suppressed.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                suppressed.is_empty(),
                "MISREPORT: an annotation name grounds `token_ends_operand`'s run \
                 as \"ends an operand\", so the *annotated* operand — a \
                 supertype or the delegate expression itself — is judged \"does \
                 not end\" and the enum's `{{` is swallowed as an expression \
                 block; `enum_body_start` bails on compilable Kotlin and buries \
                 the file's real consumer (`@TA T` supertypes are legal; the \
                 argumented `@TA(x) T` shape survives only via the `)`-owner \
                 check):\n{}",
                suppressed.join("\n---\n")
            );
        }

        /// The annotated operand also flips the parity the *other* way:
        /// `by @EA a x { d }` counts the annotated `a` as run token 1, so the
        /// infix `x` inherits "ends an operand" and its `{` is stolen —
        /// `(a).x({ d })` compiles (kotlinc-verified).
        #[test]
        fn an_annotated_operand_flips_the_infix_parity() {
            let mut leaked = Vec::new();
            for consumer in [
                // `(@EA a).x({ d })` — `x` is the infix name after the annotated
                // operand
                "enum class Registry : Iface by @EA a x { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
                // the same flip on an escaped infix name
                "enum class Registry : Iface by @EA a `x y` { d } {\n  DeadUseCase;\n  fun f() = DeadUseCase\n}\n",
            ] {
                let consumer = enum_prelude(
                    "@Retention(AnnotationRetention.SOURCE) @Target(AnnotationTarget.EXPRESSION) annotation class EA\nclass Op {\n  infix fun x(f: () -> Iface): Iface = d\n  infix fun `x y`(f: () -> Iface): Iface = d\n}\nval a = Op()\n",
                    consumer,
                );
                let dir = tempfile::tempdir().expect("fixture");
                usecase_fixture(dir.path(), &consumer);
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Err(error) if format!("{error:#}").contains("production consumers") => {}
                    other => leaked.push(format!("{consumer} -> {other:?}")),
                }
            }
            assert!(
                leaked.is_empty(),
                "BLIND SPOT: an annotation name grounds `token_ends_operand`'s \
                 run as \"ends an operand\", so the annotated operand `a` counts \
                 as run token 1 and the infix `x` after it inherits \"ends\" — \
                 `{{` stolen, real body mints phantom consumers (fail-open):\n{}",
                leaked.join("\n---\n")
            );
        }

        // ------------------------------------------------------------------
        // N14-3 — `skip_header` ends a header at its *line's* end, so every
        // same-line `;`-separated directive or declaration is eaten with it —
        // `package p; import x`, `import a; import b`, `import a; val g = …`
        // are all legal Kotlin (kotlinc-verified). `header_pieces` also keeps
        // collecting identifier pieces across the `;`, so `import a.b.C; val
        // g = DeadUseCase()` mints a `("DeadUseCase", <garbage-target>)`
        // binding that *shadows* every bare `DeadUseCase` in the file — even
        // a correct `import` on a later line cannot un-shadow it — and
        // `package com.lomo.domain.usecase; class DeadUseCase` poisons the
        // usecase's own `package_name` for the qualified-tail channel.
        // ------------------------------------------------------------------

        /// Same-line `;`-separated directives and declarations: the tail is
        /// swallowed by the first header's line skip — the use-case import
        /// never mints its binding, or mints a garbage-target binding that
        /// shadows the bare name file-wide. Every fixture carries a REAL
        /// consumer (`val used = DeadUseCase()`), so the honest verdict is
        /// `Ok` (reachable); the gate reports a fabricated violation instead —
        /// a fail-closed misreport.
        #[test]
        fn same_line_constructs_after_a_semicolon_are_eaten() {
            let mut misreported = Vec::new();
            for (pkg_and_imports, consumer) in [
                // `package` and `import` on one line — the `import` never mints
                // and `scope.package` collects the import's pieces too
                (
                    "package com.lomo.app; import com.lomo.domain.usecase.DeadUseCase",
                    "val used = DeadUseCase()",
                ),
                // two `import`s on one line — the second never mints
                (
                    "package com.lomo.app\nimport com.lomo.app.Fake; import com.lomo.domain.usecase.DeadUseCase",
                    "val used = DeadUseCase()",
                ),
                // a same-line `val` after the use-case import steals the
                // binding's name slot — the minted `("f", <garbage>)` leaves
                // `DeadUseCase` without an explicit channel
                (
                    "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase; val f = 0",
                    "val used = DeadUseCase()",
                ),
                // `import a.b.C; val g = DeadUseCase()` — the decl's
                // identifiers ride `header_pieces` into the binding: minted
                // `("DeadUseCase", <garbage>)` is an explicit shadow, so the
                // bare name is non-binding FILE-WIDE — a correct `import` on
                // the next line cannot un-shadow it. (Kotlin requires imports
                // ahead of declarations, so the usecase import comes first and
                // the `;`-joined `val g = DeadUseCase()` is the exercised
                // construct.)
                (
                    "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase\nimport com.lomo.app.Fake; val g = DeadUseCase()",
                    "val used = DeadUseCase()",
                ),
                // `package` + `import` + decl all on one line — package is
                // garbage, import binding is garbage-named, consumer lost
                (
                    "package com.lomo.app; import com.lomo.domain.usecase.DeadUseCase; val f = 0",
                    "val used = DeadUseCase()",
                ),
                // the eaten wildcard channel — `import a; import pkg.*` never
                // mints `*`
                (
                    "package com.lomo.app\nimport com.lomo.app.Fake; import com.lomo.domain.usecase.*",
                    "val used = DeadUseCase()",
                ),
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                write(
                    dir.path(),
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
                );
                write(
                    dir.path(),
                    "apps/android/app/src/Consumer.kt",
                    &format!("{pkg_and_imports}\n{consumer}\n"),
                );
                match lomo_xtask::check_usecase_reachability(dir.path()) {
                    Ok(()) => {}
                    other => {
                        misreported.push(format!("{pkg_and_imports}\\n{consumer} -> {other:?}"));
                    }
                }
            }
            assert!(
                misreported.is_empty(),
                "MISREPORT: `skip_header` ends a header at the line end, so a \
                 same-line `;`-separated directive or declaration is eaten with \
                 it — `package p; import x`, `import a; import b` and `import a;\
                 val g = …` are legal Kotlin (kotlinc-verified) — and \
                 `header_pieces` collects the tail's identifiers into the \
                 binding, so `import a.b.C; val g = DeadUseCase()` mints a \
                 `(\"DeadUseCase\", <garbage>)` that *shadows* the bare name \
                 file-wide; the real consumer is missed and the gate reports a \
                 fabricated violation on a reachable usecase:\n{}",
                misreported.join("\n---\n")
            );
        }

        /// `package p; class DeadUseCase` in the *declaration* file poisons the
        /// usecase's `package_name` with the line tail — the qualified-tail
        /// channel in the consumer can never match it, and the fabricated
        /// violation reports a reachable usecase as unreachable.
        #[test]
        fn same_line_package_decl_poisons_the_usecase_package_name() {
            let dir = tempfile::tempdir().expect("fixture");
            write(
                dir.path(),
                "apps/android/domain/src/usecase/DeadUseCase.kt",
                // `package com.lomo.domain.usecase; class DeadUseCase` —
                // kotlinc-verified compilable; `header_pieces` collects the
                // `class`+name pieces into the package string
                "package com.lomo.domain.usecase; class DeadUseCase { operator fun invoke() = Unit; }\n",
            );
            write(
                dir.path(),
                "apps/android/app/src/Consumer.kt",
                // a *qualified* reference — binds only through
                // `qualified_tail_binds`, which needs the decl's package to
                // match `com.lomo.domain.usecase`
                "package com.lomo.app\nval used = com.lomo.domain.usecase.DeadUseCase()\n",
            );
            match lomo_xtask::check_usecase_reachability(dir.path()) {
                Ok(()) => {}
                other => panic!(
                    "MISREPORT: `header_pieces` collects `class` + the declared \
                     name into `package com.lomo.domain.usecase; class \
                     DeadUseCase`'s package string — the usecase's \
                     `package_name` is poisoned, so `qualified_tail_binds` can \
                     never match `com.lomo.domain.usecase` and the qualified \
                     consumer is missed — a fabricated violation on a reachable \
                     usecase: {other:?}"
                ),
            }
        }

        /// GREEN controls — newline-separated directives mint normally, a `;`
        /// inside the preamble is tolerated, a qualified consumer binds by
        /// segment even when the same-line `import` is eaten, and a split
        /// package header resolves.
        #[test]
        fn header_boundaries_still_mint_their_bindings() {
            let mut failed = Vec::new();
            for (pkg_and_imports, consumer) in [
                // baseline — newline-separated directives
                (
                    "package com.lomo.app\nimport com.lomo.domain.usecase.DeadUseCase",
                    "val used = DeadUseCase()",
                ),
                // a leading `;` line in the preamble is tolerated
                (
                    "package com.lomo.app\n;\nimport com.lomo.domain.usecase.DeadUseCase",
                    "val used = DeadUseCase()",
                ),
                // `package p; import x` where the consumer is a qualified
                // reference — the qualified-tail channel binds by *segment*,
                // not through the eaten import
                (
                    "package com.lomo.app; import com.lomo.domain.usecase.DeadUseCase",
                    "val used = com.lomo.domain.usecase.DeadUseCase()",
                ),
                // split package header — `p.\napp` — `import` still parses
                (
                    "package com.lomo.\napp\nimport com.lomo.domain.usecase.DeadUseCase",
                    "val used = DeadUseCase()",
                ),
            ] {
                let dir = tempfile::tempdir().expect("fixture");
                write(
                    dir.path(),
                    "apps/android/domain/src/usecase/DeadUseCase.kt",
                    "package com.lomo.domain.usecase\nclass DeadUseCase { operator fun invoke() = Unit; }\n",
                );
                write(
                    dir.path(),
                    "apps/android/app/src/Consumer.kt",
                    &format!("{pkg_and_imports}\n{consumer}\n"),
                );
                let result = lomo_xtask::check_usecase_reachability(dir.path());
                if result.is_err() {
                    failed.push(format!("{pkg_and_imports}\\n{consumer} -> {result:?}"));
                }
            }
            assert!(
                failed.is_empty(),
                "newline-separated headers and tolerated `;` preamble tokens \
                 must keep minting their bindings:\n{}",
                failed.join("\n---\n")
            );
        }
    }
}
