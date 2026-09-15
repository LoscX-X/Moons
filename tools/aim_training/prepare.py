"""Stream the source into UUID-separated, continuous motion windows."""
import argparse
import hashlib
import json
import math
import time
from collections import Counter, deque
from pathlib import Path

import numpy as np
import pandas as pd

WINDOW = 16
HORIZON = 20
LENGTH = WINDOW + HORIZON + 1
FIELDS = ["yaw_deg", "pitch_deg", "horizontal_reference_deg", "horizontal_distance",
          "observed_dt_ms", "yaw_step_deg", "pitch_step_deg"]


def wrap(x):
    return (x + 180) % 360 - 180


def partition(uid):
    digest = hashlib.sha256(uid.encode()).digest()
    bucket = int.from_bytes(digest[:4], "big") % 100
    return ("train" if bucket < 70 else "validation" if bucket < 85 else "test", digest.hex())


def prepare(source, output, limit=None, chunk_size=300000, stride=16, shard_size=8192):
    output.mkdir(parents=True, exist_ok=False)
    before = source.stat()
    pending = {s: [] for s in ("train", "validation", "test")}
    counts, shard_counts, window_counts = Counter(), Counter(), Counter()
    identities, previous, buffers = {}, {}, {}
    started = time.monotonic()

    def flush(split):
        if not pending[split]:
            return
        windows, ids = zip(*pending[split])
        np.savez(output / f"{split}-{shard_counts[split]:05d}.npz",
                 rows=np.asarray(windows, dtype=np.float32), groups=np.asarray(ids, dtype="U64"))
        shard_counts[split] += 1
        pending[split].clear()

    for index, block in enumerate(pd.read_csv(source, chunksize=chunk_size, nrows=limit)):
        counts["source_rows"] += len(block)
        for r in block.itertuples(index=False):
            uid = r.uuid
            if uid not in identities:
                identities[uid] = partition(uid)
                buffers[uid] = deque()
            buf = buffers[uid]
            values = (r.time, r.yaw, r.pitch, r.target_x, r.target_z, r.position_x,
                      r.position_z, r.delta_yaw, r.delta_pitch)
            if not all(math.isfinite(v) for v in values) or abs(r.pitch) > 90:
                previous.pop(uid, None)
                buf.clear()
                counts["invalid_rows"] += 1
                continue
            old = previous.get(uid)
            previous[uid] = (r.time, r.yaw, r.pitch)
            dx, dz = r.target_x-r.position_x, r.target_z-r.position_z
            distance = math.hypot(dx, dz)
            if old is None or bool(r.new_sequence) or distance <= .05:
                buf.clear()
                counts["start_or_geometry_break"] += 1
                continue
            dt, dy, dp = r.time-old[0], wrap(r.yaw-old[1]), r.pitch-old[2]
            if (not 40 <= dt <= 60 or abs(dy) > 90 or abs(dp) > 60
                    or abs(dy-r.delta_yaw) > .01 or abs(dp-r.delta_pitch) > .01):
                buf.clear()
                counts["continuity_breaks"] += 1
                continue
            heading = math.degrees(math.atan2(dz, dx))-90
            buf.append((wrap(r.yaw), r.pitch, wrap(heading), distance, dt, dy, dp))
            counts["valid_motion_rows"] += 1
            if len(buf) == LENGTH:
                split, anonymous = identities[uid]
                pending[split].append((list(buf), anonymous))
                window_counts[split] += 1
                for _ in range(stride):
                    buf.popleft()
                if len(pending[split]) >= shard_size:
                    flush(split)
        if index % 4 == 0:
            print(f"prepare: {counts['source_rows']:,} rows; windows={dict(window_counts)}; "
                  f"{time.monotonic()-started:.1f}s", flush=True)
    for split in pending:
        flush(split)
    after = source.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise RuntimeError("Source changed during processing; discard this output")
    manifest = {"format": 1, "source_name": source.name, "source_bytes": before.st_size,
                "source_mtime_ns": before.st_mtime_ns, "limited_rows": limit,
                "row_fields": FIELDS, "window": WINDOW, "horizon": HORIZON,
                "stride": stride, "counts": dict(counts), "windows": dict(window_counts),
                "shards": dict(shard_counts), "seconds": time.monotonic()-started,
                "split": "SHA256(uuid) first 4 bytes big-endian modulo 100: <70 / <85 / rest",
                "scope": "Motion forecasting; horizontal reference only, no verified pitch target"}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2), flush=True)
    return manifest


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--limit", type=int)
    p.add_argument("--chunk-size", type=int, default=300000)
    p.add_argument("--stride", type=int, default=16)
    a = p.parse_args()
    if not 1 <= a.stride <= LENGTH or a.chunk_size < 1 or (a.limit is not None and a.limit < 1):
        p.error("Invalid stride/chunk size/limit")
    prepare(a.source, a.output, a.limit, a.chunk_size, a.stride)
