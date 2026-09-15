"""Small end-to-end CPU test, including split and chunk-boundary invariants."""
import argparse
import csv
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np
import torch

from model import features, load_checkpoint
from prepare import prepare, partition, wrap
from train import load_shard


def run(output):
    output.mkdir(parents=True, exist_ok=False)
    source = output/"synthetic.csv"
    columns = ["uuid", "yaw", "pitch", "delta_yaw", "delta_pitch", "accel_yaw", "accel_pitch",
               "target_x", "target_y", "target_z", "position_x", "position_y", "position_z",
               "sensitivity", "time", "new_sequence"]
    previous = {}
    with source.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        for tick in range(160):
            for group in range(60):
                uid = f"synthetic-{group}"
                yaw = wrap(170+tick*1.3+group*.2+math.sin(tick*.2)*2)
                pitch = math.sin(tick*.12+group*.03)*12
                older = previous.get(uid, (yaw, pitch))
                dy, dp = wrap(yaw-older[0]), pitch-older[1]
                # Reset at tick 80, time gap at 120; these must split windows.
                timestamp = 1000000+tick*50+(500 if tick >= 120 else 0)
                writer.writerow([uid, yaw, pitch, dy, dp, -99999, 99999, 3, 0, 1,
                                 0, 0, 0, -63, timestamp, tick in (0, 80)])
                previous[uid] = (yaw, pitch)
    prepare(source, output/"small-chunks", chunk_size=173, shard_size=67)
    prepare(source, output/"large-chunks", chunk_size=3000, shard_size=67)
    seen = {}
    for small in sorted((output/"small-chunks").glob("*.npz")):
        a, ag = load_shard(small)
        b, bg = load_shard(output/"large-chunks"/small.name)
        np.testing.assert_array_equal(a, b)
        np.testing.assert_array_equal(ag, bg)
        split = small.stem.split("-")[0]
        for group in ag:
            if group in seen:
                assert seen[group] == split
            seen[group] = split
        x = features(a[:, :17])
        altered = a.copy()
        altered[:, 17:, :] += 1000
        np.testing.assert_array_equal(x, features(altered[:, :17]))
        assert np.all((a[..., 4] >= 40) & (a[..., 4] <= 60))
    assert len(seen) == 60 and set(seen.values()) == {"train", "validation", "test"}
    # 3 windows before the reset, then 1 before and 1 after the deliberate time gap.
    total_windows = sum(len(load_shard(f)[0]) for f in (output/"small-chunks").glob("*.npz"))
    assert total_windows == 60*5
    expected_groups = {partition(f"synthetic-{g}")[1]: partition(f"synthetic-{g}")[0] for g in range(60)}
    assert seen == expected_groups
    entry = Path(__file__).with_name("train.py")
    command = [sys.executable, str(entry), "train", str(output/"large-chunks"), str(output/"run"),
               "--device", "cpu", "--batch-size", "64", "--threads", "2", "--epochs", "2",
               "--steps-per-epoch", "4"]
    subprocess.run(command, check=True)
    command[command.index("--epochs")+1] = "3"
    subprocess.run(command+["--resume"], check=True)
    net, ck = load_checkpoint(output/"run"/"best.pt")
    rows, _ = load_shard(next((output/"large-chunks").glob("validation-*.npz")))
    x = torch.from_numpy(features(rows[:4, :17]))
    net.eval()
    with torch.inference_mode():
        prediction = net(x)
        again, _ = load_checkpoint(output/"run"/"best.pt")
        again.eval()
        torch.testing.assert_close(prediction, again(x), rtol=0, atol=0)
    assert ck["epoch"] <= 3
    result = {"status": "passed", "torch": str(torch.__version__), "device": "cpu",
              "checks": ["interleaved UUID split isolation", "chunk invariance", "finite windows",
                         "future input isolation", "training", "checkpoint reload", "resume", "20-step replay"]}
    (output/"smoke-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("output", type=Path)
    run(p.parse_args().output.resolve())
