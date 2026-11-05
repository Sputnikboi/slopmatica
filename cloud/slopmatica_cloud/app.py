"""Slopmatica Cloud: per-Minecraft-account storage for schematics (.slopmatic, .nbt, .litematic, .schem).

Identity comes from Mojang's session server (the same handshake a Minecraft
server does), so there are no passwords. See README.md for the API.
"""

from __future__ import annotations

import base64
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
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zlib
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

from . import __version__ as VERSION, convert, images, model3d, nbt, textfilter

log = logging.getLogger("slopmatica_cloud")

PKG_DIR = Path(__file__).resolve().parent
CLOUD_DIR = PKG_DIR.parent

DAY = 86400
USER_AGENT = f"SlopmaticaCloud/{VERSION} (+https://github.com/Sputnikboi/slopmatica)"
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
BOARD_SORTS = ("top", "hot", "new", "downloads")
MAX_BOARD_LIMIT = 100  # posts per page the API gives at most (?limit=)
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

# ---- settings profiles (the mod's named sets of settings)
PROFILE_LIMIT = 6  # per account; admins have no limit
MAX_PROFILE_NAME_LEN = 40
MAX_PROFILE_SETTINGS = 64 * 1024  # bytes of the settings object as compact JSON
PROFILE_ID_RE = POST_ID_RE  # same shape as post ids

# ---- telemetry (the mod's opt-in usage reports)
MAX_TELEMETRY_BYTES = 32 * 1024
TELEMETRY_WINDOW = 15 * 60  # seconds over which reports are counted for the rate limits
TELEMETRY_IP_LIMIT = 60  # reports per address per window (the mod sends up to 20 queued ones at once)
TELEMETRY_INSTALL_LIMIT = 30  # reports per install per window
TELEMETRY_KEEP_DAYS = 180  # older reports are deleted at startup
SOURCE_RE = re.compile(r"^(?:local|cloud|shared|board:[A-Za-z0-9_-]{1,32})$")
SETTING_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]{0,63}$")
# A Quick Menu action: a built-in id, or a setting toggle "toggle:<setting>:<value>|<value>...".
QUICK_ACTION_RE = re.compile(r"^(?:[a-z][a-z0-9_]{0,39}|toggle:[A-Za-z][A-Za-z0-9_]{0,63}:[A-Za-z0-9_.|-]{0,200})$")

# ---- identity: display names, avatars, Discord
MIN_DISPLAY_NAME_LEN = 3
MAX_DISPLAY_NAME_LEN = 24
DISPLAY_TAG_RE = re.compile(r"^[A-Za-z0-9]{3,5}$")
TAG_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # random tags: 4 of these (no 0/O or 1/I)
MAX_AVATAR_BYTES = 256 * 1024
MAX_AVATAR_SIDE = 512
AVATAR_OUT_SIDE = 256  # what the account page's crop step sends
AVATAR_TYPES = {"png": "image/png", "jpeg": "image/jpeg", "webp": "image/webp"}
USER_CARD_MAX_AGE = 300  # seconds a browser may reuse a name's hover card
SKIN_TTL = DAY  # a cached skin is fetched again after this
SKIN_RETRY = 600  # seconds before trying Mojang again after a failed fetch
SKIN_MAX_BYTES = 64 * 1024
DISCORD_AUTHORIZE = "https://discord.com/oauth2/authorize"
DISCORD_TOKEN = "https://discord.com/api/oauth2/token"
DISCORD_ME = "https://discord.com/api/users/@me"
DISCORD_STATE_TTL = 600

# ---- board: authors, links, versions, downloads, complexity
MAX_COAUTHORS = 10
MAX_AUTHOR_NAME_LEN = 40
MAX_LINKS = 4
MAX_LINK_LEN = 300
MAX_VERSION_LABEL_LEN = 60
VERSION_DIR_RE = re.compile(r"^v(\d{1,6})$")  # posts/<id>/v<n>/: an earlier version's snapshot, thumbnail and 3D cache
# Complexity by distinct block types plus entity types: (key, label, exclusive upper bound or None). Fixed for
# now; complexity_classes() is where they would come from the distribution of real uploads (as planned for size).
COMPLEXITY_CLASSES: tuple[tuple[str, str, int | None], ...] = (
    ("simple", "Simple", 10),
    ("moderate", "Moderate", 30),
    ("complex", "Complex", 80),
    ("very-complex", "Very complex", None),
)
COMPLEXITY_LABELS = {key: label for key, label, _ in COMPLEXITY_CLASSES}

# ---- 0.10.0: listings, favourites, storage accounting, feedback attachments
# My listings (/listings, GET /api/listings): the sort keys and whether they sort high to low by default.
LISTING_SORTS: dict[str, bool] = {
    "created": True, "updated": True, "title": False, "visibility": False, "score": True, "ups": True, "downs": True,
    "downloads": True, "favorites": True, "saves": True, "versions": True, "size": True,
}
SITE_USAGE_TTL = 30  # seconds a measured total of the data directory is trusted (plus what was written since)
FEEDBACK_DIR = "feedback"  # <data>/feedback/<id>/log or schematic: a bug report's attachments
FEEDBACK_ATTACHMENTS: dict[str, str] = {"log": "Game log", "schematic": "Schematic"}
MAX_FEEDBACK_LOG = 5 * 1024 * 1024
MAX_FEEDBACK_SCHEMATIC = 20 * 1024 * 1024
MAX_FEEDBACK_JSON = 64 * 1024  # a feedback body without attachments
MAX_LOG_TEXT = 64 * 1024 * 1024  # a gzipped log may unpack to at most this much (checked while reading it)
FEEDBACK_ATTACHMENT_DAYS = 30  # attachments of reports resolved longer ago are deleted at startup
STORAGE_TOP = 15  # rows in each list of the admin page's Site storage tab

# ---- 0.12.0: groups, and credited authors on posts
GROUP_ID_RE = POST_ID_RE  # same shape as post ids
GROUP_VISIBILITIES: dict[str, str] = {
    "public": "Listed in the groups directory. Anyone can see the group page.",
    "unlisted": "Not listed. Anyone with the link can see the group page.",
    "private": "Members only. Everyone else gets a 404.",
}
JOIN_POLICIES: dict[str, str] = {
    "open": "Anyone logged in can join.",
    "request": "Players ask to join; admins and moderators approve or deny.",
    "invite": "Only admins and moderators (or members allowed to invite) add people.",
}
POST_POLICIES: dict[str, str] = {
    "members": "Members who may post (see member permissions), moderators and admins.",
    "moderators": "Moderators and admins.",
    "closed": "Admins only.",
}
POST_VISIBILITY_RULES: dict[str, str] = {
    "group": "Builds posted here are seen by the group's members only, whatever their own visibility.",
    "any": "The poster picks public, unlisted or private as usual; public builds are on the board too.",
}
GROUP_ROLES = ("admin", "moderator", "member")  # in rank order
MANAGER_ROLES = frozenset({"admin", "moderator"})
MEMBER_PERMS: dict[str, str] = {"post": "Post builds", "invite": "Add people", "download": "Download builds"}
DEFAULT_MEMBER_PERMS: dict[str, bool] = {"post": True, "invite": False, "download": True}
SLUG_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")
MAX_SLUG_LEN = 40
MAX_GROUP_NAME_LEN = 60
MAX_GROUP_DESCRIPTION_LEN = 2000
MAX_JOIN_MESSAGE_LEN = 500
MAX_CREDIT_LEN = 60
MAX_FOLDER_DEPTH = 8  # folders inside a group: at most this deep
MAX_FOLDER_NAME_LEN = 60
GROUPS_PER_USER = 10  # groups an account may own; admins have no limit
GROUP_CREATE_LIMIT = 5  # new groups per account per LOGIN_WINDOW, counted in login_failures
GROUP_REQUEST_LIMIT = 10  # join requests per account per LOGIN_WINDOW
GROUP_ADD_LIMIT = 30  # players added or banned by name per account per LOGIN_WINDOW (each may be a Mojang lookup)


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
    # Skins for avatars: the profile (+ "/<uuid without dashes>") and the hosts a skin URL may point at.
    mojang_session_profile_url: str = "https://sessionserver.mojang.com/session/minecraft/profile"
    skin_hosts: frozenset[str] = frozenset({"textures.minecraft.net"})
    skin_timeout: float = 5.0
    # Discord account linking (OAuth2); off unless all three are set.
    discord_client_id: str = ""
    discord_client_secret: str = ""
    discord_redirect: str = ""  # the callback URL registered with Discord: <site>/account/discord/callback
    # Everything the site stores together (files, board, releases, pictures, feedback, the database).
    site_quota_bytes: int = 100 * 1024 ** 3

    @property
    def discord_enabled(self) -> bool:
        return bool(self.discord_client_id and self.discord_client_secret and self.discord_redirect)

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
        if env.get("SLOPMATICA_SITE_QUOTA_BYTES"):
            kw["site_quota_bytes"] = int(env["SLOPMATICA_SITE_QUOTA_BYTES"])
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
        if env.get("SLOPMATICA_MOJANG_SESSION_PROFILE"):
            kw["mojang_session_profile_url"] = env["SLOPMATICA_MOJANG_SESSION_PROFILE"]
        for key, attr in (("SLOPMATICA_DISCORD_CLIENT_ID", "discord_client_id"),
                          ("SLOPMATICA_DISCORD_CLIENT_SECRET", "discord_client_secret"),
                          ("SLOPMATICA_DISCORD_REDIRECT", "discord_redirect")):
            if env.get(key):
                kw[attr] = env[key].strip()
        return cls(**kw)


# --------------------------------------------------------------------------- errors


class ApiError(Exception):
    def __init__(self, status: int, error: str, **extra: Any) -> None:
        super().__init__(error)
        self.status = status
        self.payload = {"error": error, **extra}


# The formats the Slopmatica mod reads: its own, vanilla structure files, Litematica's, WorldEdit's (Sponge).
SCHEMATIC_EXTENSIONS = (".slopmatic", ".nbt", ".litematic", ".schem")


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


def complexity_classes() -> tuple[tuple[str, str, int | None], ...]:
    """The complexity thresholds. Fixed for now; this is the one place to derive them from the distribution of
    posts.distinct_count later (posts store the raw count, and the class is worked out when read)."""
    return COMPLEXITY_CLASSES


def complexity_class(distinct: int | None) -> str | None:
    """The complexity category of a snapshot with distinct block and entity types, None if unknown."""
    if distinct is None:
        return None
    for key, _, below in complexity_classes():
        if below is None or distinct < below:
            return key
    raise AssertionError("unreachable")


def complexity_sql(column: str) -> str:
    """complexity_class() as an SQL expression (the board's filter)."""
    classes = complexity_classes()
    cases = " ".join(f"WHEN {column} < {int(below)} THEN '{key}'" for key, _, below in classes if below is not None)
    return f"CASE WHEN {column} IS NULL THEN NULL {cases} ELSE '{classes[-1][0]}' END"


def link_domain(url: str) -> str:
    """The host a post's link points at, as shown next to it (without www.)."""
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    return host.removeprefix("www.")


# Telemetry summaries group free-form report fields into a few buckets.
MEMORY_BUCKETS = ("under 2 GB", "2 to 4 GB", "4 to 8 GB", "8 to 16 GB", "16 GB or more", "unknown")


def memory_bucket(mb: int | None) -> str:
    """The game's maximum heap (maxMemoryMb) as a bucket."""
    if mb is None:
        return "unknown"
    for limit, name in zip((2048, 4096, 8192, 16384), MEMORY_BUCKETS):
        if mb < limit:
            return name
    return MEMORY_BUCKETS[4]


def os_family(os_name: str | None) -> str:
    """'Windows 11 10.0' -> 'Windows 11', 'Mac OS X 14.5' -> 'macOS', 'Linux 6.8.0-45' -> 'Linux'."""
    words = (os_name or "").split()
    if not words:
        return "unknown"
    low = words[0].lower()
    if low == "windows":
        return " ".join(words[:2])
    if low in ("mac", "macos", "darwin"):
        return "macOS"
    return words[0]


def gpu_vendor(vendor: str | None) -> str:
    """A GPU vendor name as reported by the driver, folded to the usual few."""
    low = (vendor or "").lower()
    for needle, name in (("nvidia", "NVIDIA"), ("amd", "AMD"), ("ati ", "AMD"), ("advanced micro", "AMD"),
                         ("intel", "Intel"), ("apple", "Apple"), ("mesa", "Mesa"), ("microsoft", "Microsoft")):
        if needle in low + " ":
            return name
    return (vendor or "unknown").strip()[:40] or "unknown"


# --------------------------------------------------------------------------- storage


@dataclass
class User:
    uuid: str
    name: str
    is_admin: bool
    quota_bytes: int | None = None  # a per-account quota set by an admin; None: the default (admins: unlimited)
    display_name: str = ""  # shown on the site; the Minecraft name until the player picks another
    display_tag: str | None = None  # tells apart players with the same display name; None for its first holder
    avatar: str | None = None  # a custom icon's type (AVATAR_TYPES), None for the Minecraft face
    avatar_version: int = 0  # changes with the icon, for cache-busting URLs
    discord_id: str | None = None
    discord_name: str | None = None

    @property
    def shown(self) -> str:
        """The name on public pages (no tag)."""
        return self.display_name or self.name

    @property
    def tagged(self) -> str:
        """The name with its tag, for the player and admins."""
        return self.shown + (f"#{self.display_tag}" if self.display_tag else "")

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "User":
        return cls(
            r["uuid"], r["name"], bool(r["is_admin"]), r["quota_bytes"], r["display_name"] or "", r["display_tag"],
            r["avatar"], r["avatar_version"] or 0, r["discord_id"], r["discord_name"],
        )


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
    listing_post: str | None = None  # the owner's own board post this file was posted from (files.listing_post)
    reference: bool = False  # saved from the board and still a post's snapshot byte for byte: costs the saver nothing
    listing: dict[str, Any] | None = None  # filled by Store.attach_listings

    @property
    def billed(self) -> int:
        """Bytes the file counts toward its owner's quota."""
        return 0 if self.reference else self.size

    def api(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "size": self.size,
            "sha256": self.sha256,
            "modified": self.modified,
            "hasThumbnail": self.has_thumb,
            "source": self.source.api() if self.source else None,
            "listing": self.listing,
            "reference": self.reference,
        }

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "FileRow":
        source = None
        if r["source_post"]:
            # the author's current name if they have logged in since, else the name when it was saved
            name = r["source_author_now"] or r["source_author_name"] or ""
            source = FileSource(r["source_post"], r["source_title"] or "", r["source_author_uuid"] or "", name)
        return cls(r["path"], r["size"], r["sha256"], r["modified"], bool(r["has_thumb"]), source,
                   r["listing_post"], bool(r["is_ref"]))


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
    author_display: str = ""  # the author's display name (no tag)
    author_avatar_v: int = 0
    version: int = 1  # the current snapshot's version number; earlier ones are in post_versions
    version_label: str = ""
    mc: str | None = None  # the Minecraft version the current snapshot is for
    distinct_count: int | None = None  # distinct block and entity types, None if unknown
    links: list[str] = field(default_factory=list)
    downloads: int = 0
    version_count: int = 1
    coauthors: list["Coauthor"] = field(default_factory=list)  # filled by Store.attach_coauthors
    favorites: int = 0  # accounts that favourited it
    saves: int = 0  # accounts (not the author) that added it to their cloud
    total_bytes: int = 0  # every version's snapshot together (what the author's quota counts for it)
    credit: str | None = None  # a credited author shown instead of the uploader ("by Mojang · uploaded by ...")
    group_id: str | None = None  # the group it is posted in, if any
    group_slug: str | None = None
    group_name: str | None = None
    group_vis: str | None = None  # the group's visibility
    group_post_vis: str | None = None  # the group's post_visibility: 'group' makes the post members only
    group_hidden: int | None = None  # unix ms when a group admin or moderator hid it in the group
    group_folder: str = ""  # the folder it sits in inside its group, "a/b" ('' at the top)

    @property
    def group_only(self) -> bool:
        """Only the group's members (and the uploader and site admins) may see it, whatever its own visibility."""
        return self.group_id is not None and self.group_post_vis == "group"

    @property
    def score(self) -> int:
        return self.ups - self.downs

    @property
    def complexity(self) -> str | None:
        return complexity_class(self.distinct_count)

    @property
    def author_shown(self) -> str:
        return self.author_display or self.author_name

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "PostRow":
        dims = (r["size_x"], r["size_y"], r["size_z"])
        tags = set((r["tags"] or "").split(","))
        try:
            links = [u for u in json.loads(r["links"] or "[]") if isinstance(u, str)]
        except ValueError:
            links = []
        return cls(
            r["id"], r["uuid"], r["author_name"], r["title"], r["description"], r["visibility"],
            r["source_path"], r["filename"], r["format"], r["size"], r["sha256"], bool(r["has_thumb"]),
            dims if None not in dims else None, r["block_count"], r["created"], r["updated"],
            r["ups"], r["downs"], [t for t in TAGS if t in tags], r["size_class"],
            badge_list(r["author_badges"]), r["hidden"], r["hidden_reason"] or "",
            r["author_display"] or "", r["author_avatar_v"] or 0, r["version"], r["version_label"] or "",
            r["mc_version"], r["distinct_count"], links, r["downloads"], r["version_count"],
            favorites=r["favorites"], saves=r["saves"], total_bytes=r["total_bytes"], credit=r["credit"],
            group_id=r["group_id"], group_slug=r["group_slug"], group_name=r["group_name"], group_vis=r["group_vis"],
            group_post_vis=r["group_post_vis"], group_hidden=r["group_hidden"], group_folder=r["group_folder"] or "",
        )


@dataclass
class Coauthor:
    """Someone else credited on a post: an account here (uuid set) or a name without an account."""
    uuid: str | None
    name: str  # the account's Minecraft name, or the free-text name
    display: str  # the account's display name now, else name

    def api(self) -> dict[str, Any]:
        return {"uuid": self.uuid, "name": self.name, "displayName": self.display}


@dataclass
class VersionRow:
    """One snapshot of a post: the current one (from posts) or an earlier one (post_versions)."""
    num: int
    label: str
    mc: str | None
    filename: str
    format: str
    size: int
    sha256: str
    has_thumb: bool
    dims: tuple[int, int, int] | None
    block_count: int | None
    distinct_count: int | None
    created: int  # unix ms
    current: bool

    @property
    def size_class(self) -> str | None:
        return size_class(self.block_count)

    @property
    def complexity(self) -> str | None:
        return complexity_class(self.distinct_count)

    def api(self) -> dict[str, Any]:
        return {
            "version": self.num, "label": self.label, "mc": self.mc, "filename": self.filename, "format": self.format,
            "size": self.size, "sha256": self.sha256, "hasThumbnail": self.has_thumb,
            "dimensions": list(self.dims) if self.dims else None, "blockCount": self.block_count,
            "sizeClass": self.size_class, "distinctTypes": self.distinct_count, "complexity": self.complexity,
            "created": self.created, "current": self.current,
        }

    @classmethod
    def of_post(cls, p: PostRow) -> "VersionRow":
        return cls(p.version, p.version_label, p.mc, p.filename, p.format, p.size, p.sha256, p.has_thumb, p.dims,
                   p.block_count, p.distinct_count, p.updated, True)

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "VersionRow":
        dims = (r["size_x"], r["size_y"], r["size_z"])
        return cls(r["num"], r["label"] or "", r["mc_version"], r["filename"], r["format"], r["size"], r["sha256"],
                   bool(r["has_thumb"]), dims if None not in dims else None, r["block_count"], r["distinct_count"],
                   r["created"], False)


def parse_perms(text: str | None) -> dict[str, bool]:
    """A stored permission object (JSON) -> its known keys with true/false values."""
    try:
        raw = json.loads(text) if text else {}
    except ValueError:
        return {}
    return {k: v for k, v in raw.items() if k in MEMBER_PERMS and isinstance(v, bool)} if isinstance(raw, dict) else {}


def slugify(name: str) -> str:
    """A group's name as a slug: lowercase ASCII letters and digits, runs of anything else as one dash."""
    folded = unicodedata.normalize("NFKD", name.casefold()).encode("ascii", "ignore").decode()  # é -> e
    return re.sub(r"[^a-z0-9]+", "-", folded).strip("-")[:MAX_SLUG_LEN].strip("-") or "group"


@dataclass
class GroupRow:
    id: str
    slug: str
    name: str
    description: str
    owner: str  # uuid; always one of its admins
    owner_name: str
    owner_display: str
    visibility: str  # GROUP_VISIBILITIES
    join_policy: str  # JOIN_POLICIES
    post_policy: str  # POST_POLICIES
    post_visibility: str  # POST_VISIBILITY_RULES
    member_perms: dict[str, bool]  # defaults for plain members, over DEFAULT_MEMBER_PERMS
    created: int  # unix ms
    updated: int
    members: int = 0
    builds: int = 0  # posts in it that neither a site admin nor the group hid
    role: str | None = None  # the viewer's role, when listed for a viewer (Store.groups_of)

    @property
    def owner_shown(self) -> str:
        return self.owner_display or self.owner_name

    @property
    def perms(self) -> dict[str, bool]:
        """What plain members may do by default: member_perms over DEFAULT_MEMBER_PERMS."""
        return {**DEFAULT_MEMBER_PERMS, **self.member_perms}

    @classmethod
    def from_row(cls, r: sqlite3.Row) -> "GroupRow":
        return cls(
            r["id"], r["slug"], r["name"], r["description"], r["owner"], r["owner_name"] or "", r["owner_display"] or "",
            r["visibility"], r["join_policy"], r["post_policy"], r["post_visibility"], parse_perms(r["member_perms"]),
            r["created"], r["updated"], r["member_count"], r["build_count"],
            r["my_role"] if "my_role" in r.keys() else None,
        )


@dataclass
class Standing:
    """Someone's place in a group: their role (None if not a member) and what they may do there. Site admins may see
    and manage every group as its admins can."""
    group: GroupRow
    uuid: str | None
    role: str | None = None
    override: dict[str, bool] | None = None  # the member's own permissions, over the group's defaults
    site_admin: bool = False
    banned: bool = False
    requested: bool = False  # has a pending join request

    @property
    def member(self) -> bool:
        return self.role is not None

    @property
    def admin(self) -> bool:
        return self.role == "admin" or self.site_admin

    @property
    def manager(self) -> bool:
        """A group admin or moderator (or a site admin)."""
        return self.role in MANAGER_ROLES or self.site_admin

    @property
    def owner(self) -> bool:
        return self.uuid is not None and self.uuid == self.group.owner

    @property
    def can_see(self) -> bool:
        return self.group.visibility != "private" or self.member or self.site_admin

    @property
    def perms(self) -> dict[str, bool]:
        if self.manager:
            return {k: True for k in MEMBER_PERMS}
        if not self.member:
            return {k: False for k in MEMBER_PERMS}
        return {**self.group.perms, **(self.override or {})}

    def may(self, perm: str) -> bool:
        return self.perms[perm]

    @property
    def can_post(self) -> bool:
        policy = self.group.post_policy
        if policy == "closed":
            return self.admin
        if policy == "moderators":
            return self.manager
        return self.may("post")


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
    # 0.8.0: settings profiles, telemetry, display names, avatars, Discord, and on the board co-authors, links,
    # versions, download counts and complexity. Store.fill_display_names runs right after, in the same transaction.
    6: """
ALTER TABLE users ADD COLUMN display_name TEXT;     -- filled with the Minecraft name, then the player's choice
ALTER TABLE users ADD COLUMN display_key TEXT;      -- display_name case-folded (unique together with the tag)
ALTER TABLE users ADD COLUMN display_tag TEXT;      -- NULL for the first holder of a name, else 3-5 letters or digits
ALTER TABLE users ADD COLUMN display_custom INTEGER NOT NULL DEFAULT 0;  -- 1: chosen (else it follows Minecraft renames)
ALTER TABLE users ADD COLUMN avatar TEXT;           -- NULL: the Minecraft face; 'png' | 'jpeg': users/<uuid>/avatar.<type>
ALTER TABLE users ADD COLUMN avatar_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN discord_id TEXT;
ALTER TABLE users ADD COLUMN discord_name TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS users_discord ON users(discord_id) WHERE discord_id IS NOT NULL;
ALTER TABLE posts ADD COLUMN version INTEGER NOT NULL DEFAULT 1;  -- the current snapshot's number
ALTER TABLE posts ADD COLUMN version_label TEXT NOT NULL DEFAULT '';
ALTER TABLE posts ADD COLUMN mc_version TEXT;       -- the Minecraft version the current snapshot is for
ALTER TABLE posts ADD COLUMN distinct_count INTEGER;  -- distinct block + entity types, NULL until read
ALTER TABLE posts ADD COLUMN links TEXT NOT NULL DEFAULT '[]';  -- JSON list of http(s) URLs
ALTER TABLE posts ADD COLUMN downloads INTEGER NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS post_versions (       -- earlier snapshots, in posts/<id>/v<num>/
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    num INTEGER NOT NULL,
    label TEXT NOT NULL DEFAULT '',
    mc_version TEXT,
    filename TEXT NOT NULL,
    format TEXT NOT NULL,
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    has_thumb INTEGER NOT NULL DEFAULT 0,
    size_x INTEGER,
    size_y INTEGER,
    size_z INTEGER,
    block_count INTEGER,
    distinct_count INTEGER,
    created INTEGER NOT NULL,      -- unix ms, when this snapshot was taken
    PRIMARY KEY (post_id, num)
);
CREATE INDEX IF NOT EXISTS post_versions_mc ON post_versions(mc_version);
CREATE TABLE IF NOT EXISTS post_authors (        -- co-authors besides the owner, in order
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    pos INTEGER NOT NULL,
    uuid TEXT,                     -- an account here, NULL for a name without one
    name TEXT NOT NULL,            -- the Minecraft name when added, or the free-text name
    PRIMARY KEY (post_id, pos)
);
CREATE INDEX IF NOT EXISTS post_authors_uuid ON post_authors(uuid);
CREATE TABLE IF NOT EXISTS post_downloads (      -- who has been counted in posts.downloads
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    who TEXT NOT NULL,             -- 'u:<uuid>', or 'ip:<day>:<keyed hash of the address>' when logged out
    at INTEGER NOT NULL,           -- unix s
    PRIMARY KEY (post_id, who)
);
CREATE TABLE IF NOT EXISTS profiles (            -- the mod's settings profiles
    id TEXT PRIMARY KEY,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    name TEXT NOT NULL,
    settings TEXT NOT NULL,        -- a JSON object
    created INTEGER NOT NULL,      -- unix s
    updated INTEGER NOT NULL       -- unix s
);
CREATE INDEX IF NOT EXISTS profiles_uuid ON profiles(uuid, created);
CREATE TABLE IF NOT EXISTS telemetry (           -- the mod's usage reports; nothing about the player
    id INTEGER PRIMARY KEY,
    received INTEGER NOT NULL,     -- unix s
    install TEXT NOT NULL,         -- sha256 of the install's random id
    mod_version TEXT,
    mc_version TEXT,
    loader TEXT,
    started INTEGER,               -- unix s, as the mod reports them
    ended INTEGER,
    os TEXT,
    cpu_cores INTEGER,
    max_memory_mb INTEGER,
    gpu TEXT,
    gpu_vendor TEXT,
    backend TEXT,
    render_distance INTEGER,
    width INTEGER,
    height INTEGER,
    frames INTEGER,
    avg_ms REAL,
    p50_ms REAL,
    p99_ms REAL,
    over33_ms INTEGER,
    max_sections INTEGER,
    schematics TEXT NOT NULL DEFAULT '[]',        -- JSON list; board:<id> only for public posts
    settings_changed TEXT NOT NULL DEFAULT '[]',  -- JSON list of setting names
    keys TEXT NOT NULL DEFAULT '{}'               -- JSON {key name: uses}
);
CREATE INDEX IF NOT EXISTS telemetry_received ON telemetry(received);
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
""",
    # 0.10.0: which board post a cloud file is the source of, favourites, "Add to my cloud" counts, and bug
    # report attachments. Existing files are linked to their owner's posts by path, then by content.
    7: """
ALTER TABLE files ADD COLUMN listing_post TEXT;  -- the owner's post this file was posted (or a version added) from
UPDATE files SET listing_post = (
    SELECT p.id FROM posts p WHERE p.uuid = files.uuid AND p.source_path = files.path ORDER BY p.created DESC, p.id LIMIT 1);
UPDATE files SET listing_post = (
    SELECT p.id FROM posts p WHERE p.uuid = files.uuid AND (p.sha256 = files.sha256
        OR EXISTS (SELECT 1 FROM post_versions v WHERE v.post_id = p.id AND v.sha256 = files.sha256))
    ORDER BY p.created DESC, p.id LIMIT 1)
WHERE listing_post IS NULL;
CREATE INDEX IF NOT EXISTS files_source_post ON files(source_post) WHERE source_post IS NOT NULL;
CREATE TABLE IF NOT EXISTS favorites (
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    created INTEGER NOT NULL,      -- unix ms
    PRIMARY KEY (post_id, uuid)
);
CREATE INDEX IF NOT EXISTS favorites_uuid ON favorites(uuid, created);
CREATE TABLE IF NOT EXISTS post_saves (          -- accounts (never the author) that added the post to their cloud
    post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL,
    at INTEGER NOT NULL,           -- unix s
    PRIMARY KEY (post_id, uuid)
);
INSERT OR IGNORE INTO post_saves(post_id, uuid, at)
    SELECT f.source_post, f.uuid, MIN(f.modified) / 1000 FROM files f JOIN posts p ON p.id = f.source_post
    WHERE f.uuid != p.uuid GROUP BY f.source_post, f.uuid;
CREATE TABLE IF NOT EXISTS feedback_files (      -- a report's attachments, in feedback/<id>/<kind>
    feedback_id INTEGER NOT NULL REFERENCES feedback(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('log', 'schematic')),
    filename TEXT NOT NULL,        -- the name it was sent with (one plain segment), for downloads
    size INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    created INTEGER NOT NULL,      -- unix ms
    PRIMARY KEY (feedback_id, kind)
);
""",
    # 0.11.0: the Quick Menu and action wheel layouts from usage reports (dropped before).
    8: """
ALTER TABLE telemetry ADD COLUMN quick TEXT NOT NULL DEFAULT '{}';
""",
    # 0.12.0: groups, and a credited author on posts
    9: """
CREATE TABLE IF NOT EXISTS groups (
    id TEXT PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,     -- /g/<slug>: from the name when made, changed only in the settings
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    owner TEXT NOT NULL,           -- uuid, always one of its admins
    visibility TEXT NOT NULL CHECK (visibility IN ('public', 'unlisted', 'private')),
    join_policy TEXT NOT NULL CHECK (join_policy IN ('open', 'request', 'invite')),
    post_policy TEXT NOT NULL CHECK (post_policy IN ('members', 'moderators', 'closed')),
    post_visibility TEXT NOT NULL CHECK (post_visibility IN ('group', 'any')),
    member_perms TEXT NOT NULL DEFAULT '{}',  -- JSON {post, invite, download}: plain members' defaults
    created INTEGER NOT NULL,      -- unix ms
    updated INTEGER NOT NULL       -- unix ms
);
CREATE INDEX IF NOT EXISTS groups_visibility ON groups(visibility);
CREATE TABLE IF NOT EXISTS group_members (
    group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL,            -- added by Minecraft name, so maybe without an account here yet
    name TEXT NOT NULL,            -- the Minecraft name when added
    role TEXT NOT NULL CHECK (role IN ('admin', 'moderator', 'member')),
    joined INTEGER NOT NULL,       -- unix ms
    perms TEXT,                    -- JSON: this member's own permissions over member_perms, NULL for none
    PRIMARY KEY (group_id, uuid)
);
CREATE INDEX IF NOT EXISTS group_members_uuid ON group_members(uuid);
CREATE TABLE IF NOT EXISTS group_requests (
    group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL REFERENCES users(uuid),
    message TEXT NOT NULL DEFAULT '',
    created INTEGER NOT NULL,      -- unix ms
    PRIMARY KEY (group_id, uuid)
);
CREATE TABLE IF NOT EXISTS group_bans (
    group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    uuid TEXT NOT NULL,
    name TEXT NOT NULL,            -- the Minecraft name when banned
    banned_by TEXT NOT NULL,       -- uuid
    created INTEGER NOT NULL,      -- unix ms
    PRIMARY KEY (group_id, uuid)
);
ALTER TABLE posts ADD COLUMN group_id TEXT REFERENCES groups(id) ON DELETE SET NULL;
ALTER TABLE posts ADD COLUMN group_hidden INTEGER;  -- unix ms when a group admin or moderator hid it in the group
ALTER TABLE posts ADD COLUMN credit TEXT;          -- a credited author shown instead of the uploader, NULL for none
CREATE INDEX IF NOT EXISTS posts_group ON posts(group_id) WHERE group_id IS NOT NULL;
""",
    # Folders inside a group: the path of the folder a post sits in there ('' at the top).
    10: """
ALTER TABLE posts ADD COLUMN group_folder TEXT NOT NULL DEFAULT '';
""",
}
SCHEMA_VERSION = max(MIGRATIONS)
# Created by Store.fill_display_names once every account has a display name (migration 6).
DISPLAY_INDEX = "CREATE UNIQUE INDEX IF NOT EXISTS users_display ON users(display_key, lower(COALESCE(display_tag, '')))"

POST_SELECT = """
SELECT p.*, u.name AS author_name, u.display_name AS author_display, u.avatar_version AS author_avatar_v,
    (SELECT COUNT(*) FROM votes v WHERE v.post_id = p.id AND v.value = 1) AS ups,
    (SELECT COUNT(*) FROM votes v WHERE v.post_id = p.id AND v.value = -1) AS downs,
    (SELECT group_concat(t.tag, ',') FROM post_tags t WHERE t.post_id = p.id) AS tags,
    (SELECT group_concat(b.badge, ',') FROM badges b WHERE b.uuid = p.uuid) AS author_badges,
    1 + (SELECT COUNT(*) FROM post_versions pv WHERE pv.post_id = p.id) AS version_count,
    p.size + (SELECT COALESCE(SUM(pv.size), 0) FROM post_versions pv WHERE pv.post_id = p.id) AS total_bytes,
    (SELECT COUNT(*) FROM favorites fv WHERE fv.post_id = p.id) AS favorites,
    (SELECT COUNT(*) FROM post_saves ps WHERE ps.post_id = p.id) AS saves,
    g.slug AS group_slug, g.name AS group_name, g.visibility AS group_vis, g.post_visibility AS group_post_vis
"""
POST_FROM = "FROM posts p JOIN users u ON u.uuid = p.uuid LEFT JOIN groups g ON g.id = p.group_id"
POST_SELECT += POST_FROM + "\n"
# A post (alias p) that is not in a members-only group: the only kind the board, profiles, search and counts list.
NOT_GROUP_ONLY_SQL = "NOT EXISTS (SELECT 1 FROM groups gx WHERE gx.id = p.group_id AND gx.post_visibility = 'group')"
# The group tab of list_posts: which of a group's posts (alias p, group g) the viewer is listed. Group posts follow
# can_view; besides, unlisted ones are listed to members only. :member, :manager and :admin are the viewer's standing.
def like_prefix(folder: str) -> str:
    """A LIKE pattern (ESCAPE '\\') for every folder below folder."""
    return folder.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"


GROUP_LISTED_SQL = """(p.uuid = :viewer OR :admin OR CASE WHEN g.post_visibility = 'group' THEN :member
    ELSE p.visibility = 'public' OR (p.visibility = 'unlisted' AND :member)
        OR (p.visibility = 'private' AND (EXISTS (SELECT 1 FROM post_access a WHERE a.post_id = p.id AND a.uuid = :viewer)
            OR (g.visibility = 'private' AND :member))) END)"""
GROUP_SELECT = """
SELECT g.*, u.name AS owner_name, u.display_name AS owner_display,
    (SELECT COUNT(*) FROM group_members m WHERE m.group_id = g.id) AS member_count,
    (SELECT COUNT(*) FROM posts p WHERE p.group_id = g.id AND p.hidden IS NULL AND p.group_hidden IS NULL) AS build_count
"""
GROUP_FROM = "FROM groups g LEFT JOIN users u ON u.uuid = g.owner"
# Bytes of an account's board snapshots, every version: the quota counts them all.
POST_BYTES_SQL = (
    "((SELECT COALESCE(SUM(size),0) FROM posts WHERE uuid={owner})"
    " + (SELECT COALESCE(SUM(pv.size),0) FROM post_versions pv JOIN posts pp ON pp.id = pv.post_id WHERE pp.uuid={owner}))"
)
# A file (alias f) saved from the board that is still byte for byte one of that post's snapshots (any version):
# a reference, which costs the saver nothing because the post's bytes count for its author. Once it no longer
# matches (the saver changed it; the author deleted that version or the post), it counts for the saver.
REFERENCE_SQL = (
    "(f.source_post IS NOT NULL AND (EXISTS (SELECT 1 FROM posts rp WHERE rp.id = f.source_post AND rp.sha256 = f.sha256)"
    " OR EXISTS (SELECT 1 FROM post_versions rv WHERE rv.post_id = f.source_post AND rv.sha256 = f.sha256)))"
)
# Bytes of an account's cloud files that count toward its quota (references don't).
FILE_BYTES_SQL = "(SELECT COALESCE(SUM(f.size),0) FROM files f WHERE f.uuid={owner} AND NOT " + REFERENCE_SQL + ")"
FILE_SELECT = f"""
SELECT f.*, a.name AS source_author_now, {REFERENCE_SQL} AS is_ref
FROM files f LEFT JOIN users a ON a.uuid = f.source_author_uuid
"""
FILE_COLUMNS = (
    "uuid", "path", "size", "sha256", "modified", "has_thumb",
    "source_post", "source_title", "source_author_uuid", "source_author_name", "listing_post",
)
BOARD_ORDER = {
    "top": "(ups - downs) DESC, p.created DESC, p.id",
    "new": "p.created DESC, p.id",
    "hot": "slop_hot(ups - downs, p.created, :now) DESC, p.created DESC, p.id",
    "downloads": "p.downloads DESC, (ups - downs) DESC, p.created DESC, p.id",
}


def _hot(score: int, created_ms: int, now_ms: int) -> float:
    """Score decayed by age: score / (hours + 2) ** 1.5."""
    hours = max(0, now_ms - created_ms) / 3_600_000
    return score / (hours + 2) ** 1.5


def _fold(value: Any) -> str:
    return value.casefold() if isinstance(value, str) else ""


def read_schematic(disk: Path, filename: str, max_volume: int, path: str = "") -> convert.Schematic:
    """Parse disk as the format filename's extension names (path: where it sat, for a vanilla structure file's chest
    markers). ValueError if it can't be read."""
    fmt = convert.format_of(filename)
    if fmt is None:
        raise convert.ConversionError("not a schematic file name")
    _, root = nbt.load(disk)
    return convert.read(root, fmt, filename[: -len(fmt) - 1], max_volume, "/" + path if path else "")


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
        self.skins_dir = self.root / "skins"  # <uuid>.png (the skin) and <uuid>.face.png, refreshed daily
        self.skins_dir.mkdir(exist_ok=True)
        self.feedback_root = self.root / FEEDBACK_DIR  # <id>/log and <id>/schematic: bug report attachments
        self.feedback_root.mkdir(exist_ok=True)
        # the data directory's measured size: (time.monotonic() when measured, bytes by category)
        self._site: tuple[float, dict[str, list[int]]] | None = None
        self._site_pending = 0  # bytes accepted by check_site since that measurement
        self.migrate()
        # keys anonymous downloads by address without storing the address
        self.ip_salt = self.meta_secret("ip_salt")
        self.prune()
        self.reconcile()
        self.reconcile_posts()
        self.reconcile_releases()
        self.reconcile_feedback_files()
        self.backfill_complexity()

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
                if target == 6:
                    self.fill_display_names()
                # single statements: executescript would commit the open transaction first
                self.db.execute(f"PRAGMA user_version = {target}")
                self.db.execute("COMMIT")
            except BaseException:
                if self.db.in_transaction:
                    self.db.execute("ROLLBACK")
                raise

    def fill_display_names(self) -> None:
        """Migration 6: every account's display name starts as its Minecraft name. Where several accounts
        share one (a stale row after renames), the oldest keeps it plain and the others get a tag."""
        rows = self.db.execute("SELECT uuid, name FROM users ORDER BY created, uuid").fetchall()
        for r in rows:
            name = r["name"]
            tag = None if not self._display_taken(name.casefold(), None, r["uuid"]) else self._free_tag(name.casefold())
            self.db.execute("UPDATE users SET display_name=?, display_key=?, display_tag=? WHERE uuid=?",
                            (name, name.casefold(), tag, r["uuid"]))
        self.db.execute(DISPLAY_INDEX)

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
            self.db.execute("DELETE FROM telemetry WHERE received < ?", (t - TELEMETRY_KEEP_DAYS * DAY,))
            # an address counts once per post and day: older keys are no longer needed (the counts stay)
            self.db.execute("DELETE FROM post_downloads WHERE who LIKE 'ip:%' AND at < ?", (t - 2 * DAY,))

    def meta_secret(self, key: str) -> bytes:
        """A random secret kept in the meta table, made on first use."""
        with self.lock:
            self.db.execute("INSERT OR IGNORE INTO meta(key, value) VALUES(?, ?)", (key, secrets.token_hex(32)))
            (value,) = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return bytes.fromhex(value)

    def backfill_complexity(self) -> None:
        """Posts and versions from before 0.8.0 have no distinct type count: read their snapshots once."""
        with self.lock:
            todo = [(r["id"], None, r["filename"]) for r in self.db.execute(
                "SELECT id, filename FROM posts WHERE distinct_count IS NULL AND block_count IS NOT NULL")]
            todo += [(r["post_id"], r["num"], r["filename"]) for r in self.db.execute(
                "SELECT post_id, num, filename FROM post_versions WHERE distinct_count IS NULL AND block_count IS NOT NULL")]
        for post_id, num, filename in todo:
            pdir = self.posts_root / post_id / (f"v{num}" if num is not None else "")
            try:
                n = read_schematic(self._under(pdir, filename), filename, self.settings.max_export_volume).distinct_types()
            except (OSError, ValueError, ApiError) as e:
                log.warning("could not count the block types of board post %s: %s", post_id, e)
                continue
            with self.lock:
                if num is None:
                    self.db.execute("UPDATE posts SET distinct_count=? WHERE id=? AND filename=?", (n, post_id, filename))
                else:
                    self.db.execute("UPDATE post_versions SET distinct_count=? WHERE post_id=? AND num=?", (n, post_id, num))
        if todo:
            log.info("counted the block types of %d board snapshots", len(todo))

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
                versions = {v["num"]: v for v in self.db.execute(
                    "SELECT num, filename, sha256, has_thumb FROM post_versions WHERE post_id=?", (post_id,))}
                for f in pdir.iterdir():
                    m = VERSION_DIR_RE.match(f.name)
                    if f.is_dir():
                        if m is None or int(m.group(1)) not in versions:
                            log.info("removing stale folder from board post %s: %s", post_id, f.name)
                            shutil.rmtree(f, ignore_errors=True)
                    elif f.name not in (r["filename"], POST_THUMB) and not f.name.startswith(cache):
                        log.info("removing stale file from board post %s: %s", post_id, f.name)
                        f.unlink(missing_ok=True)
                for num, v in versions.items():  # earlier versions: the same, inside v<num>/
                    vdir = pdir / f"v{num}"
                    if not (vdir / v["filename"]).is_file():
                        log.warning("removing version %d of board post %s: its snapshot is missing", num, post_id)
                        self.db.execute("DELETE FROM post_versions WHERE post_id=? AND num=?", (post_id, num))
                        shutil.rmtree(vdir, ignore_errors=True)
                        continue
                    vcache = f"{MODEL_CACHE_PREFIX}{v['sha256'][:16]}."
                    for f in vdir.iterdir():
                        if f.name not in (v["filename"], POST_THUMB) and not f.name.startswith(vcache):
                            (shutil.rmtree(f, ignore_errors=True) if f.is_dir() else f.unlink(missing_ok=True))
                    vthumb = (vdir / POST_THUMB).is_file()
                    if vthumb != bool(v["has_thumb"]):
                        self.db.execute("UPDATE post_versions SET has_thumb=? WHERE post_id=? AND num=?",
                                        (int(vthumb), post_id, num))
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
            old = self.db.execute("SELECT name, display_custom FROM users WHERE uuid=?", (uuid,)).fetchone()
            self.db.execute(
                """INSERT INTO users(uuid,name,is_admin,created,last_login,last_seen) VALUES(?,?,?,?,?,?)
                   ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, last_login=excluded.last_login,
                   last_seen=excluded.last_seen, is_admin=MAX(users.is_admin, excluded.is_admin)""",
                (uuid, name, int(admin_by_name), t, t, t),
            )
            if old is None or (not old["display_custom"] and old["name"] != name):
                # a new account, or a Minecraft rename that a display name nobody chose follows
                self.set_display(uuid, name, custom=False)
            self._seen[uuid] = t
            return self.get_user(uuid)  # type: ignore[return-value]

    def get_user(self, uuid: str) -> User | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM users WHERE uuid=?", (uuid,)).fetchone()
        return User.from_row(r) if r else None

    # ---- display names: unique by (case-folded name, tag); the first holder of a name needs no tag

    def _display_taken(self, key: str, tag: str | None, uuid: str) -> bool:
        """Whether another account than uuid has display name key (case-folded) with tag (None: untagged)."""
        return self.db.execute(
            "SELECT 1 FROM users WHERE display_key=? AND lower(COALESCE(display_tag, ''))=? AND uuid != ?",
            (key, (tag or "").lower(), uuid),
        ).fetchone() is not None

    def _free_tag(self, key: str) -> str:
        while True:
            tag = "".join(secrets.choice(TAG_ALPHABET) for _ in range(4))
            if not self._display_taken(key, tag, ""):
                return tag

    def set_display(self, uuid: str, name: str, tag: Any = ..., custom: bool = True) -> str | None:
        """Give the account a display name. tag ... (the default) picks one: none if nobody else uses the
        name plainly, else the account's current tag when only the case changes, else a random free one.
        An explicit tag (None: untagged) must be free with that name, or 409. Returns the tag."""
        key = name.casefold()
        with self.lock:
            cur = self.db.execute("SELECT display_key, display_tag FROM users WHERE uuid=?", (uuid,)).fetchone()
            if tag is ...:
                if cur is not None and cur["display_key"] == key:
                    tag = cur["display_tag"]
                else:
                    tag = None if not self._display_taken(key, None, uuid) else self._free_tag(key)
            elif self._display_taken(key, tag, uuid):
                raise ApiError(409, f"someone already uses the name {name}" + (f" with the tag {tag}" if tag else
                               " without a tag") + "; pick another tag", code="taken")
            self.db.execute(
                "UPDATE users SET display_name=?, display_key=?, display_tag=?, display_custom=? WHERE uuid=?",
                (name, key, tag, int(custom), uuid),
            )
        return tag

    def set_avatar(self, uuid: str, kind: str | None) -> None:
        with self.lock:
            self.db.execute("UPDATE users SET avatar=?, avatar_version=avatar_version + 1 WHERE uuid=?", (kind, uuid))

    def avatar_path(self, uuid: str, kind: str) -> Path:
        assert DASHED_UUID_RE.match(uuid) and kind in AVATAR_TYPES
        return self.root / "users" / uuid / f"avatar.{kind}"

    def known_player(self, uuid: str) -> bool:
        """An account here or a whitelisted player: the uuids whose skins may be fetched for avatars."""
        with self.lock:
            return self.db.execute(
                "SELECT 1 FROM users WHERE uuid=:u UNION SELECT 1 FROM whitelist WHERE uuid=:u", {"u": uuid}
            ).fetchone() is not None

    def set_discord(self, uuid: str, discord_id: str | None, discord_name: str | None) -> None:
        """409 if that Discord account is linked to another account already."""
        with self.lock:
            try:
                self.db.execute("UPDATE users SET discord_id=?, discord_name=? WHERE uuid=?", (discord_id, discord_name, uuid))
            except sqlite3.IntegrityError:
                raise ApiError(409, "that Discord account is already linked to another Slopmatica account")

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
        return self.attach_listings(uuid, [FileRow.from_row(r) for r in rows])

    def get_file(self, uuid: str, path: str) -> FileRow | None:
        with self.lock:
            r = self.db.execute(FILE_SELECT + " WHERE f.uuid=? AND f.path=?", (uuid, path)).fetchone()
        return self.attach_listings(uuid, [FileRow.from_row(r)])[0] if r else None

    def attach_listings(self, uuid: str, files: list[FileRow]) -> list[FileRow]:
        """Fill each file's listing: the owner's board post it was posted (or a version added) from, else one of
        the owner's posts with exactly its bytes (any version; the newest post wins). Posts that are gone don't count."""
        if not files:
            return files
        with self.lock:
            posts = {r["id"]: r for r in self.db.execute(
                "SELECT id, title, visibility, hidden, sha256 FROM posts WHERE uuid=? ORDER BY created DESC, id", (uuid,))}
            if not posts:
                return files
            by_sha: dict[str, str] = {}
            for r in posts.values():  # current snapshots first, newest post first
                by_sha.setdefault(r["sha256"], r["id"])
            for r in self.db.execute(
                """SELECT v.post_id, v.sha256 FROM post_versions v JOIN posts p ON p.id = v.post_id WHERE p.uuid=?
                   ORDER BY p.created DESC, p.id, v.num DESC""", (uuid,)):
                by_sha.setdefault(r["sha256"], r["post_id"])
        for f in files:
            pid = f.listing_post if f.listing_post in posts else by_sha.get(f.sha256)
            if pid is not None:
                r = posts[pid]
                f.listing = {"postId": pid, "title": r["title"], "visibility": r["visibility"], "hidden": r["hidden"] is not None}
        return files

    def set_listing(self, uuid: str, path: str, post_id: str) -> None:
        """Record that the account's file at path was posted (or a version added) as post_id."""
        with self.lock:
            self.db.execute("UPDATE files SET listing_post=? WHERE uuid=? AND path=?", (post_id, uuid, path))

    def used_bytes(self, uuid: str) -> int:
        """Bytes counted against the quota: the account's files (not references to board posts) plus its board
        snapshots, every version."""
        with self.lock:
            (n,) = self.db.execute(
                "SELECT " + FILE_BYTES_SQL.format(owner=":u") + " + " + POST_BYTES_SQL.format(owner=":u"), {"u": uuid},
            ).fetchone()
        return int(n)

    def post_usage(self, uuid: str) -> tuple[int, int]:
        """(number of posts, bytes of their snapshots, every version)."""
        with self.lock:
            n, size = self.db.execute(
                "SELECT (SELECT COUNT(*) FROM posts WHERE uuid=:u), " + POST_BYTES_SQL.format(owner=":u"), {"u": uuid}
            ).fetchone()
        return int(n), int(size)

    def put_file_row(self, uuid: str, row: FileRow) -> None:
        src = row.source
        with self.lock:
            self.db.execute(
                f"INSERT OR REPLACE INTO files({','.join(FILE_COLUMNS)}) VALUES({','.join('?' * len(FILE_COLUMNS))})",
                (uuid, row.path, row.size, row.sha256, row.modified, int(row.has_thumb),
                 src.post_id if src else None, src.title if src else None,
                 src.author_uuid if src else None, src.author_name if src else None, row.listing_post),
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
        return self.attach_coauthors([PostRow.from_row(r)])[0] if r else None

    def insert_post(
        self, values: dict[str, Any], allowed: list[tuple[str, str]], tags: list[str] = (),
        coauthors: list[tuple[str | None, str]] = (),
    ) -> None:
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.transaction():
            self.db.execute(f"INSERT INTO posts({cols}) VALUES({marks})", values)
            self._set_allowed(values["id"], allowed)
            self._set_tags(values["id"], tags)
            self._set_coauthors(values["id"], coauthors)

    def update_post(
        self, post_id: str, values: dict[str, Any], allowed: list[tuple[str, str]] | None = None,
        tags: list[str] | None = None, coauthors: list[tuple[str | None, str]] | None = None,
    ) -> None:
        with self.transaction():
            if values:
                sets = ", ".join(f"{k}=:{k}" for k in values)
                self.db.execute(f"UPDATE posts SET {sets} WHERE id=:id", {**values, "id": post_id})
            if allowed is not None:
                self._set_allowed(post_id, allowed)
            if tags is not None:
                self._set_tags(post_id, tags)
            if coauthors is not None:
                self._set_coauthors(post_id, coauthors)

    def _set_coauthors(self, post_id: str, coauthors: list[tuple[str | None, str]]) -> None:
        self.db.execute("DELETE FROM post_authors WHERE post_id=?", (post_id,))
        self.db.executemany("INSERT INTO post_authors(post_id, pos, uuid, name) VALUES(?,?,?,?)",
                            [(post_id, i, uuid, name) for i, (uuid, name) in enumerate(coauthors)])

    def attach_coauthors(self, posts: list[PostRow]) -> list[PostRow]:
        """Fill each post's coauthors (one query for the lot), with accounts' current display names."""
        if not posts:
            return posts
        marks = ",".join("?" * len(posts))
        with self.lock:
            rows = self.db.execute(
                f"""SELECT a.post_id, a.uuid, COALESCE(u.name, a.name) AS name, u.display_name FROM post_authors a
                    LEFT JOIN users u ON u.uuid = a.uuid WHERE a.post_id IN ({marks}) ORDER BY a.post_id, a.pos""",
                [p.id for p in posts],
            ).fetchall()
        by_post: dict[str, list[Coauthor]] = {}
        for r in rows:  # an account's current Minecraft and display names
            by_post.setdefault(r["post_id"], []).append(Coauthor(r["uuid"], r["name"], r["display_name"] or r["name"]))
        for p in posts:
            p.coauthors = by_post.get(p.id, [])
        return posts

    # ---- earlier versions of a post (posts/<id>/v<num>/)

    def list_versions(self, post_id: str) -> list[VersionRow]:
        """Earlier versions, newest first (the current one is the post itself)."""
        with self.lock:
            rows = self.db.execute("SELECT * FROM post_versions WHERE post_id=? ORDER BY num DESC", (post_id,)).fetchall()
        return [VersionRow.from_row(r) for r in rows]

    def get_version(self, post_id: str, num: int) -> VersionRow | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM post_versions WHERE post_id=? AND num=?", (post_id, num)).fetchone()
        return VersionRow.from_row(r) if r else None

    def archive_current(self, post: PostRow) -> None:
        """Record the post's current snapshot as an earlier version (its files are moved by the caller)."""
        dims = post.dims or (None, None, None)
        self.db.execute(
            """INSERT INTO post_versions(post_id, num, label, mc_version, filename, format, size, sha256, has_thumb,
                   size_x, size_y, size_z, block_count, distinct_count, created) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (post.id, post.version, post.version_label, post.mc, post.filename, post.format, post.size, post.sha256,
             int(post.has_thumb), *dims, post.block_count, post.distinct_count, post.updated),
        )

    def delete_version(self, post_id: str, num: int) -> bool:
        with self.lock:
            return self.db.execute("DELETE FROM post_versions WHERE post_id=? AND num=?", (post_id, num)).rowcount == 1

    def mc_versions(self) -> list[str]:
        """The Minecraft versions public posts are (or were) for, newest first: the board's filter."""
        with self.lock:
            rows = self.db.execute(
                f"""SELECT mc_version AS mc FROM posts p WHERE visibility='public' AND hidden IS NULL AND mc_version IS NOT NULL
                   AND {NOT_GROUP_ONLY_SQL}
                   UNION SELECT v.mc_version FROM post_versions v JOIN posts p ON p.id = v.post_id
                   WHERE p.visibility='public' AND p.hidden IS NULL AND v.mc_version IS NOT NULL AND {NOT_GROUP_ONLY_SQL}"""
            ).fetchall()
        return sorted((r["mc"] for r in rows), key=mc_version_key, reverse=True)

    # ---- download counts

    def count_post_download(self, post_id: str, who: str) -> bool:
        """Count a download or save of the post once per who. True if it counted."""
        with self.lock:
            cur = self.db.execute("INSERT OR IGNORE INTO post_downloads(post_id, who, at) VALUES(?,?,?)",
                                  (post_id, who, now_s()))
            if cur.rowcount != 1:
                return False
            self.db.execute("UPDATE posts SET downloads = downloads + 1 WHERE id=?", (post_id,))
            return True

    def count_save(self, post_id: str, uuid: str) -> None:
        """Record that uuid (not the post's author) added the post to their cloud: counted once per account."""
        with self.lock:
            self.db.execute("INSERT OR IGNORE INTO post_saves(post_id, uuid, at) VALUES(?,?,?)", (post_id, uuid, now_s()))

    # ---- favourites and listings

    def set_favorite(self, post_id: str, uuid: str, on: bool) -> None:
        with self.lock:
            if on:
                self.db.execute("INSERT OR IGNORE INTO favorites(post_id, uuid, created) VALUES(?,?,?)",
                                (post_id, uuid, int(time.time() * 1000)))
            else:
                self.db.execute("DELETE FROM favorites WHERE post_id=? AND uuid=?", (post_id, uuid))

    def favorite_ids(self, uuid: str, post_ids: list[str] | None = None) -> set[str]:
        """Which of post_ids (every post when None) uuid has favourited."""
        with self.lock:
            if post_ids is None:
                rows = self.db.execute("SELECT post_id FROM favorites WHERE uuid=?", (uuid,)).fetchall()
            elif not post_ids:
                return set()
            else:
                marks = ",".join("?" * len(post_ids))
                rows = self.db.execute(f"SELECT post_id FROM favorites WHERE uuid=? AND post_id IN ({marks})",
                                       [uuid, *post_ids]).fetchall()
        return {r["post_id"] for r in rows}

    def list_favorites(self, uuid: str) -> list[PostRow]:
        """The posts uuid favourited, most recently favourited first. A post that has become private, members only
        in a group, or hidden (unless it is the caller's own) is left out; a deleted one is gone with its favourites."""
        with self.lock:
            rows = self.db.execute(
                POST_SELECT + f""" JOIN favorites fv ON fv.post_id = p.id AND fv.uuid = :u
                   WHERE p.uuid = :u OR (p.visibility != 'private' AND p.hidden IS NULL AND {NOT_GROUP_ONLY_SQL})
                   ORDER BY fv.created DESC, p.id""", {"u": uuid},
            ).fetchall()
        return self.attach_coauthors([PostRow.from_row(r) for r in rows])

    def own_posts(self, uuid: str) -> list[PostRow]:
        """Every post of the account, newest first (at most Settings.max_posts_per_user)."""
        with self.lock:
            rows = self.db.execute(POST_SELECT + " WHERE p.uuid=? ORDER BY p.created DESC, p.id", (uuid,)).fetchall()
        return self.attach_coauthors([PostRow.from_row(r) for r in rows])

    # ---- groups: members (admin, moderator, member), join requests and bans

    def new_group_id(self) -> str:
        with self.lock:
            while True:
                group_id = secrets.token_urlsafe(8)[:POST_ID_LEN]
                if GROUP_ID_RE.match(group_id) and self.get_group(group_id) is None:
                    return group_id

    def get_group(self, group_id: str) -> GroupRow | None:
        with self.lock:
            r = self.db.execute(GROUP_SELECT + GROUP_FROM + " WHERE g.id=?", (group_id,)).fetchone()
        return GroupRow.from_row(r) if r else None

    def group_by_slug(self, slug: str) -> GroupRow | None:
        with self.lock:
            r = self.db.execute(GROUP_SELECT + GROUP_FROM + " WHERE g.slug=?", (slug,)).fetchone()
        return GroupRow.from_row(r) if r else None

    def free_slug(self, name: str) -> str:
        """A slug for a new group called name that no group has: its slugify(), else with -2, -3... after it."""
        stem = slugify(name)
        with self.lock:
            for n in range(1, 10000):
                slug = stem if n == 1 else f"{stem[:MAX_SLUG_LEN - len(str(n)) - 1].rstrip('-')}-{n}"
                if self.group_by_slug(slug) is None:
                    return slug
        raise ApiError(409, "too many groups have that name")

    def insert_group(self, values: dict[str, Any], owner_name: str) -> None:
        """A new group with its owner as its first admin."""
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.transaction():
            self.db.execute(f"INSERT INTO groups({cols}) VALUES({marks})", values)
            self.db.execute("INSERT INTO group_members(group_id, uuid, name, role, joined) VALUES(?,?,?,'admin',?)",
                            (values["id"], values["owner"], owner_name, values["created"]))

    def update_group(self, group_id: str, values: dict[str, Any]) -> None:
        """Change settings. A group made members only also makes its posts private: they stay private if it opens up
        again, or if it is deleted, so nothing it hid becomes visible on its own."""
        with self.transaction():
            if values.get("post_visibility") == "group":
                self.db.execute("UPDATE posts SET visibility='private' WHERE group_id=?", (group_id,))
            sets = ", ".join(f"{k}=:{k}" for k in values)
            self.db.execute(f"UPDATE groups SET {sets}, updated=:now WHERE id=:id",
                            {**values, "now": int(time.time() * 1000), "id": group_id})

    def delete_group(self, group_id: str) -> int:
        """Delete a group (members, requests and bans go with it). Its posts stay, outside any group. How many there were."""
        with self.transaction():
            n = self.db.execute("UPDATE posts SET group_id=NULL, group_hidden=NULL WHERE group_id=?", (group_id,)).rowcount
            self.db.execute("DELETE FROM groups WHERE id=?", (group_id,))
        return n

    def public_groups(self) -> list[GroupRow]:
        """The groups directory: public groups, the most members first."""
        with self.lock:
            rows = self.db.execute(
                GROUP_SELECT + GROUP_FROM + " WHERE g.visibility='public' ORDER BY member_count DESC, g.name COLLATE NOCASE, g.id"
            ).fetchall()
        return [GroupRow.from_row(r) for r in rows]

    def groups_of(self, uuid: str) -> list[GroupRow]:
        """The groups uuid is a member of, by name, each with their role."""
        with self.lock:
            rows = self.db.execute(
                GROUP_SELECT + ", gm.role AS my_role " + GROUP_FROM
                + " JOIN group_members gm ON gm.group_id = g.id AND gm.uuid = ? ORDER BY g.name COLLATE NOCASE, g.id", (uuid,),
            ).fetchall()
        return [GroupRow.from_row(r) for r in rows]

    def owned_groups(self, uuid: str) -> int:
        with self.lock:
            (n,) = self.db.execute("SELECT COUNT(*) FROM groups WHERE owner=?", (uuid,)).fetchone()
        return int(n)

    def group_member(self, group_id: str, uuid: str) -> sqlite3.Row | None:
        with self.lock:
            return self.db.execute("SELECT * FROM group_members WHERE group_id=? AND uuid=?", (group_id, uuid)).fetchone()

    def group_members(self, group_id: str) -> list[sqlite3.Row]:
        """Members, admins first, then moderators, then by when they joined; with their account's current names."""
        with self.lock:
            return self.db.execute(
                """SELECT m.*, COALESCE(u.name, m.name) AS mc_name, COALESCE(u.display_name, u.name, m.name) AS shown,
                       u.avatar_version, u.uuid IS NOT NULL AS has_account
                   FROM group_members m LEFT JOIN users u ON u.uuid = m.uuid WHERE m.group_id=?
                   ORDER BY CASE m.role WHEN 'admin' THEN 0 WHEN 'moderator' THEN 1 ELSE 2 END, m.joined, m.uuid""",
                (group_id,),
            ).fetchall()

    def add_member(self, group_id: str, uuid: str, name: str, role: str = "member") -> bool:
        """Add uuid (their join request, if any, is done with). False if they were a member already."""
        with self.lock:
            self.db.execute("DELETE FROM group_requests WHERE group_id=? AND uuid=?", (group_id, uuid))
            return self.db.execute(
                "INSERT OR IGNORE INTO group_members(group_id, uuid, name, role, joined) VALUES(?,?,?,?,?)",
                (group_id, uuid, name, role, int(time.time() * 1000)),
            ).rowcount == 1

    def remove_member(self, group_id: str, uuid: str) -> bool:
        with self.lock:
            return self.db.execute("DELETE FROM group_members WHERE group_id=? AND uuid=?", (group_id, uuid)).rowcount == 1

    def set_member(self, group_id: str, uuid: str, values: dict[str, Any]) -> None:
        """Change a member's role and/or own permissions (perms: a JSON object, or None for the group's defaults)."""
        sets = ", ".join(f"{k}=:{k}" for k in values)
        with self.lock:
            self.db.execute(f"UPDATE group_members SET {sets} WHERE group_id=:g AND uuid=:u", {**values, "g": group_id, "u": uuid})

    def transfer_group(self, group_id: str, uuid: str) -> None:
        """Make the member uuid the owner (and an admin). The old owner stays an admin."""
        with self.transaction():
            self.db.execute("UPDATE group_members SET role='admin' WHERE group_id=? AND uuid=?", (group_id, uuid))
            self.db.execute("UPDATE groups SET owner=?, updated=? WHERE id=?", (uuid, int(time.time() * 1000), group_id))

    def group_request(self, group_id: str, uuid: str) -> sqlite3.Row | None:
        with self.lock:
            return self.db.execute("SELECT * FROM group_requests WHERE group_id=? AND uuid=?", (group_id, uuid)).fetchone()

    def add_group_request(self, group_id: str, uuid: str, message: str) -> None:
        with self.lock:
            self.db.execute("INSERT INTO group_requests(group_id, uuid, message, created) VALUES(?,?,?,?)",
                            (group_id, uuid, message, int(time.time() * 1000)))

    def delete_group_request(self, group_id: str, uuid: str) -> bool:
        with self.lock:
            return self.db.execute("DELETE FROM group_requests WHERE group_id=? AND uuid=?", (group_id, uuid)).rowcount == 1

    def group_requests(self, group_id: str) -> list[sqlite3.Row]:
        """Pending join requests, oldest first, with the asker's names."""
        with self.lock:
            return self.db.execute(
                """SELECT r.*, u.name AS mc_name, COALESCE(u.display_name, u.name) AS shown, u.avatar_version
                   FROM group_requests r JOIN users u ON u.uuid = r.uuid WHERE r.group_id=? ORDER BY r.created, r.uuid""",
                (group_id,),
            ).fetchall()

    def group_ban(self, group_id: str, uuid: str) -> sqlite3.Row | None:
        with self.lock:
            return self.db.execute("SELECT * FROM group_bans WHERE group_id=? AND uuid=?", (group_id, uuid)).fetchone()

    def ban_from_group(self, group_id: str, uuid: str, name: str, by: str) -> None:
        """Ban uuid: they stop being a member, their join request goes, and they can't join or be added again."""
        with self.transaction():
            self.db.execute("DELETE FROM group_members WHERE group_id=? AND uuid=?", (group_id, uuid))
            self.db.execute("DELETE FROM group_requests WHERE group_id=? AND uuid=?", (group_id, uuid))
            self.db.execute("INSERT OR REPLACE INTO group_bans(group_id, uuid, name, banned_by, created) VALUES(?,?,?,?,?)",
                            (group_id, uuid, name, by, int(time.time() * 1000)))

    def unban_from_group(self, group_id: str, uuid: str) -> bool:
        with self.lock:
            return self.db.execute("DELETE FROM group_bans WHERE group_id=? AND uuid=?", (group_id, uuid)).rowcount == 1

    def group_bans(self, group_id: str) -> list[sqlite3.Row]:
        with self.lock:
            return self.db.execute(
                """SELECT b.*, COALESCE(u.name, b.name) AS mc_name, COALESCE(u.display_name, u.name, b.name) AS shown
                   FROM group_bans b LEFT JOIN users u ON u.uuid = b.uuid WHERE b.group_id=? ORDER BY b.created DESC, b.uuid""",
                (group_id,),
            ).fetchall()

    # ---- the site's storage: everything under the data directory together

    SITE_CATEGORIES = (
        ("posts", "Board snapshots and versions"), ("models", "3D view caches"), ("post_thumbs", "Board thumbnails"),
        ("files", "Cloud files"), ("thumbs", "File thumbnails"), ("avatars", "Custom pictures"),
        ("skins", "Cached skins and faces"), ("releases", "Mod releases"), ("feedback", "Feedback attachments"),
        ("database", "Database"), ("other", "Other"),
    )

    def _classify(self, rel: tuple[str, ...]) -> str:
        top = rel[0]
        if top == "posts":
            name = rel[-1]
            if name.startswith(MODEL_CACHE_PREFIX):
                return "models"
            return "post_thumbs" if name == POST_THUMB else "posts"
        if top == "users" and len(rel) >= 3:
            if rel[2] == "files":
                return "files"
            if rel[2] == "thumbs":
                return "thumbs"
            if rel[2].startswith("avatar."):
                return "avatars"
        if top in ("skins", "releases"):
            return top
        if top == FEEDBACK_DIR:
            return "feedback"
        if len(rel) == 1 and top.startswith("cloud.db"):
            return "database"
        return "other"

    def measure_site(self) -> dict[str, list[int]]:
        """Walk the data directory: {category: [bytes, files]}. A file with several hard links (a board snapshot
        and the copies "Add to my cloud" made of it) counts once, where it is met first: board snapshots are walked
        first, so shared bytes show up there."""
        out = {key: [0, 0] for key, _ in self.SITE_CATEGORIES}
        seen: set[tuple[int, int]] = set()
        # (tmp/ is scratch space: an upload being checked against the cap sits there while it is)
        roots = [self.posts_root] + sorted(p for p in self.root.iterdir() if p not in (self.posts_root, self.tmp))
        for top in roots:
            for dirpath, _, names in os.walk(top) if top.is_dir() else [(str(top.parent), [], [top.name])]:
                for name in names:
                    full = os.path.join(dirpath, name)
                    try:
                        st = os.lstat(full)
                    except OSError:
                        continue
                    if not (st.st_mode & 0o170000 == 0o100000):  # regular files only
                        continue
                    if st.st_nlink > 1:
                        key = (st.st_dev, st.st_ino)
                        if key in seen:
                            continue
                        seen.add(key)
                    cat = out[self._classify(Path(full).relative_to(self.root).parts)]
                    cat[0] += st.st_size
                    cat[1] += 1
        with self.lock:
            self._site = (time.monotonic(), out)
            self._site_pending = 0
        return out

    def site_used(self, fresh: bool = False) -> int:
        """The data directory's size: measured at most SITE_USAGE_TTL seconds ago, plus what check_site let in since."""
        with self.lock:
            cached = self._site
            pending = self._site_pending
        if fresh or cached is None or time.monotonic() - cached[0] > SITE_USAGE_TTL:
            return sum(v[0] for v in self.measure_site().values())
        return sum(v[0] for v in cached[1].values()) + pending

    def check_site(self, delta: int) -> None:
        """507 code "site_full" if delta more bytes would take the site past Settings.site_quota_bytes (admins too).
        The estimate errs high (deletions only count at the next measurement), so the disk is measured again
        before anything is refused."""
        if delta <= 0:
            return
        cap = self.settings.site_quota_bytes
        if self.site_used() + delta > cap and self.site_used(fresh=True) + delta > cap:
            raise ApiError(507, "the site's storage is full, so nothing more can be stored for now; please tell an admin",
                           code="site_full")
        with self.lock:
            self._site_pending += delta

    def storage_top_users(self, limit: int) -> list[sqlite3.Row]:
        """Accounts by quota usage, most first: files (not references), board snapshots, references."""
        with self.lock:
            return self.db.execute(
                """SELECT * FROM (SELECT u.uuid, u.name, u.display_name, u.display_tag, u.is_admin, u.quota_bytes,
                       """ + FILE_BYTES_SQL.format(owner="u.uuid") + """ AS file_bytes,
                       """ + POST_BYTES_SQL.format(owner="u.uuid") + """ AS post_bytes,
                       (SELECT COALESCE(SUM(f.size),0) FROM files f WHERE f.uuid = u.uuid AND """ + REFERENCE_SQL + """) AS ref_bytes
                   FROM users u) WHERE file_bytes + post_bytes > 0
                   ORDER BY file_bytes + post_bytes DESC, name COLLATE NOCASE LIMIT ?""", (limit,),
            ).fetchall()

    def storage_largest_posts(self, limit: int) -> list[PostRow]:
        with self.lock:
            rows = self.db.execute(POST_SELECT + " ORDER BY total_bytes DESC, p.created DESC LIMIT ?", (limit,)).fetchall()
        return [PostRow.from_row(r) for r in rows]

    def storage_largest_files(self, limit: int) -> list[sqlite3.Row]:
        with self.lock:
            return self.db.execute(
                f"""SELECT f.uuid, f.path, f.size, f.source_post, {REFERENCE_SQL} AS is_ref, u.name, u.display_name,
                       u.display_tag FROM files f JOIN users u ON u.uuid = f.uuid
                   ORDER BY f.size DESC, f.uuid, f.path LIMIT ?""", (limit,),
            ).fetchall()

    # ---- feedback attachments: feedback/<id>/<kind>, a row each in feedback_files

    def feedback_file(self, feedback_id: int, kind: str) -> Path:
        assert kind in FEEDBACK_ATTACHMENTS
        return self.feedback_root / str(int(feedback_id)) / kind

    def feedback_attachments(self, ids: list[int]) -> dict[int, list[sqlite3.Row]]:
        if not ids:
            return {}
        marks = ",".join("?" * len(ids))
        with self.lock:
            rows = self.db.execute(
                f"SELECT * FROM feedback_files WHERE feedback_id IN ({marks}) ORDER BY feedback_id, kind", ids).fetchall()
        out: dict[int, list[sqlite3.Row]] = {}
        for r in rows:
            out.setdefault(r["feedback_id"], []).append(r)
        return out

    def delete_feedback_files(self, feedback_id: int, kind: str | None = None) -> int:
        """Remove a report's attachments (one kind, or all): rows and files. Returns how many there were."""
        kinds = [kind] if kind else list(FEEDBACK_ATTACHMENTS)
        with self.lock:
            n = 0
            for k in kinds:
                n += self.db.execute("DELETE FROM feedback_files WHERE feedback_id=? AND kind=?", (feedback_id, k)).rowcount
                self.feedback_file(feedback_id, k).unlink(missing_ok=True)
            try:
                (self.feedback_root / str(int(feedback_id))).rmdir()
            except OSError:
                pass
        return n

    def reconcile_feedback_files(self) -> None:
        """At startup: delete the attachments of reports resolved more than FEEDBACK_ATTACHMENT_DAYS ago, rows whose
        file is missing, and folders and files without a row (an interrupted submission)."""
        cutoff = (now_s() - FEEDBACK_ATTACHMENT_DAYS * DAY) * 1000
        with self.lock:
            old = [r[0] for r in self.db.execute(
                """SELECT DISTINCT ff.feedback_id FROM feedback_files ff JOIN feedback f ON f.id = ff.feedback_id
                   WHERE f.resolved IS NOT NULL AND f.resolved < ?""", (cutoff,))]
            for fid in old:
                self.delete_feedback_files(fid)
            if old:
                log.info("deleted the attachments of %d feedback reports resolved over %d days ago", len(old),
                         FEEDBACK_ATTACHMENT_DAYS)
            rows = {(r["feedback_id"], r["kind"]) for r in self.db.execute("SELECT feedback_id, kind FROM feedback_files")}
            for fid, kind in rows:
                if not self.feedback_file(fid, kind).is_file():
                    log.warning("feedback %d: its %s attachment is missing", fid, kind)
                    self.db.execute("DELETE FROM feedback_files WHERE feedback_id=? AND kind=?", (fid, kind))
            for d in self.feedback_root.iterdir():
                if not d.is_dir() or not d.name.isdigit():
                    (shutil.rmtree(d, ignore_errors=True) if d.is_dir() else d.unlink(missing_ok=True))
                    continue
                for f in d.iterdir():
                    if (int(d.name), f.name) not in rows:
                        (shutil.rmtree(f, ignore_errors=True) if f.is_dir() else f.unlink(missing_ok=True))
                try:
                    d.rmdir()  # only if now empty
                except OSError:
                    pass

    # ---- settings profiles

    def list_profiles(self, uuid: str) -> list[sqlite3.Row]:
        with self.lock:
            return self.db.execute(
                "SELECT id, name, updated FROM profiles WHERE uuid=? ORDER BY created, rowid", (uuid,)).fetchall()

    def get_profile(self, uuid: str, profile_id: str) -> sqlite3.Row | None:
        """The account's profile, or None (also for another account's id)."""
        with self.lock:
            return self.db.execute("SELECT * FROM profiles WHERE id=? AND uuid=?", (profile_id, uuid)).fetchone()

    def new_profile_id(self) -> str:
        with self.lock:
            while True:
                pid = secrets.token_urlsafe(8)[:POST_ID_LEN]
                if PROFILE_ID_RE.match(pid) and not self.db.execute("SELECT 1 FROM profiles WHERE id=?", (pid,)).fetchone():
                    return pid

    # ---- telemetry

    def add_telemetry(self, values: dict[str, Any]) -> None:
        cols = ", ".join(values)
        marks = ", ".join(":" + k for k in values)
        with self.lock:
            self.db.execute(f"INSERT INTO telemetry({cols}) VALUES({marks})", values)

    def telemetry_since(self, t: int) -> list[sqlite3.Row]:
        with self.lock:
            return self.db.execute("SELECT * FROM telemetry WHERE received >= ? ORDER BY received", (t,)).fetchall()

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
        tags: list[str] = (), size: str | None = None, complexity: str | None = None, mc: str | None = None,
        author: str | None = None, group: Standing | None = None, folder: str | None = None,
    ) -> tuple[list[PostRow], int]:
        """One page of a board tab and the total number of matching posts.
        'public' lists public posts only; 'shared' private posts whose access list has viewer;
        'mine' every post by viewer; 'group' the posts in group (viewer's standing there) that viewer may see, minus
        those the group hid (but for its admins and moderators). Posts in a members-only group are never in 'public' or
        'shared'. Posts an admin hid are only listed in 'mine' (and to their author in 'group'). Every tag in tags must
        be on the post; size is a size class, complexity a complexity class; mc a Minecraft version any version of the
        post is for; author a uuid that owns the post or is a co-author of it. folder ('group' only): the posts in that folder
        of the group, or with a search, in it and every folder below it."""
        params: dict[str, Any] = {"viewer": viewer, "now": int(time.time() * 1000)}
        if tab == "public":
            where = ["p.visibility = 'public'", "p.hidden IS NULL", NOT_GROUP_ONLY_SQL]
        elif tab == "shared":
            where = [
                "p.visibility = 'private'",
                "p.hidden IS NULL",
                "p.uuid != :viewer",
                "EXISTS (SELECT 1 FROM post_access a WHERE a.post_id = p.id AND a.uuid = :viewer)",
                NOT_GROUP_ONLY_SQL,  # an access list doesn't open those
            ]
        elif tab == "mine":
            where = ["p.uuid = :viewer"]
        elif tab == "group" and group is not None:
            where = self._group_where(group, params)
            if folder is not None and (folder or not query.strip()):
                params["folder"] = folder
                params["below"] = like_prefix(folder)
                where.append("(p.group_folder = :folder OR p.group_folder LIKE :below ESCAPE '\\')" if query.strip()
                             else "p.group_folder = :folder")
        else:
            raise ValueError(tab)
        for i, term in enumerate(query.casefold().split()[:10]):
            params[f"t{i}"] = term
            where.append(
                f"instr(slop_fold(p.title || char(10) || p.description || char(10) || u.name || char(10)"
                f" || COALESCE(u.display_name, '') || char(10) || COALESCE(p.credit, '') || char(10) || COALESCE((SELECT group_concat(COALESCE(cu.display_name, a.name),"
                f" char(10)) FROM post_authors a LEFT JOIN users cu ON cu.uuid = a.uuid WHERE a.post_id = p.id), '')), :t{i}) > 0"
            )
        for i, tag in enumerate(tags):
            params[f"tag{i}"] = tag
            where.append(f"EXISTS (SELECT 1 FROM post_tags g WHERE g.post_id = p.id AND g.tag = :tag{i})")
        if size is not None:
            params["size"] = size
            where.append("p.size_class = :size")
        if complexity is not None:
            params["complexity"] = complexity
            where.append(f"{complexity_sql('p.distinct_count')} = :complexity")
        if mc is not None:
            params["mc"] = mc
            where.append("(p.mc_version = :mc OR EXISTS (SELECT 1 FROM post_versions v WHERE v.post_id = p.id AND v.mc_version = :mc))")
        if author is not None:
            params["author"] = author
            where.append("(p.uuid = :author OR EXISTS (SELECT 1 FROM post_authors a WHERE a.post_id = p.id AND a.uuid = :author))")
        clause = " WHERE " + " AND ".join(where)
        with self.lock:
            (total,) = self.db.execute(f"SELECT COUNT(*) {POST_FROM}" + clause, params).fetchone()
            rows = self.db.execute(
                POST_SELECT + clause + f" ORDER BY {BOARD_ORDER[sort]} LIMIT :limit OFFSET :offset",
                {**params, "limit": page_size, "offset": (page - 1) * page_size},
            ).fetchall()
        return self.attach_coauthors([PostRow.from_row(r) for r in rows]), int(total)

    @staticmethod
    def _group_where(group: Standing, params: dict[str, Any]) -> list[str]:
        """The conditions for the posts in group that params["viewer"] may see (see list_posts)."""
        params.update(group=group.group.id, member=int(group.member), manager=int(group.manager),
                      admin=int(group.site_admin))
        return [
            "p.group_id = :group",
            "(p.hidden IS NULL OR p.uuid = :viewer)",
            "(p.group_hidden IS NULL OR p.uuid = :viewer OR :manager)",
            GROUP_LISTED_SQL,
        ]

    def group_folders(self, group: Standing, viewer: str | None, folder: str) -> list[dict[str, Any]]:
        """The folders right inside folder of group ('' for the top) that hold posts viewer may see, by name: each with
        its path, how many posts are in it (and below it), and a cover post with a thumbnail (the biggest), or None."""
        params: dict[str, Any] = {"viewer": viewer, "now": int(time.time() * 1000)}
        where = self._group_where(group, params)
        if folder:
            params["below"] = like_prefix(folder)
            where.append("p.group_folder LIKE :below ESCAPE '\\'")
        else:
            where.append("p.group_folder != ''")
        with self.lock:
            rows = self.db.execute(
                f"SELECT p.id, p.group_folder AS f, p.has_thumb, p.block_count {POST_FROM} WHERE " + " AND ".join(where), params,
            ).fetchall()
        start = len(folder) + 1 if folder else 0
        children: dict[str, dict[str, Any]] = {}
        for r in rows:
            name = r["f"][start:].split("/", 1)[0]
            child = children.setdefault(name, {"name": name, "path": (folder + "/" if folder else "") + name,
                                               "count": 0, "cover": None, "_big": -1})
            child["count"] += 1
            if r["has_thumb"] and (r["block_count"] or 0) > child["_big"]:
                child["cover"], child["_big"] = r["id"], r["block_count"] or 0
        out = sorted(children.values(), key=lambda c: c["name"].casefold())
        for c in out:
            del c["_big"]
        return out

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

    def user_card_stats(self, uuid: str) -> tuple[int, int]:
        """(contributions, joined): the public, not hidden posts uuid owns or co-authored (the profile page's
        list), and when the account was made (its first login), in unix s."""
        with self.lock:
            (count,) = self.db.execute(
                f"""SELECT COUNT(*) FROM posts p WHERE p.visibility = 'public' AND p.hidden IS NULL AND {NOT_GROUP_ONLY_SQL}
                   AND (p.uuid = :u OR EXISTS (SELECT 1 FROM post_authors a WHERE a.post_id = p.id AND a.uuid = :u))""",
                {"u": uuid},
            ).fetchone()
            row = self.db.execute("SELECT created FROM users WHERE uuid=?", (uuid,)).fetchone()
        return int(count), int(row["created"]) if row else 0

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
        """Accounts matching query (part of the Minecraft, display or Discord name, or the uuid), most recently
        seen first, with usage,
        badges and whitelist entry."""
        where, params = "", {}
        if query:
            where = (" WHERE instr(lower(u.name), :q) > 0 OR instr(u.uuid, :q) > 0 OR instr(replace(u.uuid, '-', ''), :q) > 0"
                     " OR instr(slop_fold(u.display_name), :qf) > 0 OR instr(lower(COALESCE(u.discord_name, '')), :q) > 0")
            params["q"], params["qf"] = query.lower(), query.casefold()
        with self.lock:
            (total,) = self.db.execute("SELECT COUNT(*) FROM users u" + where, params).fetchone()
            rows = self.db.execute(
                """SELECT u.*, w.source AS wl_source,
                       """ + FILE_BYTES_SQL.format(owner="u.uuid") + """
                       + """ + POST_BYTES_SQL.format(owner="u.uuid") + """ AS used,
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


def _get(url: str, timeout: float, cap: int, headers: dict[str, str] | None = None) -> bytes | None:
    """GET url: the body (at most cap bytes), None for 204/404, MojangUnavailable for anything else."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 204:
                return None
            body = resp.read(cap + 1)
    except urllib.error.HTTPError as e:
        e.close()
        if e.code in (400, 404):
            return None
        raise MojangUnavailable(f"HTTP {e.code}") from e
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise MojangUnavailable(str(e)) from e
    if len(body) > cap:
        raise MojangUnavailable("response too large")
    return body


def fetch_skin(settings: Settings, uuid: str) -> bytes | None:
    """The player's skin PNG through Mojang's session server (profile -> textures -> SKIN url), or None if they
    have none (the default skins). MojangUnavailable if it can't be fetched. The skin URL must be on a host in
    settings.skin_hosts (textures.minecraft.net), so a strange answer can't make us fetch anything else."""
    body = _get(f"{settings.mojang_session_profile_url.rstrip('/')}/{uuid.replace('-', '')}", settings.skin_timeout, 1 << 16)
    if body is None:
        return None
    try:
        props = json.loads(body).get("properties") or []
        value = next(p["value"] for p in props if p.get("name") == "textures")
        textures = json.loads(base64.b64decode(value)).get("textures") or {}
        url = (textures.get("SKIN") or {}).get("url")
    except StopIteration:
        return None
    except (ValueError, TypeError, KeyError, AttributeError) as e:
        raise MojangUnavailable("unexpected profile response") from e
    if not isinstance(url, str):
        return None
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or parts.hostname not in settings.skin_hosts:
        raise MojangUnavailable(f"unexpected skin host {parts.hostname}")
    return _get(url, settings.skin_timeout, SKIN_MAX_BYTES)


class DiscordError(Exception):
    pass


def discord_token(settings: Settings, code: str) -> str:
    """Trade an authorization code for an access token (scope identify). DiscordError if refused."""
    form = urllib.parse.urlencode({
        "client_id": settings.discord_client_id, "client_secret": settings.discord_client_secret,
        "grant_type": "authorization_code", "code": code, "redirect_uri": settings.discord_redirect,
    }).encode()
    req = urllib.request.Request(DISCORD_TOKEN, data=form, headers={
        "User-Agent": USER_AGENT, "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            token = json.loads(resp.read(1 << 16)).get("access_token")
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, AttributeError) as e:
        raise DiscordError(str(e)) from e
    if not isinstance(token, str) or not token:
        raise DiscordError("no access token")
    return token


def discord_user(settings: Settings, token: str) -> tuple[str, str]:
    """(Discord id, username) of the token's user. DiscordError if that fails."""
    req = urllib.request.Request(DISCORD_ME, headers={"User-Agent": USER_AGENT, "Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read(1 << 16))
        uid, name = data["id"], data["username"]  # the unique handle (global_name is a free display name)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError, KeyError, TypeError) as e:
        raise DiscordError(str(e)) from e
    if not isinstance(uid, str) or not uid.isdigit() or not isinstance(name, str):
        raise DiscordError("unexpected user")
    return uid, name[:100]


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
    coauthors: Any = None  # [{"player": Minecraft name} | {"name": free text}]
    links: Any = None  # [http(s) URL]
    versionLabel: Any = None
    mc: Any = None  # the Minecraft version the build is for
    group: Any = None  # a group's id to post it in
    folder: Any = None  # the folder in that group, "a/b" (omitted: the top)
    credit: Any = None  # a credited author shown instead of the uploader


class PostPatchIn(BaseModel):
    title: str | None = None
    description: str | None = None
    visibility: str | None = None
    allowed: list[str] | None = None
    tags: list[str] | None = None
    coauthors: Any = None
    links: Any = None
    versionLabel: Any = None  # of the current version
    mc: Any = None
    group: Any = None  # a group's id, or null to take it out of its group
    folder: Any = None  # the folder in its group, "a/b"; "" or null for the top
    credit: Any = None


class VersionIn(BaseModel):
    path: Any = None  # one of the owner's cloud files
    label: Any = None
    mc: Any = None


class DisplayNameIn(BaseModel):
    name: Any = None  # omitted: keep the name and change only the tag; null: back to the Minecraft name
    tag: Any = None  # omitted: picked for you; null or "": no tag (if nobody else has the name without one)


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


class GroupIn(BaseModel):
    """A new group, or (PATCH) the settings to change."""
    name: Any = None
    description: Any = None
    slug: Any = None  # PATCH only; a new group's slug comes from its name
    visibility: Any = None
    joinPolicy: Any = None
    postPolicy: Any = None
    postVisibility: Any = None
    memberPerms: Any = None  # {"post", "invite", "download": true | false}, plain members' defaults


class MemberPatchIn(BaseModel):
    role: Any = None
    perms: Any = None  # this member's own permissions over the group's defaults; null: none


class JoinRequestIn(BaseModel):
    message: Any = None


class GroupPostIn(BaseModel):
    post: Any = None  # a post id


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
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
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
    templates.globals["complexity_labels"] = COMPLEXITY_LABELS
    templates.filters["domain"] = link_domain
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

    def avatar_url(uuid: str, version: int = 0, absolute: bool = False) -> str:
        """The player's picture (custom icon or Minecraft face), from our own /avatar endpoint."""
        return f"{settings.site_url if absolute else base}/avatar/{uuid}" + (f"?v={version}" if version else "")

    templates.globals["avatar_url"] = avatar_url

    def account_api(user: User) -> dict[str, Any]:
        """The caller's account, for the mod (/api/auth/verify and /api/me)."""
        out = {"uuid": user.uuid, "name": user.name, **usage(user), "whitelisted": is_member(user),
               "badges": store.badges_of(user.uuid), "displayName": user.shown, "tag": user.display_tag,
               "avatar": avatar_url(user.uuid, user.avatar_version, absolute=True)}
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
        """Raise 413 unless the account has room for delta more bytes, then 507 unless the site has.
        Call with store.lock held."""
        quota = quota_for(user)
        used = store.used_bytes(user.uuid)
        if quota is not None and used + delta > quota:
            raise ApiError(413, "quota exceeded", usedBytes=used, quotaBytes=quota)
        store.check_site(delta)

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
                # the same bytes again keep a reference to a board post a reference (free); anything else counts
                still_ref = old is not None and old.reference and old.sha256 == digest
                quota = quota_for(user)
                used = store.used_bytes(user.uuid)
                if quota is not None and used - (old.billed if old else 0) + (0 if still_ref else size) > quota:
                    raise ApiError(413, "quota exceeded", usedBytes=used, quotaBytes=quota)
                if not still_ref:
                    store.check_site(size - (old.billed if old else 0))
                dest = store.file_path(user.uuid, path)
                if not still_ref:  # (the same bytes as a board snapshot: keep the file, a hard link to it)
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
                # a file saved from the board stays "from the board" until its content changes; a file posted to the
                # board stays linked to its post (the post can be updated from it)
                source = old.source if old and old.sha256 == digest else None
                store.put_file_row(user.uuid, FileRow(path, size, digest, int(time.time() * 1000), has_thumb, source,
                                                      old.listing_post if old else None))
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
            store.check_site(len(data))
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
        if user is not None and (user.uuid == post.uuid or user.is_admin):
            return True
        if post.group_only:  # whatever its own visibility (and access list): the group's members
            return group_grants(post, user)
        if post.visibility != "private":
            return True
        if user is None:
            return False
        # members of a private group also see the private posts in it
        return store.is_allowed(post.id, user.uuid) or (post.group_vis == "private" and group_grants(post, user))

    def group_grants(post: PostRow, user: User | None) -> bool:
        """Whether user sees post through its group: as a member, or as one of its admins and moderators if the
        group hid the post."""
        if user is None or post.group_id is None:
            return False
        row = store.group_member(post.group_id, user.uuid)
        return row is not None and (post.group_hidden is None or row["role"] in MANAGER_ROLES)

    def require_download(post: PostRow, user: User | None) -> None:
        """403 for a plain member who only sees the post through its group, when the group doesn't let them download
        its builds. Call after can_view."""
        if user is None or user.uuid == post.uuid or user.is_admin or post.group_id is None:
            return
        if not post.group_only and (post.visibility != "private" or store.is_allowed(post.id, user.uuid)):
            return  # open to them without the group
        group = store.get_group(post.group_id)
        if group is not None and not standing(group, user).may("download"):
            raise ApiError(403, f"members of {group.name} can't download its builds", code="no_download")

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
        distinct: int | None = None  # distinct block and entity types (complexity)
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
            read_snapshot(snap, tmp, name, path)
        except BaseException:
            snap.cleanup()
            raise
        return snap

    def read_snapshot(snap: Snapshot, tmp: Path, name: str, path: str = "") -> None:
        """Read the temporary copy tmp of the schematic name into snap: converted to .slopmatic if it is in another
        format, with its size, block count and distinct types. 400 if it can't be read or is modded."""
        src_fmt = convert.format_of(name)
        with conversion_slot():
            try:
                schem = read_schematic(tmp, name, settings.max_export_volume, path)
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
            snap.distinct = schem.distinct_types()

    async def upload_snapshot(request: Request, name: str) -> Snapshot:
        """A snapshot of a schematic sent as the request body (gzip, at most the upload limit)."""
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".snap")
        tmp = Path(tmp_name)
        snap = Snapshot(tmp, None, slopmatic_name(name), "slopmatic", 0, "")
        try:
            with os.fdopen(fd, "wb") as f:
                head, snap.size, snap.sha256 = await read_capped(request, settings.max_upload_bytes, f)
            if not head.startswith(GZIP_MAGIC):
                raise ApiError(400, "not a gzip file (every schematic format is gzipped NBT)")
            await run_in_threadpool(read_snapshot, snap, tmp, name)
        except BaseException:
            snap.cleanup()
            raise
        return snap

    def snapshot_columns(snap: Snapshot) -> dict[str, Any]:
        dims = snap.dims or (None, None, None)
        return {
            "filename": snap.filename, "format": snap.fmt, "size": snap.size, "sha256": snap.sha256,
            "has_thumb": int(snap.thumb is not None), "size_x": dims[0], "size_y": dims[1], "size_z": dims[2],
            "block_count": snap.blocks, "size_class": size_class(snap.blocks), "distinct_count": snap.distinct,
            "updated": int(time.time() * 1000),
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

    def archive_snapshot(pdir: Path, post: PostRow) -> None:
        """Move the current snapshot, its thumbnail and 3D cache into posts/<id>/v<version>/ and record it as an
        earlier version. Call inside store.transaction(); a failure moves the files back."""
        vdir = pdir / f"v{post.version}"
        vdir.mkdir()
        moved: list[tuple[Path, Path]] = []
        try:
            for f in [store._under(pdir, post.filename), pdir / POST_THUMB,
                      *pdir.glob(f"{MODEL_CACHE_PREFIX}{post.sha256[:16]}.*")]:
                if f.is_file():
                    os.replace(f, vdir / f.name)
                    moved.append((vdir / f.name, f))
            store.archive_current(post)
        except BaseException:
            for src, back in moved:
                os.replace(src, back)
            shutil.rmtree(vdir, ignore_errors=True)
            raise

    def post_api(post: PostRow, viewer: User | None, detail: bool = False, favs: set[str] | None = None) -> dict[str, Any]:
        """A post for the API. favs: the ids viewer has favourited, when known (lists), else looked up."""
        if viewer is not None and favs is None:
            favs = store.favorite_ids(viewer.uuid, [post.id])
        out: dict[str, Any] = {
            "id": post.id,
            "title": post.title,
            "visibility": post.visibility,
            "author": {"uuid": post.uuid, "name": post.author_name, "badges": post.author_badges,
                       "displayName": post.author_shown},
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
            "coauthors": [a.api() for a in post.coauthors],
            "links": post.links,
            "version": post.version,
            "versionLabel": post.version_label,
            "mc": post.mc,
            "versionCount": post.version_count,
            "distinctTypes": post.distinct_count,
            "complexity": post.complexity,
            "downloads": post.downloads,
            "shareUrl": f"{settings.site_url}/b/{post.id}",
            "favorites": post.favorites,
            "favorited": (post.id in favs) if viewer is not None and favs is not None else None,
            "credit": post.credit,  # shown instead of the author ("by <credit> · uploaded by <author>")
            "group": post_group_api(post, viewer),
        }
        if detail:
            out["description"] = post.description
            out["myVote"] = store.get_vote(post.id, viewer.uuid) if viewer else None
            if viewer and (viewer.uuid == post.uuid or viewer.is_admin):
                out["sourcePath"] = post.source_path
                out["hiddenReason"] = post.hidden_reason if post.hidden is not None else None
                out["allowed"] = [{"uuid": u, "name": n} for u, n in store.get_allowed(post.id)]
        return out

    def post_group_api(post: PostRow, viewer: User | None) -> dict[str, Any] | None:
        """The group a post is in, unless the group is private and viewer isn't in it."""
        if post.group_id is None:
            return None
        if post.group_vis == "private" and not (viewer and (viewer.is_admin or store.group_member(post.group_id, viewer.uuid))):
            return None
        return {"id": post.group_id, "slug": post.group_slug, "name": post.group_name, "postVisibility": post.group_post_vis,
                "hidden": post.group_hidden is not None, "url": f"{settings.site_url}/g/{post.group_slug}",
                "folder": post.group_folder}

    def clean_folder(user: User, value: Any) -> str:
        """A folder inside a group, "a/b/c" ('' for the top): names of one line, joined by slashes."""
        if value is None:
            return ""
        if not isinstance(value, str):
            raise ApiError(400, "folder must be text")
        parts = [part.strip() for part in value.strip().strip("/").split("/")] if value.strip().strip("/") else []
        if len(parts) > MAX_FOLDER_DEPTH or any(not part or len(part) > MAX_FOLDER_NAME_LEN or not part.isprintable()
                                                 or part in (".", "..") for part in parts):
            raise ApiError(400, f"folder must be up to {MAX_FOLDER_DEPTH} names of at most {MAX_FOLDER_NAME_LEN} characters,"
                                " separated by /")
        return filtered(user, "/".join(parts), "folder") if parts else ""

    def clean_credit(user: User, value: Any) -> str | None:
        """A credited author (free text on one line, e.g. Mojang for a vanilla structure), None for none."""
        if value is None or (isinstance(value, str) and not value.strip()):
            return None
        if not isinstance(value, str):
            raise ApiError(400, "credit must be text")
        text = " ".join(value.split())
        if len(text) > MAX_CREDIT_LEN or TITLE_BAD_CHARS.search(text):
            raise ApiError(400, f"credit must be at most {MAX_CREDIT_LEN} characters on one line")
        return filtered(user, text, "credit")

    def posting_group(user: User, value: Any) -> GroupRow:
        """The group (by id) user puts a post in: 404 if they can't see it, 403 if they may not post there."""
        group = store.get_group(value) if isinstance(value, str) and GROUP_ID_RE.match(value) else None
        st = standing(group, user) if group is not None else None
        if group is None or st is None or not st.can_see:
            raise ApiError(404, "no such group")
        if not st.can_post:
            raise ApiError(403, f"you can't post in {group.name}", code="group_post")
        return group

    def clean_tags(value: Any) -> list[str]:
        if not isinstance(value, list) or not all(isinstance(t, str) for t in value):
            raise ApiError(400, "tags must be a list of tag names")
        unknown = [t for t in value if t not in TAGS]
        if unknown:
            raise ApiError(400, ("unknown tags: " if len(unknown) > 1 else "unknown tag: ") + ", ".join(unknown)
                           + " (known: " + ", ".join(TAGS) + ")")
        return [t for t in TAGS if t in value]

    def clean_coauthors(user: User, value: Any) -> list[tuple[str | None, str]]:
        """[{"player": Minecraft name of an account here} | {"name": a name without an account}] ->
        [(uuid or None, name)]. The owner is left out (they are always the author); repeats are dropped."""
        if value is None:
            return []
        if not isinstance(value, list) or len(value) > MAX_COAUTHORS * 2:
            raise ApiError(400, f"coauthors must be a list of at most {MAX_COAUTHORS} entries")
        out: list[tuple[str | None, str]] = []
        seen: set[str] = set()
        for entry in value:
            player = entry.get("player") if isinstance(entry, dict) else None
            name = entry.get("name") if isinstance(entry, dict) else None
            if isinstance(player, str) and player.strip():
                found = store.find_user(player.strip()) if USERNAME_RE.match(player.strip()) else None
                if found is None:
                    raise ApiError(400, f"nobody called {player.strip()} has an account here; add them as a name instead",
                                   code="unknown_player", player=player.strip())
                if found.uuid == user.uuid or found.uuid in seen:
                    continue
                seen.add(found.uuid)
                out.append((found.uuid, found.name))
            elif isinstance(name, str) and name.strip():
                text = " ".join(name.split())
                if len(text) > MAX_AUTHOR_NAME_LEN or TITLE_BAD_CHARS.search(text):
                    raise ApiError(400, f"co-author names must be 1 to {MAX_AUTHOR_NAME_LEN} characters on one line")
                filtered(user, text, "co-author name")
                if text.casefold() in seen:
                    continue
                seen.add(text.casefold())
                out.append((None, text))
            else:
                raise ApiError(400, 'each co-author must be {"player": Minecraft name} or {"name": text}')
        if len(out) > MAX_COAUTHORS:
            raise ApiError(400, f"at most {MAX_COAUTHORS} co-authors")
        return out

    def clean_links(user: User, value: Any) -> list[str]:
        """Up to MAX_LINKS http(s) URLs (videos, a world download, a forum thread...)."""
        if value is None:
            return []
        if not isinstance(value, list) or not all(isinstance(u, str) for u in value):
            raise ApiError(400, "links must be a list of URLs")
        links: list[str] = []
        for raw in value:
            url = raw.strip()
            if not url or url in links:
                continue
            parts = urllib.parse.urlsplit(url) if len(url) <= MAX_LINK_LEN and not re.search(r"[\x00-\x20\x7f\\]", url) else None
            if (parts is None or parts.scheme.lower() not in ("http", "https") or not parts.hostname
                    or parts.username is not None or parts.password is not None):
                raise ApiError(400, f"links must be http:// or https:// addresses of at most {MAX_LINK_LEN} characters")
            filtered(user, url, "link")
            links.append(url)
        if len(links) > MAX_LINKS:
            raise ApiError(400, f"at most {MAX_LINKS} links")
        return links

    def clean_version_label(user: User, value: Any) -> str:
        label = clean_short(value, "version label", MAX_VERSION_LABEL_LEN) or ""
        return filtered(user, label, "version label") or ""

    def clean_mc(value: Any) -> str | None:
        if value is None or (isinstance(value, str) and not value.strip()):
            return None
        if not isinstance(value, str) or not MC_VERSION_RE.match(value.strip()):
            raise ApiError(400, "mc must be a Minecraft version such as 26.3")
        return value.strip()

    def board_filters(params: Any, strict: bool) -> dict[str, Any]:
        """{tags, size, complexity, mc, author} from ?tag=&size=&complexity=&mc=&author=; strict raises 400 for
        unknown values instead of ignoring them."""
        raw = params.getlist("tag")
        tags = [t for t in TAGS if t in raw]
        if strict and len(tags) < len({t for t in raw if t}):
            clean_tags([t for t in raw if t])  # raises, naming the unknown ones
        out: dict[str, Any] = {"tags": tags}
        for key, known in (("size", SIZE_LABELS), ("complexity", COMPLEXITY_LABELS)):
            value = params.get(key) or None
            if value is not None and value not in known:
                if strict:
                    raise ApiError(400, f"{key} must be one of " + ", ".join(known))
                value = None
            out[key] = value
        mc = (params.get("mc") or "").strip() or None
        if mc is not None and not MC_VERSION_RE.match(mc):
            if strict:
                raise ApiError(400, "mc must be a Minecraft version such as 26.3")
            mc = None
        author = params.get("author") or None
        if author is not None and parse_uuid(author) is None:
            if strict:
                raise ApiError(400, "author must be a uuid")
            author = None
        out["mc"], out["author"] = mc, parse_uuid(author) if author else None
        return out

    def board_limit(params: Any) -> int:
        """?limit= (1 to MAX_BOARD_LIMIT) for the API, else the board's page size."""
        raw = params.get("limit") or ""
        if not raw:
            return settings.board_page_size
        if not raw.isdigit() or not 1 <= int(raw) <= MAX_BOARD_LIMIT:
            raise ApiError(400, f"limit must be a number from 1 to {MAX_BOARD_LIMIT}")
        return int(raw)

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
        filters = board_filters(request.query_params, strict=True)
        size = board_limit(request.query_params)
        user = optional_user(request) if tab == "public" else authenticate(request, mutating=False)
        posts, total = store.list_posts(tab, user.uuid if user else None, sort, query, page, size, **filters)
        favs = store.favorite_ids(user.uuid, [p.id for p in posts]) if user else None
        out: dict[str, Any] = {
            "posts": [post_api(p, user, favs=favs) for p in posts],
            "tab": tab, "sort": sort, "q": query, **filters, "page": page,
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
        coauthors = clean_coauthors(user, body.coauthors)
        links = clean_links(user, body.links)
        label, mc = clean_version_label(user, body.versionLabel), clean_mc(body.mc)
        credit = clean_credit(user, body.credit)
        group = posting_group(user, body.group) if body.group is not None else None
        folder = clean_folder(user, body.folder) if group is not None else ""
        if group is not None and group.post_visibility == "group":
            visibility = "private"  # members only whatever it says; stays private should it leave the group
        limit = settings.max_posts_per_user if not user.is_admin else 10**9  # admins curate collections
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
                        "links": json.dumps(links), "version_label": label, "mc_version": mc,
                        "credit": credit, "group_id": group.id if group else None, "group_folder": folder,
                        **snapshot_columns(snap),
                    }, allowed, tags, coauthors)
                except BaseException:
                    shutil.rmtree(pdir, ignore_errors=True)
                    store.delete_post_row(post_id)
                    raise
                store.set_listing(user.uuid, path, post_id)  # My files marks the file as on the board
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
        """The author changes anything. A group's admins and moderators may change the title, description, tags,
        credit and folder of a post in their group, or take it out of the group (group: null)."""
        post, user = acting_on_post(request, post_id, owner_only=False)
        given = body.model_fields_set
        if post.uuid != user.uuid:
            group = store.get_group(post.group_id) if post.group_id else None
            if group is None or not standing(group, user).manager:
                raise ApiError(403, "only the author can do that")
            if given - {"title", "description", "tags", "credit", "group", "folder"} or ("group" in given and body.group not in (None, post.group_id)):
                raise ApiError(403, "group admins and moderators can only change the title, description, tags, credit"
                                    " and folder of a post, or take it out of the group")
        values: dict[str, Any] = {}
        if "title" in given:
            values["title"] = filtered(user, clean_title(body.title), "title")
        if "description" in given:
            values["description"] = filtered(user, clean_description(body.description), "description")
        if "visibility" in given:
            values["visibility"] = clean_visibility(body.visibility)
        if "links" in given:
            values["links"] = json.dumps(clean_links(user, body.links))
        if "versionLabel" in given:  # of the current version
            values["version_label"] = clean_version_label(user, body.versionLabel)
        if "mc" in given:
            values["mc_version"] = clean_mc(body.mc)
        if "credit" in given:
            values["credit"] = clean_credit(user, body.credit)
        if "group" in given and body.group != post.group_id:
            values.update(group_values(posting_group(user, body.group) if body.group is not None else None))
            values["group_folder"] = clean_folder(user, body.folder) if body.group is not None and "folder" in given else ""
        elif "folder" in given:
            if post.group_id is None:
                raise ApiError(409, "the post isn't in a group")
            values["group_folder"] = clean_folder(user, body.folder)
        if "group_id" not in values and post.group_only and "visibility" in values:
            values["visibility"] = "private"  # members only in its group, whatever it says
        allowed = None
        if "allowed" in given:
            if body.allowed is None:
                raise ApiError(400, "allowed must be a list of Minecraft usernames")
            allowed = resolve_names(body.allowed, store.get_allowed(post.id))
        tags = clean_tags(body.tags) if "tags" in given else None
        coauthors = clean_coauthors(user, body.coauthors) if "coauthors" in given else None
        store.update_post(post.id, values, allowed, tags, coauthors)
        return post_api(store.get_post(post.id), user, detail=True)  # type: ignore[arg-type]

    @app.post("/api/board/{post_id}/refresh")
    def board_refresh(post_id: str, request: Request) -> dict[str, Any]:
        """Re-take the current version's snapshot from the same cloud file (no new version)."""
        post, user = acting_on_post(request, post_id, owner_only=True)
        if not post.source_path or store.get_file(user.uuid, post.source_path) is None:
            raise ApiError(409, f"{post.source_path} is no longer in your cloud files" if post.source_path
                           else "this version was uploaded directly, not from your cloud files")
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
                store.set_listing(user.uuid, post.source_path, post.id)
        finally:
            snap.cleanup()
        return post_api(store.get_post(post.id), user, detail=True)  # type: ignore[arg-type]

    # ---- versions: a new snapshot keeps the old ones in posts/<id>/v<n>/ (the quota counts them all)

    def post_version(post: PostRow, request: Request) -> tuple[VersionRow, Path]:
        """The version ?version=<n> asks for (the current one without it) and its folder; 404 if there is none."""
        raw = request.query_params.get("version") or ""
        pdir = store.post_dir(post.id)
        if not raw or raw == str(post.version):
            return VersionRow.of_post(post), pdir
        ver = store.get_version(post.id, int(raw)) if raw.isdigit() and len(raw) <= 6 else None
        if ver is None:
            raise ApiError(404, "no such version")
        return ver, pdir / f"v{ver.num}"

    @app.get("/api/board/{post_id}/versions")
    def board_versions(post_id: str, request: Request) -> dict[str, Any]:
        post, _ = visible_post(request, post_id)
        return {"versions": [v.api() for v in (VersionRow.of_post(post), *store.list_versions(post.id))]}

    @app.post("/api/board/{post_id}/versions")
    async def board_new_version(post_id: str, request: Request) -> JSONResponse:
        """A new version: JSON {"path", "label"?, "mc"?} snapshots one of the owner's cloud files (its thumbnail comes
        along); any other body is the schematic itself, named by ?filename= (plus ?label=&mc=)."""
        post, user = acting_on_post(request, post_id, owner_only=True)
        if request.headers.get("content-type", "").split(";")[0].strip().lower() == "application/json":
            raw, _, _ = await read_capped(request, 64 * 1024)
            try:
                data = json.loads(raw or b"{}")
            except ValueError:
                raise ApiError(400, "invalid request body")
            if not isinstance(data, dict):
                raise ApiError(400, "invalid request body")
            body = VersionIn(**{k: data.get(k) for k in ("path", "label", "mc")})
            if not isinstance(body.path, str):
                raise ApiError(400, "path is required")
            path = validate_path(body.path)
            label, mc = clean_version_label(user, body.label), clean_mc(body.mc)
            with store.lock:
                require_file(user, path)
                check_quota(user, 1)
            snap = await run_in_threadpool(take_snapshot, user, path)
            source = path
        else:
            q = request.query_params
            name = (q.get("filename") or "").strip()
            if "/" in name:
                raise ApiError(400, "filename must be a plain file name")
            validate_path(name)
            label, mc = clean_version_label(user, q.get("label")), clean_mc(q.get("mc"))
            with store.lock:
                check_quota(user, 1)
            snap = await upload_snapshot(request, name)
            source = ""  # not one of the cloud files: refresh has nothing to re-read
        try:
            await run_in_threadpool(install_version, post.id, user, snap, label, mc, source)
        finally:
            snap.cleanup()
        log.info("%s uploaded version %d of board post %s", user.name, post.version_count + 1, post.id)
        return JSONResponse(post_api(store.get_post(post.id), user, detail=True), status_code=201)  # type: ignore[arg-type]

    def install_version(post_id: str, user: User, snap: Snapshot, label: str, mc: str | None, source: str) -> None:
        """Archive the current snapshot as an earlier version and install snap as the next one."""
        with store.lock:
            current = store.get_post(post_id)
            if current is None:
                raise ApiError(404, "no such post")
            check_quota(user, snap.size)
            pdir = store.post_dir(post_id)
            vdir = pdir / f"v{current.version}"
            with store.transaction():
                archive_snapshot(pdir, current)
                try:
                    install_snapshot(pdir, snap)
                    values = {**snapshot_columns(snap), "version": current.version + 1, "version_label": label,
                              "mc_version": mc, "source_path": source}
                    sets = ", ".join(f"{k}=:{k}" for k in values)
                    store.db.execute(f"UPDATE posts SET {sets} WHERE id=:id", {**values, "id": post_id})
                except BaseException:  # put the old snapshot back where it was
                    for f in vdir.iterdir():
                        os.replace(f, pdir / f.name)
                    shutil.rmtree(vdir, ignore_errors=True)
                    raise
            if source:
                store.set_listing(user.uuid, source, post_id)

    @app.delete("/api/board/{post_id}/versions/{num}")
    def board_delete_version(post_id: str, num: int, request: Request) -> Response:
        """Delete an earlier version (frees its bytes). The current one goes only with the post."""
        post, _ = acting_on_post(request, post_id, owner_only=True)
        if num == post.version:
            raise ApiError(409, "this is the current version; upload a newer one first, or delete the post")
        with store.lock:
            if not store.delete_version(post.id, num):
                raise ApiError(404, "no such version")
            shutil.rmtree(store.post_dir(post.id) / f"v{num}", ignore_errors=True)
        return Response(status_code=204)

    # ---- download counts: website downloads, exports and "Add to my cloud", once per account (never the
    # author's own), or once per address and day logged out. The address is only kept as a keyed hash.

    def count_download(post: PostRow, request: Request, user: User | None) -> None:
        if user is not None:
            if user.uuid != post.uuid:
                store.count_post_download(post.id, "u:" + user.uuid)
            return
        day = time.strftime("%Y%m%d", time.gmtime())
        digest = hmac.new(store.ip_salt, f"{day}:{client_ip(request)}".encode(), hashlib.sha256).hexdigest()[:24]
        store.count_post_download(post.id, f"ip:{day}:{digest}")

    @app.delete("/api/board/{post_id}")
    def board_delete(post_id: str, request: Request) -> Response:
        post, user = acting_on_post(request, post_id, owner_only=True, admin_ok=True)
        with store.lock:
            # Files saved from it stay where they are (hard links keep the bytes, copies are copies) and, no longer
            # matching a snapshot, count for their owners from now on.
            (refs,) = store.db.execute("SELECT COUNT(*) FROM files WHERE source_post=?", (post.id,)).fetchone()
            store.delete_post_row(post.id)
            shutil.rmtree(store.post_dir(post.id), ignore_errors=True)
        if refs:
            log.info("board post %s was deleted: %d saved copies now count for their owners", post.id, refs)
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
        """Add a snapshot to the caller's files as a reference: a hard link to the post's snapshot where the disk
        allows it (else a copy). It costs the caller nothing while it still matches one of the post's versions."""
        post, user = acting_on_post(request, post_id, owner_only=False)
        require_download(post, user)
        ver, vdir = post_version(post, request)
        ext = "." + ver.format if ver.format else ver.filename[ver.filename.rfind("."):]
        stem = board_file_stem(post.title, ext)
        snapshot = store._under(vdir, ver.filename)
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".save")
        os.close(fd)
        tmp = Path(tmp_name)
        try:
            tmp.unlink()
            try:
                os.link(snapshot, tmp)  # the same bytes on disk: no room taken
                size, digest = ver.size, ver.sha256
            except FileNotFoundError:
                raise ApiError(404, "no such post")
            except OSError:  # no hard links on this file system: a real copy, which takes room on the disk
                with store.lock:
                    store.check_site(ver.size)
                with tmp.open("wb") as f:
                    try:
                        size, digest = copy_hashed(snapshot, f)
                    except FileNotFoundError:
                        raise ApiError(404, "no such post")
            with store.lock:
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
                thumb = vdir / POST_THUMB
                has_thumb = False
                if ver.has_thumb and thumb.is_file():
                    dest_thumb = store.thumb_path(user.uuid, path)
                    dest_thumb.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(thumb, dest_thumb)
                    has_thumb = True
                source = FileSource(post.id, post.title, post.uuid, post.author_name)
                store.put_file_row(user.uuid, FileRow(path, size, digest, int(time.time() * 1000), has_thumb, source))
                row = store.get_file(user.uuid, path)
        finally:
            tmp.unlink(missing_ok=True)
        count_download(post, request, user)
        if user.uuid != post.uuid:
            store.count_save(post.id, user.uuid)
        return JSONResponse(row.api(), status_code=201)  # type: ignore[union-attr]

    # ---- favourites: a bookmark per account, never a copy (so it costs nobody storage)

    def favorite_state(post_id: str, user: User) -> dict[str, Any]:
        post = store.get_post(post_id)
        return {"favorites": post.favorites if post else 0, "favorited": post_id in store.favorite_ids(user.uuid, [post_id])}

    @app.post("/api/board/{post_id}/favorite")
    def board_favorite(post_id: str, request: Request) -> dict[str, Any]:
        post, user = acting_on_post(request, post_id, owner_only=False)
        if user.uuid != post.uuid and (post.visibility == "private" or post.hidden is not None):
            raise ApiError(403, "only public and unlisted posts can be favourited", code="private")
        store.set_favorite(post.id, user.uuid, True)
        return favorite_state(post.id, user)

    @app.delete("/api/board/{post_id}/favorite")
    def board_unfavorite(post_id: str, request: Request) -> dict[str, Any]:
        """Also works for a favourite whose post the caller can no longer see (it became private or hidden)."""
        post = store.get_post(post_id) if POST_ID_RE.match(post_id) else None
        user = member_auth(request, mutating=True)
        if post is None or not (can_view(post, user) or post.id in store.favorite_ids(user.uuid, [post.id])):
            raise ApiError(404, "no such post")
        store.set_favorite(post.id, user.uuid, False)
        return favorite_state(post.id, user)

    @app.get("/api/favorites")
    def favorites_list(request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=False)
        posts = store.list_favorites(user.uuid)
        favs = {p.id for p in posts}
        return {"posts": [post_api(p, user, favs=favs) for p in posts]}

    # ---- My listings: the caller's own posts and how they are doing

    def listing_order(params: Any, strict: bool) -> tuple[str, bool]:
        """(sort key, high to low) from ?sort=&order=; strict raises 400 instead of defaulting."""
        sort = params.get("sort") or "created"
        if sort not in LISTING_SORTS:
            if strict:
                raise ApiError(400, "sort must be one of " + ", ".join(LISTING_SORTS))
            sort = "created"
        order = params.get("order") or ("desc" if LISTING_SORTS[sort] else "asc")
        if order not in ("asc", "desc"):
            if strict:
                raise ApiError(400, "order must be asc or desc")
            order = "desc" if LISTING_SORTS[sort] else "asc"
        return sort, order == "desc"

    def sorted_listings(posts: list[PostRow], sort: str, desc: bool) -> list[PostRow]:
        key = {
            "created": lambda p: p.created, "updated": lambda p: p.updated, "title": lambda p: p.title.casefold(),
            "visibility": lambda p: (VISIBILITIES.index(p.visibility), p.hidden is not None),
            "score": lambda p: p.score, "ups": lambda p: p.ups, "downs": lambda p: p.downs,
            "downloads": lambda p: p.downloads, "favorites": lambda p: p.favorites, "saves": lambda p: p.saves,
            "versions": lambda p: p.version_count, "size": lambda p: p.total_bytes,
        }[sort]
        out = sorted(posts, key=lambda p: (-p.created, p.id))  # ties: newest first, either way
        return sorted(out, key=key, reverse=desc)

    def listing_api(post: PostRow, user: User, favs: set[str]) -> dict[str, Any]:
        return {**post_api(post, user, favs=favs), "saves": post.saves, "bytes": post.total_bytes,
                "page": f"{settings.site_url}/board/{post.id}", "editPage": f"{settings.site_url}/board/{post.id}#manage"}

    @app.get("/api/listings")
    def listings(request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=False)
        sort, desc = listing_order(request.query_params, strict=True)
        posts = sorted_listings(store.own_posts(user.uuid), sort, desc)
        favs = store.favorite_ids(user.uuid, [p.id for p in posts])
        return {"listings": [listing_api(p, user, favs) for p in posts], "sort": sort, "order": "desc" if desc else "asc"}

    @app.get("/api/board/{post_id}/file")
    def board_file(post_id: str, request: Request) -> FileResponse:
        post, user = visible_post(request, post_id)
        require_download(post, user)
        ver, vdir = post_version(post, request)
        disk = store._under(vdir, ver.filename)
        if not disk.is_file():
            raise ApiError(404, "no such post")
        count_download(post, request, user)
        return FileResponse(
            disk,
            media_type="application/octet-stream",
            filename=ver.filename,
            headers={"X-Sha256": ver.sha256, "Cache-Control": "private, no-cache"},
        )

    @app.get("/api/board/{post_id}/thumbnail")
    def board_thumbnail(post_id: str, request: Request) -> FileResponse:
        post, _ = visible_post(request, post_id)
        ver, vdir = post_version(post, request)
        thumb = vdir / POST_THUMB
        if not ver.has_thumb or not thumb.is_file():
            raise ApiError(404, "no thumbnail")
        return FileResponse(thumb, media_type="image/png", headers={"Cache-Control": "private, max-age=86400"})

    @app.get("/api/board/{post_id}/export")
    def board_export(post_id: str, request: Request) -> FileResponse:
        post, user = visible_post(request, post_id)
        require_download(post, user)
        ver, vdir = post_version(post, request)
        fmt = export_format(request)
        disk = store._under(vdir, ver.filename)
        if not disk.is_file():
            raise ApiError(404, "no such post")
        resp = export_response(disk, ver.filename, fmt)
        count_download(post, request, user)
        return resp

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
        ver, vdir = post_version(post, request)
        disk = store._under(vdir, ver.filename)
        if not disk.is_file():
            raise ApiError(404, "no such post")
        key = MODEL_CACHE_PREFIX + ver.sha256[:16]
        cache, big = vdir / f"{key}.json.gz", vdir / f"{key}.toobig"
        if not cache.is_file() and not big.is_file():
            with conversion_slot():
                if not cache.is_file() and not big.is_file():  # another request may have built it meanwhile
                    build_model(post.id, ver, disk, cache, big)
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

    def build_model(post_id: str, ver: VersionRow, disk: Path, cache: Path, big: Path) -> None:
        try:
            schem = read_schematic(disk, ver.filename, settings.max_export_volume)
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
            if ver.current:
                current = store.get_post(post_id)
                stale = current is None or current.sha256 != ver.sha256 or current.version != ver.num
            else:
                stale = store.get_version(post_id, ver.num) is None
            if stale:
                Path(tmp_name).unlink(missing_ok=True)  # refreshed, replaced or deleted meanwhile: don't cache
                if dest is big:
                    raise too_big(int(payload))
                raise ApiError(409, "the post changed while its 3D view was built, try again")
            os.replace(tmp_name, dest)

    @app.get("/b/{post_id}")
    def share_link(post_id: str, request: Request) -> Response:
        """The short share link: redirects to the post page (which answers 404 to those who can't see it)."""
        if not POST_ID_RE.match(post_id):
            return not_found_page(request)
        return RedirectResponse(f"{base}/board/{post_id}", status_code=302)

    # ---- groups: players sharing builds. Admins (the owner is always one) run it, moderators look after members and
    # listings, members do what the group's permissions (or their own) allow. Site admins act as a group's admins.

    def standing(group: GroupRow, user: User | None) -> Standing:
        if user is None:
            return Standing(group, None)
        row = store.group_member(group.id, user.uuid)
        if row is not None:
            return Standing(group, user.uuid, row["role"], parse_perms(row["perms"]) if row["perms"] else None,
                            site_admin=user.is_admin)
        return Standing(group, user.uuid, site_admin=user.is_admin,
                        banned=store.group_ban(group.id, user.uuid) is not None,
                        requested=store.group_request(group.id, user.uuid) is not None)

    def find_group(group_id: str, user: User | None) -> tuple[GroupRow, Standing]:
        """The group and user's standing in it. 404 if there is none, or it is private and user isn't in it."""
        group = store.get_group(group_id) if GROUP_ID_RE.match(group_id) else None
        st = standing(group, user) if group is not None else None
        if group is None or st is None or not st.can_see:
            raise ApiError(404, "no such group")
        return group, st

    def acting_on_group(request: Request, group_id: str) -> tuple[GroupRow, Standing, User]:
        """For changes: 401/403 for auth, CSRF and access, then 404 if the caller can't see the group."""
        user = member_auth(request, mutating=True)
        group, st = find_group(group_id, user)
        return group, st, user

    def require_manager(st: Standing) -> None:
        if not st.manager:
            raise ApiError(403, "only the group's admins and moderators can do that")

    def require_group_admin(st: Standing) -> None:
        if not st.admin:
            raise ApiError(403, "only the group's admins can do that")

    def check_over(group: GroupRow, st: Standing, uuid: str, role: str | None, done: str) -> None:
        """403 unless st may remove or ban the player uuid (role: theirs in the group, None if not a member): the owner
        never; admins and moderators only by admins; plain members (and outsiders) by moderators too."""
        require_manager(st)
        if uuid == group.owner:
            raise ApiError(403, f"the owner can't be {done}; transfer ownership first", code="owner")
        if role in MANAGER_ROLES and not st.admin:
            raise ApiError(403, "only the group's admins can act on its admins and moderators")

    def group_values(group: GroupRow | None) -> dict[str, Any]:
        """The post columns for putting a post in group (None: out of its group). In a members-only group a post is
        private, so it stays private if it ever leaves the group."""
        values: dict[str, Any] = {"group_id": group.id if group else None, "group_hidden": None, "group_folder": ""}
        if group is not None and group.post_visibility == "group":
            values["visibility"] = "private"
        return values

    def group_api(group: GroupRow, st: Standing | None = None, role: str | None = None) -> dict[str, Any]:
        """A group for the API. With st (the caller's standing), what the caller may do there."""
        out: dict[str, Any] = {
            "id": group.id, "slug": group.slug, "name": group.name, "description": group.description,
            "visibility": group.visibility, "joinPolicy": group.join_policy, "postPolicy": group.post_policy,
            "postVisibility": group.post_visibility, "memberPerms": group.perms,
            "owner": {"uuid": group.owner, "name": group.owner_name, "displayName": group.owner_shown},
            "members": group.members, "builds": group.builds, "created": group.created, "updated": group.updated,
            "url": f"{settings.site_url}/g/{group.slug}", "role": st.role if st is not None else role,
        }
        if st is not None and st.uuid is not None:
            out.update(canPost=st.can_post, perms=st.perms if st.member else None, requested=st.requested, banned=st.banned)
        return out

    def member_api(group: GroupRow, r: sqlite3.Row) -> dict[str, Any]:
        override = parse_perms(r["perms"]) if r["perms"] else None
        return {
            "uuid": r["uuid"], "name": r["mc_name"], "displayName": r["shown"], "role": r["role"],
            "owner": r["uuid"] == group.owner, "joined": r["joined"], "hasAccount": bool(r["has_account"]),
            "perms": override, "effective": Standing(group, r["uuid"], r["role"], override).perms,
        }

    def members_api(group: GroupRow, st: Standing) -> dict[str, Any]:
        """The member list; for the group's admins and moderators also the join requests and bans."""
        out: dict[str, Any] = {"members": [member_api(group, r) for r in store.group_members(group.id)]}
        if st.manager:
            out["requests"] = [{"uuid": r["uuid"], "name": r["mc_name"], "displayName": r["shown"], "message": r["message"],
                                "created": r["created"]} for r in store.group_requests(group.id)]
            out["bans"] = [{"uuid": r["uuid"], "name": r["mc_name"], "displayName": r["shown"], "created": r["created"]}
                           for r in store.group_bans(group.id)]
        return out

    def clean_choice(value: Any, choices: Any, what: str) -> str:
        if value not in choices:
            raise ApiError(400, f"{what} must be one of " + ", ".join(choices))
        return value

    def clean_perms(value: Any, what: str) -> dict[str, bool]:
        """{"post" | "invite" | "download": true | false}, any of them."""
        if not isinstance(value, dict) or any(k not in MEMBER_PERMS or not isinstance(v, bool) for k, v in value.items()):
            raise ApiError(400, f"{what} must be an object of " + ", ".join(MEMBER_PERMS) + " set to true or false")
        return {k: value[k] for k in MEMBER_PERMS if k in value}

    def clean_group_settings(user: User, body: GroupIn, given: set[str]) -> dict[str, Any]:
        """The groups columns for the fields given (name and description are filtered)."""
        values: dict[str, Any] = {}
        if "name" in given:
            name = clean_short(body.name, "name", MAX_GROUP_NAME_LEN)
            if name is None:
                raise ApiError(400, "name is required")
            values["name"] = filtered(user, " ".join(name.split()), "name")
        if "description" in given:
            values["description"] = filtered(user, clean_long(body.description, "description", MAX_GROUP_DESCRIPTION_LEN,
                                                              required=False), "description")
        for key, column, choices in (("visibility", "visibility", GROUP_VISIBILITIES), ("joinPolicy", "join_policy", JOIN_POLICIES),
                                     ("postPolicy", "post_policy", POST_POLICIES),
                                     ("postVisibility", "post_visibility", POST_VISIBILITY_RULES)):
            if key in given:
                values[column] = clean_choice(getattr(body, key), choices, key)
        if "memberPerms" in given:
            values["member_perms"] = clean_perms(body.memberPerms, "memberPerms")
        return values

    def group_player(group: GroupRow, text: Any) -> tuple[str, str]:
        """(uuid, Minecraft name) of a player named by uuid (an account here, or someone the group knows) or by name,
        looked up through Mojang unless the group already knows them (members, requests, bans)."""
        if not isinstance(text, str) or not text.strip():
            raise ApiError(400, "give a Minecraft name")
        known = [(r["uuid"], r["mc_name"]) for rows in (store.group_members(group.id), store.group_requests(group.id),
                                                        store.group_bans(group.id)) for r in rows]
        uuid = parse_uuid(text)
        if uuid is not None:
            account = store.get_user(uuid)
            name = account.name if account else next((n for u, n in known if u == uuid), None)
            if name is None:
                raise ApiError(404, "no account with that uuid has logged in here")
            return uuid, name
        return resolve_names([text], known)[0]

    def group_rate(user: User, kind: str, limit: int, what: str) -> None:
        """Count one more action of kind for user (site admins aren't limited): 429 past limit per LOGIN_WINDOW."""
        if user.is_admin:
            return
        key = f"group-{kind}:{user.uuid}"
        with store.lock:
            rate_limited(key, limit, what)
            store.add_failure(key)

    @app.get("/api/groups")
    def groups_list(request: Request) -> dict[str, Any]:
        """Public groups (most members first), then the caller's other groups; role is the caller's in each."""
        user = optional_user(request)
        mine = {g.id: g for g in store.groups_of(user.uuid)} if user else {}
        out = [group_api(g, role=mine[g.id].role if g.id in mine else None) for g in store.public_groups()]
        out += [group_api(g, role=g.role) for g in mine.values() if g.visibility != "public"]
        return {"groups": out}

    @app.post("/api/groups")
    def group_create(request: Request, body: GroupIn) -> JSONResponse:
        user = member_auth(request, mutating=True)
        given = body.model_fields_set - {"slug"}
        if "name" not in given:
            raise ApiError(400, "name is required")
        values = {"description": "", "visibility": "public", "join_policy": "open", "post_policy": "members",
                  "post_visibility": "any", "member_perms": {}, **clean_group_settings(user, body, given)}
        if not user.is_admin and store.owned_groups(user.uuid) >= GROUPS_PER_USER:
            raise ApiError(409, f"you already own {GROUPS_PER_USER} groups, which is the limit")
        group_rate(user, "create", GROUP_CREATE_LIMIT, "new groups")
        now = int(time.time() * 1000)
        with store.lock:
            group_id = store.new_group_id()
            store.insert_group({
                "id": group_id, "slug": store.free_slug(values["name"]), "owner": user.uuid, "created": now, "updated": now,
                **values, "member_perms": json.dumps(values["member_perms"]),
            }, user.name)
        group = store.get_group(group_id)
        assert group is not None
        log.info("%s made the group %s (%s)", user.name, group.id, group.name)
        return JSONResponse(group_api(group, standing(group, user)), status_code=201)

    @app.get("/api/groups/{group_id}")
    def group_get(group_id: str, request: Request) -> dict[str, Any]:
        user = optional_user(request)
        group, st = find_group(group_id, user)
        return group_api(group, st)

    @app.patch("/api/groups/{group_id}")
    def group_patch(group_id: str, request: Request, body: GroupIn) -> dict[str, Any]:
        """Settings, for the group's admins. memberPerms changes only the permissions given."""
        group, st, user = acting_on_group(request, group_id)
        require_group_admin(st)
        given = body.model_fields_set
        values = clean_group_settings(user, body, given)
        if "member_perms" in values:
            values["member_perms"] = json.dumps({**group.member_perms, **values["member_perms"]})
        if "slug" in given and body.slug != group.slug:
            slug = body.slug.strip().lower() if isinstance(body.slug, str) else ""
            if not SLUG_RE.match(slug):
                raise ApiError(400, f"slug must be 1 to {MAX_SLUG_LEN} lowercase letters, digits and dashes,"
                                    " starting and ending with a letter or digit")
            values["slug"] = filtered(user, slug, "slug")
        with store.lock:
            if "slug" in values and store.group_by_slug(values["slug"]) is not None:
                raise ApiError(409, "another group already has that address")
            if values:
                store.update_group(group.id, values)
        group = store.get_group(group.id)  # type: ignore[assignment]
        return group_api(group, standing(group, user))

    @app.delete("/api/groups/{group_id}")
    def group_delete(group_id: str, request: Request) -> Response:
        """Delete the group (its admins). Its posts stay, outside any group; those that were members only stay private."""
        group, st, user = acting_on_group(request, group_id)
        require_group_admin(st)
        released = store.delete_group(group.id)
        log.info("%s deleted the group %s (%s); %d posts left it", user.name, group.id, group.name, released)
        return Response(status_code=204)

    @app.post("/api/groups/{group_id}/transfer")
    def group_transfer(group_id: str, request: Request, body: PlayerIn) -> dict[str, Any]:
        """Hand the group to another member (an account here), who becomes an admin and the owner. The owner or a site
        admin only; the old owner stays an admin."""
        group, st, user = acting_on_group(request, group_id)
        if not (st.owner or st.site_admin):
            raise ApiError(403, "only the owner can hand the group over")
        text = body.player.strip() if isinstance(body.player, str) else ""
        uuid = parse_uuid(text)
        target = next((r for r in store.group_members(group.id)
                       if r["uuid"] == uuid or (uuid is None and r["mc_name"].lower() == text.lower())), None)
        if target is None:
            raise ApiError(404, "hand the group to one of its members")
        if target["uuid"] == group.owner:
            raise ApiError(409, "they already own it")
        if not target["has_account"]:
            raise ApiError(409, f"{target['mc_name']} has to log in here once first")
        store.transfer_group(group.id, target["uuid"])
        log.info("%s handed the group %s to %s", user.name, group.id, target["mc_name"])
        group = store.get_group(group.id)  # type: ignore[assignment]
        return group_api(group, standing(group, user))

    def clean_folder_query(value: str | None, strict: bool) -> str:
        """The ?folder= of a group listing, normalised like clean_folder ('' for the top)."""
        parts = [part.strip() for part in (value or "").strip("/").split("/") if part.strip()]
        if len(parts) > MAX_FOLDER_DEPTH or any(len(part) > MAX_FOLDER_NAME_LEN for part in parts):
            if strict:
                raise ApiError(400, "no such folder")
            return ""
        return "/".join(parts)

    @app.get("/api/groups/{group_id}/posts")
    def group_posts(group_id: str, request: Request) -> dict[str, Any]:
        """The group's builds the caller may see: the same items, sorts, filters and paging as GET /api/board."""
        _, sort, query, page = board_query(request.query_params, strict=True)
        filters = board_filters(request.query_params, strict=True)
        size = board_limit(request.query_params)
        user = optional_user(request)
        group, st = find_group(group_id, user)
        # Without ?folder= (clients from before folders): every post, as one flat list, and no folders.
        folder = clean_folder_query(request.query_params["folder"], strict=True) if "folder" in request.query_params else None
        posts, total = store.list_posts("group", user.uuid if user else None, sort, query, page, size, group=st,
                                        folder=folder, **filters)
        favs = store.favorite_ids(user.uuid, [p.id for p in posts]) if user else None
        return {
            "posts": [post_api(p, user, favs=favs) for p in posts], "group": group.id, "folder": folder,
            "folders": [] if query.strip() or folder is None else store.group_folders(st, user.uuid if user else None, folder),
            "sort": sort, "q": query, **filters, "page": page, "pageSize": size, "total": total,
            "pages": max(1, -(-total // size)),
        }

    @app.post("/api/groups/{group_id}/posts")
    def group_add_post(group_id: str, request: Request, body: GroupPostIn) -> dict[str, Any]:
        """Put one of the caller's posts in the group (out of the one it was in)."""
        group, _, user = acting_on_group(request, group_id)
        if not isinstance(body.post, str):
            raise ApiError(400, "post is required")
        post = find_post(body.post, user)
        if post.uuid != user.uuid:
            raise ApiError(403, "only the author can put a post in a group")
        if post.group_id != group.id:
            store.update_post(post.id, group_values(posting_group(user, group.id)))
        return post_api(store.get_post(post.id), user, detail=True)  # type: ignore[arg-type]

    def group_post(group: GroupRow, post_id: str) -> PostRow:
        post = store.get_post(post_id) if POST_ID_RE.match(post_id) else None
        if post is None or post.group_id != group.id:
            raise ApiError(404, "no such post in this group")
        return post

    @app.delete("/api/groups/{group_id}/posts/{post_id}")
    def group_remove_post(group_id: str, post_id: str, request: Request) -> Response:
        """Take a post out of the group (its author, or the group's admins and moderators). The post itself stays."""
        group, st, user = acting_on_group(request, group_id)
        post = group_post(group, post_id)
        if post.uuid != user.uuid:
            require_manager(st)
        store.update_post(post.id, group_values(None))
        return Response(status_code=204)

    @app.post("/api/groups/{group_id}/posts/{post_id}/hide")
    def group_hide_post(group_id: str, post_id: str, request: Request, body: HideIn) -> dict[str, Any]:
        """Hide a post in the group, or show it again (the group's admins and moderators): a hidden one isn't listed
        there, and members who only saw it through the group no longer do."""
        group, st, user = acting_on_group(request, group_id)
        require_manager(st)
        post = group_post(group, post_id)
        if not isinstance(body.hidden, bool):
            raise ApiError(400, "hidden must be true or false")
        store.update_post(post.id, {"group_hidden": int(time.time() * 1000) if body.hidden else None})
        log.info("%s %s post %s in the group %s", user.name, "hid" if body.hidden else "unhid", post.id, group.id)
        return {"id": post.id, "hidden": body.hidden}

    @app.post("/api/groups/{group_id}/join")
    def group_join(group_id: str, request: Request) -> dict[str, Any]:
        group, st, user = acting_on_group(request, group_id)
        if st.member:
            raise ApiError(409, "you are already a member")
        if st.banned:
            raise ApiError(403, "you can't join this group", code="banned")
        if group.join_policy == "request":
            raise ApiError(409, "this group takes requests to join; ask instead", code="request_required")
        if group.join_policy == "invite":
            raise ApiError(403, "this group is invite only", code="invite_only")
        store.add_member(group.id, user.uuid, user.name)
        group = store.get_group(group.id)  # type: ignore[assignment]
        return group_api(group, standing(group, user))

    @app.post("/api/groups/{group_id}/leave")
    def group_leave(group_id: str, request: Request) -> Response:
        group, st, user = acting_on_group(request, group_id)
        if not st.member:
            raise ApiError(409, "you are not a member")
        if st.owner:
            raise ApiError(409, "the owner can't leave; transfer ownership or delete the group", code="owner")
        store.remove_member(group.id, user.uuid)
        return Response(status_code=204)

    @app.post("/api/groups/{group_id}/requests")
    def group_request(group_id: str, request: Request, body: JoinRequestIn) -> JSONResponse:
        """Ask to join a group whose join policy is request."""
        group, st, user = acting_on_group(request, group_id)
        if st.member:
            raise ApiError(409, "you are already a member")
        if st.banned:
            raise ApiError(403, "you can't join this group", code="banned")
        if group.join_policy == "open":
            raise ApiError(409, "anyone can join this group; just join", code="open")
        if group.join_policy == "invite":
            raise ApiError(403, "this group is invite only", code="invite_only")
        if st.requested:
            raise ApiError(409, "you already asked; the group's admins and moderators will look at it")
        message = filtered(user, clean_long(body.message, "message", MAX_JOIN_MESSAGE_LEN, required=False), "message") or ""
        group_rate(user, "request", GROUP_REQUEST_LIMIT, "requests to join groups")
        store.add_group_request(group.id, user.uuid, message)
        return JSONResponse(group_api(group, standing(group, user)), status_code=201)

    @app.delete("/api/groups/{group_id}/requests")
    def group_request_withdraw(group_id: str, request: Request) -> Response:
        group, _, user = acting_on_group(request, group_id)
        if not store.delete_group_request(group.id, user.uuid):
            raise ApiError(404, "you haven't asked to join")
        return Response(status_code=204)

    @app.get("/api/groups/{group_id}/requests")
    def group_requests(group_id: str, request: Request) -> dict[str, Any]:
        group, st = find_group(group_id, authenticate(request, mutating=False))
        require_manager(st)
        return {"requests": members_api(group, st)["requests"]}

    def decide_request(group_id: str, uuid: str, request: Request, approve: bool) -> dict[str, Any]:
        group, st, user = acting_on_group(request, group_id)
        require_manager(st)
        asker = parse_uuid(uuid)
        row = next((r for r in store.group_requests(group.id) if r["uuid"] == asker), None)
        if row is None:
            raise ApiError(404, "no such request")
        if approve:
            store.add_member(group.id, row["uuid"], row["mc_name"])
        else:
            store.delete_group_request(group.id, row["uuid"])
        log.info("%s %s the request of %s to join the group %s", user.name, "approved" if approve else "denied",
                 row["mc_name"], group.id)
        return members_api(group, st)

    @app.post("/api/groups/{group_id}/requests/{uuid}/approve")
    def group_request_approve(group_id: str, uuid: str, request: Request) -> dict[str, Any]:
        return decide_request(group_id, uuid, request, approve=True)

    @app.post("/api/groups/{group_id}/requests/{uuid}/deny")
    def group_request_deny(group_id: str, uuid: str, request: Request) -> dict[str, Any]:
        return decide_request(group_id, uuid, request, approve=False)

    @app.get("/api/groups/{group_id}/members")
    def group_members(group_id: str, request: Request) -> dict[str, Any]:
        group, st = find_group(group_id, optional_user(request))
        return members_api(group, st)

    @app.post("/api/groups/{group_id}/members")
    def group_member_add(group_id: str, request: Request, body: PlayerIn) -> JSONResponse:
        """Add a player by Minecraft name (or uuid): the group's admins and moderators, or members allowed to invite.
        They become a plain member straight away."""
        group, st, user = acting_on_group(request, group_id)
        if not st.may("invite"):
            raise ApiError(403, "you can't add people to this group")
        group_rate(user, "add", GROUP_ADD_LIMIT, "players added")
        uuid, name = group_player(group, body.player)
        if store.group_ban(group.id, uuid) is not None:
            raise ApiError(409, f"{name} is banned from this group; lift the ban first", code="banned")
        if not store.add_member(group.id, uuid, name):
            raise ApiError(409, f"{name} is already a member")
        log.info("%s added %s to the group %s", user.name, name, group.id)
        return JSONResponse(members_api(group, st), status_code=201)

    def group_member_row(group: GroupRow, uuid: str) -> sqlite3.Row:
        dashed = parse_uuid(uuid)
        row = store.group_member(group.id, dashed) if dashed else None
        if row is None:
            raise ApiError(404, "no such member")
        return row

    @app.patch("/api/groups/{group_id}/members/{uuid}")
    def group_member_patch(group_id: str, uuid: str, request: Request, body: MemberPatchIn) -> dict[str, Any]:
        """role (the group's admins), and perms: a plain member's own permissions over the group's defaults (admins
        and moderators; null for none)."""
        group, st, user = acting_on_group(request, group_id)
        row = group_member_row(group, uuid)
        given = body.model_fields_set
        values: dict[str, Any] = {}
        role = row["role"]
        if "role" in given:
            if not st.admin:
                raise ApiError(403, "only the group's admins can change roles")
            role = clean_choice(body.role, GROUP_ROLES, "role")
            if row["uuid"] == group.owner and role != "admin":
                raise ApiError(403, "the owner is always an admin; transfer ownership first", code="owner")
            values["role"] = role
        if "perms" in given:
            require_manager(st)
            if role != "member":
                raise ApiError(409, "admins and moderators may do everything; permissions are for plain members")
            perms = clean_perms(body.perms, "perms") if body.perms is not None else {}
            values["perms"] = json.dumps(perms) if perms else None
        if not values:
            raise ApiError(400, "give role or perms")
        store.set_member(group.id, row["uuid"], values)
        return members_api(group, st)

    @app.delete("/api/groups/{group_id}/members/{uuid}")
    def group_member_remove(group_id: str, uuid: str, request: Request) -> dict[str, Any]:
        group, st, user = acting_on_group(request, group_id)
        row = group_member_row(group, uuid)
        if row["uuid"] == user.uuid:
            raise ApiError(400, "to leave the group, use leave")
        check_over(group, st, row["uuid"], row["role"], "removed")
        store.remove_member(group.id, row["uuid"])
        log.info("%s removed %s from the group %s", user.name, row["name"], group.id)
        return members_api(group, st)

    @app.post("/api/groups/{group_id}/bans")
    def group_ban(group_id: str, request: Request, body: PlayerIn) -> JSONResponse:
        """Ban a player (member or not) by name or uuid: out of the group, their request gone, and no joining again."""
        group, st, user = acting_on_group(request, group_id)
        require_manager(st)
        group_rate(user, "add", GROUP_ADD_LIMIT, "players added")
        uuid, name = group_player(group, body.player)
        if uuid == user.uuid:
            raise ApiError(400, "you can't ban yourself")
        row = store.group_member(group.id, uuid)
        check_over(group, st, uuid, row["role"] if row else None, "banned")
        store.ban_from_group(group.id, uuid, name, user.uuid)
        log.info("%s banned %s from the group %s", user.name, name, group.id)
        return JSONResponse(members_api(group, st), status_code=201)

    @app.delete("/api/groups/{group_id}/bans/{uuid}")
    def group_unban(group_id: str, uuid: str, request: Request) -> dict[str, Any]:
        group, st, _ = acting_on_group(request, group_id)
        require_manager(st)
        dashed = parse_uuid(uuid)
        if dashed is None or not store.unban_from_group(group.id, dashed):
            raise ApiError(404, "no such ban")
        return members_api(group, st)

    # ---- settings profiles: named sets of the mod's settings, per account

    def profile_api(r: sqlite3.Row) -> dict[str, Any]:
        return {"id": r["id"], "name": r["name"], "updated": r["updated"]}

    def profile_limit(user: User) -> int | None:
        return None if user.is_admin else PROFILE_LIMIT

    def find_profile(user: User, profile_id: str) -> sqlite3.Row:
        r = store.get_profile(user.uuid, profile_id) if PROFILE_ID_RE.match(profile_id) else None
        if r is None:
            raise ApiError(404, "no such profile")
        return r

    async def json_object_body(request: Request, cap: int) -> dict[str, Any]:
        raw, _, _ = await read_capped(request, cap)
        try:
            data = json.loads(raw)
        except ValueError:
            raise ApiError(400, "invalid request body")
        if not isinstance(data, dict):
            raise ApiError(400, "invalid request body")
        return data

    def clean_profile_name(user: User, value: Any) -> str:
        if not isinstance(value, str):
            raise ApiError(400, "name is required")
        name = value.strip()
        if not 1 <= len(name) <= MAX_PROFILE_NAME_LEN:
            raise ApiError(400, f"name must be 1 to {MAX_PROFILE_NAME_LEN} characters")
        if TITLE_BAD_CHARS.search(name):
            raise ApiError(400, "name may not contain control characters or line breaks")
        return filtered(user, name, "profile name")  # type: ignore[return-value]

    def clean_settings(value: Any) -> str:
        if not isinstance(value, dict):
            raise ApiError(400, "settings must be a JSON object")
        text = json.dumps(value, separators=(",", ":"), ensure_ascii=False)
        if len(text.encode()) > MAX_PROFILE_SETTINGS:
            raise ApiError(413, f"settings must be at most {MAX_PROFILE_SETTINGS // 1024} KiB", maxBytes=MAX_PROFILE_SETTINGS)
        return text

    PROFILE_BODY_CAP = MAX_PROFILE_SETTINGS + 4096  # room for the name and the JSON around the settings

    @app.get("/api/profiles")
    def profiles_list(request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=False)
        return {"profiles": [profile_api(r) for r in store.list_profiles(user.uuid)], "limit": profile_limit(user)}

    @app.get("/api/profiles/{profile_id}")
    def profile_get(profile_id: str, request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=False)
        r = find_profile(user, profile_id)
        return {**profile_api(r), "settings": json.loads(r["settings"])}

    @app.post("/api/profiles")
    async def profile_create(request: Request) -> JSONResponse:
        user = member_auth(request, mutating=True)
        data = await json_object_body(request, PROFILE_BODY_CAP)
        name = clean_profile_name(user, data.get("name"))
        text = clean_settings(data.get("settings"))
        limit = profile_limit(user)
        with store.lock:
            if limit is not None and len(store.list_profiles(user.uuid)) >= limit:
                raise ApiError(409, f"you can keep at most {limit} settings profiles; delete one first", code="limit")
            t = now_s()
            pid = store.new_profile_id()
            store.insert_row("profiles", {"id": pid, "uuid": user.uuid, "name": name, "settings": text,
                                          "created": t, "updated": t})
        return JSONResponse({"id": pid, "name": name, "updated": t}, status_code=201)

    @app.put("/api/profiles/{profile_id}")
    async def profile_update(profile_id: str, request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=True)
        find_profile(user, profile_id)
        data = await json_object_body(request, PROFILE_BODY_CAP)
        values: dict[str, Any] = {}
        if "settings" in data:
            values["settings"] = clean_settings(data["settings"])
        if data.get("name") is not None:
            values["name"] = clean_profile_name(user, data["name"])
        if not values:
            raise ApiError(400, "send settings (and optionally a name)")
        values["updated"] = now_s()
        with store.lock:
            find_profile(user, profile_id)  # deleted meanwhile?
            sets = ", ".join(f"{k}=:{k}" for k in values)
            store.db.execute(f"UPDATE profiles SET {sets} WHERE id=:id", {**values, "id": profile_id})
            return profile_api(find_profile(user, profile_id))

    @app.delete("/api/profiles/{profile_id}")
    def profile_delete(profile_id: str, request: Request) -> Response:
        user = member_auth(request, mutating=True)
        with store.lock:
            find_profile(user, profile_id)
            store.db.execute("DELETE FROM profiles WHERE id=?", (profile_id,))
        return Response(status_code=204)

    # ---- telemetry: anonymous usage reports from the mod. Nothing that identifies a player is stored: not the
    # address (rate limits are counted in memory, under a keyed hash), and board post ids only of public posts.

    telemetry_hits: dict[str, list[float]] = {}  # rate-limit key -> times of recent reports (monotonic)
    telemetry_lock = threading.Lock()

    def telemetry_allowed(limits: list[tuple[str, int]]) -> bool:
        """Count a report against every (key, limit); False (and nothing counted) if one is full."""
        now = time.monotonic()
        with telemetry_lock:
            if len(telemetry_hits) > 10000:
                for k in [k for k, v in telemetry_hits.items() if not v or v[-1] < now - TELEMETRY_WINDOW]:
                    del telemetry_hits[k]
            recent = {k: [t for t in telemetry_hits.get(k, []) if t > now - TELEMETRY_WINDOW] for k, _ in limits}
            telemetry_hits.update(recent)
            if any(len(recent[k]) >= limit for k, limit in limits):
                return False
            for k, _ in limits:
                telemetry_hits[k].append(now)
            return True

    def tele_str(d: dict[str, Any], key: str, limit: int) -> str | None:
        v = d.get(key)
        if v is None:
            return None
        if not isinstance(v, str):
            raise ApiError(400, f"{key} must be text")
        return TITLE_BAD_CHARS.sub("", v).strip()[:limit] or None

    def tele_num(d: dict[str, Any], key: str, hi: float, integer: bool = True) -> Any:
        v = d.get(key)
        if v is None:
            return None
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0 <= v <= hi:
            raise ApiError(400, f"{key} must be a number from 0 to {hi:g}")
        return int(v) if integer else round(float(v), 2)

    def tele_obj(d: dict[str, Any], key: str) -> dict[str, Any]:
        v = d.get(key)
        if v is None:
            return {}
        if not isinstance(v, dict):
            raise ApiError(400, f"{key} must be an object")
        return v

    def tele_quick(d: dict[str, Any]) -> dict[str, Any]:
        """The Quick Menu and wheel part of a report, keeping only well-formed values."""
        def slots(v: Any, n: int) -> list[str] | None:
            if not isinstance(v, list) or len(v) != n:
                return None
            return [a if isinstance(a, str) and (a == "" or QUICK_ACTION_RE.match(a)) else "" for a in v]

        out: dict[str, Any] = {}
        for key, choices in (("quickStyle", ("HOTBAR", "WHEEL")), ("quickMode", ("CLICK", "RELEASE"))):
            if d.get(key) in choices:
                out[key] = d[key]
        for key in ("quickCloseAfterUse", "wheelCloseAfterUse", "wheelResetPage"):
            if isinstance(d.get(key), bool):
                out[key] = d[key]
        if (hotbar := slots(d.get("quickMenu"), 9)) is not None:
            out["quickMenu"] = hotbar
        pages = d.get("wheelPages")
        if isinstance(pages, list) and 0 < len(pages) <= 9:
            checked = [slots(p, 8) for p in pages]
            if all(p is not None for p in checked):
                out["wheelPages"] = checked
        return out

    def tele_list(d: dict[str, Any], key: str) -> list[Any]:
        v = d.get(key)
        if v is None:
            return []
        if not isinstance(v, list):
            raise ApiError(400, f"{key} must be a list")
        return v

    def public_source(source: str) -> str:
        """board:<id> stays only for a public post; else board:private, board:unlisted or board:gone."""
        if not source.startswith("board:"):
            return source
        post = store.get_post(source[6:]) if POST_ID_RE.match(source[6:]) else None
        if post is None:
            return "board:gone"
        if post.hidden is not None or post.visibility == "private" or post.group_only:
            return "board:private"
        return "board:unlisted" if post.visibility == "unlisted" else source

    @app.post("/api/telemetry")
    async def telemetry_ingest(request: Request) -> Response:
        """The mod deletes a report on 2xx or 400 and keeps it (to retry) on anything else."""
        try:
            data = await json_object_body(request, MAX_TELEMETRY_BYTES)
        except ApiError as e:
            if e.status == 413:  # never acceptable: 400 so the mod drops it instead of retrying forever
                raise ApiError(400, f"a report must be at most {MAX_TELEMETRY_BYTES // 1024} KiB")
            raise
        install = data.get("install")
        if not isinstance(install, str) or not 1 <= len(install.strip()) <= 100:
            raise ApiError(400, "install is required")
        specs, render_ = tele_obj(data, "specs"), tele_obj(data, "render")
        far = 4_102_444_800  # 2100-01-01
        values: dict[str, Any] = {
            "install": hashlib.sha256(install.strip().encode()).hexdigest(),
            "mod_version": tele_str(data, "mod", 40), "mc_version": tele_str(data, "mc", 40),
            "loader": tele_str(data, "loader", 20),
            "started": tele_num(data, "start", far), "ended": tele_num(data, "end", far),
            "os": tele_str(specs, "os", 100), "cpu_cores": tele_num(specs, "cpuCores", 4096),
            "max_memory_mb": tele_num(specs, "maxMemoryMb", 1 << 24), "gpu": tele_str(specs, "gpu", 200),
            "gpu_vendor": tele_str(specs, "gpuVendor", 100), "backend": tele_str(specs, "backend", 60),
            "render_distance": tele_num(specs, "renderDistance", 512), "width": tele_num(specs, "width", 1 << 16),
            "height": tele_num(specs, "height", 1 << 16),
            "frames": tele_num(render_, "frames", 1e12), "avg_ms": tele_num(render_, "avgMs", 1e6, False),
            "p50_ms": tele_num(render_, "p50Ms", 1e6, False), "p99_ms": tele_num(render_, "p99Ms", 1e6, False),
            "over33_ms": tele_num(render_, "over33Ms", 1e12), "max_sections": tele_num(render_, "maxSections", 1e9),
        }
        sources: list[str] = []
        for src in tele_list(data, "schematics")[:100]:
            if isinstance(src, str) and SOURCE_RE.match(src):
                src = public_source(src) if src not in ("board:private", "board:unlisted", "board:gone") else src
                if src not in sources:
                    sources.append(src)
        changed = sorted({n for n in tele_list(data, "settingsChanged")[:300] if isinstance(n, str) and SETTING_NAME_RE.match(n)})
        keys: dict[str, int] = {}
        for k, n in list(tele_obj(data, "keys").items())[:200]:
            if SETTING_NAME_RE.match(k) and type(n) is int and 0 <= n <= 10**9:
                keys[k] = n
        values.update(received=now_s(), schematics=json.dumps(sources), settings_changed=json.dumps(changed),
                      keys=json.dumps(keys), quick=json.dumps(tele_quick(data)))
        ip_key = hmac.new(store.ip_salt, client_ip(request).encode(), hashlib.sha256).hexdigest()[:24]
        if not telemetry_allowed([("ip:" + ip_key, TELEMETRY_IP_LIMIT), ("install:" + values["install"], TELEMETRY_INSTALL_LIMIT)]):
            raise ApiError(429, "too many reports for now, try again later")
        store.add_telemetry(values)
        return Response(status_code=204)

    def telemetry_summary() -> dict[str, Any]:
        """What the admin page's Telemetry tab shows: the last 30 days, mostly counted per install."""
        t = now_s()
        rows = store.telemetry_since(t - 30 * DAY)
        week = [r for r in rows if r["received"] >= t - 7 * DAY]
        latest = {r["install"]: r for r in rows}  # rows come oldest first

        def median(values: list[float]) -> float | None:
            values = sorted(values)
            if not values:
                return None
            mid = len(values) // 2
            return values[mid] if len(values) % 2 else round((values[mid - 1] + values[mid]) / 2, 2)

        def frame_stats(key: Any) -> list[dict[str, Any]]:
            groups: dict[str, list[sqlite3.Row]] = {}
            for r in rows:
                if r["frames"] and r["p50_ms"] is not None:
                    groups.setdefault(key(r), []).append(r)
            out = [{"key": k, "reports": len(g), "installs": len({r["install"] for r in g}),
                    "p50": median([r["p50_ms"] for r in g]), "p99": median([r["p99_ms"] for r in g if r["p99_ms"] is not None])}
                   for k, g in groups.items()]
            return sorted(out, key=lambda x: (-x["reports"], x["key"]))

        settings_count: dict[str, int] = {}
        for r in latest.values():
            for name in json.loads(r["settings_changed"]):
                settings_count[name] = settings_count.get(name, 0) + 1
        keys: dict[str, list[int]] = {}  # name -> [uses, installs]
        key_installs: dict[str, set[str]] = {}
        sources: dict[str, int] = {}
        posts: dict[str, set[str]] = {}
        for r in rows:
            for name, n in json.loads(r["keys"]).items():
                keys.setdefault(name, [0, 0])[0] += n
                key_installs.setdefault(name, set()).add(r["install"])
            for src in json.loads(r["schematics"]):
                bucket = "board (public)" if src.startswith("board:") and src not in (
                    "board:private", "board:unlisted", "board:gone") else src
                sources[bucket] = sources.get(bucket, 0) + 1
                if bucket == "board (public)":
                    posts.setdefault(src[6:], set()).add(r["install"])
        for name, installs in key_installs.items():
            keys[name][1] = len(installs)
        top_posts = []
        for pid, installs in sorted(posts.items(), key=lambda kv: (-len(kv[1]), kv[0])):
            post = store.get_post(pid)
            if post is not None and post.visibility == "public" and post.hidden is None and not post.group_only:  # still public now
                top_posts.append({"id": pid, "title": post.title, "installs": len(installs)})
            if len(top_posts) == 10:
                break
        styles: dict[str, int] = {}
        hotbar_actions: dict[str, int] = {}
        wheel_actions: dict[str, int] = {}
        wheel_pages: dict[str, int] = {}
        for r in latest.values():
            quick = json.loads(r["quick"]) if r["quick"] else {}
            if "quickStyle" in quick:
                styles[quick["quickStyle"].title()] = styles.get(quick["quickStyle"].title(), 0) + 1
            for action in set(quick.get("quickMenu", [])) - {""}:
                hotbar_actions[action] = hotbar_actions.get(action, 0) + 1
            if "wheelPages" in quick:
                n = str(len(quick["wheelPages"]))
                wheel_pages[n] = wheel_pages.get(n, 0) + 1
                for action in {a for p in quick["wheelPages"] for a in p} - {""}:
                    wheel_actions[action] = wheel_actions.get(action, 0) + 1
        oses: dict[str, int] = {}
        memory: dict[str, int] = {}
        for r in latest.values():
            oses[os_family(r["os"])] = oses.get(os_family(r["os"]), 0) + 1
            memory[memory_bucket(r["max_memory_mb"])] = memory.get(memory_bucket(r["max_memory_mb"]), 0) + 1

        def ranked(d: dict[str, int], limit: int = 30) -> list[dict[str, Any]]:
            return [{"name": k, "count": v} for k, v in sorted(d.items(), key=lambda kv: (-kv[1], kv[0]))[:limit]]

        return {
            "reports7": len(week), "installs7": len({r["install"] for r in week}),
            "reports30": len(rows), "installs30": len(latest),
            "byGpuVendor": frame_stats(lambda r: gpu_vendor(r["gpu_vendor"])),
            "byModVersion": frame_stats(lambda r: r["mod_version"] or "unknown"),
            "settings": ranked(settings_count),
            "keys": [{"name": k, "uses": v[0], "installs": v[1]} for k, v in sorted(keys.items(), key=lambda kv: (-kv[1][0], kv[0]))],
            "sources": ranked(sources), "topPosts": top_posts,
            "quickStyles": ranked(styles), "hotbarActions": ranked(hotbar_actions), "wheelActions": ranked(wheel_actions),
            "wheelPages": ranked(wheel_pages),
            "os": ranked(oses), "memory": [{"name": b, "count": memory.get(b, 0)} for b in MEMORY_BUCKETS if memory.get(b)],
        }

    @app.get("/api/admin/telemetry")
    def admin_telemetry(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        return telemetry_summary()

    # ---- identity: display names, pictures, Discord

    def clean_display_name(user: User, value: Any) -> str:
        if not isinstance(value, str):
            raise ApiError(400, "name must be text")
        name = " ".join(value.split())
        if not MIN_DISPLAY_NAME_LEN <= len(name) <= MAX_DISPLAY_NAME_LEN:
            raise ApiError(400, f"a display name is {MIN_DISPLAY_NAME_LEN} to {MAX_DISPLAY_NAME_LEN} characters")
        if TITLE_BAD_CHARS.search(name) or "#" in name or not any(c.isalnum() for c in name):
            raise ApiError(400, "a display name needs a letter or digit, and may not contain # or control characters")
        return filtered(user, name, "display name")  # type: ignore[return-value]

    @app.put("/api/account/display-name")
    def set_display_name(request: Request, body: DisplayNameIn) -> dict[str, Any]:
        """{"name"?, "tag"?}: name null goes back to the Minecraft name; a tag is chosen when the name is taken
        (and the answer warns), unless one is given (null or "": none, if nobody else uses the name plainly)."""
        user = member_auth(request, mutating=True)
        given = body.model_fields_set
        if not given & {"name", "tag"}:
            raise ApiError(400, "send a name, a tag or both")
        if "name" in given and body.name is None:
            name, custom = user.name, False  # follows Minecraft renames again
        elif "name" in given:
            name, custom = clean_display_name(user, body.name), True
        else:
            name, custom = user.shown, True
        if "tag" in given:
            tag = body.tag.strip() if isinstance(body.tag, str) else body.tag
            if tag in (None, ""):
                tag = None
            elif not isinstance(tag, str) or not DISPLAY_TAG_RE.match(tag):
                raise ApiError(400, "a tag is 3 to 5 letters or digits")
            else:
                filtered(user, tag, "tag")
            tag = store.set_display(user.uuid, name, tag, custom=custom)
        else:
            tag = store.set_display(user.uuid, name, custom=custom)
        out: dict[str, Any] = {"displayName": name, "tag": tag}
        if tag and "tag" not in given:
            out["warning"] = (f"Someone else already uses the name {name}, so yours carries the tag #{tag}. Only you and "
                              "admins see it; you can change it to another 3 to 5 letters or digits.")
        return out

    def minecraft_face(uuid: str) -> bytes:
        """The player's face from their skin, cached in skins/ for a day (a failed fetch is retried after
        SKIN_RETRY, meanwhile the old face or the placeholder is shown). Only accounts and whitelisted players
        are looked up, so the endpoint can't be used to make us query Mojang for anyone."""
        face, fail = store.skins_dir / f"{uuid}.face.png", store.skins_dir / f"{uuid}.fail"

        def fresh(p: Path, ttl: int) -> bool:
            try:
                return time.time() - p.stat().st_mtime < ttl
            except OSError:
                return False

        def fallback() -> bytes:
            try:
                return face.read_bytes()
            except OSError:
                return images.placeholder_face()

        if fresh(face, SKIN_TTL):
            return fallback()
        if not store.known_player(uuid) or fresh(fail, SKIN_RETRY):
            return fallback()
        with skin_locks.setdefault(uuid, threading.Lock()):
            if fresh(face, SKIN_TTL):  # fetched by another request meanwhile
                return fallback()
            try:
                skin = fetch_skin(settings, uuid)
            except MojangUnavailable as e:
                log.info("could not fetch the skin of %s: %s", uuid, e)
                fail.touch()
                return fallback()
            data = images.placeholder_face()
            if skin is not None:
                atomic_write(store.skins_dir / f"{uuid}.png", skin)
                try:
                    data = images.face_png(skin)
                except ValueError as e:
                    log.info("could not read the skin of %s: %s", uuid, e)
            atomic_write(face, data)
            fail.unlink(missing_ok=True)
            return data

    skin_locks: dict[str, threading.Lock] = {}

    def atomic_write(dest: Path, data: bytes) -> None:
        fd, tmp_name = tempfile.mkstemp(dir=store.tmp, suffix=".part")
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp_name, dest)

    @app.get("/avatar/{uuid}")
    def avatar(uuid: str) -> Response:
        """A player's picture: their custom icon, else their Minecraft face. Served from here so the pages need
        no image hosts but their own."""
        dashed = parse_uuid(uuid)
        if dashed is None:
            raise ApiError(404, "no such player")
        headers = {"Cache-Control": "public, max-age=3600"}
        user = store.get_user(dashed)
        if user is not None and user.avatar in AVATAR_TYPES:
            icon = store.avatar_path(dashed, user.avatar)
            if icon.is_file():
                return FileResponse(icon, media_type=AVATAR_TYPES[user.avatar], headers=headers)
        return Response(minecraft_face(dashed), media_type="image/png", headers=headers)

    @app.put("/api/account/avatar")
    async def avatar_upload(request: Request) -> dict[str, Any]:
        """A custom icon: the raw PNG, JPEG or WebP, at most MAX_AVATAR_BYTES and MAX_AVATAR_SIDE pixels a side.
        The account page crops and scales pictures to 256 x 256 before sending them."""
        user = member_auth(request, mutating=True)
        data, _, _ = await read_capped(request, MAX_AVATAR_BYTES)
        kind, dims = "png", images.png_size(data)
        if dims is None:
            kind, dims = "jpeg", images.jpeg_size(data)
        if dims is None:
            kind, dims = "webp", images.webp_size(data)
        if dims is None:
            raise ApiError(400, "the icon must be a PNG or JPEG image (or a WebP)")
        w, h = dims
        if not (0 < w <= MAX_AVATAR_SIDE and 0 < h <= MAX_AVATAR_SIDE):
            raise ApiError(400, f"the icon must be at most {MAX_AVATAR_SIDE} x {MAX_AVATAR_SIDE} pixels (this one is {w} x {h})")
        with store.lock:
            store.check_site(len(data))
            dest = store.avatar_path(user.uuid, kind)
            dest.parent.mkdir(parents=True, exist_ok=True)
            atomic_write(dest, data)
            for other in AVATAR_TYPES:
                if other != kind:
                    store.avatar_path(user.uuid, other).unlink(missing_ok=True)
            store.set_avatar(user.uuid, kind)
            user = store.get_user(user.uuid)  # type: ignore[assignment]
        return {"avatar": avatar_url(user.uuid, user.avatar_version, absolute=True), "custom": True}

    @app.delete("/api/account/avatar")
    def avatar_reset(request: Request) -> dict[str, Any]:
        """Back to the Minecraft face."""
        user = member_auth(request, mutating=True)
        with store.lock:
            for kind in AVATAR_TYPES:
                store.avatar_path(user.uuid, kind).unlink(missing_ok=True)
            store.set_avatar(user.uuid, None)
            user = store.get_user(user.uuid)  # type: ignore[assignment]
        return {"avatar": avatar_url(user.uuid, user.avatar_version, absolute=True), "custom": False}

    def account_redirect(result: str) -> RedirectResponse:
        return RedirectResponse(f"{base}/account?discord={result}#discord", status_code=303)

    @app.get("/account/discord/link")
    def discord_link(request: Request) -> Response:
        """Start linking: off to Discord with a state tied to this browser session (checked by the callback)."""
        user = session_user(request)
        if user is None:
            return RedirectResponse(f"{base}/", status_code=303)
        if not settings.discord_enabled:
            return account_redirect("off")
        if not is_member(user):
            return RedirectResponse(f"{base}/apply", status_code=303)
        session = request.cookies.get(SESSION_COOKIE, "")
        state = store.issue_token(user.uuid, "discord", DISCORD_STATE_TTL, sha256_hex(session))
        query = urllib.parse.urlencode({
            "response_type": "code", "client_id": settings.discord_client_id, "scope": "identify",
            "state": state, "redirect_uri": settings.discord_redirect, "prompt": "consent",
        })
        return RedirectResponse(f"{DISCORD_AUTHORIZE}?{query}", status_code=303)

    @app.get("/account/discord/callback")
    def discord_callback(request: Request) -> Response:
        user = session_user(request)
        if user is None:
            return RedirectResponse(f"{base}/", status_code=303)
        if not settings.discord_enabled:
            return account_redirect("off")
        q = request.query_params
        state = q.get("state") or ""
        found = store.consume_token(state, "discord") if state else None  # single use either way
        session = request.cookies.get(SESSION_COOKIE, "")
        if found is None or found[0].uuid != user.uuid or found[1] != sha256_hex(session):
            log.info("Discord callback for %s with a missing, expired or foreign state", user.name)
            return account_redirect("state")
        if q.get("error") or not q.get("code"):
            return account_redirect("cancelled")
        try:
            discord_id, discord_name = discord_user(settings, discord_token(settings, q["code"]))
        except DiscordError as e:
            log.warning("Discord linking failed for %s: %s", user.name, e)
            return account_redirect("failed")
        try:
            store.set_discord(user.uuid, discord_id, discord_name)
        except ApiError:
            return account_redirect("taken")
        log.info("%s linked the Discord account %s (%s)", user.name, discord_name, discord_id)
        return account_redirect("linked")

    @app.delete("/api/account/discord")
    def discord_unlink(request: Request) -> dict[str, Any]:
        user = member_auth(request, mutating=True)
        store.set_discord(user.uuid, None, None)
        return {"discord": None}

    @app.get("/api/users/{uuid}")
    def public_user(uuid: str) -> dict[str, Any]:
        """A player's public face: display name (no tag), badges and picture."""
        dashed = parse_uuid(uuid)
        target = store.get_user(dashed) if dashed else None
        if target is None:
            raise ApiError(404, "no such user")
        return {"uuid": target.uuid, "displayName": target.shown, "badges": store.badges_of(target.uuid),
                "avatar": avatar_url(target.uuid, target.avatar_version, absolute=True),
                "page": f"{settings.site_url}/u/{target.uuid}"}

    @app.get("/api/users/{uuid}/card")
    def user_card(uuid: str, request: Request) -> JSONResponse:
        """What the hover card on a player's name shows. The tag only for admins, so the answer is cached by
        the browser alone (private), per login."""
        dashed = parse_uuid(uuid)
        target = store.get_user(dashed) if dashed else None
        if target is None:
            raise ApiError(404, "no such user")
        viewer = optional_user(request)
        contributions, joined = store.user_card_stats(target.uuid)
        return JSONResponse({
            "uuid": target.uuid, "name": target.name, "displayName": target.shown,
            "tag": target.display_tag if viewer is not None and viewer.is_admin else None,
            "badges": store.badges_of(target.uuid), "avatarVersion": target.avatar_version,
            "contributions": contributions, "joined": joined * 1000,
        }, headers={"Cache-Control": f"private, max-age={USER_CARD_MAX_AGE}", "Vary": "Cookie, Authorization"})

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
        store.check_site(len(content))  # admins too
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

    def attachment_api(feedback_id: int, a: sqlite3.Row) -> dict[str, Any]:
        return {"kind": a["kind"], "filename": a["filename"], "size": a["size"], "sha256": a["sha256"],
                "url": f"{settings.site_url}/api/feedback/{feedback_id}/attachments/{a['kind']}"}

    def feedback_api(r: sqlite3.Row, attachments: list[sqlite3.Row] | None = None) -> dict[str, Any]:
        """A feedback item. attachments: its rows in feedback_files when known (lists), else looked up."""
        if attachments is None:
            attachments = store.feedback_attachments([r["id"]]).get(r["id"], [])
        return {
            "id": r["id"], "type": r["kind"], "message": r["message"], "modVersion": r["mod_version"],
            "mcVersion": r["mc_version"], "contact": r["contact"],
            "user": {"uuid": r["uuid"], "name": r["name"]} if r["uuid"] else None,
            "created": r["created"], "resolved": r["resolved"],
            "attachments": [attachment_api(r["id"], a) for a in attachments],
        }

    def feedback_items(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
        files = store.feedback_attachments([r["id"] for r in rows])
        return [feedback_api(r, files.get(r["id"], [])) for r in rows]

    def attachment_name(raw: str, default: str) -> str:
        """The name a file part was sent with as one plain, harmless segment (else default)."""
        name = BAD_SEGMENT_CHARS.sub("", raw.replace("\\", "/").rsplit("/", 1)[-1]).strip().lstrip(".").strip()
        if len(name) > MAX_SEGMENT_LEN:
            stem, dot, ext = name.rpartition(".")
            name = (stem[: MAX_SEGMENT_LEN - len(ext) - 1] + dot + ext) if dot and len(ext) < 12 else name[:MAX_SEGMENT_LEN]
        return name or default

    def clean_log(raw_name: str, data: bytes) -> tuple[str, bytes]:
        """The game's log: text (no NUL bytes), or gzip of text; at most MAX_FEEDBACK_LOG as sent."""
        if len(data) > MAX_FEEDBACK_LOG:
            raise ApiError(413, f"the log must be at most {MAX_FEEDBACK_LOG // (1024 * 1024)} MiB", maxBytes=MAX_FEEDBACK_LOG)
        text = data
        if data.startswith(GZIP_MAGIC):
            inflate = zlib.decompressobj(16 + zlib.MAX_WBITS)
            try:
                text = inflate.decompress(data, MAX_LOG_TEXT)
            except zlib.error:
                raise ApiError(400, "the log is not a valid gzip file")
            if inflate.unconsumed_tail or not inflate.eof:
                raise ApiError(400, "the log is not a valid gzip file, or unpacks to more than "
                                    f"{MAX_LOG_TEXT // (1024 * 1024)} MiB")
        if b"\x00" in text:
            raise ApiError(400, "the log must be a text file (such as latest.log), plain or gzipped")
        name = attachment_name(raw_name, "latest.log")
        if data.startswith(GZIP_MAGIC) and not name.lower().endswith(".gz"):
            name += ".gz"
        return name, data

    def clean_attached_schematic(raw_name: str, data: bytes) -> tuple[str, bytes]:
        """A schematic in any supported format (by its name), gzipped like all of them; not parsed, since a
        bug report may be about a file the game or the server can't read."""
        if len(data) > MAX_FEEDBACK_SCHEMATIC:
            raise ApiError(413, f"the schematic must be at most {MAX_FEEDBACK_SCHEMATIC // (1024 * 1024)} MiB",
                           maxBytes=MAX_FEEDBACK_SCHEMATIC)
        name = attachment_name(raw_name, "")
        if convert.format_of(name) is None:
            raise ApiError(400, "the schematic must be a .slopmatic, .nbt, .litematic or .schem file")
        if not data.startswith(GZIP_MAGIC):
            raise ApiError(400, "the schematic is not a gzip file (every schematic format is gzipped NBT)")
        return name, data

    @app.post("/api/feedback")
    async def feedback_submit(request: Request) -> JSONResponse:
        """JSON, or multipart/form-data with the same fields as text parts plus optional file parts log and
        schematic. Login optional (bearer token or session cookie)."""
        if request.headers.get("authorization"):
            user: User | None = authenticate(request, mutating=True)
        else:
            # logged out too: the header keeps other sites from posting through a visitor's browser
            if request.headers.get(CSRF_HEADER) != CSRF_VALUE:
                raise ApiError(403, f"missing X-Requested-With: {CSRF_VALUE} header")
            user = session_user(request)
        ctype = request.headers.get("content-type", "")
        uploads: dict[str, tuple[str, bytes]] = {}
        if ctype.split(";")[0].strip().lower() == "multipart/form-data":
            raw, _, _ = await read_capped(request, MAX_FEEDBACK_LOG + MAX_FEEDBACK_SCHEMATIC + MAX_FEEDBACK_JSON)
            data: Any
            data, uploads = parse_multipart(raw, ctype)
            if isinstance(data.get("log"), str) and "log" not in uploads:  # the log sent as a plain text field
                uploads["log"] = ("latest.log", data.pop("log").encode())
        else:
            raw, _, _ = await read_capped(request, MAX_FEEDBACK_JSON)
            try:
                data = json.loads(raw)
            except ValueError:
                raise ApiError(400, "invalid request body")
            if not isinstance(data, dict):
                raise ApiError(400, "invalid request body")
        body = FeedbackIn(**{k: data.get(k) for k in ("type", "message", "modVersion", "mcVersion", "contact")})
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
        attachments: list[tuple[str, str, bytes]] = []  # (kind, file name, bytes)
        for kind, clean in (("log", clean_log), ("schematic", clean_attached_schematic)):
            name, content = uploads.get(kind, ("", b""))
            if content:  # a browser sends an empty part for a file input left empty
                attachments.append((kind, *clean(name, content)))
        if user is not None:
            key, limit = f"feedback-user:{user.uuid}", FEEDBACK_USER_LIMIT
        else:
            key, limit = f"feedback-ip:{client_ip(request)}", FEEDBACK_IP_LIMIT

        def save() -> sqlite3.Row:
            with store.lock:
                rate_limited(key, limit, "messages")
                store.check_site(sum(len(content) for _, _, content in attachments))
                store.add_failure(key)
                t = int(time.time() * 1000)
                with store.transaction():
                    feedback_id = store.add_feedback({
                        "kind": body.type, "message": message, "mod_version": mod_version, "mc_version": mc_version,
                        "contact": contact, "uuid": user.uuid if user else None, "name": user.name if user else None,
                        "created": t,
                    })
                    written: list[Path] = []
                    try:
                        for kind, name, content in attachments:
                            dest = store.feedback_file(feedback_id, kind)
                            dest.parent.mkdir(parents=True, exist_ok=True)
                            atomic_write(dest, content)
                            written.append(dest)
                            store.db.execute(
                                "INSERT INTO feedback_files(feedback_id, kind, filename, size, sha256, created) VALUES(?,?,?,?,?,?)",
                                (feedback_id, kind, name, len(content), hashlib.sha256(content).hexdigest(), t))
                    except BaseException:
                        for f in written:
                            f.unlink(missing_ok=True)
                        raise
                return store.db.execute("SELECT * FROM feedback WHERE id=?", (feedback_id,)).fetchone()

        row = await run_in_threadpool(save)
        if attachments:
            log.info("feedback %d arrived with %s", row["id"], ", ".join(f"{k} {n!r} ({len(c)} bytes)" for k, n, c in attachments))
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
        return {"feedback": feedback_items(store.list_feedback(status)), "open": n_open, "resolved": n_resolved}

    def find_attachment(feedback_id: int, kind: str) -> sqlite3.Row:
        rows = store.feedback_attachments([feedback_id]).get(feedback_id, []) if kind in FEEDBACK_ATTACHMENTS else []
        found = next((a for a in rows if a["kind"] == kind), None)
        if found is None or not store.feedback_file(feedback_id, kind).is_file():
            raise ApiError(404, "no such attachment")
        return found

    @app.get("/api/feedback/{feedback_id}/attachments/{kind}")
    def feedback_attachment(feedback_id: int, kind: str, request: Request) -> FileResponse:
        """Admins only. Always a download (never shown inline): it is whatever the sender uploaded."""
        admin_only(request, mutating=False)
        a = find_attachment(feedback_id, kind)
        gz = a["filename"].lower().endswith(".gz")
        media = "application/octet-stream" if kind == "schematic" or gz else "text/plain; charset=utf-8"
        return FileResponse(store.feedback_file(feedback_id, kind), media_type=media, filename=a["filename"],
                            headers={"X-Sha256": a["sha256"], "Cache-Control": "private, no-cache"})

    def delete_attachments(feedback_id: int, kind: str | None, request: Request) -> Response:
        admin = admin_only(request, mutating=True)
        with store.lock:
            if store.db.execute("SELECT 1 FROM feedback WHERE id=?", (feedback_id,)).fetchone() is None:
                raise ApiError(404, "no such feedback")
            if kind is not None:
                find_attachment(feedback_id, kind)
            n = store.delete_feedback_files(feedback_id, kind)
        if n:
            log.info("%s deleted %d attachment(s) of feedback %d", admin.name, n, feedback_id)
        return Response(status_code=204)

    @app.delete("/api/feedback/{feedback_id}/attachments")
    def feedback_attachments_delete(feedback_id: int, request: Request) -> Response:
        return delete_attachments(feedback_id, None, request)

    @app.delete("/api/feedback/{feedback_id}/attachments/{kind}")
    def feedback_attachment_delete(feedback_id: int, kind: str, request: Request) -> Response:
        return delete_attachments(feedback_id, kind, request)

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
        """A player in an admin answer: the Minecraft name, and the display name and tag that tell people apart."""
        if not uuid:
            return None
        u = store.get_user(uuid)
        return {"uuid": uuid, "name": name or "", "displayName": u.shown if u else (name or ""),
                "tag": u.display_tag if u else None}

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
            out["target"]["displayName"] = target_user.shown if target_user else None
            out["target"]["tag"] = target_user.display_tag if target_user else None
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
        u = User.from_row(r)
        return {
            "uuid": u.uuid, "name": u.name, "displayName": u.shown, "tag": u.display_tag,
            "discord": {"id": u.discord_id, "name": u.discord_name} if u.discord_id else None,
            "avatarVersion": u.avatar_version, "customAvatar": u.avatar is not None,
            "admin": u.is_admin, "usedBytes": int(r["used"]), "quotaBytes": quota_for(u),
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

    def storage_summary() -> dict[str, Any]:
        """What the admin page's Site storage tab shows: the data directory against the site's cap, by category,
        and who and what takes the most."""
        cats = store.measure_site()
        total = sum(v[0] for v in cats.values())
        cap = settings.site_quota_bytes
        try:
            disk = shutil.disk_usage(store.root)
            disk_free: int | None = disk.free
        except OSError:
            disk_free = None
        users = []
        for r in store.storage_top_users(STORAGE_TOP):
            quota = None if r["is_admin"] else (r["quota_bytes"] if r["quota_bytes"] is not None else settings.quota_bytes)
            users.append({
                "uuid": r["uuid"], "name": r["name"], "displayName": r["display_name"] or r["name"], "tag": r["display_tag"],
                "usedBytes": int(r["file_bytes"]) + int(r["post_bytes"]), "quotaBytes": quota,
                "fileBytes": int(r["file_bytes"]), "postBytes": int(r["post_bytes"]), "referenceBytes": int(r["ref_bytes"]),
            })
        posts = [{
            "id": p.id, "title": p.title, "visibility": p.visibility, "hidden": p.hidden is not None,
            "author": {"uuid": p.uuid, "name": p.author_name, "displayName": p.author_shown},
            "bytes": p.total_bytes, "versionCount": p.version_count, "saves": p.saves,
            "page": f"{settings.site_url}/board/{p.id}",
        } for p in store.storage_largest_posts(STORAGE_TOP)]
        files = [{
            "owner": {"uuid": r["uuid"], "name": r["name"], "displayName": r["display_name"] or r["name"], "tag": r["display_tag"]},
            "path": r["path"], "size": r["size"], "reference": bool(r["is_ref"]), "sourcePost": r["source_post"],
        } for r in store.storage_largest_files(STORAGE_TOP)]
        return {
            "usedBytes": total, "capBytes": cap, "freeBytes": max(0, cap - total), "diskFreeBytes": disk_free,
            "categories": [{"key": key, "label": label, "bytes": cats[key][0], "files": cats[key][1]}
                           for key, label in store.SITE_CATEGORIES],
            "topUsers": users, "largestPosts": posts, "largestFiles": files,
        }

    @app.get("/api/admin/storage")
    def admin_storage(request: Request) -> dict[str, Any]:
        admin_only(request, mutating=False)
        return storage_summary()

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
        favorites = store.list_favorites(user.uuid)
        return render(
            "files.html",
            user=user,
            nav="files",
            files_tab="files",
            entries=entries,
            post_count=post_count,
            post_bytes=post_bytes,
            ref_count=sum(1 for f in files if f.reference),
            favorites=favorites,
            fav_count=len(favorites),
            folders=folders,
            **post_form_ctx(user),
            used=used,
            quota=quota,
            pct=f"{pct:.2f}",
            over=quota is not None and used >= quota,
        )

    def board_url(
        tab: str = "public", sort: str | None = None, q: str = "", page: int = 1,
        tags: Any = (), size: str | None = None, complexity: str | None = None, mc: str | None = None,
        author: str | None = None,
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
        for key, value in (("size", size), ("complexity", complexity), ("mc", mc), ("author", author)):
            if value:
                params.append((key, value))
        if page > 1:
            params.append(("page", page))
        return f"{base}/board" + ("?" + urllib.parse.urlencode(params) if params else "")

    def post_form_ctx(user: User | None) -> dict[str, Any]:
        """What the post form (_post_form.html) needs, on the files page and a post's page."""
        newest = store.list_releases()
        return {
            "visibilities": VISIBILITIES, "max_title": MAX_TITLE_LEN, "max_description": MAX_DESCRIPTION_LEN,
            "all_tags": TAGS, "max_coauthors": MAX_COAUTHORS, "max_author_name": MAX_AUTHOR_NAME_LEN,
            "max_links": MAX_LINKS, "max_link": MAX_LINK_LEN, "max_version_label": MAX_VERSION_LABEL_LEN,
            "max_credit": MAX_CREDIT_LEN,
            "default_mc": newest[0].mc if newest else "",  # the newest release's Minecraft version
            # the Group choice: groups user may post in
            "post_groups": [g for g in store.groups_of(user.uuid) if standing(g, user).can_post] if user else [],
        }

    LISTING_COLUMNS = (
        ("title", "Title"), ("visibility", "Visibility"), ("score", "Score"), ("ups", "Up"), ("downs", "Down"),
        ("downloads", "Downloads"), ("favorites", "Favourites"), ("saves", "Saves"), ("versions", "Versions"),
        ("size", "Size"), ("created", "Posted"), ("updated", "Updated"),
    )

    @app.get("/listings", response_class=HTMLResponse)
    def listings_page(request: Request) -> Response:
        """My listings: the player's own posts with their numbers, sortable by each column."""
        user = session_user(request)
        if user is None:
            return RedirectResponse(f"{base}/", status_code=303)
        if not is_member(user):
            return RedirectResponse(f"{base}/apply", status_code=303)
        sort, desc = listing_order(request.query_params, strict=False)
        posts = sorted_listings(store.own_posts(user.uuid), sort, desc)

        def sort_link(key: str) -> str:
            """The column's header: sort by it (its natural order), or flip the order if it is the sort already."""
            order = ("asc" if desc else "desc") if key == sort else ("desc" if LISTING_SORTS[key] else "asc")
            return f"{base}/listings?" + urllib.parse.urlencode({"sort": key, "order": order})

        return render(
            "listings.html",
            user=user,
            nav="files",
            files_tab="listings",
            posts=posts,
            sort=sort,
            desc=desc,
            columns=LISTING_COLUMNS,
            sort_link=sort_link,
            post_count=len(posts),
            fav_count=len(store.list_favorites(user.uuid)),
            totals={key: sum(getattr(p, key) for p in posts) for key in ("downloads", "favorites", "saves", "total_bytes")},
        )

    def fav_context(user: User | None, posts: list[PostRow]) -> dict[str, Any]:
        """What post cards need for their heart: the viewer's favourites among posts, and who may favourite."""
        if user is None or not is_member(user):
            return {"favs": set(), "me": None}
        return {"favs": store.favorite_ids(user.uuid, [p.id for p in posts]), "me": user.uuid}

    @app.get("/board", response_class=HTMLResponse)
    def board_page(request: Request) -> HTMLResponse:
        user = session_user(request)
        tab, sort, query, page = board_query(request.query_params, strict=False)
        filters = board_filters(request.query_params, strict=False)
        tags, size_filter = filters["tags"], filters["size"]
        size = settings.board_page_size
        posts: list[PostRow] = []
        total = 0
        if tab == "public" or user is not None:
            posts, total = store.list_posts(tab, user.uuid if user else None, sort, query, page, size, **filters)
        author = store.get_user(filters["author"]) if filters["author"] else None

        def link(**change: Any) -> str:
            """This board page with some of tab, sort, q, page and the filters changed (page resets to 1)."""
            state = {"tab": tab, "sort": sort, "q": query, "page": 1, **filters}
            return board_url(**{**state, **change})

        def toggle_tag(tag: str) -> str:
            return link(tags=[t for t in tags if t != tag] if tag in tags else [*tags, tag])

        def chip_url(tag: str | None = None, size: str | None = None, complexity: str | None = None,
                     mc: str | None = None) -> str:
            """A card's chip: add its tag (or set its size, complexity or Minecraft version) to the current filters."""
            if tag is not None:
                return link(tags=tags if tag in tags else [*tags, tag])
            if complexity is not None:
                return link(complexity=complexity)
            if mc is not None:
                return link(mc=mc)
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
            complexity=filters["complexity"],
            mc=filters["mc"],
            author=author,
            filtered_by=any(filters[k] for k in ("tags", "size", "complexity", "mc", "author")),
            mc_versions=store.mc_versions(),
            link=link,
            toggle_tag=toggle_tag,
            chip_url=chip_url,
            all_tags=TAGS,
            size_classes=SIZE_LABELS,
            **fav_context(user, posts),
        )

    def og_tags(post: PostRow) -> dict[str, str]:
        """OpenGraph / Twitter card values for a post, so a pasted link previews (Discord, YouTube, ...)."""
        text = " ".join(post.description.split())
        if len(text) > 200:
            text = text[:197].rstrip() + "..."
        if not text:
            facts = [f"by {post.author_shown}"]
            if post.dims:
                facts.append(f"{post.dims[0]} x {post.dims[1]} x {post.dims[2]}")
            if post.block_count is not None:
                facts.append(f"{post.block_count:,} blocks")
            text = "A Minecraft schematic " + ", ".join(facts) + "."
        out = {"title": post.title, "description": text, "url": f"{settings.site_url}/board/{post.id}"}
        if post.has_thumb:
            out["image"] = f"{settings.site_url}/api/board/{post.id}/thumbnail?v={post.updated}"
        return out

    @app.get("/board/{post_id}", response_class=HTMLResponse)
    def post_page(post_id: str, request: Request) -> HTMLResponse:
        user = session_user(request)
        try:
            post = find_post(post_id, user)
            ver, _ = post_version(post, request)
        except ApiError:
            return render("not_found.html", status=404, user=user, nav="board")
        own = user is not None and user.uuid == post.uuid
        allowed = store.get_allowed(post.id) if user and (own or user.is_admin) else []
        ext = "." + ver.format if ver.format else ""
        versions = [VersionRow.of_post(post), *store.list_versions(post.id)]
        form = post_form_ctx(user if own else None)
        group = store.get_group(post.group_id) if post.group_id else None
        group_st = standing(group, user) if group is not None else None
        if own and group is not None and all(g.id != group.id for g in form["post_groups"]):
            form["post_groups"].append(group)  # where it is now stays a choice, even if they can't post there any more
        try:
            require_download(post, user)
            can_download = True
        except ApiError:
            can_download = False
        return render(
            "post.html",
            user=user,
            nav="board",
            post=post,
            ver=ver,
            versions=versions,
            version_q="" if ver.current else f"?version={ver.num}",
            own=own,
            my_vote=store.get_vote(post.id, user.uuid) if user else 0,
            allowed=allowed,
            source_exists=own and bool(post.source_path) and store.get_file(post.uuid, post.source_path) is not None,
            my_files=[f.path for f in store.list_files(user.uuid)] if own else [],
            exports=[e for e in SCHEMATIC_EXTENSIONS if e != ext] if ext else [],
            size_classes=SIZE_LABELS,
            board_url=board_url,
            has_model=ver.block_count is not None,
            max_visible=model3d.MAX_VISIBLE,
            report_reasons=REPORT_REASONS,
            max_report=MAX_REPORT_LEN,
            og=og_tags(post),
            share_url=f"{settings.site_url}/b/{post.id}",
            group=group if group_st is not None and group_st.can_see else None,
            group_manage=group_st is not None and group_st.manager and not own and is_member(user),  # type: ignore[arg-type]
            can_download=can_download,
            **fav_context(user, [post]),
            **form,
        )

    @app.get("/u/{uuid}", response_class=HTMLResponse)
    def user_page(uuid: str, request: Request) -> HTMLResponse:
        """A player's public profile: display name, badges, picture, and their public posts (own or co-authored)."""
        viewer = session_user(request)
        dashed = parse_uuid(uuid)
        person = store.get_user(dashed) if dashed else None
        if person is None:
            return not_found_page(request)
        raw_page = request.query_params.get("page") or "1"
        page = int(raw_page) if raw_page.isdigit() and 0 < int(raw_page) <= 100000 else 1
        size = settings.board_page_size
        posts, total = store.list_posts("public", None, "new", "", page, size, author=person.uuid)
        return render(
            "user.html",
            user=viewer,
            nav="board",
            person=person,
            badges=store.badges_of(person.uuid),
            posts=posts,
            total=total,
            page=page,
            pages=max(1, -(-total // size)),
            all_tags=TAGS,
            size_classes=SIZE_LABELS,
            board_url=board_url,
            **fav_context(viewer, posts),
        )

    def group_form_ctx() -> dict[str, Any]:
        """What the group settings form (_group_form.html) needs, on /groups and a group's settings page."""
        return {
            "group_visibilities": GROUP_VISIBILITIES, "join_policies": JOIN_POLICIES, "post_policies": POST_POLICIES,
            "post_rules": POST_VISIBILITY_RULES, "perm_labels": MEMBER_PERMS, "default_perms": DEFAULT_MEMBER_PERMS,
            "max_group_name": MAX_GROUP_NAME_LEN, "max_group_description": MAX_GROUP_DESCRIPTION_LEN,
            "max_slug": MAX_SLUG_LEN,
        }

    @app.get("/groups", response_class=HTMLResponse)
    def groups_page(request: Request) -> HTMLResponse:
        """The directory of public groups, the viewer's own groups, and the form for a new one."""
        user = session_user(request)
        return render(
            "groups.html",
            user=user,
            nav="groups",
            directory=store.public_groups(),
            mine=store.groups_of(user.uuid) if user else [],
            **group_form_ctx(),
        )

    def group_for_page(slug: str, request: Request) -> tuple[GroupRow, Standing, User | None] | None:
        """The group at /g/<slug> and the viewer's standing there, or None if it isn't there for them."""
        user = session_user(request)
        group = store.group_by_slug(slug) if SLUG_RE.match(slug) else None
        st = standing(group, user) if group is not None else None
        if group is None or st is None or not st.can_see:
            return None
        return group, st, user

    @app.get("/g/{slug}", response_class=HTMLResponse)
    def group_page(slug: str, request: Request) -> HTMLResponse:
        found = group_for_page(slug, request)
        if found is None:
            return not_found_page(request)
        group, st, user = found
        _, sort, query, page = board_query(request.query_params, strict=False)
        folder = clean_folder_query(request.query_params.get("folder"), strict=False)
        size = settings.board_page_size
        viewer = user.uuid if user else None
        posts, total = store.list_posts("group", viewer, sort, query, page, size, group=st, folder=folder)
        folders = [] if query.strip() or page > 1 else store.group_folders(st, viewer, folder)

        def link(**change: Any) -> str:
            """This group page with sort, q, page or folder changed (page resets to 1)."""
            state = {"sort": sort, "q": query, "page": 1, "folder": folder, **change}
            params = [(k, v) for k, v in (("folder", state["folder"]), ("sort", state["sort"] if state["sort"] != "top" else ""),
                                          ("q", state["q"]), ("page", state["page"] if state["page"] > 1 else "")) if v]
            return f"{base}/g/{group.slug}" + ("?" + urllib.parse.urlencode(params) if params else "")

        parts = folder.split("/") if folder else []
        crumbs = [{"name": name, "folder": "/".join(parts[: i + 1])} for i, name in enumerate(parts)]

        return render(
            "group.html",
            user=user,
            nav="groups",
            group=group,
            st=st,
            posts=posts,
            total=total,
            folder=folder,
            folders=folders,
            crumbs=crumbs,
            sort=sort,
            q=query,
            page=page,
            pages=max(1, -(-total // size)),
            sorts=BOARD_SORTS,
            link=link,
            all_tags=TAGS,
            size_classes=SIZE_LABELS,
            max_join_message=MAX_JOIN_MESSAGE_LEN,
            **group_form_ctx(),
            **fav_context(user, posts),
        )

    @app.get("/g/{slug}/members", response_class=HTMLResponse)
    def group_members_page(slug: str, request: Request) -> HTMLResponse:
        found = group_for_page(slug, request)
        if found is None:
            return not_found_page(request)
        group, st, user = found
        return render(
            "group_members.html",
            user=user,
            nav="groups",
            group=group,
            st=st,
            roles=GROUP_ROLES,
            perm_labels=MEMBER_PERMS,
            **members_api(group, st),
        )

    @app.get("/g/{slug}/settings", response_class=HTMLResponse)
    def group_settings_page(slug: str, request: Request) -> HTMLResponse:
        found = group_for_page(slug, request)
        if found is None or not found[1].admin:
            return not_found_page(request)  # like the admin pages: there is nothing here for anyone else
        group, st, user = found
        return render("group_settings.html", user=user, nav="groups", group=group, st=st, **group_form_ctx())

    DISCORD_MESSAGES = {
        "linked": ("Your Discord account is linked.", False),
        "cancelled": ("Linking was cancelled on Discord.", True),
        "state": ("That link request expired or came from another browser. Try again.", True),
        "failed": ("Discord didn't confirm the account. Try again later.", True),
        "taken": ("That Discord account is already linked to another Slopmatica account.", True),
        "off": ("Discord linking isn't set up yet.", True),
    }

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
            ref_count=sum(1 for f in store.list_files(user.uuid) if f.reference),
            post_count=post_count,
            post_bytes=post_bytes,
            has_password=stored is not None,
            password_set=password_set * 1000 if password_set else None,
            min_password=MIN_PASSWORD_LEN,
            max_password=MAX_PASSWORD_LEN,
            min_display=MIN_DISPLAY_NAME_LEN,
            max_display=MAX_DISPLAY_NAME_LEN,
            max_avatar_kb=MAX_AVATAR_BYTES // 1024,
            avatar_out=AVATAR_OUT_SIDE,
            discord_enabled=settings.discord_enabled,
            discord_msg=DISCORD_MESSAGES.get(request.query_params.get("discord") or ""),
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
            max_log_mb=MAX_FEEDBACK_LOG // (1024 * 1024),
            max_schematic_mb=MAX_FEEDBACK_SCHEMATIC // (1024 * 1024),
            attachment_days=FEEDBACK_ATTACHMENT_DAYS,
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
            items=feedback_items(store.list_feedback(status)),
            show=status,
            counts={"open": n_open, "resolved": n_resolved, "all": n_open + n_resolved},
            kinds=FEEDBACK_KINDS,
            attachment_labels=FEEDBACK_ATTACHMENTS,
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

    ADMIN_TABS = ("applications", "storage", "reports", "whitelist", "badges", "users", "telemetry", "disk")

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
            ctx.update(holders=[{**who(r["uuid"], r["name"]), "badges": badge_list(r["badges"])}  # type: ignore[dict-item]
                                for r in store.badge_holders()])
        elif tab == "telemetry":
            ctx.update(t=telemetry_summary())
        elif tab == "disk":
            st = storage_summary()
            pct = 0.0 if not st["capBytes"] else min(100.0, st["usedBytes"] * 100.0 / st["capBytes"])
            ctx.update(st=st, site_pct=f"{pct:.2f}", site_over=st["usedBytes"] >= st["capBytes"])
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
