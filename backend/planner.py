"""Courses from a read request (docs/NL_COURSES.md section 5): "한강 따라 5km", "서울숲 두 바퀴",
"남산까지 왕복", "공원 돌아서 30분".

The phone reads the sentence (on-device model + rules) and sends the reading - distance, shape,
themes, named places with roles. Here the places are looked up in places.db and a course is
built around them. Cost is the point: the shape is worked out with plain geometry first, and
TMAP is asked for the walking route only once or twice per course.

Everything that talks to the outside (routing, place lookups, the usage charge) is passed in,
so the planning itself is tested without the network.
"""

import json
import math
import time
from dataclasses import dataclass, field

import parkpaths
import places
import routes
import shade

M = places.M_PER_DEG
DETOUR = 1.25          # walking route vs straight line, as in routes.py
NEAR_KM = {"START": 30, "DEST": 50, "AROUND": 30}  # how far a named place may be
CACHE_S = 7 * 24 * 3600


class PlanError(routes.RouteError):
    pass


@dataclass
class Plan:
    courses: list = field(default_factory=list)   # {title, distance_m, points, via, shade}
    notes: list = field(default_factory=list)     # things the user should know, in Korean
    sources: set = field(default_factory=set)
    sun: dict | None = None                       # {elevation, azimuth} at planning time
    prefer_shade: bool = False

    def to_json(self):
        return {"courses": self.courses, "notes": self.notes, "sources": sorted(self.sources),
                "sun": self.sun, "prefer_shade": self.prefer_shade}


# --- geometry on a local flat plane around a reference point ---------------------------------

class Plane:
    def __init__(self, ref):
        self.lat0, self.lon0 = ref
        self.kx = M * math.cos(math.radians(ref[0]))

    def xy(self, p):
        return ((p[1] - self.lon0) * self.kx, (p[0] - self.lat0) * M)

    def ll(self, x, y):
        return [round(self.lat0 + y / M, 7), round(self.lon0 + x / self.kx, 7)]


def dist(a, b) -> float:
    return places.distance_m(a[0], a[1], b[0], b[1])


def path_length(points) -> float:
    return sum(dist(a, b) for a, b in zip(points, points[1:]))


def nearest_on(line, p, plane):
    """(distance m, segment index, t) of the point of [line] nearest to [p]."""
    px, py = plane.xy(p)
    best = (float("inf"), 0, 0.0)
    for i, (a, b) in enumerate(zip(line, line[1:])):
        (ax, ay), (bx, by) = plane.xy(a), plane.xy(b)
        dx, dy = bx - ax, by - ay
        L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
        d = math.hypot(ax + t * dx - px, ay + t * dy - py)
        if d < best[0]:
            best = (d, i, t)
    return best


def walk(line, seg, t, metres, direction, plane):
    """The point [metres] along [line] from (seg, t), forward (+1) or back (-1), with the unit
    normal there - always to the left of the line's own direction, whichever way we walk, so
    one sign means one bank. Stops at the end of the line; returns (point_xy, normal_xy, metres
    actually walked)."""
    pts = [plane.xy(p) for p in line]
    (ax, ay), (bx, by) = pts[seg], pts[seg + 1]
    cx, cy = ax + t * (bx - ax), ay + t * (by - ay)
    i = seg + 1 if direction > 0 else seg
    walked = 0.0
    nx, ny = -(by - ay), bx - ax
    while 0 <= i < len(pts):
        tx, ty = pts[i][0] - cx, pts[i][1] - cy
        seg_len = math.hypot(tx, ty)
        if seg_len > 0:
            ux, uy = tx / seg_len, ty / seg_len
            nx, ny = (-uy, ux) if direction > 0 else (uy, -ux)
            if walked + seg_len >= metres:
                f = metres - walked
                return (cx + ux * f, cy + uy * f), (nx, ny), metres
            walked += seg_len
        cx, cy = pts[i]
        i += direction
    n = math.hypot(nx, ny) or 1
    return (cx, cy), (nx / n, ny / n), walked


def offset(pt, normal, metres):
    n = math.hypot(*normal) or 1
    return pt[0] + normal[0] / n * metres, pt[1] + normal[1] / n * metres


def bank_offset(river) -> float:
    """How far from the mapped centreline the walkable bank is, roughly. OSM rivers are drawn
    down the middle of the water; a via point there makes TMAP pick a bridge or the far bank."""
    if river["subtype"] == "river" and (river["length_m"] or 0) > 50_000:
        return 350.0   # 한강, 낙동강, 금강...: hundreds of metres wide
    if river["subtype"] == "river":
        return 40.0
    return 15.0


def join(*parts):
    """Concatenates route point lists, dropping the shared joint."""
    out = []
    for part in parts:
        for p in part:
            if not out or out[-1] != p:
                out.append(p)
    return out


def km(m) -> str:
    return f"{m / 1000:.1f}".rstrip("0").rstrip(".") + "km"


# --- the planner -----------------------------------------------------------------------------

class Planner:
    def __init__(self, user_id: int, route=None, find=None, nearby=None, shape=None, charge=None, loops=None,
                 poi=None, paths=None, now=None):
        self.route_fn = route or routes.pedestrian
        self.find = find or places.find
        # Names places.db doesn't have (landmarks, stations, buildings): TMAP place search.
        self.poi = poi or routes.poi_search
        self.nearby = nearby or places.nearby
        self.shape = shape or places.shape
        self.paths = paths or places.park_paths
        # Every TMAP request counts against the user's daily allowance (routes.charge).
        self.charge = charge or (lambda: routes.charge(user_id))
        self.loops = loops or (lambda s, d: routes.loops(s, d, user_id, count=2, route=self.route_fn))
        self.calls = 0
        self.now = now  # planning time, for the sun (tests pass a fixed one)
        self.prefer_shade = False

    def route(self, a, b, via=None, spurs=False):
        """Walking route a -> via -> b. Dead-end detours are cut (a via point at the end of a
        cul-de-sac) unless [spurs] - going into a park and back out is the point of a park tour."""
        self.charge()
        self.calls += 1
        r = self.route_fn(a, b, via)
        return r["points"] if spurs else routes.remove_spurs(r["points"])

    # Entry point.
    def plan(self, start, intent: dict) -> Plan:
        out = Plan()
        target = intent.get("distance_m")
        shape = intent.get("shape") or "LOOP"
        themes = set(intent.get("themes") or [])
        resolved = {}
        for m in intent.get("places") or []:
            role = m.get("role", "AROUND")
            if role in resolved:
                continue
            near = resolved.get("START", {}).get("pt", start)
            limit = NEAR_KM[role] * 1000
            if m.get("id"):
                # A place the app picked from a list (공원 코스): exactly that one.
                hit = places.get(m["id"], tuple(near))
                hits = [hit] if hit else []
            else:
                hits = [h for h in self.find(m["name"], near[0], near[1]) if h["away_m"] <= limit]
            if not hits:
                # Shown by the name the user said: TMAP's listing names are things like
                # "YTN서울타워" for 남산타워.
                hits = [{**h, "name": m["name"], "id": None, "kind": "poi", "source": "TMAP"}
                        for h in self.poi(m["name"], near, NEAR_KM[role]) if h["away_m"] <= limit]
            if not hits:
                out.notes.append(f"'{m['name']}'을(를) 찾지 못했어요.")
                continue
            h = hits[0]
            resolved[role] = {**h, "pt": [h["lat"], h["lon"]]}
            out.sources.add(h["source"])

        origin = resolved["START"]["pt"] if "START" in resolved else start
        if intent.get("flat"):
            out.notes.append("오르막 정보는 아직 반영하지 못했어요.")
        # Shade first when asked for, or by default while the sun is high (docs/SHADE.md).
        elevation, azimuth = shade.sun(origin[0], origin[1], self.now)
        out.sun = {"elevation": round(elevation), "azimuth": round(azimuth)}
        asked = intent.get("shade")
        self.prefer_shade = shade.has_map() and (asked if asked is not None else elevation >= 35)
        out.prefer_shade = self.prefer_shade
        if self.prefer_shade and asked is None:
            out.notes.append(f"지금 해가 높아요 (고도 {round(elevation)}°). 나무 그늘이 많은 길 위주로 골랐어요.")

        count = intent.get("laps")
        repeat = bool(intent.get("repeat") or count)
        if "DEST" in resolved:
            self.to_destination(out, origin, resolved["DEST"], shape, target)
        elif "AROUND" in resolved:
            self.around(out, origin, resolved["AROUND"], shape, target, count)
        elif repeat and not themes & {"RIVER", "COAST"} and (spot := self.lap_place(origin, target or 5000, themes)):
            out.sources.add(spot["source"])
            self.laps(out, origin, spot, target, count)
        elif themes & {"RIVER", "LAKE", "COAST", "PARK"}:
            self.by_theme(out, origin, themes, shape, target or 5000)
        else:
            if "SIGHTS" in themes:
                out.notes.append("명소를 넣은 코스는 아직 준비 중이에요. 거리에 맞춘 순환 코스예요.")
            self.plain_loop(out, origin, target or 3000)
        if not out.courses:
            raise PlanError(out.notes[-1] if out.notes else "알맞은 코스를 찾지 못했어요.")
        if any("shade" in c for c in out.courses):
            out.sources.add(shade.SOURCE)
            if self.prefer_shade:
                # Shadiest first (every course here was already fitted to the distance).
                out.courses.sort(key=lambda c: -c.get("shade", 0))
        return out

    def add(self, out, title, points, via=()):
        course = {"title": title, "distance_m": round(path_length(points)), "points": points, "via": list(via)}
        if shade.has_map():
            course["shade"] = round(shade.ratio(points) * 100)
        out.courses.append(course)

    # "남산까지", "남산까지 왕복"
    def to_destination(self, out, origin, dest, shape, target):
        there = self.route(origin, dest["pt"])
        if shape == "OUT_AND_BACK":
            pts = join(there, there[::-1])
            self.add(out, f"{dest['name']} 왕복", pts, [dest["name"]])
        else:
            pts = there
            self.add(out, f"{dest['name']}까지", pts, [dest["name"]])
        if target and abs(path_length(pts) - target) > 0.3 * target:
            out.notes.append(f"말씀하신 {km(target)}가 아니라 {km(path_length(pts))}예요. 목적지까지의 거리라서요.")

    # "서울숲 한바퀴", "한강 따라 5km", "석촌호수 세 바퀴"
    def around(self, out, origin, place, shape, target, count=None):
        if place["kind"] == "river":
            self.along_river(out, origin, place, shape, target or 5000)
        else:
            self.laps(out, origin, place, target, count)

    def by_theme(self, out, origin, themes, shape, target):
        radius = max(2000, target / 2.2)
        for theme, kinds in (("RIVER", ("river",)), ("LAKE", ("lake",)), ("COAST", ("beach",))):
            if theme in themes:
                found = self.nearby(origin[0], origin[1], radius, kinds=kinds)
                if found:
                    p = found[0]
                    out.sources.add(p["source"])
                    return self.around(out, origin, {**p, "pt": [p["lat"], p["lon"]]}, shape, target)
                out.notes.append({"RIVER": "근처에 강변이 없어요.", "LAKE": "근처에 호수가 없어요.",
                                  "COAST": "근처에 바닷가가 없어요."}[theme])
        if "PARK" in themes:
            return self.park_tour(out, origin, target)
        self.plain_loop(out, origin, target)

    # --- rivers ------------------------------------------------------------------------------

    def along_river(self, out, origin, river, shape, target):
        lines = self.shape(river["id"]) or []
        if not lines:
            raise PlanError(f"{river['name']}의 위치 정보가 없어요.")
        plane = Plane(origin)
        # The piece of the river nearest to the start.
        d, seg, t, line = min((nearest_on(l, origin, plane) + (l,) for l in lines), key=lambda x: x[0])
        bank = bank_offset(river)
        # Which side of the river the start is on: offset via points to that bank.
        (sx, sy) = plane.xy(origin)
        p_xy, normal, _ = walk(line, seg, t, 0, +1, plane)
        side = 1 if (sx - p_xy[0]) * normal[0] + (sy - p_xy[1]) * normal[1] > 0 else -1
        entry = plane.ll(*offset(p_xy, normal, side * min(bank, max(d - 10, 0))))
        approach = dist(origin, entry) * DETOUR
        along = target - 2 * approach
        if along < 1000:
            out.notes.append(f"{river['name']}까지 편도 {km(approach)}라 강변 구간이 짧아요.")
            along = 1000

        # Go the way the river continues for longer - or, preferring shade, the way whose bank
        # has more trees (as long as it goes most of the way).
        best = None
        for direction in (+1, -1):
            q_xy, q_normal, walked = walk(line, seg, t, along / 2, direction, plane)
            trees = 0.0
            if self.prefer_shade:
                probes = [walk(line, seg, t, along / 2 * k / 8, direction, plane) for k in range(1, 9)]
                trees = sum(shade.shaded(*plane.ll(*offset(pq, pn, side * bank))) for pq, pn, _ in probes) / 8
            key = (walked >= 0.8 * along / 2, trees, walked)
            if best is None or key > best[0]:
                best = (key, q_xy, q_normal, walked, direction)
        _, q_xy, q_normal, walked, direction = best
        turn = plane.ll(*offset(q_xy, q_normal, side * bank))

        name = river["name"]
        if shape == "LOOP" and bank <= 40:
            # A stream has paths on both banks: up one side, back down the other.
            far_turn = plane.ll(*offset(q_xy, q_normal, -side * bank))
            far_entry = plane.ll(*offset(p_xy, normal, -side * bank))
            pts = self.route(origin, origin, [entry, turn, far_turn, far_entry])
            if path_length(pts) < 1.4 * target:
                return self.add(out, f"{name} 따라 순환", pts, [name])
        out_pts = self.route(origin, turn, [entry])
        pts = join(out_pts, out_pts[::-1])
        # One correction if the bank path was much longer or shorter than the plan.
        ratio = path_length(pts) / target
        if not 0.85 <= ratio <= 1.15 and walked >= along / 2 - 1:
            q_xy, q_normal, walked = walk(line, seg, t, max(300, along / 2 / ratio), direction, plane)
            turn = plane.ll(*offset(q_xy, q_normal, side * bank))
            out_pts = self.route(origin, turn, [entry])
            pts = join(out_pts, out_pts[::-1])
        self.add(out, f"{name} 따라 왕복", pts, [name])

    # --- parks and lakes ---------------------------------------------------------------------

    def laps(self, out, origin, place, target, count=None):
        """Laps of a park or lake: [count] of them if the user said ("세 바퀴"), else as many as
        fit [target]. The course always starts at [origin] (the user's position or chosen
        start): the way to the park and back is part of it and counts toward the distance."""
        rings = self.shape(place["id"]) or []
        name = place["name"]
        if not rings:
            target = target or 3000
            # Only a point (standard data parks): a loop that starts there.
            pts = self.route(origin, place["pt"])
            loop_m = max(1000, target - 2 * path_length(pts))
            return self.plain_loop(out, place["pt"], loop_m, lead=pts, title=f"{name} 주변 순환", via=[name])
        ring = max(rings, key=len)
        plane = Plane(origin)
        perimeter = path_length(ring)
        d, seg, t = nearest_on(ring, origin, plane)
        if ring[0] == ring[-1]:
            # Start the closed outline at the nearest segment so walking it never runs off the end.
            ring = ring[seg:] + ring[1:seg + 1]
            seg = 0
        if target is None:
            # Only a lap count ("석촌호수 세 바퀴"): whole laps of the outline, however long.
            target = (count or 1) * perimeter + 2 * d * DETOUR
        budget = max(target - 2 * d * DETOUR, 0.3 * target)
        # Laps wanted: as said, else the fewest whole laps of at most the outline that make the
        # distance (5 km on a 3.5 km outline: two laps of a 2.5 km ring, not one short lap).
        laps_wanted = count or max(1, math.ceil(budget / (perimeter * 1.1)))
        per_lap = budget / laps_wanted
        cx = sum(plane.xy(p)[0] for p in ring) / len(ring)
        cy = sum(plane.xy(p)[1] for p in ring) / len(ring)
        # A park bigger than one lap should be: run a smaller ring inside it instead, the outline
        # shrunk toward its middle so one lap is about the distance wanted. TMAP then follows
        # the park's own paths near that ring.
        scale = 1.0
        if place["kind"] == "park" and perimeter > 1.15 * per_lap:
            scale = max(0.3, per_lap / perimeter)
        # Four points around the (scaled) outline, starting at the one nearest the start, nudged
        # onto where the path is: a park's path runs inside its edge (the road is outside), a
        # lake's or beach's path runs outside the water or sand.
        nudge = 15 if place["kind"] == "park" else -20

        def onto_path(p_xy):
            x, y = cx + (p_xy[0] - cx) * scale, cy + (p_xy[1] - cy) * scale
            vx, vy = cx - x, cy - y
            n = math.hypot(vx, vy) or 1
            return plane.ll(x + vx / n * nudge, y + vy / n * nudge)

        def lap_route():
            corners = [onto_path(walk(ring, seg, t, perimeter * k / 4, +1, plane)[0]) for k in range(4)]
            # spurs=True: a closed lap returns to where it began, which the dead-end cutter would
            # take for one long dead end and remove whole.
            return corners, self.route(corners[0], corners[0], corners[1:], spurs=True)

        # Best: the park's own paths (OSM footways), joined into a lap here - it follows the
        # path runners use, and needs no routing request at all.
        on_paths, path_laps = None, None
        lines = self.paths(place["id"]) if place["kind"] == "park" else None
        if lines:
            graph = parkpaths.PathGraph(lines)
            if graph.length() >= 300:
                if self.prefer_shade:
                    on_paths, path_laps = self.shady_laps(graph, ring, budget, count, origin)
                else:
                    on_paths, path_laps = parkpaths.fit_laps(graph, ring, budget, count, origin)
        if on_paths:
            lap = routes.remove_spurs(on_paths, max_spur_m=150)
            corners = [lap[0]]
            lap_m = path_length(lap)
        else:
            corners, lap = lap_route()
            lap_m = path_length(lap)
        # One correction when the laps miss the distance by more than 15%: resize the ring by
        # the miss (only an inner ring can be resized; a full outline is what it is).
        n_est = count or max(1, round(budget / lap_m)) if lap_m > 0 else 1
        if not on_paths and lap_m > 0 and place["kind"] == "park" and abs(n_est * lap_m - budget) > 0.15 * budget:
            new_scale = min(1.0, max(0.3, scale * budget / (n_est * lap_m)))
            if abs(new_scale - scale) > 0.05:
                # Paths inside a park don't scale evenly with the ring: keep whichever lap fits.
                old_scale, old = scale, (corners, lap, lap_m)
                scale = new_scale
                corners, lap = lap_route()
                lap_m = path_length(lap)
                n_new = count or max(1, round(budget / lap_m)) if lap_m > 0 else 1
                if abs(n_new * lap_m - budget) > abs(n_est * old[2] - budget):
                    scale, (corners, lap, lap_m) = old_scale, old
        lead = self.route(origin, corners[0]) if d > 60 else [origin, corners[0]]
        lead_m = path_length(lead)
        # Laps from the real way there and back, not the estimate the lap was sized with.
        n = count or (max(1, round((target - 2 * lead_m) / lap_m)) if lap_m > 0 else 1)
        if 2 * lead_m > 0.5 * target:
            out.notes.append(f"{name}까지 가고 오는 {km(2 * lead_m)}가 거리에 포함돼 있어요.")
        pts = join(lead, *([lap] * n), lead[::-1])
        title = f"{name} {n}바퀴" if n > 1 else f"{name} 한 바퀴"
        self.add(out, title, pts, [name])

    def shady_laps(self, graph, ring, budget, count, origin):
        """Like parkpaths.fit_laps, but a lap under more trees may be up to ~15% off the
        distance: 40 points of shade weigh like 16% of distance."""
        best = None
        for pts in parkpaths.candidate_laps(graph, ring, origin):
            m = path_length(pts)
            n = count or min(range(1, 11), key=lambda k: abs(k * m - budget))
            score = abs(n * m - budget) / budget - 0.4 * shade.ratio(pts)
            if best is None or score < best[0]:
                best = (score, pts, n)
        return (best[1], best[2]) if best else (None, None)

    def park_canopy(self, p) -> float:
        return shade.canopy(self.shape(p["id"]) or [])

    def lap_place(self, origin, target, themes):
        """"공원 뺑뺑 5km" with no park named: a nearby park (or lake) worth lapping. A lap of at
        least 600 m; a bigger one (1 km+) is worth a few hundred metres more walking."""
        kinds = ("lake",) if "LAKE" in themes and "PARK" not in themes else ("park", "lake")
        best = None
        for p in self.nearby(origin[0], origin[1], 2500, kinds=kinds)[:15]:
            rings = self.shape(p["id"]) or []
            lap_m = path_length(max(rings, key=len)) if rings else 0
            if lap_m < 600:
                continue
            # Bigger laps are nicer than tiny ones; but one much longer than the distance has to
            # be shrunk into an inner ring, which suits a compact park better than a long strip.
            score = p["away_m"] - (400 if lap_m >= 1000 else 0) + (400 if lap_m > 1.5 * target else 0)
            if self.prefer_shade:
                score -= 800 * shade.canopy(rings)  # a wooded park is worth a longer walk
            if best is None or score < best[0]:
                best = (score, p)
        return {**best[1], "pt": [best[1]["lat"], best[1]["lon"]]} if best else None

    def park_tour(self, out, origin, target):
        """"공원 돌아서 5km": a loop through one to three parks that fits the distance."""
        found = self.nearby(origin[0], origin[1], max(1500, target / 2.2), kinds=("park",))
        found = sorted(found, key=lambda p: -(p["area_m2"] or 0))[:12]
        if not found:
            out.notes.append("근처에 공원이 없어서 거리에 맞춘 순환 코스예요.")
            return self.plain_loop(out, origin, target)
        combos = []
        for a in found:
            for b in [None] + found:
                for c in [None] + found:
                    stops = [p for p in (a, b, c) if p]
                    if len({p["id"] for p in stops}) < len(stops) or (b is None and c is not None):
                        continue
                    # Stops this close are one park (the two sources name it slightly differently).
                    if any(dist([p["lat"], p["lon"]], [q["lat"], q["lon"]]) < 400
                           for i, p in enumerate(stops) for q in stops[i + 1:]):
                        continue
                    pts = [origin] + [[p["lat"], p["lon"]] for p in stops] + [origin]
                    est = path_length(pts) * DETOUR
                    size = sum(math.log10(max(p["area_m2"] or 1, 10)) for p in stops)
                    trees = sum(self.park_canopy(p) for p in stops) / len(stops) if self.prefer_shade else 0
                    combos.append((abs(est - target) / target - 0.03 * size - 0.25 * trees, est, stops))
        combos.sort(key=lambda c: c[0])
        if not combos or combos[0][1] < 0.6 * target:
            # Even the farthest parks are close: laps of the biggest instead.
            p = max(found, key=lambda p: p["area_m2"] or 0)
            out.sources.add(p["source"])
            return self.laps(out, origin, {**p, "pt": [p["lat"], p["lon"]]}, target)
        # The estimate is from park centres; the real route (in and out of each park) can come out
        # quite different. If it misses by more than a quarter, learn how far off estimates run
        # around here and route the combination that this corrected estimate fits best.
        def attempt(stops):
            pts = self.route(origin, origin, [[p["lat"], p["lon"]] for p in stops], spurs=True)
            return abs(path_length(pts) - target) / target, pts, stops

        _, est, stops = combos[0]
        best = attempt(stops)
        if best[0] > 0.25:
            ratio = path_length(best[1]) / est
            rest = [c for c in combos[1:] if c[2] != stops]
            if rest:
                _, _, stops2 = min(rest, key=lambda c: abs(c[1] * ratio - target))
                second = attempt(stops2)
                best = min(best, second, key=lambda b: b[0])
        _, pts, stops = best
        for p in stops:
            out.sources.add(p["source"])
        names = [p["name"] for p in stops]
        self.add(out, "·".join(names) + " 순환", pts, names)

    # --- no place at all ---------------------------------------------------------------------

    def plain_loop(self, out, origin, target, lead=None, title=None, via=()):
        try:
            found = self.loops(origin, target)
        except routes.RouteError as e:
            out.notes.append(str(e))
            return
        for c in found[:2]:
            pts = join(lead, c["points"], lead[::-1]) if lead else c["points"]
            self.add(out, title or f"순환 {km(path_length(pts))}", pts, via)


# --- cache: the same request from the same spot within a week reuses the answer --------------

_cache: dict = {}


def cached_plan(start, intent, planner: Planner) -> dict:
    # Whether the sun is high is part of the request: the same words make a shadier course at noon.
    key = (round(start[0], 3), round(start[1], 3), json.dumps(intent, sort_keys=True, ensure_ascii=False),
           shade.strong_sun(start[0], start[1], planner.now))
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < CACHE_S:
        return hit[1]
    result = planner.plan(start, intent).to_json()
    _cache[key] = (time.time(), result)
    if len(_cache) > 2000:
        for k in sorted(_cache, key=lambda k: _cache[k][0])[:500]:
            del _cache[k]
    return result
