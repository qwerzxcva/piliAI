"""Verify the Android-only ffi module has no orphan declarations.

Why this exists: `gpu_next.rs`'s ffi module is `#[cfg(target_os = "android")]`,
so `cargo build` on the host never compiles it and never reports its dead code.
CI runs clippy for the Android target and does. A dead helper removed on the
host side can therefore leave an orphan behind and turn CI red (that is what
happened in run 38344951871 after `describe_failure` was deleted).

Scope of the check (deliberately narrow, to avoid false positives):
- only plain `fn name(...)` declarations directly in the ffi module body
- excludes `extern "C"` blocks (those are libmpv's API, not ours)
- excludes `impl Trait for Type` blocks (methods there satisfy the trait)
- excludes `#[allow(dead_code)]` items

A reported orphan means the fn is declared and never referenced anywhere in
the module, which is exactly what clippy's dead_code lint flags.
"""
from pathlib import Path
import re
import unittest

# 相对本脚本定位仓库根：CI 的工作目录与本地不同，不能写死绝对路径
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'rust/src/gpu_next.rs'


def ffi_orphans(source: str):
    """Return [(lineno, name, refs)] for orphaned plain fns in the ffi module."""
    start = source.index('mod ffi {')
    tests = source.index('mod tests', start)
    ffi = source[start:tests]

    # extern "C" 块：libmpv 的声明，不算我们的死代码
    extern_match = re.search(r'extern "C" \{.*?\n    \}', ffi, re.DOTALL)
    extern_names = set()
    if extern_match:
        extern_names = set(
            re.findall(r'^\s*fn (mpv_\w+)\(', extern_match.group(0), re.MULTILINE)
        )

    # impl Trait for Type 块：方法受 trait 约束，clippy 不报死代码
    impl_spans = [m.span() for m in re.finditer(r'impl \w+ for \w+ \{.*?\n    \}', ffi, re.DOTALL)]

    def in_impl(pos):
        return any(a <= pos < b for a, b in impl_spans)

    orphans = []
    for match in re.finditer(r'^\s*fn (\w+)\(', ffi, re.MULTILINE):
        name = match.group(1)
        if name in extern_names or in_impl(match.start()):
            continue
        line_start = ffi.rfind('\n', 0, match.start()) + 1
        if 'allow(dead_code)' in ffi[line_start:match.start()]:
            continue
        refs = len(re.findall(rf'\b{name}\b', ffi))
        if refs <= 1:
            lineno = source[:start].count('\n') + ffi[:match.start()].count('\n') + 1
            orphans.append((lineno, name, refs))
    return orphans


class FfiOrphanTest(unittest.TestCase):
    def test_no_orphan_ffi_declarations(self):
        source = SRC.read_text(encoding='utf-8')
        orphans = ffi_orphans(source)
        detail = ', '.join(f'gpu_next.rs:{n} {name} (refs={r})' for n, name, r in orphans)
        self.assertEqual([], orphans, f'orphan ffi declarations: {detail}')


if __name__ == '__main__':
    unittest.main()
