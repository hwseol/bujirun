"""python -m unittest test_waterside"""

import os
import tempfile
import unittest

import waterside


def way(id, tags, points):
    return {"type": "way", "id": id, "tags": tags, "geometry": [{"lat": a, "lon": b} for a, b in points]}


# A stream flowing east along lat 37.5055, a path on each bank 20 m away, a road 150 m back, and
# a bridge joining the banks - spread over several 0.01 degree cells.
LAT = 37.5055
ELEMENTS = [
    way(1, {"waterway": "stream", "name": "시험천"}, [(LAT, 126.981 + i * 0.002) for i in range(15)]),
    way(2, {"highway": "footway"}, [(LAT + 0.00018, 126.981 + i * 0.002) for i in range(15)]),
    way(3, {"highway": "footway"}, [(LAT - 0.00018, 126.981 + i * 0.002) for i in range(15)]),
    way(4, {"highway": "residential"}, [(LAT + 0.0014, 126.981 + i * 0.002) for i in range(15)]),
    way(5, {"highway": "secondary", "bridge": "yes"}, [(LAT + 0.0004, 126.995), (LAT - 0.0004, 126.995)]),
]


class PrebuiltCellsTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        os.environ["BJ_DATA_DIR"] = self.dir.name

    def tearDown(self):
        del os.environ["BJ_DATA_DIR"]
        self.dir.cleanup()

    def test_cells_give_the_same_route_as_the_raw_area(self):
        points = [[LAT + 0.0002, 126.983], [LAT + 0.0002, 126.999]]
        raw = waterside.along(points, fetch=lambda url, q: b"x")  # nothing fetched, nothing stored
        self.assertIsNone(raw)
        direct = waterside.along(points, fetch=self._serve)
        wanted = {(i, j) for i in range(3745, 3756) for j in range(12693, 12706)}
        waterside.store_cells(waterside.cells_from(ELEMENTS, wanted))
        built = waterside.along(points, fetch=lambda url, q: self.fail("fetched a prebuilt area"))
        self.assertIsNotNone(direct)
        self.assertEqual(len(direct), len(built))
        for a, b in zip(direct, built):
            self.assertAlmostEqual(a[0], b[0], places=5)
            self.assertAlmostEqual(a[1], b[1], places=5)

    def test_only_paths_by_the_water_are_kept(self):
        cells = waterside.cells_from(ELEMENTS, {(3750, 12698)})
        kinds = {el["tags"].get("highway") or el["tags"].get("waterway") for el in cells[(3750, 12698)]}
        self.assertEqual(kinds, {"footway", "stream"})  # the road 150 m back is dropped

    def test_a_missing_cell_means_not_built(self):
        waterside.store_cells({(3750, 12698): []})
        self.assertEqual(waterside._from_cells(37.50, 126.98, 37.51, 126.99), [])
        self.assertIsNone(waterside._from_cells(37.50, 126.98, 37.52, 126.99))

    @staticmethod
    def _serve(url, q):
        import json
        return json.dumps({"elements": ELEMENTS}).encode()


if __name__ == "__main__":
    unittest.main()
