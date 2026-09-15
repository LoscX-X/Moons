"""Read-only streaming audit of an interleaved aim CSV; no game integration.

Uses pandas/numpy only. UUID-separated held-out regression is an exploratory
one-step baseline, not evidence of human authenticity or a deployable policy.
"""
import argparse
import hashlib
import json
import time
from collections import Counter
from pathlib import Path

import numpy as np
import pandas as pd


def wrap(x):
    return (x + 180) % 360 - 180


def quantiles(x):
    return dict(zip(["p50", "p90", "p95", "p99", "max_sample"],
                    map(float, np.quantile(x, [.5, .9, .95, .99, 1]))))


def regress(sample, user_buckets):
    # Samples contain only previous observations as predictors. Current target,
    # current angle and current dt are not predictors of the next mouse step.
    y = sample[:, :2]
    x = sample[:, [7, 8, 9, 10, 11, 12]]
    x = np.column_stack([x, np.sign(x[:, 4]) * np.sqrt(abs(x[:, 4]))])
    ids = sample[:, 14].astype(int)
    buckets = np.array([user_buckets[int(i)] for i in ids])
    masks = [buckets < 70, (buckets >= 70) & (buckets < 85), buckets >= 85]
    if min(map(np.count_nonzero, masks)) < 100:
        return {"status": "Insufficient independent user partitions"}, None
    train, validation, test = masks
    def score(pred, truth):
        return {"mae": np.mean(abs(pred-truth), axis=0).tolist(),
                "rmse": np.sqrt(np.mean((pred-truth)**2, axis=0)).tolist()}
    result = {"partition_rows": [int(m.sum()) for m in masks],
              "partition_users": [len(np.unique(ids[m])) for m in masks],
              "zero": score(np.zeros_like(y[test]), y[test]),
              "previous_step": score(x[test, :2], y[test])}
    models = {}
    for name, columns in [("history", [0, 1, 2, 3]),
                          ("history_geometry", list(range(x.shape[1])))]:
        features = x[:, columns]
        mean, std = features[train].mean(axis=0), features[train].std(axis=0)
        std[std < 1e-8] = 1
        z = np.column_stack([np.ones(len(features)), (features-mean)/std])
        xx, xy = z[train].T @ z[train], z[train].T @ y[train]
        best = None
        for penalty in [.01, .1, 1, 10, 100, 1000]:
            ridge = np.eye(z.shape[1])*penalty
            ridge[0, 0] = 0
            coef = np.linalg.solve(xx+ridge, xy)
            loss = float(np.mean((z[validation] @ coef-y[validation])**2))
            if best is None or loss < best[0]:
                best = (loss, penalty, coef)
        pred = z[test] @ best[2]
        metrics = score(pred, y[test])
        # Equal weight per held-out user as a second view beside per-row scores.
        user_mae = [np.mean(abs(pred[ids[test] == uid]-y[test][ids[test] == uid]), axis=0)
                    for uid in np.unique(ids[test])]
        metrics.update({"user_macro_mae": np.mean(user_mae, axis=0).tolist(), "ridge": best[1]})
        result[name] = metrics
        models[name] = {"feature_columns": columns, "mean": mean.tolist(),
                        "std": std.tolist(), "coefficients": best[2].tolist(), "ridge": best[1]}
    models["feature_names"] = ["previous_yaw_step", "previous_pitch_step",
            "second_previous_yaw_step", "second_previous_pitch_step",
            "previous_horizontal_target_error", "previous_horizontal_distance",
            "signed_sqrt_previous_horizontal_error"]
    models["units"] = "Output degrees/update; input coordinates follow the unverified source convention."
    models["scope"] = "Offline one-step ridge baseline; no roll-out validation, no game deployment."
    return result, models


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--chunk-size", type=int, default=300000)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    before = args.source.stat()
    counts, users = Counter(), Counter()
    carry = pd.DataFrame()
    rng = np.random.default_rng(20260915)
    sampled, uid_number, buckets = [], {}, {}
    global_time_min, global_time_max = np.inf, -np.inf
    sensitivity_min, sensitivity_max = np.inf, -np.inf
    dt_hist = Counter()
    max_residual = {}
    per_user = {}
    for block_index, block in enumerate(pd.read_csv(args.source, chunksize=args.chunk_size, nrows=args.limit)):
        counts["rows"] += len(block)
        users.update(block.uuid.value_counts().to_dict())
        for uid in block.uuid.unique():
            if uid not in uid_number:
                num = len(uid_number)
                uid_number[uid] = num
                buckets[num] = int.from_bytes(hashlib.sha256(uid.encode()).digest()[:4], "big") % 100
        offset = len(carry)
        combined = pd.concat([carry, block], ignore_index=True)
        g = combined.groupby("uuid", sort=False)
        lag1 = g.shift(1).iloc[offset:].reset_index(drop=True)
        lag2 = g[["yaw", "pitch", "time", "new_sequence"]].shift(2).iloc[offset:].reset_index(drop=True)
        lag3 = g[["yaw", "pitch", "time"]].shift(3).iloc[offset:].reset_index(drop=True)
        carry = g.tail(3).copy()
        d = block.reset_index(drop=True)
        numeric = d.select_dtypes(include="number")
        finite = np.isfinite(numeric.to_numpy()).all(axis=1)
        counts["nonfinite_rows"] += int((~finite).sum())
        counts["sequence_markers"] += int(d.new_sequence.sum())
        counts["pitch_outside_90"] += int((abs(d.pitch) > 90.001).sum())
        counts["provided_yaw_step_over_180"] += int((abs(d.delta_yaw) > 180.01).sum())
        counts["sensitivity_negative"] += int((d.sensitivity < 0).sum())
        counts["sensitivity_above_100"] += int((d.sensitivity > 100).sum())
        sensitivity_min = min(sensitivity_min, d.sensitivity.min())
        sensitivity_max = max(sensitivity_max, d.sensitivity.max())
        global_time_min = min(global_time_min, d.time.min())
        global_time_max = max(global_time_max, d.time.max())
        dt = d.time - lag1.time
        dt_previous = lag1.time-lag2.time
        dt_older = lag2.time-lag3.time
        same_seq = (~d.new_sequence) & lag1.time.notna()
        counts["nonpositive_dt_within_sequence"] += int((same_seq & dt.le(0)).sum())
        counts["negative_dt_within_sequence"] += int((same_seq & dt.lt(0)).sum())
        counts["zero_dt_within_sequence"] += int((same_seq & dt.eq(0)).sum())
        counts["gap_over_250ms_within_sequence"] += int((same_seq & dt.gt(250)).sum())
        counts["adjacent_duplicate_time_and_angles"] += int((same_seq & dt.eq(0) & d.yaw.eq(lag1.yaw) & d.pitch.eq(lag1.pitch)).sum())
        known_dt = dt[same_seq & dt.ge(0) & dt.le(1000)].round().astype(int)
        dt_hist.update(known_dt.value_counts().to_dict())
        dy, dp = wrap(d.yaw-lag1.yaw), d.pitch-lag1.pitch
        previous_dy, previous_dp = wrap(lag1.yaw-lag2.yaw), lag1.pitch-lag2.pitch
        older_dy, older_dp = wrap(lag2.yaw-lag3.yaw), lag2.pitch-lag3.pitch
        ay, ap = dy-previous_dy, dp-previous_dp
        jy, jp = ay-(previous_dy-older_dy), ap-(previous_dp-older_dp)
        contiguous = same_seq & dt.gt(0) & dt.le(250) & finite
        consistent = (abs(dy-d.delta_yaw) <= .01) & (abs(dp-d.delta_pitch) <= .01)
        counts["contiguous_rows"] += int(contiguous.sum())
        for name, expected, recorded in [
                ("delta_yaw", dy, d.delta_yaw), ("delta_pitch", dp, d.delta_pitch),
                ("accel_yaw_forward", d.delta_yaw-lag1.delta_yaw, d.accel_yaw),
                ("accel_pitch_forward", d.delta_pitch-lag1.delta_pitch, d.accel_pitch),
                ("accel_yaw_reverse", lag1.delta_yaw-d.delta_yaw, d.accel_yaw),
                ("accel_pitch_reverse", lag1.delta_pitch-d.delta_pitch, d.accel_pitch),
                ("accel_pitch_previous_cross_axis", lag1.delta_pitch-lag1.delta_yaw, d.accel_pitch)]:
            residual = abs(expected-recorded)[contiguous]
            counts[name+"_mismatch_001"] += int((residual > .01).sum())
            max_residual[name] = max(max_residual.get(name, 0), float(residual.max()))
        chain = (contiguous & ~lag1.new_sequence.fillna(True).astype(bool)
                 & ~lag2.new_sequence.fillna(True).astype(bool)
                 & dt_previous.gt(0) & dt_previous.le(250) & dt_older.gt(0) & dt_older.le(250))
        # Conservative regular-cadence subset for comparing degrees/update dynamics.
        clean = (chain & consistent & dt.between(40, 60) & dt_previous.between(40, 60)
                 & dt_older.between(40, 60) & (abs(dy) <= 90) & (abs(dp) <= 60)
                 & (abs(previous_dy) <= 90) & (abs(previous_dp) <= 60)
                 & (abs(older_dy) <= 90) & (abs(older_dp) <= 60)
                 & (abs(d.pitch) <= 90) & (abs(lag1.pitch) <= 90))
        counts["regular_subset_rows"] += int(clean.sum())
        counts["regular_yaw_zero"] += int((clean & (abs(dy) < 1e-5)).sum())
        counts["regular_pitch_zero"] += int((clean & (abs(dp) < 1e-5)).sum())
        counts["regular_both_zero"] += int((clean & (abs(dy) < 1e-5) & (abs(dp) < 1e-5)).sum())
        dx, dz = lag1.target_x-lag1.position_x, lag1.target_z-lag1.position_z
        error = wrap(np.degrees(np.arctan2(dz, dx))-90-lag1.yaw)
        distance = np.hypot(dx, dz)
        # This assumes the conventional Minecraft horizontal coordinate/yaw mapping.
        sample_mask = clean & (rng.random(len(d)) < .025) & distance.gt(.05)
        positions = np.flatnonzero(sample_mask)
        nums = d.uuid.map(uid_number).to_numpy()
        if len(positions):
            selected = np.column_stack([dy, dp, ay, ap, jy, jp, dt,
                    previous_dy, previous_dp, older_dy, older_dp, error, distance,
                    lag1.sensitivity, nums])[positions]
            selected = selected[np.isfinite(selected).all(axis=1)]
            sampled.append(selected)
        aggregate = pd.DataFrame({"uuid": d.uuid[clean], "abs_yaw": abs(dy[clean]),
                                  "abs_pitch": abs(dp[clean])}).groupby("uuid").agg(["sum", "count"])
        for uid, row in aggregate.iterrows():
            state = per_user.setdefault(uid_number[uid], [0, 0., 0.])
            state[0] += int(row[("abs_yaw", "count")])
            state[1] += float(row[("abs_yaw", "sum")])
            state[2] += float(row[("abs_pitch", "sum")])
        if block_index % 3 == 0:
            print(f"Processed {counts['rows']:,} rows, {len(users)} users, "
                  f"{time.monotonic()-started:.1f}s", flush=True)
    after = args.source.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise RuntimeError("Source changed during analysis")
    sample = np.concatenate(sampled)
    if len(sample) > 400000:
        sample = sample[rng.choice(len(sample), 400000, replace=False)]
    results = {"source": str(args.source), "bytes": before.st_size, "full_scan": args.limit is None,
               "counts": dict(counts), "users": len(users),
               "time_min_ms": int(global_time_min), "time_max_ms": int(global_time_max),
               "sensitivity_range": [float(sensitivity_min), float(sensitivity_max)],
               "sample_rows": len(sample), "sample_users": len(np.unique(sample[:, 14])),
               "max_residual": max_residual,
               "dt_histogram_0_1000ms": {str(k): v for k, v in sorted(dt_hist.items())},
               "rows_per_user": quantiles(np.array(list(users.values()))),
               "sample_quantiles": {}, "correlations": {}, "horizontal_error_bins": []}
    for i, name in enumerate(["yaw_step", "pitch_step", "yaw_step_change", "pitch_step_change",
                              "yaw_second_step_change", "pitch_second_step_change"]):
        results["sample_quantiles"][name] = quantiles(abs(sample[:, i]))
    results["sample_quantiles"]["observed_yaw_speed_deg_s"] = quantiles(abs(sample[:, 0])/(sample[:, 6]*.001))
    results["sample_quantiles"]["observed_pitch_speed_deg_s"] = quantiles(abs(sample[:, 1])/(sample[:, 6]*.001))
    for name, a, b in [("yaw_lag1", 0, 7), ("pitch_lag1", 1, 8), ("yaw_pitch", 0, 1),
                       ("yaw_target_error", 0, 11)]:
        results["correlations"][name] = float(np.corrcoef(sample[:, a], sample[:, b])[0, 1])
    results["correlations"]["abs_yaw_abs_pitch"] = float(np.corrcoef(abs(sample[:, 0]), abs(sample[:, 1]))[0, 1])
    for low, high in zip([0, 1, 3, 5, 10, 20, 45, 90], [1, 3, 5, 10, 20, 45, 90, 180]):
        part = sample[(abs(sample[:, 11]) >= low) & (abs(sample[:, 11]) < high)]
        if not len(part):
            continue
        moving = abs(part[:, 0]) > 1e-5
        results["horizontal_error_bins"].append({"range": [low, high], "n": len(part),
                "median_abs_step": float(np.median(abs(part[:, 0]))),
                "p90_abs_step": float(np.quantile(abs(part[:, 0]), .9)),
                "toward_fraction_when_moving": float(np.mean(part[moving, 0]*part[moving, 11] > 0)) if moving.any() else None})
    means = np.array([[s[1]/s[0], s[2]/s[0]] for s in per_user.values() if s[0] >= 1000])
    results["user_mean_abs_step_quantiles"] = {"yaw": quantiles(means[:, 0]), "pitch": quantiles(means[:, 1])}
    results["baseline"], models = regress(sample, buckets)
    results["elapsed_seconds"] = time.monotonic()-started
    (args.output/"analysis.json").write_text(json.dumps(results, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    np.savez_compressed(args.output/"analysis-sample.npz", sample=sample)
    if models:
        (args.output/"ridge-baseline.json").write_text(json.dumps(models, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    print(json.dumps({k: results[k] for k in ["counts", "users", "sample_rows", "sample_quantiles", "correlations", "baseline", "elapsed_seconds"]}, indent=2), flush=True)


if __name__ == "__main__":
    main()
