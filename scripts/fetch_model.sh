#!/bin/sh
# Downloads Depth Anything V2 Small (Apache-2.0, ONNX, dynamic input size) into the app assets.
set -e
DIR="$(dirname "$0")/../app/src/main/assets/models"
F="$DIR/depth_anything_v2_vits_dynamic.onnx"
mkdir -p "$DIR"
if [ ! -s "$F" ]; then
  curl -L --fail --retry 4 -o "$F" \
    https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vits_dynamic.onnx
fi
ls -l "$F"
