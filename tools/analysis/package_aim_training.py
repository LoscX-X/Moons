"""Build a small, explicit-allowlist portable archive without source CSV/cache."""
import hashlib
import argparse
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT/'outputs'/'Aim-GRU-Training-Kit.zip')
args = parser.parse_args()
KIT = ROOT/"tools"/"aim_training"
VERIFY = KIT/"verification"
VERIFY.mkdir(exist_ok=True)
copies = {
    "smoke-result.json": "build/aim-training-delivery-check/smoke-result.json",
    "preprocessing-manifest.json": "build/aim-gru-data-verified/manifest.json",
    "one-epoch-best.pt": "build/aim-gru-one-epoch/best.pt",
    "one-epoch-validation.json": "build/aim-gru-one-epoch/validation.json",
    "one-epoch-history.jsonl": "build/aim-gru-one-epoch/history.jsonl",
    "one-epoch-environment.json": "build/aim-gru-one-epoch/environment.json",
    "model-metadata.json": "build/aim-gru-one-epoch/model-metadata.json",
}
for name, source in copies.items():
    shutil.copyfile(ROOT/source, VERIFY/name)
names = ["README.md", "START_HERE.md", "requirements.txt", "setup.ps1", "prepare.py", "model.py",
         "check_env.py", "train.py", "infer.py", "smoke_test.py", "compare_epochs.py", "example-input.json", "ridge-baseline.json"]
paths = [KIT/name for name in names]+[VERIFY/name for name in copies]
hashes = {str(p.relative_to(KIT)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
manifest = KIT/"SHA256SUMS.json"
manifest.write_text(json.dumps(hashes, indent=2), encoding="utf-8")
paths.append(manifest)
archive = args.output.resolve()
archive.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
    for path in paths:
        package.write(path, "Aim-GRU-Training-Kit/"+path.relative_to(KIT).as_posix())
with zipfile.ZipFile(archive) as package:
    assert package.testzip() is None
    for name, digest in hashes.items():
        assert hashlib.sha256(package.read("Aim-GRU-Training-Kit/"+name)).hexdigest() == digest
print(json.dumps({"archive": str(archive), "bytes": archive.stat().st_size,
                  "files": len(paths), "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()}, indent=2))
