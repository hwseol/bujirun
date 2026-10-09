"""handwritten.tsv -> jsonl in the schema.py format.   python tsv2jsonl.py handwritten.tsv data/handwritten.jsonl"""

import json
import sys

from schema import SPAN_KINDS, normalize, validate


def convert(line: str) -> dict:
    text, _, spec = line.rstrip("\n").partition("\t")
    text = normalize(text)
    ex = {"text": text, "spans": [], "shape": "LOOP", "themes": [], "activity": "NONE", "flat": False}
    for field in filter(None, spec.split(";")):
        key, _, value = field.partition("=")
        if key in SPAN_KINDS:
            start = text.find(value)
            assert start >= 0, (text, value)
            ex["spans"].append([start, start + len(value), key])
        elif key == "shape":
            ex["shape"] = value
        elif key == "themes":
            ex["themes"] = sorted(value.split(","))
        elif key == "act":
            ex["activity"] = value
        elif key == "flat":
            ex["flat"] = value == "1"
        else:
            raise ValueError(field)
    ex["spans"].sort()
    validate(ex)
    return ex


if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f, open(sys.argv[2], "w", encoding="utf-8") as out:
        for line in f:
            if line.strip() and not line.startswith("#"):
                out.write(json.dumps(convert(line), ensure_ascii=False) + "\n")
