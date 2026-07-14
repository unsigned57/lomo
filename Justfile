set shell := ["bash", "-euo", "pipefail", "-c"]

# Show available commands.
default:
    @just --list

# Show available commands.
list:
    @just --list

# Lightweight iterative gate: model + build + host tests.
fast *args:
    quality/scripts/kotlin_fast_quality_check.sh {{args}}

# Iterative static quality gate (build, Detekt, Lint, shell contracts, host tests).
static *args:
    quality/scripts/kotlin_static_quality_check.sh {{args}}

# Full quality gate (static surface + Compose static + coverage/host tests).
quality *args:
    quality/scripts/kotlin_quality_check.sh {{args}}

# Format staged Kotlin files.
format:
    quality/scripts/kotlin_detekt_format.sh staged

# Format all Kotlin files.
format-all:
    quality/scripts/kotlin_detekt_format.sh all

# Run Kotlin Toolchain host tests (same module set as quality gates).
test *args:
    source quality/scripts/kotlin_toolchain_env.sh && source quality/scripts/kotlin_toolchain_test_args.sh && lomo_kotlin_prepare_env just-test && lomo_kotlin_run test "${toolchain_test_modules[@]}" --build-dir .kotlin/toolchain-build/test {{args}}

# Build the app debug variant.
debug *args:
    source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env just-debug && lomo_kotlin_run build --module app --platform android --variant debug --build-dir .kotlin/toolchain-build/app-debug {{args}}

# Build the app release variant.
release *args:
    source quality/scripts/kotlin_toolchain_env.sh && lomo_kotlin_prepare_env just-release && lomo_kotlin_run build --module app --platform android --variant release --build-dir .kotlin/toolchain-build/app-release {{args}}

# Run Android Lint.
lint:
    quality/scripts/kotlin_android_lint_check.sh

# Run coverage verification.
coverage:
    quality/scripts/kotlin_coverage_check.sh

# Show repo-local generated-state sizes.
cache-audit:
    quality/scripts/kotlin_cache_audit.sh

# Preview generated-state cleanup targets.
cache-clean:
    quality/scripts/kotlin_cache_cleanup.sh --dry-run

# Apply generated-state cleanup.
cache-clean-apply:
    quality/scripts/kotlin_cache_cleanup.sh --apply
