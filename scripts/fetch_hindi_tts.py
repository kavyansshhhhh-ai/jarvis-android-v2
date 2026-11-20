#!/usr/bin/env python3
"""
Fetches and prepares genuine offline Hindi/English neural TTS runtime (Sherpa-ONNX static AAR)
and Supertonic-3 int8 model assets
into app/libs and app/build/generated/tts-assets. Idempotent, validated, and safe for CI/Gradle.
"""
import http.client
import json
import os
import pathlib
import shutil
import ssl
import sys
import tarfile
import time
import traceback
import urllib.error
import urllib.request
import zipfile
from typing import Callable, Iterable

# Ensure unbuffered output even when invoked without -u
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(line_buffering=True)
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(line_buffering=True)

ROOT = pathlib.Path(__file__).resolve().parent.parent
LIBS_DIR = ROOT / "app" / "libs"
ASSETS_OUT = ROOT / "app" / "build" / "generated" / "tts-assets" / "voices"
CACHE_DIR = ROOT / "app" / "build" / "tmp" / "tts-downloads"

AAR_NAME = "sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
AAR_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/{AAR_NAME}"
AAR_REQUIRED_ENTRIES = (
    "classes.jar",
    "jni/arm64-v8a/libsherpa-onnx-jni.so",
    "jni/armeabi-v7a/libsherpa-onnx-jni.so",
)

PIPER_MODELS = [
    (
        "priyamvada",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-priyamvada-medium-int8.tar.bz2",
        "hi_IN-priyamvada-medium",
    ),
    (
        "pratham",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium-int8.tar.bz2",
        "hi_IN-pratham-medium",
    ),
    (
        "rohan",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-rohan-medium-int8.tar.bz2",
        "hi_IN-rohan-medium",
    ),
]

KOKORO_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
SUPERTONIC_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2"

SUPERTONIC_REQUIRED_FILES = (
    "duration_predictor.int8.onnx",
    "text_encoder.int8.onnx",
    "vector_estimator.int8.onnx",
    "vocoder.int8.onnx",
    "tts.json",
    "unicode_indexer.bin",
    "voice.bin",
    "LICENSE",
)
SUPERTONIC_EXTRACT_FILES = SUPERTONIC_REQUIRED_FILES

ESPEAK_REQUIRED_FILES = (
    "hi_dict",
    "phontab",
    "phondata",
    "phonindex",
    "intonations",
    "lang/inc/hi",
)

STAMP_FILE = ASSETS_OUT / ".tts-assets-v3.ready"

DOWNLOAD_TIMEOUT_SECONDS = 60
MAX_DOWNLOAD_ATTEMPTS = 4
BASE_BACKOFF_SECONDS = 2.0
CHUNK_SIZE_BYTES = 128 * 1024
USER_AGENT = "jarvis-android-tts-fetcher/1.0"


def _log(message: str) -> None:
    print(f"[fetch_hindi_tts] {message}", flush=True)


def _err(message: str) -> None:
    print(f"[fetch_hindi_tts] ERROR: {message}", file=sys.stderr, flush=True)


def _non_empty_file(path: pathlib.Path) -> bool:
    return path.exists() and path.is_file() and path.stat().st_size > 0


def validate_aar_zip(path: pathlib.Path) -> None:
    if not _non_empty_file(path):
        raise ValueError(f"AAR file missing or empty: {path}")
    if not zipfile.is_zipfile(path):
        raise ValueError(f"AAR file is not a valid ZIP archive: {path}")
    with zipfile.ZipFile(path, "r") as zf:
        bad_member = zf.testzip()
        if bad_member is not None:
            raise ValueError(f"Corrupted ZIP entry '{bad_member}' inside {path.name}")
        names = set(zf.namelist())
        for req in AAR_REQUIRED_ENTRIES:
            if req not in names:
                raise ValueError(f"Required AAR entry '{req}' missing from {path.name}")


def validate_tar_bz2(path: pathlib.Path, required_basenames: Iterable[str] = ()) -> None:
    if not _non_empty_file(path):
        raise ValueError(f"Archive missing or empty: {path}")
    required_set = set(required_basenames)
    found_basenames = set()
    try:
        with tarfile.open(path, "r:bz2") as tf:
            for member in tf:
                if member.isfile():
                    found_basenames.add(pathlib.PurePosixPath(member.name).name)
    except (tarfile.TarError, EOFError, OSError) as exc:
        raise ValueError(f"Invalid or truncated tar.bz2 archive '{path.name}': {exc}") from exc

    missing = sorted(required_set - found_basenames)
    if missing:
        raise ValueError(f"Archive '{path.name}' missing required files: {', '.join(missing)}")


def _remove_file_quietly(path: pathlib.Path) -> None:
    try:
        if path.exists():
            path.unlink()
    except OSError:
        pass


def download_with_retries(
    url: str,
    dest: pathlib.Path,
    validator: Callable[[pathlib.Path], None],
    label: str,
) -> pathlib.Path:
    dest.parent.mkdir(parents=True, exist_ok=True)

    if dest.exists():
        try:
            _log(f"Validating cached {label} ({dest.name})...")
            validator(dest)
            _log(f"Cached {label} passed integrity validation ({dest.stat().st_size} bytes).")
            return dest
        except Exception as exc:
            _err(f"Cached {label} failed validation ({exc}); removing and re-downloading.")
            _remove_file_quietly(dest)

    part_path = dest.with_name(f"{dest.name}.part")
    last_error: Exception | None = None

    for attempt in range(1, MAX_DOWNLOAD_ATTEMPTS + 1):
        _remove_file_quietly(part_path)
        try:
            _log(f"Downloading {label} (attempt {attempt}/{MAX_DOWNLOAD_ATTEMPTS}): {url}")
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            bytes_written = 0
            expected_length: int | None = None

            with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT_SECONDS) as resp:
                status = getattr(resp, "status", 200)
                if status < 200 or status >= 300:
                    raise urllib.error.HTTPError(url, status, f"Unexpected HTTP status {status}", resp.headers, None)

                raw_len = resp.headers.get("Content-Length")
                if raw_len and raw_len.isdigit():
                    expected_length = int(raw_len)
                    if expected_length <= 0:
                        raise ValueError(f"Server returned empty Content-Length ({expected_length}) for {url}")

                with open(part_path, "wb") as out:
                    while True:
                        chunk = resp.read(CHUNK_SIZE_BYTES)
                        if not chunk:
                            break
                        out.write(chunk)
                        bytes_written += len(chunk)
                    out.flush()
                    os.fsync(out.fileno())

            if bytes_written <= 0:
                raise ValueError(f"Downloaded 0 bytes for {label}")
            if expected_length is not None and bytes_written != expected_length:
                raise ValueError(
                    f"Truncated download for {label}: got {bytes_written} bytes, expected {expected_length}"
                )

            _log(f"Verifying downloaded archive for {label} ({bytes_written} bytes)...")
            validator(part_path)
            part_path.replace(dest)
            _log(f"Successfully downloaded and verified {label} -> {dest}")
            return dest

        except (
            urllib.error.HTTPError,
            urllib.error.URLError,
            http.client.HTTPException,
            ssl.SSLError,
            TimeoutError,
            OSError,
            ValueError,
        ) as exc:
            last_error = exc
            _remove_file_quietly(part_path)
            _err(f"Attempt {attempt}/{MAX_DOWNLOAD_ATTEMPTS} failed for {label}: {exc}")
            if attempt < MAX_DOWNLOAD_ATTEMPTS:
                delay = BASE_BACKOFF_SECONDS * (2 ** (attempt - 1))
                _log(f"Retrying {label} in {delay:.1f}s...")
                time.sleep(delay)
        except Exception as exc:
            _remove_file_quietly(part_path)
            raise RuntimeError(f"Unexpected error downloading {label}: {exc}") from exc

    raise RuntimeError(
        f"Failed to download and verify {label} after {MAX_DOWNLOAD_ATTEMPTS} attempts: {last_error}"
    )


def _write_member_atomic(tf: tarfile.TarFile, member: tarfile.TarInfo, target: pathlib.Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f"{target.name}.tmp")
    _remove_file_quietly(tmp)
    try:
        src = tf.extractfile(member)
        if src is None:
            raise ValueError(f"Cannot read tar member '{member.name}'")
        with src, open(tmp, "wb") as out:
            shutil.copyfileobj(src, out, length=CHUNK_SIZE_BYTES)
            out.flush()
        if member.size > 0 and tmp.stat().st_size != member.size:
            raise ValueError(
                f"Extracted member '{member.name}' size mismatch: got {tmp.stat().st_size}, expected {member.size}"
            )
        tmp.replace(target)
    except Exception:
        _remove_file_quietly(tmp)
        raise


def _espeak_is_complete(dest_espeak: pathlib.Path) -> bool:
    return all(_non_empty_file(dest_espeak / rel) for rel in ESPEAK_REQUIRED_FILES)


def extract_espeak_once(tf: tarfile.TarFile, dest_espeak: pathlib.Path, archive_label: str) -> None:
    if _espeak_is_complete(dest_espeak):
        return
    _log(f"Extracting espeak-ng-data from {archive_label} -> {dest_espeak}")
    dest_espeak.mkdir(parents=True, exist_ok=True)
    marker = "/espeak-ng-data/"
    extracted_count = 0
    for member in tf.getmembers():
        if not member.isfile() or marker not in member.name:
            continue
        rel = member.name.split(marker, 1)[1]
        if not rel or rel.startswith("/") or ".." in pathlib.PurePosixPath(rel).parts:
            continue
        target = dest_espeak / rel
        _write_member_atomic(tf, member, target)
        extracted_count += 1

    if not _espeak_is_complete(dest_espeak):
        missing = [rel for rel in ESPEAK_REQUIRED_FILES if not _non_empty_file(dest_espeak / rel)]
        raise RuntimeError(
            f"espeak-ng-data extraction from {archive_label} incomplete; missing: {', '.join(missing)}"
        )
    _log(f"Extracted {extracted_count} espeak-ng-data files.")


def _validate_json_file(path: pathlib.Path) -> None:
    if not _non_empty_file(path):
        raise ValueError(f"JSON file missing or empty: {path}")
    with open(path, "r", encoding="utf-8") as fp:
        data = json.load(fp)
    if not isinstance(data, dict):
        raise ValueError(f"Expected JSON object in {path}")


def _piper_outputs_valid(name: str, model_stem: str) -> bool:
    out_dir = ASSETS_OUT / "piper" / name
    model_file = out_dir / f"{model_stem}.onnx"
    tokens_file = out_dir / "tokens.txt"
    config_file = out_dir / f"{model_stem}.onnx.json"
    if not (_non_empty_file(model_file) and _non_empty_file(tokens_file) and _non_empty_file(config_file)):
        return False
    try:
        _validate_json_file(config_file)
    except Exception:
        return False
    return True


def extract_piper(name: str, url: str, model_stem: str) -> None:
    espeak_dir = ASSETS_OUT / "espeak-ng-data"
    if _piper_outputs_valid(name, model_stem) and _espeak_is_complete(espeak_dir):
        _log(f"Piper voice '{name}' already extracted and verified.")
        return

    req_files = (f"{model_stem}.onnx", f"{model_stem}.onnx.json", "tokens.txt")
    archive = download_with_retries(
        url=url,
        dest=CACHE_DIR / f"{name}.tar.bz2",
        validator=lambda p: validate_tar_bz2(p, req_files),
        label=f"Piper Hindi voice ({name})",
    )

    out_dir = ASSETS_OUT / "piper" / name
    out_dir.mkdir(parents=True, exist_ok=True)
    _log(f"Extracting Piper voice '{name}' from {archive.name}...")
    allowed = set(req_files) | {"MODEL_CARD"}
    with tarfile.open(archive, "r:bz2") as tf:
        extract_espeak_once(tf, espeak_dir, archive.name)
        for member in tf.getmembers():
            if not member.isfile():
                continue
            base = pathlib.PurePosixPath(member.name).name
            if base in allowed:
                _write_member_atomic(tf, member, out_dir / base)

    if not _piper_outputs_valid(name, model_stem):
        raise RuntimeError(f"Piper voice '{name}' extraction verification failed in {out_dir}")
    _log(f"Extracted and verified Piper voice '{name}'.")


def _kokoro_outputs_valid() -> bool:
    out_dir = ASSETS_OUT / "kokoro"
    return all(
        _non_empty_file(out_dir / fname)
        for fname in ("model.int8.onnx", "voices.bin", "tokens.txt")
    )


def extract_kokoro() -> None:
    espeak_dir = ASSETS_OUT / "espeak-ng-data"
    if _kokoro_outputs_valid() and _espeak_is_complete(espeak_dir):
        _log("Kokoro multi-lang assets already extracted and verified.")
        return

    req_files = ("model.int8.onnx", "voices.bin", "tokens.txt")
    archive = download_with_retries(
        url=KOKORO_URL,
        dest=CACHE_DIR / "kokoro-int8-multi-lang-v1_0.tar.bz2",
        validator=lambda p: validate_tar_bz2(p, req_files),
        label="Kokoro multi-lang int8",
    )

    out_dir = ASSETS_OUT / "kokoro"
    out_dir.mkdir(parents=True, exist_ok=True)
    _log(f"Extracting Kokoro multi-lang assets from {archive.name}...")
    allowed = set(req_files) | {"LICENSE"}
    with tarfile.open(archive, "r:bz2") as tf:
        extract_espeak_once(tf, espeak_dir, archive.name)
        for member in tf.getmembers():
            if not member.isfile():
                continue
            if "/dict/" in member.name or "/espeak-ng-data/" in member.name:
                continue
            base = pathlib.PurePosixPath(member.name).name
            if base in allowed:
                _write_member_atomic(tf, member, out_dir / base)

    if not _kokoro_outputs_valid():
        raise RuntimeError(f"Kokoro extraction verification failed in {out_dir}")
    _log("Extracted and verified Kokoro multi-lang assets.")


def _supertonic_outputs_valid() -> bool:
    out_dir = ASSETS_OUT / "supertonic"
    if not all(_non_empty_file(out_dir / fname) for fname in SUPERTONIC_REQUIRED_FILES):
        return False
    if (out_dir / "unicode_indexer.bin").stat().st_size % 4 != 0:
        return False
    if (out_dir / "voice.bin").stat().st_size < 32:
        return False
    try:
        _validate_json_file(out_dir / "tts.json")
    except Exception:
        return False
    return True


def extract_supertonic() -> None:
    if _supertonic_outputs_valid():
        _log("Supertonic-3 assets already extracted and verified.")
        return

    archive = download_with_retries(
        url=SUPERTONIC_URL,
        dest=CACHE_DIR / "supertonic-3.tar.bz2",
        validator=lambda p: validate_tar_bz2(p, SUPERTONIC_REQUIRED_FILES),
        label="Supertonic-3 int8",
    )

    out_dir = ASSETS_OUT / "supertonic"
    out_dir.mkdir(parents=True, exist_ok=True)
    _log(f"Extracting Supertonic-3 assets from {archive.name}...")
    allowed = set(SUPERTONIC_EXTRACT_FILES)
    with tarfile.open(archive, "r:bz2") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            base = pathlib.PurePosixPath(member.name).name
            if base in allowed:
                _write_member_atomic(tf, member, out_dir / base)

    if not _supertonic_outputs_valid():
        raise RuntimeError(f"Supertonic-3 extraction verification failed in {out_dir}")
    _log("Extracted and verified Supertonic-3 assets.")


def validate_all_prepared_assets() -> list[str]:
    missing: list[str] = []
    aar_path = LIBS_DIR / AAR_NAME
    try:
        validate_aar_zip(aar_path)
    except Exception as exc:
        missing.append(f"AAR ({exc})")

    if not _supertonic_outputs_valid():
        missing.append("supertonic")

    return missing


def run() -> int:
    _log(f"Checking offline Hindi TTS assets in {ROOT}")
    if STAMP_FILE.exists():
        problems = validate_all_prepared_assets()
        if not problems:
            _log("All Sherpa-ONNX AAR and offline Hindi TTS assets are present and verified.")
            return 0
        _err(f"Existing ready stamp invalid; missing/corrupt assets: {', '.join(problems)}")
        _remove_file_quietly(STAMP_FILE)

    # 1. Ensure Sherpa-ONNX static AAR is present and passes ZIP + native .so checks
    download_with_retries(
        url=AAR_URL,
        dest=LIBS_DIR / AAR_NAME,
        validator=validate_aar_zip,
        label="Sherpa-ONNX AAR",
    )

    # 2. Prepare the verified multilingual Supertonic-3 model only.
    ASSETS_OUT.mkdir(parents=True, exist_ok=True)
    # Remove generated assets from the previous catalog so unsupported languages and
    # unused models are not accidentally packaged into the APK.
    for stale_dir in (ASSETS_OUT / "piper", ASSETS_OUT / "kokoro", ASSETS_OUT / "espeak-ng-data"):
        if stale_dir.exists():
            shutil.rmtree(stale_dir)
    extract_supertonic()

    # 3. Verify every required asset before writing ready stamp
    problems = validate_all_prepared_assets()
    if problems:
        _remove_file_quietly(STAMP_FILE)
        raise RuntimeError(f"Asset verification failed after extraction: {', '.join(problems)}")

    manifest = {
        "engine": "Supertonic-3",
        "languages": ["hi", "en"],
        "sample_rate": 24000,
        "model_license": "OpenRAIL-M",
        "supertonic_speakers": {
            "F1": 0, "F2": 1, "F3": 2, "F4": 3, "F5": 4,
            "M1": 5, "M2": 6, "M3": 7, "M4": 8, "M5": 9,
        },
    }
    stamp_tmp = STAMP_FILE.with_name(f"{STAMP_FILE.name}.tmp")
    stamp_tmp.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    stamp_tmp.replace(STAMP_FILE)
    _log(f"Prepared and verified all offline Hindi TTS assets at {ASSETS_OUT}")
    return 0


def main() -> int:
    try:
        return run()
    except Exception as exc:
        _err(f"Fatal failure preparing offline Hindi TTS assets: {exc}")
        traceback.print_exc(file=sys.stderr)
        _remove_file_quietly(STAMP_FILE)
        return 1


if __name__ == "__main__":
    sys.exit(main())
