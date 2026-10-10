"""Laps along a park's own paths (OpenStreetMap footways inside the park), worked out here
without any routing API.

TMAP given a few points around a park takes whatever links connect them - often a shortcut
across the middle or the road outside. A park's runners follow its own circuit path; this finds
it: points spread around the inside of the outline are snapped to the nearest path, and
consecutive ones are joined along the path network (shortest way). Shrinking the ring toward the
middle gives shorter laps on inner paths, so the lap can be fitted to a distance.
"""

import heapq
import math

M = 111_195.0


def _key(p):
    return (round(p[0], 6), round(p[1], 6))


class PathGraph:
    """Footways as a graph: OSM ways that meet share an exact vertex."""

    def __init__(self, lines):
        self.adj: dict = {}
        for line in lines:
            for a, b in zip(line, line[1:]):
                ka, kb = _key(a), _key(b)
                if ka == kb:
                    continue
                d = _dist(ka, kb)
                self.adj.setdefault(ka, {})[kb] = d
                self.adj.setdefault(kb, {})[ka] = d
        self._keep_largest_component()

    def _keep_largest_component(self):
        seen, best = set(), set()
        for start in self.adj:
            if start in seen:
                continue
            comp, stack = {start}, [start]
            while stack:
                n = stack.pop()
                for m in self.adj[n]:
                    if m not in comp:
                        comp.add(m)
                        stack.append(m)
            seen |= comp
            if len(comp) > len(best):
                best = comp
        self.adj = {n: {m: d for m, d in e.items() if m in best} for n, e in self.adj.items() if n in best}

    def length(self) -> float:
        return sum(d for e in self.adj.values() for d in e.values()) / 2

    def nearest(self, p, within_m=80.0):
        best = min(self.adj, key=lambda n: _dist(n, p), default=None)
        return best if best is not None and _dist(best, p) <= within_m else None

    def shortest(self, a, b):
        """Node path a -> b (Dijkstra)."""
        dist = {a: 0.0}
        prev = {}
        heap = [(0.0, a)]
        while heap:
            d, n = heapq.heappop(heap)
            if n == b:
                break
            if d > dist.get(n, math.inf):
                continue
            for m, w in self.adj[n].items():
                nd = d + w
                if nd < dist.get(m, math.inf):
                    dist[m] = nd
                    prev[m] = n
                    heapq.heappush(heap, (nd, m))
        if b not in dist:
            return None
        path = [b]
        while path[-1] != a:
            path.append(prev[path[-1]])
        return path[::-1]


def _dist(a, b) -> float:
    kx = M * math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot((b[1] - a[1]) * kx, (b[0] - a[0]) * M)


def path_length(points) -> float:
    return sum(_dist(a, b) for a, b in zip(points, points[1:]))


def _drop_backtracks(nodes):
    """A -> B -> A stubs (two snapped points joined through a dead end) are cut."""
    out = []
    for n in nodes:
        if len(out) >= 2 and out[-2] == n:
            out.pop()
        elif not out or out[-1] != n:
            out.append(n)
    return out


def ring_points(ring, scale: float, k: int = 16):
    """[k] points evenly around [ring] (a closed outline), shrunk toward its centre by [scale]."""
    lat0 = sum(p[0] for p in ring) / len(ring)
    lon0 = sum(p[1] for p in ring) / len(ring)
    total = path_length(ring)
    out, walked, i = [], 0.0, 0
    targets = [total * j / k for j in range(k)]
    for t in targets:
        while i < len(ring) - 2 and walked + _dist(ring[i], ring[i + 1]) < t:
            walked += _dist(ring[i], ring[i + 1])
            i += 1
        seg = _dist(ring[i], ring[i + 1]) or 1
        f = (t - walked) / seg
        p = (ring[i][0] + (ring[i + 1][0] - ring[i][0]) * f, ring[i][1] + (ring[i + 1][1] - ring[i][1]) * f)
        out.append((lat0 + (p[0] - lat0) * scale, lon0 + (p[1] - lon0) * scale))
    return out


def lap(graph: PathGraph, ring, scale: float, start_near=None):
    """One closed lap on the park's paths following [ring] shrunk by [scale], starting at the
    point nearest [start_near]; [[lat, lon], ...] or None if the paths can't make one."""
    pts = ring_points(ring, scale)
    if start_near is not None:
        i = min(range(len(pts)), key=lambda j: _dist(pts[j], start_near))
        pts = pts[i:] + pts[:i]
    snapped = []
    for p in pts:
        n = graph.nearest(p)
        if n is not None and (not snapped or snapped[-1] != n):
            snapped.append(n)
    if len(snapped) >= 2 and snapped[0] == snapped[-1]:
        snapped.pop()
    if len(set(snapped)) < 3:
        return None
    nodes = [snapped[0]]
    for a, b in zip(snapped, snapped[1:] + snapped[:1]):
        seg = graph.shortest(a, b)
        if seg is None:
            return None
        nodes += seg[1:]
    nodes = _drop_backtracks(nodes)
    if len(nodes) < 4:
        return None
    return [[n[0], n[1]] for n in nodes]


SCALES = (1.0, 0.85, 0.7, 0.55, 0.4)


def candidate_laps(graph: PathGraph, ring, start_near=None) -> list:
    """Laps on the outline and on smaller rings inside it, distinct ones only."""
    out, seen = [], set()
    for scale in SCALES:
        pts = lap(graph, ring, scale, start_near)
        if pts and round(path_length(pts), -1) not in seen:
            seen.add(round(path_length(pts), -1))
            out.append(pts)
    return out


def best_lap(graph: PathGraph, ring, per_lap_m: float, start_near=None):
    """The lap whose length is closest to [per_lap_m], trying the outline and smaller rings."""
    laps = candidate_laps(graph, ring, start_near)
    return min(laps, key=lambda p: abs(path_length(p) - per_lap_m)) if laps else None


def fit_laps(graph: PathGraph, ring, budget_m: float, count=None, start_near=None):
    """(lap, how many) making [budget_m] best: [count] laps of the best-sized ring if the user
    said how many, else whichever ring and number of laps (up to 10) comes closest - two laps of
    an inner ring can fit 3 km better than one lap of a 2.3 km outline."""
    best = None
    for pts in candidate_laps(graph, ring, start_near):
        m = path_length(pts)
        for n in ([count] if count else range(1, 11)):
            miss = abs(n * m - budget_m)
            if best is None or miss < best[0]:
                best = (miss, pts, n)
    return (best[1], best[2]) if best else (None, None)
