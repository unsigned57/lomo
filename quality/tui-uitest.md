# TUI UI Test (device-free)

Read this file for PTY-driven TUI debugging: exercising the real `lomo` terminal binary without
manual keypresses, reproducing interaction bugs, and authoring flows.
[AGENTS.md](../AGENTS.md) owns task completion; [Quality](README.md) owns gates (`tuitest` is a
diagnostic); [Architecture](../ARCHITECTURE.md) owns module authority.

## Capability

```bash
just tuitest                    # build lomo (debug), run all committed flows
just tuitest create_memo        # one flow (bare name, .yaml, repo-relative or absolute path)
just tuitest --skip-build       # reuse target/lomo/artifacts/tui/debug/lomo for fast iteration
```

Each flow runs `target/lomo/artifacts/tui/debug/lomo <workspace>` inside an isolated 100x36 PTY
with temporary `HOME`/`XDG_*` paths under `target/lomo/tmp/tui-uitest/<flow>/`, so runs exercise
clean first-run setup without contending with a host `lomo` session. Commands fail closed on
missing binaries, missing flow paths, or unknown flags; an `expect` timeout fails that flow while
retaining evidence.

## Flow files (`apps/tui/uitest/flows/*.yaml`)

```yaml
steps:
  - expect: First-run setup        # wait until the rendered screen grid contains text (default 15s)
  - key: enter                     # named key: enter/esc/tab/arrows/home/end/pgup/pgdn/f5/space
  - key: ctrl+s                    # ctrl chords or single printable char (key: q)
  - send: tuitest memo body        # literal text typed into the focused field
  - sleep_ms: 500                  # last resort — prefer expect for synchronization
  - snap: main                     # dump screen text to <flow>/main.txt
  - expect_exit: true              # assert clean process exit (optional ms)
```

- `expect` matches the `vte`-parsed **rendered screen grid**, not raw terminal escape streams.
- "All memos" renders behind the first-run wizard; wait for `expect: Write a thought` after setup.
- Press `n` (`New memo`) before typing capture text; in Browse mode, letters are commands (`e`/`m`/`d`).

## Terminal capability probe handshake

`lomo` queries terminal graphics/cell capabilities and cursor position at startup (and during inline
redraw/shutdown). `lomo-xtask` replies to those DSR/capability probes directly from the `vte` model
so startup probes settle cleanly before keystrokes arrive.

## Evidence layout (`reports/tui-uitest/`)

- `tui-uitest.v1.json`: binary path, PTY dimensions, options, per-flow status, and artifact paths.
- `<flow>/<snap>.txt`, `<flow>/failed-screen.txt`, `<flow>/final.txt`, `<flow>.log`.

## When to use which surface

- `cargo test -p lomo-tui --locked`: primary behavioral coverage for TEA state/rendering contracts.
- `just tuitest`: end-to-end PTY verification of the real binary, wizard, key dispatch, and exit.
