"""python -m unittest test_routes"""

import unittest

import routes

# Trimmed real response shape (반포한강공원 -> 서래섬).
SAMPLE = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature", "geometry": {"type": "Point", "coordinates": [126.996, 37.5115]},
         "properties": {"totalDistance": 649, "totalTime": 541, "turnType": 200}},
        {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[126.996, 37.5115], [126.995, 37.511]]},
         "properties": {}},
        {"type": "Feature", "geometry": {"type": "Point", "coordinates": [126.995, 37.511]},
         "properties": {"turnType": 13, "description": "우회전 후 28m 이동"}},
        {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[126.995, 37.511], [126.9907, 37.5079]]},
         "properties": {}},
    ],
}


class RoutesTest(unittest.TestCase):
    def test_parse_joins_line_segments(self):
        r = routes.parse_tmap(SAMPLE)
        self.assertEqual(r["distance_m"], 649)
        # Lat/lon order flipped from GeoJSON, shared endpoint not duplicated.
        self.assertEqual(r["points"], [[37.5115, 126.996], [37.511, 126.995], [37.5079, 126.9907]])

    def test_empty_response_is_a_route_error(self):
        with self.assertRaises(routes.RouteError):
            routes.parse_tmap({"features": []})

    def test_daily_cap(self):
        routes._usage.clear()
        for _ in range(routes.PER_USER_DAILY):
            routes.charge(1)
        with self.assertRaises(routes.RouteError):
            routes.charge(1)
        routes.charge(2)  # other users unaffected


def _dist(a, b):
    kx = routes.M_PER_DEG * routes.math.cos(routes.math.radians(a[0]))
    return routes.math.hypot((b[1] - a[1]) * kx, (b[0] - a[0]) * routes.M_PER_DEG)


class LoopTest(unittest.TestCase):
    START = [37.5115, 126.996]

    def test_waypoints_lie_on_a_circle_through_start(self):
        r = 500.0
        wps = routes.circle_waypoints(self.START, r, theta=1.0)
        self.assertEqual(len(wps), 3)
        # The centre is r from start; every waypoint is r from the centre, i.e. at most 2r out.
        for p in wps:
            self.assertLessEqual(_dist(self.START, p), 2 * r + 1)
        # Opposite point of the circle (the middle waypoint) is the farthest: 2r away.
        self.assertAlmostEqual(_dist(self.START, wps[1]), 2 * r, delta=2)

    def test_overlap_detects_out_and_back(self):
        out = [[self.START[0] + i * 50 / routes.M_PER_DEG, self.START[1]] for i in range(21)]
        out_and_back = out + out[::-1][1:]
        self.assertGreater(routes.overlap_ratio(out_and_back), 0.4)
        square = routes.circle_waypoints(self.START, 400, 0.0, n=12) + [self.START]
        self.assertLess(routes.overlap_ratio([self.START] + square), 0.05)

    def test_remove_spurs_cuts_dead_end_stubs(self):
        def p(e, n):
            kx = routes.M_PER_DEG * routes.math.cos(routes.math.radians(self.START[0]))
            return [self.START[0] + n / routes.M_PER_DEG, self.START[1] + e / kx]
        # East 500 m, with a 150 m stub north and straight back at 250 m, then on east.
        main1 = [p(x, 0) for x in range(0, 251, 25)]
        stub = [p(250, y) for y in range(25, 151, 25)] + [p(250, y) for y in range(125, 0, -25)]
        main2 = [p(x, 0) for x in range(250, 501, 25)]
        cleaned = routes.remove_spurs(main1 + stub + main2)
        self.assertAlmostEqual(routes.route_length(cleaned), 500, delta=5)
        # A genuine loop (ends where it started, far along the path) is left alone.
        loop = routes.circle_waypoints(self.START, 400, 0.0, n=16)
        ring = [self.START] + loop + [self.START]
        self.assertEqual(len(routes.remove_spurs(ring)), len(ring))

    def _fake_router(self, stretch):
        """Pretends the walking route is the polygon through the waypoints, [stretch] times longer
        than that (as streets are)."""
        def route(start, end, via):
            pts = [start] + via + [end]
            # Stretch by pushing waypoints outwards from start.
            stretched = [start] + [[start[0] + (p[0] - start[0]) * stretch, start[1] + (p[1] - start[1]) * stretch] for p in via] + [end]
            return {"distance_m": None, "points": stretched}
        return route

    def test_loops_hit_the_target_distance(self):
        routes._usage.clear()
        result = routes.loops(self.START, 5000, user_id=1, seed=7, route=self._fake_router(1.6))
        self.assertTrue(1 <= len(result) <= 3)
        for c in result:
            self.assertAlmostEqual(c["distance_m"], 5000, delta=5000 * 0.12)

    def test_no_loops_is_an_error(self):
        routes._usage.clear()
        def always_fails(start, end, via):
            raise routes.RouteError("no")
        with self.assertRaises(routes.RouteError):
            routes.loops(self.START, 5000, user_id=1, route=always_fails)

    def test_a_route_with_no_length_is_skipped_not_a_crash(self):
        # The router going out and back on one street: nothing left after the spurs are cut.
        routes._usage.clear()
        def there_and_back(start, end, via):
            return {"distance_m": None, "points": [start, via[0], start]}
        with self.assertRaises(routes.RouteError):
            routes.loops(self.START, 5000, user_id=1, route=there_and_back)


if __name__ == "__main__":
    unittest.main()
