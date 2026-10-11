import os
import tempfile
import unittest

import routes


class RouteCacheTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["BJ_DATA_DIR"] = self.dir.name
        self.calls = []
        self.real = routes._tmap_pedestrian

        def fake(start, end, via=None):
            self.calls.append((start, end, via))
            if end == [37.6, 127.1]:
                raise routes.NoRoute("이 두 지점 사이의 걷는 길을 찾지 못했어요.")
            return {"distance_m": 120, "points": [list(start), [37.5005, 127.0005], list(end)]}
        routes._tmap_pedestrian = fake

    def tearDown(self):
        routes._tmap_pedestrian = self.real
        del os.environ["BJ_DATA_DIR"]
        self.dir.cleanup()

    def test_same_walk_asked_twice_costs_one_request(self):
        charged = []
        a, b = [37.5, 127.0], [37.501, 127.001]
        first = routes.pedestrian(a, b, on_miss=lambda: charged.append(1))
        # A few metres off is the same request.
        again = routes.pedestrian([37.50001, 127.00002], b, on_miss=lambda: charged.append(1))
        self.assertEqual(first, again)
        self.assertEqual(1, len(self.calls))
        self.assertEqual(1, len(charged))  # cache hits are free

    def test_the_way_back_is_the_way_there_reversed(self):
        a, b = [37.5, 127.0], [37.501, 127.001]
        there = routes.pedestrian(a, b)
        back = routes.pedestrian(b, a)
        self.assertEqual(1, len(self.calls))
        self.assertEqual(there["points"][::-1], back["points"])

    def test_no_route_is_remembered(self):
        for _ in range(3):
            with self.assertRaises(routes.NoRoute):
                routes.pedestrian([37.5, 127.0], [37.6, 127.1])
        self.assertEqual(1, len(self.calls))

    def test_daily_cap_only_counts_real_requests(self):
        a, b = [37.5, 127.0], [37.501, 127.001]
        routes.pedestrian(a, b, on_miss=lambda: routes.charge(99))
        for _ in range(routes.PER_USER_DAILY + 5):
            routes.pedestrian(a, b, on_miss=lambda: routes.charge(99))  # all hits: never refused


class PoiCacheTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["BJ_DATA_DIR"] = self.dir.name
        self.calls = 0
        self.real = routes._tmap_poi

        def fake(words, near, radius_km=30):
            self.calls += 1
            return [{"name": "금정역", "lat": 37.3722, "lon": 126.9434, "away_m": 0, "address": "군포"}]
        routes._tmap_poi = fake

    def tearDown(self):
        routes._tmap_poi = self.real
        del os.environ["BJ_DATA_DIR"]
        self.dir.cleanup()

    def test_same_words_nearby_reuse_with_fresh_distances(self):
        one = routes.poi_search("금정역", [37.3628, 126.9369])
        two = routes.poi_search(" 금정역 ", [37.3640, 126.9390])  # ~300 m away, same words
        self.assertEqual(1, self.calls)
        self.assertNotEqual(one[0]["away_m"], two[0]["away_m"])

    def test_unreachable_tmap_is_not_remembered(self):
        routes._tmap_poi = lambda *a, **k: None
        self.assertEqual([], routes.poi_search("없는곳", [37.36, 126.93]))
        routes._tmap_poi = lambda *a, **k: [{"name": "x", "lat": 37.36, "lon": 126.93, "away_m": 0, "address": ""}]
        self.assertEqual(1, len(routes.poi_search("없는곳", [37.36, 126.93])))


if __name__ == "__main__":
    unittest.main()
