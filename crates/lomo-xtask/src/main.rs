#![deny(unsafe_code)]

use anyhow::Result;

fn main() -> Result<()> {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    lomo_xtask::run_cli(&arguments)
}
