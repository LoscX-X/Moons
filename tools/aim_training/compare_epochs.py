"""Compare saved epochs on identical validation rollouts; keep test data unused."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
import torch

from model import load_checkpoint
from prepare import WINDOW
from train import features, predict, rollout, sample_split, one_step, write_json


def rollout_details(net, rows, device):
    """Per-UUID endpoint errors, used only for descriptive validation breakdowns."""
    simulated = rows.copy()
    for h in range(1, 21):
        t = WINDOW+h
        delta = predict(net, features(simulated[:, t-WINDOW-1:t]), device, 512)
        simulated[:, t, :2] = simulated[:, t-1, :2]+delta
        simulated[:, t, 0] = (simulated[:, t, 0]+180) % 360-180
        simulated[:, t, 5:7] = delta
    error = simulated[:, -1, :2]-rows[:, -1, :2]
    error[:, 0] = (error[:, 0]+180) % 360-180
    initial_error = abs((rows[:, WINDOW, 2]-rows[:, WINDOW, 0]+180) % 360-180)
    bins = {}
    for label, mask in [('reference_error_0_to_5_deg', initial_error <= 5),
                        ('reference_error_5_to_30_deg', (initial_error > 5) & (initial_error <= 30)),
                        ('reference_error_over_30_deg', initial_error > 30)]:
        if mask.any():
            bins[label] = {'uuid_windows': int(mask.sum()), 'yaw_pitch_mae_deg': abs(error[mask]).mean(0).tolist()}
    return bins


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('data', type=Path)
    p.add_argument('run', type=Path)
    p.add_argument('--reference', type=Path, required=True, help='GRU 48 one-step-selected checkpoint')
    p.add_argument('--reference-early', type=Path, required=True, help='GRU 48 epoch 1 checkpoint')
    a = p.parse_args()
    torch.set_num_threads(8)
    data_fingerprint = hashlib.sha256((a.data/'manifest.json').read_bytes()).hexdigest()
    observed, groups, replay = sample_split(a.data, 'validation', 20000)
    snapshots = sorted(a.run.glob('epoch-*.pt'))
    if not snapshots:
        raise ValueError('No epoch snapshots; train with --keep-epochs')
    history = [json.loads(line) for line in (a.run/'history.jsonl').read_text().splitlines() if line]
    epochs = {row['epoch']: row for row in history}
    saved, reference_runs, best = [], {}, None

    def checked_load(path):
        model, ck = load_checkpoint(path)
        if ck['manifest_sha256'] != data_fingerprint:
            raise ValueError('Dataset fingerprint mismatch')
        return model, ck

    for path in snapshots:
        net, ck = checked_load(path)
        metrics = rollout(net, replay, 'cpu', 512)
        gru = metrics['horizons']['gru']
        baseline = metrics['horizons']['ridge_geometry']
        # Fixed before evaluating any epochs: equal weight for both axes and 5/20 horizons.
        score = float(np.mean([np.asarray(gru[str(h)]['yaw_pitch_mae_deg']) /
                    np.maximum(baseline[str(h)]['yaw_pitch_mae_deg'], 1e-6) for h in (5, 20)]))
        row = {'epoch': ck['epoch'], 'checkpoint': path.name, 'rollout_selection_score': score,
               'one_step': epochs[ck['epoch']]['validation']['gru'], 'rollout': metrics}
        saved.append(row)
        if best is None or score < best['rollout_selection_score']:
            best = row
        print(json.dumps({'epoch': ck['epoch'], 'rollout_score': score,
                          'h20_mae': gru['20']['yaw_pitch_mae_deg']}), flush=True)
    for name, path in [('gru48_epoch1', a.reference_early), ('gru48_one_step_best', a.reference)]:
        net, ck = checked_load(path)
        reference_runs[name] = {'epoch': ck['epoch'], 'one_step': one_step(net, observed, groups, 'cpu', 512)['gru'],
             'rollout': rollout(net, replay, 'cpu', 512), 'initial_error_breakdown': rollout_details(net, replay, 'cpu')}
    selected_path = a.run/best['checkpoint']
    shutil.copyfile(selected_path, a.run/'best-rollout.pt')
    net, ck = checked_load(selected_path)
    selected = {'epoch': ck['epoch'], 'one_step': one_step(net, observed, groups, 'cpu', 512),
                'rollout': best['rollout'], 'initial_error_breakdown': rollout_details(net, replay, 'cpu')}
    write_json(a.run/'best-rollout-validation.json', selected)
    summary = {'scope': 'Validation-only model selection; not an untouched test estimate',
               'selection_rule': 'Mean GRU/ridge_geometry MAE ratio, equal weights for yaw/pitch and horizons 5/20',
               'selected_rollout_epoch': ck['epoch'], 'epochs': saved, 'references': reference_runs}
    write_json(a.run/'epoch-rollout-comparison.json', summary)
    print(json.dumps({'selected_rollout_epoch': ck['epoch'], 'output': str(a.run/'best-rollout.pt')}), flush=True)


if __name__ == '__main__':
    main()
