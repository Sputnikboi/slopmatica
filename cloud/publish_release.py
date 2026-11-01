#!/usr/bin/env python3
"""Publish a mod jar as a Slopmatica release, straight into this machine's cloud data directory (no HTTP).

    publish_release.py build/libs/slopmatica-0.6.0.jar --mc 26.3 --loader fabric --changelog-file notes.md

The version is read from the jar's fabric.mod.json unless --version is given; the file name is the jar's
unless --filename is given. The data directory is --data, else SLOPMATICA_DATA from the environment, else
from ~/.config/slopmatica-cloud/env (the service's), else cloud/data. The server may keep running: SQLite
serialises the two. The database must already be at schema 4 (start the 0.6.0 server once).
Exits 0 when published, 1 on any error (a version or file name that is already taken included).
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import zipfile
from pathlib import Path

CLOUD_DIR = Path(__file__).resolve().parent
VENV = CLOUD_DIR / ".venv"
SERVICE_ENV = Path.home() / ".config" / "slopmatica-cloud" / "env"

sys.path.insert(0, str(CLOUD_DIR))
try:
    from slopmatica_cloud.app import (
        DEFAULT_LOADER, RELEASES_DIR, SCHEMA_VERSION, ApiError, Settings, add_release, human_size,
    )
except ModuleNotFoundError:
    # run with a python that lacks FastAPI: use the project's virtualenv instead
    if (VENV / "bin" / "python").is_file() and Path(sys.prefix) != VENV:
        os.execv(VENV / "bin" / "python", [str(VENV / "bin" / "python"), __file__, *sys.argv[1:]])
    raise


def read_env_file(path: Path) -> dict[str, str]:
    """KEY=VALUE lines as systemd's EnvironmentFile reads them (comments, optional quotes)."""
    out: dict[str, str] = {}
    try:
        lines = path.read_text().splitlines()
    except OSError:
        return out
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        out[key.strip()] = value
    return out


def find_data_dir(arg: str | None, env: dict[str, str], env_file: Path) -> Path:
    if arg:
        return Path(arg).expanduser()
    if env.get("SLOPMATICA_DATA"):
        return Path(env["SLOPMATICA_DATA"]).expanduser()
    from_file = read_env_file(env_file).get("SLOPMATICA_DATA")
    if from_file:
        return Path(from_file).expanduser()
    return Settings().data_dir


def jar_version(jar: Path) -> str | None:
    """The version in the jar's fabric.mod.json, or None."""
    try:
        with zipfile.ZipFile(jar) as z:
            meta = json.loads(z.read("fabric.mod.json"))
    except (OSError, KeyError, ValueError, zipfile.BadZipFile):
        return None
    version = meta.get("version") if isinstance(meta, dict) else None
    return version if isinstance(version, str) and "$" not in version else None


def main(argv: list[str] | None = None, env: dict[str, str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Publish a mod jar as a Slopmatica release on this machine.")
    p.add_argument("jar", type=Path)
    p.add_argument("--mc", required=True, help="Minecraft version the jar is for, e.g. 26.3")
    p.add_argument("--loader", default=DEFAULT_LOADER)
    p.add_argument("--changelog-file", type=Path, help="markdown-ish release notes ('-' for stdin)")
    p.add_argument("--version", help="defaults to the version in the jar's fabric.mod.json")
    p.add_argument("--filename", help="the release's file name, defaults to the jar's")
    p.add_argument("--hidden", action="store_true", help="publish without listing it or offering it as an update")
    p.add_argument("--data", help="data directory (see above)")
    p.add_argument("--env-file", type=Path, default=SERVICE_ENV, help=argparse.SUPPRESS)
    args = p.parse_args(argv)
    env = dict(os.environ if env is None else env)

    def fail(message: str) -> int:
        print(f"publish_release: {message}", file=sys.stderr)
        return 1

    if not args.jar.is_file():
        return fail(f"{args.jar}: no such file")
    version = args.version or jar_version(args.jar)
    if not version:
        return fail("could not read the version from the jar's fabric.mod.json; pass --version")
    changelog = ""
    if args.changelog_file is not None:
        try:
            changelog = sys.stdin.read() if str(args.changelog_file) == "-" else args.changelog_file.read_text()
        except (OSError, UnicodeDecodeError) as e:
            return fail(f"cannot read the changelog: {e}")

    data = find_data_dir(args.data, env, args.env_file)
    db_path = data / "cloud.db"
    if not db_path.is_file():
        return fail(f"no database at {db_path}")
    db = sqlite3.connect(db_path, isolation_level=None, timeout=30)
    try:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        (schema,) = db.execute("PRAGMA user_version").fetchone()
        if schema < 4:
            return fail(f"the database at {db_path} is at schema {schema}; start the 0.6.0 server once to migrate it")
        if schema > SCHEMA_VERSION:
            return fail(f"the database at {db_path} is at schema {schema}, newer than this script knows ({SCHEMA_VERSION})")
        releases = data / RELEASES_DIR
        releases.mkdir(exist_ok=True)
        try:
            row = add_release(db, releases, args.jar, version, args.mc, args.loader,
                              args.filename or args.jar.name, changelog, args.hidden)
        except ApiError as e:
            return fail(e.payload["error"])
    finally:
        db.close()
    print(f"published {row.version} for Minecraft {row.mc} ({row.loader}){' (hidden)' if row.hidden else ''}: "
          f"{releases / row.filename}, {human_size(row.size)}, sha256 {row.sha256}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
