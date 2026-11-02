#!/usr/bin/env python3
"""Independently replay selected-point and true4x4-cell gain metrics from retained artifacts.

Uses stdlib + the existing Pillow installation. Gain interpolation is the recorded analytical
bilinear estimator, not a physical Android sampler. The scalar-gain fixtures use common metadata.
"""
import argparse
import gzip
import json
import math
import struct
from pathlib import Path

from PIL import Image


def quantile(values, probability):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * probability) - 1)] if ordered else 0.0


def summarize(channels, luminances, biases):
    return {
        "meanAbsoluteLinearChannelError": sum(channels) / len(channels),
        "p95AbsoluteLinearChannelError": quantile(channels, .95),
        "p99AbsoluteLinearChannelError": quantile(channels, .99),
        "maxAbsoluteLinearChannelError": max(channels),
        "p95RelativeLuminanceErrorWithFloor": quantile(luminances, .95),
        "p99RelativeLuminanceErrorWithFloor": quantile(luminances, .99),
        "maxRelativeLuminanceErrorWithFloor": max(luminances),
        "meanSignedLinearLuminanceBias": sum(biases) / len(biases),
    }


def replay(sidecar):
    obj = json.loads(sidecar.read_text())
    directory = sidecar.parent
    prefix = sidecar.name.removesuffix("-settings.json")
    base_image = Image.open(directory / f"{prefix}_decoded-sdr-base.png").convert("RGB")
    gain_image = Image.open(directory / f"{prefix}_decoded-gain.png").convert("RGBA")
    width, height = base_image.size
    gw, gh = gain_image.size
    base = base_image.tobytes()
    gain = gain_image.tobytes()[0 if "decodedGainPngEncoding" in obj else (3 if obj["gain"]["config"] == "ALPHA_8" else 0)::4]
    arrays = obj["gain"]
    for key in ("ratioMin", "ratioMax", "gamma", "epsilonSdr", "epsilonHdr"):
        assert arrays[key] == [arrays[key][0]] * 3, "Replay supports the retained scalar gain fixtures"
    log_min = math.log(arrays["ratioMin"][0])
    log_span = math.log(arrays["ratioMax"][0]) - log_min
    gamma = arrays["gamma"][0]
    epsilon_sdr = arrays["epsilonSdr"][0]
    epsilon_hdr = arrays["epsilonHdr"][0]
    snapshot = obj["targetSamples"]
    raw_target = gzip.decompress((directory / snapshot["file"]).read_bytes())
    raw_cells = gzip.decompress((directory / snapshot["cellMeansFile"]).read_bytes())
    target = struct.unpack(f">{len(raw_target) // 4}f", raw_target)
    cells = struct.unpack(f">{len(raw_cells) // 4}f", raw_cells)
    assert len(target) == len(cells) == snapshot["samples"] * 3
    lookup = [(v / 255 / 12.92 if v / 255 <= .04045 else ((v / 255 + .055) / 1.055) ** 2.4) for v in range(256)]

    def gain_code(x, y):
        sx = (x + .5) * gw / width - .5
        sy = (y + .5) * gh / height - .5
        x0, y0 = math.floor(sx), math.floor(sy)
        fx, fy = sx - x0, sy - y0
        def sample(px, py):
            return gain[min(gh - 1, max(0, py)) * gw + min(gw - 1, max(0, px))] / 255
        return ((sample(x0, y0) * (1 - fx) + sample(x0 + 1, y0) * fx) * (1 - fy)
                + (sample(x0, y0 + 1) * (1 - fx) + sample(x0 + 1, y0 + 1) * fx) * fy)

    def reconstruct(x, y):
        code = gain_code(x, y)
        q = math.exp(log_min + code ** gamma * log_span)
        p = (y * width + x) * 3
        return [(lookup[base[p + c]] + epsilon_sdr) * q - epsilon_hdr for c in range(3)]

    def y_value(rgb):
        return .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2]

    point_channels, point_y, point_bias = [], [], []
    cell_channels, cell_y, cell_bias, cell_max_rgb = [], [], [], []
    i = 0
    for by in range(snapshot["blocksHigh"]):
        y = by * 8 + 1 + by % 4
        for bx in range(snapshot["blocksWide"]):
            x = bx * 8 + 1 + bx % 4
            actual = reconstruct(x, y)
            wanted = target[i:i + 3]
            point_channels.extend(abs(a - b) for a, b in zip(actual, wanted))
            delta_y = y_value(actual) - y_value(wanted)
            point_y.append(abs(delta_y) / max(.05, y_value(wanted)))
            point_bias.append(delta_y)
            sums = [0.0, 0.0, 0.0]
            for cy in range(y // 4 * 4, y // 4 * 4 + 4):
                for cx in range(x // 4 * 4, x // 4 * 4 + 4):
                    rgb = reconstruct(cx, cy)
                    for c in range(3):
                        sums[c] += rgb[c]
            mean = [value / 16 for value in sums]
            wanted_cell = cells[i:i + 3]
            cell_errors = [abs(a - b) for a, b in zip(mean, wanted_cell)]
            cell_channels.extend(cell_errors)
            cell_max_rgb.append(max(cell_errors))
            delta_y = y_value(mean) - y_value(wanted_cell)
            cell_y.append(abs(delta_y) / max(.05, y_value(wanted_cell)))
            cell_bias.append(delta_y)
            i += 3
    results = {"points": summarize(point_channels, point_y, point_bias), "cells": summarize(cell_channels, cell_y, cell_bias)}
    results["cells"].update(p95PerCellMaximumAbsoluteLinearChannelError=quantile(cell_max_rgb, .95),
                            p99PerCellMaximumAbsoluteLinearChannelError=quantile(cell_max_rgb, .99),
                            maxPerCellMaximumAbsoluteLinearChannelError=max(cell_max_rgb))
    guards = {"cellYp95": results["cells"]["p95RelativeLuminanceErrorWithFloor"] <= .05,
              "cellYp99": results["cells"]["p99RelativeLuminanceErrorWithFloor"] <= .10,
              "cellMaximumRGBp99": results["cells"]["p99PerCellMaximumAbsoluteLinearChannelError"] <= .05}
    reported = {"points": obj["fullGainReconstruction"]["bilinearLogGainApproximation"],
                "cells": obj["fullGainReconstruction"]["selected4x4CellMeans"]["all"]}
    differences = {group: {key: results[group][key] - reported[group][key] for key in results[group] if key in reported[group]} for group in results}
    for group in differences.values():
        assert max(abs(value) for value in group.values()) < 1e-4, differences
    return {"sidecar": str(sidecar), "samples": snapshot["samples"], "rendition": obj["rendition"], "result": results, "predeclaredGuards": guards, "allGuardsPass": all(guards.values()),
            "differenceFromDeviceFloat32Metrics": differences, "tolerance": 1e-4,
            "meaning": "Independent double-precision replay of retained selected samples/cells, not exhaustive image or panel output"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sidecar", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = []
    for path in args.sidecar:
        results.append(replay(path))
        print(f"Replayed {path.name}", flush=True)
    args.output.write_text(json.dumps({"schema": 1, "guardPolicy": {"perFixtureCellYp95Max": .05, "perFixtureCellYp99Max": .10, "perFixtureCellMaxRGBp99Max": .05, "meaning": "Bilinear full-gain selected true4x4 cell means; Y denominator max(targetMeanY,.05); RGB per-cell maximum across3channels, not pooled quantiles; no exhaustive-image/panel claim"}, "results": results}, indent=2) + "\n")


if __name__ == "__main__":
    main()
