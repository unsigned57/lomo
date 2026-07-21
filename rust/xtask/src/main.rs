#![deny(unsafe_code)]

mod android;
mod cache;
mod cli;
mod deps;
mod native;
mod perf;
mod quality;
mod rust_pin;
mod tools;
mod util;
mod workspace;

use anyhow::Result;

fn main() -> Result<()> {
    let workspace = workspace::Workspace::discover()?;
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    cli::run(&workspace, &arguments)
}
