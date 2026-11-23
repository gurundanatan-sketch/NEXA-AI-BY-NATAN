"""
NEXA AI BY NATAN — Server Media (Python + Flask + FFmpeg)
=========================================================

Pabrik media & riset untuk aplikasi NEXA. Jalan di Termux, PC, VPS, atau Google Colab.

Endpoint (semua butuh header X-Nexa-Token kecuali /health):
  GET  /health
  POST /upload            multipart "file"  → {file_id}          (audio suara asli, musik latar, sampel suara)
  GET  /voices            daftar suara (bawaan + kloning)
  POST /voices/clone      multipart: file, name, engine=minimax|gemini, [consent]  → kloning suara
  DELETE /voices/<id>
  POST /tts               {text, voice:"engine:id"} → audio (wav/mp3)
  POST /script            {topic, duration_min} → {script}
  POST /image             {prompt, enhance} → {file}
  POST /video             lihat VideoRequest di bawah → {job_id}
  GET  /jobs  /jobs/<id>  status
  GET  /files/<nama>      unduh hasil
  GET  /gallery           daftar hasil

Spesifikasi suara ("voice"):
  gemini:Kore | gemini:voice_xxx        Gemini TTS (bawaan / voice design / voice replication)
  minimax:<voice_id>                    MiniMax Speech (hasil kloning / suara sistem MiniMax)
  openai:alloy | openai:coral …          OpenAI TTS
  sovits:default                        GPT-SoVITS (zh/en/ja/ko/yue)
  own                                   SUARA ASLI: rekaman Anda sendiri (own_audio_id)

Variabel lingkungan (isi di ~/.nexa_env):
  GEMINI_API_KEY, OPENAI_API_KEY, MINIMAX_API_KEY, [MINIMAX_GROUP_ID], NEXA_TOKEN
  TEXT_MODEL=gemini-3.8-flash  IMAGE_MODEL=gemini-3.1-flash-image  VIDEO_MODEL=gemini-omni-1.1-flash
  TTS_MODEL=gemini-3.8-flash-tts  DEFAULT_VOICE=gemini:Kore
  MINIMAX_BASE=https://api.minimax.io  MINIMAX_MODEL=speech-02-hd  MINIMAX_LANG=Indonesian
  OPENAI_TTS_MODEL=gpt-4o-mini-tts  OPENAI_STT_MODEL=whisper-1
  SOVITS_URL, SOVITS_REF, SOVITS_PROMPT, SOVITS_LANG
  HOST=127.0.0.1  PORT=5000  OUTPUT_DIR=nexa_output
"""
from __future__ import annotations

import base64
import json
import os
import random
import re
import shutil
import string
import subprocess
import threading
import time
import uuid
from pathlib import Path

import requests
from flask import Flask, Response, abort, jsonify, request, send_from_directory

# ============================================================ KONFIGURASI
ENV = os.environ.get
API = "https://generativelanguage.googleapis.com/v1beta"
GEMINI_KEY = ENV("GEMINI_API_KEY", "")
OPENAI_KEY = ENV("OPENAI_API_KEY", "")
MINIMAX_KEY = ENV("MINIMAX_API_KEY", "")
MINIMAX_GROUP = ENV("MINIMAX_GROUP_ID", "")
MINIMAX_BASE = ENV("MINIMAX_BASE", "https://api.minimax.io").rstrip("/")
MINIMAX_MODEL = ENV("MINIMAX_MODEL", "speech-02-hd")
MINIMAX_LANG = ENV("MINIMAX_LANG", "Indonesian")
TOKEN = ENV("NEXA_TOKEN", "")
TEXT_MODEL = ENV("TEXT_MODEL", "gemini-3.8-flash")
IMAGE_MODEL = ENV("IMAGE_MODEL", "gemini-3.1-flash-image")
VIDEO_MODEL = ENV("VIDEO_MODEL", "gemini-omni-1.1-flash")
TTS_MODEL = ENV("TTS_MODEL", "gemini-3.8-flash-tts")
DEFAULT_VOICE = ENV("DEFAULT_VOICE", "gemini:Kore")
OPENAI_TTS_MODEL = ENV("OPENAI_TTS_MODEL", "gpt-4o-mini-tts")
OPENAI_STT_MODEL = ENV("OPENAI_STT_MODEL", "whisper-1")
SOVITS_URL = ENV("SOVITS_URL", "").rstrip("/")
SOVITS_REF = ENV("SOVITS_REF", "")
SOVITS_PROMPT = ENV("SOVITS_PROMPT", "")
SOVITS_LANG = ENV("SOVITS_LANG", "en")

OUT = Path(ENV("OUTPUT_DIR", "nexa_output")).resolve()
UPLOADS = OUT / "uploads"
for d in (OUT, UPLOADS):
    d.mkdir(parents=True, exist_ok=True)
VOICES_FILE = OUT / "voices.json"
MAX_SCENES = 60
FPS = 30

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 200 * 1024 * 1024
jobs: dict[str, dict] = {}
_lock = threading.Lock()


# ============================================================ UTIL
def _check(r: requests.Response, who: str) -> requests.Response:
    if r.status_code >= 300:
        raise RuntimeError(f"{who} {r.status_code}: {r.text[:300]}")
    return r


def gemini(path: str, body: dict, timeout: int = 300) -> dict:
    if not GEMINI_KEY:
        raise RuntimeError("GEMINI_API_KEY belum diisi")
    r = requests.post(f"{API}/{path}", json=body, timeout=timeout,
                      headers={"x-goog-api-key": GEMINI_KEY})
    return _check(r, "Gemini").json()


def last_output(resp: dict, kind: str) -> bytes:
    """Ambil output terakhir bertipe kind (audio/image/video) dari Interactions API."""
    direct = resp.get(f"output_{kind}") or {}
    if direct.get("data"):
        return base64.b64decode(direct["data"])
    found = None
    for step in resp.get("steps", []):
        for c in step.get("content", []) or []:
            if c.get("type") == kind and c.get("data"):
                found = c["data"]
    if not found:
        raise RuntimeError(f"Model tidak mengembalikan {kind}")
    return base64.b64decode(found)


def gemini_text(prompt: str, schema: dict | None = None, parts: list | None = None) -> str:
    body: dict = {"contents": [{"role": "user", "parts": (parts or []) + [{"text": prompt}]}]}
    if schema:
        body["generationConfig"] = {"responseMimeType": "application/json", "responseSchema": schema}
    resp = gemini(f"models/{TEXT_MODEL}:generateContent", body)
    return "".join(p.get("text", "") for p in resp["candidates"][0]["content"]["parts"])


def rid(n: int = 10) -> str:
    return uuid.uuid4().hex[:n]


def upload_path(file_id: str) -> Path:
    if not re.fullmatch(r"[\w.\-]+", file_id or ""):
        raise RuntimeError("file_id tidak valid")
    matches = list(UPLOADS.glob(f"{file_id}*"))
    if not matches:
        raise RuntimeError(f"file {file_id} tidak ditemukan")
    return matches[0]


# ============================================================ SUARA
def load_voices() -> list[dict]:
    try:
        return json.loads(VOICES_FILE.read_text())
    except Exception:  # noqa: BLE001
        return []


def save_voices(v: list[dict]) -> None:
    VOICES_FILE.write_text(json.dumps(v, indent=2, ensure_ascii=False))


def tts(text: str, voice: str | None = None) -> tuple[bytes, str]:
    """Teks → (audio, ekstensi). voice = 'engine:id'."""
    voice = voice or DEFAULT_VOICE
    engine, _, vid = voice.partition(":")
    if engine == "minimax":
        return tts_minimax(text, vid), "mp3"
    if engine == "openai":
        return tts_openai(text, vid or "coral"), "wav"
    if engine == "sovits":
        return tts_sovits(text), "wav"
    return tts_gemini(text, vid or "Kore"), "wav"


def tts_gemini(text: str, voice: str) -> bytes:
    resp = gemini("interactions", {
        "model": TTS_MODEL,
        "input": [{"type": "user_input", "content": [{
            "type": "text", "text": text,
            "annotations": [{"type": "speech_metadata", "style": "narator yang hangat dan ekspresif"}],
        }]}],
        "response_format": {"type": "audio"},
        "generation_config": {"speech_config": [{"voice": voice}]},
    })
    return last_output(resp, "audio")


def _minimax_url(path: str) -> str:
    url = f"{MINIMAX_BASE}{path}"
    return f"{url}?GroupId={MINIMAX_GROUP}" if MINIMAX_GROUP else url


def _minimax_headers() -> dict:
    if not MINIMAX_KEY:
        raise RuntimeError("MINIMAX_API_KEY belum diisi")
    return {"Authorization": f"Bearer {MINIMAX_KEY}"}


def tts_minimax(text: str, voice_id: str) -> bytes:
    body = {
        "model": MINIMAX_MODEL,
        "text": text,
        "stream": False,
        "language_boost": MINIMAX_LANG,
        "voice_setting": {"voice_id": voice_id, "speed": 1.0, "vol": 1.0, "pitch": 0},
        "audio_setting": {"sample_rate": 32000, "bitrate": 128000, "format": "mp3", "channel": 1},
    }
    r = _check(requests.post(_minimax_url("/v1/t2a_v2"), json=body, headers=_minimax_headers(), timeout=300),
               "MiniMax")
    data = r.json()
    base = data.get("base_resp", {})
    if base.get("status_code", 0) != 0:
        raise RuntimeError(f"MiniMax: {base.get('status_msg')}")
    audio = (data.get("data") or {}).get("audio", "")
    if not audio:
        raise RuntimeError("MiniMax tidak mengembalikan audio")
    return bytes.fromhex(audio)  # t2a_v2 mengembalikan audio dalam bentuk hex


def tts_openai(text: str, voice: str) -> bytes:
    if not OPENAI_KEY:
        raise RuntimeError("OPENAI_API_KEY belum diisi")
    r = requests.post("https://api.openai.com/v1/audio/speech", timeout=300,
                      headers={"Authorization": f"Bearer {OPENAI_KEY}"},
                      json={"model": OPENAI_TTS_MODEL, "voice": voice, "input": text,
                            "response_format": "wav",
                            "instructions": "Bicara dalam bahasa Indonesia, hangat, ekspresif, jelas."})
    return _check(r, "OpenAI TTS").content


def tts_sovits(text: str) -> bytes:
    if not SOVITS_URL:
        raise RuntimeError("SOVITS_URL belum diisi")
    r = requests.post(f"{SOVITS_URL}/tts", timeout=300, json={
        "text": text, "text_lang": SOVITS_LANG, "ref_audio_path": SOVITS_REF,
        "prompt_text": SOVITS_PROMPT, "prompt_lang": SOVITS_LANG,
        "text_split_method": "cut5", "media_type": "wav", "streaming_mode": False,
    })
    return _check(r, "GPT-SoVITS").content


def clone_minimax(sample: Path, name: str) -> dict:
    """Kloning suara MiniMax: upload sampel → voice_clone → voice_id."""
    h = _minimax_headers()
    with sample.open("rb") as f:
        r = _check(requests.post(_minimax_url("/v1/files/upload"), headers=h, timeout=300,
                                 data={"purpose": "voice_clone"}, files={"file": (sample.name, f)}),
                   "MiniMax upload")
    file_id = r.json().get("file", {}).get("file_id")
    if not file_id:
        raise RuntimeError(f"MiniMax upload gagal: {r.text[:200]}")
    # voice_id: huruf di depan, min. 8 karakter, huruf+angka
    voice_id = "Nexa" + re.sub(r"[^A-Za-z0-9]", "", name)[:12] + "".join(random.choices(string.digits, k=4))
    body = {"file_id": file_id, "voice_id": voice_id,
            "need_noise_reduction": True, "need_volume_normalization": True,
            "text": "Halo, ini suara kloning saya di NEXA AI.", "model": MINIMAX_MODEL}
    r = _check(requests.post(_minimax_url("/v1/voice_clone"), headers=h, json=body, timeout=300),
               "MiniMax clone")
    base = r.json().get("base_resp", {})
    if base.get("status_code", 0) != 0:
        raise RuntimeError(f"MiniMax clone: {base.get('status_msg')}")
    return {"voice_id": voice_id, "preview": r.json().get("demo_audio", "")}


def clone_gemini(sample: Path, consent: Path, name: str) -> dict:
    """Voice replication Gemini (WAJIB rekaman persetujuan dari orang yang sama)."""
    b64 = lambda p: base64.b64encode(p.read_bytes()).decode()  # noqa: E731
    mime = lambda p: "audio/wav" if p.suffix.lower() == ".wav" else "audio/mpeg"  # noqa: E731
    body = {"voice": {
        "model": TTS_MODEL, "type": "replicated", "display_name": name,
        "replicated": {"source_audio": {"mime_type": mime(sample), "data": b64(sample)},
                       "consent_audio": {"mime_type": mime(consent), "data": b64(consent)}},
    }, "store": True}
    resp = gemini("voices", body)
    vid = resp.get("id") or resp.get("name", "").split("/")[-1]
    if not vid:
        raise RuntimeError(f"Gemini tidak mengembalikan ID suara: {str(resp)[:200]}")
    return {"voice_id": vid, "preview": ""}


# ============================================================ TRANSKRIP (suara asli)
def transcribe(audio: Path) -> list[dict]:
    """Audio → segmen [{start, end, text, words:[{start,end,word}]}]."""
    if OPENAI_KEY:
        with audio.open("rb") as f:
            r = requests.post(
                "https://api.openai.com/v1/audio/transcriptions", timeout=600,
                headers={"Authorization": f"Bearer {OPENAI_KEY}"},
                files={"file": (audio.name, f)},
                data=[("model", OPENAI_STT_MODEL), ("response_format", "verbose_json"),
                      ("language", "id"), ("timestamp_granularities[]", "segment"),
                      ("timestamp_granularities[]", "word")])
        if r.status_code < 300:
            d = r.json()
            words = d.get("words", [])
            segs = []
            for s in d.get("segments", []):
                segs.append({"start": s["start"], "end": s["end"], "text": s["text"].strip(),
                             "words": [w for w in words if s["start"] - 0.01 <= w["start"] < s["end"]]})
            if segs:
                return segs
    # Cadangan: Gemini memahami audio dan memberi timestamp per kalimat
    mime = "audio/mpeg" if audio.suffix.lower() in (".mp3", ".m4a", ".aac") else "audio/wav"
    if audio.suffix.lower() in (".m4a", ".aac"):
        mime = "audio/aac"
    schema = {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
        "start": {"type": "NUMBER"}, "end": {"type": "NUMBER"}, "text": {"type": "STRING"}},
        "required": ["start", "end", "text"]}}
    text = gemini_text("Transkripsikan audio ini kata per kata (bahasa aslinya). Pecah per kalimat, "
                       "beri waktu mulai & selesai dalam detik.", schema,
                       parts=[{"inline_data": {"mime_type": mime, "data": base64.b64encode(audio.read_bytes()).decode()}}])
    return [{**s, "words": []} for s in json.loads(text)]


# ============================================================ NASKAH & VISUAL
SCENE_SCHEMA = {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
    "narration": {"type": "STRING"}, "visual": {"type": "STRING"},
    "camera": {"type": "STRING", "enum": ["zoom_in", "zoom_out", "pan_left", "pan_right", "static"]}},
    "required": ["narration", "visual", "camera"]}}


def write_script(topic: str, minutes: float) -> str:
    words = int(max(0.3, minutes) * 140)
    return gemini_text(
        f"Tulis naskah narasi video berbahasa Indonesia tentang: {topic}\n"
        f"Panjang sekitar {words} kata (≈{minutes} menit dibacakan). Gaya bertutur menarik, "
        "pembuka yang memancing rasa penasaran, isi padat, penutup dengan ajakan. "
        "Tulis HANYA teks narasi, tanpa judul, tanpa tanda adegan, tanpa emoji.")


def plan_scenes(script: str) -> list[dict]:
    t = gemini_text(
        f"Pecah naskah ini menjadi adegan berurutan (maks {MAX_SCENES}, tiap adegan 1–3 kalimat, ±5–10 detik). "
        "narration = teks PERSIS dari naskah (jangan diringkas/diubah). visual = deskripsi sinematik bahasa Inggris "
        "untuk AI gambar/video, tokoh & gaya konsisten antar adegan, tanpa teks di gambar. camera = gerakan kamera.\n\n"
        "NASKAH:\n" + script, SCENE_SCHEMA)
    return [s for s in json.loads(t) if s.get("narration", "").strip()][:MAX_SCENES]


def visuals_for(texts: list[str]) -> list[dict]:
    """Untuk suara asli: teks per adegan sudah pasti, minta visual & kamera saja."""
    schema = {"type": "ARRAY", "items": {"type": "OBJECT", "properties": {
        "visual": {"type": "STRING"},
        "camera": {"type": "STRING", "enum": ["zoom_in", "zoom_out", "pan_left", "pan_right", "static"]}},
        "required": ["visual", "camera"]}}
    numbered = "\n".join(f"{i + 1}. {t}" for i, t in enumerate(texts))
    t = gemini_text(f"Untuk tiap baris narasi berikut ({len(texts)} baris), buat 1 deskripsi visual sinematik "
                    "bahasa Inggris (konsisten gaya & tokoh, tanpa teks) dan gerakan kamera. Jumlah output HARUS "
                    f"{len(texts)}.\n\n{numbered}", schema)
    out = json.loads(t)
    while len(out) < len(texts):
        out.append({"visual": texts[len(out)], "camera": "zoom_in"})
    return out[:len(texts)]


def enhance_prompt(p: str) -> str:
    return gemini_text("Ubah permintaan gambar ini menjadi prompt fotografi bahasa Inggris yang detail "
                       "(subjek, suasana, pencahayaan, lensa, komposisi, photorealistic, sangat detail). "
                       f"Balas HANYA prompt-nya.\n\nPermintaan: {p}").strip()


def gen_image(prompt: str, aspect: str) -> bytes:
    resp = gemini("interactions", {"model": IMAGE_MODEL,
                                   "input": f"{prompt}. Aspect ratio {aspect}, no text, no watermark."})
    return last_output(resp, "image")


def gen_clip(prompt: str, aspect: str) -> bytes:
    resp = gemini("interactions", {"model": VIDEO_MODEL, "input": prompt,
                                   "response_format": {"type": "video", "aspect_ratio": aspect}}, timeout=900)
    return last_output(resp, "video")


# ============================================================ FFMPEG
def ff(*args: str, cwd: Path | None = None) -> None:
    p = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", *args], capture_output=True, text=True, cwd=cwd)
    if p.returncode != 0:
        raise RuntimeError("ffmpeg: " + p.stderr[-500:])


def duration(path: Path) -> float:
    p = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=nw=1:nk=1", str(path)], capture_output=True, text=True)
    try:
        return float(p.stdout.strip())
    except ValueError:
        return 0.0


ENC = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-pix_fmt", "yuv420p",
       "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2"]


def kenburns(camera: str, frames: int, W: int, H: int) -> str:
    big = f"scale={W * 2}:{H * 2}:force_original_aspect_ratio=increase,crop={W * 2}:{H * 2}"
    n = max(frames, 1)
    z = {"zoom_in": "min(1+0.18*on/{n},1.18)", "zoom_out": "max(1.18-0.18*on/{n},1)"}.get(camera, "1.15")
    z = z.format(n=n)
    x, y = "iw/2-(iw/zoom/2)", "ih/2-(ih/zoom/2)"
    if camera == "pan_left":
        x = f"(iw-iw/zoom)*(1-on/{n})"
    elif camera == "pan_right":
        x = f"(iw-iw/zoom)*on/{n}"
    if camera == "static":
        return f"scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},format=yuv420p"
    return f"{big},zoompan=z='{z}':x='{x}':y='{y}':d={n}:s={W}x{H}:fps={FPS},format=yuv420p"


def scene_image(img: Path, dur: float, camera: str, W: int, H: int, out: Path, audio: Path | None) -> None:
    frames = int(round(dur * FPS))
    vf = kenburns(camera, frames, W, H)
    if audio:
        ff("-i", str(img), "-i", str(audio), "-filter_complex", f"[0:v]{vf}[v]", "-map", "[v]", "-map", "1:a",
           *ENC, "-t", f"{dur:.3f}", str(out))
    else:  # suara asli: video tanpa audio dulu, audio ditempel di akhir
        ff("-i", str(img), "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-filter_complex", f"[0:v]{vf}[v]",
           "-map", "[v]", "-map", "1:a", *ENC, "-t", f"{dur:.3f}", str(out))


def scene_clip(clip: Path, dur: float, W: int, H: int, out: Path, audio: Path | None) -> None:
    fit = f"scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},format=yuv420p"
    a_in = ["-i", str(audio)] if audio else ["-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"]
    ff("-stream_loop", "-1", "-i", str(clip), *a_in, "-map", "0:v", "-map", "1:a", "-vf", fit, *ENC,
       "-t", f"{dur:.3f}", str(out))


def srt_time(t: float) -> str:
    ms = int(round(max(t, 0) * 1000))
    h, ms = divmod(ms, 3600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02}:{m:02}:{s:02},{ms:03}"


def chunk_cues(text: str, start: float, end: float, words: list[dict], per: int = 4) -> list[tuple]:
    """Subtitle pendek ala video vertikal (±4 kata). Pakai timestamp kata bila ada."""
    if words:
        cues = []
        for i in range(0, len(words), per):
            grp = words[i:i + per]
            cues.append((grp[0]["start"], grp[-1]["end"], " ".join(w["word"].strip() for w in grp)))
        return cues
    toks = text.split()
    if not toks:
        return []
    groups = [toks[i:i + per] for i in range(0, len(toks), per)]
    total = sum(len(" ".join(g)) for g in groups) or 1
    cues, t = [], start
    for g in groups:
        d = (end - start) * len(" ".join(g)) / total
        cues.append((t, t + d, " ".join(g)))
        t += d
    return cues


def write_srt(cues: list[tuple], path: Path) -> None:
    path.write_text("\n".join(f"{i}\n{srt_time(a)} --> {srt_time(b)}\n{t}\n" for i, (a, b, t) in enumerate(cues, 1)),
                    encoding="utf-8")


def stt_words(audio: Path) -> list[dict]:
    """Timestamp kata untuk audio TTS (bila ada kunci OpenAI) → subtitle sinkron kata."""
    if not OPENAI_KEY:
        return []
    try:
        with audio.open("rb") as f:
            r = requests.post("https://api.openai.com/v1/audio/transcriptions", timeout=300,
                              headers={"Authorization": f"Bearer {OPENAI_KEY}"},
                              files={"file": (audio.name, f)},
                              data=[("model", OPENAI_STT_MODEL), ("response_format", "verbose_json"),
                                    ("timestamp_granularities[]", "word")])
        return r.json().get("words", []) if r.status_code < 300 else []
    except Exception:  # noqa: BLE001
        return []


# ============================================================ PIPELINE VIDEO
def run_video_job(job_id: str, req: dict) -> None:
    job = jobs[job_id]
    work = OUT / f"job_{job_id}"
    work.mkdir(exist_ok=True)
    aspect = req.get("aspect", "9:16")
    W, H = (1080, 1920) if aspect == "9:16" else (1920, 1080) if aspect == "16:9" else (1080, 1080)
    mode = req.get("mode", "images")
    voice = req.get("voice") or DEFAULT_VOICE
    burn = req.get("burn_subs", True)
    subs_on = req.get("subtitles", True)

    def status(msg: str, pct: int | None = None) -> None:
        job["message"] = msg
        if pct is not None:
            job["progress"] = pct
        print(f"[{job_id}] {msg}", flush=True)

    def visual(i: int, n: int, prompt: str, camera: str, dur: float, audio: Path | None) -> Path:
        out = work / f"s{i:03}.mp4"
        if mode == "clips":
            status(f"Adegan {i}/{n}: klip video AI…")
            clip = work / f"c{i:03}.mp4"
            clip.write_bytes(gen_clip(prompt, aspect))
            scene_clip(clip, dur, W, H, out, audio)
        else:
            status(f"Adegan {i}/{n}: gambar…")
            img = work / f"i{i:03}.png"
            img.write_bytes(gen_image(prompt, aspect))
            scene_image(img, dur, camera, W, H, out, audio)
        return out

    try:
        parts: list[Path] = []
        cues: list[tuple] = []
        own_audio: Path | None = None

        if voice == "own":
            # ---------------- SUARA ASLI ----------------
            own_audio = upload_path(req["own_audio_id"])
            status("Mentranskrip suara asli…", 5)
            segs = transcribe(own_audio)
            if not segs:
                raise RuntimeError("Transkrip kosong")
            # gabungkan segmen menjadi adegan ±8 detik
            scenes, cur = [], None
            for s in segs:
                if cur and s["end"] - cur["start"] <= 9:
                    cur["end"], cur["text"] = s["end"], cur["text"] + " " + s["text"]
                    cur["words"] += s.get("words", [])
                else:
                    cur = {"start": s["start"], "end": s["end"], "text": s["text"], "words": list(s.get("words", []))}
                    scenes.append(cur)
            scenes = scenes[:MAX_SCENES]
            scenes[0]["start"] = 0.0
            total = duration(own_audio)
            for i in range(len(scenes)):
                scenes[i]["end"] = scenes[i + 1]["start"] if i + 1 < len(scenes) else max(total, scenes[i]["end"])
            status("Menyusun visual…", 10)
            vis = visuals_for([s["text"] for s in scenes])
            for i, (sc, v) in enumerate(zip(scenes, vis), 1):
                dur = max(sc["end"] - sc["start"], 0.5)
                parts.append(visual(i, len(scenes), v["visual"], v.get("camera", "zoom_in"), dur, None))
                cues += chunk_cues(sc["text"], sc["start"], sc["end"], sc["words"])
                job["progress"] = 10 + int(80 * i / len(scenes))
        else:
            # ---------------- NASKAH + SUARA AI / KLONING ----------------
            script = (req.get("script") or "").strip()
            if not script:
                status("Menulis naskah dari topik…", 2)
                script = write_script(req.get("topic", ""), float(req.get("duration_min", 1)))
                job["script"] = script
            status("Menyusun adegan…", 5)
            scenes = plan_scenes(script)
            if not scenes:
                raise RuntimeError("Naskah kosong")
            t = 0.0
            for i, sc in enumerate(scenes, 1):
                status(f"Adegan {i}/{len(scenes)}: suara ({voice})…")
                audio_bytes, ext = tts(sc["narration"], voice)
                raw = work / f"a{i:03}.{ext}"
                raw.write_bytes(audio_bytes)
                wav = work / f"a{i:03}.wav"
                ff("-i", str(raw), "-af", "apad=pad_dur=0.25", "-ar", "48000", "-ac", "2", str(wav))
                dur = duration(wav)
                parts.append(visual(i, len(scenes), sc["visual"], sc.get("camera", "zoom_in"), dur, wav))
                words = [{**w, "start": w["start"] + t, "end": w["end"] + t} for w in stt_words(raw)] if subs_on else []
                cues += chunk_cues(sc["narration"], t, t + dur, words)
                t += dur
                job["progress"] = 5 + int(85 * i / len(scenes))

        # ---------------- GABUNG ----------------
        status("Menggabungkan adegan…", 92)
        lst = work / "list.txt"
        lst.write_text("".join(f"file '{p.name}'\n" for p in parts))
        joined = work / "joined.mp4"
        ff("-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy", str(joined))

        cur = joined
        if own_audio:
            with_voice = work / "voice.mp4"
            ff("-i", str(cur), "-i", str(own_audio), "-map", "0:v", "-map", "1:a", "-c:v", "copy",
               "-c:a", "aac", "-b:a", "192k", "-shortest", str(with_voice))
            cur = with_voice

        if req.get("bgm_id"):
            status("Menambah musik latar (auto-ducking)…", 94)
            bgm = upload_path(req["bgm_id"])
            vol = float(req.get("bgm_volume", 0.3))
            ducked = work / "bgm.mp4"
            ff("-i", str(cur), "-stream_loop", "-1", "-i", str(bgm), "-filter_complex",
               f"[1:a]volume={vol}[bg];[bg][0:a]sidechaincompress=threshold=0.03:ratio=10:attack=20:release=500[duck];"
               "[0:a][duck]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]",
               "-map", "0:v", "-map", "[a]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", str(ducked))
            cur = ducked

        final_name = f"nexa_{job_id}.mp4"
        final = OUT / final_name
        if subs_on and cues:
            status("Menambahkan subtitle…", 97)
            srt = work / "subs.srt"
            write_srt(cues, srt)
            size = 13 if H > W else 18
            style = (f"FontName=Sans,Fontsize={size},Bold=1,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,"
                     "BorderStyle=1,Outline=2,Shadow=0,Alignment=2,MarginV=60")
            done = False
            if burn:
                try:
                    ff("-i", str(cur), "-vf", f"subtitles=subs.srt:force_style='{style}'", *ENC, str(final), cwd=work)
                    done = True
                except RuntimeError as e:
                    status(f"Subtitle tanam gagal ({e}); pakai subtitle terpisah")
            if not done:
                ff("-i", str(cur), "-i", str(srt), "-map", "0", "-map", "1", "-c", "copy", "-c:s", "mov_text",
                   "-metadata:s:s:0", "language=ind", str(final))
            shutil.copy(srt, OUT / f"nexa_{job_id}.srt")
        else:
            shutil.copy(cur, final)

        job.update(status="done", file=final_name, message="Selesai", progress=100,
                   duration=round(duration(final), 1))
        shutil.rmtree(work, ignore_errors=True)
    except Exception as e:  # noqa: BLE001
        job.update(status="error", message=str(e))
        print(f"[{job_id}] ERROR {e}", flush=True)


# ============================================================ ROUTES
@app.after_request
def cors(resp: Response) -> Response:
    resp.headers["Access-Control-Allow-Origin"] = "*"
    resp.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Nexa-Token"
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
    return resp


@app.before_request
def check_token():
    if request.method == "OPTIONS":
        return Response(status=204)
    if request.path == "/health":
        return None
    tok = request.headers.get("X-Nexa-Token") or request.args.get("token")
    if not TOKEN or tok != TOKEN:
        abort(401)
    return None


@app.errorhandler(RuntimeError)
def runtime_error(e):
    return jsonify(error=str(e)), 500


@app.get("/health")
def health():
    return {"ok": True, "ffmpeg": bool(shutil.which("ffmpeg")), "gemini": bool(GEMINI_KEY),
            "openai": bool(OPENAI_KEY), "minimax": bool(MINIMAX_KEY), "sovits": bool(SOVITS_URL)}


@app.post("/upload")
def upload():
    f = request.files.get("file")
    if not f:
        return jsonify(error="file kosong"), 400
    ext = Path(f.filename or "").suffix.lower()[:6] or ".bin"
    fid = rid(12)
    f.save(UPLOADS / f"{fid}{ext}")
    return {"file_id": fid, "name": f.filename}


@app.get("/voices")
def voices():
    builtin = [{"id": f"gemini:{v}", "name": f"Gemini {v}", "engine": "gemini", "cloned": False}
               for v in ("Kore", "Puck", "Aoede", "Charon", "Fenrir", "Leda", "Orus", "Zephyr")]
    builtin += [{"id": f"openai:{v}", "name": f"OpenAI {v}", "engine": "openai", "cloned": False}
                for v in ("coral", "alloy", "nova", "shimmer", "echo", "sage")] if OPENAI_KEY else []
    builtin += [{"id": "sovits:default", "name": "GPT-SoVITS", "engine": "sovits", "cloned": False}] if SOVITS_URL else []
    return {"voices": load_voices() + builtin}


@app.post("/voices/clone")
def voices_clone():
    engine = request.form.get("engine", "minimax")
    name = request.form.get("name", "Suara Saya").strip()[:40]
    if request.form.get("agree") != "1":
        return jsonify(error="Wajib menyatakan bahwa suara ini milik Anda atau ada izin pemiliknya"), 400
    def get_file(field: str, id_field: str, prefix: str) -> Path | None:
        """Ambil file dari upload langsung (multipart) atau dari file_id hasil /upload."""
        f = request.files.get(field)
        if f:
            p = UPLOADS / f"{prefix}_{rid()}{Path(f.filename or '').suffix or '.wav'}"
            f.save(p)
            return p
        if request.form.get(id_field):
            return upload_path(request.form[id_field])
        return None

    sample = get_file("file", "sample_id", "clone")
    if not sample:
        return jsonify(error="sampel suara kosong"), 400
    if engine == "gemini":
        consent = get_file("consent", "consent_id", "consent")
        if not consent:
            return jsonify(error="Gemini butuh rekaman persetujuan (consent)"), 400
        res = clone_gemini(sample, consent, name)
        spec = f"gemini:{res['voice_id']}"
    else:
        res = clone_minimax(sample, name)
        spec = f"minimax:{res['voice_id']}"
    with _lock:
        v = load_voices()
        v.insert(0, {"id": spec, "name": name, "engine": engine, "cloned": True,
                     "created": int(time.time()), "preview": res.get("preview", "")})
        save_voices(v)
    return {"id": spec, "name": name, "preview": res.get("preview", "")}


@app.delete("/voices/<path:vid>")
def voices_delete(vid: str):
    with _lock:
        save_voices([v for v in load_voices() if v["id"] != vid])
    return {"ok": True}


@app.post("/tts")
def tts_route():
    d = request.get_json(force=True)
    audio, ext = tts((d.get("text") or "").strip(), d.get("voice"))
    return Response(audio, mimetype="audio/mpeg" if ext == "mp3" else "audio/wav")


@app.post("/script")
def script_route():
    d = request.get_json(force=True)
    return {"script": write_script(d.get("topic", ""), float(d.get("duration_min", 1)))}


@app.post("/image")
def image_route():
    d = request.get_json(force=True)
    prompt = (d.get("prompt") or "").strip()
    if d.get("enhance", True):
        prompt = enhance_prompt(prompt)
    name = f"img_{rid(8)}.png"
    (OUT / name).write_bytes(gen_image(prompt, d.get("aspect", "9:16")))
    return {"file": name, "prompt": prompt}


@app.post("/video")
def video_route():
    d = request.get_json(force=True)
    if not (d.get("script") or d.get("topic") or d.get("voice") == "own"):
        return jsonify(error="isi script, topic, atau pakai voice=own dengan own_audio_id"), 400
    if d.get("voice") == "own" and not d.get("own_audio_id"):
        return jsonify(error="own_audio_id wajib untuk suara asli"), 400
    job_id = rid()
    jobs[job_id] = {"id": job_id, "status": "running", "message": "Antre…", "progress": 0,
                    "created": time.time(), "title": (d.get("topic") or d.get("script") or "Suara asli")[:60]}
    threading.Thread(target=run_video_job, args=(job_id, d), daemon=True).start()
    return {"job_id": job_id}


@app.get("/jobs")
def jobs_list():
    return {"jobs": sorted(jobs.values(), key=lambda j: -j["created"])[:30]}


@app.get("/jobs/<job_id>")
def job_status(job_id: str):
    job = jobs.get(job_id)
    if not job:
        abort(404)
    return job


@app.get("/files/<path:name>")
def files(name: str):
    if not re.fullmatch(r"[\w.\-]+", name):
        abort(400)
    return send_from_directory(OUT, name, as_attachment=request.args.get("dl") == "1")


@app.get("/gallery")
def gallery():
    items = [{"file": p.name, "size": p.stat().st_size, "time": int(p.stat().st_mtime),
              "type": "video" if p.suffix == ".mp4" else "image"}
             for p in OUT.iterdir() if p.suffix in (".mp4", ".png", ".jpg")]
    return {"items": sorted(items, key=lambda x: -x["time"])[:100]}


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("Set NEXA_TOKEN dulu (lihat ~/.nexa_env).")
    if not shutil.which("ffmpeg"):
        raise SystemExit("ffmpeg belum terpasang (Termux: pkg install ffmpeg).")
    print(f"NEXA Server → http://{ENV('HOST', '127.0.0.1')}:{ENV('PORT', '5000')}  "
          f"gemini={bool(GEMINI_KEY)} openai={bool(OPENAI_KEY)} minimax={bool(MINIMAX_KEY)}")
    app.run(host=ENV("HOST", "127.0.0.1"), port=int(ENV("PORT", "5000")), threaded=True)
