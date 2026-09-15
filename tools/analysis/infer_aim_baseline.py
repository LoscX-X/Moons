"""Inference and isolated closed-loop probes for the saved aim regression.

Pure Python; no training library, game state or network access is required.
Model outputs are predictions in degrees/update, not bounded control commands.
"""
import argparse
import json
import math
from pathlib import Path


DEFAULT_MODEL = (Path(__file__).resolve().parents[2]
                 / "outputs/legit-aim-analysis/ridge-baseline.json")


class AimBaseline:
    def __init__(self, model_path=DEFAULT_MODEL, variant="history"):
        artifact = json.loads(Path(model_path).read_text(encoding="utf-8"))
        if variant not in ("history", "history_geometry"):
            raise ValueError("Unknown model variant")
        model = artifact[variant]
        self.variant = variant
        self.columns = model["feature_columns"]
        self.mean = model["mean"]
        self.std = model["std"]
        self.coefficients = model["coefficients"]
        size = 4 if variant == "history" else 7
        if (self.columns != list(range(size)) or len(self.mean) != size
                or len(self.std) != size or len(self.coefficients) != size + 1
                or any(len(row) != 2 for row in self.coefficients)):
            raise ValueError("Unexpected saved model layout")
        values = self.mean + self.std + [v for row in self.coefficients for v in row]
        if not all(math.isfinite(v) for v in values) or any(v <= 0 for v in self.std):
            raise ValueError("Non-finite coefficients or invalid normalization")

    def predict(self, previous, older, *, horizontal_error=None, horizontal_distance=None):
        """Both history pairs are (yaw step, pitch step), most recent pair first.

        Geometry describes the same observation as `previous`. Error is target
        yaw minus observed yaw on the shortest arc; distance is horizontal blocks.
        This model was fitted to roughly 50 ms observations. It has no dt input.
        """
        if len(previous) != 2 or len(older) != 2:
            raise ValueError("Each history pair needs yaw and pitch")
        features = list(previous) + list(older)
        if self.variant == "history_geometry":
            if horizontal_error is None or horizontal_distance is None:
                raise ValueError("Geometry variant requires horizontal error and distance")
            if not math.isfinite(horizontal_error) or not math.isfinite(horizontal_distance):
                raise ValueError("Geometry must be finite")
            if horizontal_distance < 0:
                raise ValueError("Distance cannot be negative")
            error = (horizontal_error + 180) % 360 - 180
            features += [error, horizontal_distance,
                         math.copysign(math.sqrt(abs(error)), error)]
        if not all(math.isfinite(v) for v in features):
            raise ValueError("History must be finite")
        normalized = [1.] + [(v-m)/s for v, m, s in zip(features, self.mean, self.std)]
        result = tuple(sum(x * weights[axis] for x, weights in zip(normalized, self.coefficients))
                       for axis in (0, 1))
        if not all(math.isfinite(v) for v in result):
            raise ValueError("Prediction overflow")
        return result


def probe(model_path):
    """Feed predictions back as history, unlike one-step evaluation on real history.

    Synthetic stationary target: yaw 90, pitch 45, horizontal distance 3.
    No quantizer, clamp, controller, collision, randomness or real player replay.
    This deliberately tests whether the predictor alone can serve as a controller.
    """
    runs = []
    for variant in ("history", "history_geometry"):
        model = AimBaseline(model_path, variant)
        for initial in ((0., 0.), (4., 1.)):
            yaw = pitch = 0.
            previous = older = initial
            samples = []
            first = None
            for tick in range(1, 101):
                error = (90-yaw+180) % 360-180
                prediction = model.predict(previous, older, horizontal_error=error,
                                           horizontal_distance=3.)
                yaw += prediction[0]
                pitch += prediction[1]
                older, previous = previous, prediction
                remaining = math.hypot((90-yaw+180) % 360-180, 45-pitch)
                if remaining <= 1 and first is None:
                    first = tick
                samples.append({"update": tick, "yaw": yaw, "pitch": pitch,
                                "yaw_step": prediction[0], "pitch_step": prediction[1]})
            runs.append({"variant": variant, "initial_step": initial,
                         "first_within_one_degree_update": first,
                         "final_yaw": yaw, "final_pitch": pitch,
                         "remaining_error_deg": remaining, "samples": samples})
    return {"scope": "Synthetic predictor-only closed loop, not game or dataset replay",
            "target_yaw": 90, "target_pitch": 45, "updates": 100,
            "nominal_duration_seconds": 5, "runs": runs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    commands = parser.add_subparsers(dest="command", required=True)
    predict_parser = commands.add_parser("predict")
    predict_parser.add_argument("--variant", choices=["history", "history_geometry"], default="history")
    predict_parser.add_argument("--previous", type=float, nargs=2, required=True, metavar=("YAW", "PITCH"))
    predict_parser.add_argument("--older", type=float, nargs=2, required=True, metavar=("YAW", "PITCH"))
    predict_parser.add_argument("--horizontal-error", type=float)
    predict_parser.add_argument("--horizontal-distance", type=float)
    probe_parser = commands.add_parser("probe")
    probe_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "predict":
        result = AimBaseline(args.model, args.variant).predict(
                args.previous, args.older, horizontal_error=args.horizontal_error,
                horizontal_distance=args.horizontal_distance)
        print(json.dumps({"variant": args.variant, "yaw_step": result[0],
                          "pitch_step": result[1], "units": "degrees/update"}, indent=2))
    else:
        result = probe(args.model)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, allow_nan=False), encoding="utf-8")
        print(json.dumps([{k: v for k, v in r.items() if k != "samples"}
                          for r in result["runs"]], indent=2))


if __name__ == "__main__":
    main()
