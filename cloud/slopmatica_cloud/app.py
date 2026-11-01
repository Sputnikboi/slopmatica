"""Slopmatica Cloud: per-Minecraft-account storage for schematics (.slopmatic, .nbt, .litematic).

Identity comes from Mojang's session server (the same handshake a Minecraft
server does), so there are no passwords. See README.md for the API.
"""

from __future__ import annotations

import gzip
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
import markupsafe
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import Headers
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import convert, model3d, nbt, textfilter

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
SMALL_PARSE_BYTES = 1024 * 1024  # uploads up to this (gzipped) size are checked for mod blocks without a conversion slot

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
# Tags a post can carry (the owner picks any number), in display order.
TAGS: dict[str, str] = {
    "redstone": "Redstone",
    "farm": "Farm",
    "decoration": "Decoration",
    "building": "Building",
    "survival-friendly": "Survival friendly",
    "creative-only": "Creative only",
    "map-art": "Map art",
    "transport": "Transport",
    "technical": "Technical",
    "terrain": "Terrain",
    "statue": "Statue",
    "interior": "Interior",
}
# Size categories by block count (non-air, non-void): (key, label, exclusive upper bound or None).
SIZE_CLASSES: tuple[tuple[str, str, int | None], ...] = (
    ("small", "Small", 1_000),
    ("medium", "Medium", 10_000),
    ("big", "Big", 100_000),
    ("very-big", "Very big", None),
)
SIZE_LABELS = {key: label for key, label, _ in SIZE_CLASSES}
MODEL_CACHE_PREFIX = "model-"  # posts/<id>/model-<sha256[:16]>.json.gz (or .toobig): the 3D view's cache
TITLE_BAD_CHARS = re.compile(r"[\x00-\x1f\x7f]")
DESCRIPTION_BAD_CHARS = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")  # tabs and newlines are fine

# ---- mod releases
RELEASES_DIR = "releases"  # <data>/releases/<filename>.jar
GITHUB_URL = "https://github.com/Sputnikboi/slopmatica"
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([\w.]+))?$", re.ASCII)
MAX_VERSION_LEN = 64
MC_VERSION_RE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$")  # 26.3, 1.21.10, 25w14a, 26.3-pre1
LOADER_RE = re.compile(r"^[a-z][a-z0-9_-]{0,19}$")
# A jar's file name: one plain segment, so it can't leave the releases folder or hide as a dot file.
RELEASE_FILENAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,99}\.jar$")
JAR_MAGIC = b"PK\x03\x04"
MAX_CHANGELOG_LEN = 20000
DEFAULT_LOADER = "fabric"

# ---- feedback
FEEDBACK_KINDS: dict[str, str] = {"bug": "Bug report", "suggestion": "Suggestion", "other": "Other"}
FEEDBACK_STATUSES = ("open", "resolved", "all")
MAX_FEEDBACK_LEN = 5000
MAX_FEEDBACK_VERSION_LEN = 40
MAX_CONTACT_LEN = 200
# Submissions per LOGIN_WINDOW, counted in login_failures like failed logins.
FEEDBACK_IP_LIMIT = 5  # logged out, per address
FEEDBACK_USER_LIMIT = 20  # logged in, per account

# ---- access (the Fruitbowl whitelist), applications and storage requests
SERVER_NAME = "Fruitbowl"  # the Minecraft server whose whitelist decides who may use the cloud
WHITELIST_SOURCES = ("seed", "upload", "approved", "manual")
MAX_WHITELIST_BYTES = 4 * 1024 * 1024  # an uploaded whitelist.json
MAX_WHITELIST_NAME_LEN = 64  # loose: Bedrock (Floodgate) names may be longer and carry a prefix
MAX_APPLICATION_LEN = 2000  # each of the two questions
DECISIONS = ("pending", "approved", "rejected")
MAX_NOTE_LEN = 1000  # an admin's reason for a rejection or a report's outcome
# New applications or storage requests per account within REQUEST_WINDOW (one may be open at a time).
REQUEST_LIMIT = 3
REQUEST_WINDOW = 7 * DAY
GIB = 1024 ** 3
STORAGE_CHOICES = (2 * GIB, 5 * GIB, 10 * GIB)  # the request form's presets; any whole GB up to the cap works
MAX_STORAGE_REQUEST = 1024 * GIB
MAX_REQUEST_REASON_LEN = 1000
STORAGE_WARN_PCT = 80  # the account page offers more storage above this

# ---- badges and reports
BADGES: dict[str, str] = {"verified": "Verified", "mod": "Mod", "dev": "Dev"}  # in display order
REPORT_REASONS: dict[str, str] = {
    "spam": "Spam",
    "inappropriate": "Inappropriate",
    "stolen": "Stolen / not their build",
    "broken": "Broken file",
    "other": "Other",
}
REPORT_STATUSES = ("open", "resolved", "dismissed")
MAX_REPORT_LEN = 2000
REPORT_USER_LIMIT = 10  # reports per account per LOGIN_WINDOW, counted in login_failures
ADMIN_PAGE_SIZE = 50
LAST_SEEN_EVERY = 300  # seconds: users.last_seen is written at most this often per account


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
    max_release_bytes: int = 64 * 1024 * 1024
    # Only players on the whitelist (and admins) may sync, upload, post, vote and report.
    whitelist_enforced: bool = True
    # A whitelist.json read once, when the database migrates to schema 5. None: start empty.
    whitelist_seed: Path | None = None

    def __post_init__(self) -> None:
        self.data_dir = Path(self.data_dir).resolve()
        self.root_path = _normalize_root_path(self.root_path)
        self.admins = frozenset(a.strip().lower() for a in self.admins if a.strip())
        self.public_url = self.public_url.rstrip("/")

    @property
    def site_url(self) -> str:
        """The site's absolute root URL: PUBLIC_URL plus ROOT_PATH, unless PUBLIC_URL already ends in it
        (the proxy strips the prefix, so web-login links work either way, but these URLs end up in the game)."""
        if self.root_path and self.public_url.endswith(self.root_path):
            return self.public_url
        return self.public_url + self.root_path

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
        if env.get("SLOPMATICA_WHITELIST_ENFORCED"):
            kw["whitelist_enforced"] = env["SLOPMATICA_WHITELIST_ENFORCED"].strip().lower() not in ("0", "false", "no", "off")
        if env.get("SLOPMATICA_WHITELIST_SEED"):
            kw["whitelist_seed"] = Path(env["SLOPMATICA_WHITELIST_SEED"]).expanduser()
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


def parse_uuid(value: Any) -> str | None:
    """A Minecraft uuid, dashed or not, as the dashed lowercase form; None if it isn't one."""
    if not isinstance(value, str):
        return None
    v = value.strip().lower()
    if UUID_HEX_RE.match(v):
        return dash_uuid(v)
    return v if DASHED_UUID_RE.match(v) else None


def parse_whitelist(data: Any) -> list[tuple[str, str]]:
    """Minecraft's whitelist.json ([{"uuid", "name"}, ...]) as [(dashed uuid, name)], in file order and
    without repeated uuids. Other keys are ignored. ApiError(400) naming the first bad entry."""
    if not isinstance(data, list):
        raise ApiError(400, 'a whitelist must be a JSON list of {"uuid", "name"} entries')
    out: dict[str, str] = {}
    for i, entry in enumerate(data):
        uuid = parse_uuid(entry.get("uuid")) if isinstance(entry, dict) else None
        name = entry.get("name") if isinstance(entry, dict) else None
        if uuid is None:
            raise ApiError(400, f"whitelist entry {i + 1} has no valid uuid")
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= MAX_WHITELIST_NAME_LEN or TITLE_BAD_CHARS.search(name):
            raise ApiError(400, f"whitelist entry {i + 1} has no valid name")
        out.setdefault(uuid, name.strip())
    return list(out.items())


def sha256_hex(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def now_s() -> int:
    return int(time.time())


def size_class(block_count: int | None) -> str | None:
    """The size category of a post with block_count blocks, or None if that is unknown."""
    if block_count is None:
        return None
    for key, _, below in SIZE_CLASSES:
        if below is None or block_count < below:
            return key
    raise AssertionError("unreachable")


def size_class_sql(column: str) -> str:
    """size_class() as an SQL expression over column (used by the migration)."""
    cases = " ".join(f"WHEN {column} < {below} THEN '{key}'" for key, _, below in SIZE_CLASSES if below is not None)
    return f"CASE WHEN {column} IS NULL THEN NULL {cases} ELSE '{SIZE_CLASSES[-1][0]}' END"


# --------------------------------------------------------------------------- storage


@dataclass
class User:
    uuid: str
    name: str
    is_admin: bool
    quota_bytes: int | None = None  # a per-account quota set by an admin; None: the default (admins: unlimited)


@dataclass
class FileSource:
    """The board post a file was saved from with "Add to my cloud"."""
    post_id: str
    title: str
    author_uuid: str
    author_name: str

    def api(self) -> dict[str, str]:
        return {"postId": self.post_id, "title": self.title, "author": self.author_name, "authorUuid": self.author_uuid}


@dataclass
class FileRow:
    path: str
    size: int
    sha256: str
    modified: int
    has_thumb: bool
    source: FileSource | None = None

    def api(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "size": self.size,
            "sha256": self.sha256,
            "modified": self.modified,
            "hasThumbnail": self.has_thumb,
            "source": self.source.api() if self.source else None,
        }

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "FileRow":
        source = None
        if r["source_post"]:
            # the author's current name if they have logged in since, else the name when it was saved
            name = r["source_author_now"] or r["source_author_name"] or ""
            source = FileSource(r["source_post"], r["source_title"] or "", r["source_author_uuid"] or "", name)
        return cls(r["path"], r["size"], r["sha256"], r["modified"], bool(r["has_thumb"]), source)


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
    tags: list[str] = field(default_factory=list)  # in TAGS order
    size_class: str | None = None  # a SIZE_CLASSES key, None if the block count is unknown
    author_badges: list[str] = field(default_factory=list)  # in BADGES order
    hidden: int | None = None  # unix ms when an admin hid it (only the author and admins see it then)
    hidden_reason: str = ""

    @property
    def score(self) -> int:
        return self.ups - self.downs

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "PostRow":
        dims = (r["size_x"], r["size_y"], r["size_z"])
        tags = set((r["tags"] or "").split(","))
        return cls(
            r["id"], r["uuid"], r["author_name"], r["title"], r["description"], r["visibility"],
            r["source_path"], r["filename"], r["format"], r["size"], r["sha256"], bool(r["has_thumb"]),
            dims if None not in dims else None, r["block_count"], r["created"], r["updated"],
            r["ups"], r["downs"], [t for t in TAGS if t in tags], r["size_class"],
            badge_list(r["author_badges"]), r["hidden"], r["hidden_reason"] or "",
        )


def badge_list(joined: str | None) -> list[str]:
    """group_concat(badge) as a list in BADGES order."""
    have = set((joined or "").split(","))
    return [b for b in BADGES if b in have]


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
    # 0.5.0: tags, size categories, and where a file saved from the board came from.
    # Before this step runs, Store.convert_post_snapshots turns board snapshots into .slopmatic.
    3: f"""
ALTER TABLE posts ADD COLUMN size_class TEXT;     -- small | medium | big | very-big, NULL if the block count is unknown
UPDATE posts SET size_class = {size_class_sql("block_count")};
CREATE INDEX IF NOT EXISTS posts_size_class ON posts(size_class);
CREATE TABLE IF NOT EXISTS post_tags (
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag TEXT NOT NULL,
    PRIMARY KEY (post_id, tag)
);
CREATE INDEX IF NOT EXISTS post_tags_tag ON post_tags(tag, post_id);
ALTER TABLE files ADD COLUMN source_post TEXT;        -- "Add to my cloud": the post id, NULL for the user's own files
ALTER TABLE files ADD COLUMN source_title TEXT;       -- the post's title then
ALTER TABLE files ADD COLUMN source_author_uuid TEXT; -- the post's author
ALTER TABLE files ADD COLUMN source_author_name TEXT; -- the author's name then
""",
    # 0.6.0: mod releases (jars in <data>/releases/) and feedback from the website
    4: """
CREATE TABLE IF NOT EXISTS releases (
    id INTEGER PRIMARY KEY,
    version TEXT NOT NULL,         -- semver: 0.6.0, 0.7.0-beta.1
    mc_version TEXT NOT NULL,      -- the Minecraft version the jar is built for
    loader TEXT NOT NULL,          -- fabric
    filename TEXT NOT NULL UNIQUE, -- releases/<filename>
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL,
    uploaded INTEGER NOT NULL,     -- unix ms
    changelog TEXT NOT NULL DEFAULT '',
    hidden INTEGER NOT NULL DEFAULT 0,
    downloads INTEGER NOT NULL DEFAULT 0,
    UNIQUE (version, mc_version, loader)
);
CREATE TABLE IF NOT EXISTS feedback (
    id INTEGER PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('bug', 'suggestion', 'other')),
    message TEXT NOT NULL,
    mod_version TEXT,
    mc_version TEXT,
    contact TEXT,
    uuid TEXT,                     -- the logged-in sender, NULL if anonymous
    name TEXT,                     -- their name then
    created INTEGER NOT NULL,      -- unix ms
    resolved INTEGER               -- unix ms, NULL while open
);
CREATE INDEX IF NOT EXISTS feedback_open ON feedback(resolved, created);
""",
    # 0.7.0: the Fruitbowl whitelist and applications for it, per-account quotas and storage requests,
    # badges, reports, and posts hidden by an admin. Store.seed_whitelist runs right after, in the same transaction.
    5: """
ALTER TABLE users ADD COLUMN quota_bytes INTEGER;  -- set by an admin, NULL: the default quota
ALTER TABLE users ADD COLUMN last_seen INTEGER;    -- unix s, last authenticated request (written every few minutes)
UPDATE users SET last_seen = last_login;
ALTER TABLE posts ADD COLUMN hidden INTEGER;       -- unix ms when an admin hid it, NULL if not hidden
ALTER TABLE posts ADD COLUMN hidden_reason TEXT;
CREATE TABLE IF NOT EXISTS whitelist (
    uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,            -- as in the whitelist file, or the account's name when added
    source TEXT NOT NULL CHECK (source IN ('seed', 'upload', 'approved', 'manual')),
    added INTEGER NOT NULL,        -- unix ms
    added_by TEXT                  -- the admin's uuid, NULL for the seed
);
CREATE TABLE IF NOT EXISTS applications (
    id INTEGER PRIMARY KEY,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    name TEXT NOT NULL,            -- the applicant's name then
    reason TEXT NOT NULL,          -- why they want access
    community TEXT NOT NULL,       -- how they know the community
    contact TEXT,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created INTEGER NOT NULL,      -- unix ms
    decided INTEGER,               -- unix ms
    decided_by TEXT,               -- admin uuid
    note TEXT                      -- the admin's reason, shown to the applicant
);
CREATE UNIQUE INDEX IF NOT EXISTS applications_one_open ON applications(uuid) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS applications_status ON applications(status, created);
CREATE TABLE IF NOT EXISTS storage_requests (
    id INTEGER PRIMARY KEY,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    requested_bytes INTEGER NOT NULL,
    reason TEXT NOT NULL,
    used_bytes INTEGER NOT NULL,   -- usage and quota when asked
    quota_bytes INTEGER,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created INTEGER NOT NULL,      -- unix ms
    decided INTEGER,
    decided_by TEXT,
    granted_bytes INTEGER,         -- the quota set on approval
    note TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS storage_requests_one_open ON storage_requests(uuid) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS storage_requests_status ON storage_requests(status, created);
CREATE TABLE IF NOT EXISTS badges (
    uuid TEXT NOT NULL REFERENCES users(uuid),
    badge TEXT NOT NULL CHECK (badge IN ('verified', 'mod', 'dev')),
    granted INTEGER NOT NULL,      -- unix ms
    granted_by TEXT,
    PRIMARY KEY (uuid, badge)
);
CREATE TABLE IF NOT EXISTS reports (
    id INTEGER PRIMARY KEY,
    reporter TEXT NOT NULL REFERENCES users(uuid),
    kind TEXT NOT NULL CHECK (kind IN ('post', 'user')),
    target TEXT NOT NULL,          -- the post id or the user's uuid
    target_uuid TEXT NOT NULL,     -- the reported user, or the post's author
    target_label TEXT NOT NULL,    -- the post's title or the user's name then (a post may be deleted since)
    reason TEXT NOT NULL CHECK (reason IN ('spam', 'inappropriate', 'stolen', 'broken', 'other')),
    details TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created INTEGER NOT NULL,      -- unix ms
    decided INTEGER,
    decided_by TEXT,
    note TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS reports_one_open ON reports(reporter, kind, target) WHERE status = 'open';
CREATE INDEX IF NOT EXISTS reports_status ON reports(status, created);
""",
}
SCHEMA_VERSION = max(MIGRATIONS)

POST_SELECT = """
SELECT p.*, u.name AS author_name,
    (SELECT COUNT(*) FROM votes v WHERE v.post_id = p.id AND v.value = 1) AS ups,
    (SELECT COUNT(*) FROM votes v WHERE v.post_id = p.id AND v.value = -1) AS downs,
    (SELECT group_concat(t.tag, ',') FROM post_tags t WHERE t.post_id = p.id) AS tags,
    (SELECT group_concat(b.badge, ',') FROM badges b WHERE b.uuid = p.uuid) AS author_badges
FROM posts p JOIN users u ON u.uuid = p.uuid
"""
FILE_SELECT = """
SELECT f.*, a.name AS source_author_now
FROM files f LEFT JOIN users a ON a.uuid = f.source_author_uuid
"""
FILE_COLUMNS = (
    "uuid", "path", "size", "sha256", "modified", "has_thumb",
    "source_post", "source_title", "source_author_uuid", "source_author_name",
)
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


def read_schematic(disk: Path, filename: str, max_volume: int) -> convert.Schematic:
    """Parse disk as the format filename's extension names. ValueError if it can't be read."""
    fmt = convert.format_of(filename)
    if fmt is None:
        raise convert.ConversionError("not a schematic file name")
    _, root = nbt.load(disk)
    return convert.read(root, fmt, filename[: -len(fmt) - 1], max_volume)


def modded_error(schem: convert.Schematic) -> ApiError | None:
    """400 code "modded" if schem uses ids outside the minecraft namespace (the cloud is for vanilla builds;
    the mod keeps modded files local), else None."""
    mods = sorted(convert.namespaces(schem) - {"minecraft"})
    if not mods:
        return None
    shown = ", ".join(mods[:5]) + (f" and {len(mods) - 5} more" if len(mods) > 5 else "")
    return ApiError(400, f"this schematic uses blocks or entities from mods ({shown}); only vanilla Minecraft "
                         "schematics can be uploaded", code="modded", namespaces=mods)


def slopmatic_name(filename: str) -> str:
    """<stem>.slopmatic for a schematic file name."""
    fmt = convert.format_of(filename)
    return (filename[: -len(fmt) - 1] if fmt else filename) + ".slopmatic"


def write_slopmatic(schem: convert.Schematic, dest: Path) -> tuple[int, str]:
    """Write schem to dest as .slopmatic: (size, sha256)."""
    with dest.open("wb") as f:
        nbt.dump(convert.write(schem, "slopmatic"), f)
        f.flush()
        os.fsync(f.fileno())
    return dest.stat().st_size, _hash_file(dest)


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
        self._seen: dict[str, int] = {}  # uuid -> unix s last written to users.last_seen
        self.db = sqlite3.connect(self.root / "cloud.db", check_same_thread=False, isolation_level=None)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.create_function("slop_hot", 3, _hot, deterministic=True)
        self.db.create_function("slop_fold", 1, _fold, deterministic=True)
        self.posts_root = self.root / "posts"
        self.posts_root.mkdir(exist_ok=True)
        self.releases_dir = self.root / RELEASES_DIR
        self.releases_dir.mkdir(exist_ok=True)
        self.migrate()
        self.prune()
        self.reconcile()
        self.reconcile_posts()
        self.reconcile_releases()

    def migrate(self) -> None:
        """Create the 0.3.0 tables if missing, then apply MIGRATIONS past PRAGMA user_version, in place."""
        self.db.executescript(SCHEMA)
        (version,) = self.db.execute("PRAGMA user_version").fetchone()
        for target in sorted(v for v in MIGRATIONS if v > version):
            log.info("migrating the database to schema version %d", target)
            if target == 3:
                # Data before schema: each converted post is committed on its own, so an interrupted
                # run simply converts the rest next time (version 3 is only recorded after this).
                self.convert_post_snapshots()
            # read before the transaction: a seed file that is set but unreadable stops the start (nothing changes)
            seed = self.read_whitelist_seed() if target == 5 else []
            try:
                self.db.executescript(f"BEGIN IMMEDIATE;\n{MIGRATIONS[target]}")
                if seed:
                    t = int(time.time() * 1000)
                    self.db.executemany(
                        "INSERT OR IGNORE INTO whitelist(uuid, name, source, added) VALUES(?,?,'seed',?)",
                        [(uuid, name, t) for uuid, name in seed],
                    )
                    log.info("seeded the whitelist with %d players from %s", len(seed), self.settings.whitelist_seed)
                # single statements: executescript would commit the open transaction first
                self.db.execute(f"PRAGMA user_version = {target}")
                self.db.execute("COMMIT")
            except BaseException:
                if self.db.in_transaction:
                    self.db.execute("ROLLBACK")
                raise

    def read_whitelist_seed(self) -> list[tuple[str, str]]:
        """SLOPMATICA_WHITELIST_SEED's players for migration 5, or [] if it isn't set.
        Set but missing or malformed is an error, so a typo doesn't silently leave the whitelist empty."""
        path = self.settings.whitelist_seed
        if path is None:
            return []
        try:
            return parse_whitelist(json.loads(Path(path).read_text(encoding="utf-8")))
        except (OSError, ValueError) as e:
            raise RuntimeError(f"cannot read the whitelist seed {path}: {e}") from e
        except ApiError as e:
            raise RuntimeError(f"cannot read the whitelist seed {path}: {e.payload['error']}") from e

    def convert_post_snapshots(self) -> None:
        """0.5.0: board posts are stored as .slopmatic. Convert older snapshots in place; one that
        cannot be converted stays as it is (logged)."""
        rows = self.db.execute("SELECT id, filename FROM posts WHERE format != 'slopmatic' ORDER BY id").fetchall()
        for r in rows:
            post_id, filename = r["id"], r["filename"]
            pdir = self.posts_root / post_id
            if not POST_ID_RE.match(post_id) or not (pdir / filename).is_file():
                continue  # reconcile_posts drops it
            fd, tmp_name = tempfile.mkstemp(dir=self.tmp, suffix=".slopmatic")
            os.close(fd)
            tmp = Path(tmp_name)
            try:
                schem = read_schematic(self._under(pdir, filename), filename, self.settings.max_export_volume)
                size, digest = write_slopmatic(schem, tmp)
                new_name = slopmatic_name(filename)
                _, dims = schem.bounds()
                blocks = schem.block_count()
                with self.lock:
                    os.replace(tmp, self._under(pdir, new_name))
                    self.db.execute(
                        """UPDATE posts SET filename=?, format='slopmatic', size=?, sha256=?,
                           size_x=?, size_y=?, size_z=?, block_count=? WHERE id=?""",
                        (new_name, size, digest, *dims, blocks, post_id),
                    )
                    if new_name != filename:
                        self._under(pdir, filename).unlink(missing_ok=True)
                log.info("converted board post %s from %s to %s (%d bytes)", post_id, filename, new_name, size)
            except Exception as e:
                log.warning("could not convert board post %s (%s) to .slopmatic, left as it is: %s", post_id, filename, e)
            finally:
                tmp.unlink(missing_ok=True)

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
                            # new or changed on disk: a plain file of the user's (no board source)
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
            rows = {r["id"]: r for r in self.db.execute("SELECT id, filename, size, sha256, has_thumb FROM posts")}
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
                digest = r["sha256"]
                if size != r["size"]:
                    digest = _hash_file(snap)
                    self.db.execute("UPDATE posts SET size=?, sha256=? WHERE id=?", (size, digest, post_id))
                # anything else in the folder is left over from an interrupted refresh or conversion,
                # or a 3D view cache of an older snapshot
                cache = f"{MODEL_CACHE_PREFIX}{digest[:16]}."
                for f in pdir.iterdir():
                    if f.name not in (r["filename"], POST_THUMB) and not f.name.startswith(cache) and f.is_file():
                        log.info("removing stale file from board post %s: %s", post_id, f.name)
                        f.unlink(missing_ok=True)
                has_thumb = (pdir / POST_THUMB).is_file()
                if has_thumb != bool(r["has_thumb"]):
                    self.db.execute("UPDATE posts SET has_thumb=? WHERE id=?", (int(has_thumb), post_id))

    def reconcile_releases(self) -> None:
        """Remove partial jars an interrupted publish left behind (older than an hour, so a publish_release.py
        running right now keeps its own), and log releases whose jar is missing (their downloads are 404)."""
        cutoff = time.time() - 3600
        for p in self.releases_dir.glob(".*.part"):
            if p.stat().st_mtime < cutoff:
                p.unlink(missing_ok=True)
        with self.lock:
            names = [r["filename"] for r in self.db.execute("SELECT filename FROM releases")]
        for name in names:
            if not (self.releases_dir / name).is_file():
                log.warning("release %s is in the database but its jar is missing", name)

    # ---- users and tokens

    def upsert_user(self, uuid: str, name: str, admin_by_name: bool) -> User:
        with self.lock:
            t = now_s()
            self.db.execute(
                """INSERT INTO users(uuid,name,is_admin,created,last_login,last_seen) VALUES(?,?,?,?,?,?)
                   ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_login=excluded.last_login,
                   last_seen=excluded.last_seen, is_admin=MAX(users.is_admin, excluded.is_admin)""",
                (uuid, name, int(admin_by_name), t, t, t),
            )
            self._seen[uuid] = t
            return self.get_user(uuid)  # type: ignore[return-value]

    def get_user(self, uuid: str) -> User | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM users WHERE uuid=?", (uuid,)).fetchone()
        return User(r["uuid"], r["name"], bool(r["is_admin"]), r["quota_bytes"]) if r else None

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
            if r is None:
                return None
            self.touch_seen(r["uuid"])
        return self.get_user(r["uuid"])

    def touch_seen(self, uuid: str) -> None:
        """Record that the account is active (users.last_seen), at most every LAST_SEEN_EVERY seconds."""
        t = now_s()
        with self.lock:
            if t - self._seen.get(uuid, 0) >= LAST_SEEN_EVERY:
                self._seen[uuid] = t
                self.db.execute("UPDATE users SET last_seen=? WHERE uuid=?", (t, uuid))

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
            t = now_s()
            self.db.execute("UPDATE users SET last_login=?, last_seen=? WHERE uuid=?", (t, t, uuid))
            self._seen[uuid] = t

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
            rows = self.db.execute(FILE_SELECT + " WHERE f.uuid=? ORDER BY f.path", (uuid,)).fetchall()
        return [FileRow.from_row(r) for r in rows]

    def get_file(self, uuid: str, path: str) -> FileRow | None:
        with self.lock:
            r = self.db.execute(FILE_SELECT + " WHERE f.uuid=? AND f.path=?", (uuid, path)).fetchone()
        return FileRow.from_row(r) if r else None

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
        src = row.source
        with self.lock:
            self.db.execute(
                f"INSERT OR REPLACE INTO files({','.join(FILE_COLUMNS)}) VALUES({','.join('?' * len(FILE_COLUMNS))})",
                (uuid, row.path, row.size, row.sha256, row.modified, int(row.has_thumb),
                 src.post_id if src else None, src.title if src else None,
                 src.author_uuid if src else None, src.author_name if src else None),
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

    def insert_post(self, values: dict[str, Any], allowed: list[tuple[str, str]], tags: list[str] = ()) -> None:
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.transaction():
            self.db.execute(f"INSERT INTO posts({cols}) VALUES({marks})", values)
            self._set_allowed(values["id"], allowed)
            self._set_tags(values["id"], tags)

    def update_post(
        self, post_id: str, values: dict[str, Any], allowed: list[tuple[str, str]] | None = None,
        tags: list[str] | None = None,
    ) -> None:
        with self.transaction():
            if values:
                sets = ", ".join(f"{k}=:{k}" for k in values)
                self.db.execute(f"UPDATE posts SET {sets} WHERE id=:id", {**values, "id": post_id})
            if allowed is not None:
                self._set_allowed(post_id, allowed)
            if tags is not None:
                self._set_tags(post_id, tags)

    def _set_tags(self, post_id: str, tags: list[str]) -> None:
        self.db.execute("DELETE FROM post_tags WHERE post_id=?", (post_id,))
        self.db.executemany("INSERT OR IGNORE INTO post_tags(post_id, tag) VALUES(?,?)", [(post_id, t) for t in tags])

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
        self, tab: str, viewer: str | None, sort: str, query: str, page: int, page_size: int,
        tags: list[str] = (), size: str | None = None,
    ) -> tuple[list[PostRow], int]:
        """One page of a board tab and the total number of matching posts.
        'public' lists public posts only; 'shared' private posts whose access list has viewer;
        'mine' every post by viewer. Posts an admin hid are only listed in 'mine'. Every tag in tags must be on the post; size is a size class."""
        params: dict[str, Any] = {"viewer": viewer, "now": int(time.time() * 1000)}
        if tab == "public":
            where = ["p.visibility = 'public'", "p.hidden IS NULL"]
        elif tab == "shared":
            where = [
                "p.visibility = 'private'",
                "p.hidden IS NULL",
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
        for i, tag in enumerate(tags):
            params[f"tag{i}"] = tag
            where.append(f"EXISTS (SELECT 1 FROM post_tags g WHERE g.post_id = p.id AND g.tag = :tag{i})")
        if size is not None:
            params["size"] = size
            where.append("p.size_class = :size")
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

    # ---- releases

    def list_releases(self, include_hidden: bool = False) -> list[ReleaseRow]:
        """Releases, newest version first (semver), then newest Minecraft version."""
        with self.lock:
            rows = self.db.execute(
                "SELECT * FROM releases" + ("" if include_hidden else " WHERE hidden = 0")
            ).fetchall()
        out = [ReleaseRow.from_row(r) for r in rows]
        out.sort(key=lambda r: (semver_key(r.version), mc_version_key(r.mc), r.loader), reverse=True)
        return out

    def latest_release(self, mc: str, loader: str) -> ReleaseRow | None:
        """The highest version (by semver, not upload time) published for mc and loader and not hidden."""
        with self.lock:
            rows = self.db.execute(
                "SELECT * FROM releases WHERE hidden = 0 AND mc_version = ? AND loader = ?", (mc, loader)
            ).fetchall()
        best = max(rows, key=lambda r: semver_key(r["version"]), default=None)
        return ReleaseRow.from_row(best) if best else None

    def get_release(self, filename: str) -> ReleaseRow | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM releases WHERE filename=?", (filename,)).fetchone()
        return ReleaseRow.from_row(r) if r else None

    def update_release(self, filename: str, values: dict[str, Any]) -> None:
        with self.lock:
            sets = ", ".join(f"{k}=:{k}" for k in values)
            self.db.execute(f"UPDATE releases SET {sets} WHERE filename=:filename", {**values, "filename": filename})

    def count_download(self, filename: str) -> None:
        with self.lock:
            self.db.execute("UPDATE releases SET downloads = downloads + 1 WHERE filename=?", (filename,))

    # ---- feedback

    def add_feedback(self, values: dict[str, Any]) -> int:
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.lock:
            return int(self.db.execute(f"INSERT INTO feedback({cols}) VALUES({marks})", values).lastrowid)

    def list_feedback(self, status: str) -> list[sqlite3.Row]:
        """'open', 'resolved' or 'all'; open ones first, newest first."""
        where = {"open": " WHERE resolved IS NULL", "resolved": " WHERE resolved IS NOT NULL", "all": ""}[status]
        with self.lock:
            return self.db.execute(
                "SELECT * FROM feedback" + where + " ORDER BY resolved IS NOT NULL, created DESC, id DESC"
            ).fetchall()

    def feedback_counts(self) -> tuple[int, int]:
        """(open, resolved)."""
        with self.lock:
            n_open, n_resolved = self.db.execute(
                "SELECT COUNT(*) - COUNT(resolved), COUNT(resolved) FROM feedback"
            ).fetchone()
        return int(n_open), int(n_resolved)

    def set_feedback_resolved(self, feedback_id: int, resolved: bool) -> sqlite3.Row | None:
        with self.lock:
            if resolved:  # resolving twice keeps the first time
                self.db.execute("UPDATE feedback SET resolved=COALESCE(resolved, ?) WHERE id=?",
                                (int(time.time() * 1000), feedback_id))
            else:
                self.db.execute("UPDATE feedback SET resolved=NULL WHERE id=?", (feedback_id,))
            return self.db.execute("SELECT * FROM feedback WHERE id=?", (feedback_id,)).fetchone()

    # ---- whitelist

    def on_whitelist(self, uuid: str) -> bool:
        with self.lock:
            return self.db.execute("SELECT 1 FROM whitelist WHERE uuid=?", (uuid,)).fetchone() is not None

    def whitelist_entries(self) -> list[sqlite3.Row]:
        """Every entry with the account's current name (if it has logged in), by name."""
        with self.lock:
            return self.db.execute(
                """SELECT w.*, u.name AS account_name FROM whitelist w LEFT JOIN users u ON u.uuid = w.uuid
                   ORDER BY w.name COLLATE NOCASE, w.uuid"""
            ).fetchall()

    def whitelist_entry(self, uuid: str) -> sqlite3.Row | None:
        with self.lock:
            return self.db.execute("SELECT * FROM whitelist WHERE uuid=?", (uuid,)).fetchone()

    def whitelist_add(self, uuid: str, name: str, source: str, by: str | None) -> bool:
        """Add a player (an existing entry keeps its source and name). True if they were not on it."""
        with self.lock:
            cur = self.db.execute(
                "INSERT OR IGNORE INTO whitelist(uuid, name, source, added, added_by) VALUES(?,?,?,?,?)",
                (uuid, name, source, int(time.time() * 1000), by),
            )
            return cur.rowcount == 1

    def whitelist_import(self, entries: list[tuple[str, str]], by: str) -> dict[str, Any]:
        """Merge an uploaded whitelist.json: add new players (source 'upload'), take over changed names,
        remove nobody. Returns what was added and renamed, and the entries the file doesn't have."""
        added: list[tuple[str, str]] = []
        renamed: list[tuple[str, str, str]] = []
        t = int(time.time() * 1000)
        with self.transaction():
            have = {r["uuid"]: r for r in self.db.execute("SELECT * FROM whitelist")}
            for uuid, name in entries:
                row = have.get(uuid)
                if row is None:
                    self.db.execute(
                        "INSERT INTO whitelist(uuid, name, source, added, added_by) VALUES(?,?,'upload',?,?)",
                        (uuid, name, t, by),
                    )
                    added.append((uuid, name))
                elif row["name"] != name:
                    self.db.execute("UPDATE whitelist SET name=? WHERE uuid=?", (name, uuid))
                    renamed.append((uuid, row["name"], name))
        in_file = {uuid for uuid, _ in entries}
        missing = sorted((r for u, r in have.items() if u not in in_file), key=lambda r: (r["name"].lower(), r["uuid"]))
        return {"added": added, "renamed": renamed, "unchanged": len(entries) - len(added) - len(renamed), "missing": missing}

    def whitelist_remove(self, uuids: list[str]) -> int:
        with self.lock:
            return self.db.executemany("DELETE FROM whitelist WHERE uuid=?", [(u,) for u in uuids]).rowcount

    def whitelist_count(self) -> int:
        with self.lock:
            return int(self.db.execute("SELECT COUNT(*) FROM whitelist").fetchone()[0])

    # ---- applications, storage requests, reports: rows with a status and a decision

    def insert_row(self, table: str, values: dict[str, Any]) -> int:
        """Insert into a table of ours (never user input). sqlite3.IntegrityError if a one-open index refuses it."""
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.lock:
            return int(self.db.execute(f"INSERT INTO {table}({cols}) VALUES({marks})", values).lastrowid)

    def get_row(self, table: str, row_id: int) -> sqlite3.Row | None:
        with self.lock:
            return self.db.execute(
                f"SELECT t.*, u.name AS user_name FROM {table} t LEFT JOIN users u ON u.uuid = t.{_OWNER[table]}"
                " WHERE t.id=?", (row_id,)
            ).fetchone()

    def list_rows(self, table: str, status: str, where: str = "", params: tuple = ()) -> list[sqlite3.Row]:
        """Rows with status ('all' for every one), open ones first, newest first, with the owner's current name."""
        clauses = [c for c in (where, "" if status == "all" else "t.status = ?") if c]
        args = params + (() if status == "all" else (status,))
        sql = (f"SELECT t.*, u.name AS user_name FROM {table} t LEFT JOIN users u ON u.uuid = t.{_OWNER[table]}"
               + (" WHERE " + " AND ".join(clauses) if clauses else "")
               + f" ORDER BY t.status != '{_OPEN[table]}', t.created DESC, t.id DESC")
        with self.lock:
            return self.db.execute(sql, args).fetchall()

    def status_counts(self, table: str) -> dict[str, int]:
        with self.lock:
            rows = self.db.execute(f"SELECT status, COUNT(*) AS n FROM {table} GROUP BY status").fetchall()
        return {r["status"]: int(r["n"]) for r in rows}

    def decide(self, table: str, row_id: int, status: str, by: str, note: str | None, **extra: Any) -> bool:
        """Close an open row. False if it is not open (any more)."""
        sets = "".join(f", {k}=:{k}" for k in extra)
        with self.lock:
            cur = self.db.execute(
                f"UPDATE {table} SET status=:status, decided=:t, decided_by=:by, note=:note{sets}"
                f" WHERE id=:id AND status='{_OPEN[table]}'",
                {"status": status, "t": int(time.time() * 1000), "by": by, "note": note, "id": row_id, **extra},
            )
            return cur.rowcount == 1

    def recent_rows(self, table: str, uuid: str, window: int) -> int:
        """Rows the account created in the last window seconds."""
        with self.lock:
            (n,) = self.db.execute(
                f"SELECT COUNT(*) FROM {table} WHERE {_OWNER[table]}=? AND created >= ?",
                (uuid, (now_s() - window) * 1000),
            ).fetchone()
        return int(n)

    def latest_row(self, table: str, uuid: str) -> sqlite3.Row | None:
        with self.lock:
            return self.db.execute(
                f"SELECT t.*, u.name AS user_name FROM {table} t LEFT JOIN users u ON u.uuid = t.{_OWNER[table]}"
                f" WHERE t.{_OWNER[table]}=? ORDER BY t.created DESC, t.id DESC LIMIT 1", (uuid,)
            ).fetchone()

    # ---- users, quotas and badges

    def set_quota(self, uuid: str, quota: int | None) -> None:
        with self.lock:
            self.db.execute("UPDATE users SET quota_bytes=? WHERE uuid=?", (quota, uuid))

    def badges_of(self, uuid: str) -> list[str]:
        with self.lock:
            rows = self.db.execute("SELECT badge FROM badges WHERE uuid=?", (uuid,)).fetchall()
        return badge_list(",".join(r["badge"] for r in rows))

    def set_badge(self, uuid: str, badge: str, on: bool, by: str) -> None:
        with self.lock:
            if on:
                self.db.execute("INSERT OR IGNORE INTO badges(uuid, badge, granted, granted_by) VALUES(?,?,?,?)",
                                (uuid, badge, int(time.time() * 1000), by))
            else:
                self.db.execute("DELETE FROM badges WHERE uuid=? AND badge=?", (uuid, badge))

    def badge_holders(self) -> list[sqlite3.Row]:
        with self.lock:
            return self.db.execute(
                """SELECT u.uuid, u.name, group_concat(b.badge, ',') AS badges FROM badges b JOIN users u ON u.uuid = b.uuid
                   GROUP BY u.uuid ORDER BY u.name COLLATE NOCASE"""
            ).fetchall()

    def find_user(self, text: str) -> User | None:
        """An account by uuid (dashed or not) or by current name (any case; the most recently active one)."""
        uuid = parse_uuid(text)
        if uuid:
            return self.get_user(uuid)
        with self.lock:
            r = self.db.execute(
                "SELECT uuid FROM users WHERE name = ? COLLATE NOCASE ORDER BY last_login DESC LIMIT 1", (text.strip(),)
            ).fetchone()
        return self.get_user(r["uuid"]) if r else None

    def known_names(self, candidates: set[str]) -> set[str]:
        """Which of candidates (lowercase) are the Minecraft names of accounts or whitelisted players."""
        names = list(candidates)[:200]
        marks = ",".join("?" * len(names))
        with self.lock:
            rows = self.db.execute(
                f"SELECT lower(name) AS n FROM users WHERE lower(name) IN ({marks})"
                f" UNION SELECT lower(name) FROM whitelist WHERE lower(name) IN ({marks})", names + names,
            ).fetchall()
        return {r["n"] for r in rows}

    def list_users(self, query: str, page: int, page_size: int) -> tuple[list[sqlite3.Row], int]:
        """Accounts matching query (part of the name or uuid), most recently seen first, with usage,
        badges and whitelist entry."""
        where, params = "", {}
        if query:
            where = " WHERE instr(lower(u.name), :q) > 0 OR instr(u.uuid, :q) > 0 OR instr(replace(u.uuid, '-', ''), :q) > 0"
            params["q"] = query.lower()
        with self.lock:
            (total,) = self.db.execute("SELECT COUNT(*) FROM users u" + where, params).fetchone()
            rows = self.db.execute(
                """SELECT u.*, w.source AS wl_source,
                       (SELECT COALESCE(SUM(size),0) FROM files f WHERE f.uuid = u.uuid)
                       + (SELECT COALESCE(SUM(size),0) FROM posts p WHERE p.uuid = u.uuid) AS used,
                       (SELECT COUNT(*) FROM posts p WHERE p.uuid = u.uuid) AS posts,
                       (SELECT group_concat(b.badge, ',') FROM badges b WHERE b.uuid = u.uuid) AS badges
                   FROM users u LEFT JOIN whitelist w ON w.uuid = u.uuid""" + where
                + " ORDER BY COALESCE(u.last_seen, u.last_login) DESC, u.name COLLATE NOCASE LIMIT :limit OFFSET :offset",
                {**params, "limit": page_size, "offset": (page - 1) * page_size},
            ).fetchall()
        return rows, int(total)


# The tables with a status: who owns a row, and the status of an open one.
_OWNER = {"applications": "uuid", "storage_requests": "uuid", "reports": "reporter"}
_OPEN = {"applications": "pending", "storage_requests": "pending", "reports": "open"}


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


# --------------------------------------------------------------------------- releases


def semver_key(version: str) -> tuple:
    """Sort key for semver precedence: 0.10.0 > 0.9.2, 1.0.0 > 1.0.0-rc.1 > 1.0.0-beta.2 > 1.0.0-beta.
    Pre-release identifiers compare numerically when numeric, and numeric ones sort first."""
    m = VERSION_RE.match(version)
    if m is None:
        return (-1,)
    core = tuple(int(g) for g in m.group(1, 2, 3))
    if m.group(4) is None:
        return (*core, 1, ())
    ids = tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in m.group(4).split("."))
    return (*core, 0, ids)


def mc_version_key(mc: str) -> tuple:
    """Sort key for Minecraft versions: by their numbers (26.3 > 26.2 > 1.21.10 > 1.21.9), then the text."""
    return (tuple(int(n) for n in re.findall(r"\d+", mc)), mc)


@dataclass
class ReleaseRow:
    id: int
    version: str
    mc: str
    loader: str
    filename: str
    sha256: str
    size: int
    uploaded: int  # unix ms
    changelog: str
    hidden: bool
    downloads: int

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "ReleaseRow":
        return cls(
            r["id"], r["version"], r["mc_version"], r["loader"], r["filename"], r["sha256"], r["size"],
            r["uploaded"], r["changelog"], bool(r["hidden"]), r["downloads"],
        )


def clean_release_meta(version: Any, mc: Any, loader: Any, filename: Any, changelog: Any) -> tuple[str, str, str, str, str]:
    """Validate a release's metadata (for the API and publish_release.py); ApiError(400) if anything is off."""
    def text(value: Any, what: str) -> str:
        if not isinstance(value, str):
            raise ApiError(400, f"{what} is required")
        return value.strip()

    version, mc, loader, filename = text(version, "version"), text(mc, "mc"), text(loader, "loader"), text(filename, "filename")
    if len(version) > MAX_VERSION_LEN or not VERSION_RE.match(version):
        raise ApiError(400, "version must look like 1.2.3 or 1.2.3-beta.1")
    if not MC_VERSION_RE.match(mc):
        raise ApiError(400, "mc must be a Minecraft version such as 26.3")
    if not LOADER_RE.match(loader):
        raise ApiError(400, "loader must be a lowercase name such as fabric")
    if not RELEASE_FILENAME_RE.match(filename) or ".." in filename:
        raise ApiError(400, "filename must be a plain .jar name (letters, digits and ._+- only)")
    if changelog is None:
        changelog = ""
    if not isinstance(changelog, str):
        raise ApiError(400, "changelog must be text")
    changelog = changelog.replace("\r\n", "\n").replace("\r", "\n").strip()
    if len(changelog) > MAX_CHANGELOG_LEN:
        raise ApiError(400, f"changelog must be at most {MAX_CHANGELOG_LEN} characters")
    if DESCRIPTION_BAD_CHARS.search(changelog):
        raise ApiError(400, "changelog may not contain control characters")
    return version, mc, loader, filename, changelog


def release_path(releases_dir: Path, filename: str) -> Path:
    """releases/<filename>, refusing any name that is not a plain jar name inside the folder."""
    if not RELEASE_FILENAME_RE.match(filename) or ".." in filename:
        raise ApiError(404, "no such release")
    p = releases_dir / filename
    if p.resolve().parent != releases_dir.resolve():  # defence in depth (symlinks)
        raise ApiError(404, "no such release")
    return p


@contextmanager
def immediate(db: sqlite3.Connection) -> Any:
    """A write transaction on an autocommit connection, taken up front so other processes wait."""
    db.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        db.execute("ROLLBACK")
        raise
    db.execute("COMMIT")


def add_release(
    db: sqlite3.Connection, releases_dir: Path, jar: Path, version: str, mc: str, loader: str, filename: str,
    changelog: str = "", hidden: bool = False,
) -> ReleaseRow:
    """Copy jar into releases_dir as filename and record it. Shared by POST /api/releases (with the store's
    lock held) and publish_release.py (its own connection: SQLite serialises the two processes).
    400 for bad metadata or a file that isn't a jar, 409 if the version or file name is taken."""
    version, mc, loader, filename, changelog = clean_release_meta(version, mc, loader, filename, changelog)
    dest = release_path(releases_dir, filename)
    part = releases_dir / f".{filename}.{secrets.token_hex(4)}.part"
    try:
        h = hashlib.sha256()
        size = 0
        with jar.open("rb") as src, part.open("wb") as out:
            if src.read(len(JAR_MAGIC)) != JAR_MAGIC:
                raise ApiError(400, "not a jar (zip) file")
            src.seek(0)
            for chunk in iter(lambda: src.read(1 << 20), b""):
                h.update(chunk)
                size += len(chunk)
                out.write(chunk)
            out.flush()
            os.fsync(out.fileno())
        with immediate(db):
            if db.execute("SELECT 1 FROM releases WHERE version=? AND mc_version=? AND loader=?",
                          (version, mc, loader)).fetchone():
                raise ApiError(409, f"{version} for Minecraft {mc} ({loader}) is already published")
            if db.execute("SELECT 1 FROM releases WHERE filename=?", (filename,)).fetchone():
                raise ApiError(409, f"a release named {filename} already exists; pick another file name")
            os.replace(part, dest)  # a stray jar of that name without a row is simply replaced
            try:
                cur = db.execute(
                    """INSERT INTO releases(version, mc_version, loader, filename, sha256, size, uploaded, changelog, hidden)
                       VALUES(?,?,?,?,?,?,?,?,?)""",
                    (version, mc, loader, filename, h.hexdigest(), size, int(time.time() * 1000), changelog, int(hidden)),
                )
            except BaseException:
                dest.unlink(missing_ok=True)
                raise
            row = db.execute("SELECT * FROM releases WHERE id=?", (cur.lastrowid,)).fetchone()
    finally:
        part.unlink(missing_ok=True)
    return ReleaseRow.from_row(row)


_MD_INLINE = re.compile(r"\*\*(.+?)\*\*|`([^`]+)`|\[([^\]\n]+)\]\((https?://[^)\s]+)\)")
_MD_HEADING = re.compile(r"^(#{1,3})\s+(.*)$")
_MD_BULLET = re.compile(r"^\s*[-*+]\s+(.*)$")
_MD_NUMBERED = re.compile(r"^\s*\d+[.)]\s+(.*)$")


def _md_inline(text: str) -> str:
    out: list[str] = []
    pos = 0
    for m in _MD_INLINE.finditer(text):
        out.append(str(markupsafe.escape(text[pos:m.start()])))
        bold, code, label, href = m.groups()
        if bold is not None:
            out.append(f"<strong>{markupsafe.escape(bold)}</strong>")
        elif code is not None:
            out.append(f"<code>{markupsafe.escape(code)}</code>")
        else:
            out.append(f'<a href="{markupsafe.escape(href)}" rel="noopener noreferrer">{markupsafe.escape(label)}</a>')
        pos = m.end()
    out.append(str(markupsafe.escape(text[pos:])))
    return "".join(out)


def render_changelog(text: str) -> markupsafe.Markup:
    """A changelog as HTML. Markdown-ish: # headings, - or 1. lists, **bold**, `code`, [links](https://...),
    blank lines between paragraphs; single line breaks are kept. Every piece of text is escaped."""
    blocks: list[str] = []
    para: list[str] = []
    items: list[str] = []
    list_tag = ""

    def flush() -> None:
        nonlocal list_tag
        if para:
            blocks.append("<p>" + "<br>".join(_md_inline(line) for line in para) + "</p>")
            para.clear()
        if items:
            blocks.append(f"<{list_tag}>" + "".join(f"<li>{_md_inline(i)}</li>" for i in items) + f"</{list_tag}>")
            items.clear()

    for raw in text.split("\n"):
        line = raw.rstrip()
        heading, bullet, numbered = _MD_HEADING.match(line), _MD_BULLET.match(line), _MD_NUMBERED.match(line)
        if not line.strip():
            flush()
        elif heading:
            flush()
            level = len(heading.group(1)) + 2  # under the page's own headings
            blocks.append(f"<h{level}>{_md_inline(heading.group(2))}</h{level}>")
        elif bullet or numbered:
            tag = "ul" if bullet else "ol"
            if para or (items and tag != list_tag):
                flush()
            list_tag = tag
            items.append((bullet or numbered).group(1))  # type: ignore[union-attr]
        else:
            if items:
                flush()
            para.append(line)
    flush()
    return markupsafe.Markup("".join(blocks))


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
    tags: list[str] = Field(default_factory=list)


class PostPatchIn(BaseModel):
    title: str | None = None
    description: str | None = None
    visibility: str | None = None
    allowed: list[str] | None = None
    tags: list[str] | None = None


class VoteIn(BaseModel):
    value: Any = None


class MoveIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    src: str = Field(alias="from")
    dst: str = Field(alias="to")


class FeedbackIn(BaseModel):
    type: Any = None
    message: Any = None
    modVersion: Any = None
    mcVersion: Any = None
    contact: Any = None


class ResolveIn(BaseModel):
    resolved: Any = None


class ReleasePatchIn(BaseModel):
    hidden: Any = None
    changelog: Any = None


class ApplicationIn(BaseModel):
    reason: Any = None
    community: Any = None
    contact: Any = None


class StorageRequestIn(BaseModel):
    bytes: Any = None
    reason: Any = None


class ReportIn(BaseModel):
    kind: Any = None  # post | user
    target: Any = None  # the post id, or the user's uuid or name
    reason: Any = None
    details: Any = None


class DecisionIn(BaseModel):
    note: Any = None
    quotaBytes: Any = None  # storage requests: the quota to grant instead of the one asked for


class PlayerIn(BaseModel):
    player: Any = None  # a Minecraft name or uuid
    name: Any = None  # the name, when player is a uuid the server doesn't know


class UuidsIn(BaseModel):
    uuids: Any = None


class UserPatchIn(BaseModel):
    quotaBytes: Any = None


class HideIn(BaseModel):
    hidden: Any = None
    reason: Any = None


def parse_multipart(body: bytes, content_type: str) -> tuple[dict[str, str], dict[str, tuple[str, bytes]]]:
    """multipart/form-data -> ({field: text}, {field: (file name, bytes)}). Small and in memory: it is only
    used for release uploads by admins, and avoids the python-multipart dependency. ApiError(400) if malformed."""
    m = re.search(r'boundary=(?:"([^"]+)"|([^;\s]+))', content_type)
    if not content_type.lower().startswith("multipart/form-data") or m is None:
        raise ApiError(400, "expected multipart/form-data")
    delim = b"\r\n--" + (m.group(1) or m.group(2)).encode("latin-1")
    pieces = (b"\r\n" + body).split(delim)
    if len(pieces) < 3 or not pieces[-1].startswith(b"--"):
        raise ApiError(400, "malformed multipart body")
    fields: dict[str, str] = {}
    files: dict[str, tuple[str, bytes]] = {}
    for piece in pieces[1:-1]:
        head, sep, content = piece.partition(b"\r\n\r\n")
        if not sep:
            raise ApiError(400, "malformed multipart body")
        disposition = ""
        for line in head.decode("utf-8", "replace").split("\r\n"):
            key, _, value = line.partition(":")
            if key.strip().lower() == "content-disposition":
                disposition = value
        name = re.search(r'\bname="([^"]*)"', disposition)
        if name is None:
            continue
        filename = re.search(r'\bfilename="([^"]*)"', disposition)
        if filename is not None:
            files[name.group(1)] = (filename.group(1), content)
        else:
            try:
                fields[name.group(1)] = content.decode("utf-8")
            except UnicodeDecodeError:
                raise ApiError(400, f"form field {name.group(1)} is not UTF-8")
    return fields, files


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


class CompressedStaticFiles(StaticFiles):
    """StaticFiles that sends scripts, styles and JSON gzip-compressed to clients that accept it
    (the vendored three.js is 2 MB, about 420 KB compressed). Compressed once per file version, in memory."""

    COMPRESS = (".js", ".css", ".json", ".svg")

    def __init__(self, *args: Any, **kw: Any) -> None:
        super().__init__(*args, **kw)
        self._gz: dict[str, tuple[int, bytes]] = {}
        self._gz_lock = threading.Lock()

    def _compressed(self, path: str) -> bytes:
        mtime = os.stat(path).st_mtime_ns
        with self._gz_lock:
            hit = self._gz.get(path)
        if hit is None or hit[0] != mtime:
            hit = (mtime, gzip.compress(Path(path).read_bytes(), compresslevel=6, mtime=0))
            with self._gz_lock:
                self._gz[path] = hit
        return hit[1]

    async def get_response(self, path: str, scope: dict) -> Response:
        resp = await super().get_response(path, scope)
        if not isinstance(resp, FileResponse) or resp.status_code != 200 or not path.lower().endswith(self.COMPRESS):
            return resp
        resp.headers["Vary"] = "Accept-Encoding"
        if "gzip" not in Headers(scope=scope).get("accept-encoding", "").lower():
            return resp
        headers = {k: v for k, v in resp.headers.items() if k.lower() not in ("content-length", "content-type")}
        headers["Content-Encoding"] = "gzip"
        return Response(self._compressed(str(resp.path)), headers=headers, media_type=resp.headers.get("content-type"))


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
    templates.filters["changelog"] = render_changelog
    templates.globals["badge_labels"] = BADGES  # for the badge macro, which imported macros call without context
    static_dir = PKG_DIR / "static"
    static_version = str(max(int(p.stat().st_mtime) for p in static_dir.iterdir()))
    try:  # block id -> map colour; null for blocks that are clear on maps (glass, panes, ...)
        block_colors = json.loads((static_dir / "blockcolors.json").read_text())
    except (OSError, ValueError):
        block_colors = {}
    transparent_blocks = frozenset(k for k, v in block_colors.items() if v is None)

    from . import __version__
    app = FastAPI(title="Slopmatica Cloud", version=__version__, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings = settings
    app.state.store = store
    app.add_middleware(RootPathMiddleware, root_path=base)
    app.mount("/static", CompressedStaticFiles(directory=static_dir), name="static")

    # ---- error shapes: always {"error": "..."} for the API

    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(exc.payload, status_code=exc.status)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(request: Request, exc: StarletteHTTPException) -> Response:
        route = request.scope["path"].removeprefix(base) if base else request.scope["path"]
        if exc.status_code == 404 and request.method in ("GET", "HEAD") and not route.startswith(("/api/", "/static/")):
            # an unknown page (the admin pages answer the same to anyone but admins)
            return await run_in_threadpool(not_found_page, request)
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
        """None (unlimited) for admins, else the quota an admin set for the account, else the default."""
        if user.is_admin:
            return None
        return user.quota_bytes if user.quota_bytes is not None else settings.quota_bytes

    def usage(user: User) -> dict[str, Any]:
        return {"usedBytes": store.used_bytes(user.uuid), "quotaBytes": quota_for(user)}

    # ---- access: the Fruitbowl whitelist

    def is_member(user: User) -> bool:
        """May use the cloud: on the whitelist, an admin, or the gate is off."""
        return not settings.whitelist_enforced or user.is_admin or store.on_whitelist(user.uuid)

    def apply_url() -> str:
        return f"{settings.site_url}/apply"

    def require_member(user: User) -> User:
        if not is_member(user):
            # the mod shows error and offers the apply link
            raise ApiError(
                403, f"Slopmatica Cloud is only open to {SERVER_NAME} members for now. You can apply for access on the website.",
                code="not_whitelisted", apply=apply_url(),
            )
        return user

    def member_auth(request: Request, *, mutating: bool) -> User:
        """authenticate(), then 403 not_whitelisted for players without access."""
        return require_member(authenticate(request, mutating=mutating))

    def account_api(user: User) -> dict[str, Any]:
        """The caller's account, for the mod (/api/auth/verify and /api/me)."""
        out = {"uuid": user.uuid, "name": user.name, **usage(user), "whitelisted": is_member(user),
               "badges": store.badges_of(user.uuid)}
        if not out["whitelisted"]:
            out["apply"] = apply_url()
        return out

    def filtered(user: User, text: str | None, what: str) -> str | None:
        """400 if text (a field the user wrote) has a blocked word. Minecraft names of real accounts in the
        text never count, and admins aren't filtered."""
        if text and not user.is_admin and textfilter.check(text, store.known_names) is not None:
            raise ApiError(400, f"the {what} contains language that isn't allowed here; please rephrase it",
                           code="filtered", field=what)
        return text

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
        return {"token": token, **account_api(user)}

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
        return account_api(user)

    # ---- files API

    @app.get("/api/files")
    def list_files(request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=False)
        return {"files": [f.api() for f in store.list_files(user.uuid)], **usage(user)}

    @app.post("/api/files/move")
    def move_file(request: Request, body: MoveIn) -> dict[str, Any]:
        user = member_auth(request, mutating=True)
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
        user = member_auth(request, mutating=True)
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
            await run_in_threadpool(check_vanilla, tmp, path.rsplit("/", 1)[-1], size)
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
                # a file saved from the board stays "from the board" until its content changes
                source = old.source if old and old.sha256 == digest else None
                store.put_file_row(user.uuid, FileRow(path, size, digest, int(time.time() * 1000), has_thumb, source))
                row = store.get_file(user.uuid, path)
        finally:
            tmp.unlink(missing_ok=True)
        return JSONResponse(row.api(), status_code=200 if old else 201)  # type: ignore[union-attr]

    def check_vanilla(disk: Path, name: str, size: int) -> None:
        """400 code "modded" for an upload that uses mod namespaces. A file the server can't read is stored as
        before (the game is the judge of its own files); big files wait for a conversion slot to be parsed."""
        def read() -> convert.Schematic | None:
            try:
                return read_schematic(disk, name, settings.max_export_volume)
            except ValueError:  # ConversionError, NBTError, TooLarge
                return None

        if size <= SMALL_PARSE_BYTES:
            schem = read()
        else:
            with conversion_slot():
                schem = read()
        modded = modded_error(schem) if schem is not None else None
        if modded is not None:
            raise modded

    @app.get("/api/files/{path:path}")
    def get_file(path: str, request: Request) -> FileResponse:
        user = member_auth(request, mutating=False)
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
        user = member_auth(request, mutating=False)
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
        user = member_auth(request, mutating=True)
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
        user = member_auth(request, mutating=True)
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
        user = member_auth(request, mutating=False)
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
        if post.hidden is not None and not (user and (user.uuid == post.uuid or user.is_admin)):
            return False  # hidden by an admin: the author and admins only, whatever the visibility
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
        """For changes: 404 if invisible to the caller, then 401/403 for auth, CSRF, access and ownership."""
        post, _ = visible_post(request, post_id)
        user = member_auth(request, mutating=True)
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
        file: Path  # temporary .slopmatic in store.tmp
        thumb: Path | None
        filename: str
        fmt: str
        size: int
        sha256: str
        dims: tuple[int, int, int] | None = None
        blocks: int | None = None
        extra: list[Path] = field(default_factory=list)  # other temporary files to remove

        def cleanup(self) -> None:
            for p in (self.file, self.thumb, *self.extra):
                if p is not None:
                    p.unlink(missing_ok=True)

    def copy_hashed(src: Path, dst: Any) -> tuple[int, str]:
        h = hashlib.sha256()
        size = 0
        with src.open("rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
                size += len(chunk)
                dst.write(chunk)
        return size, h.hexdigest()

    @contextmanager
    def conversion_slot() -> Any:
        if not export_slots.acquire(timeout=EXPORT_WAIT):
            raise ApiError(503, "the server is busy converting other files, try again shortly")
        try:
            yield
        finally:
            export_slots.release()

    def take_snapshot(user: User, path: str) -> Snapshot:
        """Copy one of user's files (and its thumbnail) to temporary files, as .slopmatic: a file in
        another format is converted (block states and data version kept). 400 if it can't be read."""
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".snap")
        tmp = Path(tmp_name)
        name = path.rsplit("/", 1)[-1]
        snap = Snapshot(tmp, None, slopmatic_name(name), "slopmatic", 0, "")
        try:
            with os.fdopen(fd, "wb") as f, store.lock:
                row, disk = require_file(user, path)
                snap.size, snap.sha256 = copy_hashed(disk, f)
                thumb = store.thumb_path(user.uuid, path)
                if row.has_thumb and thumb.is_file():
                    snap.thumb = tmp.with_suffix(".png")
                    shutil.copyfile(thumb, snap.thumb)
            src_fmt = convert.format_of(name)
            with conversion_slot():
                try:
                    schem = read_schematic(tmp, name, settings.max_export_volume)
                    modded = modded_error(schem)
                    if modded is not None:
                        raise modded
                    if src_fmt != "slopmatic":
                        snap.extra.append(tmp)
                        snap.file = tmp.with_suffix(".slopmatic")
                        snap.size, snap.sha256 = write_slopmatic(schem, snap.file)
                except ValueError as e:  # ConversionError, NBTError: not a readable schematic
                    what = "read" if src_fmt == "slopmatic" else "convert"
                    raise ApiError(400, f"cannot {what} {name}" + ("" if what == "read" else " to .slopmatic") + f": {e}")
            _, snap.dims = schem.bounds()
            snap.blocks = schem.block_count()
        except BaseException:
            snap.cleanup()
            raise
        return snap

    def snapshot_columns(snap: Snapshot) -> dict[str, Any]:
        dims = snap.dims or (None, None, None)
        return {
            "filename": snap.filename, "format": snap.fmt, "size": snap.size, "sha256": snap.sha256,
            "has_thumb": int(snap.thumb is not None), "size_x": dims[0], "size_y": dims[1], "size_z": dims[2],
            "block_count": snap.blocks, "size_class": size_class(snap.blocks), "updated": int(time.time() * 1000),
        }

    def install_snapshot(pdir: Path, snap: Snapshot, old_filename: str | None = None) -> None:
        """Move a snapshot's temporary files into posts/<id>/. Call with store.lock held."""
        pdir.mkdir(parents=True, exist_ok=True)
        os.replace(snap.file, store._under(pdir, snap.filename))
        if old_filename and old_filename != snap.filename:
            store._under(pdir, old_filename).unlink(missing_ok=True)
        for cached in pdir.glob(MODEL_CACHE_PREFIX + "*"):  # the 3D view of the old snapshot
            cached.unlink(missing_ok=True)
        if snap.thumb is not None:
            os.replace(snap.thumb, pdir / POST_THUMB)
        else:
            (pdir / POST_THUMB).unlink(missing_ok=True)

    def post_api(post: PostRow, viewer: User | None, detail: bool = False) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": post.id,
            "title": post.title,
            "visibility": post.visibility,
            "author": {"uuid": post.uuid, "name": post.author_name, "badges": post.author_badges},
            "filename": post.filename,
            "format": post.format,
            "size": post.size,
            "sha256": post.sha256,
            "dimensions": list(post.dims) if post.dims else None,
            "blockCount": post.block_count,
            "sizeClass": post.size_class,
            "tags": post.tags,
            "hasThumbnail": post.has_thumb,
            "created": post.created,
            "updated": post.updated,
            "score": post.score,
            "ups": post.ups,
            "downs": post.downs,
            "hidden": post.hidden is not None,  # by an admin; only the author and admins see such a post
        }
        if detail:
            out["description"] = post.description
            out["myVote"] = store.get_vote(post.id, viewer.uuid) if viewer else None
            if viewer and (viewer.uuid == post.uuid or viewer.is_admin):
                out["sourcePath"] = post.source_path
                out["hiddenReason"] = post.hidden_reason if post.hidden is not None else None
                out["allowed"] = [{"uuid": u, "name": n} for u, n in store.get_allowed(post.id)]
        return out

    def clean_tags(value: Any) -> list[str]:
        if not isinstance(value, list) or not all(isinstance(t, str) for t in value):
            raise ApiError(400, "tags must be a list of tag names")
        unknown = [t for t in value if t not in TAGS]
        if unknown:
            raise ApiError(400, ("unknown tags: " if len(unknown) > 1 else "unknown tag: ") + ", ".join(unknown)
                           + " (known: " + ", ".join(TAGS) + ")")
        return [t for t in TAGS if t in value]

    def board_filters(params: Any, strict: bool) -> tuple[list[str], str | None]:
        """(tags, size class) from ?tag=&size=; strict raises 400 for unknown values instead of ignoring them."""
        raw = params.getlist("tag")
        tags = [t for t in TAGS if t in raw]
        if strict and len(tags) < len({t for t in raw if t}):
            clean_tags([t for t in raw if t])  # raises, naming the unknown ones
        size = params.get("size") or None
        if size is not None and size not in SIZE_LABELS:
            if strict:
                raise ApiError(400, "size must be one of " + ", ".join(SIZE_LABELS))
            size = None
        return tags, size

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
        tags, size_filter = board_filters(request.query_params, strict=True)
        user = optional_user(request) if tab == "public" else authenticate(request, mutating=False)
        size = settings.board_page_size
        posts, total = store.list_posts(tab, user.uuid if user else None, sort, query, page, size, tags, size_filter)
        out: dict[str, Any] = {
            "posts": [post_api(p, user) for p in posts],
            "tab": tab, "sort": sort, "q": query, "tags": tags, "size": size_filter, "page": page,
            "pageSize": size, "total": total, "pages": max(1, -(-total // size)),
        }
        if tab == "mine" and user is not None:
            out["postCount"], out["postBytes"] = store.post_usage(user.uuid)
        return out

    @app.post("/api/board")
    def board_create(request: Request, body: PostIn) -> JSONResponse:
        user = member_auth(request, mutating=True)
        path = validate_path(body.path)
        title = filtered(user, clean_title(body.title), "title")
        description = filtered(user, clean_description(body.description), "description")
        visibility = clean_visibility(body.visibility)
        tags = clean_tags(body.tags)
        limit = settings.max_posts_per_user
        if store.post_usage(user.uuid)[0] >= limit:
            raise ApiError(409, f"you already have {limit} posts on the board, which is the limit")
        allowed = resolve_names(body.allowed)
        with store.lock:
            require_file(user, path)
            check_quota(user, 1)  # already full: don't convert for nothing (the real check follows)
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
                    }, allowed, tags)
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
            values["title"] = filtered(user, clean_title(body.title), "title")
        if "description" in given:
            values["description"] = filtered(user, clean_description(body.description), "description")
        if "visibility" in given:
            values["visibility"] = clean_visibility(body.visibility)
        allowed = None
        if "allowed" in given:
            if body.allowed is None:
                raise ApiError(400, "allowed must be a list of Minecraft usernames")
            allowed = resolve_names(body.allowed, store.get_allowed(post.id))
        tags = clean_tags(body.tags) if "tags" in given else None
        store.update_post(post.id, values, allowed, tags)
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
                source = FileSource(post.id, post.title, post.uuid, post.author_name)
                store.put_file_row(user.uuid, FileRow(path, size, digest, int(time.time() * 1000), has_thumb, source))
                row = store.get_file(user.uuid, path)
        finally:
            tmp.unlink(missing_ok=True)
        return JSONResponse(row.api(), status_code=201)  # type: ignore[union-attr]

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

    def too_big(visible: int) -> ApiError:
        return ApiError(
            413, f"too big for the 3D view: {visible:,} visible blocks (the limit is {model3d.MAX_VISIBLE:,})",
            visibleBlocks=visible, maxVisibleBlocks=model3d.MAX_VISIBLE,
        )

    @app.get("/api/board/{post_id}/model")
    def board_model(post_id: str, request: Request) -> Response:
        """The visible blocks of the snapshot for the 3D view (model3d.visible_blocks), gzip JSON.
        Cached next to the snapshot under its sha256; a refresh removes the cache."""
        post, _ = visible_post(request, post_id)
        disk = store.post_file(post)
        if not disk.is_file():
            raise ApiError(404, "no such post")
        pdir = store.post_dir(post.id)
        key = MODEL_CACHE_PREFIX + post.sha256[:16]
        cache, big = pdir / f"{key}.json.gz", pdir / f"{key}.toobig"
        if not cache.is_file() and not big.is_file():
            with conversion_slot():
                if not cache.is_file() and not big.is_file():  # another request may have built it meanwhile
                    build_model(post, disk, cache, big)
        try:
            if big.is_file():
                text = big.read_text().strip()
                raise too_big(int(text) if text.isdigit() else model3d.MAX_VISIBLE + 1)
            data = cache.read_bytes()
        except FileNotFoundError:  # the post was refreshed or deleted just now
            raise ApiError(404, "no such post")
        headers = {"Cache-Control": "private, max-age=86400", "Vary": "Accept-Encoding"}
        if "gzip" in request.headers.get("accept-encoding", "").lower():
            return Response(data, media_type="application/json", headers={**headers, "Content-Encoding": "gzip"})
        return Response(gzip.decompress(data), media_type="application/json", headers=headers)

    def build_model(post: PostRow, disk: Path, cache: Path, big: Path) -> None:
        try:
            schem = read_schematic(disk, post.filename, settings.max_export_volume)
            model = model3d.visible_blocks(schem, transparent_blocks, model3d.MAX_VISIBLE, settings.max_export_volume)
            payload = gzip.compress(json.dumps(model, separators=(",", ":")).encode(), compresslevel=6, mtime=0)
            dest = cache
        except model3d.TooManyBlocks as e:
            payload, dest = str(e.visible).encode(), big
        except ValueError as e:  # an old post that never could be read
            raise ApiError(400, f"cannot read this schematic for the 3D view: {e}")
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".model")
        with os.fdopen(fd, "wb") as f:
            f.write(payload)
        with store.lock:
            current = store.get_post(post.id)
            if current is None or current.sha256 != post.sha256:
                Path(tmp_name).unlink(missing_ok=True)  # refreshed or deleted meanwhile: don't cache
                if dest is big:
                    raise too_big(int(payload))
                raise ApiError(409, "the post changed while its 3D view was built, try again")
            os.replace(tmp_name, dest)

    # ---- mod releases

    def require_admin(request: Request) -> User:
        user = authenticate(request, mutating=True)
        if not user.is_admin:
            raise ApiError(403, "only admins can do that")
        return user

    def release_page_url(version: str, mc: str | None = None) -> str:
        """The releases page (absolute) with version highlighted and scrolled to."""
        params = {"highlight": version, **({"mc": mc} if mc else {})}
        return f"{settings.site_url}/releases?{urllib.parse.urlencode(params)}#v{version}"

    def release_download_url(r: ReleaseRow) -> str:
        return f"{settings.site_url}/releases/{urllib.parse.quote(r.filename)}"

    def release_api(r: ReleaseRow) -> dict[str, Any]:
        return {
            "version": r.version, "mc": r.mc, "loader": r.loader, "filename": r.filename, "sha256": r.sha256,
            "size": r.size, "uploaded": r.uploaded, "changelog": r.changelog, "hidden": r.hidden,
            "downloads": r.downloads, "download": release_download_url(r), "page": release_page_url(r.version, r.mc),
        }

    @app.get("/api/releases/latest")
    def releases_latest(request: Request) -> JSONResponse:
        # The mod's update check: keep these field names.
        mc = (request.query_params.get("mc") or "").strip()
        loader = (request.query_params.get("loader") or DEFAULT_LOADER).strip().lower()
        if not MC_VERSION_RE.match(mc):
            raise ApiError(400, "mc must be a Minecraft version such as 26.3")
        if not LOADER_RE.match(loader):
            raise ApiError(400, "loader must be a lowercase name such as fabric")
        r = store.latest_release(mc, loader)
        if r is None:
            raise ApiError(404, f"no release for Minecraft {mc} ({loader})")
        return JSONResponse({
            "version": r.version, "download": release_download_url(r), "page": release_page_url(r.version, r.mc),
            "sha256": r.sha256, "mc": r.mc, "loader": r.loader,
        }, headers={"Cache-Control": "no-cache"})

    @app.get("/api/releases")
    def releases_list(request: Request) -> dict[str, Any]:
        user = optional_user(request)
        admin = user is not None and user.is_admin
        return {"releases": [release_api(r) for r in store.list_releases(include_hidden=admin)]}

    @app.post("/api/releases")
    async def releases_publish(request: Request) -> JSONResponse:
        user = require_admin(request)
        data, _, _ = await read_capped(request, settings.max_release_bytes + 64 * 1024)  # room for the form fields
        fields, files = parse_multipart(data, request.headers.get("content-type", ""))
        if "jar" not in files:
            raise ApiError(400, "the jar file is required (form field jar)")
        upload_name, content = files["jar"]
        if len(content) > settings.max_release_bytes:
            raise ApiError(413, "file too large", maxBytes=settings.max_release_bytes)
        filename = fields.get("filename") or upload_name.replace("\\", "/").rsplit("/", 1)[-1]
        hidden = fields.get("hidden", "").strip().lower() in ("1", "true", "yes", "on")
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".jar")
        tmp = Path(tmp_name)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(content)

            def publish() -> ReleaseRow:
                with store.lock:
                    return add_release(
                        store.db, store.releases_dir, tmp, fields.get("version"), fields.get("mc"),
                        fields.get("loader") or DEFAULT_LOADER, filename, fields.get("changelog", ""), hidden,
                    )

            row = await run_in_threadpool(publish)
        finally:
            tmp.unlink(missing_ok=True)
        log.info("%s published release %s (Minecraft %s, %s) as %s", user.name, row.version, row.mc, row.loader, row.filename)
        return JSONResponse(release_api(row), status_code=201)

    @app.patch("/api/releases/{filename}")
    def releases_patch(filename: str, request: Request, body: ReleasePatchIn) -> dict[str, Any]:
        require_admin(request)
        if store.get_release(filename) is None:
            raise ApiError(404, "no such release")
        values: dict[str, Any] = {}
        if "hidden" in body.model_fields_set:
            if not isinstance(body.hidden, bool):
                raise ApiError(400, "hidden must be true or false")
            values["hidden"] = int(body.hidden)
        if "changelog" in body.model_fields_set:
            r = store.get_release(filename)
            values["changelog"] = clean_release_meta(r.version, r.mc, r.loader, r.filename, body.changelog)[4]  # type: ignore[union-attr]
        if values:
            store.update_release(filename, values)
        return release_api(store.get_release(filename))  # type: ignore[arg-type]

    @app.get("/releases/{filename}")
    def release_download(filename: str, request: Request) -> FileResponse:
        r = store.get_release(filename) if RELEASE_FILENAME_RE.match(filename) else None
        if r is not None and r.hidden:
            user = optional_user(request)
            if user is None or not user.is_admin:
                r = None
        if r is None:
            raise ApiError(404, "no such release")
        disk = release_path(store.releases_dir, r.filename)
        if not disk.is_file():
            raise ApiError(404, "no such release")
        store.count_download(r.filename)
        return FileResponse(
            disk,
            media_type="application/java-archive",
            filename=r.filename,
            headers={"X-Sha256": r.sha256, "Cache-Control": "no-cache"},
        )

    # ---- feedback

    def clean_short(value: Any, what: str, limit: int) -> str | None:
        """An optional one-line text field: None if missing or blank."""
        if value is None:
            return None
        if not isinstance(value, str):
            raise ApiError(400, f"{what} must be text")
        value = value.strip()
        if len(value) > limit:
            raise ApiError(400, f"{what} must be at most {limit} characters")
        if TITLE_BAD_CHARS.search(value):
            raise ApiError(400, f"{what} may not contain control characters or line breaks")
        return value or None

    def feedback_api(r: sqlite3.Row) -> dict[str, Any]:
        return {
            "id": r["id"], "type": r["kind"], "message": r["message"], "modVersion": r["mod_version"],
            "mcVersion": r["mc_version"], "contact": r["contact"],
            "user": {"uuid": r["uuid"], "name": r["name"]} if r["uuid"] else None,
            "created": r["created"], "resolved": r["resolved"],
        }

    @app.post("/api/feedback")
    def feedback_submit(request: Request, body: FeedbackIn) -> JSONResponse:
        if request.headers.get("authorization"):
            user: User | None = authenticate(request, mutating=True)
        else:
            # logged out too: the header keeps other sites from posting through a visitor's browser
            if request.headers.get(CSRF_HEADER) != CSRF_VALUE:
                raise ApiError(403, f"missing X-Requested-With: {CSRF_VALUE} header")
            user = session_user(request)
        if body.type not in FEEDBACK_KINDS:
            raise ApiError(400, "type must be one of " + ", ".join(FEEDBACK_KINDS))
        if not isinstance(body.message, str) or not body.message.strip():
            raise ApiError(400, "message is required")
        message = body.message.replace("\r\n", "\n").replace("\r", "\n").strip()
        if len(message) > MAX_FEEDBACK_LEN:
            raise ApiError(400, f"message must be at most {MAX_FEEDBACK_LEN} characters")
        if DESCRIPTION_BAD_CHARS.search(message):
            raise ApiError(400, "message may not contain control characters")
        mod_version = clean_short(body.modVersion, "modVersion", MAX_FEEDBACK_VERSION_LEN)
        mc_version = clean_short(body.mcVersion, "mcVersion", MAX_FEEDBACK_VERSION_LEN)
        contact = clean_short(body.contact, "contact", MAX_CONTACT_LEN)
        if user is not None:
            key, limit = f"feedback-user:{user.uuid}", FEEDBACK_USER_LIMIT
        else:
            key, limit = f"feedback-ip:{client_ip(request)}", FEEDBACK_IP_LIMIT
        with store.lock:
            n, oldest = store.recent_failures(key)
            if n >= limit:
                wait = max(1, -(-((oldest or now_s()) + LOGIN_WINDOW - now_s()) // 60))
                raise ApiError(429, f"too many messages for now, try again in {wait} minute{'s' if wait != 1 else ''}")
            store.add_failure(key)
            feedback_id = store.add_feedback({
                "kind": body.type, "message": message, "mod_version": mod_version, "mc_version": mc_version,
                "contact": contact, "uuid": user.uuid if user else None, "name": user.name if user else None,
                "created": int(time.time() * 1000),
            })
            row = store.db.execute("SELECT * FROM feedback WHERE id=?", (feedback_id,)).fetchone()
        return JSONResponse(feedback_api(row), status_code=201)

    @app.get("/api/feedback")
    def feedback_list(request: Request) -> dict[str, Any]:
        user = authenticate(request, mutating=False)
        if not user.is_admin:
            raise ApiError(403, "only admins can do that")
        status = request.query_params.get("status") or "open"
        if status not in FEEDBACK_STATUSES:
            raise ApiError(400, "status must be one of " + ", ".join(FEEDBACK_STATUSES))
        n_open, n_resolved = store.feedback_counts()
        return {"feedback": [feedback_api(r) for r in store.list_feedback(status)], "open": n_open, "resolved": n_resolved}

    @app.post("/api/feedback/{feedback_id}/resolve")
    def feedback_resolve(feedback_id: int, request: Request, body: ResolveIn) -> dict[str, Any]:
        require_admin(request)
        if not isinstance(body.resolved, bool):
            raise ApiError(400, "resolved must be true or false")
        row = store.set_feedback_resolved(feedback_id, body.resolved)
        if row is None:
            raise ApiError(404, "no such feedback")
        return feedback_api(row)

    # ---- applications for access, storage requests, reports

    def clean_long(value: Any, what: str, limit: int, required: bool = True) -> str:
        """A multi-line text field: newlines normalised, trimmed, at most limit characters."""
        if value is None and not required:
            return ""
        if not isinstance(value, str) or (required and not value.strip()):
            raise ApiError(400, f"{what} is required")
        text = value.replace("\r\n", "\n").replace("\r", "\n").strip()
        if len(text) > limit:
            raise ApiError(400, f"{what} must be at most {limit} characters")
        if DESCRIPTION_BAD_CHARS.search(text):
            raise ApiError(400, f"{what} may not contain control characters")
        return text

    def ms_or_none(seconds: int | None) -> int | None:
        return seconds * 1000 if seconds is not None else None

    def who(uuid: str | None, name: str | None) -> dict[str, Any] | None:
        return {"uuid": uuid, "name": name or ""} if uuid else None

    def application_api(r: sqlite3.Row, admin: bool = False) -> dict[str, Any]:
        out = {
            "id": r["id"], "user": who(r["uuid"], r["user_name"] or r["name"]), "reason": r["reason"],
            "community": r["community"], "contact": r["contact"], "status": r["status"], "created": r["created"],
            "decided": r["decided"], "note": r["note"],
        }
        if admin:
            out["decidedBy"] = r["decided_by"]
            out["whitelisted"] = store.on_whitelist(r["uuid"])
        return out

    def storage_api(r: sqlite3.Row, admin: bool = False) -> dict[str, Any]:
        out = {
            "id": r["id"], "user": who(r["uuid"], r["user_name"]), "requestedBytes": r["requested_bytes"],
            "reason": r["reason"], "usedBytes": r["used_bytes"], "quotaBytes": r["quota_bytes"], "status": r["status"],
            "created": r["created"], "decided": r["decided"], "grantedBytes": r["granted_bytes"], "note": r["note"],
        }
        if admin:
            out["decidedBy"] = r["decided_by"]
            u = store.get_user(r["uuid"])
            if u is not None:  # usage and quota now, next to those when asked
                out["current"] = usage(u)
        return out

    def report_api(r: sqlite3.Row, admin: bool = False) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": r["id"], "kind": r["kind"],
            "target": {"id": r["target"], "label": r["target_label"], "uuid": r["target_uuid"]},
            "reason": r["reason"], "details": r["details"], "status": r["status"], "created": r["created"],
            "decided": r["decided"], "note": r["note"],
        }
        if admin:
            out["reporter"] = who(r["reporter"], r["user_name"])
            out["decidedBy"] = r["decided_by"]
            target_user = store.get_user(r["target_uuid"])
            out["target"]["name"] = target_user.name if target_user else None  # the reported user / the author now
            if r["kind"] == "post":
                post = store.get_post(r["target"]) if POST_ID_RE.match(r["target"]) else None
                out["post"] = {"visibility": post.visibility, "hidden": post.hidden is not None} if post else None
        return out

    def rate_limited(key: str, limit: int, what: str) -> None:
        """429 once key has limit entries in login_failures' window. Call with store.lock held, then add_failure."""
        n, oldest = store.recent_failures(key)
        if n >= limit:
            wait = max(1, -(-((oldest or now_s()) + LOGIN_WINDOW - now_s()) // 60))
            raise ApiError(429, f"too many {what} for now, try again in {wait} minute{'s' if wait != 1 else ''}")

    def request_window_check(table: str, user: User, what: str) -> None:
        """One open row per account, and at most REQUEST_LIMIT new ones per REQUEST_WINDOW. Call with store.lock held."""
        latest = store.latest_row(table, user.uuid)
        if latest is not None and latest["status"] == "pending":
            raise ApiError(409, f"you already have an open {what}; wait for an admin to look at it")
        if store.recent_rows(table, user.uuid, REQUEST_WINDOW) >= REQUEST_LIMIT:
            raise ApiError(429, f"too many {what}s for now, try again in a few days")

    def grant_access(uuid: str, name: str, source: str, admin: User) -> bool:
        """Put a player on the whitelist and approve their open application. True if they were not on it."""
        added = store.whitelist_add(uuid, name, source, admin.uuid)
        latest = store.latest_row("applications", uuid)
        if latest is not None and latest["status"] == "pending":
            store.decide("applications", latest["id"], "approved", admin.uuid, None)
        return added

    @app.get("/api/application")
    def application_status(request: Request) -> dict[str, Any]:
        user = authenticate(request, mutating=False)
        row = store.latest_row("applications", user.uuid)
        return {"whitelisted": is_member(user), "application": application_api(row) if row else None, "apply": apply_url()}

    @app.post("/api/application")
    def application_submit(request: Request, body: ApplicationIn) -> JSONResponse:
        user = authenticate(request, mutating=True)
        if is_member(user):
            raise ApiError(409, "you already have access")
        reason = filtered(user, clean_long(body.reason, "reason", MAX_APPLICATION_LEN), "reason")
        community = filtered(user, clean_long(body.community, "community", MAX_APPLICATION_LEN), "community")
        contact = filtered(user, clean_short(body.contact, "contact", MAX_CONTACT_LEN), "contact")
        with store.lock:
            request_window_check("applications", user, "application")
            row_id = store.insert_row("applications", {
                "uuid": user.uuid, "name": user.name, "reason": reason, "community": community, "contact": contact,
                "created": int(time.time() * 1000),
            })
            row = store.get_row("applications", row_id)
        log.info("%s applied for access (application %d)", user.name, row_id)
        return JSONResponse(application_api(row), status_code=201)  # type: ignore[arg-type]

    @app.get("/api/account/storage-request")
    def storage_status(request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=False)
        row = store.latest_row("storage_requests", user.uuid)
        return {**usage(user), "choices": [c for c in STORAGE_CHOICES if quota_for(user) is not None and c > quota_for(user)],
                "maxBytes": MAX_STORAGE_REQUEST, "request": storage_api(row) if row else None}

    @app.post("/api/account/storage-request")
    def storage_submit(request: Request, body: StorageRequestIn) -> JSONResponse:
        user = member_auth(request, mutating=True)
        quota = quota_for(user)
        if quota is None:
            raise ApiError(409, "your storage is already unlimited")
        if type(body.bytes) is not int or not quota < body.bytes <= MAX_STORAGE_REQUEST:
            raise ApiError(400, f"bytes must be more than your current quota ({human_size(quota)}) and at most "
                                f"{human_size(MAX_STORAGE_REQUEST)}")
        reason = filtered(user, clean_long(body.reason, "reason", MAX_REQUEST_REASON_LEN), "reason")
        with store.lock:
            request_window_check("storage_requests", user, "storage request")
            row_id = store.insert_row("storage_requests", {
                "uuid": user.uuid, "requested_bytes": body.bytes, "reason": reason,
                "used_bytes": store.used_bytes(user.uuid), "quota_bytes": quota, "created": int(time.time() * 1000),
            })
            row = store.get_row("storage_requests", row_id)
        log.info("%s asked for %s of storage (request %d)", user.name, human_size(body.bytes), row_id)
        return JSONResponse(storage_api(row), status_code=201)  # type: ignore[arg-type]

    @app.post("/api/reports")
    def report_submit(request: Request, body: ReportIn) -> JSONResponse:
        user = member_auth(request, mutating=True)
        if body.reason not in REPORT_REASONS:
            raise ApiError(400, "reason must be one of " + ", ".join(REPORT_REASONS))
        details = filtered(user, clean_long(body.details, "details", MAX_REPORT_LEN, required=body.reason == "other"), "details")
        if not isinstance(body.target, str) or not body.target.strip():
            raise ApiError(400, "target is required")
        if body.kind == "post":
            post = find_post(body.target.strip(), user)  # 404 unless the reporter can see it
            if post.uuid == user.uuid:
                raise ApiError(400, "you can't report your own post")
            target, target_uuid, label = post.id, post.uuid, post.title
        elif body.kind == "user":
            reported = store.find_user(body.target)
            if reported is None:
                raise ApiError(404, "no such user")
            if reported.uuid == user.uuid:
                raise ApiError(400, "you can't report yourself")
            target, target_uuid, label = reported.uuid, reported.uuid, reported.name
        else:
            raise ApiError(400, "kind must be post or user")
        key = f"report-user:{user.uuid}"
        with store.lock:
            if store.list_rows("reports", "open", "t.reporter = ? AND t.kind = ? AND t.target = ?", (user.uuid, body.kind, target)):
                raise ApiError(409, "you already reported this; an admin will look at it")
            rate_limited(key, REPORT_USER_LIMIT, "reports")
            store.add_failure(key)
            row_id = store.insert_row("reports", {
                "reporter": user.uuid, "kind": body.kind, "target": target, "target_uuid": target_uuid,
                "target_label": label, "reason": body.reason, "details": details, "created": int(time.time() * 1000),
            })
            row = store.get_row("reports", row_id)
        log.info("%s reported %s %s (%s)", user.name, body.kind, target, body.reason)
        return JSONResponse(report_api(row), status_code=201)  # type: ignore[arg-type]

    # ---- admin

    def admin_only(request: Request, *, mutating: bool) -> User:
        user = authenticate(request, mutating=mutating)
        if not user.is_admin:
            raise ApiError(403, "only admins can do that")
        return user

    def status_param(request: Request, statuses: tuple[str, ...]) -> str:
        status = request.query_params.get("status") or statuses[0]
        if status not in (*statuses, "all"):
            raise ApiError(400, "status must be one of " + ", ".join((*statuses, "all")))
        return status

    def note_of(body: DecisionIn | HideIn, field_name: str = "note") -> str | None:
        return clean_short(getattr(body, field_name), field_name, MAX_NOTE_LEN)

    def find_player(text: Any) -> User:
        if not isinstance(text, str) or not text.strip():
            raise ApiError(400, "give a Minecraft name or uuid")
        user = store.find_user(text)
        if user is None:
            raise ApiError(404, "no account with that name or uuid has logged in here")
        return user

    def user_admin_api(r: sqlite3.Row) -> dict[str, Any]:
        u = User(r["uuid"], r["name"], bool(r["is_admin"]), r["quota_bytes"])
        return {
            "uuid": u.uuid, "name": u.name, "admin": u.is_admin, "usedBytes": int(r["used"]), "quotaBytes": quota_for(u),
            "quotaOverride": u.quota_bytes, "badges": badge_list(r["badges"]), "whitelisted": r["wl_source"] is not None,
            "whitelistSource": r["wl_source"], "posts": r["posts"], "created": ms_or_none(r["created"]),
            "lastSeen": ms_or_none(r["last_seen"] if r["last_seen"] is not None else r["last_login"]),
        }

    def user_admin_one(uuid: str) -> dict[str, Any]:
        rows, _ = store.list_users(uuid, 1, 1)
        return user_admin_api(rows[0])

    @app.get("/api/admin/applications")
    def admin_applications(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        status = status_param(request, DECISIONS)
        return {"applications": [application_api(r, True) for r in store.list_rows("applications", status)],
                "counts": store.status_counts("applications")}

    def decide_application(app_id: int, body: DecisionIn, request: Request, approve: bool) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        note = note_of(body)
        with store.lock:
            row = store.get_row("applications", app_id)
            if row is None:
                raise ApiError(404, "no such application")
            if not store.decide("applications", app_id, "approved" if approve else "rejected", admin.uuid, note):
                raise ApiError(409, f"this application was already {row['status']}")
            if approve:
                store.whitelist_add(row["uuid"], row["user_name"] or row["name"], "approved", admin.uuid)
            row = store.get_row("applications", app_id)
        log.info("%s %s the application of %s", admin.name, "approved" if approve else "rejected", row["user_name"])  # type: ignore[index]
        return application_api(row, True)  # type: ignore[arg-type]

    @app.post("/api/admin/applications/{app_id}/approve")
    def admin_application_approve(app_id: int, request: Request, body: DecisionIn) -> dict[str, Any]:
        return decide_application(app_id, body, request, approve=True)

    @app.post("/api/admin/applications/{app_id}/reject")
    def admin_application_reject(app_id: int, request: Request, body: DecisionIn) -> dict[str, Any]:
        return decide_application(app_id, body, request, approve=False)

    @app.get("/api/admin/storage-requests")
    def admin_storage_requests(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        status = status_param(request, DECISIONS)
        return {"requests": [storage_api(r, True) for r in store.list_rows("storage_requests", status)],
                "counts": store.status_counts("storage_requests")}

    @app.post("/api/admin/storage-requests/{req_id}/approve")
    def admin_storage_approve(req_id: int, request: Request, body: DecisionIn) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        note = note_of(body)
        with store.lock:
            row = store.get_row("storage_requests", req_id)
            if row is None:
                raise ApiError(404, "no such storage request")
            granted = row["requested_bytes"] if body.quotaBytes is None else body.quotaBytes
            if type(granted) is not int or granted <= 0:
                raise ApiError(400, "quotaBytes must be a positive number of bytes")
            if not store.decide("storage_requests", req_id, "approved", admin.uuid, note, granted_bytes=granted):
                raise ApiError(409, f"this request was already {row['status']}")
            store.set_quota(row["uuid"], granted)
            row = store.get_row("storage_requests", req_id)
        log.info("%s set the quota of %s to %s", admin.name, row["user_name"], human_size(granted))  # type: ignore[index]
        return storage_api(row, True)  # type: ignore[arg-type]

    @app.post("/api/admin/storage-requests/{req_id}/reject")
    def admin_storage_reject(req_id: int, request: Request, body: DecisionIn) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        note = note_of(body)
        with store.lock:
            row = store.get_row("storage_requests", req_id)
            if row is None:
                raise ApiError(404, "no such storage request")
            if not store.decide("storage_requests", req_id, "rejected", admin.uuid, note):
                raise ApiError(409, f"this request was already {row['status']}")
            return storage_api(store.get_row("storage_requests", req_id), True)  # type: ignore[arg-type]

    @app.get("/api/admin/reports")
    def admin_reports(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        status = status_param(request, REPORT_STATUSES)
        return {"reports": [report_api(r, True) for r in store.list_rows("reports", status)],
                "counts": store.status_counts("reports")}

    def close_report(report_id: int, body: DecisionIn, request: Request, status: str) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        note = note_of(body)
        with store.lock:
            row = store.get_row("reports", report_id)
            if row is None:
                raise ApiError(404, "no such report")
            if not store.decide("reports", report_id, status, admin.uuid, note):
                raise ApiError(409, f"this report was already {row['status']}")
            return report_api(store.get_row("reports", report_id), True)  # type: ignore[arg-type]

    @app.post("/api/admin/reports/{report_id}/resolve")
    def admin_report_resolve(report_id: int, request: Request, body: DecisionIn) -> dict[str, Any]:
        return close_report(report_id, body, request, "resolved")

    @app.post("/api/admin/reports/{report_id}/dismiss")
    def admin_report_dismiss(report_id: int, request: Request, body: DecisionIn) -> dict[str, Any]:
        return close_report(report_id, body, request, "dismissed")

    @app.post("/api/admin/posts/{post_id}/hide")
    def admin_post_hide(post_id: str, request: Request, body: HideIn) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        post = find_post(post_id, admin)
        if not isinstance(body.hidden, bool):
            raise ApiError(400, "hidden must be true or false")
        reason = note_of(body, "reason") or ""
        store.update_post(post.id, {"hidden": int(time.time() * 1000) if body.hidden else None,
                                    "hidden_reason": reason if body.hidden else None})
        log.info("%s %s board post %s (%r by %s)", admin.name, "hid" if body.hidden else "unhid", post.id, post.title, post.author_name)
        return post_api(store.get_post(post.id), admin, detail=True)  # type: ignore[arg-type]

    @app.get("/api/admin/whitelist")
    def admin_whitelist(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        entries = [whitelist_api(r) for r in store.whitelist_entries()]
        return {"entries": entries, "count": len(entries)}

    def whitelist_api(r: sqlite3.Row) -> dict[str, Any]:
        out = {"uuid": r["uuid"], "name": r["name"], "source": r["source"], "added": r["added"]}
        if "account_name" in r.keys():
            out["accountName"] = r["account_name"]  # the name at the account's last login, if it has one here
        return out

    @app.post("/api/admin/whitelist")
    def admin_whitelist_add(request: Request, body: PlayerIn) -> JSONResponse:
        """Add one player by name (looked up here, then at Mojang) or uuid (known here, or with a name)."""
        admin = admin_only(request, mutating=True)
        if not isinstance(body.player, str) or not body.player.strip():
            raise ApiError(400, "give a Minecraft name or uuid")
        text = body.player.strip()
        uuid = parse_uuid(text)
        if uuid is not None:
            known = store.get_user(uuid)
            entry = store.whitelist_entry(uuid)
            if known or entry:  # an entry's name may be a Bedrock one from a whitelist file: keep it as it is
                name = known.name if known else entry["name"]  # type: ignore[index]
            elif isinstance(body.name, str) and USERNAME_RE.match(body.name.strip()):
                name = body.name.strip()
            else:
                raise ApiError(400, "nobody with that uuid has logged in here; add them by name instead")
        elif USERNAME_RE.match(text):
            known = store.find_user(text)
            if known is not None:
                uuid, name = known.uuid, known.name
            else:
                uuid, name = resolve_names([text])[0]
        else:
            raise ApiError(400, f"not a Minecraft name or uuid: {text}")
        with store.lock:
            added = grant_access(uuid, name, "manual", admin)
            entry = store.whitelist_entry(uuid)
        if added:
            log.info("%s added %s (%s) to the whitelist", admin.name, name, uuid)
        return JSONResponse({**whitelist_api(entry), "added": added}, status_code=201 if added else 200)  # type: ignore[arg-type]

    @app.delete("/api/admin/whitelist/{uuid}")
    def admin_whitelist_delete(uuid: str, request: Request) -> Response:
        admin = admin_only(request, mutating=True)
        dashed = parse_uuid(uuid)
        if dashed is None or store.whitelist_remove([dashed]) == 0:
            raise ApiError(404, "not on the whitelist")
        log.info("%s removed %s from the whitelist", admin.name, dashed)
        return Response(status_code=204)

    @app.post("/api/admin/whitelist/remove")
    def admin_whitelist_remove(request: Request, body: UuidsIn) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        if not isinstance(body.uuids, list) or not body.uuids:
            raise ApiError(400, "uuids must be a list of uuids")
        uuids = [parse_uuid(u) for u in body.uuids]
        if None in uuids:
            raise ApiError(400, "uuids must be a list of uuids")
        removed = store.whitelist_remove(uuids)  # type: ignore[arg-type]
        log.info("%s removed %d players from the whitelist", admin.name, removed)
        return {"removed": removed, "count": store.whitelist_count()}

    @app.post("/api/admin/whitelist/import")
    async def admin_whitelist_import(request: Request) -> dict[str, Any]:
        """The body is a whitelist.json. Adds new players, updates names, removes nobody: the entries the file
        doesn't have come back as missing, for the admin to remove (or keep) one by one."""
        admin = admin_only(request, mutating=True)
        raw, _, _ = await read_capped(request, MAX_WHITELIST_BYTES)
        try:
            data = json.loads(raw.decode("utf-8-sig"))
        except (UnicodeDecodeError, ValueError):
            raise ApiError(400, "not a JSON file")
        entries = parse_whitelist(data)

        def merge() -> dict[str, Any]:
            with store.lock:
                result = store.whitelist_import(entries, admin.uuid)
                for uuid, name in result["added"]:
                    grant_access(uuid, name, "upload", admin)  # already added: this approves open applications
                return result

        result = await run_in_threadpool(merge)
        log.info("%s uploaded a whitelist: %d added, %d renamed, %d not in the file",
                 admin.name, len(result["added"]), len(result["renamed"]), len(result["missing"]))
        return {
            "added": [{"uuid": u, "name": n} for u, n in result["added"]],
            "renamed": [{"uuid": u, "from": a, "to": b} for u, a, b in result["renamed"]],
            "unchanged": result["unchanged"],
            "missing": [whitelist_api(r) for r in result["missing"]],
            "count": store.whitelist_count(),
        }

    @app.get("/api/admin/users")
    def admin_users(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        q = (request.query_params.get("q") or "").strip()[:MAX_QUERY_LEN]
        raw_page = request.query_params.get("page") or "1"
        page = int(raw_page) if raw_page.isdigit() and 0 < int(raw_page) <= 100000 else 1
        rows, total = store.list_users(q, page, ADMIN_PAGE_SIZE)
        return {"users": [user_admin_api(r) for r in rows], "q": q, "page": page, "pageSize": ADMIN_PAGE_SIZE,
                "total": total, "pages": max(1, -(-total // ADMIN_PAGE_SIZE))}

    @app.patch("/api/admin/users/{player}")
    def admin_user_patch(player: str, request: Request, body: UserPatchIn) -> dict[str, Any]:
        """{"quotaBytes": n} sets the account's quota, null goes back to the default."""
        admin = admin_only(request, mutating=True)
        user = find_player(player)
        if "quotaBytes" in body.model_fields_set:
            quota = body.quotaBytes
            if quota is not None and (type(quota) is not int or quota <= 0):
                raise ApiError(400, "quotaBytes must be a positive number of bytes, or null for the default")
            store.set_quota(user.uuid, quota)
            log.info("%s set the quota of %s to %s", admin.name, user.name, human_size(quota) if quota else "the default")
        return user_admin_one(user.uuid)

    @app.put("/api/admin/users/{player}/badges/{badge}")
    def admin_badge_grant(player: str, badge: str, request: Request) -> dict[str, Any]:
        return set_badge(player, badge, request, True)

    @app.delete("/api/admin/users/{player}/badges/{badge}")
    def admin_badge_revoke(player: str, badge: str, request: Request) -> dict[str, Any]:
        return set_badge(player, badge, request, False)

    def set_badge(player: str, badge: str, request: Request, on: bool) -> dict[str, Any]:
        admin = admin_only(request, mutating=True)
        if badge not in BADGES:
            raise ApiError(400, "badge must be one of " + ", ".join(BADGES))
        user = find_player(player)
        store.set_badge(user.uuid, badge, on, admin.uuid)
        log.info("%s %s the %s badge %s %s", admin.name, "gave" if on else "took", badge, "to" if on else "from", user.name)
        return user_admin_one(user.uuid)

    # ---- website

    def render(name: str, status: int = 200, **ctx: Any) -> HTMLResponse:
        user = ctx.get("user")
        ctx.setdefault("member", user is not None and is_member(user))
        html = templates.get_template(name).render(
            base=base, v=static_version, server_name=SERVER_NAME, **ctx
        )
        return HTMLResponse(html, status_code=status, headers={"Cache-Control": "no-store"})

    def not_found_page(request: Request) -> HTMLResponse:
        """The 404 page for an unknown address. Admin pages answer it to everyone else too."""
        return render("not_found.html", status=404, user=session_user(request), heading="This page isn’t here.",
                      lead="There is nothing at this address.")

    @app.get("/", response_class=HTMLResponse)
    def home(request: Request) -> Response:
        user = session_user(request)
        if user is None:
            return render("landing.html", max_password=MAX_PASSWORD_LEN)
        if not is_member(user):
            return RedirectResponse(f"{base}/apply", status_code=303)  # their files stay, out of reach until approved
        files = store.list_files(user.uuid)
        used = store.used_bytes(user.uuid)  # files plus board snapshots
        post_count, post_bytes = store.post_usage(user.uuid)
        quota = quota_for(user)
        entries = []
        post_visible: dict[str, bool] = {}
        for f in files:
            folder, _, name = f.path.rpartition("/")
            ext = next(e for e in SCHEMATIC_EXTENSIONS if name.lower().endswith(e))
            source_url = None
            if f.source:
                # link to the post only while it exists and this user may see it
                pid = f.source.post_id
                if pid not in post_visible:
                    post = store.get_post(pid) if POST_ID_RE.match(pid) else None
                    post_visible[pid] = post is not None and can_view(post, user)
                if post_visible[pid]:
                    source_url = f"{base}/board/{pid}"
            entries.append({
                "f": f, "folder": folder, "name": name, "stem": name[: -len(ext)], "ext": ext,
                "exports": [e for e in SCHEMATIC_EXTENSIONS if e != ext], "source_url": source_url,
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
            all_tags=TAGS,
            folders=folders,
            used=used,
            quota=quota,
            pct=f"{pct:.2f}",
            over=quota is not None and used >= quota,
        )

    def board_url(
        tab: str = "public", sort: str | None = None, q: str = "", page: int = 1,
        tags: Any = (), size: str | None = None,
    ) -> str:
        """A link to a board page, leaving out parameters at their defaults."""
        params: list[tuple[str, Any]] = []
        if tab != "public":
            params.append(("tab", tab))
        if sort and sort != ("top" if tab == "public" else "new"):
            params.append(("sort", sort))
        if q:
            params.append(("q", q))
        params.extend(("tag", t) for t in TAGS if t in tags)
        if size:
            params.append(("size", size))
        if page > 1:
            params.append(("page", page))
        return f"{base}/board" + ("?" + urllib.parse.urlencode(params) if params else "")

    @app.get("/board", response_class=HTMLResponse)
    def board_page(request: Request) -> HTMLResponse:
        user = session_user(request)
        tab, sort, query, page = board_query(request.query_params, strict=False)
        tags, size_filter = board_filters(request.query_params, strict=False)
        size = settings.board_page_size
        posts: list[PostRow] = []
        total = 0
        if tab == "public" or user is not None:
            posts, total = store.list_posts(tab, user.uuid if user else None, sort, query, page, size, tags, size_filter)

        def link(**change: Any) -> str:
            """This board page with some of tab, sort, q, page, tags and size changed (page resets to 1)."""
            state = {"tab": tab, "sort": sort, "q": query, "page": 1, "tags": tags, "size": size_filter}
            return board_url(**{**state, **change})

        def toggle_tag(tag: str) -> str:
            return link(tags=[t for t in tags if t != tag] if tag in tags else [*tags, tag])

        def chip_url(tag: str | None = None, size: str | None = None) -> str:
            """A card's chip: add its tag (or set its size) to the current filters."""
            if tag is not None:
                return link(tags=tags if tag in tags else [*tags, tag])
            return link(size=size)

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
            tags=tags,
            size=size_filter,
            link=link,
            toggle_tag=toggle_tag,
            chip_url=chip_url,
            all_tags=TAGS,
            size_classes=SIZE_LABELS,
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
            all_tags=TAGS,
            size_classes=SIZE_LABELS,
            board_url=board_url,
            has_model=post.block_count is not None,
            max_visible=model3d.MAX_VISIBLE,
            report_reasons=REPORT_REASONS,
            max_report=MAX_REPORT_LEN,
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
        member = is_member(user)
        storage_row = store.latest_row("storage_requests", user.uuid) if member else None
        return render(
            "account.html",
            user=user,
            nav="account",
            member=member,
            badges=store.badges_of(user.uuid),
            used=used,
            quota=quota,
            pct=f"{pct:.2f}",
            near_full=quota is not None and pct >= STORAGE_WARN_PCT,
            storage_choices=[c for c in STORAGE_CHOICES if quota is not None and c > quota],
            min_custom_gb=(quota // GIB + 1) if quota is not None else 1,
            max_custom_gb=MAX_STORAGE_REQUEST // GIB,
            max_request_reason=MAX_REQUEST_REASON_LEN,
            storage_request=storage_api(storage_row) if storage_row else None,
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

    @app.get("/releases", response_class=HTMLResponse)
    def releases_page(request: Request) -> HTMLResponse:
        user = session_user(request)
        releases = store.list_releases()  # newest version first
        highlight = request.query_params.get("highlight") or ""
        if not VERSION_RE.match(highlight):
            highlight = ""
        # ?highlight=<version>&mc=<mc>: the mod's fallback when updating in place fails. Without mc (or
        # for an mc it has no build for) every build of the version is highlighted.
        marked = [r for r in releases if r.version == highlight]
        hl_mc = request.query_params.get("mc") or ""
        if any(r.mc == hl_mc for r in marked):
            marked = [r for r in marked if r.mc == hl_mc]
        groups: dict[str, list[ReleaseRow]] = {}
        for r in sorted(releases, key=lambda r: mc_version_key(r.mc), reverse=True):  # stable: versions stay sorted
            groups.setdefault(r.mc, []).append(r)
        # Element ids: #v<version> on one build of each version (the highlighted one if any), so the
        # mod's #v<version> lands right; other builds of the same version get #v<version>-mc<mc>-<loader>.
        anchors: dict[int, str] = {}
        if marked:
            anchors[marked[0].id] = f"v{highlight}"
        for group in groups.values():
            for r in group:
                if r.id not in anchors:
                    bare = f"v{r.version}"
                    anchors[r.id] = bare if bare not in anchors.values() else f"v{r.version}-mc{r.mc}-{r.loader}"
        newest = releases[0] if releases else None
        return render(
            "releases.html",
            user=user,
            nav="releases",
            newest=newest,
            newest_builds=[r for r in releases if newest and r.version == newest.version],
            groups=list(groups.items()),
            anchors=anchors,
            marked={r.id for r in marked},
            highlight=highlight,
            github=GITHUB_URL,
            default_loader=DEFAULT_LOADER,
        )

    @app.get("/feedback", response_class=HTMLResponse)
    def feedback_page(request: Request) -> HTMLResponse:
        # The mod's Credits button opens this; ?mod=&mc= fill in the versions, ?type= picks the kind.
        q = request.query_params
        kind = q.get("type") if q.get("type") in FEEDBACK_KINDS else "bug"
        return render(
            "feedback.html",
            user=session_user(request),
            nav="feedback",
            kinds=FEEDBACK_KINDS,
            kind=kind,
            mod_version=(q.get("mod") or "").strip()[:MAX_FEEDBACK_VERSION_LEN],
            mc_version=(q.get("mc") or "").strip()[:MAX_FEEDBACK_VERSION_LEN],
            max_message=MAX_FEEDBACK_LEN,
            max_version=MAX_FEEDBACK_VERSION_LEN,
            max_contact=MAX_CONTACT_LEN,
            github=GITHUB_URL,
        )

    @app.get("/admin/feedback", response_class=HTMLResponse)
    def admin_feedback_page(request: Request) -> HTMLResponse:
        user = session_user(request)
        if user is None or not user.is_admin:
            return not_found_page(request)
        status = request.query_params.get("status") or "open"
        if status not in FEEDBACK_STATUSES:
            status = "open"
        n_open, n_resolved = store.feedback_counts()
        return render(
            "admin_feedback.html",
            user=user,
            nav="admin",
            items=[feedback_api(r) for r in store.list_feedback(status)],
            show=status,
            counts={"open": n_open, "resolved": n_resolved, "all": n_open + n_resolved},
            kinds=FEEDBACK_KINDS,
            admin_tab="feedback",
            tab_counts=admin_counts(),
        )

    def admin_counts() -> dict[str, int]:
        """What waits in each tab of the admin pages."""
        return {
            "applications": store.status_counts("applications").get("pending", 0),
            "storage": store.status_counts("storage_requests").get("pending", 0),
            "reports": store.status_counts("reports").get("open", 0),
            "feedback": store.feedback_counts()[0],
            "whitelist": store.whitelist_count(),
        }

    ADMIN_TABS = ("applications", "storage", "reports", "whitelist", "badges", "users")

    @app.get("/admin", response_class=HTMLResponse)
    def admin_page(request: Request) -> HTMLResponse:
        user = session_user(request)
        if user is None or not user.is_admin:
            return not_found_page(request)
        q = request.query_params
        tab = q.get("tab") if q.get("tab") in ADMIN_TABS else "applications"
        ctx: dict[str, Any] = {}
        if tab in ("applications", "storage", "reports"):
            statuses = REPORT_STATUSES if tab == "reports" else DECISIONS
            status = q.get("status") if q.get("status") in (*statuses, "all") else statuses[0]
            table = {"applications": "applications", "storage": "storage_requests", "reports": "reports"}[tab]
            to_api = {"applications": application_api, "storage": storage_api, "reports": report_api}[tab]
            counts = store.status_counts(table)
            ctx.update(show=status, statuses=statuses, counts={**counts, "all": sum(counts.values())},
                       items=[to_api(r, True) for r in store.list_rows(table, status)])
        elif tab == "whitelist":
            ctx.update(entries=[whitelist_api(r) for r in store.whitelist_entries()])
        elif tab == "badges":
            ctx.update(holders=[{"uuid": r["uuid"], "name": r["name"], "badges": badge_list(r["badges"])}
                                for r in store.badge_holders()])
        else:
            query = (q.get("q") or "").strip()[:MAX_QUERY_LEN]
            raw_page = q.get("page") or "1"
            page = int(raw_page) if raw_page.isdigit() and 0 < int(raw_page) <= 100000 else 1
            rows, total = store.list_users(query, page, ADMIN_PAGE_SIZE)
            ctx.update(users=[user_admin_api(r) for r in rows], q=query, page=page, total=total,
                       pages=max(1, -(-total // ADMIN_PAGE_SIZE)), default_quota=settings.quota_bytes)
        return render(
            "admin.html",
            user=user,
            nav="admin",
            admin_tab=tab,
            tab_counts=admin_counts(),
            report_reasons=REPORT_REASONS,
            enforced=settings.whitelist_enforced,
            **ctx,
        )

    @app.get("/apply", response_class=HTMLResponse)
    def apply_page(request: Request) -> HTMLResponse:
        # Where the mod's not_whitelisted answer points. Logged out, it explains and says how to log in.
        user = session_user(request)
        application = None
        if user is not None:
            row = store.latest_row("applications", user.uuid)
            application = application_api(row) if row else None
        return render(
            "apply.html",
            user=user,
            nav="apply",
            application=application,
            has_data=user is not None and (store.used_bytes(user.uuid) > 0),
            max_answer=MAX_APPLICATION_LEN,
            max_contact=MAX_CONTACT_LEN,
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
