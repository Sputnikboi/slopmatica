"""Slopmatica Cloud: per-Minecraft-account storage for .litematic schematics.

Identity comes from Mojang's session server (the same handshake a Minecraft
server does), so there are no passwords. See README.md for the API.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import secrets
import shutil
import sqlite3
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import jinja2
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("slopmatica_cloud")

PKG_DIR = Path(__file__).resolve().parent
CLOUD_DIR = PKG_DIR.parent

DAY = 86400
CHALLENGE_TTL = 120
API_TOKEN_TTL = 180 * DAY
WEBLOGIN_TTL = 300
SESSION_TTL = 30 * DAY
MAX_PENDING_CHALLENGES = 10000

SESSION_COOKIE = "slopmatica_session"
CSRF_HEADER = "x-requested-with"
CSRF_VALUE = "slopmatica"

GZIP_MAGIC = b"\x1f\x8b"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{1,16}$")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
UUID_HEX_RE = re.compile(r"^[0-9a-fA-F]{32}$")
DASHED_UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
# Control characters plus characters Windows cannot store in file names
# (game instances on Windows must be able to mirror every path).
BAD_SEGMENT_CHARS = re.compile(r'[\x00-\x1f\x7f<>:"|?*\\]')

MAX_PATH_LEN = 300
MAX_SEGMENT_LEN = 100
MAX_DEPTH = 8  # segments, including the file name


# --------------------------------------------------------------------------- settings


def _normalize_root_path(value: str) -> str:
    value = (value or "").strip().strip("/")
    return "/" + value if value else ""


@dataclass
class Settings:
    data_dir: Path = field(default_factory=lambda: CLOUD_DIR / "data")
    root_path: str = ""
    admins: frozenset[str] = frozenset({"sputnikboi"})
    quota_bytes: int = 1073741824
    public_url: str = "http://127.0.0.1:8095"
    mojang_has_joined_url: str = "https://sessionserver.mojang.com/session/minecraft/hasJoined"
    mojang_timeout: float = 10.0
    max_upload_bytes: int = 256 * 1024 * 1024
    max_thumbnail_bytes: int = 2 * 1024 * 1024

    def __post_init__(self) -> None:
        self.data_dir = Path(self.data_dir).resolve()
        self.root_path = _normalize_root_path(self.root_path)
        self.admins = frozenset(a.strip().lower() for a in self.admins if a.strip())
        self.public_url = self.public_url.rstrip("/")

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> "Settings":
        env = dict(os.environ if env is None else env)
        kw: dict[str, Any] = {}
        if env.get("SLOPMATICA_DATA"):
            kw["data_dir"] = Path(env["SLOPMATICA_DATA"])
        if "SLOPMATICA_ROOT_PATH" in env:
            kw["root_path"] = env["SLOPMATICA_ROOT_PATH"]
        if "SLOPMATICA_ADMINS" in env:
            kw["admins"] = frozenset(env["SLOPMATICA_ADMINS"].split(","))
        if env.get("SLOPMATICA_QUOTA_BYTES"):
            kw["quota_bytes"] = int(env["SLOPMATICA_QUOTA_BYTES"])
        if env.get("SLOPMATICA_PUBLIC_URL"):
            kw["public_url"] = env["SLOPMATICA_PUBLIC_URL"]
        if env.get("SLOPMATICA_MOJANG_HASJOINED"):
            kw["mojang_has_joined_url"] = env["SLOPMATICA_MOJANG_HASJOINED"]
        return cls(**kw)


# --------------------------------------------------------------------------- errors


class ApiError(Exception):
    def __init__(self, status: int, error: str, **extra: Any) -> None:
        super().__init__(error)
        self.status = status
        self.payload = {"error": error, **extra}


def validate_path(path: str) -> str:
    """Validate a user-relative schematic path; return it unchanged or raise ApiError(400)."""
    def bad(why: str) -> ApiError:
        return ApiError(400, f"invalid path: {why}")

    if not isinstance(path, str) or not path:
        raise bad("empty")
    if len(path) > MAX_PATH_LEN:
        raise bad(f"longer than {MAX_PATH_LEN} characters")
    if "\\" in path:
        raise bad("backslashes are not allowed")
    if path.startswith("/"):
        raise bad("must be relative")
    segments = path.split("/")
    if len(segments) > MAX_DEPTH:
        raise bad(f"more than {MAX_DEPTH} path segments")
    for seg in segments:
        if not seg:
            raise bad("empty path segment")
        if seg.startswith("."):
            raise bad("segments may not start with '.'")
        if len(seg) > MAX_SEGMENT_LEN:
            raise bad(f"segment longer than {MAX_SEGMENT_LEN} characters")
        if BAD_SEGMENT_CHARS.search(seg):
            raise bad('control characters and <>:"|?* are not allowed')
        if seg != seg.strip() or seg.endswith("."):
            raise bad("segments may not start/end with whitespace or end with '.'")
    if not segments[-1].lower().endswith(".litematic"):
        raise bad("must end in .litematic")
    for seg in segments[:-1]:
        if seg.lower().endswith(".litematic"):
            raise bad("folder names may not end in .litematic")
    return path


def dash_uuid(hex32: str) -> str:
    h = hex32.lower()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def now_s() -> int:
    return int(time.time())


# --------------------------------------------------------------------------- storage


@dataclass
class User:
    uuid: str
    name: str
    is_admin: bool


@dataclass
class FileRow:
    path: str
    size: int
    sha256: str
    modified: int
    has_thumb: bool

    def api(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "size": self.size,
            "sha256": self.sha256,
            "modified": self.modified,
            "hasThumbnail": self.has_thumb,
        }


SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    is_admin INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL,
    last_login INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tokens (
    hash TEXT PRIMARY KEY,
    kind TEXT NOT NULL,            -- 'api' | 'weblogin' | 'session'
    uuid TEXT NOT NULL REFERENCES users(uuid),
    created INTEGER NOT NULL,
    expires INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tokens_uuid ON tokens(uuid);
CREATE TABLE IF NOT EXISTS challenges (
    server_id TEXT PRIMARY KEY,
    expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS files (
    uuid TEXT NOT NULL,
    path TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    modified INTEGER NOT NULL,     -- unix ms
    has_thumb INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, path)
);
"""


class Store:
    """SQLite metadata + on-disk bytes. One connection guarded by a lock: tiny service."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.root = settings.data_dir
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "users").mkdir(exist_ok=True)
        self.tmp = self.root / "tmp"
        shutil.rmtree(self.tmp, ignore_errors=True)
        self.tmp.mkdir()
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.root / "cloud.db", check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript(SCHEMA)
        self.prune()
        self.reconcile()

    # ---- paths

    def files_dir(self, uuid: str) -> Path:
        assert DASHED_UUID_RE.match(uuid)
        return self.root / "users" / uuid / "files"

    def thumbs_dir(self, uuid: str) -> Path:
        assert DASHED_UUID_RE.match(uuid)
        return self.root / "users" / uuid / "thumbs"

    @staticmethod
    def _under(base: Path, rel: str, suffix: str = "") -> Path:
        p = base.joinpath(*rel.split("/"))
        if suffix:
            p = p.with_name(p.name + suffix)
        # defence in depth on top of validate_path
        if not str(p.resolve()).startswith(str(base.resolve()) + os.sep):
            raise ApiError(400, "invalid path")
        return p

    def file_path(self, uuid: str, rel: str) -> Path:
        return self._under(self.files_dir(uuid), rel)

    def thumb_path(self, uuid: str, rel: str) -> Path:
        return self._under(self.thumbs_dir(uuid), rel, ".png")

    # ---- housekeeping

    def prune(self) -> None:
        with self.lock:
            t = now_s()
            self.db.execute("DELETE FROM tokens WHERE expires < ?", (t,))
            self.db.execute("DELETE FROM challenges WHERE expires < ?", (t,))

    def reconcile(self) -> None:
        """Disk is the source of truth for bytes: make the metadata match it."""
        with self.lock:
            users = [r["uuid"] for r in self.db.execute("SELECT uuid FROM users")]
            for uuid in users:
                fdir = self.files_dir(uuid)
                rows = {r["path"]: r for r in self.db.execute("SELECT * FROM files WHERE uuid=?", (uuid,))}
                seen: set[str] = set()
                if fdir.is_dir():
                    for p in fdir.rglob("*"):
                        if not p.is_file():
                            continue
                        rel = p.relative_to(fdir).as_posix()
                        try:
                            validate_path(rel)
                        except ApiError:
                            continue
                        seen.add(rel)
                        st = p.stat()
                        row = rows.get(rel)
                        if row is None or row["size"] != st.st_size:
                            digest = _hash_file(p)
                            self.db.execute(
                                "INSERT OR REPLACE INTO files(uuid,path,size,sha256,modified,has_thumb) VALUES(?,?,?,?,?,0)",
                                (uuid, rel, st.st_size, digest, int(st.st_mtime * 1000)),
                            )
                        has_thumb = self.thumb_path(uuid, rel).is_file()
                        self.db.execute(
                            "UPDATE files SET has_thumb=? WHERE uuid=? AND path=?", (int(has_thumb), uuid, rel)
                        )
                for rel in rows.keys() - seen:
                    self.db.execute("DELETE FROM files WHERE uuid=? AND path=?", (uuid, rel))

    # ---- users and tokens

    def upsert_user(self, uuid: str, name: str, admin_by_name: bool) -> User:
        with self.lock:
            t = now_s()
            self.db.execute(
                """INSERT INTO users(uuid,name,is_admin,created,last_login) VALUES(?,?,?,?,?)
                   ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_login=excluded.last_login,
                   is_admin=MAX(users.is_admin, excluded.is_admin)""",
                (uuid, name, int(admin_by_name), t, t),
            )
            return self.get_user(uuid)  # type: ignore[return-value]

    def get_user(self, uuid: str) -> User | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM users WHERE uuid=?", (uuid,)).fetchone()
        return User(r["uuid"], r["name"], bool(r["is_admin"])) if r else None

    def issue_token(self, uuid: str, kind: str, ttl: int) -> str:
        token = secrets.token_urlsafe(32)
        with self.lock:
            t = now_s()
            self.db.execute(
                "INSERT INTO tokens(hash,kind,uuid,created,expires) VALUES(?,?,?,?,?)",
                (sha256_hex(token), kind, uuid, t, t + ttl),
            )
        return token

    def user_for_token(self, token: str, kind: str) -> User | None:
        with self.lock:
            r = self.db.execute(
                "SELECT uuid FROM tokens WHERE hash=? AND kind=? AND expires>=?",
                (sha256_hex(token), kind, now_s()),
            ).fetchone()
        return self.get_user(r["uuid"]) if r else None

    def consume_token(self, token: str, kind: str) -> User | None:
        with self.lock:
            h = sha256_hex(token)
            r = self.db.execute(
                "SELECT uuid, expires FROM tokens WHERE hash=? AND kind=?", (h, kind)
            ).fetchone()
            if r is None:
                return None
            self.db.execute("DELETE FROM tokens WHERE hash=?", (h,))
            if r["expires"] < now_s():
                return None
            return self.get_user(r["uuid"])

    def revoke_token(self, token: str, kind: str) -> None:
        with self.lock:
            self.db.execute("DELETE FROM tokens WHERE hash=? AND kind=?", (sha256_hex(token), kind))

    # ---- challenges

    def new_challenge(self) -> str:
        server_id = secrets.token_hex(20)
        with self.lock:
            t = now_s()
            self.db.execute("DELETE FROM challenges WHERE expires < ?", (t,))
            (count,) = self.db.execute("SELECT COUNT(*) FROM challenges").fetchone()
            if count >= MAX_PENDING_CHALLENGES:
                raise ApiError(429, "too many pending challenges, try again shortly")
            self.db.execute("INSERT INTO challenges(server_id,expires) VALUES(?,?)", (server_id, t + CHALLENGE_TTL))
        return server_id

    def consume_challenge(self, server_id: str) -> bool:
        with self.lock:
            cur = self.db.execute(
                "DELETE FROM challenges WHERE server_id=? AND expires>=?", (server_id, now_s())
            )
            return cur.rowcount == 1

    # ---- files

    def list_files(self, uuid: str) -> list[FileRow]:
        with self.lock:
            rows = self.db.execute("SELECT * FROM files WHERE uuid=? ORDER BY path", (uuid,)).fetchall()
        return [FileRow(r["path"], r["size"], r["sha256"], r["modified"], bool(r["has_thumb"])) for r in rows]

    def get_file(self, uuid: str, path: str) -> FileRow | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM files WHERE uuid=? AND path=?", (uuid, path)).fetchone()
        return FileRow(r["path"], r["size"], r["sha256"], r["modified"], bool(r["has_thumb"])) if r else None

    def used_bytes(self, uuid: str) -> int:
        with self.lock:
            (n,) = self.db.execute("SELECT COALESCE(SUM(size),0) FROM files WHERE uuid=?", (uuid,)).fetchone()
        return int(n)

    def put_file_row(self, uuid: str, row: FileRow) -> None:
        with self.lock:
            self.db.execute(
                "INSERT OR REPLACE INTO files(uuid,path,size,sha256,modified,has_thumb) VALUES(?,?,?,?,?,?)",
                (uuid, row.path, row.size, row.sha256, row.modified, int(row.has_thumb)),
            )

    def delete_file_row(self, uuid: str, path: str) -> None:
        with self.lock:
            self.db.execute("DELETE FROM files WHERE uuid=? AND path=?", (uuid, path))

    def set_thumb(self, uuid: str, path: str, has: bool) -> None:
        with self.lock:
            self.db.execute("UPDATE files SET has_thumb=? WHERE uuid=? AND path=?", (int(has), uuid, path))

    def move_file_row(self, uuid: str, src: str, dst: str) -> None:
        with self.lock:
            self.db.execute("UPDATE files SET path=? WHERE uuid=? AND path=?", (dst, uuid, src))


def _hash_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _remove_empty_parents(p: Path, stop: Path) -> None:
    parent = p.parent
    while parent != stop and stop in parent.parents:
        try:
            parent.rmdir()
        except OSError:
            return
        parent = parent.parent


# --------------------------------------------------------------------------- mojang


class MojangUnavailable(Exception):
    pass


def mojang_has_joined(settings: Settings, username: str, server_id: str) -> tuple[str, str] | None:
    """Return (dashed uuid, name) if Mojang confirms the join, else None."""
    sep = "&" if "?" in settings.mojang_has_joined_url else "?"
    url = settings.mojang_has_joined_url + sep + urllib.parse.urlencode(
        {"username": username, "serverId": server_id}
    )
    req = urllib.request.Request(url, headers={"User-Agent": "SlopmaticaCloud/1.0", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=settings.mojang_timeout) as resp:
            if resp.status != 200:
                return None
            body = resp.read(1 << 16)
    except urllib.error.HTTPError as e:
        if e.code >= 500:
            raise MojangUnavailable(f"HTTP {e.code}") from e
        return None
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise MojangUnavailable(str(e)) from e
    try:
        data = json.loads(body)
        uid, name = data["id"], data["name"]
    except (ValueError, KeyError, TypeError):
        return None
    if not isinstance(uid, str) or not UUID_HEX_RE.match(uid.replace("-", "")) or not isinstance(name, str):
        return None
    return dash_uuid(uid.replace("-", "")), name


# --------------------------------------------------------------------------- formatting


def human_size(n: int | None) -> str:
    if n is None:
        return "Unlimited"
    size = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if size < 1024 or unit == "TB":
            if unit == "B":
                return f"{int(size)} B"
            text = f"{size:.1f}" if size < 10 else f"{size:.0f}"
            return f"{text.removesuffix('.0')} {unit}"
        size /= 1024
    return f"{n} B"


def iso_utc(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def pretty_utc(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


# --------------------------------------------------------------------------- request models


class VerifyIn(BaseModel):
    username: str
    serverId: str


class MoveIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    src: str = Field(alias="from")
    dst: str = Field(alias="to")


# --------------------------------------------------------------------------- middleware


class RootPathMiddleware:
    """Serve correctly whether or not the reverse proxy strips SLOPMATICA_ROOT_PATH."""

    def __init__(self, app: Any, root_path: str) -> None:
        self.app = app
        self.root_path = root_path

    async def __call__(self, scope: dict, receive: Any, send: Any) -> None:
        if self.root_path and scope["type"] in ("http", "websocket"):
            scope = dict(scope)
            scope["root_path"] = self.root_path
            path = scope["path"]
            raw = scope.get("raw_path") or path.encode()
            if path == self.root_path:
                scope["path"], scope["raw_path"] = path + "/", raw + b"/"
            elif not path.startswith(self.root_path + "/"):
                # The proxy stripped the prefix: put it back so that, per the ASGI spec,
                # path includes root_path (mounts such as /static rely on that).
                scope["path"] = self.root_path + path
                scope["raw_path"] = self.root_path.encode() + raw
        await self.app(scope, receive, send)


CSP = (
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' https://mc-heads.net data:; "
    "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
)


# --------------------------------------------------------------------------- app


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    store = Store(settings)
    base = settings.root_path

    templates = jinja2.Environment(
        loader=jinja2.FileSystemLoader(PKG_DIR / "templates"),
        autoescape=True,
        trim_blocks=True,
        lstrip_blocks=True,
    )
    templates.filters["qpath"] = lambda p: urllib.parse.quote(p, safe="/")
    templates.filters["human_size"] = human_size
    templates.filters["iso_utc"] = iso_utc
    templates.filters["pretty_utc"] = pretty_utc
    static_dir = PKG_DIR / "static"
    static_version = str(max(int(p.stat().st_mtime) for p in static_dir.iterdir()))

    app = FastAPI(title="Slopmatica Cloud", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.store = store
    app.add_middleware(RootPathMiddleware, root_path=base)
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

    # ---- error shapes: always {"error": "..."} for the API

    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(exc.payload, status_code=exc.status)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        return JSONResponse({"error": str(exc.detail)}, status_code=exc.status_code, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse({"error": "invalid request body"}, status_code=400)

    @app.middleware("http")
    async def _security_headers(request: Request, call_next: Any) -> Response:
        resp = await call_next(request)
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("Referrer-Policy", "no-referrer")
        resp.headers.setdefault("X-Frame-Options", "DENY")
        if resp.headers.get("content-type", "").startswith("text/html"):
            resp.headers.setdefault("Content-Security-Policy", CSP)
        return resp

    # ---- helpers

    def quota_for(user: User) -> int | None:
        return None if user.is_admin else settings.quota_bytes

    def usage(user: User) -> dict[str, Any]:
        return {"usedBytes": store.used_bytes(user.uuid), "quotaBytes": quota_for(user)}

    def session_user(request: Request) -> User | None:
        cookie = request.cookies.get(SESSION_COOKIE)
        return store.user_for_token(cookie, "session") if cookie else None

    def authenticate(request: Request, *, mutating: bool, allow_cookie: bool = True) -> User:
        header = request.headers.get("authorization")
        if header:
            scheme, _, token = header.partition(" ")
            token = token.strip()
            if scheme.lower() != "bearer" or not token:
                raise ApiError(401, "malformed Authorization header")
            user = store.user_for_token(token, "api")
            if user is None:
                raise ApiError(401, "invalid or expired token")
            return user
        if allow_cookie:
            user = session_user(request)
            if user is not None:
                if mutating and request.headers.get(CSRF_HEADER) != CSRF_VALUE:
                    raise ApiError(403, f"missing X-Requested-With: {CSRF_VALUE} header")
                return user
        raise ApiError(401, "authentication required")

    def is_https(request: Request) -> bool:
        proto = request.headers.get("x-forwarded-proto", "").split(",")[0].strip().lower()
        return request.url.scheme == "https" or proto == "https" or settings.public_url.startswith("https://")

    async def read_capped(request: Request, cap: int, dest: Any | None = None) -> tuple[bytes, int, str]:
        """Read the body, enforcing cap. Returns (first bytes, size, sha256). Writes to dest if given."""
        cl = request.headers.get("content-length")
        if cl and cl.isdigit() and int(cl) > cap:
            raise ApiError(413, "file too large", maxBytes=cap)
        h = hashlib.sha256()
        size = 0
        head = b""
        buf = bytearray()
        async for chunk in request.stream():
            if not chunk:
                continue
            size += len(chunk)
            if size > cap:
                raise ApiError(413, "file too large", maxBytes=cap)
            if len(head) < 8:
                head += chunk[: 8 - len(head)]
            h.update(chunk)
            if dest is not None:
                dest.write(chunk)
            else:
                buf += chunk
        return (bytes(buf) if dest is None else head), size, h.hexdigest()

    def require_file(user: User, path: str) -> tuple[FileRow, Path]:
        row = store.get_file(user.uuid, path)
        disk = store.file_path(user.uuid, path)
        if row is None or not disk.is_file():
            raise ApiError(404, "no such file")
        return row, disk

    # ---- auth API

    @app.post("/api/auth/challenge")
    def auth_challenge() -> dict[str, str]:
        return {"serverId": store.new_challenge()}

    @app.post("/api/auth/verify")
    def auth_verify(body: VerifyIn) -> dict[str, Any]:
        if not USERNAME_RE.match(body.username):
            raise ApiError(400, "invalid username")
        if not store.consume_challenge(body.serverId):
            raise ApiError(401, "unknown or expired serverId; request a new challenge")
        try:
            result = mojang_has_joined(settings, body.username, body.serverId)
        except MojangUnavailable as e:
            log.warning("Mojang session server unavailable: %s", e)
            raise ApiError(401, "could not reach the Mojang session server")
        if result is None:
            raise ApiError(401, "Mojang did not confirm the session (hasJoined failed)")
        uuid, name = result
        user = store.upsert_user(uuid, name, name.lower() in settings.admins)
        store.prune()
        token = store.issue_token(user.uuid, "api", API_TOKEN_TTL)
        return {"token": token, "uuid": user.uuid, "name": user.name, **usage(user)}

    @app.post("/api/weblogin")
    def weblogin(request: Request) -> dict[str, str]:
        user = authenticate(request, mutating=True, allow_cookie=False)
        token = store.issue_token(user.uuid, "weblogin", WEBLOGIN_TTL)
        return {"url": f"{settings.public_url}{base}/login/{token}"}

    @app.get("/api/me")
    def me(request: Request) -> dict[str, Any]:
        user = authenticate(request, mutating=False)
        return {"uuid": user.uuid, "name": user.name, **usage(user)}

    # ---- files API

    @app.get("/api/files")
    def list_files(request: Request) -> dict[str, Any]:
        user = authenticate(request, mutating=False)
        return {"files": [f.api() for f in store.list_files(user.uuid)], **usage(user)}

    @app.post("/api/files/move")
    def move_file(request: Request, body: MoveIn) -> dict[str, Any]:
        user = authenticate(request, mutating=True)
        src, dst = validate_path(body.src), validate_path(body.dst)
        with store.lock:
            row, src_disk = require_file(user, src)
            dst_disk = store.file_path(user.uuid, dst)
            if src == dst or store.get_file(user.uuid, dst) is not None or dst_disk.exists():
                raise ApiError(409, "target already exists")
            dst_disk.parent.mkdir(parents=True, exist_ok=True)
            os.replace(src_disk, dst_disk)
            _remove_empty_parents(src_disk, store.files_dir(user.uuid))
            src_thumb = store.thumb_path(user.uuid, src)
            if src_thumb.is_file():
                dst_thumb = store.thumb_path(user.uuid, dst)
                dst_thumb.parent.mkdir(parents=True, exist_ok=True)
                os.replace(src_thumb, dst_thumb)
                _remove_empty_parents(src_thumb, store.thumbs_dir(user.uuid))
            store.move_file_row(user.uuid, src, dst)
            return store.get_file(user.uuid, dst).api()  # type: ignore[union-attr]

    @app.put("/api/files/{path:path}")
    async def put_file(path: str, request: Request) -> JSONResponse:
        user = authenticate(request, mutating=True)
        path = validate_path(path)
        expected = request.headers.get("x-sha256")
        if expected is not None:
            expected = expected.strip()
            if not SHA256_RE.match(expected):
                raise ApiError(400, "X-Sha256 must be 64 hex characters")
            expected = expected.lower()
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".part")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "wb") as f:
                head, size, digest = await read_capped(request, settings.max_upload_bytes, f)
                f.flush()
                os.fsync(f.fileno())
            if not head.startswith(GZIP_MAGIC):
                raise ApiError(400, "not a gzip file (.litematic files are gzipped NBT)")
            if expected is not None and expected != digest:
                raise ApiError(400, "sha256 mismatch", expected=expected, actual=digest)
            with store.lock:
                old = store.get_file(user.uuid, path)
                quota = quota_for(user)
                used = store.used_bytes(user.uuid)
                if quota is not None and used - (old.size if old else 0) + size > quota:
                    raise ApiError(413, "quota exceeded", usedBytes=used, quotaBytes=quota)
                dest = store.file_path(user.uuid, path)
                try:
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(tmp, dest)
                except (NotADirectoryError, IsADirectoryError, FileExistsError):
                    raise ApiError(409, "path conflicts with an existing file or folder")
                has_thumb = bool(old and old.has_thumb)
                if old and old.sha256 != digest and has_thumb:
                    # content changed: the old thumbnail is stale
                    store.thumb_path(user.uuid, path).unlink(missing_ok=True)
                    has_thumb = False
                row = FileRow(path, size, digest, int(time.time() * 1000), has_thumb)
                store.put_file_row(user.uuid, row)
        finally:
            tmp.unlink(missing_ok=True)
        return JSONResponse(row.api(), status_code=200 if old else 201)

    @app.get("/api/files/{path:path}")
    def get_file(path: str, request: Request) -> FileResponse:
        user = authenticate(request, mutating=False)
        path = validate_path(path)
        row, disk = require_file(user, path)
        return FileResponse(
            disk,
            media_type="application/octet-stream",
            filename=path.rsplit("/", 1)[-1],
            headers={"X-Sha256": row.sha256, "Cache-Control": "private, no-cache"},
        )

    @app.delete("/api/files/{path:path}")
    def delete_file(path: str, request: Request) -> Response:
        user = authenticate(request, mutating=True)
        path = validate_path(path)
        with store.lock:
            _, disk = require_file(user, path)
            disk.unlink()
            _remove_empty_parents(disk, store.files_dir(user.uuid))
            thumb = store.thumb_path(user.uuid, path)
            thumb.unlink(missing_ok=True)
            _remove_empty_parents(thumb, store.thumbs_dir(user.uuid))
            store.delete_file_row(user.uuid, path)
        return Response(status_code=204)

    @app.put("/api/thumbnails/{path:path}")
    async def put_thumbnail(path: str, request: Request) -> JSONResponse:
        user = authenticate(request, mutating=True)
        path = validate_path(path)
        require_file(user, path)
        data, _, _ = await read_capped(request, settings.max_thumbnail_bytes)
        if not data.startswith(PNG_MAGIC):
            raise ApiError(400, "not a PNG image")
        with store.lock:
            require_file(user, path)
            dest = store.thumb_path(user.uuid, path)
            dest.parent.mkdir(parents=True, exist_ok=True)
            fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".png")
            with os.fdopen(fd, "wb") as f:
                f.write(data)
            os.replace(tmp_name, dest)
            store.set_thumb(user.uuid, path, True)
            return JSONResponse(store.get_file(user.uuid, path).api())  # type: ignore[union-attr]

    @app.get("/api/thumbnails/{path:path}")
    def get_thumbnail(path: str, request: Request) -> FileResponse:
        user = authenticate(request, mutating=False)
        path = validate_path(path)
        require_file(user, path)
        thumb = store.thumb_path(user.uuid, path)
        if not thumb.is_file():
            raise ApiError(404, "no thumbnail")
        return FileResponse(thumb, media_type="image/png", headers={"Cache-Control": "private, max-age=86400"})

    # ---- website

    def render(name: str, status: int = 200, **ctx: Any) -> HTMLResponse:
        html = templates.get_template(name).render(base=base, v=static_version, **ctx)
        return HTMLResponse(html, status_code=status, headers={"Cache-Control": "no-store"})

    @app.get("/", response_class=HTMLResponse)
    def home(request: Request) -> HTMLResponse:
        user = session_user(request)
        if user is None:
            return render("landing.html")
        files = store.list_files(user.uuid)
        used = sum(f.size for f in files)
        quota = quota_for(user)
        entries = []
        for f in files:
            folder, _, name = f.path.rpartition("/")
            entries.append({"f": f, "folder": folder, "name": name, "stem": name[: -len(".litematic")]})
        folders = sorted({e["folder"] for e in entries if e["folder"]}, key=str.lower)
        pct = 0.0 if not quota else min(100.0, used * 100.0 / quota)
        return render(
            "files.html",
            user=user,
            entries=entries,
            folders=folders,
            used=used,
            quota=quota,
            pct=f"{pct:.2f}",
            over=quota is not None and used >= quota,
        )

    @app.get("/login/{token}")
    def login(token: str, request: Request) -> Response:
        user = store.consume_token(token, "weblogin")
        if user is None:
            return render("login_failed.html", status=400)
        session = store.issue_token(user.uuid, "session", SESSION_TTL)
        resp = RedirectResponse(f"{base}/", status_code=303)
        resp.set_cookie(
            SESSION_COOKIE,
            session,
            max_age=SESSION_TTL,
            path=base or "/",
            httponly=True,
            samesite="lax",
            secure=is_https(request),
        )
        return resp

    @app.post("/logout")
    def logout(request: Request) -> Response:
        cookie = request.cookies.get(SESSION_COOKIE)
        if cookie:
            store.revoke_token(cookie, "session")
        resp = RedirectResponse(f"{base}/", status_code=303)
        resp.delete_cookie(SESSION_COOKIE, path=base or "/", httponly=True, samesite="lax", secure=is_https(request))
        return resp

    return app
