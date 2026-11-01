#!/usr/bin/env python3
"""Summarize retained natural-image codec measurements and build SDR comparison panels.

Uses the existing Pillow installation; does not decode/render HDR or infer panel appearance.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--controls-directory", type=Path, help="Preserved exact-render frozen A controls for production-only captures")
    args = parser.parse_args()
    directory = args.directory
    rows = []
    for path in sorted(directory.glob("*-settings.json")):
        obj = json.loads(path.read_text())
        prefix = path.name.removesuffix("-settings.json")
        c = "candidate" in obj or obj.get("rendition", "").startswith("C production")
        production_c = obj.get("rendition", "").startswith("C production")
        error = obj.get("fullGainReconstruction", {}).get("bilinearLogGainApproximation", {})
        bins = obj["decodedBaseVsSocial"]
        cells = obj.get("fullGainReconstruction", {}).get("selected4x4CellMeans", {}).get("all", {})
        rows.append({
            "set": prefix,
            "kind": obj.get("rendition", "C test-only direct encode" if c else "A production export request"),
            "input": obj["fixture"],
            "pre_sharpen_ratio": obj["preSharpenRatio"],
            "actual_format": obj["requested-ultra"]["actualFormat"] if production_c or not c else "ULTRA_HDR_JPEG",
            "encoded_ratio_max": obj.get("gain", {}).get("ratioMax", [1])[0],
            "shadow_mean_base_over_social_Y": bins["shadowY0.02to0.18"]["ratioOfMeanY"],
            "midtone_mean_base_over_social_Y": bins["midtoneY0.18to0.6"]["ratioOfMeanY"],
            "highlight_mean_base_over_social_Y": bins["highlightY0.6to1"]["ratioOfMeanY"],
            "midtone_fraction_over_5pct_darker": bins["midtoneY0.18to0.6"]["fractionMoreThan5PercentDarker"],
            "recon_linear_rgb_mae": error.get("meanAbsoluteLinearChannelError", "n/a"),
            "recon_linear_rgb_p95": error.get("p95AbsoluteLinearChannelError", "n/a"),
            "recon_linear_rgb_p99": error.get("p99AbsoluteLinearChannelError", "n/a"),
            "recon_relative_Y_p95_floor005": error.get("p95RelativeLuminanceErrorWithFloor", "n/a"),
            "recon_relative_Y_p99_floor005": error.get("p99RelativeLuminanceErrorWithFloor", "n/a"),
            "cell_mean_signed_Y_bias": cells.get("meanSignedLinearLuminanceBias", "n/a"),
            "cell_mean_rgb_mae": cells.get("meanAbsoluteLinearChannelError", "n/a"),
            "cell_mean_rgb_p95": cells.get("p95AbsoluteLinearChannelError", "n/a"),
            "cell_mean_rgb_p99": cells.get("p99AbsoluteLinearChannelError", "n/a"),
            "cell_mean_rgb_max": cells.get("maxAbsoluteLinearChannelError", "n/a"),
            "cell_Y_relative_p95_floor005": cells.get("p95RelativeLuminanceErrorWithFloor", "n/a"),
            "cell_Y_relative_p99_floor005": cells.get("p99RelativeLuminanceErrorWithFloor", "n/a"),
            "cell_Y_relative_max_floor005": cells.get("maxRelativeLuminanceErrorWithFloor", "n/a"),
            "recon_linear_rgb_max": error.get("maxAbsoluteLinearChannelError", "n/a"),
            "recon_relative_luminance_mae_Y_ge_005": error.get("meanRelativeLuminanceError", "n/a"),
            "recon_relative_luminance_max_Y_ge_005": error.get("maxRelativeLuminanceError", "n/a"),
            "interior_relative_luminance_mae": obj.get("fullGainReconstruction", {}).get("bilinearInterior", {}).get("meanRelativeLuminanceError", "n/a"),
            "edge_relative_luminance_mae": obj.get("fullGainReconstruction", {}).get("bilinearEdges", {}).get("meanRelativeLuminanceError", "n/a"),
        })
    if not rows:
        raise SystemExit("No sidecars found")
    with (directory / "summary.tsv").open("w") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="\t")
        writer.writeheader()
        writer.writerows(rows)
    sources = json.loads((directory / "sources.json").read_text())["fixtures"]
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 18)
    except OSError:
        font = ImageFont.load_default()
    for fixture in sources:
        name = Path(fixture["file"]).stem
        new_protocol = json.loads((directory / f"{name}-stress-C-settings.json").read_text()).get("rendition", "").startswith("C production")
        columns = [("Social JPEG (C base)" if new_protocol else "Social JPEG (clipped)", f"{name}-stress_social.jpg"),
                   ("Frozen A decoded SDR base" if new_protocol else "Current A decoded SDR base", f"{name}-stress_decoded-sdr-base.png"),
                   ("Production C decoded SDR base" if new_protocol else "Test-only C decoded SDR base", f"{name}-stress-C_decoded-sdr-base.png")]
        panel = Image.new("RGB", (900, 660), "#202020")
        draw = ImageDraw.Draw(panel)
        draw.text((12, 8), f"{name}: AZURE contrast1 exposure+1, HDR Boost off", fill="white", font=font)
        for i, (title, filename) in enumerate(columns):
            image_path = directory / filename
            if not image_path.exists() and args.controls_directory is not None:
                image_path = args.controls_directory / filename
            with Image.open(image_path) as image:
                thumbnail = image.convert("RGB").resize((288, 512), Image.Resampling.LANCZOS)
            x = 6 + i * 300
            draw.text((x, 40), title, fill="white", font=font)
            panel.paste(thumbnail, (x, 74))
        draw.text((12, 598), f"Credit: {fixture['author']} / {fixture['license']}", fill="white", font=font)
        draw.text((12, 628), "SDR renditions only; reduced for inspection; see sources.json and original files.", fill="white", font=font)
        panel.save(directory / f"{name}-stress-sdr-panel.png")
    files = {p.name: {"sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "bytes": p.stat().st_size}
             for p in sorted(directory.iterdir()) if p.is_file() and p.name != "artifact-manifest.json"}
    (directory / "artifact-manifest.json").write_text(json.dumps({"schema": 1, "files": files}, indent=2) + "\n")
    print(f"Summarized {len(rows)} sets, wrote {len(sources)} SDR panels and {len(files)} artifact hashes")


if __name__ == "__main__":
    main()
