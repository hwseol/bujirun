import os
import tempfile
import time
import unittest
from unittest import mock

import notices


class NoticesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["BJ_DATA_DIR"] = self.dir.name

    def tearDown(self):
        del os.environ["BJ_DATA_DIR"]
        self.dir.cleanup()

    def test_new_ones_after_the_last_seen(self):
        a = notices.add("점검", "오늘 밤 점검해요")
        b = notices.add("새 버전", "업데이트해 주세요")
        self.assertEqual([n["id"] for n in notices.active()], [a, b])
        self.assertEqual([n["title"] for n in notices.active(after=a)], ["새 버전"])
        self.assertEqual(notices.active(after=b), [])

    def test_expired_and_removed_are_gone(self):
        a = notices.add("잠깐", "곧 사라져요", days=1)
        b = notices.add("남아요", "일주일")
        with mock.patch("time.time", return_value=time.time() + 2 * 86400):
            self.assertEqual([n["id"] for n in notices.active()], [b])
        self.assertTrue(notices.remove(b))
        self.assertFalse(notices.remove(b))
        self.assertEqual([n["id"] for n in notices.active()], [a])


if __name__ == "__main__":
    unittest.main()
