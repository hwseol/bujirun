"""python -m unittest test_trails"""

import json
import os
import tempfile
import unittest

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name

import trails  # noqa: E402


def gpx(points, lon_first=False):
    tags = []
    for lat, lon, ele in points:
        attrs = f'lon="{lon}" lat="{lat}"' if lon_first else f'lat="{lat}" lon="{lon}"'
        inner = f"<ele>{ele}</ele><time>2020-01-01T00:00:00Z</time>" if ele is not None else ""
        tags.append(f"<trkpt {attrs}>{inner}</trkpt>" if inner else f"<trkpt {attrs}/>")
    return f'<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>{"".join(tags)}</trkseg></trk></gpx>'


LINE = [[37.50 + i * 0.001, 127.0, 10.0 + i] for i in range(20)]  # ~2.1 km north


class TrailsTest(unittest.TestCase):
    def setUp(self):
        trails.db_path().unlink(missing_ok=True)

    def test_parse_gpx_both_attribute_orders(self):
        self.assertEqual(trails.parse_gpx(gpx(LINE))[:2], LINE[:2])
        self.assertEqual(trails.parse_gpx(gpx(LINE, lon_first=True))[:2], LINE[:2])
        self.assertIsNone(trails.parse_gpx(gpx([[37.5, 127.0, None], [37.6, 127.0, None]]))[0][2])

    def test_clean_text(self):
        self.assertEqual(trails.clean_text("- 바다길<br>- 숲길 &amp; 해안<br />"), "- 바다길\n- 숲길 & 해안")

    def _fake_fetch(self, courses, files):
        def fetch(url):
            if url.startswith(trails.API):
                return json.dumps({"response": {"body": {"totalCount": len(courses), "items": {"item": courses}}}}).encode()
            return files[url].encode()
        return fetch

    def test_sync_and_nearby(self):
        far = [[35.10 + i * 0.001, 129.03, None] for i in range(20)]  # Busan
        courses = [
            {"crsIdx": "A", "crsKorNm": "서울 근처길", "sigun": "서울", "crsDstnc": "2", "crsTotlRqrmHour": "40",
             "crsLevel": "1", "crsCycle": "비순환형", "crsSummary": "강변<br>산책", "gpxpath": "http://x/a.gpx"},
            {"crsIdx": "B", "crsKorNm": "부산 바닷길", "sigun": "부산", "crsDstnc": "2", "crsTotlRqrmHour": "40",
             "crsLevel": "2", "crsCycle": "순환형", "crsSummary": "", "gpxpath": "http://x/b.gpx"},
            {"crsIdx": "C", "crsKorNm": "경로 없음", "gpxpath": "http://x/c.gpx"},
        ]
        files = {"http://x/a.gpx": gpx(LINE), "http://x/b.gpx": gpx(far), "http://x/c.gpx": "<gpx/>"}
        stored = trails.sync("key", fetch=self._fake_fetch(courses, files), log=lambda *_: None)
        self.assertEqual(stored, 2)

        near = trails.nearby(37.505, 127.001)
        self.assertEqual([t["id"] for t in near], ["A", "B"])
        self.assertLess(near[0]["away_m"], 100)
        self.assertEqual(near[0]["summary"], "강변\n산책")
        self.assertAlmostEqual(near[0]["distance_m"], 2113, delta=20)
        self.assertEqual(near[0]["source"], trails.SOURCE)
        self.assertTrue(near[1]["loop"])

        full = trails.get("A")
        self.assertEqual(full["points"][0][:2], LINE[0][:2])
        self.assertIsNone(trails.get("nope"))


if __name__ == "__main__":
    unittest.main()
