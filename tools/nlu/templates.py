"""Course requests built from parts, labelled by construction. Free and unlimited, so it's the
bulk of the training data and lets the pipeline be tested before any model-written data
exists. Its weakness is variety (every sentence is a shuffle of the same parts); the
Claude-written data (generate.py) covers how people actually phrase things.

    python templates.py 20000 data/templates.jsonl
"""

import json
import random
import sys

from schema import normalize, validate

# (name, type). Types: park, river, lake, coast, mountain, landmark, station, area.
PLACES = [
    ("올림픽공원", "park"), ("서울숲", "park"), ("남산공원", "park"), ("보라매공원", "park"),
    ("어린이대공원", "park"), ("월드컵공원", "park"), ("하늘공원", "park"), ("여의도공원", "park"),
    ("반포한강공원", "park"), ("뚝섬한강공원", "park"), ("여의도한강공원", "park"), ("망원한강공원", "park"),
    ("선유도공원", "park"), ("북서울꿈의숲", "park"), ("양재시민의숲", "park"), ("용산가족공원", "park"),
    ("올림픽 공원", "park"), ("서울 숲", "park"), ("송도센트럴파크", "park"), ("일산호수공원", "park"),
    ("광교호수공원", "park"), ("분당중앙공원", "park"), ("율동공원", "park"), ("시민공원", "park"),
    ("부산시민공원", "park"), ("대구 수성못", "lake"), ("석촌호수", "lake"), ("호수공원", "park"),
    ("한강", "river"), ("양재천", "river"), ("탄천", "river"), ("중랑천", "river"), ("안양천", "river"),
    ("청계천", "river"), ("불광천", "river"), ("홍제천", "river"), ("성내천", "river"), ("도림천", "river"),
    ("낙동강", "river"), ("금강", "river"), ("태화강", "river"), ("광안리", "coast"), ("해운대", "coast"),
    ("경포대", "coast"), ("송정해수욕장", "coast"), ("협재해변", "coast"), ("이호테우해변", "coast"),
    ("남산", "mountain"), ("북한산", "mountain"), ("인왕산", "mountain"), ("아차산", "mountain"),
    ("남산타워", "landmark"), ("광화문", "landmark"), ("경복궁", "landmark"), ("덕수궁", "landmark"),
    ("동대문", "landmark"), ("잠수교", "landmark"), ("반포대교", "landmark"), ("롯데타워", "landmark"),
    ("시청", "landmark"), ("광안대교", "landmark"), ("첨성대", "landmark"), ("수원화성", "landmark"),
    ("서울역", "station"), ("강남역", "station"), ("잠실역", "station"), ("홍대입구역", "station"),
    ("여의나루역", "station"), ("성수역", "station"), ("판교역", "station"), ("해운대역", "station"),
    ("회사", "area"), ("집", "area"), ("숙소", "area"), ("학교", "area"), ("연남동", "area"),
    ("성수동", "area"), ("망원동", "area"), ("이태원", "area"), ("가로수길", "area"), ("서촌", "area"),
]
PLACE_THEME = {"park": "PARK", "river": "RIVER", "lake": "LAKE", "coast": "COAST", "landmark": "SIGHTS"}
# Words for "where I am" are not places to look up; they mark the start as the current position.
HERE = {"회사", "집", "숙소", "학교"}

DIST = [
    "{n}km", "{n} km", "{n}키로", "{n}킬로", "{n}k", "{n} 키로", "{n}킬로미터", "{n}키로미터",
]
DIST_VALUES = ["1", "2", "3", "4", "5", "6", "7", "8", "10", "12", "15", "2.5", "3.5", "4.5", "1.5"]
KOREAN_KM = ["일", "이", "삼", "사", "오", "육", "칠", "팔", "십"]
METERS = ["500m", "800m", "800미터", "오백미터", "삼천 미터", "오천미터", "1500m"]
NAMED = ["하프", "하프 마라톤", "풀코스"]

TIME = [
    "{n}분", "{n} 분", "삼십분", "사십분", "이십 분", "한 시간", "한시간", "한 시간 반", "한시간반",
    "1시간", "1시간 반", "1시간30분", "두 시간", "반시간", "40분", "열다섯 분", "50분", "90분",
]
TIME_VALUES = ["10", "15", "20", "25", "30", "35", "40", "45", "50", "60"]

THEME_WORDS = {
    "RIVER": ["강변", "강변으로", "강 따라", "강가", "하천 따라", "물길 따라", "천변", "강변길로"],
    "PARK": ["공원", "공원 위주로", "공원 몇 군데", "근처 공원", "숲길", "공원 들러서", "공원 끼고"],
    "LAKE": ["호수", "호수 끼고", "호숫가", "저수지 한바퀴"],
    "COAST": ["바다", "바닷가", "해변", "해안길", "바다 보면서", "해변 따라"],
    "SIGHTS": ["명소", "구경하면서", "관광지", "볼거리 있는 데", "유명한 곳", "명소 들러서"],
}
LOOP_WORDS = ["한바퀴", "한 바퀴", "순환", "순환 코스", "돌아서", "빙 돌아서", "두 바퀴", "돌기"]
OAB_WORDS = ["왕복", "왕복으로", "갔다가 돌아오기", "갔다 오기", "찍고 오기", "찍고 돌아오기", "다녀오기", "반환"]
ONE_WAY_WORDS = ["편도로", "편도", "가는 길", "가기"]
RUN = ["달리기", "뛰기", "뛰고 싶어", "러닝", "조깅", "달릴래", "뛸래", "런닝", "달리고 싶어요", "가볍게 뛰기"]
WALK = ["산책", "걷기", "걸을래", "산책하고 싶어", "걷고 싶어요", "워킹", "산보", "걸어서"]
FLAT = ["평지로", "오르막 없이", "언덕 피해서", "평평한 길로", "오르막 말고", "경사 없는 길"]
FILLERS_FRONT = ["오늘", "퇴근하고", "아침에", "저녁에", "점심시간에", "주말에", "지금", "출근 전에", "새벽에", "밥 먹고"]
FILLERS_BACK = ["코스", "코스 짜줘", "추천해줘", "만들어줘", "부탁해", "ㄱㄱ", "가능?", "어때", "알려줘", "짜 줘요", "해줘"]
ADVERBS = ["가볍게", "천천히", "좀", "그냥", "빡세게", "살살"]


class Builder:
    def __init__(self, rng: random.Random):
        self.rng = rng
        self.text = ""
        self.spans = []

    def add(self, piece: str, kind: str | None = None, sep: str | None = None):
        if self.text:
            # Mostly spaced, sometimes run together as people do on a phone keyboard.
            self.text += sep if sep is not None else (" " if self.rng.random() > 0.12 else "")
        start = len(self.text)
        self.text += piece
        if kind:
            self.spans.append([start, len(self.text), kind])

    def add_place(self, name: str, kind: str, after: str):
        """A place name (the labelled span) immediately followed by its particle or word."""
        self.add(name, kind)
        if after:
            self.text += after if after[0] in "까에으로부" or after in ("로", "을", "를") else " " + after


def distance(rng) -> str:
    r = rng.random()
    if r < 0.7:
        return rng.choice(DIST).format(n=rng.choice(DIST_VALUES))
    if r < 0.82:
        return rng.choice(KOREAN_KM) + rng.choice(["키로", " 키로", "킬로", "km"])
    if r < 0.95:
        return rng.choice(METERS)
    return rng.choice(NAMED)


def time(rng) -> str:
    return rng.choice(TIME).format(n=rng.choice(TIME_VALUES))


def example(rng: random.Random) -> dict:
    b = Builder(rng)
    themes, places = set(), []
    shape = "LOOP"
    activity = "NONE"
    flat = False

    parts = []
    # Where: a destination, a place to run around, a start, or none.
    r = rng.random()
    if r < 0.25:
        name, typ = rng.choice([p for p in PLACES if p[1] != "area"])
        out_and_back = rng.random() < 0.5
        shape = "OUT_AND_BACK" if out_and_back else "ONE_WAY"
        after = rng.choice(["까지", "까지", "로", "에"]) if not out_and_back else rng.choice(["까지", ""])
        follow = rng.choice(OAB_WORDS) if out_and_back else rng.choice(ONE_WAY_WORDS + ["", ""])
        if out_and_back and after == "":
            follow = rng.choice(["찍고 오기", "찍고 돌아오기", "왕복"])
        parts.append(("dest", name, after, follow))
        if rng.random() < 0.3:
            # "X에서 Y까지": with a destination, the place before it is where the course starts.
            start, _ = rng.choice([p for p in PLACES if p[0] != name])
            parts.append(("start", start, rng.choice(["에서", "에서부터", "부터"]), ""))
    elif r < 0.6:
        name, typ = rng.choice([p for p in PLACES if p[1] not in ("area", "station")])
        if typ in PLACE_THEME:
            themes.add(PLACE_THEME[typ])
        after = rng.choice(["에서", "", "", ""])
        follow = "" if after else rng.choice(["따라", "근처", "주변", "한바퀴", "쪽", "돌기", "따라서"])
        parts.append(("around", name, after, follow))
    elif r < 0.7:
        name, typ = rng.choice(PLACES)
        # "X에서" alone means "run at X" (around); it's a start only with 출발/시작 or 부터.
        after, follow = rng.choice([("에서", "출발해서"), ("에서", "출발"), ("에서", "시작해서"),
                                    ("에서부터", ""), ("부터", ""), ("에서부터", "출발"), ("", "출발")])
        parts.append(("start", name, after, follow))

    if rng.random() < 0.45:
        theme = rng.choice(list(THEME_WORDS))
        themes.add(theme)
        parts.append(("word", rng.choice(THEME_WORDS[theme]), None))

    r = rng.random()
    if r < 0.6:
        parts.append(("q", distance(rng), "DIST"))
    elif r < 0.9:
        parts.append(("q", time(rng), "TIME"))
    elif r < 0.95:
        parts.append(("q", distance(rng), "DIST"))
        parts.append(("q", time(rng), "TIME"))

    if shape == "LOOP" and rng.random() < 0.35:
        if rng.random() < 0.25 and not any(p[0] == "dest" for p in parts):
            shape = "OUT_AND_BACK"
            parts.append(("word", rng.choice(["왕복", "왕복으로", "갔다가 돌아오기", "반환"]), None))
        else:
            parts.append(("word", rng.choice(LOOP_WORDS), None))
    if rng.random() < 0.6:
        if rng.random() < 0.6:
            activity = "RUN"
            parts.append(("word", rng.choice(RUN), None))
        else:
            activity = "WALK"
            parts.append(("word", rng.choice(WALK), None))
    if rng.random() < 0.12:
        flat = True
        parts.append(("word", rng.choice(FLAT), None))
    if rng.random() < 0.2:
        parts.append(("word", rng.choice(ADVERBS), None))

    # Word order is free in Korean; keep the verb-ish activity near the end most of the time.
    tail = [p for p in parts if p[0] == "word" and p[1] in RUN + WALK]
    body = [p for p in parts if p not in tail]
    rng.shuffle(body)
    for i, p in enumerate(body):
        if p[0] == "start" and any(q[0] == "dest" for q in body):
            body.pop(i)
            body.insert(next(j for j, q in enumerate(body) if q[0] == "dest"), p)
            break
    parts = body + tail

    if rng.random() < 0.3:
        b.add(rng.choice(FILLERS_FRONT))
    for p in parts:
        if p[0] == "q":
            b.add(p[1], p[2])
        elif p[0] == "word":
            b.add(p[1])
        else:
            role, name, after, follow = p
            if name in HERE:
                # "집에서 출발" is the current position, not a place to look up.
                b.add(name + after)
            else:
                b.add_place(name, {"dest": "PD", "around": "PA", "start": "PS"}[role], after)
            if follow:
                b.add(follow)
    if rng.random() < 0.4:
        b.add(rng.choice(FILLERS_BACK))

    if not b.text:
        return example(rng)
    text = normalize(b.text)
    if text != b.text:  # normalization only lowercases ("5K") and never moves characters
        assert len(text) == len(b.text)
    ex = {"text": text, "spans": b.spans, "shape": shape, "themes": sorted(themes),
          "activity": activity, "flat": flat}
    validate(ex)
    return ex


def main():
    n = int(sys.argv[1])
    out = sys.argv[2]
    rng = random.Random(int(sys.argv[3]) if len(sys.argv) > 3 else 1)
    seen = set()
    with open(out, "w", encoding="utf-8") as f:
        while len(seen) < n:
            ex = example(rng)
            if ex["text"] in seen or len(ex["text"]) > 64:
                continue
            seen.add(ex["text"])
            f.write(json.dumps(ex, ensure_ascii=False) + "\n")
    print(f"{len(seen)} examples -> {out}")


if __name__ == "__main__":
    main()
