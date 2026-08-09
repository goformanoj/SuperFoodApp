#!/usr/bin/env python3
"""
openWakeWord pipeline check — runs the SAME streaming inference the app's
`voice/OpenWakeWord.kt` performs, on real audio, and asserts the "hey jarvis"
model detects a real utterance and ignores silence.

This validates the pipeline math + the detection threshold without a device or an
emulator. It does NOT validate the Android TFLite runtime load (a different, newer
runtime runs here) — the emulator instrumented test covers that.

Fixtures are raw 16 kHz mono little-endian int16 PCM (see fixtures/). Models are
read straight from the app assets so there is one source of truth.

Exit code 0 on pass, 1 on failure.
"""
import os
import sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
MODELS = os.path.join(REPO, "app", "src", "main", "assets", "openwakeword")
FIXTURES = os.path.join(HERE, "fixtures")

# Must match OpenWakeWord.kt.
CHUNK = 1280
MEL_INPUT = 1760
THRESHOLD = 0.5

try:
    from ai_edge_litert.interpreter import Interpreter
except ImportError:
    print("ai-edge-litert not installed; run: pip install ai-edge-litert numpy", file=sys.stderr)
    sys.exit(2)


def _interp(name, shape):
    it = Interpreter(model_path=os.path.join(MODELS, name))
    it.resize_tensor_input(0, shape)
    it.allocate_tensors()
    return it


def _run(it, x):
    it.set_tensor(it.get_input_details()[0]["index"], x.astype(np.float32))
    it.invoke()
    return it.get_tensor(it.get_output_details()[0]["index"])


# Weights blob layout must match MelSpectrogram.kt / extract_melspec_weights.py.
_WIN, _HOP, _FREQ, _NMEL, _FRAMES = 512, 160, 257, 32, 8


def _load_mel_weights():
    """cos[257,512], sin[257,512], mel[257,32] from the shipped weights asset."""
    blob = np.fromfile(os.path.join(MODELS, "melspec_weights.bin"), dtype="<f4")
    n = _FREQ * _WIN
    cos = blob[:n].reshape(_FREQ, _WIN)
    sin = blob[n:2 * n].reshape(_FREQ, _WIN)
    mel = blob[2 * n:2 * n + _FREQ * _NMEL].reshape(_FREQ, _NMEL)
    return cos, sin, mel


def _melspec(raw, weights):
    """The SAME melspectrogram MelSpectrogram.kt computes: 1760 samples -> [8, 32]."""
    cos, sin, mel = weights
    frames = np.stack([raw[k * _HOP:k * _HOP + _WIN] for k in range(_FRAMES)])
    power = (frames @ cos.T) ** 2 + (frames @ sin.T) ** 2
    ld = 10.0 * np.log10(np.maximum(power @ mel, 1e-10))
    return np.maximum(ld, ld.max() - 80.0)


def peak_score(pcm_int16):
    """Peak 'hey jarvis' score over a clip, mirroring OpenWakeWord.process().

    Uses the Kotlin-side melspectrogram (the shipped `melspec_weights.bin`), NOT the
    `melspectrogram.tflite` model — that model's dynamic-length input overflows a
    CONV_2D on every Android TFLite/LiteRT runtime, which is why the mel step moved to
    Kotlin. So this check validates the exact math the app ships.
    """
    weights = _load_mel_weights()
    emb = _interp("embedding_model.tflite", [1, 76, 32, 1])
    ww = _interp("hey_jarvis_v0.1.tflite", [1, 16, 96])
    raw = np.zeros(MEL_INPUT, np.float32)
    melbuf = np.zeros((0, 32), np.float32)
    featbuf = np.zeros((0, 96), np.float32)
    best = 0.0
    for i in range(len(pcm_int16) // CHUNK):
        chunk = pcm_int16[i * CHUNK:(i + 1) * CHUNK].astype(np.float32)
        raw = np.concatenate([raw, chunk])[-MEL_INPUT:]
        m = _melspec(raw, weights) / 10.0 + 2.0
        melbuf = np.vstack([melbuf, m])[-970:]
        if melbuf.shape[0] >= 76:
            e = _run(emb, melbuf[-76:][None, :, :, None])[0, 0, 0]
            featbuf = np.vstack([featbuf, e[None, :]])[-120:]
        if featbuf.shape[0] >= 16:
            best = max(best, float(_run(ww, featbuf[-16:][None, :, :])[0, 0]))
    return best


def load_pcm(name):
    return np.fromfile(os.path.join(FIXTURES, name), dtype="<i2")


def main():
    positive = peak_score(load_pcm("hey_jarvis.pcm"))
    silence = peak_score(load_pcm("silence.pcm"))
    print(f"hey_jarvis.pcm peak score: {positive:.4f} (threshold {THRESHOLD})")
    print(f"silence.pcm    peak score: {silence:.4f}")

    ok = True
    if positive < THRESHOLD:
        print("FAIL: a real 'hey jarvis' did not cross the detection threshold", file=sys.stderr)
        ok = False
    if silence >= 0.1:
        print("FAIL: silence scored too high (false positive)", file=sys.stderr)
        ok = False
    if ok:
        print("PASS: detects the wake word, ignores silence")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
