import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts' / 'build_native_dependencies.py'
LOCK = ROOT / 'scripts' / 'native-dependencies.lock.json'


def load_builder():
    spec = importlib.util.spec_from_file_location('native_dependency_builder', SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json(path, payload):
    path.write_text(json.dumps(payload), encoding='utf-8')


class NativeDependencyBuildTests(unittest.TestCase):
    def setUp(self):
        self.builder = load_builder()

    def lock(self):
        return json.loads(LOCK.read_text(encoding='utf-8'))

    def test_real_lock_exposes_api36_arm64_plan_without_claiming_build(self):
        completed = subprocess.run(
            ['python3', str(SCRIPT), '--plan', '--lock', str(LOCK)],
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        payload = json.loads(completed.stdout)
        self.assertEqual(payload['status'], 'plan')
        self.assertEqual(payload['api'], 36)
        self.assertEqual(payload['abi'], 'arm64-v8a')
        self.assertEqual(payload['page_size'], 16384)
        self.assertFalse(payload['real_build'])
        self.assertEqual([item[1] for item in payload['commands'][:-1]], list(self.builder.BUILD_ORDER))

    def test_lock_contract_and_cached_dependency_order(self):
        data = self.builder.validate_lock(self.lock())
        self.assertEqual(self.builder.BUILD_ORDER[0], 'mbedtls')
        self.assertEqual(self.builder.BUILD_ORDER[3], 'ffmpeg')
        self.assertLess(self.builder.BUILD_ORDER.index('ffmpeg'), self.builder.BUILD_ORDER.index('libass'))
        self.assertLess(self.builder.BUILD_ORDER.index('libplacebo'), self.builder.BUILD_ORDER.index('mpv'))
        self.assertEqual(data['git']['mpv']['revision'], 'b2c255c13e8e37952dbac6c34da56a690560378b')
        self.assertNotIn('egl', data['git'])

    def test_missing_archive_checksum_stops_before_download(self):
        payload = self.lock()
        payload['archives'].pop('fontconfig')
        with self.assertRaises(self.builder.BuildFailure) as caught:
            self.builder.reject_unverified_archives(self.builder.validate_lock(payload))
        self.assertIn('refusing to download or extract', str(caught.exception))
        message = str(caught.exception)
        self.assertIn('fontconfig', message)
        self.assertNotIn('unibreak 8.0', message)
        for trusted in ('libxml2-2.15.4.tar.xz', 'mbedtls-3.6.7', 'freetype-2.14.3.tar.gz', 'fribidi-1.0.17', 'harfbuzz-14.5.0', 'libunibreak-8.0', 'lua-5.2.4', 'curl-8.22.0'):
            self.assertNotIn(trusted, message)
        self.assertNotIn('4f7b554a38cdf78c033f666c8871f3749e14a094f65a07f630c91ed0b43d35e3', message)
        self.assertIn('No checksum was guessed', str(caught.exception))

    def test_checksum_and_version_mismatch_are_rejected(self):
        payload = self.lock()
        payload['archives'] = {
            'curl': {
                'version': '8.22.0',
                'url': 'https://curl.se/download/curl-8.22.0.tar.gz',
                'sha256': 'a' * 64,
            }
        }
        with self.assertRaises(self.builder.BuildFailure):
            self.builder.reject_unverified_archives(self.builder.validate_lock(payload))
        payload['archives']['curl']['version'] = '9.0.0'
        with self.assertRaises(self.builder.BuildFailure):
            self.builder.validate_lock(payload)

    def test_branch_revision_and_unsafe_archive_are_rejected(self):
        payload = self.lock()
        payload['git_dependencies']['ffmpeg']['revision'] = 'n9.0'
        with self.assertRaises(self.builder.BuildFailure):
            self.builder.validate_lock(payload)
        payload = self.lock()
        payload['archives'] = {
            'lua': {
                'version': '5.2.4',
                'url': 'http://www.lua.org/ftp/lua-5.2.4.tar.gz',
                'sha256': 'b' * 64,
            }
        }
        with self.assertRaises(self.builder.BuildFailure):
            self.builder.validate_lock(payload)

    def test_api_and_abi_overrides_are_fixed(self):
        with tempfile.TemporaryDirectory() as directory:
            prefix = Path(directory) / 'prefix'
            environment, compiler = self.builder.make_environment(
                Path(directory) / 'ndk', Path(directory) / 'toolchain', Path(directory) / 'build-tools', prefix
            )
            self.assertEqual(compiler, 'aarch64-linux-android36')
            self.assertEqual(environment['ANDROID_API'], '36')
            self.assertEqual(environment['ANDROID_ABI'], 'arm64-v8a')
            self.assertEqual(environment['PREFIX'], str(prefix))
            self.assertNotIn('DESTDIR', environment)
            self.assertNotIn('PKG_CONFIG_SYSROOT_DIR', environment)
            self.assertEqual(environment['PKG_CONFIG_PATH'], str(prefix / 'lib' / 'pkgconfig'))
            self.assertEqual(environment['PKG_CONFIG_LIBDIR'], str(prefix / 'lib' / 'pkgconfig'))
            self.assertIn('max-page-size=16384', environment['LDFLAGS'])
            self.assertNotIn('armv7', environment['CC'])
            self.assertNotIn('23-clang', environment['CC'])
            cross = Path(directory) / 'cross.txt'
            self.builder.write_cross_file(cross, environment)
            text = cross.read_text(encoding='utf-8')
            self.assertIn(f"prefix = '{prefix}'", text)
            self.assertIn("c_args = ['--target=aarch64-linux-android36', '--sysroot=", text)
            self.assertNotIn('c_args = ["--target=', text)  # meson rejects double quotes

    def test_populated_archives_validate_without_planning_a_build(self):
        payload = self.lock()
        payload['archives'] = {
            name: {
                'version': payload['upstream_versioned_dependencies'][name],
                'url': f'https://example.invalid/{name}-1.0.tar.gz',
                'sha256': 'ab' * 32,
            }
            for name in self.builder.ARCHIVE_NAMES
        }
        validated = self.builder.validate_lock(payload)
        self.assertEqual(set(validated['archives']), set(self.builder.ARCHIVE_NAMES))
        self.builder.reject_unverified_archives(validated)

    def test_component_failure_stops_later_components(self):
        calls = []

        def fail_second(name, source, work, environment, cross_file, log_path, lock_data):
            calls.append(name)
            if name == 'dav1d':
                raise self.builder.BuildFailure('fixture compilation failed')
            return source

        original = self.builder.build_component
        self.builder.build_component = fail_second
        self.builder.prepare_git = lambda name, item, sources, env, log_path: sources / name
        self.builder.prepare_archive = lambda name, entry, downloads, sources, env, log_path: sources / name
        self.builder.discover_ndk = lambda explicit: (Path('/tmp/ndk'), Path('/tmp/ndk/toolchain'))
        self.builder.discover_build_tools = lambda: Path('/tmp/build-tools')
        self.builder.require_tools = lambda names: None
        self.builder.make_environment = lambda *args: (
            {'CC': '/tmp/clang', 'ANDROID_SYSROOT': '/tmp/sysroot', 'PREFIX': '/tmp/prefix', 'DESTDIR': '/tmp/prefix'},
            'aarch64-linux-android36',
        )
        self.builder.write_cross_file = lambda path, environment: None
        self.builder.verify_outputs = lambda prefix, log_path: 'fixture-not-a-real-build'
        payload = self.lock()
        payload['archives'] = {
            name: {
                'version': payload['upstream_versioned_dependencies'][name],
                'url': f'https://example.invalid/{name}.tar.gz',
                'sha256': 'c' * 64,
            }
            for name in self.builder.ARCHIVE_NAMES
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_path = root / 'lock.json'
            write_json(lock_path, payload)
            (root / 'clang').write_text('', encoding='utf-8')
            (root / 'sysroot').mkdir()
            self.builder.make_environment = lambda *args: (
                {
                    'CC': str(root / 'clang'),
                    'ANDROID_SYSROOT': str(root / 'sysroot'),
                    'PREFIX': str(root / 'prefix'),
                    'DESTDIR': str(root / 'prefix'),
                },
                'aarch64-linux-android36',
            )
            allowed = root / '.aharou' / 'native-build' / 'run'
            self.builder.PROJECT_ROOT = root
            with self.assertRaises(self.builder.BuildFailure) as caught:
                self.builder.build(lock_path, root / 'outside')
            self.assertIn('work directory must stay inside', str(caught.exception))
            with self.assertRaises(self.builder.BuildFailure) as caught:
                self.builder.build(lock_path, allowed)
            self.assertEqual(calls, ['mbedtls', 'dav1d'])
            self.assertIn('fixture compilation failed', str(caught.exception))
            self.assertFalse(any(path.name == 'libxml2' for path in (allowed / 'build').glob('*')))

    def test_missing_tools_are_rejected_without_using_workspace_state(self):
        self.builder._which = lambda name: None if name == 'meson' else '/usr/bin/' + name
        with self.assertRaises(self.builder.BuildFailure) as caught:
            self.builder.require_tools(('python3', 'meson', 'ninja'))
        self.assertIn('required build tools are not installed: meson', str(caught.exception))
        self.assertNotIn('ninja', str(caught.exception))
        self.assertIn('Automatic installation is disabled', str(caught.exception))

    def test_existing_directory_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            existing = root / 'build' / 'mbedtls'
            existing.mkdir(parents=True)
            (existing / 'keep.txt').write_text('keep', encoding='utf-8')
            created = self.builder._prepare_build_dir(root, 'mbedtls')
            self.assertTrue((existing / 'keep.txt').is_file())
            self.assertNotEqual(created, existing)
            with self.assertRaises(self.builder.BuildFailure):
                self.builder._remove_exclusive(existing, root / 'build')
            lookalike = root / 'build' / f'{created.name}'
            if lookalike != created:
                lookalike.mkdir()
            else:
                lookalike = root / 'build' / (created.name + '-copy')
                lookalike.mkdir()
            (lookalike / 'keep.txt').write_text('stranger', encoding='utf-8')
            with self.assertRaises(self.builder.BuildFailure):
                self.builder._remove_exclusive(lookalike, root / 'build')
            self.assertEqual((lookalike / 'keep.txt').read_text(encoding='utf-8'), 'stranger')
            owned = self.builder._exclusive_dir(root / 'build', 'owned')
            self.builder._remove_exclusive(owned, root / 'build')
            self.assertFalse(owned.exists())

    def test_archive_checksum_mismatch_refuses_extraction(self):
        payload = b'fixture archive member'
        raw = io.BytesIO()
        with tarfile.open(fileobj=raw, mode='w:gz') as archive:
            info = tarfile.TarInfo('fixture-1.0/README')
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
        data = raw.getvalue()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            downloads = root / 'downloads'
            sources = root / 'sources'
            downloads.mkdir()
            sources.mkdir()
            (downloads / 'fixture.tar.gz').write_bytes(data)
            entry = {
                'url': 'https://example.invalid/fixture.tar.gz',
                'sha256': 'd' * 64,
            }
            with self.assertRaises(self.builder.BuildFailure) as caught:
                self.builder.prepare_archive('fixture', entry, downloads, sources, {}, root / 'unused.log')
            self.assertIn('checksum mismatch', str(caught.exception))
            self.assertFalse((sources / 'fixture').exists())
            entry['sha256'] = hashlib.sha256(data).hexdigest()
            extracted = self.builder.prepare_archive('fixture', entry, downloads, sources, {}, root / 'unused.log')
            self.assertEqual((extracted / 'README').read_bytes(), payload)
            self.assertFalse(any(path.suffix == '.partial' for path in downloads.iterdir()))
            with self.assertRaises(self.builder.BuildFailure) as caught:
                self.builder.prepare_archive('fixture', entry, downloads, sources, {}, root / 'unused.log')
            self.assertIn('already exists', str(caught.exception))

    def test_mbedtls_headers_keep_nested_layout_on_a_source_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'cache'
            (source / 'include' / 'mbedtls').mkdir(parents=True)
            (source / 'include' / 'psa').mkdir()
            (source / 'include' / 'mbedtls' / 'ssl.h').write_text('ssl', encoding='utf-8')
            (source / 'include' / 'psa' / 'crypto.h').write_text('psa', encoding='utf-8')
            (source / 'library').mkdir()
            for name in ('libmbedtls.a', 'libmbedx509.a', 'libmbedcrypto.a'):
                (source / 'library' / name).write_bytes(b'archive')
            commands = []

            def fake_run(command, cwd, env, log_path):
                commands.append(list(map(str, command)))

            self.builder._run = fake_run
            prefix = root / 'prefix'
            environment = {'CC': 'clang', 'AR': 'llvm-ar', 'PREFIX': str(prefix)}
            self.builder.build_component(
                'mbedtls', source, root / 'work', environment, root / 'cross.txt', root / 'build.log',
                {'versions': {'mbedtls': '3.6.7'}},
            )
            self.assertEqual((prefix / 'include' / 'mbedtls' / 'ssl.h').read_text(encoding='utf-8'), 'ssl')
            self.assertEqual((prefix / 'include' / 'psa' / 'crypto.h').read_text(encoding='utf-8'), 'psa')
            self.assertTrue((source / 'include' / 'mbedtls' / 'ssl.h').is_file())
            self.assertFalse(any('clean' in command for command in commands))
            self.assertTrue(any(Path(command[2]).is_relative_to(root / 'work') for command in commands if command[:2] == ['make', '-C']))

    def test_pkg_config_prefix_is_not_duplicated(self):
        if shutil.which('pkg-config') is None:
            self.skipTest('real pkg-config is not installed; a shell substitute does not prove sysroot handling')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prefix = root / 'prefix'
            # Exercise the real pc layout produced by _install_pc instead of an
            # arbitrary absolute prefix that freedesktop pkg-config relocates.
            self.builder._install_pc(prefix, 'fixture', '-lfixture', '1')
            environment, _compiler = self.builder.make_environment(
                root / 'ndk', root / 'toolchain', root / 'build-tools', prefix
            )
            completed = subprocess.run(
                ['pkg-config', '--cflags', 'fixture'], env=environment, text=True, capture_output=True, check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(completed.stdout.strip(), f'-I{prefix}/include')

    def test_nested_gitlinks_are_checked_recursively(self):
        from unittest.mock import patch

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            child = root / 'child'
            nested = child / 'nested'
            nested.mkdir(parents=True)
            (child / '.git').write_text('controlled fixture', encoding='utf-8')
            (nested / '.git').write_text('controlled fixture', encoding='utf-8')
            child_revision = 'a' * 40
            nested_revision = 'b' * 40
            visited = []
            invalid_nested = False

            def fake_git(source, *args, env):
                if args == ('ls-tree', '-r', 'HEAD'):
                    visited.append(source)
                    if source == root:
                        return f'160000 commit {child_revision}\tchild'
                    if source == child:
                        return f'160000 commit {nested_revision}\tnested'
                    return ''
                if args == ('rev-parse', 'HEAD'):
                    return child_revision if source == child else nested_revision
                if args == ('status', '--porcelain', '--untracked-files=no'):
                    return ' M tracked-file' if source == nested and invalid_nested else ''
                raise AssertionError(f'Unexpected Git request: {source}, {args}')

            with patch.object(self.builder, '_git', side_effect=fake_git):
                self.builder._verify_gitlinks(root, {})
                self.assertEqual(visited, [root, child, nested])
                invalid_nested = True
                with self.assertRaises(self.builder.BuildFailure) as caught:
                    self.builder._verify_gitlinks(root, {})
                self.assertIn('tracked modifications', str(caught.exception))

    def test_verify_outputs_requires_named_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            prefix = Path(directory)
            (prefix / 'libunrelated.so').write_bytes(b'not-required')
            with self.assertRaises(self.builder.BuildFailure) as caught:
                self.builder.verify_outputs(prefix, prefix / 'verify.log')
            self.assertIn('libmpv.so', str(caught.exception))
            self.assertIn('libavcodec.so', str(caught.exception))

    def test_verify_outputs_accepts_versioned_library_aliases(self):
        import struct

        ident = b'\x7fELF' + bytes((2, 1, 1)) + bytes(9)
        header = struct.pack('<16sHHIQQQIHHHHHH', ident, 3, 183, 1,
                             0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
        segment = struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 120, 120, 16384)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prefix = root / 'prefix'
            libraries = prefix / 'lib'
            libraries.mkdir(parents=True)
            for name in self.builder.REQUIRED_SHARED_LIBRARIES:
                versioned = libraries / (name + '.1.0')
                versioned.write_bytes(header + segment)
                (libraries / name).symlink_to(versioned.name)
            payload = json.loads(self.builder.verify_outputs(prefix, root / 'verify.log'))
            self.assertEqual(payload['status'], 'verified')
            self.assertEqual(payload['abi'], 'arm64-v8a')
            self.assertEqual(len(payload['libraries']), len(self.builder.REQUIRED_SHARED_LIBRARIES))
            self.assertEqual(payload['gpu_next_rendering'], 'not_verified')

            invalid = libraries / 'libavcodec.so.1.0'
            data = bytearray(invalid.read_bytes())
            struct.pack_into('<H', data, 18, 62)
            invalid.write_bytes(data)
            with self.assertRaises(self.builder.BuildFailure):
                self.builder.verify_outputs(prefix, root / 'invalid-verify.log')

    def test_fixture_does_not_prove_real_dependency_build(self):
        self.assertTrue(SCRIPT.is_file())
        self.assertIn('not a successful native build', SCRIPT.read_text(encoding='utf-8'))
        self.assertFalse((ROOT / '.aharou' / 'native-build' / 'prefix').exists())


if __name__ == '__main__':
    unittest.main()
