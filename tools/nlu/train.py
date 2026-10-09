"""Train the on-device course-request model and export it for the app.

    python train.py data/train.jsonl [more.jsonl ...] --eval data/eval.jsonl \
        --out ../../app/src/main/assets/nlu.bin \
        --fixture ../../app/src/test/resources/nlu_fixture.tsv

Model: each character -> embedding of the character + embeddings of its Hangul jamo (so a
syllable never seen in training still carries its sounds), one bidirectional GRU layer, then
 - per character: a BIO tag (schema.TAGS) for distance / time / place spans
 - per sentence (mean + max of the GRU outputs): shape, themes, activity, flat

Export (little-endian), read by app/.../course/NluModel.kt:
    "BJNL", int32 version, int32 vocab size V, V x int32 code points (index 2.. ; 0 pad, 1 unk),
    int32 embedding dim E, int32 hidden H, then the tensors in TENSOR_ORDER, each as
    int32 rows, int32 cols, rows x float32 scale, rows x cols int8  (value = int8 * scale)
Vectors (biases) are stored as 1 x n.

The fixture holds sentences with the exported (int8) model's raw outputs so a Kotlin test can
check that the app computes exactly what Python does.
"""

import argparse
import json
import random
import struct
from collections import Counter

import torch
import torch.nn as nn
import torch.nn.functional as F

from schema import ACTIVITIES, MAX_LEN, SHAPES, SPAN_KINDS, TAGS, THEMES, normalize, tags_for, validate

VERSION = 1
EMB = 48
HIDDEN = 64
N_CHO, N_JUNG, N_JONG = 19, 21, 28


def jamo(c: str) -> tuple[int, int, int]:
    """Hangul syllable -> (choseong+1, jungseong+1, jongseong+1); 0s for anything else."""
    code = ord(c) - 0xAC00
    if 0 <= code < 11172:
        return code // 588 + 1, (code % 588) // 28 + 1, code % 28 + 1
    return 0, 0, 0


class Model(nn.Module):
    def __init__(self, vocab_size: int):
        super().__init__()
        self.char = nn.Embedding(vocab_size, EMB, padding_idx=0)
        self.cho = nn.Embedding(N_CHO + 1, EMB, padding_idx=0)
        self.jung = nn.Embedding(N_JUNG + 1, EMB, padding_idx=0)
        self.jong = nn.Embedding(N_JONG + 1, EMB, padding_idx=0)
        self.gru = nn.GRU(EMB, HIDDEN, batch_first=True, bidirectional=True)
        self.tag = nn.Linear(2 * HIDDEN, len(TAGS))
        self.sent = nn.Linear(4 * HIDDEN, len(SHAPES) + len(THEMES) + len(ACTIVITIES) + 1)

    def forward(self, ids, cho, jung, jong, lengths):
        x = self.char(ids) + self.cho(cho) + self.jung(jung) + self.jong(jong)
        packed = nn.utils.rnn.pack_padded_sequence(x, lengths.cpu(), batch_first=True, enforce_sorted=False)
        out, _ = self.gru(packed)
        out, _ = nn.utils.rnn.pad_packed_sequence(out, batch_first=True, total_length=ids.shape[1])
        mask = (ids != 0).unsqueeze(-1)
        mean = (out * mask).sum(1) / mask.sum(1)
        mx = out.masked_fill(~mask, -1e4).max(1).values
        return self.tag(out), self.sent(torch.cat([mean, mx], -1))


def split_sentence(logits):
    a = len(SHAPES)
    b = a + len(THEMES)
    c = b + len(ACTIVITIES)
    return logits[..., :a], logits[..., a:b], logits[..., b:c], logits[..., c]


def encode(texts, vocab):
    n = len(texts)
    width = max(min(len(t), MAX_LEN) for t in texts)
    ids = torch.zeros(n, width, dtype=torch.long)
    cho, jung, jong = (torch.zeros(n, width, dtype=torch.long) for _ in range(3))
    for i, t in enumerate(texts):
        for j, c in enumerate(t[:MAX_LEN]):
            ids[i, j] = vocab.get(c, 1)
            cho[i, j], jung[i, j], jong[i, j] = jamo(c)
    lengths = torch.tensor([min(len(t), MAX_LEN) for t in texts])
    return ids, cho, jung, jong, lengths


def targets(batch):
    width = max(min(len(ex["text"]), MAX_LEN) for ex in batch)
    tags = torch.full((len(batch), width), -100, dtype=torch.long)
    for i, ex in enumerate(batch):
        for j, t in enumerate(tags_for(ex)):
            tags[i, j] = TAGS.index(t)
    shape = torch.tensor([SHAPES.index(ex["shape"]) for ex in batch])
    themes = torch.tensor([[float(t in ex["themes"]) for t in THEMES] for ex in batch])
    activity = torch.tensor([ACTIVITIES.index(ex["activity"]) for ex in batch])
    flat = torch.tensor([float(ex["flat"]) for ex in batch])
    return tags, shape, themes, activity, flat


def load(paths):
    out = []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    ex = json.loads(line)
                    ex["text"] = normalize(ex["text"])
                    validate(ex)
                    out.append(ex)
    return out


# ---- reading the outputs (mirrored in NluModel.kt) -------------------------------------

def decode(text, tag_logits, sent_logits):
    tags = [TAGS[i] for i in tag_logits[: len(text)].argmax(-1).tolist()]
    spans, cur = [], None
    for i, t in enumerate(tags):
        kind = t[2:] if t != "O" else None
        if t.startswith("B-") or (t.startswith("I-") and (cur is None or cur[2] != kind)):
            if cur:
                spans.append(cur)
            cur = [i, i + 1, kind]
        elif t.startswith("I-"):
            cur[1] = i + 1
        else:
            if cur:
                spans.append(cur)
            cur = None
    if cur:
        spans.append(cur)
    # Trim spaces a span may have swallowed at its edges.
    cleaned = []
    for s, e, k in spans:
        while s < e and text[s] == " ":
            s += 1
        while e > s and text[e - 1] == " ":
            e -= 1
        if s < e:
            cleaned.append([s, e, k])
    shape, themes, activity, flat = split_sentence(sent_logits)
    return {
        "spans": cleaned,
        "shape": SHAPES[int(shape.argmax())],
        "themes": [t for t, v in zip(THEMES, themes.tolist()) if v > 0],
        "activity": ACTIVITIES[int(activity.argmax())],
        "flat": bool(flat > 0),
    }


def same(pred, ex):
    return (sorted(map(tuple, pred["spans"])) == sorted(map(tuple, ex["spans"]))
            and pred["shape"] == ex["shape"] and sorted(pred["themes"]) == sorted(ex["themes"])
            and pred["activity"] == ex["activity"] and pred["flat"] == ex["flat"])


@torch.no_grad()
def evaluate(model, data, vocab, show=0):
    model.eval()
    right = 0
    parts = Counter()
    wrong = []
    for k in range(0, len(data), 256):
        batch = data[k:k + 256]
        tag_logits, sent_logits = model(*encode([ex["text"] for ex in batch], vocab))
        for i, ex in enumerate(batch):
            pred = decode(ex["text"][:MAX_LEN], tag_logits[i], sent_logits[i])
            ok = same(pred, ex)
            right += ok
            parts["spans"] += sorted(map(tuple, pred["spans"])) == sorted(map(tuple, ex["spans"]))
            parts["shape"] += pred["shape"] == ex["shape"]
            parts["themes"] += sorted(pred["themes"]) == sorted(ex["themes"])
            parts["activity"] += pred["activity"] == ex["activity"]
            parts["flat"] += pred["flat"] == ex["flat"]
            if not ok:
                wrong.append((ex, pred))
    n = len(data)
    print(f"  exact {right / n:.3f}  " + "  ".join(f"{k} {v / n:.3f}" for k, v in parts.items()))
    for ex, pred in wrong[:show]:
        print("   ", ex["text"])
        print("      want", {k: ex[k] for k in ("spans", "shape", "themes", "activity", "flat")})
        print("      got ", pred)
    return right / n


# ---- export ----------------------------------------------------------------------------

TENSOR_ORDER = [
    "char.weight", "cho.weight", "jung.weight", "jong.weight",
    "gru.weight_ih_l0", "gru.weight_hh_l0", "gru.bias_ih_l0", "gru.bias_hh_l0",
    "gru.weight_ih_l0_reverse", "gru.weight_hh_l0_reverse", "gru.bias_ih_l0_reverse", "gru.bias_hh_l0_reverse",
    "tag.weight", "tag.bias", "sent.weight", "sent.bias",
]


def quantize(t: torch.Tensor):
    m = t.reshape(1, -1) if t.dim() == 1 else t
    scale = m.abs().max(1).values.clamp(min=1e-8) / 127
    q = torch.round(m / scale[:, None]).clamp(-127, 127).to(torch.int8)
    return q, scale


def export(model, vocab, path):
    """Writes the model and replaces the in-memory weights with their int8 round trip, so
    everything evaluated afterwards is exactly what the phone runs."""
    state = model.state_dict()
    codepoints = [ord(c) for c, _ in sorted(vocab.items(), key=lambda kv: kv[1])]
    with open(path, "wb") as f:
        f.write(b"BJNL")
        f.write(struct.pack("<ii", VERSION, len(codepoints)))
        f.write(struct.pack(f"<{len(codepoints)}i", *codepoints))
        f.write(struct.pack("<ii", EMB, HIDDEN))
        for name in TENSOR_ORDER:
            t = state[name].float()
            q, scale = quantize(t)
            f.write(struct.pack("<ii", *q.shape))
            f.write(struct.pack(f"<{scale.numel()}f", *scale.tolist()))
            f.write(struct.pack(f"<{q.numel()}b", *q.flatten().tolist()))  # (torch's numpy bridge is broken here)
            state[name].copy_((q.float() * scale[:, None]).reshape(t.shape))
    model.load_state_dict(state)


def write_fixture(model, vocab, texts, path):
    """One line per sentence: text, then all tag logits, then the sentence logits."""
    model.eval()
    with torch.no_grad(), open(path, "w", encoding="utf-8") as f:
        for t in texts:
            t = normalize(t)[:MAX_LEN]
            tag_logits, sent_logits = model(*encode([t], vocab))
            nums = tag_logits[0, : len(t)].flatten().tolist() + sent_logits[0].tolist()
            f.write(t + "\t" + " ".join(f"{v:.5f}" for v in nums) + "\n")


FIXTURE_SENTENCES = [
    "한강 따라 5km", "남산까지 갔다가 돌아오기", "공원 돌아서 30분", "올림픽 공원에서 한 시간 반 뛰기",
    "반포한강공원에서부터 잠수교까지", "평지로 가볍게 3키로", "석촌호수 두 바퀴 산책", "해운대 바다 보면서 오킬로",
    "퇴근하고 동네 한바퀴", "양재천따라 40분 조깅 ㄱㄱ", "x", "광화문 경복궁 명소 구경하면서 걷기 2시간",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("train", nargs="+")
    ap.add_argument("--eval", nargs="*", default=[])
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--out")
    ap.add_argument("--fixture")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    data = load(args.train)
    random.shuffle(data)
    held = data[: max(500, len(data) // 20)]
    train = data[len(held):]
    evals = {p: load([p]) for p in args.eval}

    counts = Counter(c for ex in train for c in ex["text"])
    chars = [c for c, n in counts.most_common() if n >= 2][:3000]
    vocab = {c: i + 2 for i, c in enumerate(chars)}
    print(f"{len(train)} train, {len(held)} held out, vocab {len(vocab)}")

    model = Model(len(vocab) + 2)
    print(f"{sum(p.numel() for p in model.parameters())} parameters")
    opt = torch.optim.Adam(model.parameters(), lr=3e-3)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=3e-3, total_steps=args.epochs * ((len(train) + 63) // 64))
    for epoch in range(args.epochs):
        model.train()
        random.shuffle(train)
        total = 0.0
        for k in range(0, len(train), 64):
            batch = train[k:k + 64]
            texts = [ex["text"] for ex in batch]
            # Unknown-character practice: now and then hide a character so the model learns to
            # lean on jamo and context for syllables it has never seen.
            ids, cho, jung, jong, lengths = encode(texts, vocab)
            drop = (torch.rand(ids.shape) < 0.05) & (ids > 1)
            ids = ids.masked_fill(drop, 1)
            tag_logits, sent_logits = model(ids, cho, jung, jong, lengths)
            tags, shape, themes, activity, flat = targets(batch)
            s, t, a, fl = split_sentence(sent_logits)
            loss = (F.cross_entropy(tag_logits.reshape(-1, len(TAGS)), tags.reshape(-1), ignore_index=-100)
                    + F.cross_entropy(s, shape) + F.binary_cross_entropy_with_logits(t, themes)
                    + F.cross_entropy(a, activity) + F.binary_cross_entropy_with_logits(fl, flat))
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            total += loss.item() * len(batch)
        print(f"epoch {epoch + 1}: loss {total / len(train):.4f}")
        evaluate(model, held, vocab)

    if args.out:
        export(model, vocab, args.out)
        print(f"exported -> {args.out}; after int8:")
    print("held out:")
    evaluate(model, held, vocab, show=5)
    for p, d in evals.items():
        print(f"{p}:")
        evaluate(model, d, vocab, show=15)
    if args.fixture:
        write_fixture(model, vocab, FIXTURE_SENTENCES, args.fixture)


if __name__ == "__main__":
    main()
