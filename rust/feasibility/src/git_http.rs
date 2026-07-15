//! Hermetic HTTPS smart-HTTP Git fixture backed by `git-http-backend` + vendored `git2`.
#![allow(
    clippy::too_many_lines,
    clippy::manual_ok_err,
    clippy::option_if_let_else,
    clippy::map_err_ignore,
    clippy::disallowed_methods,
    reason = "smart-HTTP CGI bridge prioritizes explicit control flow over pedantic rewrites"
)]

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;
use std::{fs, io};

use crate::git_probe::GitProbeError;
use git2::{
    CertificateCheckStatus, Cred, ErrorClass, ErrorCode, FetchOptions, PushOptions,
    RemoteCallbacks, Repository, RepositoryInitOptions, Signature, build::RepoBuilder,
};
use rcgen::{CertificateParams, KeyPair};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::{ServerConfig, ServerConnection, StreamOwned};

fn git_err(error: impl std::fmt::Display) -> GitProbeError {
    GitProbeError::Git {
        detail: error.to_string(),
    }
}

fn io_err(error: impl std::fmt::Display) -> GitProbeError {
    GitProbeError::Io {
        detail: error.to_string(),
    }
}

/// Smart-HTTP Git evidence report.
#[derive(Clone, Debug, Eq, PartialEq)]
#[allow(
    clippy::struct_excessive_bools,
    reason = "stage-0 probe report is a flat checklist of independent evidence flags"
)]
pub struct SmartHttpGitReport {
    pub cloned: bool,
    pub pushed: bool,
    pub fetched: bool,
    pub credential_accepted: bool,
    pub certificate_rejected: bool,
    pub non_fast_forward_rejected: bool,
    pub lock_recovery: bool,
}

/// Local HTTPS smart-HTTP server exporting one bare repository via `git-http-backend`.
pub struct SmartHttpGitFixture {
    addr: SocketAddr,
    ca_pem: String,
    /// Leaf certificate DER presented by the fixture server (for client pin checks).
    server_cert_der: Vec<u8>,
    project_root: PathBuf,
    repo_name: String,
    username: String,
    password: String,
    shutdown: Arc<AtomicBool>,
    join: Option<thread::JoinHandle<()>>,
}

impl SmartHttpGitFixture {
    /// Create a bare repo under `root` and serve it over HTTPS smart-HTTP.
    ///
    /// # Errors
    ///
    /// Returns [`GitProbeError`] when TLS, bind, or repository init fails.
    pub fn start(root: &Path) -> Result<Self, GitProbeError> {
        let _provider: Result<(), Arc<rustls::crypto::CryptoProvider>> =
            rustls::crypto::aws_lc_rs::default_provider().install_default();
        if root.exists() {
            fs::remove_dir_all(root).map_err(io_err)?;
        }
        fs::create_dir_all(root).map_err(io_err)?;
        let repo_name = "repo.git".to_owned();
        let bare = root.join(&repo_name);
        let mut bare_opts = RepositoryInitOptions::new();
        bare_opts.bare(true);
        bare_opts.initial_head("main");
        Repository::init_opts(&bare, &bare_opts).map_err(git_err)?;
        // Allow push/fetch over HTTP for the fixture only.
        Command::new("git")
            .args([
                "-C",
                &bare.to_string_lossy(),
                "config",
                "http.receivepack",
                "true",
            ])
            .status()
            .map_err(io_err)?;
        Command::new("git")
            .args([
                "-C",
                &bare.to_string_lossy(),
                "config",
                "http.uploadpack",
                "true",
            ])
            .status()
            .map_err(io_err)?;

        let key_pair = KeyPair::generate().map_err(|error| GitProbeError::Io {
            detail: error.to_string(),
        })?;
        let mut params =
            CertificateParams::new(vec!["localhost".to_owned(), "127.0.0.1".to_owned()]).map_err(
                |error| GitProbeError::Io {
                    detail: error.to_string(),
                },
            )?;
        params
            .distinguished_name
            .push(rcgen::DnType::CommonName, "lomo-git-http");
        let cert = params
            .self_signed(&key_pair)
            .map_err(|error| GitProbeError::Io {
                detail: error.to_string(),
            })?;
        let ca_pem = cert.pem();
        let server_cert_der = cert.der().to_vec();
        let cert_der = CertificateDer::from(server_cert_der.clone());
        let key_der = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_pair.serialize_der()));
        let mut server_config = ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der], key_der)
            .map_err(|error| GitProbeError::Io {
                detail: error.to_string(),
            })?;
        server_config.alpn_protocols = vec![b"http/1.1".to_vec()];
        let server_config = Arc::new(server_config);

        let listener = TcpListener::bind("127.0.0.1:0").map_err(io_err)?;
        let addr = listener.local_addr().map_err(io_err)?;
        listener.set_nonblocking(true).map_err(io_err)?;
        let shutdown = Arc::new(AtomicBool::new(false));
        let shutdown_thread = Arc::clone(&shutdown);
        let project_root = root.to_path_buf();
        let username = "token-user".to_owned();
        let password = "token-secret".to_owned();
        let auth_user = username.clone();
        let auth_pass = password.clone();
        let join = thread::spawn(move || {
            while !shutdown_thread.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let config = Arc::clone(&server_config);
                        let project_root = project_root.clone();
                        let auth_user = auth_user.clone();
                        let auth_pass = auth_pass.clone();
                        let _worker: thread::JoinHandle<()> = thread::spawn(move || {
                            let handled: Result<(), GitProbeError> = handle_git_http(
                                stream,
                                config,
                                &project_root,
                                &auth_user,
                                &auth_pass,
                            );
                            drop(handled);
                        });
                    }
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });

        Ok(Self {
            addr,
            ca_pem,
            server_cert_der,
            project_root: root.to_path_buf(),
            repo_name,
            username,
            password,
            shutdown,
            join: Some(join),
        })
    }

    #[must_use]
    pub fn base_url(&self) -> String {
        format!("https://127.0.0.1:{}/{}", self.addr.port(), self.repo_name)
    }

    #[must_use]
    pub fn ca_pem(&self) -> &str {
        &self.ca_pem
    }

    #[must_use]
    pub fn server_cert_der(&self) -> &[u8] {
        &self.server_cert_der
    }

    #[must_use]
    pub fn username(&self) -> &str {
        &self.username
    }

    #[must_use]
    pub fn password(&self) -> &str {
        &self.password
    }

    #[must_use]
    pub fn bare_path(&self) -> PathBuf {
        self.project_root.join(&self.repo_name)
    }
}

impl Drop for SmartHttpGitFixture {
    fn drop(&mut self) {
        self.shutdown.store(true, Ordering::SeqCst);
        let nudge: Result<TcpStream, std::io::Error> = TcpStream::connect(self.addr);
        drop(nudge);
        if let Some(join) = self.join.take() {
            let joined: thread::Result<()> = join.join();
            drop(joined);
        }
    }
}

/// Run the P0-09 smart-HTTP evidence matrix on a temporary root.
///
/// # Errors
///
/// Returns [`GitProbeError`] when any required capability fails.
pub fn run_smart_http_git_probe(root: &Path) -> Result<SmartHttpGitReport, GitProbeError> {
    if root.exists() {
        fs::remove_dir_all(root).map_err(io_err)?;
    }
    fs::create_dir_all(root).map_err(io_err)?;
    let fixture_root = root.join("server");
    let fixture = SmartHttpGitFixture::start(&fixture_root)?;
    let work = root.join("work");
    let work2 = root.join("work2");

    // Seed an initial commit via filesystem bare push first so smart-HTTP has content.
    seed_bare_via_local(&fixture.bare_path(), &root.join("seed")).map_err(step("seed"))?;

    let certificate_rejected =
        probe_certificate_rejection(&fixture).map_err(step("certificate_rejection"))?;
    let pin_mismatch =
        probe_certificate_pin_mismatch(&fixture).map_err(step("certificate_pin_mismatch"))?;
    if !pin_mismatch {
        return Err(GitProbeError::Unexpected {
            detail: "certificate pin mismatch probe returned false".to_owned(),
        });
    }
    clone_with_credentials(&fixture, &work).map_err(step("clone"))?;
    let credential_accepted = true;
    push_new_commit(&fixture, &work).map_err(step("push"))?;
    let pushed = true;
    fetch_into_second_clone(&fixture, &work2).map_err(step("fetch"))?;
    let fetched = true;
    let non_fast_forward_rejected =
        probe_non_fast_forward(&fixture, &work, &work2).map_err(step("non_fast_forward"))?;
    let lock_recovery = probe_lock_recovery(&work).map_err(step("lock_recovery"))?;
    let cancel_observed =
        probe_transfer_cancel(&fixture, &work).map_err(step("transfer_cancel"))?;

    Ok(SmartHttpGitReport {
        cloned: true,
        pushed,
        fetched,
        credential_accepted,
        certificate_rejected,
        non_fast_forward_rejected,
        lock_recovery: lock_recovery && cancel_observed,
    })
}

/// Cancel a clone via transfer-progress returning false mid-transfer.
///
/// Pass only when the progress callback actually requests cancel **and** the clone returns an
/// error. Silent success after a discarded result is not evidence.
fn probe_transfer_cancel(
    fixture: &SmartHttpGitFixture,
    work: &Path,
) -> Result<bool, GitProbeError> {
    // Ensure remote has a non-trivial pack so transfer-progress fires during clone.
    seed_large_cancel_payload(fixture, work)?;

    let cancel_root = work
        .parent()
        .ok_or_else(|| GitProbeError::Unexpected {
            detail: "cancel work has no parent".to_owned(),
        })?
        .join("cancel-work");
    let removed: Result<(), std::io::Error> = fs::remove_dir_all(&cancel_root);
    drop(removed);

    let username = fixture.username().to_owned();
    let password = fixture.password().to_owned();
    let expected_der = fixture.server_cert_der().to_vec();
    let cancel_requested = Arc::new(AtomicBool::new(false));
    let cancel_flag = Arc::clone(&cancel_requested);
    let mut callbacks = RemoteCallbacks::new();
    callbacks.credentials(move |_url, _username_from_url, _allowed| {
        Cred::userpass_plaintext(&username, &password)
    });
    callbacks.certificate_check(move |cert, _host| {
        let Some(x509) = cert.as_x509() else {
            return Err(git2::Error::from_str("expected x509"));
        };
        if x509.data() == expected_der.as_slice() {
            Ok(CertificateCheckStatus::CertificateOk)
        } else {
            Err(git2::Error::from_str("pin mismatch"))
        }
    });
    // Cancel as soon as progress reports any objects in the pack negotiation.
    let saw_progress = Arc::new(AtomicBool::new(false));
    let saw_flag = Arc::clone(&saw_progress);
    callbacks.transfer_progress(move |stats| {
        saw_flag.store(true, Ordering::SeqCst);
        if stats.received_objects() > 0 || stats.indexed_objects() > 0 || stats.total_objects() > 0
        {
            cancel_flag.store(true, Ordering::SeqCst);
            return false;
        }
        true
    });
    let mut fetch_opts = FetchOptions::new();
    fetch_opts.remote_callbacks(callbacks);
    let clone_result = RepoBuilder::new()
        .fetch_options(fetch_opts)
        .clone(&fixture.base_url(), &cancel_root);
    let cancelled = cancel_requested.load(Ordering::SeqCst);
    let saw = saw_progress.load(Ordering::SeqCst);
    match clone_result {
        Err(_error) if cancelled => Ok(true),
        Err(error) => Err(GitProbeError::Unexpected {
            detail: format!(
                "clone failed without transfer-progress cancel (saw_progress={saw}): {error}"
            ),
        }),
        Ok(_repo) if cancelled => Err(GitProbeError::Unexpected {
            detail: "transfer-progress requested cancel but clone completed successfully"
                .to_owned(),
        }),
        Ok(_repo) => Err(GitProbeError::Unexpected {
            detail: format!(
                "clone completed without transfer-progress cancel (saw_progress={saw})"
            ),
        }),
    }
}

/// Push a large blob so a subsequent fetch has enough pack data for progress callbacks.
fn seed_large_cancel_payload(
    fixture: &SmartHttpGitFixture,
    work: &Path,
) -> Result<(), GitProbeError> {
    let repository = Repository::open(work).map_err(git_err)?;
    let signature = Signature::now("lomo-feasibility", "probe@lomo.local").map_err(git_err)?;
    // ~256 KiB forces multi-chunk transfer progress under libgit2.
    let payload = vec![b'C'; 256 * 1024];
    write_commit(
        &repository,
        &signature,
        "memo/cancel-payload.bin",
        &payload,
        "cancel payload",
    )?;
    let callbacks = trusted_callbacks(fixture);
    let mut push_opts = PushOptions::new();
    push_opts.remote_callbacks(callbacks);
    let mut remote = repository.find_remote("origin").map_err(git_err)?;
    remote
        .push(&["refs/heads/main:refs/heads/main"], Some(&mut push_opts))
        .map_err(|error| GitProbeError::PushRejected {
            detail: error.to_string(),
        })?;
    Ok(())
}

fn step(name: &'static str) -> impl Fn(GitProbeError) -> GitProbeError {
    move |error| GitProbeError::Unexpected {
        detail: format!("{name}: {error}"),
    }
}

fn seed_bare_via_local(bare: &Path, seed: &Path) -> Result<(), GitProbeError> {
    fs::create_dir_all(seed).map_err(io_err)?;
    let mut options = RepositoryInitOptions::new();
    options.initial_head("main");
    let repository = Repository::init_opts(seed, &options).map_err(git_err)?;
    let signature = Signature::now("lomo-feasibility", "probe@lomo.local").map_err(git_err)?;
    write_commit(
        &repository,
        &signature,
        "memo/seed.md",
        b"- 10:00:00\nseed\n",
        "seed",
    )?;
    let url = bare.to_str().ok_or_else(|| GitProbeError::Unexpected {
        detail: "bare path utf8".to_owned(),
    })?;
    let mut remote = repository.remote("origin", url).map_err(git_err)?;
    remote
        .push(&["refs/heads/main:refs/heads/main"], None)
        .map_err(|error| GitProbeError::PushRejected {
            detail: error.to_string(),
        })?;
    Ok(())
}

fn probe_certificate_rejection(fixture: &SmartHttpGitFixture) -> Result<bool, GitProbeError> {
    let url = fixture.base_url();
    let mut callbacks = RemoteCallbacks::new();
    callbacks.credentials(|_url, _username, _allowed| {
        Cred::userpass_plaintext(fixture.username(), fixture.password())
    });
    // No certificate_check pin and no system trust for self-signed fixture CA.
    let mut fetch_opts = FetchOptions::new();
    fetch_opts.remote_callbacks(callbacks);
    let tmp = fixture.project_root.join("cert-reject");
    let removed: Result<(), std::io::Error> = fs::remove_dir_all(&tmp);
    drop(removed);
    let result = RepoBuilder::new()
        .fetch_options(fetch_opts)
        .clone(&url, &tmp);
    match result {
        Err(error) => {
            if is_certificate_class_error(&error) {
                Ok(true)
            } else {
                Err(GitProbeError::Unexpected {
                    detail: format!("expected cert rejection, got {error}"),
                })
            }
        }
        Ok(_) => Err(GitProbeError::Unexpected {
            detail: "untrusted clone must fail certificate check".to_owned(),
        }),
    }
}

/// Clone with credentials but a **wrong** leaf DER pin must fail (not `CertificateOk` for any cert).
fn probe_certificate_pin_mismatch(fixture: &SmartHttpGitFixture) -> Result<bool, GitProbeError> {
    let url = fixture.base_url();
    let wrong_pin = vec![0_u8; fixture.server_cert_der().len().max(32)];
    let username = fixture.username().to_owned();
    let password = fixture.password().to_owned();
    let mut callbacks = RemoteCallbacks::new();
    callbacks.credentials(move |_url, _username_from_url, _allowed| {
        Cred::userpass_plaintext(&username, &password)
    });
    callbacks.certificate_check(move |cert, _host| {
        let Some(x509) = cert.as_x509() else {
            return Err(git2::Error::from_str(
                "expected x509 certificate from smart-HTTP fixture",
            ));
        };
        if x509.data() == wrong_pin.as_slice() {
            Ok(CertificateCheckStatus::CertificateOk)
        } else {
            Err(git2::Error::from_str(
                "smart-HTTP peer certificate does not match fixture leaf DER pin",
            ))
        }
    });
    let mut fetch_opts = FetchOptions::new();
    fetch_opts.remote_callbacks(callbacks);
    let tmp = fixture.project_root.join("cert-pin-mismatch");
    let removed: Result<(), std::io::Error> = fs::remove_dir_all(&tmp);
    drop(removed);
    match RepoBuilder::new()
        .fetch_options(fetch_opts)
        .clone(&url, &tmp)
    {
        Err(error) => {
            let detail = error.to_string().to_ascii_lowercase();
            if detail.contains("does not match")
                || detail.contains("certificate")
                || detail.contains("pin")
            {
                Ok(true)
            } else {
                Err(GitProbeError::Unexpected {
                    detail: format!("expected pin mismatch rejection, got {error}"),
                })
            }
        }
        Ok(_) => Err(GitProbeError::Unexpected {
            detail: "wrong DER pin must reject clone".to_owned(),
        }),
    }
}

fn is_certificate_class_error(error: &git2::Error) -> bool {
    let detail = error.to_string().to_ascii_lowercase();
    if detail.contains("certificate")
        || detail.contains("ssl")
        || detail.contains("tls")
        || detail.contains("secure")
        || detail.contains("cert")
        || detail.contains("handshake")
    {
        return true;
    }
    // Prefer SSL class; bare Net without cert wording is not certificate evidence.
    error.class() == ErrorClass::Ssl
}

fn clone_with_credentials(
    fixture: &SmartHttpGitFixture,
    work: &Path,
) -> Result<Repository, GitProbeError> {
    let removed: Result<(), std::io::Error> = fs::remove_dir_all(work);
    drop(removed);
    let callbacks = trusted_callbacks(fixture);
    let mut fetch_opts = FetchOptions::new();
    fetch_opts.remote_callbacks(callbacks);
    RepoBuilder::new()
        .fetch_options(fetch_opts)
        .clone(&fixture.base_url(), work)
        .map_err(git_err)
}

fn push_new_commit(fixture: &SmartHttpGitFixture, work: &Path) -> Result<(), GitProbeError> {
    let repository = Repository::open(work).map_err(git_err)?;
    let signature = Signature::now("lomo-feasibility", "probe@lomo.local").map_err(git_err)?;
    write_commit(
        &repository,
        &signature,
        "memo/push.md",
        b"- 11:00:00\npush\n",
        "smart-http push",
    )?;
    let callbacks = trusted_callbacks(fixture);
    let mut push_opts = PushOptions::new();
    push_opts.remote_callbacks(callbacks);
    let mut remote = repository.find_remote("origin").map_err(git_err)?;
    remote
        .push(&["refs/heads/main:refs/heads/main"], Some(&mut push_opts))
        .map_err(|error| GitProbeError::PushRejected {
            detail: error.to_string(),
        })?;
    Ok(())
}

fn fetch_into_second_clone(
    fixture: &SmartHttpGitFixture,
    work2: &Path,
) -> Result<(), GitProbeError> {
    let repository = clone_with_credentials(fixture, work2)?;
    let callbacks = trusted_callbacks(fixture);
    let mut fetch_opts = FetchOptions::new();
    fetch_opts.remote_callbacks(callbacks);
    let mut remote = repository.find_remote("origin").map_err(git_err)?;
    remote
        .fetch(
            &["refs/heads/main:refs/remotes/origin/main"],
            Some(&mut fetch_opts),
            None,
        )
        .map_err(git_err)?;
    Ok(())
}

fn probe_non_fast_forward(
    fixture: &SmartHttpGitFixture,
    work: &Path,
    work2: &Path,
) -> Result<bool, GitProbeError> {
    // Divergent histories on work and work2, then non-force push from work2 must reject.
    let repository = Repository::open(work).map_err(git_err)?;
    let signature = Signature::now("lomo-feasibility", "probe@lomo.local").map_err(git_err)?;
    write_commit(
        &repository,
        &signature,
        "memo/divergent-a.md",
        b"a\n",
        "divergent-a",
    )?;
    let callbacks = trusted_callbacks(fixture);
    let mut push_opts = PushOptions::new();
    push_opts.remote_callbacks(callbacks);
    let mut remote = repository.find_remote("origin").map_err(git_err)?;
    remote
        .push(&["refs/heads/main:refs/heads/main"], Some(&mut push_opts))
        .map_err(|error| GitProbeError::PushRejected {
            detail: error.to_string(),
        })?;

    let repository2 = Repository::open(work2).map_err(git_err)?;
    // Reset work2 main to previous tip then create conflicting commit.
    write_commit(
        &repository2,
        &signature,
        "memo/divergent-b.md",
        b"b\n",
        "divergent-b",
    )?;
    let callbacks = trusted_callbacks(fixture);
    let mut push_opts = PushOptions::new();
    push_opts.remote_callbacks(callbacks);
    let mut remote2 = repository2.find_remote("origin").map_err(git_err)?;
    match remote2.push(&["refs/heads/main:refs/heads/main"], Some(&mut push_opts)) {
        Err(error) => {
            let detail = error.to_string().to_ascii_lowercase();
            if detail.contains("non-fast-forward")
                || detail.contains("rejected")
                || detail.contains("fetch first")
                || detail.contains("failed to push")
                || error.code() == ErrorCode::NotFastForward
            {
                Ok(true)
            } else {
                Err(GitProbeError::Unexpected {
                    detail: format!("expected non-ff rejection, got {error}"),
                })
            }
        }
        Ok(()) => Err(GitProbeError::Unexpected {
            detail: "non-force divergent push must be rejected".to_owned(),
        }),
    }
}

fn probe_lock_recovery(work: &Path) -> Result<bool, GitProbeError> {
    let repository = Repository::open(work).map_err(git_err)?;
    let workdir = repository
        .workdir()
        .ok_or_else(|| GitProbeError::Unexpected {
            detail: "bare worktree".to_owned(),
        })?;
    let lock_path = workdir.join(".git/index.lock");
    fs::write(&lock_path, b"locked-by-feasibility-probe\n").map_err(io_err)?;
    let signature = Signature::now("lomo-feasibility", "probe@lomo.local").map_err(git_err)?;
    let locked_error = write_commit(
        &repository,
        &signature,
        "memo/lock.md",
        b"lock\n",
        "should-fail-lock",
    );
    if locked_error.is_ok() {
        return Err(GitProbeError::Unexpected {
            detail: "commit must fail while index.lock exists".to_owned(),
        });
    }
    fs::remove_file(&lock_path).map_err(io_err)?;
    write_commit(
        &repository,
        &signature,
        "memo/lock.md",
        b"lock-recovered\n",
        "lock-recovered",
    )?;
    Ok(true)
}

fn trusted_callbacks(fixture: &SmartHttpGitFixture) -> RemoteCallbacks<'static> {
    let expected_der = fixture.server_cert_der().to_vec();
    let username = fixture.username().to_owned();
    let password = fixture.password().to_owned();
    let mut callbacks = RemoteCallbacks::new();
    callbacks.credentials(move |_url, _username_from_url, _allowed| {
        Cred::userpass_plaintext(&username, &password)
    });
    callbacks.certificate_check(move |cert, _host| {
        // Pin the fixture leaf certificate DER. A substituted server cert must be rejected.
        let Some(x509) = cert.as_x509() else {
            return Err(git2::Error::from_str(
                "expected x509 certificate from smart-HTTP fixture",
            ));
        };
        if x509.data() == expected_der.as_slice() {
            Ok(CertificateCheckStatus::CertificateOk)
        } else {
            Err(git2::Error::from_str(
                "smart-HTTP peer certificate does not match fixture leaf DER pin",
            ))
        }
    });
    callbacks
}

fn write_commit(
    repository: &Repository,
    signature: &Signature<'_>,
    relative: &str,
    bytes: &[u8],
    message: &str,
) -> Result<(), GitProbeError> {
    let path = repository
        .workdir()
        .ok_or_else(|| GitProbeError::Unexpected {
            detail: "bare repository".to_owned(),
        })?
        .join(relative);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(io_err)?;
    }
    fs::write(&path, bytes).map_err(io_err)?;
    let mut index = repository.index().map_err(git_err)?;
    index.add_path(Path::new(relative)).map_err(git_err)?;
    index.write().map_err(git_err)?;
    let tree_id = index.write_tree().map_err(git_err)?;
    let tree = repository.find_tree(tree_id).map_err(git_err)?;
    let parents = match repository.head() {
        Ok(head) => {
            let commit = head.peel_to_commit().map_err(git_err)?;
            vec![commit]
        }
        Err(_) => Vec::new(),
    };
    let parent_refs: Vec<&git2::Commit<'_>> = parents.iter().collect();
    repository
        .commit(
            Some("HEAD"),
            signature,
            signature,
            message,
            &tree,
            &parent_refs,
        )
        .map_err(git_err)?;
    Ok(())
}

fn handle_git_http(
    stream: TcpStream,
    config: Arc<ServerConfig>,
    project_root: &Path,
    username: &str,
    password: &str,
) -> Result<(), GitProbeError> {
    stream
        .set_read_timeout(Some(Duration::from_secs(30)))
        .map_err(io_err)?;
    stream
        .set_write_timeout(Some(Duration::from_secs(30)))
        .map_err(io_err)?;
    let connection = ServerConnection::new(config).map_err(|error| GitProbeError::Io {
        detail: error.to_string(),
    })?;
    let mut tls = StreamOwned::new(connection, stream);
    let mut buffer = Vec::new();
    let mut chunk = [0_u8; 16_384];
    loop {
        let read = tls.read(&mut chunk).map_err(io_err)?;
        if read == 0 {
            break;
        }
        buffer.extend_from_slice(&chunk[..read]);
        if find_header_end(&buffer).is_some() {
            break;
        }
        if buffer.len() > 64 * 1024 {
            break;
        }
    }
    let header_end = find_header_end(&buffer).unwrap_or(buffer.len());
    let headers = String::from_utf8_lossy(&buffer[..header_end]).into_owned();
    let expect_continue = header_value(&headers, "expect")
        .is_some_and(|value| value.eq_ignore_ascii_case("100-continue"));
    if expect_continue {
        tls.write_all(b"HTTP/1.1 100 Continue\r\n\r\n")
            .map_err(io_err)?;
        tls.flush().map_err(io_err)?;
    }
    let transfer_chunked = header_value(&headers, "transfer-encoding")
        .is_some_and(|value| value.to_ascii_lowercase().contains("chunked"));
    let content_length = content_length_of(headers.as_bytes());
    let mut body = if header_end < buffer.len() {
        buffer[header_end..].to_vec()
    } else {
        Vec::new()
    };
    if transfer_chunked {
        // Read until the chunked stream is fully available, then decode.
        loop {
            if let Ok(decoded) = try_decode_chunked(&body) {
                body = decoded;
                break;
            }
            let read = tls.read(&mut chunk).map_err(io_err)?;
            if read == 0 {
                body = try_decode_chunked(&body).unwrap_or_else(|_| body.clone());
                break;
            }
            body.extend_from_slice(&chunk[..read]);
            if body.len() > 64 * 1024 * 1024 {
                break;
            }
        }
    } else if let Some(content_length) = content_length {
        while body.len() < content_length {
            let read = tls.read(&mut chunk).map_err(io_err)?;
            if read == 0 {
                break;
            }
            body.extend_from_slice(&chunk[..read]);
        }
        body.truncate(content_length);
    }
    let first = headers.lines().next().unwrap_or("");
    let mut parts = first.split_whitespace();
    let method = parts.next().unwrap_or("GET");
    let target = parts.next().unwrap_or("/");
    let (path, query) = match target.split_once('?') {
        Some((path, query)) => (path, query),
        None => (target, ""),
    };
    if !authorized(&headers, username, password) {
        let response = "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"lomo\"\r\nContent-Length: 12\r\nConnection: close\r\n\r\nunauthorized";
        tls.write_all(response.as_bytes()).map_err(io_err)?;
        return Ok(());
    }

    let backend = git_http_backend_path().ok_or_else(|| GitProbeError::Unexpected {
        detail: "git-http-backend not found".to_owned(),
    })?;
    let content_type = header_value(&headers, "content-type").unwrap_or("");
    let mut command = Command::new(backend);
    command
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .env("GIT_PROJECT_ROOT", project_root)
        .env("GIT_HTTP_EXPORT_ALL", "1")
        .env("PATH_INFO", path)
        .env("REQUEST_METHOD", method)
        .env("QUERY_STRING", query)
        .env("CONTENT_LENGTH", body.len().to_string())
        .env("CONTENT_TYPE", content_type)
        .env("HTTP_CONTENT_TYPE", content_type)
        .env("REMOTE_ADDR", "127.0.0.1")
        .env("SERVER_PROTOCOL", "HTTP/1.1")
        .env("REQUEST_URI", target)
        .env("REMOTE_USER", username)
        .env("GIT_COMMITTER_NAME", "lomo-feasibility")
        .env("GIT_COMMITTER_EMAIL", "probe@lomo.local");
    let mut child = command.spawn().map_err(io_err)?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin.write_all(&body).map_err(io_err)?;
    }
    let output = child.wait_with_output().map_err(io_err)?;
    if output.stdout.is_empty() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let response = format!(
            "HTTP/1.1 500 Internal Server Error\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{stderr}",
            stderr.len()
        );
        tls.write_all(response.as_bytes()).map_err(io_err)?;
        return Ok(());
    }
    let cgi = output.stdout;
    let (cgi_headers, cgi_body) = split_cgi(&cgi);
    let status = cgi_status(&cgi_headers).unwrap_or(200);
    let reason = if status == 200 { "OK" } else { "Error" };
    let mut response = format!("HTTP/1.1 {status} {reason}\r\nConnection: close\r\n");
    let mut has_content_length = false;
    for line in cgi_headers.lines() {
        let lower = line.to_ascii_lowercase();
        if lower.starts_with("status:") || line.is_empty() {
            continue;
        }
        if lower.starts_with("content-length:") {
            has_content_length = true;
        }
        response.push_str(line);
        response.push_str("\r\n");
    }
    if !has_content_length {
        use std::fmt::Write as _;
        write!(response, "Content-Length: {}\r\n", cgi_body.len())
            .expect("write to String cannot fail");
    }
    response.push_str("\r\n");
    tls.write_all(response.as_bytes()).map_err(io_err)?;
    tls.write_all(cgi_body).map_err(io_err)?;
    tls.flush().map_err(io_err)?;
    Ok(())
}

fn authorized(headers: &str, username: &str, password: &str) -> bool {
    let Some(value) = header_value(headers, "authorization") else {
        return false;
    };
    let Some(encoded) = value
        .strip_prefix("Basic ")
        .or_else(|| value.strip_prefix("basic "))
    else {
        return false;
    };
    let Ok(bytes) = base64_decode(encoded.trim()) else {
        return false;
    };
    // base64_decode now returns Base64Error; keep early-return style.
    let Ok(decoded) = String::from_utf8(bytes) else {
        return false;
    };
    decoded == format!("{username}:{password}")
}

fn header_value<'a>(headers: &'a str, name: &str) -> Option<&'a str> {
    for line in headers.lines().skip(1) {
        if line.is_empty() {
            break;
        }
        let Some((key, value)) = line.split_once(':') else {
            continue;
        };
        if key.eq_ignore_ascii_case(name) {
            return Some(value.trim());
        }
    }
    None
}

fn find_header_end(buffer: &[u8]) -> Option<usize> {
    buffer
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|index| index + 4)
}

fn content_length_of(headers: &[u8]) -> Option<usize> {
    let text = String::from_utf8_lossy(headers);
    for line in text.lines() {
        if let Some(value) = line.to_ascii_lowercase().strip_prefix("content-length:") {
            return match value.trim().parse() {
                Ok(length) => Some(length),
                Err(_) => None,
            };
        }
    }
    None
}

fn try_decode_chunked(input: &[u8]) -> Result<Vec<u8>, ChunkError> {
    let mut out = Vec::new();
    let mut rest = input;
    loop {
        let Some(line_end) = rest.windows(2).position(|window| window == b"\r\n") else {
            return Err(ChunkError::Incomplete);
        };
        let size_line =
            std::str::from_utf8(&rest[..line_end]).map_err(|_| ChunkError::InvalidUtf8)?;
        let size =
            usize::from_str_radix(size_line.trim(), 16).map_err(|_| ChunkError::InvalidSize)?;
        rest = &rest[line_end + 2..];
        if size == 0 {
            return Ok(out);
        }
        if rest.len() < size + 2 {
            return Err(ChunkError::Incomplete);
        }
        out.extend_from_slice(&rest[..size]);
        rest = &rest[size..];
        if rest.starts_with(b"\r\n") {
            rest = &rest[2..];
        } else {
            return Err(ChunkError::MissingCrlf);
        }
    }
}

#[derive(Debug)]
enum ChunkError {
    Incomplete,
    InvalidUtf8,
    InvalidSize,
    MissingCrlf,
}

fn split_cgi(cgi: &[u8]) -> (String, &[u8]) {
    cgi.windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map_or_else(
            || {
                cgi.windows(2)
                    .position(|window| window == b"\n\n")
                    .map_or_else(
                        || (String::new(), cgi),
                        |index| {
                            (
                                String::from_utf8_lossy(&cgi[..index]).into_owned(),
                                &cgi[index + 2..],
                            )
                        },
                    )
            },
            |index| {
                (
                    String::from_utf8_lossy(&cgi[..index]).into_owned(),
                    &cgi[index + 4..],
                )
            },
        )
}

fn cgi_status(headers: &str) -> Option<u16> {
    for line in headers.lines() {
        if let Some(value) = line.to_ascii_lowercase().strip_prefix("status:") {
            let token = value.split_whitespace().next()?;
            return match token.parse() {
                Ok(status) => Some(status),
                Err(_) => None,
            };
        }
    }
    None
}

fn git_http_backend_path() -> Option<PathBuf> {
    match Command::new("git").arg("--exec-path").output() {
        Ok(output) if output.status.success() => {
            let exec = String::from_utf8_lossy(&output.stdout);
            let candidate = PathBuf::from(exec.trim()).join("git-http-backend");
            if candidate.is_file() {
                return Some(candidate);
            }
        }
        _ => {}
    }
    let fallback = PathBuf::from("/usr/lib/git-core/git-http-backend");
    fallback.is_file().then_some(fallback)
}

#[derive(Debug)]
enum Base64Error {
    InvalidCharacter,
}

fn base64_decode(input: &str) -> Result<Vec<u8>, Base64Error> {
    // Minimal base64 decoder for Basic auth (fixture only).
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut values = Vec::new();
    for byte in input.bytes() {
        if byte == b'=' {
            break;
        }
        let Some(index) = TABLE.iter().position(|&item| item == byte) else {
            return Err(Base64Error::InvalidCharacter);
        };
        values.push(u8::try_from(index).map_err(|_| Base64Error::InvalidCharacter)?);
    }
    let mut out = Vec::new();
    for chunk in values.chunks(4) {
        let a = u32::from(chunk.first().copied().unwrap_or(0));
        let b = u32::from(chunk.get(1).copied().unwrap_or(0));
        let c = u32::from(chunk.get(2).copied().unwrap_or(0));
        let d = u32::from(chunk.get(3).copied().unwrap_or(0));
        let triple = (a << 18) | (b << 12) | (c << 6) | d;
        out.push(u8::try_from((triple >> 16) & 0xff).unwrap_or(0));
        if chunk.len() > 2 {
            out.push(u8::try_from((triple >> 8) & 0xff).unwrap_or(0));
        }
        if chunk.len() > 3 {
            out.push(u8::try_from(triple & 0xff).unwrap_or(0));
        }
    }
    Ok(out)
}
