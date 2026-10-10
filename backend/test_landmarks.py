import json
import os
import tempfile
import unittest

import landmarks

TURN = [37.5000, 127.0000]


def node(lat, lon, **tags):
    return {"type": "node", "lat": lat, "lon": lon, "tags": tags}


def way(points, **tags):
    return {"type": "way", "geometry": [{"lat": a, "lon": b} for a, b in points], "tags": tags}


class PickTest(unittest.TestCase):
    def test_named_junction_beats_a_nearer_store(self):
        els = [node(37.50002, 127.0, name="GS25 역삼점", shop="convenience"),
               node(37.50030, 127.0, name="역삼역사거리", highway="traffic_signals")]
        self.assertEqual({"name": "역삼역사거리", "kind": "junction"}, landmarks.pick(TURN, els))

    def test_too_far_is_ignored(self):
        els = [node(37.5010, 127.0, name="먼사거리", highway="traffic_signals")]  # ~110 m
        self.assertIsNone(landmarks.pick(TURN, els))

    def test_station_gets_its_suffix(self):
        els = [node(37.5008, 127.0, name="강남", railway="station")]
        self.assertEqual("강남역", landmarks.pick(TURN, els)["name"])

    def test_park_measured_to_its_edge_not_its_middle(self):
        # A big park whose edge runs 20 m from the turn.
        edge = [[37.49982, 126.995], [37.49982, 127.005], [37.490, 127.005], [37.490, 126.995], [37.49982, 126.995]]
        self.assertEqual("park", landmarks.pick(TURN, [way(edge, name="큰공원", leisure="park")])["kind"])

    def test_clipped_geometry_with_gaps(self):
        w = {"type": "way", "geometry": [None, {"lat": 37.50001, "lon": 126.9999}, {"lat": 37.50001, "lon": 127.0001}],
             "tags": {"name": "작은다리", "bridge": "yes"}}
        self.assertEqual("bridge", landmarks.pick(TURN, [w])["kind"])


class LookupTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["BJ_DATA_DIR"] = self.dir.name

    def tearDown(self):
        del os.environ["BJ_DATA_DIR"]
        self.dir.cleanup()

    def test_cached_after_first_lookup(self):
        calls = []

        def fetch(url, q):
            calls.append(q)
            return json.dumps({"elements": [node(37.50001, 127.0, name="한사거리", highway="traffic_signals")]}).encode()

        pts = [TURN, [37.51, 127.01]]
        first = landmarks.lookup(pts, fetch)
        self.assertEqual("한사거리", first[0]["name"])
        self.assertIsNone(first[1])
        self.assertEqual(first, landmarks.lookup(pts, fetch))
        self.assertEqual(1, len(calls))

    def test_overpass_down_caches_nothing(self):
        def down(url, q):
            raise OSError("busy")
        self.assertEqual([None], landmarks.lookup([TURN], down))
        ok = lambda url, q: json.dumps({"elements": []}).encode()
        self.assertEqual([None], landmarks.lookup([TURN], ok))


if __name__ == "__main__":
    unittest.main()
