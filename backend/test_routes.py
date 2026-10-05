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


if __name__ == "__main__":
    unittest.main()
