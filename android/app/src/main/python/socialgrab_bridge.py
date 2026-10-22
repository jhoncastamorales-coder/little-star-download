import json
import time

import yt_dlp

# Cachea el resultado de extract_info para que resolve_format no tenga que
# resolver de nuevo el reto de JavaScript (lo mas lento) si list_qualities ya
# lo hizo hace un momento. Solo se guarda el ultimo (no un dict por URL que
# fuera creciendo sin limite en memoria durante la sesion).
_info_cache = {"url": None, "info": None}


def _common_flags(qjs_path: str) -> list:
    return [
        "--no-playlist",
        "--quiet",
        "--no-warnings",
        "--no-cache-dir",
        "--js-runtimes", f"quickjs:{qjs_path}",
    ]


def _extract(url: str, qjs_path: str) -> dict:
    argv = _common_flags(qjs_path) + [url]
    _, _, _, ydl_opts = yt_dlp.parse_options(argv)

    # Algunos sitios (TikTok sobre todo) a veces bloquean/responden raro de
    # forma intermitente por sus sistemas anti-bot. Reintentar un par de
    # veces suele resolverlo sin tener que hacer nada mas.
    attempts = 3
    last_exc = None
    for attempt in range(attempts):
        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                return ydl.extract_info(url, download=False)
        except Exception as exc:
            last_exc = exc
            if attempt < attempts - 1:
                time.sleep(1.5 * (attempt + 1))
    raise last_exc


def list_qualities(url: str, qjs_path: str) -> str:
    """Devuelve el titulo y las calidades de video disponibles (sin descargar nada)."""
    try:
        info = _extract(url, qjs_path)
    except Exception as exc:  # yt-dlp levanta muchos tipos de excepcion distintos
        return json.dumps({"error": str(exc)})

    _info_cache["url"] = url
    _info_cache["info"] = info

    duration = info.get("duration")
    formats = info.get("formats") or []
    by_height = {}
    for f in formats:
        if f.get("vcodec") == "none":
            # "none" explicito = confirmado solo-audio. Si es None/desconocido
            # (legacy de Twitter, etc.) no asumimos que es audio-only.
            continue
        height = f.get("height")
        if not height:
            continue
        if not str(f.get("protocol") or "").startswith("http"):
            # Descartar HLS (m3u8) y DASH fragmentado: su "url" es un manifiesto
            # de texto, no el archivo de video -- no se puede descargar con un
            # simple GET como hacemos nosotros.
            continue
        vcodec = str(f.get("vcodec") or "")
        if vcodec.startswith(("hev1", "hvc1", "h265")):
            # Descartar HEVC: confirmado con un crash real en pruebas (chip
            # MediaTek). avc1/h264, vp9 y av01 si se permiten -- cada
            # plataforma entrega un codec distinto (Instagram solo vp9,
            # Facebook solo av01).
            continue
        # Formatos sin informacion de codec (comun en los "progresivos" viejos
        # de Instagram/Facebook/Twitter) se permiten: en la practica siempre
        # resultaron ser contenido legacy compatible (h264-ish), y para
        # algunas plataformas (Twitter) son la unica opcion de descarga
        # directa disponible.

        filesize = f.get("filesize") or f.get("filesize_approx")
        if not filesize:
            # yt-dlp no siempre calcula filesize_approx (p.ej. si todavia no
            # proceso el formato); lo estimamos nosotros con el bitrate total
            # (tbr, en kbps) y la duracion del video, igual que hace yt-dlp
            # internamente cuando si lo calcula.
            tbr = f.get("tbr")
            if tbr and duration:
                filesize = int(tbr * duration * 1000 / 8)

        is_mp4_like = f.get("ext") == "mp4"
        current = by_height.get(height)
        if current is None or (is_mp4_like and not current["_mp4"]):
            by_height[height] = {
                "format_id": f["format_id"],
                "height": height,
                "fps": f.get("fps"),
                "ext": f.get("ext"),
                "filesize": filesize,
                # acodec == "none" es una confirmacion positiva de "sin audio"
                # (streams DASH video-only). Si es None/desconocido (comun en
                # los progresivos legacy de Twitter) se asume que SI trae
                # audio, porque eso es lo que son en la practica.
                "has_audio": f.get("acodec") != "none",
                "_mp4": is_mp4_like,
            }

    qualities = sorted(by_height.values(), key=lambda q: q["height"], reverse=True)
    for q in qualities:
        q.pop("_mp4", None)

    return json.dumps({
        "title": info.get("title") or "video",
        "qualities": qualities,
    })


def resolve_format(url: str, format_id: str, has_audio: bool, qjs_path: str) -> str:
    """Resuelve las URLs directas de descarga para la calidad elegida, reusando
    la extraccion de list_qualities si todavia esta en cache (evita resolver
    el reto de JavaScript dos veces)."""
    info = _info_cache["info"] if _info_cache["url"] == url else None
    if info is None:
        try:
            info = _extract(url, qjs_path)
        except Exception as exc:
            return json.dumps({"error": str(exc)})

    formats = info.get("formats") or []
    title = info.get("title") or "video"

    video_f = next((f for f in formats if f.get("format_id") == format_id), None)
    if video_f is None:
        return json.dumps({"error": "Esa calidad ya no esta disponible, busca de nuevo."})

    if has_audio:
        return json.dumps({
            "title": title,
            "needs_mux": False,
            "combined_url": video_f["url"],
            "ext": video_f.get("ext") or "mp4",
        })

    audio_candidates = [
        f for f in formats
        if (f.get("acodec") or "none") != "none"
        and (f.get("vcodec") or "none") == "none"
        and str(f.get("protocol") or "").startswith("http")
    ]
    if not audio_candidates:
        return json.dumps({"error": "No se encontro una pista de audio compatible."})

    def audio_score(f):
        is_aac = str(f.get("acodec") or "").startswith(("mp4a", "aac"))
        return (is_aac, f.get("abr") or 0)

    audio_f = max(audio_candidates, key=audio_score)

    return json.dumps({
        "title": title,
        "needs_mux": True,
        "video_url": video_f["url"],
        "video_ext": video_f.get("ext") or "mp4",
        "audio_url": audio_f["url"],
        "audio_ext": audio_f.get("ext") or "m4a",
    })
