"""UUID-separated real-sequence replay for the saved predictors.

The default uses validation UUIDs, leaving the test split for later evaluation.
World/player positions are externally replayed; this is not a game simulation.
"""
import argparse
import hashlib
import json
import math
import time
from collections import Counter, deque
from pathlib import Path

import numpy as np
import pandas as pd

from infer_aim_baseline import AimBaseline, DEFAULT_MODEL

WARMUP = 16
HORIZON = 20
LENGTH = WARMUP + HORIZON + 1
FIELDS = ["time_ms", "yaw", "pitch", "target_x", "target_z", "position_x", "position_z"]


def wrap(angle):
    return (angle + 180) % 360 - 180


def step(a, b):
    return (wrap(b[1]-a[1]), b[2]-a[2])


def geometry(row, yaw):
    dx, dz = row[3]-row[5], row[4]-row[6]
    return wrap(math.degrees(math.atan2(dz, dx))-90-yaw), math.hypot(dx, dz)


def extract(source, split):
    rng = np.random.default_rng(20260916)
    groups, buffers, chosen, candidates = {}, {}, {}, Counter()
    counts = Counter()
    started = time.monotonic()
    before = source.stat()
    for block_index, block in enumerate(pd.read_csv(source, chunksize=300000)):
        counts["source_rows"] += len(block)
        for uid in block.uuid.unique():
            if uid not in groups:
                digest = hashlib.sha256(uid.encode()).digest()
                bucket = int.from_bytes(digest[:4], "big") % 100
                keep = 70 <= bucket < 85 if split == "validation" else bucket >= 85
                groups[uid] = (keep, digest.hex()[:16])
        selected = block[block.uuid.map(lambda uid: groups[uid][0])]
        counts["split_rows"] += len(selected)
        for r in selected.itertuples(index=False):
            uid = groups[r.uuid][1]
            row = (int(r.time), r.yaw, r.pitch, r.target_x, r.target_z, r.position_x, r.position_z)
            buffer = buffers.setdefault(uid, deque(maxlen=LENGTH))
            if not all(math.isfinite(v) for v in row) or abs(r.pitch) > 90:
                buffer.clear()
                counts["invalid_numeric_rows"] += 1
                continue
            if r.new_sequence:
                buffer.clear()
            if buffer:
                previous = buffer[-1]
                interval = row[0]-previous[0]
                delta = step(previous, row)
                if (not 40 <= interval <= 60 or abs(delta[0]) > 90 or abs(delta[1]) > 60
                        or abs(delta[0]-r.delta_yaw) > .01 or abs(delta[1]-r.delta_pitch) > .01):
                    buffer.clear()
                    counts["continuity_breaks"] += 1
            buffer.append(row)
            if len(buffer) == LENGTH:
                candidates[uid] += 1
                # One uniformly reservoir-selected non-overlapping window per UUID.
                if rng.integers(candidates[uid]) == 0:
                    chosen[uid] = list(buffer)
                buffer.clear()
                buffer.append(row)
        if block_index % 12 == 0:
            print(f"Replay extraction: {counts['source_rows']:,} rows, "
                  f"{len(chosen)} UUID windows, {time.monotonic()-started:.1f}s", flush=True)
    after = source.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise RuntimeError("Source changed during replay extraction")
    sequences = [{"group": uid, "candidate_windows": candidates[uid], "rows": chosen[uid]}
                 for uid in sorted(chosen)]
    for sequence in sequences:
        rows = sequence["rows"]
        assert len(rows) == LENGTH
        assert all(40 <= b[0]-a[0] <= 60 for a, b in zip(rows, rows[1:]))
    return {"source": str(source), "source_bytes": before.st_size, "split": split,
            "selection": "one reservoir-selected non-overlapping window per UUID; equal UUID weight",
            "warmup_updates": WARMUP, "forecast_updates": HORIZON, "row_fields": FIELDS,
            "counts": dict(counts), "available_candidate_windows": sum(candidates.values()),
            "sequences": sequences}


def evaluate(bundle, model_path):
    models = {name: AimBaseline(model_path, name) for name in ("history", "history_geometry")}
    methods = ["zero", "persistence", *models]
    outcomes = {name: {h: [] for h in (1, 5, 20)} for name in methods}
    teacher_errors = {name: [] for name in models}
    invalid_pitch = Counter()
    traces = []
    for sequence in bundle["sequences"]:
        rows = sequence["rows"]
        for name in methods:
            yaw, pitch = rows[WARMUP][1:3]
            previous = step(rows[WARMUP-1], rows[WARMUP])
            older = step(rows[WARMUP-2], rows[WARMUP-1])
            predicted = []
            for horizon in range(1, HORIZON+1):
                observation = rows[WARMUP+horizon-1]
                actual = rows[WARMUP+horizon]
                if name == "zero":
                    next_step = (0., 0.)
                elif name == "persistence":
                    next_step = previous
                else:
                    error, distance = geometry(observation, yaw)
                    next_step = models[name].predict(previous, older,
                            horizontal_error=error, horizontal_distance=distance)
                    actual_previous = step(rows[WARMUP+horizon-2], observation)
                    actual_older = step(rows[WARMUP+horizon-3], rows[WARMUP+horizon-2])
                    true_error, true_distance = geometry(observation, observation[1])
                    one_step = models[name].predict(actual_previous, actual_older,
                            horizontal_error=true_error, horizontal_distance=true_distance)
                    true_step = step(observation, actual)
                    teacher_errors[name].append(np.subtract(one_step, true_step))
                yaw += next_step[0]
                pitch += next_step[1]
                older, previous = previous, next_step
                invalid_pitch[name] += int(abs(pitch) > 90)
                prediction_error = (wrap(yaw-actual[1]), pitch-actual[2])
                assert all(math.isfinite(v) for v in prediction_error)
                if horizon in outcomes[name]:
                    outcomes[name][horizon].append(prediction_error)
                predicted.append([horizon, yaw, pitch, *prediction_error])
            # Persist only a small deterministic selection for inspection.
            if len(traces) < 32:
                traces.append({"group": sequence["group"], "method": name, "predictions": predicted})
    result = {"scope": "Observed-world replay with self-fed predicted rotations; no game physics or quantization",
              "split": bundle["split"], "uuid_windows": len(bundle["sequences"]),
              "horizons": {}, "one_step_with_observed_history": {},
              "pitch_outside_90_prediction_count": dict(invalid_pitch),
              "predictions_per_method": len(bundle["sequences"])*HORIZON}
    for name, horizons in outcomes.items():
        result["horizons"][name] = {}
        for h, errors in horizons.items():
            errors = np.array(errors)
            result["horizons"][name][str(h)] = {
                    "yaw_mae_deg": float(np.mean(abs(errors[:, 0]))),
                    "pitch_mae_deg": float(np.mean(abs(errors[:, 1]))),
                    "combined_p95_deg": float(np.quantile(np.hypot(*errors.T), .95))}
    for name, errors in teacher_errors.items():
        result["one_step_with_observed_history"][name] = np.mean(abs(np.array(errors)), axis=0).tolist()
    result["traces"] = traces
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--split", choices=["validation", "test"], default="validation")
    parser.add_argument("--reuse", action="store_true", help="Reuse previously extracted windows")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    saved = args.output/"sequence-windows.json"
    if args.reuse:
        bundle = json.loads(saved.read_text(encoding="utf-8"))
        if bundle["split"] != args.split or Path(bundle["source"]).resolve() != args.source.resolve():
            raise ValueError("Requested source/split does not match saved replay windows")
    else:
        bundle = extract(args.source, args.split)
        if not bundle["sequences"]:
            raise ValueError("No usable sequence windows")
        saved.write_text(json.dumps(bundle, separators=(",", ":"), allow_nan=False), encoding="utf-8")
    result = evaluate(bundle, args.model)
    (args.output/"results.json").write_text(json.dumps(result, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "traces"}, indent=2), flush=True)


if __name__ == "__main__":
    main()
