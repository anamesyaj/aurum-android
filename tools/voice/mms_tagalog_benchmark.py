#!/usr/bin/env python3
"""Generate a small, reproducible MMS Tagalog listening benchmark.

This is intentionally a research harness, not production Android integration.
It downloads Meta's facebook/mms-tts-tgl checkpoint at runtime and writes WAV
samples plus machine-readable timing metadata for human listening evaluation.
"""

from __future__ import annotations

import json
import math
import platform
import time
from pathlib import Path

import numpy as np
import scipy.io.wavfile
import torch
from transformers import AutoTokenizer, VitsModel, set_seed

MODEL_ID = "facebook/mms-tts-tgl"
SEED = 555

SAMPLES = [
    (
        "filipino",
        "Kumusta! Ako si Aurum. Nandito ako para tulungan ka sa mga gawain mo araw-araw.",
    ),
    (
        "taglish",
        "Sige, iche-check ko muna ang schedule mo, then sasabihin ko kung ano ang pinaka-importanteng gawin ngayon.",
    ),
    (
        "casual",
        "Ayos, gets ko. Hahanapin ko ang sagot at babalikan kita agad.",
    ),
    (
        "english",
        "Good morning. I am Aurum, your personal assistant, and I am ready to help.",
    ),
]


def finite_float(value: float) -> float:
    if not math.isfinite(value):
        raise RuntimeError(f"non-finite metric: {value}")
    return round(float(value), 4)


def main() -> None:
    out_dir = Path("voice-benchmark-mms-tagalog")
    out_dir.mkdir(parents=True, exist_ok=True)

    set_seed(SEED)
    torch.set_num_threads(max(1, min(4, torch.get_num_threads())))

    load_started = time.perf_counter()
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = VitsModel.from_pretrained(MODEL_ID)
    model.eval()
    load_seconds = time.perf_counter() - load_started
    sample_rate = int(model.config.sampling_rate)

    results = []
    for name, text in SAMPLES:
        set_seed(SEED)
        encoded = tokenizer(text, return_tensors="pt")
        synth_started = time.perf_counter()
        with torch.inference_mode():
            waveform = model(**encoded).waveform.squeeze().cpu().numpy()
        synth_seconds = time.perf_counter() - synth_started

        waveform = np.asarray(waveform, dtype=np.float32)
        peak = float(np.max(np.abs(waveform))) if waveform.size else 0.0
        if peak > 1.0:
            waveform = waveform / peak
        pcm = np.clip(waveform * 32767.0, -32768, 32767).astype(np.int16)

        wav_path = out_dir / f"{name}.wav"
        scipy.io.wavfile.write(wav_path, sample_rate, pcm)

        duration_seconds = len(pcm) / sample_rate
        rtf = synth_seconds / duration_seconds if duration_seconds else float("inf")
        results.append(
            {
                "name": name,
                "text": text,
                "wav": wav_path.name,
                "sample_rate_hz": sample_rate,
                "duration_seconds": finite_float(duration_seconds),
                "synthesis_seconds": finite_float(synth_seconds),
                "realtime_factor": finite_float(rtf),
                "samples": int(len(pcm)),
            }
        )
        print(
            f"{name}: duration={duration_seconds:.2f}s "
            f"synth={synth_seconds:.2f}s rtf={rtf:.3f}"
        )

    metadata = {
        "model": MODEL_ID,
        "model_license": "CC-BY-NC-4.0",
        "purpose": "personal non-commercial Aurum voice research",
        "seed": SEED,
        "load_seconds": finite_float(load_seconds),
        "python": platform.python_version(),
        "torch": torch.__version__,
        "sample_rate_hz": sample_rate,
        "samples": results,
        "listening_questions": [
            "Does Filipino pronunciation sound more natural than Android fil-PH TTS?",
            "Does the voice feel less robotic and more conversational?",
            "How badly does Taglish/English code-switching degrade?",
            "Would this be acceptable as an interim Aurum voice?",
        ],
    }
    (out_dir / "benchmark.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (out_dir / "README.txt").write_text(
        "Aurum MMS Tagalog listening benchmark\n"
        "Model: facebook/mms-tts-tgl (CC-BY-NC-4.0)\n"
        "Research use only; this does not change the Android production voice.\n"
        "Listen to all WAV files and compare them with the current Aurum fil-PH voice.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
