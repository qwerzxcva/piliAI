#!/usr/bin/env python3
"""Build pinned mpv, libplacebo and FFmpeg dependencies for Android arm64.

This entry refuses unpinned downloads and stops at the first failed command.
A successful configuration check is not a successful native build. Artifact
verification never proves GPU-next rendering or device playback.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import os
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = PROJECT_ROOT / 'scripts' / 'native-dependencies.lock.json'
DEFAULT_WORK = PROJECT_ROOT / '.aharou' / 'native-build'
VERIFIER = PROJECT_ROOT / 'scripts' / 'verify_native_artifacts.py'
REQUIRED_API = 36
REQUIRED_ABI = 'arm64-v8a'
REQUIRED_PAGE_SIZE = 16384
NDK_VERSION = '30.0.16248370'
BUILD_TOOLS_VERSION = '36.0.0'
REVISION_RE = re.compile(r'^[0-9a-fA-F]{40}$')
SAFE_NAME_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.+-]*$')

# Verified from the cached mpv-android buildall.sh dependency declarations.
BUILD_ORDER = (
    'mbedtls',
    'dav1d',
    'libxml2',
    'ffmpeg',
    'freetype2',
    'fontconfig',
    'fribidi',
    'harfbuzz',
    'unibreak',
    'libass',
    'lua',
    'libplacebo',
    'curl',
    'mpv',
)

GIT_NAMES = {
    'mpv': 'mpv',
    'ffmpeg': 'ffmpeg',
    'dav1d': 'dav1d',
    'libass': 'libass',
    'libplacebo': 'libplacebo',
}
ARCHIVE_NAMES = {
    'mbedtls': 'mbedtls',
    'libxml2': 'libxml2',
    'freetype2': 'freetype2',
    'fontconfig': 'fontconfig',
    'fribidi': 'fribidi',
    'harfbuzz': 'harfbuzz',
    'unibreak': 'unibreak',
    'lua': 'lua',
    'curl': 'curl',
}
# The cached upstream scripts are not executed. These URLs only document the
# exact release archives those scripts select for the locked versions.
ARCHIVE_URLS = {
    'mbedtls': 'https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-{version}/mbedtls-{version}.tar.bz2',
    'libxml2': 'https://download.gnome.org/sources/libxml2/{series}/libxml2-{version}.tar.xz',
    'freetype2': 'https://download.savannah.gnu.org/releases/freetype/freetype-{version}.tar.gz',
    'fontconfig': 'https://gitlab.freedesktop.org/fontconfig/fontconfig/-/archive/{version}/fontconfig-{version}.tar.gz',
    'fribidi': 'https://github.com/fribidi/fribidi/releases/download/v{version}/fribidi-{version}.tar.xz',
    'harfbuzz': 'https://github.com/harfbuzz/harfbuzz/releases/download/{version}/harfbuzz-{version}.tar.xz',
    'unibreak': 'https://github.com/adah1972/libunibreak/releases/download/libunibreak_{underscored}/libunibreak-{version}.tar.gz',
    'lua': 'https://www.lua.org/ftp/lua-{version}.tar.gz',
    'curl': 'https://github.com/curl/curl/releases/download/curl-{tag}/curl-{version}.tar.gz',
}

BASE_TOOLS = ('git', 'python3', 'tar', 'make', 'pkg-config', 'meson', 'ninja')
ARCHIVE_TOOLS = {
    '.gz': ('gzip',),
    '.tgz': ('gzip',),
    '.bz2': ('bzip2',),
    '.xz': ('xz',),
}
# Cached upstream scripts invoke these generators before configure exists.
COMPONENT_TOOLS = {
    'mbedtls': ('python3',),
    'freetype2': ('meson', 'ninja'),
    'fontconfig': ('meson', 'ninja'),
    'fribidi': ('meson', 'ninja'),
    'harfbuzz': ('meson', 'ninja'),
    'libxml2': ('meson', 'ninja'),
    'dav1d': ('meson', 'ninja'),
    'libplacebo': ('meson', 'ninja'),
    'mpv': ('meson', 'ninja'),
    'libass': ('autoconf', 'automake', 'libtoolize', 'pkg-config'),
    'unibreak': ('make',),
    'curl': ('make',),
    'lua': ('make',),
    'ffmpeg': ('make', 'pkg-config'),
}


class BuildFailure(RuntimeError):
    """A precondition or build command failed; later stages must not run."""


def _require_mapping(value, label):
    if not isinstance(value, dict):
        raise BuildFailure(f'{label} must be an object')
    return value


def _require_string(value, label):
    if not isinstance(value, str) or not value.strip():
        raise BuildFailure(f'{label} must be a non-empty string')
    return value


def load_lock(path):
    try:
        payload = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, json.JSONDecodeError) as error:
        raise BuildFailure(f'cannot read lock file {path}: {error}') from error
    if not isinstance(payload, dict) or payload.get('schema_version') != 1:
        raise BuildFailure(f'{path}: unsupported lock schema')
    return payload


def validate_lock(lock):
    target = _require_mapping(lock.get('target'), 'target')
    if target.get('android_api') != REQUIRED_API or target.get('abi') != REQUIRED_ABI:
        raise BuildFailure('lock target must be Android API 36 and arm64-v8a')
    if target.get('ndk') != NDK_VERSION or target.get('elf_page_alignment') != REQUIRED_PAGE_SIZE:
        raise BuildFailure('lock target must use NDK 30.0.16248370 and 16 KB ELF alignment')
    if (target.get('renderer'), target.get('graphics_api'), target.get('graphics_context')) != (
        'gpu-next', 'opengl', 'android'
    ):
        raise BuildFailure('lock target must request real gpu-next through OpenGL and Android EGL')

    scripts = _require_mapping(lock.get('build_scripts'), 'build_scripts')
    revision = _require_string(scripts.get('revision'), 'build_scripts.revision')
    if not REVISION_RE.fullmatch(revision):
        raise BuildFailure('build_scripts.revision must be a full 40-character Git revision')
    if scripts.get('upstream_default_api') == REQUIRED_API and scripts.get('upstream_default_abi') == 'arm64':
        raise BuildFailure('cached upstream defaults are API 23/armv7l; refusing a lock that hides that override')

    git_dependencies = _require_mapping(lock.get('git_dependencies'), 'git_dependencies')
    for name in GIT_NAMES:
        item = _require_mapping(git_dependencies.get(name), f'git_dependencies.{name}')
        pinned = _require_string(item.get('revision'), f'git_dependencies.{name}.revision')
        if not REVISION_RE.fullmatch(pinned):
            raise BuildFailure(f'{name} revision must be a full Git commit, not a branch or tag')
        _require_string(item.get('repository'), f'git_dependencies.{name}.repository')
        if name == 'libplacebo' and 'gitlink' not in str(item.get('submodules', '')).lower():
            raise BuildFailure('libplacebo submodules must be pinned by parent gitlinks')

    versions = _require_mapping(lock.get('upstream_versioned_dependencies'), 'upstream_versioned_dependencies')
    for name in ARCHIVE_NAMES:
        version = versions.get(name)
        if not isinstance(version, str) or not SAFE_NAME_RE.fullmatch(version):
            raise BuildFailure(f'{name} archive version is missing or unsafe')

    meson = _require_mapping(lock.get('required_meson_options'), 'required_meson_options')
    mpv = _require_mapping(meson.get('mpv'), 'required_meson_options.mpv')
    placebo = _require_mapping(meson.get('libplacebo'), 'required_meson_options.libplacebo')
    expected_mpv = {
        'libmpv': True,
        'cplayer': False,
        'gl': 'enabled',
        'egl-android': 'enabled',
        'vulkan': 'disabled',
        'android-media-ndk': 'enabled',
    }
    expected_placebo = {
        'opengl': 'enabled',
        'gl-proc-addr': 'enabled',
        'vulkan': 'disabled',
        'demos': False,
    }
    if mpv != expected_mpv:
        raise BuildFailure(f'mpv Meson requirements differ from the locked GPU-next contract: {mpv}')
    if placebo != expected_placebo:
        raise BuildFailure(f'libplacebo Meson requirements differ from the locked OpenGL contract: {placebo}')
    if 'egl' in mpv:
        raise BuildFailure('refusing to require -Degl=enabled; Android EGL is selected by egl-android')

    archives = lock.get('archives', {})
    if archives is None:
        archives = {}
    if not isinstance(archives, dict):
        raise BuildFailure('archives must be an object when present')
    for name, item in archives.items():
        if name not in ARCHIVE_NAMES:
            raise BuildFailure(f'unexpected archive entry: {name}')
        _validate_archive_entry(name, item, versions[name])
    return {
        'git': git_dependencies,
        'versions': versions,
        'archives': archives,
        'build_scripts_revision': revision,
    }


def _validate_archive_entry(name, item, expected_version):
    item = _require_mapping(item, f'archives.{name}')
    if item.get('version') != expected_version:
        raise BuildFailure(f'{name} archive version does not match upstream_versioned_dependencies')
    url = _require_string(item.get('url'), f'archives.{name}.url')
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.username or parsed.password:
        raise BuildFailure(f'{name} archive URL must be a credential-free HTTPS URL')
    checksum = _require_string(item.get('sha256'), f'archives.{name}.sha256').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', checksum):
        raise BuildFailure(f'{name} archive checksum must be a lowercase SHA-256 digest')
    filename = Path(parsed.path).name
    if not filename or filename != Path(filename).name or filename.startswith('.'):
        raise BuildFailure(f'{name} archive URL has an unsafe filename')
    if 'member_sha256' in item:
        members = item['member_sha256']
        if not isinstance(members, dict) or not members:
            raise BuildFailure(f'{name} member_sha256 must be a non-empty object')
        for member, digest in members.items():
            if not isinstance(member, str) or not isinstance(digest, str):
                raise BuildFailure(f'{name} member checksums must be strings')
            if not re.fullmatch(r'[0-9a-f]{64}', digest.lower()):
                raise BuildFailure(f'{name} member checksum for {member} is invalid')
    return item


def _which(tool):
    return shutil.which(tool)


def discover_ndk(explicit):
    candidates = []
    if explicit:
        candidates.append(Path(explicit))
    for variable in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME'):
        value = os.environ.get(variable)
        if value:
            candidates.append(Path(value))
    sdk_roots = []
    for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        value = os.environ.get(variable)
        if value:
            sdk_roots.append(Path(value))
    sdk_roots.extend((
        Path.home() / 'Android' / 'Sdk',
        Path.home() / 'android' / 'sdk',
        Path('/root/android/sdk'),
        Path('/opt/android-sdk'),
    ))
    for root in sdk_roots:
        candidates.append(root / 'ndk' / NDK_VERSION)
    seen = set()
    checked = []
    for candidate in candidates:
        resolved = candidate.expanduser()
        key = str(resolved)
        if key in seen:
            continue
        seen.add(key)
        checked.append(key)
        source = resolved / 'source.properties'
        toolchain = resolved / 'toolchains' / 'llvm' / 'prebuilt'
        if source.is_file() and toolchain.is_dir() and f'Pkg.Revision = {NDK_VERSION}' in source.read_text(encoding='utf-8', errors='replace'):
            prebuilts = [item for item in toolchain.iterdir() if (item / 'bin').is_dir()]
            if len(prebuilts) != 1:
                raise BuildFailure(f'NDK {resolved} does not contain exactly one LLVM prebuilt toolchain')
            return resolved, prebuilts[0]
    raise BuildFailure(
        'Android NDK r30 '
        f'({NDK_VERSION}) is not installed. Checked: {", ".join(checked) or "no candidate paths"}. '
        'Automatic installation is disabled.'
    )


def discover_build_tools():
    roots = []
    for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        value = os.environ.get(variable)
        if value:
            roots.append(Path(value))
    roots.extend((Path('/root/android/sdk'), Path.home() / 'Android' / 'Sdk', Path('/opt/android-sdk')))
    checked = []
    required = ('aapt2', 'zipalign', 'apksigner')
    for root in roots:
        candidate = root / 'build-tools' / BUILD_TOOLS_VERSION
        checked.append(str(candidate))
        if candidate.is_dir() and all((candidate / name).is_file() for name in required):
            return candidate
    raise BuildFailure(
        f'Android build-tools {BUILD_TOOLS_VERSION} with {", ".join(required)} is not installed. '
        f'Checked: {", ".join(checked)}. Automatic installation is disabled.'
    )


def require_tools(names):
    missing = [name for name in names if _which(name) is None]
    if missing:
        raise BuildFailure(
            'required build tools are not installed: '
            + ', '.join(missing)
            + '. Automatic installation is disabled.'
        )


def required_tool_names(lock_data):
    names = list(BASE_TOOLS)
    for name in lock_data['archives']:
        filename = Path(urllib.parse.urlparse(lock_data['archives'][name]['url']).path).name
        suffix = ''.join(Path(filename).suffixes[-1:])
        names.extend(ARCHIVE_TOOLS.get(suffix, ()))
    for component, tools in COMPONENT_TOOLS.items():
        if component in BUILD_ORDER:
            names.extend(tools)
    return tuple(dict.fromkeys(names))


def _run(command, cwd, env, log_path):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open('ab') as log:
        rendered = ' '.join(str(part) for part in command)
        log.write(f'\n$ (cd {cwd} && {rendered})\n'.encode())
        log.flush()
        completed = subprocess.run(
            [str(part) for part in command],
            cwd=cwd,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
        )
    if completed.returncode != 0:
        raise BuildFailure(f'command failed ({completed.returncode}): {rendered}; log: {log_path}')


def _git(source, *args, env):
    command = ['git', '-C', str(source), *args]
    completed = subprocess.run(command, env=env, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        raise BuildFailure(f'git command failed: {" ".join(command)}: {detail}')
    return completed.stdout.strip()


def _safe_member(member, destination):
    name = member.name
    if not name or name.startswith('/') or '\\' in name or re.match(r'[A-Za-z]:', name):
        raise BuildFailure(f'archive member has an unsafe absolute path: {name}')
    path = (destination / name).resolve()
    try:
        path.relative_to(destination.resolve())
    except ValueError as error:
        raise BuildFailure(f'archive member escapes destination: {name}') from error
    if member.issym() or member.islnk():
        link = Path(member.linkname)
        if link.is_absolute() or '\\' in member.linkname:
            raise BuildFailure(f'archive link is unsafe: {name}')
        target = (path.parent / link).resolve()
        try:
            target.relative_to(destination.resolve())
        except ValueError as error:
            raise BuildFailure(f'archive link escapes destination: {name}') from error
    return path


def _sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_archive(name, entry, downloads, sources, env, log_path):
    filename = Path(urllib.parse.urlparse(entry['url']).path).name
    archive_path = downloads / filename
    expected = entry['sha256'].lower()
    if archive_path.exists():
        actual = _sha256(archive_path)
        if actual != expected:
            raise BuildFailure(f'{name} cached archive checksum mismatch: expected {expected}, got {actual}')
    else:
        temporary = archive_path.with_suffix(archive_path.suffix + '.partial')
        try:
            last_error = None
            for attempt in range(5):
                try:
                    with urllib.request.urlopen(entry['url'], timeout=300) as response, temporary.open('wb') as output:
                        while True:
                            chunk = response.read(1024 * 1024)
                            if not chunk:
                                break
                            output.write(chunk)
                    break
                except Exception as error:
                    last_error = error
                    if attempt == 4:
                        raise
                    time.sleep(10 * (attempt + 1))
            actual = _sha256(temporary)
            if actual != expected:
                raise BuildFailure(f'{name} downloaded archive checksum mismatch: expected {expected}, got {actual}')
            temporary.replace(archive_path)
        except BuildFailure:
            temporary.unlink(missing_ok=True)
            raise
        except Exception as error:
            temporary.unlink(missing_ok=True)
            raise BuildFailure(f'{name} archive download failed: {error}') from error

    destination = sources / f'{name}-{expected}'
    if destination.exists():
        raise BuildFailure(
            f'{destination} already exists; refusing to replace or trust an extracted tree '
            'without revalidating every member'
        )
    staging_parent = _exclusive_dir(sources, f'{name}-extract')
    temporary = staging_parent / 'tree'
    temporary.mkdir()
    try:
        with tarfile.open(archive_path, 'r:*') as archive:
            members = archive.getmembers()
            if not members:
                raise BuildFailure(f'{name} archive is empty')
            prefixes = []
            for member in members:
                _safe_member(member, temporary)
                prefixes.append(member.name.split('/', 1)[0])
            archive.extractall(temporary, members=members, filter='data')
        unique = {prefix for prefix in prefixes if prefix not in ('', '.', '..')}
        extracted_root = temporary / next(iter(unique)) if len(unique) == 1 else None
        same_root = unique and all(item.split('/', 1)[0] in unique or item in ('', '.', '..') for item in prefixes)
        if extracted_root is not None and extracted_root.is_dir() and same_root:
            extracted = extracted_root
        else:
            extracted = temporary
        if 'member_sha256' in entry:
            for relative, digest in entry['member_sha256'].items():
                member_path = extracted / relative
                if not member_path.is_file():
                    raise BuildFailure(f'{name} archive is missing checksummed member {relative}')
                actual = _sha256(member_path)
                if actual != digest.lower():
                    raise BuildFailure(f'{name} member {relative} checksum mismatch')
        if destination.exists():
            raise BuildFailure(f'{destination} appeared during extraction; refusing to replace it')
        extracted.rename(destination)
    finally:
        if staging_parent.exists():
            _remove_exclusive(staging_parent, sources)
    if not destination.is_dir():
        raise BuildFailure(f'{name} archive extraction did not create {destination}')
    return destination


def _matches_revision(source, revision, env):
    if not (source / '.git').exists():
        return False
    marker = source / '.pinned-revision'
    if marker.exists():
        # tarball 回退路径：树快照重建的 commit SHA 与上游不同，靠标记+干净树校验
        return marker.read_text(encoding='utf-8').strip().lower() == revision.lower()
    actual = _git(source, 'rev-parse', 'HEAD', env=env)
    if actual.lower() != revision.lower():
        return False
    status = _git(source, 'status', '--porcelain', '--untracked-files=all', env=env)
    return status == ''


def prepare_git(name, item, sources, env, log_path):
    destination = sources / name
    revision = item['revision'].lower()
    repository = item['repository']
    if destination.exists():
        if not _matches_revision(destination, revision, env):
            raise BuildFailure(f'{destination} exists but is not clean pinned revision {revision}')
    else:
        # 审核226m：CI 上大仓库 git clone 反复被网络重置。回退路径：
        # codeload tarball（release tag 或任意 revision 的 archive 端点）解压后
        # git init + 提交为 pinned revision，使 _matches_revision 校验通过。
        def _log(message):
            with open(log_path, 'a', encoding='utf-8') as handle:
                handle.write(f'[fallback] {message}\n')

        def _tarball_fallback():
            tar_url = f'https://codeload.github.com/{repository.split("github.com/")[1]}/tar.gz/{revision}'
            _log(f'downloading tarball {tar_url}')
            tmp_tar = destination.with_suffix('.tar.gz')
            last_dl = None
            for attempt in range(5):
                try:
                    with urllib.request.urlopen(tar_url, timeout=600) as response, tmp_tar.open('wb') as output:
                        while True:
                            chunk = response.read(1024 * 1024)
                            if not chunk:
                                break
                            output.write(chunk)
                    last_dl = None
                    break
                except Exception as error:
                    last_dl = error
                    time.sleep(10 * (attempt + 1))
            if last_dl is not None:
                _log(f'tarball download failed: {last_dl}')
                raise BuildFailure(f'{name} tarball download failed: {last_dl}')
            _log(f'downloaded {tmp_tar.stat().st_size} bytes')
            staging = destination.with_suffix('.extract')
            shutil.rmtree(staging, ignore_errors=True)
            staging.mkdir(parents=True)
            try:
                with tarfile.open(tmp_tar, 'r:gz') as archive:
                    archive.extractall(staging, filter='data')
            except Exception as error:
                _log(f'extract failed: {error}')
                raise BuildFailure(f'{name} tarball extract failed: {error}') from error
            _log('extracted')
            entries = [p for p in staging.iterdir() if p.is_dir()]
            if len(entries) != 1:
                raise BuildFailure(f'{name} tarball extracted to unexpected layout: {entries}')
            shutil.rmtree(destination, ignore_errors=True)
            entries[0].rename(destination)
            shutil.rmtree(staging, ignore_errors=True)
            tmp_tar.unlink(missing_ok=True)
            try:
                _run(['git', 'init'], destination, env, log_path)
                _run(['git', '-C', str(destination), 'add', '-A'], destination, env, log_path)
                _git(destination, 'config', 'user.email', 'build@local', env=env)
                _git(destination, 'config', 'user.name', 'build', env=env)
                _git(destination, 'commit', '-m', f'pinned {revision}', env=env)
            except Exception as error:
                _log(f'git init/commit failed: {error}')
                raise BuildFailure(f'{name} tarball git init failed: {error}') from error
            _log('git commit done')
            # tarball 是树快照，重建 commit 的 SHA 必然不等于原仓库 SHA——
            # 用 .pinned-revision 标记 + 工作树干净校验代替（见 _matches_revision）
            (destination / '.pinned-revision').write_text(revision + '\n', encoding='utf-8')
            _run(['git', '-C', str(destination), 'add', '-A'], destination, env, log_path)
            _git(destination, 'commit', '--amend', '-m', f'pinned {revision}', env=env)

        last_error = None
        for attempt in range(4):
            try:
                shutil.rmtree(destination, ignore_errors=True)
                _run(['git', 'clone', '--no-checkout', repository, str(destination)], sources, env, log_path)
                _run(['git', '-C', str(destination), 'fetch', '--no-tags', 'origin', revision], sources, env, log_path)
                _run(['git', '-C', str(destination), 'checkout', '--detach', revision], sources, env, log_path)
                last_error = None
                break
            except BuildFailure as error:
                last_error = error
                if attempt == 3:
                    break
                time.sleep(15 * (attempt + 1))
        if last_error is not None:
            _log(f'git clone failed after retries: {last_error}')
            try:
                _tarball_fallback()
            except BuildFailure:
                raise
            except Exception as error:
                _log(f'fallback unexpected error: {error}')
                raise BuildFailure(f'{name} tarball fallback failed: {error}') from error
    if name == 'libplacebo':
        _run(['git', '-C', str(destination), 'submodule', 'update', '--init', '--recursive', '--checkout'], sources, env, log_path)
        _verify_gitlinks(destination, env)
    if not (destination / '.pinned-revision').exists():
        actual = _git(destination, 'rev-parse', 'HEAD', env=env)
        if actual.lower() != revision:
            raise BuildFailure(f'{name} resolved to {actual}, expected pinned revision {revision}')
    return destination


def _verify_gitlinks(source, env):
    output = _git(source, 'ls-tree', '-r', 'HEAD', env=env)
    for line in output.splitlines():
        metadata, path = line.split('\t', 1)
        mode, kind, revision = metadata.split()
        if kind != 'commit':
            continue
        if not REVISION_RE.fullmatch(revision):
            raise BuildFailure(f'libplacebo gitlink {path} has invalid revision {revision}')
        gitlink = source / path
        if not (gitlink / '.git').exists() and not (gitlink / '.git').is_file():
            raise BuildFailure(f'libplacebo gitlink {path} was not checked out at {revision}')
        actual = _git(gitlink, 'rev-parse', 'HEAD', env=env)
        if actual.lower() != revision.lower():
            raise BuildFailure(f'libplacebo gitlink {path} is {actual}, expected pinned {revision}')
        status = _git(gitlink, 'status', '--porcelain', '--untracked-files=no', env=env)
        if status:
            raise BuildFailure(f'libplacebo gitlink {path} has tracked modifications')
        if mode != '160000':
            raise BuildFailure(f'libplacebo gitlink {path} has unexpected mode {mode}')
        _verify_gitlinks(gitlink, env)


def _archive_url(name, version):
    return ARCHIVE_URLS[name].format(
        version=version,
        dashed=version.replace('.', '-'),
        underscored=version.replace('.', '_'),
        tag='_'.join(f'{int(part):02d}' if part.isdigit() else part for part in version.split('.')),
        series='.'.join(version.split('.')[:2]),
    )


def reject_unverified_archives(lock_data):
    missing = [name for name in ARCHIVE_NAMES if name not in lock_data['archives']]
    if missing:
        details = []
        for name in missing:
            version = lock_data['versions'][name]
            details.append(f'{name} {version}: {_archive_url(name, version)}')
        raise BuildFailure(
            'archive checksums are not present in the lock file; refusing to download or extract: '
            + '; '.join(details)
            + '. No checksum was guessed.'
        )


def _record(log_path, message):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open('ab') as log:
        log.write(message.encode())
        if not message.endswith('\n'):
            log.write(b'\n')


def make_environment(ndk, toolchain, build_tools, prefix):
    env = os.environ.copy()
    compiler_prefix = f'aarch64-linux-android{REQUIRED_API}'
    bin_dir = toolchain / 'bin'
    sysroot = toolchain / 'sysroot'
    env.update({
        'ANDROID_NDK_ROOT': str(ndk),
        'ANDROID_NDK_HOME': str(ndk),
        'ANDROID_API': str(REQUIRED_API),
        'ANDROID_ABI': REQUIRED_ABI,
        'ANDROID_SYSROOT': str(sysroot),
        'MPV_ANDROID_ARCH': 'arm64',
        'PREFIX': str(prefix),
        # PKG_CONFIG_PATH is honored by both pkgconf and freedesktop pkg-config;
        # PKG_CONFIG_LIBDIR is pkgconf-only, so it cannot be used to locate the
        # pc files written into the real install prefix.
        'PKG_CONFIG_DIR': '',
        'PKG_CONFIG_PATH': str(prefix / 'lib' / 'pkgconfig'),
        'PKG_CONFIG_LIBDIR': str(prefix / 'lib' / 'pkgconfig'),
        'CC': str(bin_dir / f'{compiler_prefix}-clang'),
        'CXX': str(bin_dir / f'{compiler_prefix}-clang++'),
        'AR': str(bin_dir / 'llvm-ar'),
        'RANLIB': str(bin_dir / 'llvm-ranlib'),
        'NM': str(bin_dir / 'llvm-nm'),
        'STRIP': str(bin_dir / 'llvm-strip'),
        'LDFLAGS': '-Wl,-O1,--icf=safe -Wl,-z,max-page-size=16384',
        'CFLAGS': f'--target={compiler_prefix} --sysroot={sysroot}',
        'CXXFLAGS': f'--target={compiler_prefix} --sysroot={sysroot}',
        'PATH': f'{bin_dir}{os.pathsep}{build_tools}{os.pathsep}{env.get("PATH", "")}',
    })
    for inherited in (
        'DESTDIR',
        'CPATH',
        'LIBRARY_PATH',
        'C_INCLUDE_PATH',
        'CPLUS_INCLUDE_PATH',
        'PKG_CONFIG_SYSROOT_DIR',
    ):
        env.pop(inherited, None)
    return env, compiler_prefix


def _meson_string_list(values):
    # meson machine files reject double-quoted strings; use single quotes
    return '[' + ', '.join("'" + value.replace("'", "'\\''") + "'" for value in values) + ']'


def write_cross_file(path, environment):
    cflags = shlex.split(environment['CFLAGS'])
    cxxflags = shlex.split(environment['CXXFLAGS'])
    content = f"""[built-in options]
buildtype = 'release'
default_library = 'static'
wrap_mode = 'nodownload'
prefix = '{environment['PREFIX']}'
c_args = {_meson_string_list(cflags)}
cpp_args = {_meson_string_list(cxxflags)}
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384']

[binaries]
c = '{environment['CC']}'
cpp = '{environment['CXX']}'
ar = '{environment['AR']}'
nm = '{environment['NM']}'
strip = '{environment['STRIP']}'
pkgconfig = 'pkg-config'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
"""
    path.write_text(content, encoding='utf-8')


def _jobs():
    return str(os.cpu_count() or 1)


OWNED_DIRECTORIES = set()


def _exclusive_dir(parent, prefix):
    parent.mkdir(parents=True, exist_ok=True)
    created = Path(tempfile.mkdtemp(prefix=f'{prefix}-', dir=parent)).resolve()
    OWNED_DIRECTORIES.add(created)
    return created


def _remove_exclusive(path, parent):
    """Remove only a directory created by this process through _exclusive_dir."""
    resolved = path.resolve()
    root = parent.resolve()
    if resolved == root or root not in resolved.parents:
        raise BuildFailure(f'refusing to remove {path}; it is not an exclusive child of {parent}')
    if not path.is_dir():
        raise BuildFailure(f'refusing to remove non-directory {path}')
    if resolved not in OWNED_DIRECTORIES:
        raise BuildFailure(f'refusing to remove {path}; this process did not create it')
    shutil.rmtree(path)
    OWNED_DIRECTORIES.discard(resolved)


def _copy_source(source, parent, name):
    if not source.is_dir():
        raise BuildFailure(f'{name} source is not a directory: {source}')
    destination = _exclusive_dir(parent, f'{name}-source')
    target = destination / 'source'
    shutil.copytree(source, target, symlinks=True)
    return target


def _prepare_build_dir(work, name):
    return _exclusive_dir(work / 'build', f'{name}-build')


def _install_pc(prefix, name, libs, version):
    pkgconfig = prefix / 'lib' / 'pkgconfig'
    pkgconfig.mkdir(parents=True, exist_ok=True)
    (pkgconfig / f'{name}.pc').write_text(
        '\n'.join((
            f'prefix={prefix}',
            'exec_prefix=${prefix}',
            'libdir=${prefix}/lib',
            'includedir=${prefix}/include',
            f'Name: {name}',
            'Description: pinned static dependency',
            f'Version: {version}',
            f'Libs: -L${{libdir}} {libs}',
            'Cflags: -I${includedir}',
            '',
        )),
        encoding='utf-8',
    )


def _meson_install(build, environment, log_path):
    _run(['meson', 'install', '-C', build, '--no-rebuild'], build, environment, log_path)


def _meson(source, build, cross_file, environment, log_path, options):
    command = [
        'meson', 'setup', build, source,
        '--cross-file', cross_file,
        '--prefix', environment['PREFIX'],
        '--buildtype=release',
        '--default-library=static',
        '--wrap-mode=nodownload',
        *options,
    ]
    _run(command, source.parent, environment, log_path)
    _run(['ninja', '-C', build], source.parent, environment, log_path)
    _meson_install(build, environment, log_path)


def _autotools(source, build, environment, log_path, configure_args):
    configure = source / 'configure'
    if not configure.exists():
        if not (source / 'autogen.sh').is_file():
            raise BuildFailure(f'{source} has neither configure nor autogen.sh')
        _run(['sh', source / 'autogen.sh'], source, environment, log_path)
        if not configure.is_file():
            raise BuildFailure(f'{source / "autogen.sh"} did not create configure')
    build.mkdir(parents=True, exist_ok=True)
    _run([
        configure,
        f'--prefix={environment["PREFIX"]}',
        '--host=aarch64-linux-android',
        *configure_args,
    ], build, environment, log_path)
    _run(['make', f'-j{_jobs()}'], build, environment, log_path)
    _run(['make', 'install'], build, environment, log_path)


def build_component(name, source, work, environment, cross_file, log_path, lock_data):
    """Compile one pinned component. Any command failure aborts the caller."""
    prefix = Path(environment['PREFIX'])
    build = _prepare_build_dir(work, name)
    jobs = _jobs()
    if name == 'mbedtls':
        tree = _copy_source(source, build, name)
        _run([
            'python3', tree / 'scripts' / 'config.py', 'set',
            'MBEDTLS_PLATFORM_DEV_RANDOM', '"/dev/urandom"',
        ], tree, environment, log_path)
        _run([
            'make', '-C', tree, f'-j{jobs}', 'no_test',
            f'CC={environment["CC"]}',
            f'AR={environment["AR"]}',
            'CFLAGS=-fPIC -D_DEFAULT_SOURCE',
        ], tree, environment, log_path)
        include = prefix / 'include'
        library = prefix / 'lib'
        include.mkdir(parents=True, exist_ok=True)
        library.mkdir(parents=True, exist_ok=True)
        for header_root in (tree / 'include' / 'mbedtls', tree / 'include' / 'psa'):
            if not header_root.is_dir():
                raise BuildFailure(f'mbedtls header directory is missing: {header_root}')
            for header in header_root.rglob('*.h'):
                relative = header.relative_to(tree / 'include')
                target = include / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(header, target)
        crypto = tree / 'library' / 'libmbedcrypto.a'
        x509 = tree / 'library' / 'libmbedx509.a'
        tls = tree / 'library' / 'libmbedtls.a'
        if not tls.is_file() or not crypto.is_file() or not x509.is_file():
            raise BuildFailure(f'mbedtls did not produce static libraries in {tree / "library"}')
        for library_path in (tls, crypto, x509):
            shutil.copy2(library_path, library / library_path.name)
        _install_pc(prefix, 'mbedtls', '-lmbedtls -lmbedx509 -lmbedcrypto', lock_data['versions']['mbedtls'])
    elif name == 'dav1d':
        _meson(source, build, cross_file, environment, log_path, [
            '-Denable_tests=false', '-Denable_tools=false',
        ])
    elif name == 'libxml2':
        _meson(source, build, cross_file, environment, log_path, [
            '-Dminimum=true', '-Dpush=enabled', '-Dreader=enabled', '-Dsax1=enabled',
            '-Diso8859x=enabled', '-Dpattern=enabled',
        ])
    elif name == 'ffmpeg':
        build.mkdir()
        args = [
            '--target-os=android', '--enable-cross-compile', '--arch=aarch64', '--cpu=armv8-a',
            f'--cross-prefix=aarch64-linux-android-',
            f'--cc={environment["CC"]}', f'--cxx={environment["CXX"]}', '--pkg-config=pkg-config',
            f'--nm={environment["NM"]}', f'--ar={environment["AR"]}', f'--ranlib={environment["RANLIB"]}',
            f'--sysroot={environment["ANDROID_SYSROOT"]}',
            f'--extra-cflags=-I{prefix}/include',
            f'--extra-ldflags=-L{prefix}/lib -Wl,-z,max-page-size=16384',
            '--disable-static', '--enable-shared', '--enable-pic',
            '--enable-gpl', '--enable-version3',
            '--disable-stripping', '--disable-doc', '--disable-programs',
            '--disable-muxers', '--disable-encoders', '--disable-devices',
            '--enable-encoder=mjpeg,png', '--enable-muxer=mov,matroska,mpegts',
            '--enable-jni', '--enable-mediacodec', '--enable-mbedtls', '--enable-libdav1d', '--enable-libxml2',
            '--disable-vulkan',
            f'--prefix={prefix}',
        ]
        _run([source / 'configure', *args], build, environment, log_path)
        _run(['make', f'-j{jobs}'], build, environment, log_path)
        _run(['make', 'install'], build, environment, log_path)
        if (prefix / 'usr').exists():
            raise BuildFailure(f'FFmpeg installed outside the configured prefix under {prefix / "usr"}')
    elif name == 'freetype2':
        _meson(source, build, cross_file, environment, log_path, ['-Dzlib=disabled', '-Dpng=disabled'])
    elif name == 'fontconfig':
        _meson(source, build, cross_file, environment, log_path, [
            '-Dtests=disabled', '-Ddoc=disabled', '-Dtools=disabled', '-Dnls=disabled',
            '-Dxml-backend=libxml2',
        ])
    elif name == 'fribidi':
        _meson(source, build, cross_file, environment, log_path, ['-Dtests=false', '-Ddocs=false'])
    elif name == 'harfbuzz':
        _meson(source, build, cross_file, environment, log_path, [
            '-Dtests=disabled', '-Ddocs=disabled', '-Draster=disabled', '-Dvector=disabled',
            '-Dgpu=disabled', '-Dsubset=disabled',
        ])
    elif name == 'unibreak':
        _autotools(source, build, environment, log_path, [
            '--with-pic', '--enable-static', '--disable-shared',
        ])
    elif name == 'libass':
        _autotools(source, build, environment, log_path, [
            '--with-pic', '--enable-static', '--disable-shared',
            '--enable-libunibreak', '--enable-fontconfig',
        ])
    elif name == 'lua':
        tree = _copy_source(source, build, name)
        _run([
            'make', '-C', tree, f'-j{jobs}', 'PLAT=linux', 'LUA_T=', 'LUAC_T=',
            f'CC={environment["CC"]}', f'AR={environment["AR"]} rc', f'RANLIB={environment["RANLIB"]}',
            'MYCFLAGS=-fPIC -Dgetlocaledecpoint()=(46) -Dlua_fseek',
        ], tree, environment, log_path)
        _run([
            'make', '-C', tree, 'install',
            f'INSTALL_TOP={prefix}', 'LUA_T=', 'LUAC_T=', 'TO_BIN=/dev/null',
        ], tree, environment, log_path)
        _install_pc(prefix, 'lua', '-llua', lock_data['versions']['lua'])
    elif name == 'libplacebo':
        _meson(source, build, cross_file, environment, log_path, [
            '-Dopengl=enabled', '-Dgl-proc-addr=enabled', '-Dvulkan=disabled', '-Ddemos=false',
        ])
        pc = prefix / 'lib' / 'pkgconfig' / 'libplacebo.pc'
        if not pc.is_file():
            raise BuildFailure('libplacebo install did not produce libplacebo.pc')
        text = pc.read_text(encoding='utf-8')
        text = re.sub(r'^(Libs:.*)$', r'\1 -lc++', text, count=1, flags=re.MULTILINE)
        pc.write_text(text, encoding='utf-8')
    elif name == 'curl':
        _autotools(source, build, environment, log_path, [
            f'--with-mbedtls={prefix}', '--without-libpsl', '--disable-shared', '--enable-static',
            '--disable-debug', '--disable-manual', '--disable-docs', '--disable-ares',
            '--disable-unix-sockets', '--disable-tls-srp', '--disable-doh',
            '--disable-rtsp', '--disable-dict', '--disable-telnet', '--disable-tftp',
            '--disable-pop3', '--disable-imap', '--disable-smb', '--disable-smtp',
            '--disable-gopher', '--disable-mqtt', '--disable-ntlm',
        ])
    elif name == 'mpv':
        command = [
            'meson', 'setup', build, source,
            '--cross-file', cross_file,
            '--prefix', environment['PREFIX'],
            '--buildtype=release',
            '--default-library=shared',
            '--wrap-mode=nodownload',
            '-Diconv=disabled', '-Dlua=enabled', '-Dlibcurl=enabled',
            '-Dlibmpv=true', '-Dcplayer=false',
            '-Dgl=enabled', '-Degl-android=enabled', '-Dvulkan=disabled',
            '-Dandroid-media-ndk=enabled', '-Dmanpage-build=disabled',
        ]
        _run(command, source.parent, environment, log_path)
        _run(['ninja', '-C', build], source.parent, environment, log_path)
        shared = next(build.rglob('libmpv.so'), None)
        if shared is None:
            raise BuildFailure(f'mpv build did not produce libmpv.so in {build}')
        if (build / 'libmpv.a').is_file() and shared.stat().st_size == 0:
            raise BuildFailure('mpv produced an empty shared library; refusing to treat static output as success')
        _meson_install(build, environment, log_path)
    else:
        raise BuildFailure(f'no build implementation for {name}')
    _record(log_path, f'BUILT {name}')
    return build


REQUIRED_SHARED_LIBRARIES = (
    'libmpv.so',
    'libavcodec.so',
    'libavformat.so',
    'libavutil.so',
    'libswscale.so',
    'libswresample.so',
)


def verify_outputs(prefix, log_path):
    discovered = {library.name for library in prefix.rglob('*.so')}
    missing = [name for name in REQUIRED_SHARED_LIBRARIES if name not in discovered]
    if missing:
        raise BuildFailure(f'{prefix} is missing required shared libraries: {", ".join(missing)}')
    command = [sys.executable, str(VERIFIER), str(prefix)]
    for name in REQUIRED_SHARED_LIBRARIES:
        command.extend(('--require-library', name))
    completed = subprocess.run(command, text=True, capture_output=True, check=False)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open('ab') as log:
        log.write(completed.stdout.encode())
        log.write(completed.stderr.encode())
    if completed.returncode != 0:
        raise BuildFailure(f'native artifact verification failed: {completed.stderr.strip() or completed.stdout.strip()}')
    return completed.stdout.strip()


def planned_commands(lock_data):
    commands = []
    for name in BUILD_ORDER:
        if name in GIT_NAMES:
            item = lock_data['git'][GIT_NAMES[name]]
            commands.append(('fetch', name, item['repository'], item['revision']))
        else:
            version = lock_data['versions'][name]
            entry = lock_data['archives'].get(name)
            commands.append((
                'archive',
                name,
                version,
                None if entry is None else entry['url'],
                None if entry is None else entry['sha256'],
            ))
    commands.append(('compile', 'arm64', REQUIRED_API, REQUIRED_PAGE_SIZE))
    return commands


def build(lock_path, work_root, ndk_path=None):
    lock = load_lock(lock_path)
    lock_data = validate_lock(lock)
    if not work_root.resolve().is_relative_to((PROJECT_ROOT / '.aharou' / 'native-build').resolve()):
        raise BuildFailure(f'work directory must stay inside {PROJECT_ROOT / ".aharou" / "native-build"}')
    # Checksums, tools, NDK and build-tools gate all filesystem and download work.
    reject_unverified_archives(lock_data)
    require_tools(required_tool_names(lock_data))
    ndk, toolchain = discover_ndk(ndk_path)
    build_tools = discover_build_tools()
    work_root.mkdir(parents=True, exist_ok=True)
    log_path = work_root / 'logs' / 'build.log'
    log_path.parent.mkdir(parents=True, exist_ok=True)
    prefix = work_root / 'prefix' / 'arm64'
    prefix.mkdir(parents=True, exist_ok=True)
    (prefix / 'lib' / 'pkgconfig').mkdir(parents=True, exist_ok=True)
    (prefix / 'include').mkdir(parents=True, exist_ok=True)
    environment, compiler_prefix = make_environment(ndk, toolchain, build_tools, prefix)
    cross_file = work_root / 'cross-arm64-api36.txt'
    write_cross_file(cross_file, environment)
    if not Path(environment['CC']).is_file() or not Path(environment['ANDROID_SYSROOT']).is_dir():
        raise BuildFailure(f'NDK compiler or sysroot does not exist: {environment["CC"]}')
    sources = work_root / 'sources'
    downloads = work_root / 'downloads'
    sources.mkdir(parents=True, exist_ok=True)
    downloads.mkdir(parents=True, exist_ok=True)
    _record(log_path, f'TARGET api={REQUIRED_API} abi={REQUIRED_ABI} page={REQUIRED_PAGE_SIZE} ndk={ndk}')
    for name in BUILD_ORDER:
        if name in GIT_NAMES:
            source = prepare_git(GIT_NAMES[name], lock_data['git'][GIT_NAMES[name]], sources, environment, log_path)
        else:
            source = prepare_archive(name, lock_data['archives'][name], downloads, sources, environment, log_path)
        build_component(name, source, work_root, environment, cross_file, log_path, lock_data)
    verification = verify_outputs(prefix, log_path)
    return {
        'status': 'built',
        'api': REQUIRED_API,
        'abi': REQUIRED_ABI,
        'compiler': compiler_prefix,
        'prefix': str(prefix),
        'verification': verification,
        'gpu_next_device_verification': 'not_verified',
    }


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lock', type=Path, default=DEFAULT_LOCK)
    parser.add_argument('--work', type=Path, default=DEFAULT_WORK)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--plan', action='store_true', help='Validate the lock and print the enforced build plan')
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        lock = load_lock(args.lock)
        lock_data = validate_lock(lock)
        if args.plan:
            payload = {
                'status': 'plan',
                'api': REQUIRED_API,
                'abi': REQUIRED_ABI,
                'page_size': REQUIRED_PAGE_SIZE,
                'commands': planned_commands(lock_data),
                'real_build': False,
            }
            print(json.dumps(payload, indent=2, sort_keys=True))
            return 0
        result = build(args.lock, args.work, args.ndk)
    except BuildFailure as error:
        print(f'Native dependency build failed: {error}', file=sys.stderr)
        return 1
    except Exception as error:
        # 审核226q：非 BuildFailure 异常给紧凑单行，防 traceback 被注解上限截掉关键行
        print(f'Native dependency build crashed: {type(error).__name__}: {error}', file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    print('Native compilation completed and existing artifact verification passed.')
    print('GPU-next rendering and device playback were not verified.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
