"""
Tes server GPT-SoVITS dari PC sebelum dipakai di HP.

    pip install requests
    python tes_gpt_sovits.py http://127.0.0.1:9880 wavs/ref.wav "transkrip audio referensi" en

Hasil: hasil_sovits.wav
Bahasa yang didukung GPT-SoVITS: zh, en, ja, ko, yue (bukan bahasa Indonesia).
"""
import sys

import requests

if len(sys.argv) != 5:
    print(__doc__)
    sys.exit(1)

url, ref, prompt, lang = sys.argv[1:]
r = requests.post(
    f"{url.rstrip('/')}/tts",
    json={
        "text": "Hello, I am Nexa. How can I help you today?",
        "text_lang": lang,
        "ref_audio_path": ref,
        "prompt_text": prompt,
        "prompt_lang": lang,
        "text_split_method": "cut5",
        "media_type": "wav",
        "streaming_mode": False,
    },
    timeout=120,
)
if r.status_code != 200:
    print("Gagal:", r.status_code, r.text[:300])
    sys.exit(1)
open("hasil_sovits.wav", "wb").write(r.content)
print("OK → hasil_sovits.wav")
