"""Slopmatica Cloud: per-Minecraft-account storage for schematics (.slopmatic, .nbt, .litematic).

Identity comes from Mojang's session server (the same handshake a Minecraft
server does), so there are no passwords. See README.md for the API.
"""

from __future__ import annotations

import hashlib
import hmac
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
from contextlib import contextmanager
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
from starlette.background import BackgroundTask
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import convert, nbt

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

EXPORT_WAIT = 30  # seconds a request waits for a free conversion slot

# ---- website passwords
MIN_PASSWORD_LEN = 8
MAX_PASSWORD_LEN = 200
SCRYPT_N, SCRYPT_R, SCRYPT_P, SCRYPT_DKLEN = 2**14, 8, 1, 64
LOGIN_WINDOW = 15 * 60  # seconds over which failed logins are counted
MAX_NAME_FAILURES = 10
MAX_IP_FAILURES = 30
MAX_NEXT_LEN = 500
LOCAL_PROXIES = frozenset({"127.0.0.1", "::1"})

# ---- schematics board
POST_ID_LEN = 10
POST_ID_RE = re.compile(r"^[A-Za-z0-9_-]{10}$")
POST_THUMB = "thumb.png"  # inside posts/<id>/, next to the snapshot (which keeps its schematic extension)
VISIBILITIES = ("public", "unlisted", "private")
BOARD_TABS = ("public", "shared", "mine")
BOARD_SORTS = ("top", "hot", "new")
MAX_TITLE_LEN = 100
MAX_DESCRIPTION_LEN = 5000
MAX_ALLOWED_NAMES = 50
MAX_QUERY_LEN = 100
BOARD_FOLDER = "board"  # "Add to my cloud" saves into this folder of the caller's files
TITLE_BAD_CHARS = re.compile(r"[\x00-\x1f\x7f]")
DESCRIPTION_BAD_CHARS = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")  # tabs and newlines are fine


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
    mojang_profile_url: str = "https://api.mojang.com/users/profiles/minecraft"  # + "/<name>"
    mojang_timeout: float = 10.0
    max_upload_bytes: int = 256 * 1024 * 1024
    max_thumbnail_bytes: int = 2 * 1024 * 1024
    max_export_volume: int = convert.DEFAULT_MAX_VOLUME  # blocks
    export_slots: int = 2  # conversions running at once
    max_posts_per_user: int = 200
    board_page_size: int = 24

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
        if env.get("SLOPMATICA_MOJANG_PROFILE"):
            kw["mojang_profile_url"] = env["SLOPMATICA_MOJANG_PROFILE"]
        return cls(**kw)


# --------------------------------------------------------------------------- errors


class ApiError(Exception):
    def __init__(self, status: int, error: str, **extra: Any) -> None:
        super().__init__(error)
        self.status = status
        self.payload = {"error": error, **extra}


# The formats the Slopmatica mod reads: its own, vanilla structure files, Litematica's.
SCHEMATIC_EXTENSIONS = (".slopmatic", ".nbt", ".litematic")


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
    if not segments[-1].lower().endswith(SCHEMATIC_EXTENSIONS):
        raise bad("must end in " + ", ".join(SCHEMATIC_EXTENSIONS))
    for seg in segments[:-1]:
        if seg.lower().endswith(SCHEMATIC_EXTENSIONS):
            raise bad("folder names may not end in a schematic extension")
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


@dataclass
class PostRow:
    id: str
    uuid: str  # author
    author_name: str
    title: str
    description: str
    visibility: str
    source_path: str  # the author's cloud path the snapshot was taken from
    filename: str
    format: str
    size: int
    sha256: str
    has_thumb: bool
    dims: tuple[int, int, int] | None  # enclosing size (x, y, z), None if the file could not be parsed
    block_count: int | None
    created: int  # unix ms
    updated: int  # unix ms, when the snapshot was last taken
    ups: int
    downs: int

    @property
    def score(self) -> int:
        return self.ups - self.downs

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "PostRow":
        dims = (r["size_x"], r["size_y"], r["size_z"])
        return cls(
            r["id"], r["uuid"], r["author_name"], r["title"], r["description"], r["visibility"],
            r["source_path"], r["filename"], r["format"], r["size"], r["sha256"], bool(r["has_thumb"]),
            dims if None not in dims else None, r["block_count"], r["created"], r["updated"],
            r["ups"], r["downs"],
        )


# The schema as of 0.3.0 (user_version 0). Later changes are MIGRATIONS, applied in place at startup.
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

# user_version -> DDL that brings the database from user_version - 1 to it.
MIGRATIONS: dict[int, str] = {
    # 0.4.0: schematics board
    1: """
CREATE TABLE IF NOT EXISTS posts (
    id TEXT PRIMARY KEY,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    visibility TEXT NOT NULL CHECK (visibility IN ('public', 'unlisted', 'private')),
    source_path TEXT NOT NULL,
    filename TEXT NOT NULL,
    format TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    has_thumb INTEGER NOT NULL DEFAULT 0,
    size_x INTEGER,
    size_y INTEGER,
    size_z INTEGER,
    block_count INTEGER,
    created INTEGER NOT NULL,      -- unix ms
    updated INTEGER NOT NULL       -- unix ms
);
CREATE INDEX IF NOT EXISTS posts_uuid ON posts(uuid);
CREATE INDEX IF NOT EXISTS posts_visibility ON posts(visibility, created);
CREATE TABLE IF NOT EXISTS post_access (
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL,
    name TEXT NOT NULL,            -- the Minecraft name when it was added
    PRIMARY KEY (post_id, uuid)
);
CREATE INDEX IF NOT EXISTS post_access_uuid ON post_access(uuid);
CREATE TABLE IF NOT EXISTS votes (
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    value INTEGER NOT NULL CHECK (value IN (-1, 1)),
    created INTEGER NOT NULL,
    PRIMARY KEY (post_id, uuid)
);
""",
    # 0.4.0: website passwords, and where a one-time login link lands
    2: """
ALTER TABLE users ADD COLUMN password_hash TEXT;   -- scrypt$n$r$p$salt_hex$hash_hex, NULL if none
ALTER TABLE users ADD COLUMN password_set INTEGER; -- unix s
ALTER TABLE tokens ADD COLUMN next TEXT;           -- weblogin only: site-relative path to land on
CREATE TABLE IF NOT EXISTS login_failures (
    key TEXT NOT NULL,             -- 'name:<lowercase name>' or 'ip:<address>'
    at INTEGER NOT NULL            -- unix s
);
CREATE INDEX IF NOT EXISTS login_failures_key ON login_failures(key, at);
""",
}
SCHEMA_VERSION = max(MIGRATIONS)

POST_SELECT = """
SELECT p.*, u.name AS author_name,
    (SELECT COUNT(*) FROM votes v WHERE v.post_id = p.id AND v.value = 1) AS ups,
    (SELECT COUNT(*) FROM votes v WHERE v.post_id = p.id AND v.value = -1) AS downs
FROM posts p JOIN users u ON u.uuid = p.uuid
"""
BOARD_ORDER = {
    "top": "(ups - downs) DESC, p.created DESC, p.id",
    "new": "p.created DESC, p.id",
    "hot": "slop_hot(ups - downs, p.created, :now) DESC, p.created DESC, p.id",
}


def _hot(score: int, created_ms: int, now_ms: int) -> float:
    """Score decayed by age: score / (hours + 2) ** 1.5."""
    hours = max(0, now_ms - created_ms) / 3_600_000
    return score / (hours + 2) ** 1.5


def _fold(value: Any) -> str:
    return value.casefold() if isinstance(value, str) else ""


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
        self.db.create_function("slop_hot", 3, _hot, deterministic=True)
        self.db.create_function("slop_fold", 1, _fold, deterministic=True)
        self.posts_root = self.root / "posts"
        self.posts_root.mkdir(exist_ok=True)
        self.migrate()
        self.prune()
        self.reconcile()
        self.reconcile_posts()

    def migrate(self) -> None:
        """Create the 0.3.0 tables if missing, then apply MIGRATIONS past PRAGMA user_version, in place."""
        self.db.executescript(SCHEMA)
        (version,) = self.db.execute("PRAGMA user_version").fetchone()
        for target in sorted(v for v in MIGRATIONS if v > version):
            log.info("migrating the database to schema version %d", target)
            try:
                self.db.executescript(f"BEGIN IMMEDIATE;\n{MIGRATIONS[target]}\nPRAGMA user_version = {target};\nCOMMIT;")
            except BaseException:
                if self.db.in_transaction:
                    self.db.execute("ROLLBACK")
                raise

    @contextmanager
    def transaction(self) -> Any:
        with self.lock:
            self.db.execute("BEGIN IMMEDIATE")
            try:
                yield
            except BaseException:
                self.db.execute("ROLLBACK")
                raise
            self.db.execute("COMMIT")

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

    def post_dir(self, post_id: str) -> Path:
        assert POST_ID_RE.match(post_id)
        return self.posts_root / post_id

    def post_file(self, post: PostRow) -> Path:
        return self._under(self.post_dir(post.id), post.filename)

    # ---- housekeeping

    def prune(self) -> None:
        with self.lock:
            t = now_s()
            self.db.execute("DELETE FROM tokens WHERE expires < ?", (t,))
            self.db.execute("DELETE FROM challenges WHERE expires < ?", (t,))
            self.db.execute("DELETE FROM login_failures WHERE at < ?", (t - LOGIN_WINDOW,))

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

    def reconcile_posts(self) -> None:
        """Board snapshots: drop folders without a post (an interrupted post), posts without a
        snapshot, and make sizes and thumbnail flags match the disk."""
        with self.lock:
            rows = {r["id"]: r for r in self.db.execute("SELECT id, filename, size, has_thumb FROM posts")}
            for d in self.posts_root.iterdir():
                if d.name not in rows:
                    log.warning("removing board snapshot folder without a post: %s", d.name)
                    if d.is_dir():
                        shutil.rmtree(d, ignore_errors=True)
                    else:
                        d.unlink(missing_ok=True)
            for post_id, r in rows.items():
                pdir = self.posts_root / post_id
                snap = pdir / r["filename"]
                if not POST_ID_RE.match(post_id) or not snap.is_file():
                    log.warning("removing board post %s: its snapshot is missing", post_id)
                    self.db.execute("DELETE FROM posts WHERE id=?", (post_id,))
                    continue
                size = snap.stat().st_size
                if size != r["size"]:
                    self.db.execute("UPDATE posts SET size=?, sha256=? WHERE id=?", (size, _hash_file(snap), post_id))
                has_thumb = (pdir / POST_THUMB).is_file()
                if has_thumb != bool(r["has_thumb"]):
                    self.db.execute("UPDATE posts SET has_thumb=? WHERE id=?", (int(has_thumb), post_id))

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

    def issue_token(self, uuid: str, kind: str, ttl: int, next_path: str | None = None) -> str:
        token = secrets.token_urlsafe(32)
        with self.lock:
            t = now_s()
            self.db.execute(
                "INSERT INTO tokens(hash,kind,uuid,created,expires,next) VALUES(?,?,?,?,?,?)",
                (sha256_hex(token), kind, uuid, t, t + ttl, next_path),
            )
        return token

    def user_for_token(self, token: str, kind: str) -> User | None:
        with self.lock:
            r = self.db.execute(
                "SELECT uuid FROM tokens WHERE hash=? AND kind=? AND expires>=?",
                (sha256_hex(token), kind, now_s()),
            ).fetchone()
        return self.get_user(r["uuid"]) if r else None

    def consume_token(self, token: str, kind: str) -> tuple[User, str | None] | None:
        """Use up a single-use token: (its user, its next path), or None if unknown or expired."""
        with self.lock:
            h = sha256_hex(token)
            r = self.db.execute(
                "SELECT uuid, expires, next FROM tokens WHERE hash=? AND kind=?", (h, kind)
            ).fetchone()
            if r is None:
                return None
            self.db.execute("DELETE FROM tokens WHERE hash=?", (h,))
            if r["expires"] < now_s():
                return None
            user = self.get_user(r["uuid"])
            return (user, r["next"]) if user else None

    def revoke_sessions(self, uuid: str, keep: str | None = None) -> int:
        """End the account's website sessions, except the one whose token is keep."""
        with self.lock:
            cur = self.db.execute(
                "DELETE FROM tokens WHERE uuid=? AND kind='session' AND hash != ?",
                (uuid, sha256_hex(keep) if keep else ""),
            )
            return cur.rowcount

    # ---- website passwords

    def password_info(self, uuid: str) -> tuple[str | None, int | None]:
        """(hash, unix s when set)."""
        with self.lock:
            r = self.db.execute("SELECT password_hash, password_set FROM users WHERE uuid=?", (uuid,)).fetchone()
        return (r["password_hash"], r["password_set"]) if r else (None, None)

    def set_password_hash(self, uuid: str, value: str | None) -> None:
        with self.lock:
            self.db.execute(
                "UPDATE users SET password_hash=?, password_set=? WHERE uuid=?",
                (value, now_s() if value else None, uuid),
            )

    def password_candidates(self, name: str) -> list[tuple[User, str]]:
        """Accounts whose current stored name is name (any case) and that have a password,
        most recently active first. Usually one; a stale row can share a name after renames."""
        with self.lock:
            rows = self.db.execute(
                """SELECT uuid, password_hash FROM users WHERE name = ? COLLATE NOCASE AND password_hash IS NOT NULL
                   ORDER BY last_login DESC LIMIT 3""",
                (name,),
            ).fetchall()
        out = []
        for r in rows:
            user = self.get_user(r["uuid"])
            if user:
                out.append((user, r["password_hash"]))
        return out

    def touch_login(self, uuid: str) -> None:
        with self.lock:
            self.db.execute("UPDATE users SET last_login=? WHERE uuid=?", (now_s(), uuid))

    # ---- login throttling

    def recent_failures(self, key: str) -> tuple[int, int | None]:
        """(failures for key in the window, unix s of the oldest)."""
        with self.lock:
            n, oldest = self.db.execute(
                "SELECT COUNT(*), MIN(at) FROM login_failures WHERE key=? AND at >= ?", (key, now_s() - LOGIN_WINDOW)
            ).fetchone()
        return int(n), oldest

    def add_failure(self, *keys: str) -> None:
        with self.lock:
            t = now_s()
            self.db.execute("DELETE FROM login_failures WHERE at < ?", (t - LOGIN_WINDOW,))
            self.db.executemany("INSERT INTO login_failures(key, at) VALUES(?,?)", [(k, t) for k in keys])

    def clear_failures(self, key: str) -> None:
        with self.lock:
            self.db.execute("DELETE FROM login_failures WHERE key=?", (key,))

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
        """Bytes counted against the quota: the account's files plus its board snapshots."""
        with self.lock:
            (n,) = self.db.execute(
                "SELECT (SELECT COALESCE(SUM(size),0) FROM files WHERE uuid=:u)"
                " + (SELECT COALESCE(SUM(size),0) FROM posts WHERE uuid=:u)",
                {"u": uuid},
            ).fetchone()
        return int(n)

    def post_usage(self, uuid: str) -> tuple[int, int]:
        """(number of posts, bytes of their snapshots)."""
        with self.lock:
            n, size = self.db.execute("SELECT COUNT(*), COALESCE(SUM(size),0) FROM posts WHERE uuid=?", (uuid,)).fetchone()
        return int(n), int(size)

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

    # ---- board posts

    def new_post_id(self) -> str:
        with self.lock:
            while True:
                post_id = secrets.token_urlsafe(8)[:POST_ID_LEN]
                if POST_ID_RE.match(post_id) and self.get_post(post_id) is None and not (self.posts_root / post_id).exists():
                    return post_id

    def get_post(self, post_id: str) -> PostRow | None:
        with self.lock:
            r = self.db.execute(POST_SELECT + " WHERE p.id=?", (post_id,)).fetchone()
        return PostRow.from_row(r) if r else None

    def insert_post(self, values: dict[str, Any], allowed: list[tuple[str, str]]) -> None:
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.transaction():
            self.db.execute(f"INSERT INTO posts({cols}) VALUES({marks})", values)
            self._set_allowed(values["id"], allowed)

    def update_post(self, post_id: str, values: dict[str, Any], allowed: list[tuple[str, str]] | None = None) -> None:
        with self.transaction():
            if values:
                sets = ", ".join(f"{k}=:{k}" for k in values)
                self.db.execute(f"UPDATE posts SET {sets} WHERE id=:id", {**values, "id": post_id})
            if allowed is not None:
                self._set_allowed(post_id, allowed)

    def delete_post_row(self, post_id: str) -> None:
        with self.lock:
            self.db.execute("DELETE FROM posts WHERE id=?", (post_id,))  # access list and votes cascade

    def _set_allowed(self, post_id: str, allowed: list[tuple[str, str]]) -> None:
        self.db.execute("DELETE FROM post_access WHERE post_id=?", (post_id,))
        self.db.executemany(
            "INSERT OR REPLACE INTO post_access(post_id, uuid, name) VALUES(?,?,?)",
            [(post_id, uuid, name) for uuid, name in allowed],
        )

    def get_allowed(self, post_id: str) -> list[tuple[str, str]]:
        with self.lock:
            rows = self.db.execute(
                "SELECT uuid, name FROM post_access WHERE post_id=? ORDER BY name COLLATE NOCASE", (post_id,)
            ).fetchall()
        return [(r["uuid"], r["name"]) for r in rows]

    def is_allowed(self, post_id: str, uuid: str) -> bool:
        with self.lock:
            r = self.db.execute("SELECT 1 FROM post_access WHERE post_id=? AND uuid=?", (post_id, uuid)).fetchone()
        return r is not None

    def get_vote(self, post_id: str, uuid: str) -> int:
        with self.lock:
            r = self.db.execute("SELECT value FROM votes WHERE post_id=? AND uuid=?", (post_id, uuid)).fetchone()
        return int(r["value"]) if r else 0

    def set_vote(self, post_id: str, uuid: str, value: int) -> None:
        with self.lock:
            if value == 0:
                self.db.execute("DELETE FROM votes WHERE post_id=? AND uuid=?", (post_id, uuid))
            else:
                self.db.execute(
                    """INSERT INTO votes(post_id, uuid, value, created) VALUES(?,?,?,?)
                       ON CONFLICT(post_id, uuid) DO UPDATE SET value=excluded.value""",
                    (post_id, uuid, value, int(time.time() * 1000)),
                )

    def list_posts(
        self, tab: str, viewer: str | None, sort: str, query: str, page: int, page_size: int
    ) -> tuple[list[PostRow], int]:
        """One page of a board tab and the total number of matching posts.
        'public' lists public posts only; 'shared' private posts whose access list has viewer;
        'mine' every post by viewer."""
        params: dict[str, Any] = {"viewer": viewer, "now": int(time.time() * 1000)}
        if tab == "public":
            where = ["p.visibility = 'public'"]
        elif tab == "shared":
            where = [
                "p.visibility = 'private'",
                "p.uuid != :viewer",
                "EXISTS (SELECT 1 FROM post_access a WHERE a.post_id = p.id AND a.uuid = :viewer)",
            ]
        elif tab == "mine":
            where = ["p.uuid = :viewer"]
        else:
            raise ValueError(tab)
        for i, term in enumerate(query.casefold().split()[:10]):
            params[f"t{i}"] = term
            where.append(f"instr(slop_fold(p.title || char(10) || p.description || char(10) || u.name), :t{i}) > 0")
        clause = " WHERE " + " AND ".join(where)
        with self.lock:
            (total,) = self.db.execute(
                "SELECT COUNT(*) FROM posts p JOIN users u ON u.uuid = p.uuid" + clause, params
            ).fetchone()
            rows = self.db.execute(
                POST_SELECT + clause + f" ORDER BY {BOARD_ORDER[sort]} LIMIT :limit OFFSET :offset",
                {**params, "limit": page_size, "offset": (page - 1) * page_size},
            ).fetchall()
        return [PostRow.from_row(r) for r in rows], int(total)


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


def mojang_profile(settings: Settings, name: str) -> tuple[str, str] | None:
    """Look up a Minecraft name: (dashed uuid, current name), or None if no account has it."""
    url = settings.mojang_profile_url.rstrip("/") + "/" + urllib.parse.quote(name, safe="")
    req = urllib.request.Request(url, headers={"User-Agent": "SlopmaticaCloud/1.0", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=settings.mojang_timeout) as resp:
            if resp.status == 204:
                return None
            if resp.status != 200:
                raise MojangUnavailable(f"HTTP {resp.status}")
            body = resp.read(1 << 16)
    except urllib.error.HTTPError as e:
        e.close()
        if e.code in (400, 404):  # 400: Mojang's answer for some invalid names
            return None
        raise MojangUnavailable(f"HTTP {e.code}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise MojangUnavailable(str(e)) from e
    try:
        data = json.loads(body)
        uid, canonical = data["id"], data["name"]
    except (ValueError, KeyError, TypeError) as e:
        raise MojangUnavailable("unexpected response") from e
    if not isinstance(uid, str) or not UUID_HEX_RE.match(uid.replace("-", "")) or not isinstance(canonical, str):
        raise MojangUnavailable("unexpected response")
    return dash_uuid(uid.replace("-", "")), canonical


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


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=SCRYPT_DKLEN)
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${salt.hex()}${digest.hex()}"


_DUMMY_HASH = f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${'00' * 16}${'00' * SCRYPT_DKLEN}"


def verify_password(password: str, stored: str | None) -> bool:
    """Check password against a stored hash. With stored None it still does the work (and fails),
    so that a missing account or password takes as long as a wrong password."""
    try:
        scheme, n, r, p, salt, expected = (stored or _DUMMY_HASH).split("$")
        if scheme != "scrypt":
            return False
        digest = hashlib.scrypt(
            password.encode(), salt=bytes.fromhex(salt), n=int(n), r=int(r), p=int(p),
            dklen=len(expected) // 2, maxmem=128 * 1024 * 1024,
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(digest.hex(), expected) and stored is not None


def valid_next(value: Any) -> str:
    """A site-relative path (relative to ROOT_PATH) to land on after a login link, or ApiError(400)."""
    bad = ApiError(400, "next must be a site-relative path starting with /")
    if not isinstance(value, str) or not value.startswith("/") or len(value) > MAX_NEXT_LEN:
        raise bad
    if value.startswith("//") or "\\" in value or re.search(r"[\x00-\x20\x7f]", value):
        raise bad
    parts = urllib.parse.urlsplit(value)
    if parts.scheme or parts.netloc or not parts.path.startswith("/"):
        raise bad
    return value


def board_file_stem(title: str, ext: str) -> str:
    """A file name stem from a post title that passes validate_path with ext and a ' (n)' suffix."""
    stem = BAD_SEGMENT_CHARS.sub("_", title.replace("/", "_"))
    room = MAX_SEGMENT_LEN - len(ext) - len(" (9999)")
    while True:
        before = stem
        stem = stem[:room].strip().strip(".")
        if stem == before:
            break
    return stem or "schematic"


# --------------------------------------------------------------------------- request models


class VerifyIn(BaseModel):
    username: str
    serverId: str


class LoginIn(BaseModel):
    name: str
    password: str


class PasswordIn(BaseModel):
    current: str | None = None
    new: str


class PasswordDeleteIn(BaseModel):
    current: str = ""


class PostIn(BaseModel):
    path: str
    title: str
    description: str = ""
    visibility: str
    allowed: list[str] = Field(default_factory=list)


class PostPatchIn(BaseModel):
    title: str | None = None
    description: str | None = None
    visibility: str | None = None
    allowed: list[str] | None = None


class VoteIn(BaseModel):
    value: Any = None


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
    templates.filters["num"] = lambda n: f"{n:,}"
    static_dir = PKG_DIR / "static"
    static_version = str(max(int(p.stat().st_mtime) for p in static_dir.iterdir()))

    from . import __version__
    app = FastAPI(title="Slopmatica Cloud", version=__version__, docs_url=None, redoc_url=None, openapi_url=None)
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

    def check_quota(user: User, delta: int) -> None:
        """Raise 413 unless the account has room for delta more bytes. Call with store.lock held."""
        quota = quota_for(user)
        used = store.used_bytes(user.uuid)
        if quota is not None and used + delta > quota:
            raise ApiError(413, "quota exceeded", usedBytes=used, quotaBytes=quota)

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
    async def weblogin(request: Request) -> dict[str, str]:
        user = authenticate(request, mutating=True, allow_cookie=False)
        next_path = None
        raw = await request.body()
        if raw.strip():
            try:
                body = json.loads(raw)
            except ValueError:
                raise ApiError(400, "invalid request body")
            if not isinstance(body, dict):
                raise ApiError(400, "invalid request body")
            if body.get("next") is not None:
                next_path = valid_next(body["next"])
        token = store.issue_token(user.uuid, "weblogin", WEBLOGIN_TTL, next_path)
        return {"url": f"{settings.public_url}{base}/login/{token}"}

    # ---- website passwords

    def client_ip(request: Request) -> str:
        """The caller's address. Behind the local reverse proxy, the address it reports."""
        host = request.client.host if request.client else "unknown"
        forwarded = request.headers.get("x-forwarded-for")
        if host in LOCAL_PROXIES and forwarded:
            # the right-most entry was added by our proxy; anything left of it came from the client
            host = forwarded.split(",")[-1].strip() or host
        return host

    def set_session_cookie(resp: Response, request: Request, token: str) -> None:
        resp.set_cookie(
            SESSION_COOKIE,
            token,
            max_age=SESSION_TTL,
            path=base or "/",
            httponly=True,
            samesite="lax",
            secure=is_https(request),
        )

    def web_user(request: Request) -> User:
        """The website session's user, for changes that a game token must not be able to make.
        A bearer token is never accepted here, even a valid one."""
        user = session_user(request)
        if user is None:
            raise ApiError(401, "log in on the website first")
        if request.headers.get(CSRF_HEADER) != CSRF_VALUE:
            raise ApiError(403, f"missing X-Requested-With: {CSRF_VALUE} header")
        return user

    def throttled(key: str, limit: int) -> None:
        n, oldest = store.recent_failures(key)
        if n >= limit:
            wait = max(1, -(-((oldest or now_s()) + LOGIN_WINDOW - now_s()) // 60))
            raise ApiError(429, f"too many failed login attempts, try again in {wait} minute{'s' if wait != 1 else ''}")

    def check_new_password(value: str) -> str:
        if not MIN_PASSWORD_LEN <= len(value) <= MAX_PASSWORD_LEN:
            raise ApiError(400, f"the password must be {MIN_PASSWORD_LEN} to {MAX_PASSWORD_LEN} characters")
        return value

    def check_current_password(user: User, current: str | None) -> None:
        """Changing or removing a password needs the current one. Wrong guesses count as failed logins."""
        stored, _ = store.password_info(user.uuid)
        if stored is None:
            return
        key = "name:" + user.name.lower()
        throttled(key, MAX_NAME_FAILURES)
        if not current or len(current) > MAX_PASSWORD_LEN or not verify_password(current, stored):
            store.add_failure(key)
            raise ApiError(403, "the current password is wrong")

    @app.post("/api/login")
    def password_login(request: Request, body: LoginIn) -> JSONResponse:
        # A sync endpoint: scrypt runs in the threadpool.
        if request.headers.get(CSRF_HEADER) != CSRF_VALUE:
            raise ApiError(403, f"missing X-Requested-With: {CSRF_VALUE} header")
        name = body.name.strip()
        name_key, ip_key = "name:" + name.lower()[:64], "ip:" + client_ip(request)
        throttled(ip_key, MAX_IP_FAILURES)
        throttled(name_key, MAX_NAME_FAILURES)
        user = None
        if USERNAME_RE.match(name) and 0 < len(body.password) <= MAX_PASSWORD_LEN:
            candidates = store.password_candidates(name)
            if not candidates:
                verify_password(body.password, None)  # same work as a wrong password
            for candidate, stored in candidates:
                if verify_password(body.password, stored):
                    user = candidate
                    break
        if user is None:
            store.add_failure(name_key, ip_key)
            raise ApiError(401, "wrong name or password")
        store.clear_failures(name_key)
        store.touch_login(user.uuid)
        store.prune()
        resp = JSONResponse({"uuid": user.uuid, "name": user.name})
        set_session_cookie(resp, request, store.issue_token(user.uuid, "session", SESSION_TTL))
        return resp

    @app.post("/api/account/password")
    def set_password(request: Request, body: PasswordIn) -> dict[str, Any]:
        user = web_user(request)
        new = check_new_password(body.new)
        check_current_password(user, body.current)
        store.set_password_hash(user.uuid, hash_password(new))
        ended = store.revoke_sessions(user.uuid, keep=request.cookies.get(SESSION_COOKIE))
        return {"hasPassword": True, "endedSessions": ended}

    @app.delete("/api/account/password")
    def delete_password(request: Request, body: PasswordDeleteIn) -> dict[str, Any]:
        user = web_user(request)
        if store.password_info(user.uuid)[0] is None:
            raise ApiError(409, "there is no password to remove")
        check_current_password(user, body.current)
        store.set_password_hash(user.uuid, None)
        ended = store.revoke_sessions(user.uuid, keep=request.cookies.get(SESSION_COOKIE))
        return {"hasPassword": False, "endedSessions": ended}

    @app.post("/api/account/logout-all")
    def logout_all(request: Request) -> JSONResponse:
        user = authenticate(request, mutating=True)
        ended = store.revoke_sessions(user.uuid)
        resp = JSONResponse({"endedSessions": ended})
        if request.cookies.get(SESSION_COOKIE):
            resp.delete_cookie(SESSION_COOKIE, path=base or "/", httponly=True, samesite="lax", secure=is_https(request))
        return resp

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
                raise ApiError(400, "not a gzip file (every schematic format is gzipped NBT)")
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

    export_slots = threading.BoundedSemaphore(settings.export_slots)

    def export_format(request: Request) -> str:
        fmt = request.query_params.get("format", "").strip().lower().lstrip(".")
        if fmt not in convert.FORMATS:
            raise ApiError(400, "format must be one of " + ", ".join(convert.FORMATS))
        return fmt

    @app.get("/api/export/{path:path}")
    def export_file(path: str, request: Request) -> FileResponse:
        # A sync endpoint, so FastAPI runs the (CPU-bound) conversion in its threadpool.
        user = authenticate(request, mutating=False)
        path = validate_path(path)
        fmt = export_format(request)
        _, disk = require_file(user, path)
        return export_response(disk, path.rsplit("/", 1)[-1], fmt)

    def export_response(disk: Path, name: str, fmt: str) -> FileResponse:
        """Convert disk (whose format and fallback name come from name) to fmt, as a download."""
        src_fmt = convert.format_of(name)
        if fmt == src_fmt:
            raise ApiError(400, f"the file is already .{fmt}")
        if not export_slots.acquire(timeout=EXPORT_WAIT):
            raise ApiError(503, "the server is busy converting other files, try again shortly")
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".export")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "wb") as f:
                convert.convert_file(disk, fmt, f, settings.max_export_volume)
        except FileNotFoundError:
            tmp.unlink(missing_ok=True)
            raise ApiError(404, "no such file")
        except ValueError as e:  # ConversionError, NBTError: the file is not what its extension says
            tmp.unlink(missing_ok=True)
            raise ApiError(400, f"cannot convert {name}: {e}")
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
        finally:
            export_slots.release()
        return FileResponse(
            tmp,
            media_type="application/octet-stream",
            filename=name[: -len(src_fmt) - 1] + "." + fmt,  # type: ignore[arg-type]
            headers={"Cache-Control": "private, no-cache"},
            background=BackgroundTask(tmp.unlink, missing_ok=True),
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

    # ---- schematics board

    def optional_user(request: Request) -> User | None:
        """The caller if authenticated (a bad bearer token is still a 401), else None. No CSRF check:
        callers that change something authenticate(mutating=True) once the post is known to be visible."""
        if request.headers.get("authorization"):
            return authenticate(request, mutating=False)
        return session_user(request)

    def can_view(post: PostRow, user: User | None) -> bool:
        if post.visibility != "private":
            return True
        if user is None:
            return False
        return user.uuid == post.uuid or user.is_admin or store.is_allowed(post.id, user.uuid)

    def find_post(post_id: str, user: User | None) -> PostRow:
        """The post if user may see it. Otherwise 404, whether or not it exists."""
        post = store.get_post(post_id) if POST_ID_RE.match(post_id) else None
        if post is None or not can_view(post, user):
            raise ApiError(404, "no such post")
        return post

    def visible_post(request: Request, post_id: str) -> tuple[PostRow, User | None]:
        user = optional_user(request)
        return find_post(post_id, user), user

    def acting_on_post(request: Request, post_id: str, *, owner_only: bool, admin_ok: bool = False) -> tuple[PostRow, User]:
        """For changes: 404 if invisible to the caller, then 401/403 for auth, CSRF and ownership."""
        post, _ = visible_post(request, post_id)
        user = authenticate(request, mutating=True)
        if owner_only and post.uuid != user.uuid and not (admin_ok and user.is_admin):
            raise ApiError(403, "only the author can do that")
        return post, user

    def clean_title(value: Any) -> str:
        if not isinstance(value, str):
            raise ApiError(400, "title is required")
        title = value.strip()
        if not 1 <= len(title) <= MAX_TITLE_LEN:
            raise ApiError(400, f"title must be 1 to {MAX_TITLE_LEN} characters")
        if TITLE_BAD_CHARS.search(title):
            raise ApiError(400, "title may not contain control characters or line breaks")
        return title

    def clean_description(value: Any) -> str:
        if not isinstance(value, str):
            raise ApiError(400, "description must be text")
        text = value.replace("\r\n", "\n").replace("\r", "\n").strip()
        if len(text) > MAX_DESCRIPTION_LEN:
            raise ApiError(400, f"description must be at most {MAX_DESCRIPTION_LEN} characters")
        if DESCRIPTION_BAD_CHARS.search(text):
            raise ApiError(400, "description may not contain control characters")
        return text

    def clean_visibility(value: Any) -> str:
        if value not in VISIBILITIES:
            raise ApiError(400, "visibility must be one of " + ", ".join(VISIBILITIES))
        return value

    def resolve_names(names: list[str], known: list[tuple[str, str]] = ()) -> list[tuple[str, str]]:
        """Minecraft names -> [(uuid, name)] through Mojang. Names already in known are not looked up again."""
        wanted: list[str] = []
        for raw in names:
            name = raw.strip()
            if not name:
                continue
            if not USERNAME_RE.match(name):
                raise ApiError(400, f"invalid Minecraft username: {name}")
            if name.lower() not in (w.lower() for w in wanted):
                wanted.append(name)
        if len(wanted) > MAX_ALLOWED_NAMES:
            raise ApiError(400, f"at most {MAX_ALLOWED_NAMES} usernames per post")
        by_name = {name.lower(): (uuid, name) for uuid, name in known}
        out: dict[str, tuple[str, str]] = {}
        unknown: list[str] = []
        for name in wanted:
            hit = by_name.get(name.lower())
            if hit is None:
                try:
                    hit = mojang_profile(settings, name)
                except MojangUnavailable as e:
                    log.warning("Mojang profile lookup failed: %s", e)
                    raise ApiError(502, "could not reach Mojang to look up Minecraft usernames, try again later")
            if hit is None:
                unknown.append(name)
            else:
                out.setdefault(hit[0], hit)
        if unknown:
            raise ApiError(
                400, ("unknown Minecraft usernames: " if len(unknown) > 1 else "unknown Minecraft username: ")
                + ", ".join(unknown), unknown=unknown,
            )
        return list(out.values())

    @dataclass
    class Snapshot:
        file: Path  # temporary copy in store.tmp
        thumb: Path | None
        filename: str
        fmt: str
        size: int
        sha256: str
        dims: tuple[int, int, int] | None = None
        blocks: int | None = None

        def cleanup(self) -> None:
            self.file.unlink(missing_ok=True)
            if self.thumb is not None:
                self.thumb.unlink(missing_ok=True)

    def copy_hashed(src: Path, dst: Any) -> tuple[int, str]:
        h = hashlib.sha256()
        size = 0
        with src.open("rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
                size += len(chunk)
                dst.write(chunk)
        return size, h.hexdigest()

    def schematic_meta(disk: Path, filename: str) -> tuple[tuple[int, int, int] | None, int | None]:
        """(enclosing size, block count) of a snapshot, or (None, None) if it cannot be parsed."""
        fmt = convert.format_of(filename)
        if fmt is None or not export_slots.acquire(timeout=EXPORT_WAIT):
            return None, None
        try:
            _, root = nbt.load(disk)
            schem = convert.read(root, fmt, filename[: -len(fmt) - 1], settings.max_export_volume)
            _, dims = schem.bounds()
            return dims, schem.block_count()
        except Exception as e:  # any valid gzip file can be posted; it just has no details
            log.info("could not read %s for board details: %s", filename, e)
            return None, None
        finally:
            export_slots.release()

    def take_snapshot(user: User, path: str) -> Snapshot:
        """Copy one of user's files (and its thumbnail) to temporary files and read its details."""
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".snap")
        tmp = Path(tmp_name)
        snap = Snapshot(tmp, None, path.rsplit("/", 1)[-1], "", 0, "")
        try:
            with os.fdopen(fd, "wb") as f, store.lock:
                row, disk = require_file(user, path)
                snap.size, snap.sha256 = copy_hashed(disk, f)
                thumb = store.thumb_path(user.uuid, path)
                if row.has_thumb and thumb.is_file():
                    snap.thumb = tmp.with_suffix(".png")
                    shutil.copyfile(thumb, snap.thumb)
            snap.fmt = convert.format_of(snap.filename) or ""
            snap.dims, snap.blocks = schematic_meta(tmp, snap.filename)
        except BaseException:
            snap.cleanup()
            raise
        return snap

    def snapshot_columns(snap: Snapshot) -> dict[str, Any]:
        dims = snap.dims or (None, None, None)
        return {
            "filename": snap.filename, "format": snap.fmt, "size": snap.size, "sha256": snap.sha256,
            "has_thumb": int(snap.thumb is not None), "size_x": dims[0], "size_y": dims[1], "size_z": dims[2],
            "block_count": snap.blocks, "updated": int(time.time() * 1000),
        }

    def install_snapshot(pdir: Path, snap: Snapshot, old_filename: str | None = None) -> None:
        """Move a snapshot's temporary files into posts/<id>/. Call with store.lock held."""
        pdir.mkdir(parents=True, exist_ok=True)
        os.replace(snap.file, store._under(pdir, snap.filename))
        if old_filename and old_filename != snap.filename:
            store._under(pdir, old_filename).unlink(missing_ok=True)
        if snap.thumb is not None:
            os.replace(snap.thumb, pdir / POST_THUMB)
        else:
            (pdir / POST_THUMB).unlink(missing_ok=True)

    def post_api(post: PostRow, viewer: User | None, detail: bool = False) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": post.id,
            "title": post.title,
            "visibility": post.visibility,
            "author": {"uuid": post.uuid, "name": post.author_name},
            "filename": post.filename,
            "format": post.format,
            "size": post.size,
            "sha256": post.sha256,
            "dimensions": list(post.dims) if post.dims else None,
            "blockCount": post.block_count,
            "hasThumbnail": post.has_thumb,
            "created": post.created,
            "updated": post.updated,
            "score": post.score,
            "ups": post.ups,
            "downs": post.downs,
        }
        if detail:
            out["description"] = post.description
            out["myVote"] = store.get_vote(post.id, viewer.uuid) if viewer else None
            if viewer and (viewer.uuid == post.uuid or viewer.is_admin):
                out["sourcePath"] = post.source_path
                out["allowed"] = [{"uuid": u, "name": n} for u, n in store.get_allowed(post.id)]
        return out

    def board_query(params: Any, strict: bool) -> tuple[str, str, str, int]:
        """(tab, sort, query, page) from query parameters; strict raises 400 instead of defaulting."""
        tab = params.get("tab") or "public"
        if tab not in BOARD_TABS:
            if strict:
                raise ApiError(400, "tab must be one of " + ", ".join(BOARD_TABS))
            tab = "public"
        sort = params.get("sort") or ("top" if tab == "public" else "new")
        if sort not in BOARD_SORTS:
            if strict:
                raise ApiError(400, "sort must be one of " + ", ".join(BOARD_SORTS))
            sort = "top" if tab == "public" else "new"
        query = (params.get("q") or "").strip()[:MAX_QUERY_LEN]
        raw_page = params.get("page") or "1"
        page = int(raw_page) if raw_page.isdigit() and 0 < int(raw_page) <= 100000 else 0
        if page == 0:
            if strict:
                raise ApiError(400, "page must be a positive integer")
            page = 1
        return tab, sort, query, page

    @app.get("/api/board")
    def board_list(request: Request) -> dict[str, Any]:
        tab, sort, query, page = board_query(request.query_params, strict=True)
        user = optional_user(request) if tab == "public" else authenticate(request, mutating=False)
        size = settings.board_page_size
        posts, total = store.list_posts(tab, user.uuid if user else None, sort, query, page, size)
        out: dict[str, Any] = {
            "posts": [post_api(p, user) for p in posts],
            "tab": tab, "sort": sort, "q": query, "page": page,
            "pageSize": size, "total": total, "pages": max(1, -(-total // size)),
        }
        if tab == "mine" and user is not None:
            out["postCount"], out["postBytes"] = store.post_usage(user.uuid)
        return out

    @app.post("/api/board")
    def board_create(request: Request, body: PostIn) -> JSONResponse:
        user = authenticate(request, mutating=True)
        path = validate_path(body.path)
        title = clean_title(body.title)
        description = clean_description(body.description)
        visibility = clean_visibility(body.visibility)
        limit = settings.max_posts_per_user
        if store.post_usage(user.uuid)[0] >= limit:
            raise ApiError(409, f"you already have {limit} posts on the board, which is the limit")
        allowed = resolve_names(body.allowed)
        with store.lock:
            row, _ = require_file(user, path)
            check_quota(user, row.size)
        snap = take_snapshot(user, path)
        try:
            with store.lock:
                if store.post_usage(user.uuid)[0] >= limit:
                    raise ApiError(409, f"you already have {limit} posts on the board, which is the limit")
                check_quota(user, snap.size)
                post_id = store.new_post_id()
                pdir = store.post_dir(post_id)
                try:
                    install_snapshot(pdir, snap)
                    store.insert_post({
                        "id": post_id, "uuid": user.uuid, "title": title, "description": description,
                        "visibility": visibility, "source_path": path, "created": int(time.time() * 1000),
                        **snapshot_columns(snap),
                    }, allowed)
                except BaseException:
                    shutil.rmtree(pdir, ignore_errors=True)
                    store.delete_post_row(post_id)
                    raise
        finally:
            snap.cleanup()
        post = store.get_post(post_id)
        assert post is not None
        return JSONResponse(post_api(post, user, detail=True), status_code=201)

    @app.get("/api/board/{post_id}")
    def board_get(post_id: str, request: Request) -> dict[str, Any]:
        post, user = visible_post(request, post_id)
        return post_api(post, user, detail=True)

    @app.patch("/api/board/{post_id}")
    def board_patch(post_id: str, request: Request, body: PostPatchIn) -> dict[str, Any]:
        post, user = acting_on_post(request, post_id, owner_only=True)
        given = body.model_fields_set
        values: dict[str, Any] = {}
        if "title" in given:
            values["title"] = clean_title(body.title)
        if "description" in given:
            values["description"] = clean_description(body.description)
        if "visibility" in given:
            values["visibility"] = clean_visibility(body.visibility)
        allowed = None
        if "allowed" in given:
            if body.allowed is None:
                raise ApiError(400, "allowed must be a list of Minecraft usernames")
            allowed = resolve_names(body.allowed, store.get_allowed(post.id))
        store.update_post(post.id, values, allowed)
        return post_api(store.get_post(post.id), user, detail=True)  # type: ignore[arg-type]

    @app.post("/api/board/{post_id}/refresh")
    def board_refresh(post_id: str, request: Request) -> dict[str, Any]:
        post, user = acting_on_post(request, post_id, owner_only=True)
        if store.get_file(user.uuid, post.source_path) is None:
            raise ApiError(409, f"{post.source_path} is no longer in your cloud files")
        try:
            snap = take_snapshot(user, post.source_path)
        except ApiError as e:
            if e.status == 404:
                raise ApiError(409, f"{post.source_path} is no longer in your cloud files")
            raise
        try:
            with store.lock:
                current = store.get_post(post.id)
                if current is None:
                    raise ApiError(404, "no such post")
                check_quota(user, snap.size - current.size)
                install_snapshot(store.post_dir(post.id), snap, current.filename)
                store.update_post(post.id, snapshot_columns(snap))
        finally:
            snap.cleanup()
        return post_api(store.get_post(post.id), user, detail=True)  # type: ignore[arg-type]

    @app.delete("/api/board/{post_id}")
    def board_delete(post_id: str, request: Request) -> Response:
        post, user = acting_on_post(request, post_id, owner_only=True, admin_ok=True)
        with store.lock:
            store.delete_post_row(post.id)
            shutil.rmtree(store.post_dir(post.id), ignore_errors=True)
        if user.uuid != post.uuid:
            log.info("admin %s deleted board post %s (%r by %s)", user.name, post.id, post.title, post.author_name)
        return Response(status_code=204)

    @app.post("/api/board/{post_id}/vote")
    def board_vote(post_id: str, request: Request, body: VoteIn) -> dict[str, Any]:
        post, user = acting_on_post(request, post_id, owner_only=False)
        if type(body.value) is not int or body.value not in (-1, 0, 1):
            raise ApiError(400, "value must be 1, -1 or 0")
        if post.uuid == user.uuid:
            raise ApiError(403, "you cannot vote on your own post")
        store.set_vote(post.id, user.uuid, body.value)
        post = store.get_post(post.id)  # type: ignore[assignment]
        return {"score": post.score, "ups": post.ups, "downs": post.downs, "myVote": body.value}

    @app.post("/api/board/{post_id}/save")
    def board_save(post_id: str, request: Request) -> JSONResponse:
        post, user = acting_on_post(request, post_id, owner_only=False)
        ext = "." + post.format if post.format else post.filename[post.filename.rfind("."):]
        stem = board_file_stem(post.title, ext)
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".save")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "wb") as f:
                try:
                    size, digest = copy_hashed(store.post_file(post), f)
                except FileNotFoundError:
                    raise ApiError(404, "no such post")
            with store.lock:
                check_quota(user, size)
                for n in range(1, 10000):
                    path = f"{BOARD_FOLDER}/{stem}{'' if n == 1 else f' ({n})'}{ext}"
                    validate_path(path)
                    dest = store.file_path(user.uuid, path)
                    if store.get_file(user.uuid, path) is None and not dest.exists():
                        break
                else:
                    raise ApiError(409, "too many copies of this post in your board folder")
                try:
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(tmp, dest)
                except (NotADirectoryError, IsADirectoryError, FileExistsError):
                    raise ApiError(409, "path conflicts with an existing file or folder")
                thumb = store.post_dir(post.id) / POST_THUMB
                has_thumb = False
                if post.has_thumb and thumb.is_file():
                    dest_thumb = store.thumb_path(user.uuid, path)
                    dest_thumb.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(thumb, dest_thumb)
                    has_thumb = True
                row = FileRow(path, size, digest, int(time.time() * 1000), has_thumb)
                store.put_file_row(user.uuid, row)
        finally:
            tmp.unlink(missing_ok=True)
        return JSONResponse(row.api(), status_code=201)

    @app.get("/api/board/{post_id}/file")
    def board_file(post_id: str, request: Request) -> FileResponse:
        post, _ = visible_post(request, post_id)
        disk = store.post_file(post)
        if not disk.is_file():
            raise ApiError(404, "no such post")
        return FileResponse(
            disk,
            media_type="application/octet-stream",
            filename=post.filename,
            headers={"X-Sha256": post.sha256, "Cache-Control": "private, no-cache"},
        )

    @app.get("/api/board/{post_id}/thumbnail")
    def board_thumbnail(post_id: str, request: Request) -> FileResponse:
        post, _ = visible_post(request, post_id)
        thumb = store.post_dir(post.id) / POST_THUMB
        if not post.has_thumb or not thumb.is_file():
            raise ApiError(404, "no thumbnail")
        return FileResponse(thumb, media_type="image/png", headers={"Cache-Control": "private, max-age=86400"})

    @app.get("/api/board/{post_id}/export")
    def board_export(post_id: str, request: Request) -> FileResponse:
        post, _ = visible_post(request, post_id)
        fmt = export_format(request)
        disk = store.post_file(post)
        if not disk.is_file():
            raise ApiError(404, "no such post")
        return export_response(disk, post.filename, fmt)

    # ---- website

    def render(name: str, status: int = 200, **ctx: Any) -> HTMLResponse:
        html = templates.get_template(name).render(base=base, v=static_version, **ctx)
        return HTMLResponse(html, status_code=status, headers={"Cache-Control": "no-store"})

    @app.get("/", response_class=HTMLResponse)
    def home(request: Request) -> HTMLResponse:
        user = session_user(request)
        if user is None:
            return render("landing.html", max_password=MAX_PASSWORD_LEN)
        files = store.list_files(user.uuid)
        used = store.used_bytes(user.uuid)  # files plus board snapshots
        post_count, post_bytes = store.post_usage(user.uuid)
        quota = quota_for(user)
        entries = []
        for f in files:
            folder, _, name = f.path.rpartition("/")
            ext = next(e for e in SCHEMATIC_EXTENSIONS if name.lower().endswith(e))
            entries.append({
                "f": f, "folder": folder, "name": name, "stem": name[: -len(ext)], "ext": ext,
                "exports": [e for e in SCHEMATIC_EXTENSIONS if e != ext],
            })
        folders = sorted({e["folder"] for e in entries if e["folder"]}, key=str.lower)
        pct = 0.0 if not quota else min(100.0, used * 100.0 / quota)
        return render(
            "files.html",
            user=user,
            nav="files",
            entries=entries,
            post_count=post_count,
            post_bytes=post_bytes,
            visibilities=VISIBILITIES,
            max_title=MAX_TITLE_LEN,
            max_description=MAX_DESCRIPTION_LEN,
            folders=folders,
            used=used,
            quota=quota,
            pct=f"{pct:.2f}",
            over=quota is not None and used >= quota,
        )

    def board_url(tab: str = "public", sort: str | None = None, q: str = "", page: int = 1) -> str:
        """A link to a board page, leaving out parameters at their defaults."""
        params: dict[str, Any] = {}
        if tab != "public":
            params["tab"] = tab
        if sort and sort != ("top" if tab == "public" else "new"):
            params["sort"] = sort
        if q:
            params["q"] = q
        if page > 1:
            params["page"] = page
        return f"{base}/board" + ("?" + urllib.parse.urlencode(params) if params else "")

    @app.get("/board", response_class=HTMLResponse)
    def board_page(request: Request) -> HTMLResponse:
        user = session_user(request)
        tab, sort, query, page = board_query(request.query_params, strict=False)
        size = settings.board_page_size
        posts: list[PostRow] = []
        total = 0
        if tab == "public" or user is not None:
            posts, total = store.list_posts(tab, user.uuid if user else None, sort, query, page, size)
        return render(
            "board.html",
            user=user,
            nav="board",
            tab=tab,
            sort=sort,
            q=query,
            page=page,
            pages=max(1, -(-total // size)),
            total=total,
            posts=posts,
            board_url=board_url,
            sorts=BOARD_SORTS,
        )

    @app.get("/board/{post_id}", response_class=HTMLResponse)
    def post_page(post_id: str, request: Request) -> HTMLResponse:
        user = session_user(request)
        try:
            post = find_post(post_id, user)
        except ApiError:
            return render("not_found.html", status=404, user=user, nav="board")
        own = user is not None and user.uuid == post.uuid
        allowed = store.get_allowed(post.id) if user and (own or user.is_admin) else []
        ext = "." + post.format if post.format else ""
        return render(
            "post.html",
            user=user,
            nav="board",
            post=post,
            own=own,
            my_vote=store.get_vote(post.id, user.uuid) if user else 0,
            allowed=allowed,
            source_exists=own and store.get_file(post.uuid, post.source_path) is not None,
            exports=[e for e in SCHEMATIC_EXTENSIONS if e != ext] if ext else [],
            visibilities=VISIBILITIES,
            max_title=MAX_TITLE_LEN,
            max_description=MAX_DESCRIPTION_LEN,
        )

    @app.get("/account", response_class=HTMLResponse)
    def account_page(request: Request) -> Response:
        user = session_user(request)
        if user is None:
            return RedirectResponse(f"{base}/", status_code=303)
        used = store.used_bytes(user.uuid)
        post_count, post_bytes = store.post_usage(user.uuid)
        quota = quota_for(user)
        stored, password_set = store.password_info(user.uuid)
        pct = 0.0 if not quota else min(100.0, used * 100.0 / quota)
        return render(
            "account.html",
            user=user,
            nav="account",
            used=used,
            quota=quota,
            pct=f"{pct:.2f}",
            over=quota is not None and used >= quota,
            file_count=len(store.list_files(user.uuid)),
            file_bytes=used - post_bytes,
            post_count=post_count,
            post_bytes=post_bytes,
            has_password=stored is not None,
            password_set=password_set * 1000 if password_set else None,
            min_password=MIN_PASSWORD_LEN,
            max_password=MAX_PASSWORD_LEN,
        )

    @app.get("/login/{token}")
    def login(token: str, request: Request) -> Response:
        found = store.consume_token(token, "weblogin")
        if found is None:
            return render("login_failed.html", status=400)
        user, next_path = found
        session = store.issue_token(user.uuid, "session", SESSION_TTL)
        resp = RedirectResponse(f"{base}{next_path or '/'}", status_code=303)
        set_session_cookie(resp, request, session)
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
