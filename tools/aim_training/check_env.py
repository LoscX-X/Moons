"""Print actual hardware and execute a small GRU forward/backward on the device."""
import json
import os
import platform
import sys

import torch


def check():
    result = {"python": sys.version, "platform": platform.platform(),
              "logical_cpus": os.cpu_count(), "torch": str(torch.__version__),
              "torch_cuda": torch.version.cuda, "cuda_available": torch.cuda.is_available()}
    device = "cuda" if torch.cuda.is_available() else "cpu"
    if device == "cuda":
        props = torch.cuda.get_device_properties(0)
        result.update(gpu=props.name, vram_gib=round(props.total_memory/1024**3, 2),
                      capability=list(torch.cuda.get_device_capability(0)))
    gru = torch.nn.GRU(7, 48, batch_first=True).to(device)
    output, _ = gru(torch.randn(32, 16, 7, device=device))
    output.square().mean().backward()
    if device == "cuda":
        torch.cuda.synchronize()
    result["gru_forward_backward"] = "passed on " + device
    print(json.dumps(result, indent=2), flush=True)
    return result


if __name__ == "__main__":
    check()
