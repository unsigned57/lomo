use lomo_sync_core::{
    Backend, LocalSnapshot, MetadataSnapshot, RemoteAbsenceVerification, RemoteSnapshot, Request,
    encode_request, plan, plan_envelope,
};
use std::hint::black_box;
use std::num::TryFromIntError;
use std::process::ExitCode;
use std::time::{Duration, Instant};

fn main() -> ExitCode {
    match run_benchmark() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("planner benchmark failed: {error}");
            ExitCode::FAILURE
        }
    }
}

fn run_benchmark() -> Result<(), Box<dyn std::error::Error>> {
    let sizes = std::env::args()
        .skip(1)
        .map(|value| value.parse::<usize>())
        .collect::<Result<Vec<_>, _>>()?;
    let sizes = if sizes.is_empty() {
        vec![1_000, 10_000, 100_000]
    } else {
        sizes
    };

    println!("scenario,size,iterations,p50_ms,p95_ms");
    for size in sizes {
        let iterations = iterations_for(size);
        run(
            "local_only_pure",
            &local_only_request(size, false)?,
            iterations,
            false,
        )?;
        run(
            "high_conflict_pure",
            &high_conflict_request(size)?,
            iterations,
            false,
        )?;
        run(
            "long_path_envelope",
            &local_only_request(size, true)?,
            iterations,
            true,
        )?;
    }
    Ok(())
}

fn run(
    name: &str,
    request: &Request,
    iterations: usize,
    envelope: bool,
) -> Result<(), lomo_sync_core::ProtocolError> {
    let encoded = if envelope {
        Some(encode_request(request)?)
    } else {
        None
    };
    for _ in 0..3 {
        if let Some(encoded) = &encoded {
            black_box(plan_envelope(black_box(encoded))?);
        } else {
            black_box(plan(black_box(request))?);
        }
    }
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        if let Some(encoded) = &encoded {
            black_box(plan_envelope(black_box(encoded))?);
        } else {
            black_box(plan(black_box(request))?);
        }
        samples.push(started.elapsed());
    }
    samples.sort_unstable();
    let p50 = percentile(&samples, 50);
    let p95 = percentile(&samples, 95);
    println!(
        "{name},{},{iterations},{:.3},{:.3}",
        request.local.len().max(request.remote.len()),
        duration_ms(p50),
        duration_ms(p95),
    );
    Ok(())
}

fn local_only_request(size: usize, long_path: bool) -> Result<Request, TryFromIntError> {
    let mut request = empty_request(Backend::S3);
    request.local = (0..size)
        .map(|index| {
            Ok(LocalSnapshot {
                path: if long_path {
                    format!("vault/{:010}/{}-memo.md", index, "segment".repeat(12))
                } else {
                    format!("memo/{index:010}.md")
                },
                last_modified: timestamp(index)?,
                size: Some(256),
                fingerprint: None,
            })
        })
        .collect::<Result<Vec<_>, TryFromIntError>>()?;
    Ok(request)
}

fn high_conflict_request(size: usize) -> Result<Request, TryFromIntError> {
    let mut request = empty_request(Backend::WebDav);
    request.local = (0..size)
        .map(|index| {
            Ok(LocalSnapshot {
                path: format!("memo/{index:010}.md"),
                last_modified: timestamp(index)? + 2,
                size: Some(256),
                fingerprint: Some(format!("local-{index}")),
            })
        })
        .collect::<Result<Vec<_>, TryFromIntError>>()?;
    request.remote = (0..size)
        .map(|index| {
            Ok(RemoteSnapshot {
                path: format!("memo/{index:010}.md"),
                etag: Some(format!("remote-{index}")),
                last_modified: Some(timestamp(index)? + 2),
                size: Some(256),
                fingerprint: None,
            })
        })
        .collect::<Result<Vec<_>, TryFromIntError>>()?;
    request.metadata = (0..size)
        .map(|index| {
            let timestamp = timestamp(index)?;
            Ok(MetadataSnapshot {
                path: format!("memo/{index:010}.md"),
                etag: Some(format!("old-remote-{index}")),
                remote_last_modified: Some(timestamp),
                local_last_modified: Some(timestamp),
                local_fingerprint: Some(format!("old-local-{index}")),
                last_synced_at: timestamp,
            })
        })
        .collect::<Result<Vec<_>, TryFromIntError>>()?;
    Ok(request)
}

const fn empty_request(backend: Backend) -> Request {
    Request {
        backend,
        timestamp_tolerance_ms: 1_000,
        local: Vec::new(),
        remote: Vec::new(),
        metadata: Vec::new(),
        pre_resolved: Vec::new(),
        suppressed: Vec::new(),
        missing_remote_verification: Vec::new(),
        default_missing_remote_verification: RemoteAbsenceVerification::VerifiedAbsent,
    }
}

const fn iterations_for(size: usize) -> usize {
    match size {
        0..=1_000 => 100,
        1_001..=10_000 => 25,
        _ => 5,
    }
}

fn timestamp(index: usize) -> Result<i64, TryFromIntError> {
    i64::try_from(index)
}

fn percentile(samples: &[Duration], percentile: usize) -> Duration {
    let index = ((samples.len() - 1) * percentile) / 100;
    samples[index]
}

fn duration_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}
