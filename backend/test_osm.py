"""python -m unittest test_osm"""

import json
import os
import tempfile
import unittest

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name

import osm  # noqa: E402
import places  # noqa: E402

D = 0.001  # ~111 m north, ~88 m east at Seoul


def geom(points):
    return [{"lat": lat, "lon": lon} for lat, lon in points]


def square(lat, lon, n):
    return [(lat, lon), (lat, lon + n * D), (lat + n * D, lon + n * D), (lat + n * D, lon), (lat, lon)]


def way(id, tags, points):
    return {"type": "way", "id": id, "tags": tags, "geometry": geom(points)}


class GeometryTest(unittest.TestCase):
    def test_relation_outline_in_pieces_becomes_one_ring(self):
        sq = square(37.5, 127.0, 5)
        rel = {"type": "relation", "id": 9, "tags": {"leisure": "park", "name": "서울숲"}, "members": [
            {"type": "way", "role": "outer", "geometry": geom(sq[:3])},
            {"type": "way", "role": "outer", "geometry": geom(sq[2:][::-1])},  # second half, reversed
            {"type": "way", "role": "inner", "geometry": geom(square(37.501, 127.001, 1))},
        ]}
        rows = osm.area_rows([rel], [])
        self.assertEqual(len(rows), 1)
        ring = json.loads(rows[0]["shape"])
        self.assertEqual(len(ring), 1)                   # inner ring left out
        self.assertEqual(ring[0][0], ring[0][-1])        # closed
        self.assertAlmostEqual(rows[0]["area_m2"], 555 * 441, delta=3000)

    def test_relation_without_members_falls_back_to_its_box(self):
        rel = {"type": "relation", "id": 1, "tags": {"natural": "water", "water": "lake", "name": "석촌호수"},
               "bounds": {"minlat": 37.507, "minlon": 127.098, "maxlat": 37.511, "maxlon": 127.106}}
        self.assertEqual(osm.area_rows([rel], [])[0]["kind"], "lake")

    def test_a_park_in_both_sources_is_offered_once_with_its_outline(self):
        p = way(1, {"leisure": "park", "name": "반포 근린공원"}, square(37.5, 127.0, 3))
        places.db_path().unlink(missing_ok=True)
        places.replace_source("data.go.kr", [{"id": "s1", "kind": "park", "name": "반포근린공원", "subtype": "근린공원",
            "lat": 37.5015, "lon": 127.0015, "min_lat": 37.5015, "max_lat": 37.5015, "min_lon": 127.0015,
            "max_lon": 127.0015, "area_m2": 90_000}])
        places.replace_source("osm", osm.area_rows([p]))
        near = places.nearby(37.5015, 127.0015, 1000)
        self.assertEqual(len(near), 1)
        self.assertTrue(near[0]["id"].startswith("osm:"))
        self.assertTrue(places.find("반포근린공원", 37.5, 127.0)[0]["id"].startswith("osm:"))

    def test_river_pieces_join_but_namesakes_far_apart_stay_apart(self):
        a = way(10, {"waterway": "stream", "name": "신천"}, [(37.5, 127.0), (37.5 + 8 * D, 127.0)])
        b = way(11, {"waterway": "stream", "name": "신천"}, [(37.5 + 8.5 * D, 127.0), (37.5 + 16 * D, 127.0)])
        c = way(12, {"waterway": "stream", "name": "신천"}, [(35.8, 128.6), (35.8 + 20 * D, 128.6)])
        short = way(13, {"waterway": "stream", "name": "도랑"}, [(37.6, 127.0), (37.6 + 2 * D, 127.0)])
        rows = osm.river_rows([a, b, c, short])
        self.assertEqual(sorted((r["id"], r["length_m"] // 100) for r in rows),
                         [("osm:river:10", 17), ("osm:river:12", 22)])


class PlacesWithShapesTest(unittest.TestCase):
    def setUp(self):
        places.db_path().unlink(missing_ok=True)

    def test_distance_is_to_the_edge_or_line(self):
        river = way(20, {"waterway": "river", "name": "한강"}, [(37.5, 126.9), (37.5, 127.1)])
        park = way(21, {"leisure": "park", "name": "큰공원"}, square(37.51, 127.0, 10))
        places.replace_source("osm", osm.area_rows([park], []) + osm.river_rows([river]))
        near = {p["name"]: p["away_m"] for p in places.nearby(37.503, 127.05, 3000)}
        self.assertAlmostEqual(near["한강"], 333, delta=5)  # to the line, not its middle
        inside = {p["name"]: p["away_m"] for p in places.nearby(37.512, 127.002, 3000)}
        self.assertEqual(inside["큰공원"], 0)


if __name__ == "__main__":
    unittest.main()
