"""Export the selected GRU to a bounded, big-endian Java resource and parity fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

import numpy as np
import torch

from model import FEATURES, load_checkpoint
from train import input_target, sample_split


def floats(values):
    return np.asarray(values, dtype='>f4').tobytes(order='C')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('checkpoint', type=Path)
    p.add_argument('data', type=Path)
    p.add_argument('resource', type=Path)
    p.add_argument('fixtures', type=Path)
    a = p.parse_args()
    torch.set_num_threads(8)
    net, ck = load_checkpoint(a.checkpoint)
    assert ck['hidden'] == 128 and ck['window'] == 16 and ck['epoch'] == 7
    assert ck['features'] == FEATURES
    assert ck['manifest_sha256'] == hashlib.sha256((a.data/'manifest.json').read_bytes()).hexdigest()
    checkpoint_hash = hashlib.sha256(a.checkpoint.read_bytes()).digest()
    header = struct.pack('>7i', 0x41475255, 1, 7, 128, 16, 2, 7)+checkpoint_hash
    names = ['mean','std','target_scale','gru.weight_ih_l0','gru.weight_hh_l0',
             'gru.bias_ih_l0','gru.bias_hh_l0','head.weight','head.bias']
    payload = header+b''.join(floats(net.state_dict()[name].numpy()) for name in names)
    assert len(payload) == 211588
    a.resource.parent.mkdir(parents=True, exist_ok=True)
    a.resource.write_bytes(payload)
    metadata = {'format':'AGRU big-endian v1', 'hidden':128, 'window':16, 'epoch':7,
        'parameters':52866, 'resource_bytes':len(payload), 'resource_sha256':hashlib.sha256(payload).hexdigest(),
        'checkpoint_sha256':checkpoint_hash.hex(), 'features':FEATURES, 'tensor_order':names,
        'normalization':'Embedded; raw feature inputs, outputs degrees/update',
        'architecture':'Single-layer PyTorch GRU (reset, update, new), zero hidden state per 16-row window, linear head',
        'scope':'Motion predictor; no target pitch error. Live helper applies bounded residual corrections to a primary aim mode.'}
    a.resource.with_suffix('.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    rows, _, _ = sample_split(a.data, 'validation', 1024)
    x, _ = input_target(rows)
    # Keep real observations plus a stationary input and a large-angle boundary case.
    extra = np.zeros((2,16,7), dtype=np.float32)
    extra[:,:,5] = 3
    extra[:,:,6] = 50
    extra[1,:,4] = 179
    x = np.concatenate((x,extra))
    net.eval()
    with torch.inference_mode():
        expected = net(torch.from_numpy(x)).numpy()
    fixture = struct.pack('>4i', 0x41494D54, 1, len(x), 16)+floats(x)+floats(expected)
    a.fixtures.parent.mkdir(parents=True, exist_ok=True)
    a.fixtures.write_bytes(fixture)
    print(json.dumps(metadata, indent=2))
    print(f'Exported {len(x)} PyTorch reference cases to {a.fixtures}')


if __name__ == '__main__':
    main()
