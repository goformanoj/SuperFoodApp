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


def peak_score(pcm_int16):
    """Peak 'hey jarvis' score over a clip, mirroring OpenWakeWord.process()."""
    mel = _interp("melspectrogram.tflite", [1, MEL_INPUT])
    emb = _interp("embedding_model.tflite", [1, 76, 32, 1])
    ww = _interp("hey_jarvis_v0.1.tflite", [1, 16, 96])
    raw = np.zeros(MEL_INPUT, np.float32)
    melbuf = np.zeros((0, 32), np.float32)
    featbuf = np.zeros((0, 96), np.float32)
    best = 0.0
    for i in range(len(pcm_int16) // CHUNK):
        chunk = pcm_int16[i * CHUNK:(i + 1) * CHUNK].astype(np.float32)
        raw = np.concatenate([raw, chunk])[-MEL_INPUT:]
        m = _run(mel, raw[None, :])[0, 0] / 10.0 + 2.0
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
