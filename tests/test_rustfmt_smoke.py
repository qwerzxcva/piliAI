"""Guard against the rustfmt violations that CI's `cargo fmt --check` rejects.

Why this exists: this sandbox has no rustfmt/clippy installed, so
`cargo fmt --all -- --check` failures only surface in CI. That is exactly what
happened in run 38349590055: removing a function left a double blank line in
danmaku.rs and the Rust job went red on the fmt gate before clippy even ran.

This is not a rustfmt reimplementation. It checks the handful of rules that
rustfmt enforces unconditionally and that are trivial to verify textually:
- no two consecutive blank lines
- no trailing whitespace
- no tab indentation
- file ends with exactly one newline
- no blank line right after an opening brace or right before a closing one

Anything beyond that needs the real rustfmt; this is a smoke guard, not a
replacement.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
RUST_SOURCES = sorted((ROOT / 'rust/src').glob('*.rs'))


def violations(text: str):
    out = []
    lines = text.split('\n')
    for idx, line in enumerate(lines, 1):
        if line != line.rstrip():
            out.append((idx, 'trailing whitespace'))
        if '\t' in line:
            out.append((idx, 'tab indentation'))
    for match in re.finditer(r'\n\n\n+', text):
        out.append((text[:match.start()].count('\n') + 1, 'consecutive blank lines'))
    # { 后紧跟空行 / 空行后紧跟 }
    for match in re.finditer(r'\{\n\n+', text):
        out.append((text[:match.start()].count('\n') + 1, 'blank line after opening brace'))
    for match in re.finditer(r'\n\n+\s*\}', text):
        out.append((text[:match.start()].count('\n') + 1, 'blank line before closing brace'))
    if not text.endswith('\n') or text.endswith('\n\n'):
        out.append((len(lines), 'file must end with exactly one newline'))
    return out


class RustFmtSmokeTest(unittest.TestCase):
    def test_no_rustfmt_smoke_violations(self):
        for path in RUST_SOURCES:
            found = violations(path.read_text(encoding='utf-8'))
            detail = ', '.join(f'{path.name}:{n} {why}' for n, why in found)
            self.assertEqual([], found, f'rustfmt smoke violations: {detail}')


if __name__ == '__main__':
    unittest.main()
