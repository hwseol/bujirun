"""python -m unittest test_places"""

import json
import os
import tempfile
import unittest

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name

import places  # noqa: E402


def park(no, name, kind, lat, lon, area, region="서울특별시 서초구"):
    return {"manageNo": no, "parkNm": name, "parkSe": kind, "latitude": str(lat), "longitude": str(lon),
            "parkAr": str(area), "insttNm": region, "rdnmadr": "", "lnmadr": "서울 어딘가", "referenceDate": "2025-12-15"}


ITEMS = [
    park("1", "반 포", "근린공원", 37.505, 127.000, 120_000),
    park("2", "신반포", "근린공원", 37.512, 126.995, 30_000),
    park("3", "놀이터", "어린이공원", 37.5101, 126.9961, 1_500),         # right here, but tiny
    park("4", "올림픽공원", "근린공원", 37.520, 127.121, 1_450_000, "서울특별시 송파구"),
    park("5", "올림픽공원", "어린이공원", 35.10, 129.03, 2_000, "부산광역시"),  # same name, far, small
    park("6", "좌표없음", "근린공원", "", "", 50_000),
    park("7", "뒤바뀐좌표", "근린공원", 127.0, 37.5, 50_000),
]


def fake_fetch(pages):
    def fetch(url):
        page = int(url.split("pageNo=")[1].split("&")[0])
        items = pages[page - 1] if page <= len(pages) else []
        return json.dumps({"header": {"resultCode": "00"},
                           "body": {"items": {"item": items}, "totalCount": sum(map(len, pages))}}).encode()
    return fetch


class PlacesTest(unittest.TestCase):
    def setUp(self):
        places.db_path().unlink(missing_ok=True)

    def test_sync_pages_and_skips_unplaceable_rows(self):
        n = places.sync("key", fetch=fake_fetch([ITEMS[:4], ITEMS[4:]]))
        self.assertEqual(n, 5)

    def test_nearby_offers_only_runnable_parks_nearest_first(self):
        places.sync("key", fetch=fake_fetch([ITEMS]))
        names = [p["name"] for p in places.nearby(37.51, 126.996, 2000)]
        self.assertEqual(names, ["신반포", "반 포"])
        self.assertIn("놀이터", [p["name"] for p in places.nearby(37.51, 126.996, 2000, all_parks=True)])

    def test_find_ignores_spaces_and_prefers_the_real_one(self):
        places.sync("key", fetch=fake_fetch([ITEMS]))
        self.assertEqual(places.find("반포", 37.51, 126.996)[0]["name"], "반 포")
        # Busan's tiny namesake is nearer to a Busan user, but Seoul's is still found first in Seoul
        self.assertEqual(places.find("올림픽 공원", 37.51, 126.996)[0]["region"], "서울특별시 송파구")
        self.assertEqual(places.find("공", 37.51, 126.996), [])

    def test_sync_keeps_aliases(self):
        places.sync("key", fetch=fake_fetch([ITEMS]))
        conn = places._connect()
        conn.execute("UPDATE places SET aliases = ? WHERE id = '4'", (json.dumps(["올팍"], ensure_ascii=False),))
        conn.commit()
        conn.close()
        places.sync("key", fetch=fake_fetch([ITEMS]))
        self.assertEqual(places.find("올팍", 37.51, 126.996)[0]["id"], "4")


if __name__ == "__main__":
    unittest.main()
