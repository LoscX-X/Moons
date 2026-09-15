"""Causal fixed-window GRU: all input features exist before the predicted step."""
import numpy as np
import torch
from torch import nn

FEATURES = ["yaw_step_deg", "pitch_step_deg", "yaw_step_change_deg", "pitch_step_change_deg",
            "horizontal_reference_error_deg", "horizontal_distance", "observed_dt_ms"]


def features(rows):
    current, previous = rows[:, 1:], rows[:, :-1]
    error = (current[..., 2]-current[..., 0]+180) % 360 - 180
    return np.concatenate((current[..., 5:7], current[..., 5:7]-previous[..., 5:7],
                           error[..., None], current[..., 3:5]), axis=-1).astype(np.float32)


class MotionGRU(nn.Module):
    def __init__(self, mean, std, target_scale, hidden=48):
        super().__init__()
        self.register_buffer("mean", torch.tensor(mean, dtype=torch.float32))
        self.register_buffer("std", torch.tensor(std, dtype=torch.float32))
        self.register_buffer("target_scale", torch.tensor(target_scale, dtype=torch.float32))
        self.gru = nn.GRU(len(FEATURES), hidden, batch_first=True)
        self.head = nn.Linear(hidden, 2)

    def forward(self, raw):
        _, hidden = self.gru((raw-self.mean)/self.std)
        return self.head(hidden[-1])*self.target_scale


def load_checkpoint(path, device="cpu"):
    ck = torch.load(path, map_location=device, weights_only=True)
    net = MotionGRU(ck["mean"], ck["std"], ck["target_scale"], ck["hidden"]).to(device)
    net.load_state_dict(ck["model"])
    return net, ck
