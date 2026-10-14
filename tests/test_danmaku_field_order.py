"""弹幕 p 属性字段顺序的静态回归测试。

背景：审核293 修复了「弹幕功能实际是坏的」——dm/list.so 返回的是 deflate
压缩的 XML 而非 JSON。修复过程中发现 toParsed() 把索引 5 当 uid，实际取到的
是弹幕池（几乎总是 0），真正的用户 hash 在索引 6。

字段顺序用实测样本验证过：
    0.60100,4,25,15138834,1604742895,0,9e5adeaa,40685126698926083,10
    0=出现秒 1=mode 2=字号 3=颜色 4=发送时间戳 5=弹幕池 6=用户hash 7=rowid

Kotlin 没有单测设施（本项目 tests/ 统一用 Python 静态检查），这里断言
DanmakuModels.kt 的索引没被改坏，防回归。
"""
import os
import unittest

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                   'android/app/src/main/kotlin/com/example/piliai/data/model/DanmakuModels.kt')

# 实测样本（dm/list.so 首条弹幕的 p 属性）
SAMPLE_P = '0.60100,4,25,15138834,1604742895,0,9e5adeaa,40685126698926083,10'


class DanmakuFieldOrder(unittest.TestCase):
    def setUp(self):
        self.assertTrue(os.path.exists(SRC), SRC)
        with open(SRC, encoding='utf-8') as f:
            self.src = f.read()

    def test_timestamp_is_index_0(self):
        # 出现时间在索引 0，必须是 Float 取值
        self.assertIn('timestamp = f(0, 0f)', self.src)

    def test_mode_is_index_1(self):
        self.assertIn('mode = i(1, 1)', self.src)

    def test_fontsize_is_index_2(self):
        self.assertIn('fontSize = i(2, 25)', self.src)

    def test_color_is_index_3(self):
        self.assertIn('color = i(3, 0xFFFFFF)', self.src)

    def test_pool_is_index_5(self):
        # 弹幕池在索引 5
        self.assertIn('pool = i(5, 0)', self.src)

    def test_uid_uses_index_6_not_5(self):
        """核心回归：uid 必须取索引 6（用户 hash），不能是 5（弹幕池）。"""
        self.assertIn('parts.getOrNull(6)', self.src)
        # 不能出现把索引 5 当 uid 的旧写法
        self.assertNotIn('uid = l(5', self.src)
        self.assertNotIn('uid = parts.getOrNull(5)', self.src)

    def test_content_from_m_field(self):
        self.assertIn('content = m', self.src)

    def test_sample_fields_parse_as_expected(self):
        """用实测样本验证字段顺序解读（纯 Python 复刻 toParsed 的索引逻辑）。"""
        parts = SAMPLE_P.split(',')
        self.assertAlmostEqual(float(parts[0]), 0.601, places=3)
        self.assertEqual(int(parts[1]), 4)           # mode=底部
        self.assertEqual(int(parts[2]), 25)          # 字号
        self.assertTrue(0 <= int(parts[3]) <= 0xFFFFFF)  # 颜色
        self.assertTrue(1_500_000_000 < int(parts[4]) < 1_800_000_000)  # 时间戳
        self.assertEqual(parts[5], '0')              # 弹幕池
        self.assertEqual(len(parts[6]), 8)           # 用户 hash 是 8 位 hex
        # 关键：索引 5 和 6 必须不同，否则「uid 取错索引」不会被发现
        self.assertNotEqual(parts[5], parts[6])


if __name__ == '__main__':
    unittest.main()
