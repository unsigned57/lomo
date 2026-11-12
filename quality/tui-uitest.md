# TUI UI Test (device-free)

Read this file for the PTY-driven TUI debug loop: how to exercise the real `lomo`
terminal binary without manual keypresses, reproduce interaction bugs quickly, and
author new flows. It is the TUI counterpart of [Android UI test](android-uitest.md).
[AGENTS.md](../AGENTS.md) owns authorization and task execution; [Quality](README.md)
owns gates — `tuitest` is a diagnostic, not a required gate;
[Architecture](../ARCHITECTURE.md) owns module authority.

## Capability

One command drives the full loop:

```bash
just tuitest                    # build lomo (debug), run all committed flows
just tuitest create_memo        # one flow (bare name, .yaml, repo-relative or absolute path)
just tuitest --skip-build       # reuse target/lomo/artifacts/tui/debug/lomo — the fastest bug-iteration loop
```

Every flow spawns `target/lomo/artifacts/tui/debug/lomo <workspace>` inside a 100x36 pseudo-terminal with
a fresh isolated `HOME` — `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME` and
`XDG_RUNTIME_DIR` all point into `target/lomo/tmp/tui-uitest/<flow>/`, so flows carry
no state, exercise the first-run wizard when it appears, and never race a running
host `lomo` for the transaction lock.

The command fails closed: a missing binary (without `--skip-build`), missing flow
directory, unknown selector or unknown flag aborts before any PTY work; an `expect`
timeout fails that flow while the suite keeps collecting evidence.

## Flow files

Committed flows live in `apps/tui/uitest/flows/*.yaml` — declarative YAML steps, same
authoring model as Maestro flows:

```yaml
steps:
  - expect: First-run setup        # wait until the screen grid contains the text
  - key: enter                     # named key: enter/esc/tab/arrows/home/end/pgup/pgdn/f5/space
  - key: ctrl+s                    # ctrl chords; a lone printable char works too (key: q)
  - send: tuitest memo body        # literal text typed into the focused field
  - sleep_ms: 500                  # last resort — expect is the real synchronizer
  - snap: main                     # dump the screen text to <flow>/main.txt
  - expect_exit: true              # assert the process exits cleanly (optional ms)
```

Key vocabulary notes:

- `expect` waits on the **rendered screen grid** — the equivalent of Maestro's
  `assertVisible`, parsed from the real byte stream by a `vte` terminal model, not a
  raw-bytes grep. Default deadline 15s.
- "All memos" is visible *behind* the first-run wizard — post-setup synchronization
  uses `expect: Write a thought` (renders only once the runtime is ready).
- Compose opens with `n` and announces `New memo`; never type into Browse scope —
  letters are commands there (`e`/`m`/`d` act on the selection).

## Terminal-quirk note (why input used to starve)

`lomo` runs a graphics-capability probe at startup (kitty/sixel/cell-size/DSR) that
owns stdin until the terminal answers. A PTY that never replies leaves the probe's
timed-out reader thread consuming stdin forever — every later keystroke is eaten
before the event loop sees it. `lomo-xtask` therefore answers the probe like a real
terminal: `\x1b[0n` for the DSR query and a `\x1b[6;<h>;<w>t` cell-size reply, while
claiming no graphics capability. Do not strip this from `PtySession` — without it
every `key`/`send` step silently starves.

## Evidence layout

`reports/tui-uitest/` (gitignored):

- `tui-uitest.v1.json` — binary path, PTY geometry, options, per-flow status and
  artifact paths.
- `<flow>/<snap>.txt` — screen-text snapshots declared by `snap` steps.
- `<flow>/failed-screen.txt` — screen dump at the failing step (written on failure).
- `<flow>/final.txt` — screen state when the flow ended.
- `<flow>.log` — per-step execution transcript.

## When to use which surface

- Rust unit/contract tests (`cargo test -p lomo-tui`) remain the primary behavioral
  coverage — they exercise the TEA model without a terminal.
- `just tuitest` is for end-to-end interaction evidence: first-run wizard, real key
  dispatch, screen output and quit handshake on the actual binary.
