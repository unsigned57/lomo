use lomo_sync_core::{
    Backend, LocalSnapshot, MetadataSnapshot, RemoteAbsenceVerification, RemoteSnapshot, Request,
    encode_request, plan, plan_envelope,
};
use std::hint::black_box;
use std::time::{Duration, Instant};

fn main() {
    let sizes = std::env::args()
        .skip(1)
        .map(|value| {
            value
                .parse::<usize>()
                .expect("sizes must be positive integers")
        })
        .collect::<Vec<_>>();
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
            &local_only_request(size, false),
            iterations,
            false,
        );
        run(
            "high_conflict_pure",
            &high_conflict_request(size),
            iterations,
            false,
        );
        run(
            "long_path_envelope",
            &local_only_request(size, true),
            iterations,
            true,
        );
    }
}

fn run(name: &str, request: &Request, iterations: usize, envelope: bool) {
    let encoded = envelope.then(|| encode_request(request).expect("benchmark request must encode"));
    for _ in 0..3 {
        if let Some(encoded) = &encoded {
            black_box(plan_envelope(black_box(encoded)).expect("benchmark envelope must plan"));
        } else {
            black_box(plan(black_box(request)).expect("benchmark request must plan"));
        }
    }
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        if let Some(encoded) = &encoded {
            black_box(plan_envelope(black_box(encoded)).expect("benchmark envelope must plan"));
        } else {
            black_box(plan(black_box(request)).expect("benchmark request must plan"));
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
}

fn local_only_request(size: usize, long_path: bool) -> Request {
    let mut request = empty_request(Backend::S3);
    request.local = (0..size)
        .map(|index| LocalSnapshot {
            path: if long_path {
                format!("vault/{:010}/{}-memo.md", index, "segment".repeat(12))
            } else {
                format!("memo/{index:010}.md")
            },
            last_modified: timestamp(index),
            size: Some(256),
            fingerprint: None,
        })
        .collect();
    request
}

fn high_conflict_request(size: usize) -> Request {
    let mut request = empty_request(Backend::WebDav);
    request.local = (0..size)
        .map(|index| LocalSnapshot {
            path: format!("memo/{index:010}.md"),
            last_modified: timestamp(index) + 2,
            size: Some(256),
            fingerprint: Some(format!("local-{index}")),
        })
        .collect();
    request.remote = (0..size)
        .map(|index| RemoteSnapshot {
            path: format!("memo/{index:010}.md"),
            etag: Some(format!("remote-{index}")),
            last_modified: Some(timestamp(index) + 2),
            size: Some(256),
            fingerprint: None,
        })
        .collect();
    request.metadata = (0..size)
        .map(|index| MetadataSnapshot {
            path: format!("memo/{index:010}.md"),
            etag: Some(format!("old-remote-{index}")),
            remote_last_modified: Some(timestamp(index)),
            local_last_modified: Some(timestamp(index)),
            local_fingerprint: Some(format!("old-local-{index}")),
            last_synced_at: timestamp(index),
        })
        .collect();
    request
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

fn timestamp(index: usize) -> i64 {
    i64::try_from(index).expect("benchmark size must fit in i64")
}

fn percentile(samples: &[Duration], percentile: usize) -> Duration {
    let index = ((samples.len() - 1) * percentile) / 100;
    samples[index]
}

fn duration_ms(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}
