"""Train/evaluate a small trajectory predictor; no Minecraft integration."""
import argparse
import hashlib
import json
import shutil
import time
from pathlib import Path

import numpy as np
import torch

from model import FEATURES, MotionGRU, features, load_checkpoint
from prepare import WINDOW, HORIZON, LENGTH

RIDGE = json.loads(Path(__file__).with_name("ridge-baseline.json").read_text(encoding="utf-8"))


def ridge_prediction(observations, name):
    current, older = observations[:, -1], observations[:, -2]
    error = (current[:, 2]-current[:, 0]+180) % 360-180
    x = np.column_stack((current[:, 5:7], older[:, 5:7], error, current[:, 3],
                         np.sign(error)*np.sqrt(abs(error)))).astype(np.float64)
    model = RIDGE[name]
    normalized = (x[:, model["feature_columns"]]-model["mean"])/model["std"]
    return np.column_stack((np.ones(len(x)), normalized)) @ np.asarray(model["coefficients"])


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False), encoding="utf-8")


def files(data, split):
    result = sorted(data.glob(f"{split}-*.npz"))
    if not result:
        raise ValueError(f"No {split} shards in {data}")
    return result


def load_shard(path):
    with np.load(path, allow_pickle=False) as shard:
        rows, groups = shard["rows"], shard["groups"]
    if rows.shape[1:] != (LENGTH, 7) or not np.isfinite(rows).all():
        raise ValueError(f"Invalid shard: {path}")
    return rows, groups


def input_target(rows):
    return features(rows[:, :WINDOW+1]), rows[:, WINDOW+1, 5:7]


def statistics(data):
    total = np.zeros(len(FEATURES), dtype=np.float64)
    square = total.copy()
    y_square = np.zeros(2, dtype=np.float64)
    count = y_count = 0
    for path in files(data, "train"):
        rows, _ = load_shard(path)
        x, y = input_target(rows)
        x = x.reshape(-1, len(FEATURES)).astype(np.float64)
        total += x.sum(0)
        square += (x*x).sum(0)
        y_square += (y.astype(np.float64)**2).sum(0)
        count += len(x)
        y_count += len(y)
    mean = total/count
    std = np.sqrt(np.maximum(square/count-mean*mean, 0))
    std[std < 1e-6] = 1
    scale = np.maximum(np.sqrt(y_square/y_count), 1e-3)
    return mean.tolist(), std.tolist(), scale.tolist()


def sample_split(data, split, count, seed=20260915):
    """Uniform priorities across windows; separately one uniform window per UUID."""
    rng = np.random.default_rng(seed)
    kept_rows, kept_ids, priorities = None, None, np.empty(0)
    per_group = {}
    for path in files(data, split):
        rows, groups = load_shard(path)
        keys = rng.random(len(rows))
        for group in np.unique(groups):
            indices = np.flatnonzero(groups == group)
            index = indices[np.argmin(keys[indices])]
            if group not in per_group or keys[index] < per_group[group][0]:
                per_group[str(group)] = (keys[index], rows[index].copy())
        kept_rows = rows if kept_rows is None else np.concatenate((kept_rows, rows))
        kept_ids = groups if kept_ids is None else np.concatenate((kept_ids, groups))
        priorities = np.concatenate((priorities, keys))
        if len(priorities) > count:
            selected = np.argpartition(priorities, count-1)[:count]
            kept_rows, kept_ids, priorities = kept_rows[selected], kept_ids[selected], priorities[selected]
    replay = np.stack([per_group[g][1] for g in sorted(per_group)])
    return kept_rows, kept_ids, replay


def predict(net, x, device, batch_size):
    result = []
    net.eval()
    with torch.inference_mode():
        for start in range(0, len(x), batch_size):
            result.append(net(torch.from_numpy(x[start:start+batch_size]).to(device)).cpu().numpy())
    result = np.concatenate(result)
    if not np.isfinite(result).all():
        raise ValueError("Nonfinite predictions")
    return result


def scores(pred, truth, groups=None):
    error = pred.astype(np.float64)-truth
    result = {"mae_deg": np.abs(error).mean(0).tolist(),
              "rmse_deg": np.sqrt((error**2).mean(0)).tolist()}
    if groups is not None:
        result["uuid_macro_mae_deg"] = np.mean([
            np.abs(error[groups == g]).mean(0) for g in np.unique(groups)], 0).tolist()
    return result


def one_step(net, rows, groups, device, batch_size):
    x, y = input_target(rows)
    pred = predict(net, x, device, batch_size)
    return {"windows": len(rows), "uuids": len(np.unique(groups)),
            "gru": scores(pred, y, groups), "zero": scores(np.zeros_like(y), y, groups),
            "persistence": scores(x[:, -1, :2], y, groups),
            "ridge_history": scores(ridge_prediction(rows[:, :WINDOW+1], "history"), y, groups),
            "ridge_geometry": scores(ridge_prediction(rows[:, :WINDOW+1], "history_geometry"), y, groups)}


def rollout(net, rows, device, batch_size):
    """Self-fed angles, externally observed horizontal geometry; not game physics."""
    result = {"uuid_windows": len(rows), "horizons": {},
              "scope": "20-step self-fed angles with externally replayed world geometry; no output clamps"}
    for method in ("zero", "persistence", "ridge_history", "ridge_geometry", "gru"):
        simulated = rows.copy()
        outside = 0
        horizons = {}
        for h in range(1, HORIZON+1):
            t = WINDOW+h
            x = features(simulated[:, t-WINDOW-1:t])
            if method == "gru":
                delta = predict(net, x, device, batch_size)
            elif method.startswith("ridge_"):
                delta = ridge_prediction(simulated[:, :t],
                    "history" if method == "ridge_history" else "history_geometry")
            elif method == "zero":
                delta = np.zeros((len(rows), 2), dtype=np.float32)
            else:
                delta = simulated[:, t-1, 5:7].copy()
            simulated[:, t, :2] = simulated[:, t-1, :2]+delta
            simulated[:, t, 0] = (simulated[:, t, 0]+180) % 360-180
            simulated[:, t, 5:7] = delta
            outside += int((abs(simulated[:, t, 1]) > 90).sum())
            error = simulated[:, t, :2]-rows[:, t, :2]
            error[:, 0] = (error[:, 0]+180) % 360-180
            if not np.isfinite(error).all():
                raise ValueError("Rollout diverged to nonfinite values")
            if h in (1, 5, 20):
                horizons[str(h)] = {"yaw_pitch_mae_deg": abs(error).mean(0).tolist(),
                    "combined_p95_deg": float(np.quantile(np.hypot(*error.T), .95))}
        result["horizons"][method] = horizons
        result[method+"_pitch_outside_90"] = outside
    return result


def choose_device(requested):
    device = ("cuda" if torch.cuda.is_available() else "cpu") if requested == "auto" else requested
    if device == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA requested but unavailable. Run check_env.py or use --device cpu")
    return device


def train(a):
    if a.output.exists() and not a.resume:
        raise FileExistsError("Use a new output folder or --resume; existing runs are preserved")
    a.output.mkdir(parents=True, exist_ok=True)
    fingerprint = hashlib.sha256((a.data/"manifest.json").read_bytes()).hexdigest()
    device = choose_device(a.device)
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    vram_gib = torch.cuda.get_device_properties(0).total_memory/1024**3 if device == "cuda" else 0
    batch_size = a.batch_size or (2048 if vram_gib >= 8 else 512)
    if a.resume:
        net, ck = load_checkpoint(a.output/"last.pt", device)
        if (ck["manifest_sha256"] != fingerprint or ck["seed"] != a.seed
                or ck["steps_per_epoch"] != a.steps_per_epoch or ck["batch_size"] != batch_size
                or ck["validation_windows"] != a.validation_windows):
            raise ValueError("Resume data, seed, step budget, batch size and validation count must match")
        mean, std, scale, hidden = ck["mean"], ck["std"], ck["target_scale"], ck["hidden"]
        start_epoch, best, stale = ck["epoch"]+1, ck["best"], ck["stale"]
    else:
        mean, std, scale = statistics(a.data)
        hidden = a.hidden
        net = MotionGRU(mean, std, scale, hidden).to(device)
        start_epoch, best, stale = 1, float("inf"), 0
    optimizer = torch.optim.AdamW(net.parameters(), lr=a.lr, weight_decay=1e-4)
    if a.resume:
        optimizer.load_state_dict(ck["optimizer"])
    validation, groups, replay = sample_split(a.data, "validation", a.validation_windows)
    write_json(a.output/"environment.json", {"device": device, "torch": str(torch.__version__),
        "cuda": torch.version.cuda, "gpu": torch.cuda.get_device_name(0) if device == "cuda" else None,
        "batch_size": batch_size, "vram_gib": vram_gib, "threads": a.threads,
        "parameters": sum(p.numel() for p in net.parameters())})
    print(f"device={device}; batch={batch_size}; parameters={sum(p.numel() for p in net.parameters())}", flush=True)
    for epoch in range(start_epoch, a.epochs+1):
        started = time.monotonic()
        rng = np.random.default_rng(a.seed+epoch)
        shards = files(a.data, "train")
        rng.shuffle(shards)
        total_loss = samples = steps = 0
        net.train()
        for path in shards:
            rows, _ = load_shard(path)
            x, y = input_target(rows)
            indices = rng.permutation(len(rows))
            for start in range(0, len(rows), batch_size):
                chosen = indices[start:start+batch_size]
                xb = torch.from_numpy(x[chosen]).to(device)
                yb = torch.from_numpy(y[chosen]).to(device)
                optimizer.zero_grad(set_to_none=True)
                pred = net(xb)
                loss = torch.nn.functional.smooth_l1_loss(pred/net.target_scale, yb/net.target_scale)
                if not torch.isfinite(loss):
                    raise ValueError("Nonfinite training loss")
                loss.backward()
                torch.nn.utils.clip_grad_norm_(net.parameters(), 1., error_if_nonfinite=True)
                optimizer.step()
                total_loss += loss.item()*len(chosen)
                samples += len(chosen)
                steps += 1
                if steps % 100 == 0:
                    print(f"epoch={epoch} steps={steps} samples={samples:,} loss={total_loss/samples:.5f}", flush=True)
                if a.steps_per_epoch and steps >= a.steps_per_epoch:
                    break
            if a.steps_per_epoch and steps >= a.steps_per_epoch:
                break
        metrics = one_step(net, validation, groups, device, batch_size)
        score = float(np.mean(np.asarray(metrics["gru"]["mae_deg"])/scale))
        improved = score < best
        best, stale = (score, 0) if improved else (best, stale+1)
        report = {"epoch": epoch, "train_loss": total_loss/samples, "train_windows": samples,
                  "steps": steps, "seconds": time.monotonic()-started,
                  "validation": metrics, "selection_score_normalized_mae": score}
        with (a.output/"history.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(report, allow_nan=False)+"\n")
        print(json.dumps(report), flush=True)
        checkpoint = {"model": net.state_dict(), "optimizer": optimizer.state_dict(),
            "epoch": epoch, "best": best, "stale": stale, "hidden": hidden,
            "mean": mean, "std": std, "target_scale": scale, "features": FEATURES,
            "window": WINDOW, "manifest_sha256": fingerprint, "seed": a.seed,
            "batch_size": batch_size, "steps_per_epoch": a.steps_per_epoch,
            "validation_windows": a.validation_windows}
        torch.save(checkpoint, a.output/"last.tmp")
        (a.output/"last.tmp").replace(a.output/"last.pt")
        if a.keep_epochs:
            snapshot = a.output/f"epoch-{epoch:03d}.pt"
            shutil.copyfile(a.output/"last.pt", snapshot.with_suffix(".tmp"))
            snapshot.with_suffix(".tmp").replace(snapshot)
        if improved:
            torch.save(checkpoint, a.output/"best.tmp")
            (a.output/"best.tmp").replace(a.output/"best.pt")
        if stale >= a.patience:
            print("Early stopping: validation did not improve", flush=True)
            break
    net, ck = load_checkpoint(a.output/"best.pt", device)
    write_json(a.output/"validation.json", {"epoch": ck["epoch"],
        "one_step": one_step(net, validation, groups, device, batch_size),
        "rollout": rollout(net, replay, device, batch_size)})
    metadata = {"features": FEATURES, "window": WINDOW, "hidden": hidden,
                "mean": mean, "std": std, "target_scale": scale,
                "output": ["next_yaw_step_deg", "next_pitch_step_deg"],
                "normalization": "Embedded in model.forward; input raw features exactly once",
                "scope": "Offline motion predictor, missing verified target pitch; not a complete aiming controller"}
    write_json(a.output/"model-metadata.json", metadata)
    print(f"Saved {a.output/'best.pt'} and validation.json; test split was not evaluated", flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    t = sub.add_parser("train")
    t.add_argument("data", type=Path)
    t.add_argument("output", type=Path)
    t.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    t.add_argument("--batch-size", type=int)
    t.add_argument("--threads", type=int, default=8)
    t.add_argument("--epochs", type=int, default=20)
    t.add_argument("--hidden", type=int, default=48)
    t.add_argument("--lr", type=float, default=.001)
    t.add_argument("--seed", type=int, default=20260915)
    t.add_argument("--patience", type=int, default=5)
    t.add_argument("--validation-windows", type=int, default=20000)
    t.add_argument("--steps-per-epoch", type=int, default=0)
    t.add_argument("--resume", action="store_true")
    t.add_argument("--keep-epochs", action="store_true", help="Keep each epoch checkpoint for later rollout comparison")
    e = sub.add_parser("evaluate")
    e.add_argument("data", type=Path)
    e.add_argument("checkpoint", type=Path)
    e.add_argument("output", type=Path)
    e.add_argument("--split", choices=["validation", "test"], default="validation")
    e.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    a = p.parse_args()
    if a.command == "train":
        for key in ("threads", "epochs", "hidden", "lr", "patience", "validation_windows"):
            if getattr(a, key) <= 0:
                p.error(f"{key} must be positive")
        if a.steps_per_epoch < 0 or (a.batch_size is not None and a.batch_size < 1):
            p.error("Invalid batch/step budget")
        train(a)
    else:
        device = choose_device(a.device)
        torch.set_num_threads(8)
        net, ck = load_checkpoint(a.checkpoint, device)
        if ck["manifest_sha256"] != hashlib.sha256((a.data/"manifest.json").read_bytes()).hexdigest():
            raise ValueError("Checkpoint and prepared dataset do not match")
        rows, groups, replay = sample_split(a.data, a.split, 20000)
        write_json(a.output, {"split": a.split, "checkpoint_epoch": ck["epoch"],
                   "one_step": one_step(net, rows, groups, device, 1024),
                   "rollout": rollout(net, replay, device, 1024)})


if __name__ == "__main__":
    main()
