#!/usr/bin/env bash

# Host-runnable module test surface. Device/instrumentation suites were deleted or
# rewritten as host tests; permanent class-level gate exclusions are forbidden.
toolchain_test_modules=(
  --include-module=app
  --include-module=data
  --include-module=detekt-rules
  --include-module=domain
  --include-module=ui-components
)
