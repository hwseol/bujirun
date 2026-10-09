"""Have Claude write realistic course requests with their readings - the one-time, paid step of
docs/NL_COURSES.md section 6. Everything after this (training, the phone) is free.

The key is read from ANTHROPIC_API_KEY or ~/.secrets/anthropic_api_key.txt (never the repo).

    python generate.py preview                      # print one request; no key, no cost
    python generate.py sample 2 data/sample.jsonl   # 2 requests right away (cents), to check quality
    python generate.py batch train 400 data/claude_train.jsonl   # batch, 50% off, ~1 hour
    python generate.py batch eval 10 data/claude_eval.jsonl      # held-out set, other instructions

Each request asks for 50 examples for one combination of who is asking, where, in what style
and about what, so the set covers far more than templates can. Examples that don't check out
(a labelled span that isn't in the sentence, unknown values) are dropped, not repaired.
"""

import json
import os
import random
import sys
import time
from pathlib import Path

import anthropic

from schema import ACTIVITIES, MAX_LEN, SHAPES, THEMES, normalize, validate

MODEL = "claude-opus-5-5"
PER_REQUEST = 50

SYSTEM = f"""You write training data for 부지런, a Korean running and walking app. Users type (or
dictate) what course they want, in Korean, and a tiny on-device model must read it. You write
the requests real users would type, each with its correct reading.

Reading rules (follow exactly; a model learns from them):
- distance: the exact substring with the number and unit as written ("5km", "5키로", "오 킬로",
  "하프", "3.5킬로미터"), or null. time: same for durations ("30분", "한 시간 반", "1시간30분").
- places: named places the planner must look up, each with the exact substring of its name
  (no particle: "남산" not "남산까지") and a role:
  - START: where the course begins - "X에서 출발/시작", "X부터", "X에서부터", or "X에서" when a
    destination follows ("X에서 Y까지").
  - DEST: where it goes - "Y까지", "Y로 가는", "Y 찍고", "Y 갔다가".
  - AROUND: run in, around or along it - "X 한바퀴", "X 따라", "X 근처", "X에서 30분" (no
    destination). Named rivers and streams are AROUND places ("한강 따라" -> 한강).
  Never places: where the user is now (집, 회사, 숙소, 호텔, 여기, 학교 when it means "from
  here"), generic words (공원, 강변, 바다, 동네, 근처 공원), or things that aren't destinations.
  Nicknames and short forms are places as written ("올팍", "뚝섬", "반포").
- shape: OUT_AND_BACK for 왕복 / 갔다가 돌아오기 / 찍고 오기 / 반환, also along a river and back;
  ONE_WAY when it ends at a destination and doesn't come back ("광화문까지", "회사에서 집까지");
  otherwise LOOP (also when nothing is said).
- themes ({", ".join(THEMES)}): kinds of scenery the user wants - said in words ("강변으로" ->
  RIVER, "공원 위주로" -> PARK, "바닷가" -> COAST, "호수" -> LAKE, "구경하면서"/"명소" -> SIGHTS)
  or implied by an AROUND place's kind (올림픽공원 -> PARK, 양재천 -> RIVER, 석촌호수 -> LAKE,
  해운대 -> COAST, 경복궁 -> SIGHTS). A DEST or START place implies no theme.
- activity: RUN (달리기, 뛰기, 러닝, 조깅, 인터벌, 페이스...), WALK (산책, 걷기, 산보, 걸어서...), or
  NONE when the request doesn't say.
- flat: true only when they want to avoid hills (평지, 오르막 없이, 언덕 피해서, 무릎 아파서 평평한).

Write sentences the way Koreans actually type on a phone: spacing mistakes, missing particles,
abbreviations (ㄱㄱ, ㅇㅇ, 5k), typos, 반말 and 존댓말, voice-input run-ons, emoji-free. Keep each
under {MAX_LEN} characters. Vary heavily: no two examples may share a sentence pattern. Use real
Korean place names that fit the region given, including small local ones (동네 공원, 하천, 산책로),
not only famous landmarks. Some requests have no place, distance or time at all; that's fine."""

# The held-out set is written to the same rules but with a different emphasis, so a model that
# only learned the training set's habits shows up as a lower score.
EVAL_NOTE = """This batch is an evaluation set. Write requests as a different, careful person
would: favour longer and more unusual phrasings, mixed-up word order, and edge cases where the
rules above decide between close readings (X에서 with and without a destination, a place nickname,
a stream name, a request that only names scenery)."""

WHO = [
    "직장인, 출근 전 30분", "퇴근길 직장인", "대학생", "육아 중인 부모 (유모차, 아이와)", "60대 산책러",
    "마라톤 준비하는 러너", "러닝 막 시작한 초보", "여행 중인 관광객 (숙소 근처)", "출장 온 사람",
    "반려견 산책", "다이어트 중", "무릎이 안 좋은 사람", "야간 러너", "주말 장거리 러너", "인터벌 훈련하는 사람",
    "점심시간 직장인", "학생 (학교 근처)", "외국인 (어색한 한국어)", "음성으로 말하는 사람", "말이 아주 짧은 사람",
]
WHERE = [
    "서울 강남·서초", "서울 송파·강동", "서울 마포·서대문", "서울 영등포·여의도", "서울 성동·광진",
    "서울 종로·중구", "서울 노원·도봉", "서울 관악·동작", "서울 은평", "경기 성남·분당", "경기 수원",
    "경기 고양·일산", "경기 용인", "인천 송도", "부산 해운대·수영", "부산 서면·동래", "대구", "대전",
    "광주", "울산 태화강", "제주", "강릉·속초", "경주", "전주", "춘천",
]
FOCUS = [
    "distance goals", "time goals", "a destination (one way)", "out and back to a landmark",
    "running along a river or stream", "parks", "sightseeing on foot", "starting somewhere specific",
    "avoiding hills", "beach or lake", "a mix of everything", "vague requests with little detail",
]

EXAMPLE_SCHEMA = {
    "type": "object",
    "properties": {
        "text": {"type": "string"},
        "distance": {"type": ["string", "null"]},
        "time": {"type": ["string", "null"]},
        "places": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"name": {"type": "string"}, "role": {"type": "string", "enum": ["START", "DEST", "AROUND"]}},
                "required": ["name", "role"],
                "additionalProperties": False,
            },
        },
        "shape": {"type": "string", "enum": SHAPES},
        "themes": {"type": "array", "items": {"type": "string", "enum": THEMES}},
        "activity": {"type": "string", "enum": ACTIVITIES},
        "flat": {"type": "boolean"},
    },
    "required": ["text", "distance", "time", "places", "shape", "themes", "activity", "flat"],
    "additionalProperties": False,
}
OUTPUT = {"format": {"type": "json_schema", "schema": {
    "type": "object",
    "properties": {"examples": {"type": "array", "items": EXAMPLE_SCHEMA}},
    "required": ["examples"],
    "additionalProperties": False,
}}}


def request_params(rng: random.Random, kind: str) -> dict:
    who, where, focus = rng.choice(WHO), rng.choice(WHERE), rng.choice(FOCUS)
    prompt = (f"Write {PER_REQUEST} requests. Who: {who}. Region: {where}. Mostly about: {focus}, "
              f"but mix in other kinds too.")
    if kind == "eval":
        prompt = EVAL_NOTE + "\n\n" + prompt
    return {
        "model": MODEL,
        "max_tokens": 32000,
        # The rules are fixed and long; cache them across the batch's requests.
        "system": [{"type": "text", "text": SYSTEM, "cache_control": {"type": "ephemeral"}}],
        "messages": [{"role": "user", "content": prompt}],
        "output_config": {**OUTPUT, "effort": "medium"},
    }


def to_example(raw: dict) -> dict | None:
    """Claude's reading -> schema.py's span format; None if anything doesn't check out."""
    text = normalize(raw["text"])
    if not text or len(text) > MAX_LEN:
        return None
    spans = []

    def place(sub: str, kind: str) -> bool:
        sub = normalize(sub)
        start = text.find(sub)
        if not sub or start < 0:
            return False
        # A later span with the same text (e.g. two "공원"s) takes the next occurrence.
        while any(s[0] == start for s in spans):
            start = text.find(sub, start + 1)
            if start < 0:
                return False
        spans.append([start, start + len(sub), kind])
        return True

    if raw["distance"] and not place(raw["distance"], "DIST"):
        return None
    if raw["time"] and not place(raw["time"], "TIME"):
        return None
    for p in raw["places"]:
        if not place(p["name"], {"START": "PS", "DEST": "PD", "AROUND": "PA"}[p["role"]]):
            return None
    spans.sort()
    if any(a[1] > b[0] for a, b in zip(spans, spans[1:])):
        return None  # overlapping spans
    ex = {"text": text, "spans": spans, "shape": raw["shape"], "themes": sorted(set(raw["themes"])),
          "activity": raw["activity"], "flat": raw["flat"]}
    try:
        validate(ex)
    except AssertionError:
        return None
    return ex


def client() -> anthropic.Anthropic:
    key = os.environ.get("ANTHROPIC_API_KEY")
    if not key:
        path = Path.home() / ".secrets" / "anthropic_api_key.txt"
        if not path.exists():
            sys.exit(f"No API key: set ANTHROPIC_API_KEY or write it to {path}")
        key = path.read_text(encoding="utf-8").strip()
    return anthropic.Anthropic(api_key=key)


def collect(message, out, seen, stats):
    stats["input"] += message.usage.input_tokens + (message.usage.cache_read_input_tokens or 0) + (message.usage.cache_creation_input_tokens or 0)
    stats["output"] += message.usage.output_tokens
    if message.stop_reason != "end_turn":
        stats["incomplete"] += 1  # max_tokens or refusal: the JSON may be cut, skip it
        return
    text = next(b.text for b in message.content if b.type == "text")
    for raw in json.loads(text)["examples"]:
        ex = to_example(raw)
        if ex is None:
            stats["dropped"] += 1
        elif ex["text"] not in seen:
            seen.add(ex["text"])
            out.write(json.dumps(ex, ensure_ascii=False) + "\n")
            stats["kept"] += 1


def cost(stats, batch: bool) -> str:
    # Claude Opus 5.5: $4 / $20 per million input / output tokens; batches are half price.
    usd = (stats["input"] * 4 + stats["output"] * 20) / 1e6 * (0.5 if batch else 1)
    return f"{stats['input']} in / {stats['output']} out tokens, about ${usd:.2f}"


def main():
    mode = sys.argv[1]
    rng = random.Random(int(os.environ.get("SEED", "7")))
    if mode == "preview":
        print(json.dumps(request_params(rng, "train"), ensure_ascii=False, indent=1)[:6000])
        return

    stats = {"input": 0, "output": 0, "kept": 0, "dropped": 0, "incomplete": 0}
    if mode == "sample":
        n, path = int(sys.argv[2]), sys.argv[3]
        c = client()
        seen = set()
        with open(path, "w", encoding="utf-8") as out:
            for _ in range(n):
                with c.messages.stream(**request_params(rng, "train")) as stream:
                    collect(stream.get_final_message(), out, seen, stats)
        print(stats, cost(stats, batch=False))
        return

    if mode == "batch":
        kind, n, path = sys.argv[2], int(sys.argv[3]), sys.argv[4]
        if kind == "eval":
            rng = random.Random(1000 + rng.randint(0, 999))
        c = client()
        batch = c.messages.batches.create(requests=[
            {"custom_id": f"{kind}-{i}", "params": request_params(rng, kind)} for i in range(n)
        ])
        print(f"batch {batch.id} submitted ({n} requests); waiting")
        while batch.processing_status != "ended":
            time.sleep(60)
            batch = c.messages.batches.retrieve(batch.id)
            print(f"  {batch.request_counts.succeeded} done, {batch.request_counts.processing} processing")
        seen = set()
        with open(path, "w", encoding="utf-8") as out:
            for result in c.messages.batches.results(batch.id):
                if result.result.type == "succeeded":
                    collect(result.result.message, out, seen, stats)
                else:
                    stats["incomplete"] += 1
        print(stats, cost(stats, batch=True))
        return

    sys.exit(__doc__)


if __name__ == "__main__":
    main()
