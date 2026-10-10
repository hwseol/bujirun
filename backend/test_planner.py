"""python -m unittest test_planner"""

import math
import os
import tempfile
import unittest
from datetime import datetime, timezone

_tmp = tempfile.TemporaryDirectory()
os.environ["BJ_DATA_DIR"] = _tmp.name

import osm  # noqa: E402
import places  # noqa: E402
import planner  # noqa: E402

D = 0.001
START = [37.500, 127.000]
NIGHT = datetime(2026, 7, 1, 15, 0, tzinfo=timezone.utc)


def way(id, tags, points):
    return {"type": "way", "id": id, "tags": tags, "geometry": [{"lat": a, "lon": b} for a, b in points]}


def square(lat, lon, n):
    return [(lat, lon), (lat, lon + n * D), (lat + n * D, lon + n * D), (lat + n * D, lon), (lat, lon)]


class FakeRouter:
    """Straight lines start -> via... -> end, densified, like a perfectly direct street grid."""
    def __init__(self):
        self.calls = []

    def __call__(self, a, b, via=None):
        self.calls.append((a, b, list(via or [])))
        stops = [a] + list(via or []) + [b]
        pts = []
        for p, q in zip(stops, stops[1:]):
            for k in range(20):
                pts.append([p[0] + (q[0] - p[0]) * k / 20, p[1] + (q[1] - p[1]) * k / 20])
        pts.append(list(b))
        return {"points": pts}


def make(router, poi=lambda name, near, km: [], loops=None):
    # Midnight in Korea: no sun, so no shade preference unless a test asks for it.
    return planner.Planner(user_id=1, route=router, charge=lambda: None, poi=poi, now=NIGHT,
                           waterside_fn=lambda *a, **k: None,
                           loops=loops or (lambda s, d: [{"points": [s, [s[0] + D, s[1]], s]}]))


class PlannerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        places.db_path().unlink(missing_ok=True)
        rows = osm.river_rows([
            # A wide river 1 km north of START, running east-west for 20 km.
            way(1, {"waterway": "river", "name": "큰강"}, [(37.509, 126.90 + i * 0.01) for i in range(21)]),
            # A small stream 300 m south, 6 km long.
            way(2, {"waterway": "stream", "name": "작은천"}, [(37.497, 126.97 + i * 0.01) for i in range(7)]),
        ])
        rows[0]["length_m"] = 60_000  # make the first one count as a wide river
        rows += osm.area_rows([
            way(3, {"natural": "water", "water": "lake", "name": "동그란호수"}, square(37.502, 127.004, 4)),
            way(4, {"leisure": "park", "name": "네모공원"}, square(37.495, 126.995, 3)),
            way(5, {"leisure": "park", "name": "먼공원"}, square(37.512, 127.010, 3)),
        ], [])
        places.replace_source("osm", rows)

    def test_wide_river_out_and_back_on_the_near_bank(self):
        r = FakeRouter()
        plan = make(r).plan(START, {"distance_m": 5000, "shape": "OUT_AND_BACK", "places": [{"name": "큰강", "role": "AROUND"}]})
        self.assertLessEqual(len(r.calls), 2)  # one route, plus at most one length correction
        c = plan.courses[0]
        self.assertEqual(c["title"], "큰강 따라 왕복")
        self.assertAlmostEqual(c["distance_m"], 5000, delta=750)
        entry = r.calls[0][2][0]
        self.assertLess(entry[0], 37.509)  # the via point sits on the start's (south) bank

    def test_stream_loop_goes_up_one_bank_and_down_the_other(self):
        r = FakeRouter()
        plan = make(r).plan(START, {"distance_m": 4000, "shape": "LOOP", "places": [{"name": "작은천", "role": "AROUND"}]})
        self.assertEqual(len(r.calls), 1)
        vias = r.calls[0][2]
        self.assertEqual(len(vias), 5)
        self.assertGreater(vias[0][0], 37.497)  # near (north) bank first
        self.assertGreater(vias[2][0], 37.497)  # still the near bank at the turn
        self.assertLess(vias[3][0], 37.497)     # back along the far bank
        self.assertEqual(plan.courses[0]["title"], "작은천 따라 순환")

    def test_lake_laps_on_the_shore_outside_the_water(self):
        r = FakeRouter()
        plan = make(r).plan(START, {"distance_m": 6000, "places": [{"name": "동그란호수", "role": "AROUND"}]})
        lap_call = next(c for c in r.calls if c[0] == c[1])
        ring = places.shape(places.find("동그란호수", *START)[0]["id"])[0]
        for corner in [lap_call[0]] + lap_call[2]:
            self.assertEqual(places.distance_to_shape(corner[0], corner[1], [ring], closed=True) > 0, True)
        self.assertRegex(plan.courses[0]["title"], r"동그란호수 \d바퀴")

    def test_park_laps_inside_the_park(self):
        r = FakeRouter()
        make(r).plan(START, {"distance_m": 3000, "places": [{"name": "네모공원", "role": "AROUND"}]})
        lap_call = next(c for c in r.calls if c[0] == c[1])
        ring = places.shape(places.find("네모공원", *START)[0]["id"])[0]
        for corner in [lap_call[0]] + lap_call[2]:
            self.assertEqual(places.distance_to_shape(corner[0], corner[1], [ring], closed=True), 0)

    def test_park_with_a_running_track_laps_the_track(self):
        # A ~314 m round track (50 m radius) in the middle of 네모공원.
        oval = [[37.495 + 50 / 111_195 * math.cos(a / 20 * 2 * math.pi),
                 126.995 + 50 / 88_200 * math.sin(a / 20 * 2 * math.pi)] for a in range(21)]
        r = FakeRouter()
        p = make(r)
        p.tracks = lambda pid: [oval]
        plan = p.plan(START, {"distance_m": 3000, "places": [{"name": "네모공원", "role": "AROUND"}], "repeat": True})
        c = plan.courses[0]
        self.assertRegex(c["title"], r"네모공원 트랙 \d+바퀴")
        self.assertFalse(any(call[0] == call[1] for call in r.calls))  # no routing for the lap itself
        laps = c["legs"][1]
        self.assertIn("한 바퀴 0.3km", laps["label"])

    def test_far_park_laps_start_from_the_start_and_count_the_way_there(self):
        r = FakeRouter()
        far_start = [37.480, 127.000]  # ~1.7 km south of 네모공원
        plan = make(r).plan(far_start, {"distance_m": 5000, "places": [{"name": "네모공원", "role": "AROUND"}]})
        c = plan.courses[0]
        self.assertEqual(c["points"][0], far_start)   # from where the user is
        self.assertEqual(c["points"][-1], far_start)  # and back
        self.assertIn("가고 오는", plan.notes[0])
        self.assertAlmostEqual(c["distance_m"], 5000, delta=1300)

    def test_park_bigger_than_the_distance_gets_a_smaller_ring_inside(self):
        r = FakeRouter()
        make(r).plan(START, {"distance_m": 800, "laps": 1, "places": [{"name": "네모공원", "role": "AROUND"}]})
        lap = next(c for c in r.calls if c[0] == c[1])
        ring_pts = [lap[0]] + lap[2]
        self.assertLess(planner.path_length(ring_pts + [lap[0]]), 1000)  # outline is ~1.2 km

    def test_lap_count_is_respected(self):
        plan = make(FakeRouter()).plan(START, {"distance_m": 1000, "laps": 3, "places": [{"name": "동그란호수", "role": "AROUND"}]})
        self.assertEqual(plan.courses[0]["title"], "동그란호수 3바퀴")

    def test_repeat_without_a_name_laps_the_nearest_park(self):
        plan = make(FakeRouter()).plan(START, {"distance_m": 3000, "repeat": True, "themes": ["PARK"]})
        self.assertRegex(plan.courses[0]["title"], "바퀴")
        self.assertEqual(len(plan.courses[0]["via"]), 1)

    def test_destination_one_way_and_out_and_back(self):
        r = FakeRouter()
        one = make(r).plan(START, {"shape": "ONE_WAY", "places": [{"name": "먼공원", "role": "DEST"}]})
        back = make(r).plan(START, {"shape": "OUT_AND_BACK", "places": [{"name": "먼공원", "role": "DEST"}]})
        self.assertEqual(one.courses[0]["title"], "먼공원까지")
        self.assertAlmostEqual(back.courses[0]["distance_m"], 2 * one.courses[0]["distance_m"], delta=5)
        far = make(r).plan(START, {"distance_m": 10000, "shape": "ONE_WAY", "places": [{"name": "먼공원", "role": "DEST"}]})
        self.assertIn("목적지까지의 거리", far.notes[0])

    def test_unknown_names_go_to_place_search_then_give_up_politely(self):
        poi = lambda name, near, km: [{"name": "YTN서울타워", "lat": 37.505, "lon": 127.0, "away_m": 556}]
        plan = make(FakeRouter(), poi=poi).plan(START, {"shape": "OUT_AND_BACK", "places": [{"name": "남산타워", "role": "DEST"}]})
        self.assertEqual(plan.courses[0]["title"], "남산타워 왕복")
        self.assertIn("TMAP", plan.to_json()["sources"])
        lost = make(FakeRouter()).plan(START, {"distance_m": 3000, "places": [{"name": "없는곳", "role": "DEST"}]})
        self.assertIn("'없는곳'을(를) 찾지 못했어요.", lost.notes)
        self.assertEqual(len(lost.courses), 1)  # still a plain loop

    def test_themes_without_names(self):
        plan = make(FakeRouter()).plan(START, {"distance_m": 3000, "themes": ["PARK"]})
        self.assertIn("네모공원", plan.courses[0]["via"])
        coast = make(FakeRouter()).plan(START, {"distance_m": 3000, "themes": ["COAST"]})
        self.assertIn("근처에 바닷가가 없어요.", coast.notes)

    def test_park_tour_never_counts_one_park_twice(self):
        twins = [{"id": "a", "name": "동작주차", "lat": 37.505, "lon": 127.0, "area_m2": 50_000, "source": "x"},
                 {"id": "b", "name": "동작주차공원", "lat": 37.5052, "lon": 127.0001, "area_m2": 52_000, "source": "y"},
                 {"id": "c", "name": "다른공원", "lat": 37.5, "lon": 127.012, "area_m2": 30_000, "source": "x"}]
        p = make(FakeRouter())
        p.nearby = lambda lat, lon, r, kinds=None: twins
        via = p.plan(START, {"distance_m": 3500, "themes": ["PARK"]}).courses[0]["via"]
        self.assertFalse({"동작주차", "동작주차공원"} <= set(via))

    def test_cache_answers_the_same_request_without_routing_again(self):
        planner._cache.clear()
        r = FakeRouter()
        intent = {"distance_m": 3000, "places": [{"name": "네모공원", "role": "AROUND"}]}
        a = planner.cached_plan(START, intent, make(r))
        n = len(r.calls)
        b = planner.cached_plan([START[0] + 0.0001, START[1]], intent, make(r))
        self.assertEqual(a, b)
        self.assertEqual(len(r.calls), n)


if __name__ == "__main__":
    unittest.main()
