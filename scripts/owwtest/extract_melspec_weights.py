#!/usr/bin/env python3
"""
Extract the melspectrogram constants from openWakeWord's `melspectrogram.tflite`
so the mel step can be recomputed in pure Kotlin (see `voice/MelSpectrogram.kt`).

WHY THIS EXISTS. The melspectrogram model has a dynamic-length audio input. Every
TensorFlow-Lite / LiteRT runtime published as an Android AAR (tensorflow-lite
2.16.1 and 2.17.0; LiteRT 1.0.0 and 2.1.0) overflows a CONV_2D's byte count while
allocating tensors for that model once the input is resized ("BytesRequired number
of elements overflowed. Node number 3 (CONV_2D) failed to prepare") — a
shape-inference bug in those runtimes, proven on the CI emulator with no device.
The Python `ai-edge-litert` runtime does NOT have the bug, so we use it here to read
the model's own weights and reproduce the exact computation in Kotlin instead.

The model is a textbook librosa power-to-db mel spectrogram:
  frames (win 512, hop 160, valid)  ->  DFT via two 512-tap conv bases (cos, sin),
  257 bins  ->  power = re^2 + im^2  ->  mel = power @ mel_matrix[257,32]  ->
  10*log10(max(mel, 1e-10))  ->  max(., global_max - 80).
The conv bias is all zeros, so it is dropped. Reproducing this in numpy with the
extracted weights matches the model to ~1.5e-5, and end-to-end detection on a real
"hey jarvis" clip is identical (0.998 / 0.000) — verified before this was ported.

Output: `app/src/main/assets/openwakeword/melspec_weights.bin`, a single little-endian
float32 blob laid out as [cos(257*512)] ++ [sin(257*512)] ++ [mel(257*32)].
"""
import os
import sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "openwakeword")
# The source model is kept here (out of the shipped APK) purely so these weights
# stay reproducible: re-running this script must reproduce melspec_weights.bin.
REFERENCE_MODEL = os.path.join(HERE, "reference_model", "melspectrogram.tflite")

# Tensor indices in melspectrogram.tflite (stable for this bundled model):
#   13 = cos DFT basis [257,1,512,1], 14 = sin DFT basis, 10 = mel matrix [257,32].
COS_IDX, SIN_IDX, MEL_IDX, BIAS_IDX = 13, 14, 10, 12
NBINS, WIN, NMEL = 257, 512, 32

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    print("ai-edge-litert not installed; run: pip install ai-edge-litert numpy", file=sys.stderr)
    sys.exit(2)


def main():
    it = Interpreter(model_path=REFERENCE_MODEL)
    it.resize_tensor_input(0, [1, 1760])
    it.allocate_tensors()

    cos = it.get_tensor(COS_IDX).reshape(NBINS, WIN).astype("<f4")
    sin = it.get_tensor(SIN_IDX).reshape(NBINS, WIN).astype("<f4")
    mel = it.get_tensor(MEL_IDX).reshape(NBINS, NMEL).astype("<f4")
    bias = it.get_tensor(BIAS_IDX)
    assert np.all(bias == 0), "conv bias is non-zero; the Kotlin port must add it"

    out = os.path.join(ASSETS, "melspec_weights.bin")
    with open(out, "wb") as f:
        f.write(cos.tobytes())
        f.write(sin.tobytes())
        f.write(mel.tobytes())
    total = cos.size + sin.size + mel.size
    print(f"wrote {out}: {total} float32 "
          f"(cos {cos.size} + sin {sin.size} + mel {mel.size}) = {total * 4} bytes")


if __name__ == "__main__":
    main()
