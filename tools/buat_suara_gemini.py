"""
Buat suara kustom Gemini untuk Nexa, lalu salin ID voice_... ke aplikasi
(kolom "Suara").

Jalankan di PC:
    pip install -U "google-genai>=2.25.0"
    set GEMINI_API_KEY=AIza...        (Windows)   / export GEMINI_API_KEY=AIza... (Mac/Linux)

    # A) Desain suara dari deskripsi
    python buat_suara_gemini.py design "Perempuan usia 20-an, suara lembut dan ceria, logat Indonesia"

    # B) Tiru suara asli (WAJIB dengan izin pemilik suara)
    python buat_suara_gemini.py replicate referensi.wav persetujuan.wav

Rekaman untuk replicate (WAV 24kHz mono 16-bit disarankan):
  - referensi.wav  : 10–30 detik ucapan natural yang jernih
  - persetujuan.wav: orang yang SAMA membacakan kalimat persetujuan resmi
                     (lihat daftar kalimat & bahasa di
                     https://ai.google.dev/gemini-api/docs/voice-replication)
"""
import base64
import sys

from google import genai

MODEL = "gemini-3.8-flash-tts"


def b64(path: str) -> str:
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode("utf-8")


def design(description: str) -> None:
    client = genai.Client()
    voice = client.voices.create(
        store=True,
        voice={
            "model": MODEL,
            "type": "prompted",
            "display_name": "Nexa",
            "gender": "female",          # ganti "male" bila perlu
            "language_code": "id-ID",
            "prompted": {"input": description},
        },
    )
    print("ID suara:", voice.id)
    if voice.sample_audio and voice.sample_audio.data:
        with open("preview_nexa.wav", "wb") as f:
            f.write(base64.b64decode(voice.sample_audio.data))
        print("Contoh suara disimpan: preview_nexa.wav")


def replicate(reference_wav: str, consent_wav: str) -> None:
    client = genai.Client()
    voice = client.voices.create(
        store=True,
        voice={
            "model": MODEL,
            "type": "replicated",
            "display_name": "Nexa (replika)",
            "replicated": {
                "source_audio": {"mime_type": "audio/wav", "data": b64(reference_wav)},
                "consent_audio": {"mime_type": "audio/wav", "data": b64(consent_wav)},
            },
        },
    )
    print("ID suara:", voice.id)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "design":
        design(" ".join(sys.argv[2:]))
    elif len(sys.argv) == 4 and sys.argv[1] == "replicate":
        replicate(sys.argv[2], sys.argv[3])
    else:
        print(__doc__)
