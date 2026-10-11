import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# Kotlin 侧 RecommendFilter 无法直接 python 测——用 lint 门禁已覆盖编译。补一个静态检查：RecommendFilter.kt 存在且含关键方法
import unittest

class RecommendFilterStatic(unittest.TestCase):
    def setUp(self):
        p = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                         'android/app/src/main/kotlin/com/example/piliai/utils/RecommendFilter.kt')
        self.assertTrue(os.path.exists(p), p)
        self.src = open(p, encoding='utf-8').read()

    def test_has_filter_dims(self):
        for kw in ['filterDuration', 'filterLikeRatio', 'filterTitle', 'exemptFilterForFollowed',
                   'minDurationSec', 'minPlay', 'minLikeRatioPercent', 'IGNORE_CASE']:
            self.assertIn(kw, self.src)

    def test_disabled_by_default(self):
        self.assertIn('minDurationSec: Int = 0', self.src)
        self.assertIn('minPlay: Long = 0L', self.src)

if __name__ == '__main__':
    unittest.main()
