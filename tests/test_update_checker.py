"""更新检查的版本比较回归测试（Python 复刻 Kotlin isNewer 逻辑）。

Kotlin 实现在 utils/UpdateChecker.kt：
  isNewer(remote, current) —— 语义化版本逐段比较，全等返回 false
  parseVersion —— 取 '-'/'+' 前的数字段，任一段非数字则返回 null
"""
import os
import re
import unittest

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                   'android/app/src/main/kotlin/com/example/piliai/utils/UpdateChecker.kt')


def parse_version(v):
    numeric = v.strip().split('-')[0].split('+')[0]
    if not numeric:
        return None
    parts = numeric.split('.')
    nums = []
    for p in parts:
        try:
            nums.append(int(p))
        except ValueError:
            return None
    return nums if nums else None


def is_newer(remote, current):
    r = parse_version(remote)
    c = parse_version(current)
    if r is None or c is None:
        return False
    for i in range(max(len(r), len(c))):
        rv = r[i] if i < len(r) else 0
        cv = c[i] if i < len(c) else 0
        if rv != cv:
            return rv > cv
    return False


class VersionCompare(unittest.TestCase):
    def setUp(self):
        self.assertTrue(os.path.exists(SRC), SRC)
        with open(SRC, encoding='utf-8') as f:
            self.src = f.read()

    def test_source_has_impl(self):
        for kw in ('isNewer', 'parseVersion', 'releases/latest', 'UpdateInfo'):
            self.assertIn(kw, self.src)

    def test_patch_bump(self):
        self.assertTrue(is_newer('1.0.1', '1.0.0'))
        self.assertFalse(is_newer('1.0.0', '1.0.1'))

    def test_minor_bump(self):
        self.assertTrue(is_newer('1.1.0', '1.0.9'))

    def test_major_bump(self):
        self.assertTrue(is_newer('2.0.0', '1.99.99'))

    def test_double_digit_segment(self):
        # 不能按字符串比：'10.0.0' > '9.0.0' 必须成立
        self.assertTrue(is_newer('10.0.0', '9.0.0'))
        self.assertTrue(is_newer('1.10.0', '1.9.0'))

    def test_equal_returns_false(self):
        self.assertFalse(is_newer('1.0.0', '1.0.0'))

    def test_different_length_padded_with_zero(self):
        # 1.0 与 1.0.0 视为相等；1.0.1 更新
        self.assertFalse(is_newer('1.0', '1.0.0'))
        self.assertTrue(is_newer('1.0.1', '1.0'))

    def test_v_prefix_stripped_by_caller(self):
        # Kotlin 侧调用前 removePrefix("v")，这里验证比较本身不依赖 v
        self.assertTrue(is_newer('1.2.3', '1.2.2'))

    def test_prerelease_suffix_ignored(self):
        # 只比较 '-' 前的数字段：1.0.1-beta 与 1.0.1 相等 → 不算更新
        self.assertFalse(is_newer('1.0.1-beta', '1.0.1'))

    def test_garbage_returns_false(self):
        self.assertFalse(is_newer('abc', '1.0.0'))
        self.assertFalse(is_newer('1.0.0', ''))
        self.assertFalse(is_newer('', ''))

    def test_partial_garbage_returns_false(self):
        self.assertFalse(is_newer('1.x.0', '1.0.0'))

    def test_downgrade_not_flagged(self):
        self.assertFalse(is_newer('0.9.0', '1.0.0'))


if __name__ == '__main__':
    unittest.main()
