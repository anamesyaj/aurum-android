#!/usr/bin/env python3
"""Generate a reproducible VoxCPM2 Filipino/English/Taglish listening benchmark.

Research harness only. This does not modify Aurum's production Android voice.
VoxCPM2 is evaluated because the upstream model explicitly supports both
Tagalog and English under Apache-2.0 and offers voice design / cloning.

Designed for a CUDA-backed Colab runtime, not the 1 GB Aurum Core host.
"""

from __future__ import annotations

import json
import math
import platform
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
from voxcpm import VoxCPM

MODEL_ID = "openbmb/VoxCPM2"
SEED = 555
VOICE_DESCRIPTION = (
    "A warm natural female personal assistant voice, calm, intelligent, friendly, "
    "conversational, with clear Filipino and English pronunciation"
)

SAMPLES = [
    (
        "filipino",
        "Kumusta! Ako si Aurum. Nandito ako para tulungan ka sa mga gawain mo araw-araw.",
    ),
    (
        "english",
        "Good morning. I am Aurum, your personal assistant, and I am ready to help.",
    ),
    (
        "taglish",
        "Sige, iche-check ko muna ang schedule mo, then sasabihin ko kung ano ang pinaka-importanteng gawin ngayon.",
    ),
    (
        "taglish_conversation",
        "Gets ko. I'll check everything first, tapos bibigyan kita ng maikling update kung ano ang dapat unahin.",
    ),
]


def finite_float(value: float) -> float:
    if not math.isfinite(value):
        raise RuntimeError(f"non-finite metric: {value}")
    return round(float(value), 4)


def main() -> None:
    if not torch.cuda.is_available():
        raise RuntimeError(
            "VoxCPM2 benchmark requires a CUDA runtime. In Google Colab choose "
            "Runtime > Change runtime type > GPU, then rerun."
        )

    out_dir = Path("voice-benchmark-voxcpm2")
    out_dir.mkdir(parents=True, exist_ok=True)

    torch.manual_seed(SEED)
    torch.cuda.manual_seed_all(SEED)

    load_started = time.perf_counter()
    model = VoxCPM.from_pretrained(MODEL_ID, load_denoiser=False)
    load_seconds = time.perf_counter() - load_started
    sample_rate = int(model.tts_model.sample_rate)

    results = []
    for name, text in SAMPLES:
        designed_text = f"({VOICE_DESCRIPTION}){text}"
        if torch.cuda.is_available():
            torch.cuda.synchronize()
        synth_started = time.perf_counter()
        wav = model.generate(
            text=designed_text,
            cfg_value=2.0,
            inference_timesteps=10,
            seed=SEED,
        )
        if torch.cuda.is_available():
            torch.cuda.synchronize()
        synth_seconds = time.perf_counter() - synth_started

        waveform = np.asarray(wav, dtype=np.float32).reshape(-1)
        wav_path = out_dir / f"{name}.wav"
        sf.write(wav_path, waveform, sample_rate)

        duration_seconds = len(waveform) / sample_rate
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
                "samples": int(len(waveform)),
            }
        )
        print(
            f"{name}: duration={duration_seconds:.2f}s "
            f"synth={synth_seconds:.2f}s rtf={rtf:.3f}"
        )

    metadata = {
        "model": MODEL_ID,
        "model_license": "Apache-2.0",
        "purpose": "Aurum multilingual voice research",
        "seed": SEED,
        "voice_description": VOICE_DESCRIPTION,
        "load_seconds": finite_float(load_seconds),
        "python": platform.python_version(),
        "torch": torch.__version__,
        "cuda": torch.version.cuda,
        "gpu": torch.cuda.get_device_name(0),
        "sample_rate_hz": sample_rate,
        "samples": results,
        "listening_questions": [
            "Is Filipino at least as natural and intelligible as the accepted MMS Filipino sample?",
            "Is English clearly natural English rather than Filipino-accented phonetic failure?",
            "Does Taglish switch languages cleanly inside one sentence?",
            "Does the same Aurum-like voice identity remain coherent across Filipino and English?",
            "Does the voice sound conversational enough to justify Android integration work?",
        ],
    }
    (out_dir / "benchmark.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (out_dir / "README.txt").write_text(
        "Aurum VoxCPM2 multilingual listening benchmark\n"
        "Model: openbmb/VoxCPM2 (Apache-2.0)\n"
        "Research only; this does not change the production Android voice.\n"
        "Compare Filipino, English, and both Taglish samples before integration.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
