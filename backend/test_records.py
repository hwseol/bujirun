import os
import tempfile
import unittest
from unittest import mock

import records


class RecordsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["BJ_DATA_DIR"] = self.dir.name

    def tearDown(self):
        del os.environ["BJ_DATA_DIR"]
        self.dir.cleanup()

    def test_items_are_kept_per_account(self):
        records.put_item(1, "w:1729000000000", "aa11bb22", {"distanceM": 5012.3, "points": [[1, 37.5, 127.0]]})
        records.put_item(2, "w:1729000000000", "cc33dd44", {"distanceM": 300})
        self.assertEqual(records.list_items(1), [{"key": "w:1729000000000", "hash": "aa11bb22"}])
        self.assertEqual(records.get_item(1, "w:1729000000000")["points"], [[1, 37.5, 127.0]])
        self.assertEqual(records.get_item(2, "w:1729000000000")["distanceM"], 300)
        self.assertIsNone(records.get_item(1, "w:1"))

    def test_putting_again_replaces_and_delete_removes(self):
        records.put_item(1, "c:5", "11112222", {"name": "한강"})
        records.put_item(1, "c:5", "33334444", {"name": "한강 따라"})
        self.assertEqual(records.list_items(1), [{"key": "c:5", "hash": "33334444"}])
        self.assertEqual(records.get_item(1, "c:5")["name"], "한강 따라")
        records.delete_item(1, "c:5")
        self.assertEqual(records.list_items(1), [])

    def test_account_deletion_takes_its_records(self):
        records.put_item(1, "w:1", "aaaaaaaa", {})
        records.put_item(1, "steps", "bbbbbbbb", {"2026-10-24": 2296})
        records.put_item(2, "w:1", "cccccccc", {})
        records.delete_user(1)
        self.assertEqual(records.list_items(1), [])
        self.assertEqual(len(records.list_items(2)), 1)

    def test_bad_keys_and_limits(self):
        for key in ("", "w:", "../x", "w:1/2", "toolongprefix:1"):
            with self.assertRaises(records.RecordError):
                records.put_item(1, key, "aaaaaaaa", {})
        with self.assertRaises(records.RecordError):
            records.put_item(1, "w:1", "not a hash", {})
        with mock.patch.object(records, "MAX_ITEMS_PER_USER", 2):
            records.put_item(1, "w:1", "aaaaaaaa", {})
            records.put_item(1, "w:2", "aaaaaaaa", {})
            records.put_item(1, "w:2", "bbbbbbbb", {})  # replacing one still fits
            with self.assertRaises(records.RecordError):
                records.put_item(1, "w:3", "aaaaaaaa", {})


if __name__ == "__main__":
    unittest.main()
