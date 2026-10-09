"""What the on-device course-request model reads out of a sentence (docs/NL_COURSES.md section 6).

One example, as stored in the .jsonl data files:

    {"text": "남산까지 5키로 왕복",
     "spans": [[0, 2, "PD"], [5, 8, "DIST"]],   # [start, end) character offsets into text
     "shape": "OUT_AND_BACK", "themes": [], "activity": "NONE", "flat": false}

Span kinds: DIST and TIME cover the number and its unit ("5키로", "한 시간 반"); the app turns
them into values with its rules (IntentParser). PS / PD / PA cover a place name only, no
particle ("남산", not "남산까지"), by its role: start, destination, or run in/around/along.

Named rivers and streams are places too ("한강 따라" -> PA 한강 + theme RIVER): the planner
looks the name up and runs along it. "X에서" alone means "run at X" (PA); it's a start (PS) with
출발/시작, with 부터, or when a destination follows ("X에서 Y까지"). Words for where the user is
now (집, 회사, 숙소, 여기) are never places: the course starts from the current position.

Themes are what the user wants to pass: asked for in words ("강변으로"), or implied by a place
they want to run around ("올림픽공원 한바퀴" -> PARK). A destination doesn't imply a theme.

The Kotlin side (app/.../course/NluModel.kt) must agree with every list below, in order.
"""

SPAN_KINDS = ["DIST", "TIME", "PS", "PD", "PA"]
TAGS = ["O"] + [f"{p}-{k}" for k in SPAN_KINDS for p in ("B", "I")]
SHAPES = ["LOOP", "OUT_AND_BACK", "ONE_WAY"]
THEMES = ["RIVER", "PARK", "LAKE", "COAST", "SIGHTS"]
ACTIVITIES = ["NONE", "RUN", "WALK"]
MAX_LEN = 64  # characters; longer requests are cut (real ones are a line or two)


def normalize(text: str) -> str:
    """Same as NluModel.normalize in the app: lowercase, collapse whitespace."""
    return " ".join(text.lower().split())


def validate(ex: dict) -> None:
    text = ex["text"]
    assert text == normalize(text), f"not normalized: {text!r}"
    assert 0 < len(text) <= MAX_LEN, f"length {len(text)}: {text!r}"
    last_end = 0
    for start, end, kind in sorted(ex["spans"]):
        assert kind in SPAN_KINDS, kind
        assert last_end <= start < end <= len(text), (text, start, end)
        assert text[start:end] == text[start:end].strip(), (text, text[start:end])
        last_end = end
    assert ex["shape"] in SHAPES
    assert all(t in THEMES for t in ex["themes"])
    assert ex["activity"] in ACTIVITIES
    assert isinstance(ex["flat"], bool)


def tags_for(ex: dict) -> list[str]:
    tags = ["O"] * len(ex["text"])
    for start, end, kind in ex["spans"]:
        tags[start] = f"B-{kind}"
        for i in range(start + 1, end):
            tags[i] = f"I-{kind}"
    return tags[:MAX_LEN]
