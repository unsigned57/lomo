//! Behavior Contract
//!
//! Capability: deterministically fuzz the production `lomo-workspace` owner with arbitrary valid
//! UTF-8 fragment combinations while proving byte-stable serialize and double-parse determinism.
//!
//! Scenarios:
//! - Given a fixed seed and generated mixes of Unicode, BOM/newlines, GFM, Lomo extensions, raw HTML,
//!   controls, and incomplete delimiters, when each source is parsed twice, then both documents are
//!   equal and unedited serialization returns the exact original bytes.
//! - Given explicit seed/case bounds are missing or invalid, when the harness starts, then it fails
//!   closed instead of silently reducing the run.
//!
//! Observable outcomes: one `WORKSPACE_PROPERTY_FUZZ` line with seed, completed cases, total bytes,
//! and maximum owned Render IR node count.
//! TDD proof: RED before implementation was `error: no example target named workspace_property_fuzz
//! in lomo-workspace package`.
//! Excludes: invalid UTF-8 generation (covered by `SourceBytes` contracts), I/O jobs, SAF, and perf.

use std::env;
use std::error::Error;
use std::io::{self, Write};
use std::process::ExitCode;

use lomo_workspace::{SourceBytes, parse_workspace_document};

const DEFAULT_SEED: u64 = 20_260_720;
const DEFAULT_CASES: usize = 10_000;
const MAX_CASES: usize = 1_000_000;
const MAX_FRAGMENTS_PER_CASE: usize = 96;
const FRAGMENTS: &[&str] = &[
    "",
    "plain",
    " ",
    "\t",
    "\n",
    "\r",
    "\r\n",
    "\0",
    "中文",
    "🙂",
    "e\u{301}",
    "## 12:34:56 ",
    "#tag",
    "#层级/标签",
    "@2026-07-20-09:30",
    "@2026-07-20-09:30x2i5rd.done",
    "[link](https://lomo.app?q=中)",
    "![alt](images/a.png)",
    "![[wiki.png]]",
    "[[target|label]]",
    "- [ ] task",
    "- [x] done",
    "**strong**",
    "_emphasis_",
    "~~strike~~",
    "==highlight==",
    "`#not-tag`",
    "```rust\nfn main() {}\n```",
    "| a | b |\n| - | - |\n| 1 | 2 |",
    "> quote",
    "<b>html</b>",
    "<broken",
    "[",
    "]",
    "(",
    ")",
    "\\",
];

fn main() -> ExitCode {
    match run(&env::args().skip(1).collect::<Vec<_>>()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            match writeln!(io::stderr(), "workspace-property-fuzz: {error}") {
                Ok(()) => {}
                Err(_write_error) => {}
            }
            ExitCode::FAILURE
        }
    }
}

fn run(arguments: &[String]) -> Result<(), Box<dyn Error>> {
    let options = FuzzOptions::parse(arguments)?;
    let mut rng = SeedRng::new(options.seed);
    let mut total_bytes = 0_u64;
    let mut max_nodes = 0_u32;
    for case_index in 0..options.cases {
        let bytes = generate_source(&mut rng, case_index)?;
        total_bytes = total_bytes.saturating_add(u64::try_from(bytes.len())?);
        let source = SourceBytes::try_from_bytes(bytes.clone())?;
        let stem = format!("2026-07-{:02}", (case_index % 28) + 1);
        let first = parse_workspace_document(&source, &stem)?;
        let second = parse_workspace_document(&source, &stem)?;
        if first != second {
            return Err(
                io::Error::other(format!("double parse diverged for case {case_index}")).into(),
            );
        }
        if first.serialize_unedited() != bytes {
            return Err(io::Error::other(format!(
                "unedited serialize drifted for case {case_index}"
            ))
            .into());
        }
        max_nodes = max_nodes.max(first.render_document().node_count());
    }
    writeln!(
        io::stdout(),
        "WORKSPACE_PROPERTY_FUZZ seed={} cases={} total_bytes={total_bytes} max_nodes={max_nodes}",
        options.seed,
        options.cases
    )?;
    Ok(())
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct FuzzOptions {
    seed: u64,
    cases: usize,
}

impl FuzzOptions {
    fn parse(arguments: &[String]) -> Result<Self, Box<dyn Error>> {
        let mut seed = DEFAULT_SEED;
        let mut cases = DEFAULT_CASES;
        let mut index = 0;
        while index < arguments.len() {
            let flag = arguments
                .get(index)
                .map(String::as_str)
                .ok_or_else(|| invalid_input("missing flag"))?;
            let value = arguments
                .get(index + 1)
                .ok_or_else(|| invalid_input(format!("missing value for {flag}")))?;
            match flag {
                "--seed" => seed = value.parse::<u64>()?,
                "--cases" => cases = value.parse::<usize>()?,
                other => return Err(invalid_input(format!("unknown flag `{other}`"))),
            }
            index += 2;
        }
        if !(1..=MAX_CASES).contains(&cases) {
            return Err(invalid_input(format!(
                "cases must be within 1..={MAX_CASES}"
            )));
        }
        Ok(Self { seed, cases })
    }
}

fn generate_source(rng: &mut SeedRng, case_index: usize) -> Result<Vec<u8>, io::Error> {
    let fragment_count = rng.next_usize(MAX_FRAGMENTS_PER_CASE + 1)?;
    let mut text = String::new();
    for _ in 0..fragment_count {
        let fragment = FRAGMENTS
            .get(rng.next_usize(FRAGMENTS.len())?)
            .copied()
            .unwrap_or("");
        text.push_str(fragment);
    }
    let mut bytes = Vec::with_capacity(text.len() + 3);
    if case_index.is_multiple_of(7) {
        bytes.extend_from_slice(&[0xef, 0xbb, 0xbf]);
    }
    bytes.extend_from_slice(text.as_bytes());
    Ok(bytes)
}

struct SeedRng(u64);

impl SeedRng {
    const fn new(seed: u64) -> Self {
        Self(seed)
    }

    const fn next(&mut self) -> u64 {
        let mut value = self.0;
        value ^= value << 13;
        value ^= value >> 7;
        value ^= value << 17;
        self.0 = value;
        value
    }

    fn next_usize(&mut self, upper_exclusive: usize) -> Result<usize, io::Error> {
        if upper_exclusive == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "random upper bound must be positive",
            ));
        }
        let upper = u64::try_from(upper_exclusive).map_err(|error| {
            io::Error::other(format!("random bound conversion failed: {error}"))
        })?;
        usize::try_from(self.next() % upper)
            .map_err(|error| io::Error::other(format!("random index conversion failed: {error}")))
    }
}

fn invalid_input(message: impl Into<String>) -> Box<dyn Error> {
    io::Error::new(io::ErrorKind::InvalidInput, message.into()).into()
}
