//! Workspace archive v2: plaintext ZIP + `ArchiveManifestV2` (store-owned orchestration).
//!
//! Export streams entries; inspect/import writes an independent staging workspace and fails closed
//! on zip-slip, duplicate entries, compression bombs, checksum mismatch, or unsupported versions.
//! Activate is an atomic generation switch only after full green inspect/import.

use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use lomo_core::LomoError;
use lomo_media::{ContentDigest, MediaRelativePath};
use lomo_workspace::WorkspaceRelativePath;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

use crate::error::{corruption, resource_limit, storage, validation};

/// Manifest schema for archive v2 only (no Kotlin ZIP compat).
pub const ARCHIVE_MANIFEST_SCHEMA_V2: u32 = 2;

/// Manifest entry path inside every archive.
pub const ARCHIVE_MANIFEST_ENTRY: &str = "ArchiveManifestV2.json";

/// Maximum compression ratio allowed (uncompressed / compressed) before bomb reject.
pub const MAX_COMPRESSION_RATIO: u64 = 100;

/// Maximum single entry uncompressed size (512 MiB host safety for dark-build tests).
pub const MAX_ENTRY_UNCOMPRESSED_BYTES: u64 = 512 * 1024 * 1024;

const STREAM_CHUNK: usize = 16 * 1024;

/// Entry kind recorded in the manifest.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ArchiveEntryKind {
    Manifest,
    Markdown,
    Media,
    LomoState,
    LomoHistory,
}

/// One archived entry with length + digest.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ArchiveManifestEntry {
    pub path: String,
    pub kind: ArchiveEntryKind,
    pub size: u64,
    pub digest: String,
}

/// Unique archive v2 manifest.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ArchiveManifestV2 {
    pub schema_version: u32,
    pub entries: Vec<ArchiveManifestEntry>,
}

/// Result of export.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ArchiveExportResult {
    pub archive_path: PathBuf,
    pub manifest: ArchiveManifestV2,
}

/// Result of inspect (no activate).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ArchiveInspectResult {
    pub manifest: ArchiveManifestV2,
    pub staging_root: PathBuf,
}

fn stream_sha256_and_size(path: &Path) -> Result<(String, u64), LomoError> {
    let (digest, size) = ContentDigest::stream_from_path(path)?;
    Ok((digest.as_str().to_owned(), size))
}

fn extension_is(path: &str, ext: &str) -> bool {
    Path::new(path)
        .extension()
        .is_some_and(|value| value.eq_ignore_ascii_case(ext))
}

fn classify_relative(path: &str) -> Result<ArchiveEntryKind, LomoError> {
    if path == ARCHIVE_MANIFEST_ENTRY {
        return Ok(ArchiveEntryKind::Manifest);
    }
    if path.starts_with(".lomo/history/") {
        return Ok(ArchiveEntryKind::LomoHistory);
    }
    // Durable .lomo control tree (state, operations, manifest) is archiveable as LomoState.
    if path.starts_with(".lomo/") {
        return Ok(ArchiveEntryKind::LomoState);
    }
    if extension_is(path, "md") {
        return Ok(ArchiveEntryKind::Markdown);
    }
    MediaRelativePath::parse(path).map_err(|_error| {
        validation(
            "archive_unknown_entry_kind",
            "archive entry path is not markdown, media, or allowed .lomo state/history",
        )
    })?;
    Ok(ArchiveEntryKind::Media)
}

fn should_include(relative: &str) -> bool {
    if relative.is_empty() {
        return false;
    }
    if relative.starts_with(".lomo-sqlite")
        || relative.starts_with(".lomo-media-stage")
        || relative.starts_with(".lomo-media-trash")
        || relative.contains("/.tmp")
        || extension_is(relative, "tmp")
        || extension_is(relative, "db")
        || relative.ends_with(".db-wal")
        || relative.ends_with(".db-shm")
    {
        return false;
    }
    true
}

fn walk_files(base: &Path, out: &mut Vec<PathBuf>) -> Result<(), LomoError> {
    let entries = fs::read_dir(base).map_err(|error| {
        storage(
            "archive_walk_failed",
            &format!("cannot read dir {}: {error}", base.display()),
        )
    })?;
    for entry in entries {
        let entry = entry.map_err(|error| {
            storage(
                "archive_walk_entry_failed",
                &format!("cannot read dir entry: {error}"),
            )
        })?;
        let path = entry.path();
        let file_type = entry.file_type().map_err(|error| {
            storage(
                "archive_walk_type_failed",
                &format!("cannot stat {}: {error}", path.display()),
            )
        })?;
        if file_type.is_dir() {
            walk_files(&path, out)?;
        } else if file_type.is_file() {
            out.push(path);
        }
    }
    Ok(())
}

fn relative_to(root: &Path, path: &Path) -> Result<String, LomoError> {
    let rel = path.strip_prefix(root).map_err(|_error| {
        validation(
            "archive_path_outside_root",
            "file path is outside workspace root",
        )
    })?;
    let text = rel.to_string_lossy().replace('\\', "/");
    WorkspaceRelativePath::parse(&text)?;
    Ok(text)
}

fn copy_file_to_zip(
    zip: &mut ZipWriter<File>,
    absolute: &Path,
    relative: &str,
) -> Result<(), LomoError> {
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    zip.start_file(relative, options).map_err(|error| {
        storage(
            "archive_zip_start_failed",
            &format!("cannot start zip entry {relative}: {error}"),
        )
    })?;
    let mut input = File::open(absolute).map_err(|error| {
        storage(
            "archive_open_source_failed",
            &format!("cannot open {}: {error}", absolute.display()),
        )
    })?;
    let mut buffer = [0_u8; STREAM_CHUNK];
    loop {
        let read = input.read(&mut buffer).map_err(|error| {
            storage(
                "archive_read_source_failed",
                &format!("cannot read {}: {error}", absolute.display()),
            )
        })?;
        if read == 0 {
            break;
        }
        let Some(chunk) = buffer.get(..read) else {
            return Err(validation(
                "archive_copy_chunk_out_of_bounds",
                "archive copy chunk bounds violated",
            ));
        };
        zip.write_all(chunk).map_err(|error| {
            storage(
                "archive_zip_write_failed",
                &format!("cannot write zip entry {relative}: {error}"),
            )
        })?;
    }
    Ok(())
}

/// Exports a workspace to a plaintext ZIP archive with `ArchiveManifestV2`.
///
/// # Errors
///
/// Returns storage/validation when walk, open, or zip write fails.
pub fn archive_export(
    workspace_root: &Path,
    archive_path: &Path,
) -> Result<ArchiveExportResult, LomoError> {
    if !workspace_root.is_dir() {
        return Err(validation(
            "archive_workspace_missing",
            "workspace root must be an existing directory",
        ));
    }
    if let Some(parent) = archive_path.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            storage(
                "archive_parent_create_failed",
                &format!("cannot create archive parent: {error}"),
            )
        })?;
    }

    let mut files = Vec::new();
    walk_files(workspace_root, &mut files)?;
    files.sort();

    let file = File::create(archive_path).map_err(|error| {
        storage(
            "archive_create_failed",
            &format!("cannot create archive: {error}"),
        )
    })?;
    let mut zip = ZipWriter::new(file);
    let mut manifest_entries = Vec::new();
    for absolute in &files {
        let relative = relative_to(workspace_root, absolute)?;
        if !should_include(&relative) {
            continue;
        }
        let kind = classify_relative(&relative)?;
        let (digest, size) = stream_sha256_and_size(absolute)?;
        copy_file_to_zip(&mut zip, absolute, &relative)?;
        manifest_entries.push(ArchiveManifestEntry {
            path: relative,
            kind,
            size,
            digest,
        });
    }

    let manifest = ArchiveManifestV2 {
        schema_version: ARCHIVE_MANIFEST_SCHEMA_V2,
        entries: manifest_entries,
    };
    let full_manifest = write_manifest_entry(&mut zip, &manifest)?;
    zip.finish().map_err(|error| {
        storage(
            "archive_zip_finish_failed",
            &format!("cannot finish archive: {error}"),
        )
    })?;

    Ok(ArchiveExportResult {
        archive_path: archive_path.to_path_buf(),
        manifest: full_manifest,
    })
}

fn write_manifest_entry(
    zip: &mut ZipWriter<File>,
    manifest: &ArchiveManifestV2,
) -> Result<ArchiveManifestV2, LomoError> {
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    let manifest_json = serde_json::to_vec_pretty(manifest).map_err(|error| {
        storage(
            "archive_manifest_encode_failed",
            &format!("cannot encode manifest: {error}"),
        )
    })?;
    let manifest_digest = format!("{:x}", Sha256::digest(&manifest_json));
    zip.start_file(ARCHIVE_MANIFEST_ENTRY, options)
        .map_err(|error| {
            storage(
                "archive_manifest_start_failed",
                &format!("cannot start manifest entry: {error}"),
            )
        })?;
    zip.write_all(&manifest_json).map_err(|error| {
        storage(
            "archive_manifest_write_failed",
            &format!("cannot write manifest entry: {error}"),
        )
    })?;
    let mut full_entries = manifest.entries.clone();
    full_entries.push(ArchiveManifestEntry {
        path: ARCHIVE_MANIFEST_ENTRY.to_owned(),
        kind: ArchiveEntryKind::Manifest,
        size: u64::try_from(manifest_json.len()).unwrap_or(u64::MAX),
        digest: manifest_digest,
    });
    Ok(ArchiveManifestV2 {
        schema_version: ARCHIVE_MANIFEST_SCHEMA_V2,
        entries: full_entries,
    })
}

fn reject_zip_path(name: &str) -> Result<String, LomoError> {
    let normalized = name.replace('\\', "/");
    if normalized.is_empty()
        || normalized.starts_with('/')
        || normalized.contains('\0')
        || normalized.split('/').any(|seg| seg == ".." || seg == ".")
        || normalized.as_bytes().get(1) == Some(&b':')
    {
        return Err(validation(
            "archive_zip_slip",
            "archive entry path fails zip-slip / path policy checks",
        ));
    }
    WorkspaceRelativePath::parse(&normalized).map_err(|_error| {
        validation(
            "archive_zip_slip",
            "archive entry path fails zip-slip / path policy checks",
        )
    })?;
    Ok(normalized)
}

fn check_entry_budget(compressed: u64, uncompressed: u64) -> Result<(), LomoError> {
    if uncompressed > MAX_ENTRY_UNCOMPRESSED_BYTES {
        return Err(resource_limit(
            "archive_entry_too_large",
            "archive entry uncompressed size exceeds limit",
        ));
    }
    if compressed > 0 && uncompressed / compressed > MAX_COMPRESSION_RATIO {
        return Err(resource_limit(
            "archive_compression_bomb",
            "archive entry compression ratio exceeds safety limit",
        ));
    }
    Ok(())
}

fn extract_entry_to_staging(
    entry: &mut zip::read::ZipFile<'_>,
    dest: &Path,
) -> Result<(), LomoError> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            storage(
                "archive_staging_entry_dir_failed",
                &format!("cannot create staging entry dir: {error}"),
            )
        })?;
    }
    let mut out = File::create(dest).map_err(|error| {
        storage(
            "archive_staging_entry_create_failed",
            &format!("cannot create staging file: {error}"),
        )
    })?;
    let mut buffer = [0_u8; STREAM_CHUNK];
    let mut written = 0_u64;
    loop {
        let read = entry.read(&mut buffer).map_err(|error| {
            corruption(
                "archive_entry_read_failed",
                &format!("cannot read zip entry: {error}"),
            )
        })?;
        if read == 0 {
            break;
        }
        written = written.saturating_add(u64::try_from(read).unwrap_or(u64::MAX));
        if written > MAX_ENTRY_UNCOMPRESSED_BYTES {
            return Err(resource_limit(
                "archive_entry_too_large",
                "archive entry uncompressed size exceeds limit while reading",
            ));
        }
        let Some(chunk) = buffer.get(..read) else {
            return Err(validation(
                "archive_extract_chunk_out_of_bounds",
                "archive extract chunk bounds violated",
            ));
        };
        out.write_all(chunk).map_err(|error| {
            storage(
                "archive_staging_write_failed",
                &format!("cannot write staging file: {error}"),
            )
        })?;
    }
    Ok(())
}

fn load_and_verify_manifest(
    staging_root: &Path,
    extracted_paths: &[String],
) -> Result<ArchiveManifestV2, LomoError> {
    let manifest_path = staging_root.join(ARCHIVE_MANIFEST_ENTRY);
    if !manifest_path.is_file() {
        return Err(validation(
            "unsupported_archive_version",
            "archive is missing ArchiveManifestV2 (old Kotlin ZIP rejected)",
        ));
    }
    let manifest_bytes = fs::read(&manifest_path).map_err(|error| {
        storage(
            "archive_manifest_read_failed",
            &format!("cannot read staged manifest: {error}"),
        )
    })?;
    let manifest: ArchiveManifestV2 =
        serde_json::from_slice(&manifest_bytes).map_err(|_error| {
            validation(
                "unsupported_archive_version",
                "archive manifest is not ArchiveManifestV2",
            )
        })?;
    if manifest.schema_version != ARCHIVE_MANIFEST_SCHEMA_V2 {
        return Err(validation(
            "unsupported_archive_version",
            "archive schema_version is not v2",
        ));
    }

    for entry in &manifest.entries {
        if entry.path == ARCHIVE_MANIFEST_ENTRY {
            continue;
        }
        let path = staging_root.join(&entry.path);
        if !path.is_file() {
            return Err(corruption(
                "archive_manifest_missing_file",
                "manifest lists a path that was not extracted",
            ));
        }
        let (digest, size) = stream_sha256_and_size(&path)?;
        if size != entry.size || digest != entry.digest {
            return Err(corruption(
                "archive_entry_checksum_mismatch",
                "extracted entry digest or size does not match manifest",
            ));
        }
        reject_zip_path(&entry.path)?;
    }

    let listed: BTreeSet<_> = manifest.entries.iter().map(|e| e.path.as_str()).collect();
    for name in extracted_paths {
        if name == ARCHIVE_MANIFEST_ENTRY {
            continue;
        }
        if !listed.contains(name.as_str()) {
            return Err(validation(
                "archive_unlisted_entry",
                "archive contains a file not listed in ArchiveManifestV2",
            ));
        }
    }
    Ok(manifest)
}

fn read_le_array2(buf: &[u8], offset: usize) -> Result<[u8; 2], LomoError> {
    let Some(slice) = buf.get(offset..offset + 2) else {
        return Err(corruption(
            "archive_not_zip",
            "truncated zip field (2 bytes)",
        ));
    };
    match <[u8; 2]>::try_from(slice) {
        Ok(array) => Ok(array),
        Err(_error) => Err(corruption(
            "archive_not_zip",
            "truncated zip field (2 bytes)",
        )),
    }
}

fn read_le_array4(buf: &[u8], offset: usize) -> Result<[u8; 4], LomoError> {
    let Some(slice) = buf.get(offset..offset + 4) else {
        return Err(corruption(
            "archive_not_zip",
            "truncated zip field (4 bytes)",
        ));
    };
    match <[u8; 4]>::try_from(slice) {
        Ok(array) => Ok(array),
        Err(_error) => Err(corruption(
            "archive_not_zip",
            "truncated zip field (4 bytes)",
        )),
    }
}

/// Scans central-directory file names for duplicates.
///
/// The `zip` crate collapses same-name central records into one `IndexMap` entry (last wins), so
/// `ZipArchive::by_index` cannot observe dups. Fail closed before extract by walking EOCD + CD.
fn reject_duplicate_central_directory_names(archive_path: &Path) -> Result<(), LomoError> {
    let mut file = File::open(archive_path).map_err(|error| {
        storage(
            "archive_open_failed",
            &format!("cannot open archive: {error}"),
        )
    })?;
    let (total_entries, cd_size, cd_offset) = read_central_directory_locator(&mut file)?;
    if total_entries == 0 || cd_size == 0 {
        return Ok(());
    }
    if cd_size > 64 * 1024 * 1024 {
        return Err(resource_limit(
            "archive_central_directory_too_large",
            "central directory exceeds safety limit",
        ));
    }
    file.seek(SeekFrom::Start(cd_offset)).map_err(|error| {
        storage(
            "archive_seek_failed",
            &format!("cannot seek to central directory: {error}"),
        )
    })?;
    let mut cd = vec![0_u8; usize::try_from(cd_size).unwrap_or(0)];
    file.read_exact(&mut cd).map_err(|error| {
        corruption(
            "archive_not_zip",
            &format!("cannot read central directory: {error}"),
        )
    })?;
    scan_central_directory_names_for_duplicates(&cd, total_entries)
}

fn read_central_directory_locator(file: &mut File) -> Result<(u16, u64, u64), LomoError> {
    let len = file.seek(SeekFrom::End(0)).map_err(|error| {
        storage(
            "archive_seek_failed",
            &format!("cannot seek archive: {error}"),
        )
    })?;
    if len < 22 {
        return Err(corruption(
            "archive_not_zip",
            "archive too small to contain EOCD",
        ));
    }
    // EOCD is at least 22 bytes; comment is ≤ 65535. Search max 64KiB + 22 from end.
    let search = std::cmp::min(len, 65_557);
    file.seek(SeekFrom::End(-i64::try_from(search).unwrap_or(i64::MAX)))
        .map_err(|error| {
            storage(
                "archive_seek_failed",
                &format!("cannot seek archive: {error}"),
            )
        })?;
    let mut tail = vec![0_u8; usize::try_from(search).unwrap_or(0)];
    file.read_exact(&mut tail).map_err(|error| {
        corruption(
            "archive_not_zip",
            &format!("cannot read EOCD search window: {error}"),
        )
    })?;
    let eocd_sig = [0x50, 0x4b, 0x05, 0x06];
    let Some(eocd_rel) = tail.windows(4).rposition(|window| window == eocd_sig) else {
        return Err(corruption("archive_not_zip", "EOCD signature not found"));
    };
    let eocd = tail
        .get(eocd_rel..eocd_rel + 22)
        .ok_or_else(|| corruption("archive_not_zip", "truncated EOCD"))?;
    let total_entries = u16::from_le_bytes(read_le_array2(eocd, 10)?);
    let cd_size = u64::from(u32::from_le_bytes(read_le_array4(eocd, 12)?));
    let cd_offset = u64::from(u32::from_le_bytes(read_le_array4(eocd, 16)?));
    Ok((total_entries, cd_size, cd_offset))
}

fn scan_central_directory_names_for_duplicates(
    cd: &[u8],
    total_entries: u16,
) -> Result<(), LomoError> {
    let mut seen = BTreeSet::new();
    let mut cursor = 0_usize;
    let mut parsed = 0_u16;
    while cursor + 46 <= cd.len() && parsed < total_entries {
        if cd.get(cursor..cursor + 4) != Some(&[0x50, 0x4b, 0x01, 0x02]) {
            return Err(corruption(
                "archive_not_zip",
                "invalid central directory header signature",
            ));
        }
        let name_len = u16::from_le_bytes(read_le_array2(cd, cursor + 28)?) as usize;
        let extra_len = u16::from_le_bytes(read_le_array2(cd, cursor + 30)?) as usize;
        let comment_len = u16::from_le_bytes(read_le_array2(cd, cursor + 32)?) as usize;
        let name_start = cursor + 46;
        let name_end = name_start.saturating_add(name_len);
        if name_end > cd.len() {
            return Err(corruption(
                "archive_not_zip",
                "central directory name out of bounds",
            ));
        }
        let raw_name = cd
            .get(name_start..name_end)
            .ok_or_else(|| corruption("archive_not_zip", "central directory name slice"))?;
        let name = String::from_utf8_lossy(raw_name).replace('\\', "/");
        if !name.is_empty() && !name.ends_with('/') && !seen.insert(name) {
            return Err(validation(
                "archive_duplicate_entry",
                "archive contains duplicate entry paths",
            ));
        }
        cursor = name_end
            .saturating_add(extra_len)
            .saturating_add(comment_len);
        parsed = parsed.saturating_add(1);
    }
    Ok(())
}

/// Inspects an archive into a fresh staging directory (does not touch the live workspace).
///
/// # Errors
///
/// Fail closed on unsupported version, zip-slip, dup entry, bomb ratio, checksum mismatch.
pub fn archive_inspect(
    archive_path: &Path,
    staging_root: &Path,
) -> Result<ArchiveInspectResult, LomoError> {
    if staging_root.exists() {
        return Err(validation(
            "archive_staging_exists",
            "staging root must not already exist",
        ));
    }
    // Dup names must be rejected before ZipArchive collapses central records (last-wins IndexMap).
    reject_duplicate_central_directory_names(archive_path)?;

    fs::create_dir_all(staging_root).map_err(|error| {
        storage(
            "archive_staging_create_failed",
            &format!("cannot create staging root: {error}"),
        )
    })?;

    let file = File::open(archive_path).map_err(|error| {
        storage(
            "archive_open_failed",
            &format!("cannot open archive: {error}"),
        )
    })?;
    let mut zip = ZipArchive::new(file).map_err(|error| {
        corruption(
            "archive_not_zip",
            &format!("archive is not a readable zip: {error}"),
        )
    })?;

    let mut seen = BTreeSet::new();
    let mut extracted_paths = Vec::new();
    for index in 0..zip.len() {
        let mut entry = zip.by_index(index).map_err(|error| {
            corruption(
                "archive_entry_open_failed",
                &format!("cannot open zip entry {index}: {error}"),
            )
        })?;
        if entry.is_dir() {
            continue;
        }
        let name = reject_zip_path(entry.name())?;
        if !seen.insert(name.clone()) {
            // Fail closed without leaving a half-extracted staging tree as a success path.
            drop(fs::remove_dir_all(staging_root));
            return Err(validation(
                "archive_duplicate_entry",
                "archive contains duplicate entry paths",
            ));
        }
        if let Err(error) = check_entry_budget(entry.compressed_size(), entry.size()) {
            drop(fs::remove_dir_all(staging_root));
            return Err(error);
        }
        if let Err(error) = extract_entry_to_staging(&mut entry, &staging_root.join(&name)) {
            drop(fs::remove_dir_all(staging_root));
            return Err(error);
        }
        extracted_paths.push(name);
    }

    match load_and_verify_manifest(staging_root, &extracted_paths) {
        Ok(manifest) => Ok(ArchiveInspectResult {
            manifest,
            staging_root: staging_root.to_path_buf(),
        }),
        Err(error) => {
            drop(fs::remove_dir_all(staging_root));
            Err(error)
        }
    }
}

/// Imports by inspecting into staging (alias for inspect for call-site clarity).
///
/// # Errors
///
/// Same as [`archive_inspect`].
pub fn archive_import(
    archive_path: &Path,
    staging_root: &Path,
) -> Result<ArchiveInspectResult, LomoError> {
    archive_inspect(archive_path, staging_root)
}

/// Atomically activates a fully green staging workspace as the new live root.
///
/// # Errors
///
/// Returns storage when renames fail; validation when staging is missing.
pub fn archive_activate(
    staging_root: &Path,
    live_root: &Path,
    backup_root: &Path,
) -> Result<(), LomoError> {
    archive_activate_with_rename(staging_root, live_root, backup_root, default_rename)
}

fn default_rename(from: &Path, to: &Path) -> Result<(), std::io::Error> {
    fs::rename(from, to)
}

/// Activate with an injectable rename (host tests force mid-activate restore failure).
///
/// Production callers must use [`archive_activate`]. The rename hook is only for proving
/// fail-closed `archive_activate_restore_failed` when restore of the previous generation fails.
///
/// # Errors
///
/// Same codes as [`archive_activate`].
pub fn archive_activate_with_rename(
    staging_root: &Path,
    live_root: &Path,
    backup_root: &Path,
    mut rename: impl FnMut(&Path, &Path) -> Result<(), std::io::Error>,
) -> Result<(), LomoError> {
    if !staging_root.is_dir() {
        return Err(validation(
            "archive_activate_staging_missing",
            "staging root must exist and be a directory before activate",
        ));
    }
    if backup_root.exists() {
        return Err(validation(
            "archive_activate_backup_exists",
            "backup root must not already exist",
        ));
    }
    if live_root.exists() {
        rename(live_root, backup_root).map_err(|error| {
            storage(
                "archive_activate_backup_failed",
                &format!("cannot move live workspace to backup: {error}"),
            )
        })?;
    }
    if let Err(error) = rename(staging_root, live_root) {
        if backup_root.exists() {
            // Fail closed: if previous generation cannot be restored to live, report restore
            // failure rather than silently leaving live empty/missing after a partial swap.
            if let Err(restore_error) = rename(backup_root, live_root) {
                return Err(storage(
                    "archive_activate_restore_failed",
                    &format!(
                        "cannot activate staging workspace ({error}); restore of previous generation also failed: {restore_error}"
                    ),
                ));
            }
        }
        return Err(storage(
            "archive_activate_swap_failed",
            &format!("cannot activate staging workspace: {error}"),
        ));
    }
    Ok(())
}

/// Import archive into staging, atomically activate as live, then rebuild `SQLite` projections.
///
/// Observable generation switch: after Ok, `live_root` holds archive contents and a fresh store
/// projection (rebuild result). Failures before activate leave live untouched; activate failures
/// follow [`archive_activate`] restore rules; rebuild failures leave activated files in place
/// (`SQLite` can be rebuilt again).
///
/// # Errors
///
/// Import/inspect validation, activate storage/validation, or rebuild errors.
pub fn archive_import_activate_rebuild(
    archive_path: &Path,
    staging_root: &Path,
    live_root: &Path,
    backup_root: &Path,
    batch_size: usize,
) -> Result<crate::RebuildResult, LomoError> {
    let _inspected = archive_import(archive_path, staging_root)?;
    archive_activate(staging_root, live_root, backup_root)?;
    let store = crate::Store::open(live_root)?;
    let batch = if batch_size == 0 { 64 } else { batch_size };
    let (_store, result) = store.rebuild(batch)?;
    Ok(result)
}
