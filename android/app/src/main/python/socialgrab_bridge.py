import json
import os
import shutil
import time

import yt_dlp
import yt_dlp_ejs
from yt_dlp.extractor.youtube.jsc._builtin import quickjs as _qjs

# Cachea el resultado de extract_info para que resolve_format no tenga que
# resolver de nuevo el reto de JavaScript (lo mas lento) si list_qualities ya
# lo hizo hace un momento. Solo se guarda el ultimo (no un dict por URL que
# fuera creciendo sin limite en memoria durante la sesion).
_info_cache = {"url": None, "info": None}

# Para resolver el reto de YouTube, QuickJS primero "preprocesa" el reproductor
# (un JS de ~2.5 MB): en un telefono de gama baja eso toma ~17 s y es casi toda
# la busqueda. yt-dlp puede guardar el reproductor ya preprocesado en su cache
# (con eso baja a ~3 s), pero lo trae apagado porque en una PC esos archivos
# (~4 MB cada uno) se acumulan sin limite; aqui se guardan solo los ultimos
# (_prune_players).
_qjs.QuickJSJCP._ENABLE_PREPROCESSED_PLAYER_CACHE = True
_MAX_CACHED_PLAYERS = 2
_old_versions_removed = False


def _versioned_cache_dir(cache_dir: str) -> str:
    """Una carpeta por version de yt-dlp y del resolvedor: un reproductor
    preprocesado por otra version podria no servirle a esta. Las carpetas de
    versiones anteriores se borran la primera vez."""
    global _old_versions_removed
    current = f"{yt_dlp.version.__version__}-{yt_dlp_ejs.version}"
    if not _old_versions_removed:
        try:
            for entry in os.scandir(cache_dir):
                if entry.name != current:
                    shutil.rmtree(entry.path, ignore_errors=True)
        except OSError:
            pass  # la carpeta todavia no existe
        _old_versions_removed = True
    return os.path.join(cache_dir, current)


def _prune_players(cache_dir: str) -> None:
    """Deja solo los reproductores preprocesados mas recientes (YouTube cambia
    de reproductor cada tanto y cada uno pesa bastante)."""
    folder = os.path.join(cache_dir, "challenge-solver")
    try:
        players = [e for e in os.scandir(folder) if e.name.startswith("player")]
        players.sort(key=lambda e: e.stat().st_mtime, reverse=True)
        for old in players[_MAX_CACHED_PLAYERS:]:
            os.remove(old.path)
    except OSError:
        pass


def _common_flags(qjs_path: str, cache_dir: str) -> list:
    return [
        "--no-playlist",
        "--quiet",
        "--no-warnings",
        "--cache-dir", cache_dir,
        "--js-runtimes", f"quickjs:{qjs_path}",
    ]


def _extract(url: str, qjs_path: str, cache_dir: str) -> dict:
    cache_dir = _versioned_cache_dir(cache_dir)
    _prune_players(cache_dir)
    argv = _common_flags(qjs_path, cache_dir) + [url]
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


def list_qualities(url: str, qjs_path: str, cache_dir: str) -> str:
    """Devuelve el titulo y las calidades de video disponibles (sin descargar nada)."""
    try:
        info = _extract(url, qjs_path, cache_dir)
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
                "width": f.get("width"),
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
        "thumbnail": info.get("thumbnail"),
        "duration": int(duration) if duration else None,
        "qualities": qualities,
        "audio": _audio_options(formats, duration),
    })


def _audio_formats(formats: list) -> list:
    """Pistas de solo-audio descargables con un GET simple (sin HLS/DASH)."""
    return [
        f for f in formats
        if f.get("vcodec") == "none"
        and (f.get("acodec") or "none") != "none"
        and str(f.get("protocol") or "").startswith("http")
        # YouTube tambien ofrece versiones "drc" (volumen comprimido) de cada audio
        and "drc" not in str(f.get("format_id"))
    ]


def _audio_options(formats: list, duration) -> list:
    """El mejor audio Opus (maxima calidad de YouTube) y el mejor AAC
    (compatible con cualquier reproductor). Otras redes solo dan AAC."""
    by_codec = {}
    for f in _audio_formats(formats):
        acodec = str(f.get("acodec"))
        codec = "opus" if acodec.startswith("opus") else "aac" if acodec.startswith(("mp4a", "aac")) else None
        if codec is None:
            continue
        current = by_codec.get(codec)
        if current is None or (f.get("abr") or 0) > (current.get("abr") or 0):
            by_codec[codec] = f

    options = []
    for codec in ("opus", "aac"):
        f = by_codec.get(codec)
        if f is None:
            continue
        abr = f.get("abr")
        filesize = f.get("filesize") or f.get("filesize_approx")
        if not filesize and abr and duration:
            filesize = int(abr * duration * 1000 / 8)
        options.append({
            "format_id": f["format_id"],
            "codec": codec,
            "bitrate": int(abr) if abr else None,
            "filesize": filesize,
        })
    return options


def resolve_format(url: str, format_id: str, has_audio: bool, qjs_path: str, cache_dir: str) -> str:
    """Resuelve las URLs directas de descarga para la calidad elegida, reusando
    la extraccion de list_qualities si todavia esta en cache (evita resolver
    el reto de JavaScript dos veces)."""
    info = _info_cache["info"] if _info_cache["url"] == url else None
    if info is None:
        try:
            info = _extract(url, qjs_path, cache_dir)
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


def resolve_audio(url: str, format_id: str, qjs_path: str, cache_dir: str) -> str:
    """URL directa de la pista de audio elegida (reusa la extraccion en cache)."""
    info = _info_cache["info"] if _info_cache["url"] == url else None
    if info is None:
        try:
            info = _extract(url, qjs_path, cache_dir)
        except Exception as exc:
            return json.dumps({"error": str(exc)})

    f = next((f for f in info.get("formats") or [] if f.get("format_id") == format_id), None)
    if f is None:
        return json.dumps({"error": "Ese audio ya no esta disponible, busca de nuevo."})
    return json.dumps({
        "title": info.get("title") or "audio",
        "artist": info.get("artist") or info.get("uploader"),
        "url": f["url"],
    })
