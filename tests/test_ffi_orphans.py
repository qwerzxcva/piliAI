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
- excludes `#[cfg(test)]`/`#[allow(dead_code)]` items

A reported orphan means the fn is declared and never referenced anywhere in
the module, which is exactly what clippy's dead_code lint flags.
"""
from pathlib import Path
import re
import sys

SRC = Path('/root/workspace/piliai/rust/src/gpu_next.rs')
text = SRC.read_text(encoding='utf-8')

start = text.index('mod ffi {')
tests = text.index('mod tests', start)
ffi = text[start:tests]

# 1. extern "C" 块：libmpv 的声明，不算我们的死代码
extern_match = re.search(r'extern "C" \{.*?\n    \}', ffi, re.DOTALL)
extern_names = set()
if extern_match:
    extern_names = set(re.findall(r'^\s*fn (mpv_\w+)\(', extern_match.group(0), re.MULTILINE))

# 2. impl Trait for Type 块：方法受 trait 约束，clippy 不报死代码
impl_spans = [m.span() for m in re.finditer(r'impl \w+ for \w+ \{.*?\n    \}', ffi, re.DOTALL)]


def in_impl(pos: int) -> bool:
    return any(a <= pos < b for a, b in impl_spans)


orphans = []
for match in re.finditer(r'^\s*fn (\w+)\(', ffi, re.MULTILINE):
    name = match.group(1)
    if name in extern_names:
        continue
    if in_impl(match.start()):
        continue
    # 同一次出现里带 allow(dead_code) 的跳过
    line_start = ffi.rfind('\n', 0, match.start()) + 1
    prefix = ffi[line_start:match.start()]
    if 'allow(dead_code)' in prefix:
        continue
    refs = len(re.findall(rf'\b{name}\b', ffi))
    if refs <= 1:
        line = text[:start].count('\n') + ffi[:match.start()].count('\n') + 1
        orphans.append((line, name, refs))

if orphans:
    print('ORPHAN ffi declarations (dead on the Android target):')
    for line, name, refs in orphans:
        print(f'  gpu_next.rs:{line}  {name}  (refs={refs})')
    sys.exit(1)

own = [
    m.group(1) for m in re.finditer(r'^\s*fn (\w+)\(', ffi, re.MULTILINE)
    if m.group(1) not in extern_names and not in_impl(m.start())
]
print(f'ffi module clean: {len(set(own))} own fns referenced '
      f'({len(extern_names)} libmpv externs skipped)')
