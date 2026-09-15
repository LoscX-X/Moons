"""Predict the next two angular steps from 16 raw, ordered observations."""
import argparse
import json
from pathlib import Path

import numpy as np
import torch

from model import FEATURES, load_checkpoint


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("checkpoint", type=Path)
    p.add_argument("input", type=Path, help="JSON array [16,7], oldest observation first")
    a = p.parse_args()
    x = np.asarray(json.loads(a.input.read_text(encoding="utf-8-sig")), dtype=np.float32)
    net, ck = load_checkpoint(a.checkpoint)
    if x.shape != (ck["window"], len(FEATURES)) or not np.isfinite(x).all():
        raise ValueError("Expected finite raw feature array [16,7]")
    net.eval()
    with torch.inference_mode():
        delta = net(torch.from_numpy(x[None]))[0].tolist()
    if not np.isfinite(delta).all():
        raise ValueError("Nonfinite model output")
    print(json.dumps({"next_yaw_step_deg": delta[0], "next_pitch_step_deg": delta[1],
                      "scope": "Raw motion forecast; no game integration or output constraints"}, indent=2))
