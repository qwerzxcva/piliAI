"""空降助手时间戳识别的回归测试（Python 复刻 Kotlin 正则与解析逻辑）。

Kotlin 实现在 ui/comments/SeekableCommentText.kt：
  TIMESTAMP_REGEX = (?:@)?(?:\d{1,2}:)?\d{1,2}:\d{1,2}(?=\s|$|[，。！？、,.)）]|$)
  parseTimestampToMs 解析 h:mm:ss / m:ss，要求末段 0~59、超 24h 拒绝。
"""
import os
import re
import unittest

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                   'android/app/src/main/kotlin/com/example/piliai/ui/comments/SeekableCommentText.kt')

# 与 Kotlin 一致的 Python 版正则（lookahead 语法兼容）
TS_RE = re.compile(r'(?:@)?(?:\d{1,2}:)?\d{1,2}:\d{1,2}(?=\s|$|[，。！？、,.)）]|$)')


def parse_ts(text):
    clean = text.lstrip('@').strip()
    parts = clean.split(':')
    if len(parts) not in (2, 3):
        return None
    nums = []
    for p in parts:
        try:
            nums.append(int(p))
        except ValueError:
            return None
    if not (0 <= nums[-1] <= 59):
        return None
    if len(nums) == 3 and not (0 <= nums[1] <= 59):
        return None
    secs = nums[0] * 3600 + nums[1] * 60 + nums[2] if len(nums) == 3 \
        else nums[0] * 60 + nums[1]
    if secs > 24 * 3600:
        return None
    return secs * 1000


class SeekableTimestamp(unittest.TestCase):
    def setUp(self):
        self.assertTrue(os.path.exists(SRC), SRC)
        with open(SRC, encoding='utf-8') as f:
            self.src = f.read()

    def test_source_has_impl(self):
        for kw in ('TIMESTAMP_REGEX', 'parseTimestampToMs', 'SEEK_TAG',
                   'buildSeekableCommentText', 'ClickableCommentText'):
            self.assertIn(kw, self.src)

    def test_basic_m_s(self):
        self.assertEqual(parse_ts('3:25'), 205_000)

    def test_h_m_s(self):
        self.assertEqual(parse_ts('01:23:45'), 5_025_000)

    def test_at_prefix(self):
        self.assertEqual(parse_ts('@3:25'), 205_000)

    def test_zero(self):
        self.assertEqual(parse_ts('0:00'), 0)

    def test_reject_seconds_over_59(self):
        # 99:99 秒段越界，应拒绝
        self.assertIsNone(parse_ts('99:99'))

    def test_reject_single_number(self):
        # 正则不匹配单数字，这里验证解析层也挡得住
        self.assertIsNone(parse_ts('3'))

    def test_reject_over_24h(self):
        self.assertIsNone(parse_ts('99:99:99'))

    def test_regex_finds_in_sentence(self):
        text = '这个视频 3:25 处高能，01:23:45 名场面'
        found = TS_RE.findall(text)
        self.assertEqual(found, ['3:25', '01:23:45'])

    def test_regex_with_at_and_punct(self):
        text = '看@12:34，然后5:06。'
        found = TS_RE.findall(text)
        self.assertEqual(found, ['@12:34', '5:06'])

    def test_regex_matches_99_99_but_parse_rejects(self):
        """正则只管格式（会匹配 99:99），但解析层必须拒绝它——
        buildSeekableCommentText 对每个命中都过 parseTimestampToMs，
        所以非法时间戳最终不会变成可点击跳转点。"""
        found = TS_RE.findall('不可能的时间 99:99 出现')
        self.assertEqual(found, ['99:99'])          # 正则确实匹配
        self.assertIsNone(parse_ts(found[0]))        # 但解析层拒绝

    def test_plain_number_not_matched(self):
        self.assertEqual(TS_RE.findall('我有 3 个视频和 2024 年的回忆'), [])

    def test_matches_are_seekable(self):
        text = '空降 1:00 起飞'
        for m in TS_RE.findall(text):
            self.assertIsNotNone(parse_ts(m))


if __name__ == '__main__':
    unittest.main()
