"""python -m unittest test_parkpaths"""

import unittest

import parkpaths

D = 0.001  # ~111 m north, ~88 m east at this latitude
LAT, LON = 37.5, 127.0


def p(i, j):
    """Grid point i steps north, j steps east."""
    return [round(LAT + i * D, 6), round(LON + j * D, 6)]


# A park 600 m x 480 m whose paths are a 7 x 6 grid (every path meets the next at a shared
# vertex), plus a separate scrap of path that joins nothing.
GRID = [[p(i, j) for j in range(6)] for i in range(7)] + [[p(i, j) for i in range(7)] for j in range(6)]
SCRAP = [[p(20, 20), p(20, 21)]]
RING = [p(0, 0), p(0, 5), p(6, 5), p(6, 0), p(0, 0)]


class ParkPathsTest(unittest.TestCase):
    def setUp(self):
        self.g = parkpaths.PathGraph(GRID + SCRAP)

    def test_graph_keeps_only_the_connected_network(self):
        self.assertNotIn(tuple(p(20, 20)), self.g.adj)
        self.assertEqual(len(self.g.adj), 42)

    def test_outline_lap_runs_on_the_edge_paths_and_closes(self):
        lap = parkpaths.lap(self.g, RING, 1.0, start_near=p(0, 0))
        self.assertEqual(lap[0], lap[-1])
        self.assertAlmostEqual(parkpaths.path_length(lap), 2 * (6 * 111.2 + 5 * 88.2), delta=60)
        on_edge = [q for q in lap if q[0] in (LAT, round(LAT + 6 * D, 6)) or q[1] in (LON, round(LON + 5 * D, 6))]
        self.assertEqual(len(on_edge), len(lap))

    def test_smaller_rings_give_shorter_laps_inside(self):
        outer = parkpaths.path_length(parkpaths.lap(self.g, RING, 1.0))
        inner = parkpaths.path_length(parkpaths.lap(self.g, RING, 0.55))
        self.assertLess(inner, outer * 0.75)

    def test_fit_laps_picks_ring_and_count_together(self):
        lap, n = parkpaths.fit_laps(self.g, RING, 4000)
        self.assertAlmostEqual(n * parkpaths.path_length(lap), 4000, delta=500)
        lap3, n3 = parkpaths.fit_laps(self.g, RING, 4000, count=3)
        self.assertEqual(n3, 3)

    def test_no_paths_no_lap(self):
        self.assertIsNone(parkpaths.lap(parkpaths.PathGraph(SCRAP), RING, 1.0))


if __name__ == "__main__":
    unittest.main()
