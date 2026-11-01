# Slopmatica Cloud

A small self-hosted service that stores schematics (`.slopmatic`, vanilla `.nbt`, `.litematic`) per Minecraft account,
for the Slopmatica mod's cloud autosync, plus a website for browsing, uploading,
renaming, deleting and converting them, and a schematics board for sharing them.

There are no accounts to create. The game proves who the player is the same way a Minecraft
server does it (Mojang `join` / `hasJoined`), and the game also hands out one-time
links for logging in to the website. Once logged in, a player can set a website password
and from then on also log in on the website with their Minecraft name and that password.

FastAPI + uvicorn, SQLite (metadata only), files on disk, server-rendered HTML plus a
little vanilla JS. No build step, no CDN. The board's 3D view uses three.js, vendored unmodified
from the npm package `three@0.186.1` in `slopmatica_cloud/static/vendor/three/` (`build/three.module.js`,
`build/three.core.js`, `examples/jsm/controls/OrbitControls.js` with its `'three'` import changed to
`'./three.module.js'`, and the MIT `LICENSE`). That release ships no minified build. Static `.js`, `.css`,
`.json` and `.svg` files are sent gzip-compressed to clients that accept it, so three.js costs about 420 KB.

## Run

```bash
cd ~/Git/stencil/cloud
python3.12 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn slopmatica_cloud.main:app --host 127.0.0.1 --port 8095
.venv/bin/pytest            # tests (Mojang is faked locally)
```

`slopmatica-cloud.service` is a systemd **user** unit for the same command. It is not
installed. To install it:
`ln -s ~/Git/stencil/cloud/slopmatica-cloud.service ~/.config/systemd/user/ && systemctl --user daemon-reload && systemctl --user enable --now slopmatica-cloud`.
It reads optional overrides from `~/.config/slopmatica-cloud/env`.

## Configuration (env vars)

| Variable | Default | Meaning |
|---|---|---|
| `SLOPMATICA_DATA` | `cloud/data` | Data directory: `cloud.db` plus `users/<uuid>/files/...`, `users/<uuid>/thumbs/<path>.png` and board snapshots in `posts/<id>/` (`<original stem>.slopmatic`, `thumb.png`, and the 3D view's cache `model-<sha256[:16]>.json.gz` or `.toobig`) |
| `SLOPMATICA_ROOT_PATH` | *(empty)* | URL prefix when mounted under a path, e.g. `/slopmatica`. Works whether or not the proxy strips the prefix. |
| `SLOPMATICA_ADMINS` | `sputnikboi` | Comma-separated Minecraft names with unlimited storage. Names are matched case-insensitively at login, and the account's uuid then stays an admin even after a rename. |
| `SLOPMATICA_QUOTA_BYTES` | `1073741824` | Per-account quota (1 GiB) |
| `SLOPMATICA_PUBLIC_URL` | `http://127.0.0.1:8095` | External origin used to build web-login links (`<PUBLIC_URL><ROOT_PATH>/login/<token>`) and the absolute release URLs (`<PUBLIC_URL><ROOT_PATH>/...`, with ROOT_PATH left out if PUBLIC_URL already ends in it). If it is `https://`, session cookies are marked Secure. |
| `SLOPMATICA_MOJANG_HASJOINED` | `https://sessionserver.mojang.com/session/minecraft/hasJoined` | Override for tests |
| `SLOPMATICA_MOJANG_PROFILE` | `https://api.mojang.com/users/profiles/minecraft` | Name to uuid lookups (`<url>/<name>`) for private board posts. Override for tests |

## API

All errors look like `{"error": "..."}`. All paths below are relative to `ROOT_PATH`.

### Auth

1. `POST /api/auth/challenge` returns `{"serverId": "<40 hex>"}`. It is valid for 2 minutes and can be used once.
2. The mod calls Mojang's `join` with that serverId.
3. `POST /api/auth/verify` with `{"username", "serverId"}` returns
   `{"token", "uuid", "name", "usedBytes", "quotaBytes"}` (`quotaBytes: null` means unlimited).
   The token lasts 180 days. On failure you get 401. A malformed body or username gets 400.
4. Every other API call sends `Authorization: Bearer <token>`. A missing, invalid or expired token gets 401.

`POST /api/weblogin` (bearer only) returns `{"url": ".../login/<token>"}`. The link is
single use and valid for 5 minutes. Opening it sets an HttpOnly, SameSite=Lax session cookie
that lasts 30 days, then redirects to the site root. An optional JSON body `{"next": "/account"}`
makes the link land on that page instead: `next` must be a site-relative path (relative to
`ROOT_PATH`) starting with a single `/`, with no scheme, host, backslash, whitespace or control
characters, at most 500 characters; anything else is 400. `POST /logout` ends the session.

The website uses the same API with the cookie. Requests that change something (PUT, POST,
PATCH or DELETE) and authenticate by cookie must send `X-Requested-With: slopmatica`; without it
they get 403. Bearer requests don't need the header.

### Website passwords

| Method & path | Result |
|---|---|
| `POST /api/login` | `{"name","password"}`, with `X-Requested-With: slopmatica` (403 without). Matches the name case-insensitively against accounts' current stored names. Sets the same session cookie as a login link and returns `{"uuid","name"}`. A wrong name, wrong password or account without a password all give the same 401 `{"error":"wrong name or password"}`. After 10 failures for a name or 30 from an address within 15 minutes: 429. A success clears the name's count. |
| `POST /api/account/password` | `{"new", "current"?}` sets or changes the password (8 to 200 characters). `current` is required, and checked, once a password is set: 403 if wrong (and it counts as a failed login for the name). Ends the account's other website sessions. Returns `{"hasPassword": true, "endedSessions"}`. |
| `DELETE /api/account/password` | `{"current"}` removes the password (409 if there is none). Ends the other sessions too. |
| `POST /api/account/logout-all` | Ends every website session of the account, including the caller's. API tokens (the game) are unaffected. Cookie or bearer. Returns `{"endedSessions"}`. |

The two password endpoints accept only the website session cookie (401 otherwise), never a
bearer token, so a leaked game token cannot set a website password. Passwords are stored as
`scrypt$n$r$p$salt_hex$hash_hex` (scrypt n=2^14, r=8, p=1, 64 bytes, random 16-byte salt).
Failed attempts are counted in SQLite (`login_failures`). The client address is the TCP peer;
only when that is `127.0.0.1`/`::1` (the local reverse proxy) is the right-most
`X-Forwarded-For` entry used instead. The website's account page is `/account`.

### Files

Paths are `/`-separated and relative to the account's space. They must end in `.slopmatic`,
`.nbt` or `.litematic` and may use at most 8 segments, 100 characters per segment and 300 characters in total.
These are rejected with 400: empty segments, segments starting with `.`, backslashes,
control characters, `<>:"|?*`, segments with leading or trailing whitespace or a trailing
`.`, and folder names ending in one of those extensions.

| Method & path | Result |
|---|---|
| `GET /api/me` | `{"uuid","name","usedBytes","quotaBytes"}` |
| `GET /api/files` | `{"files":[{"path","size","sha256","modified"(unix ms),"hasThumbnail","source"}],"usedBytes","quotaBytes"}`. `source` is `null`, or for a file saved with "Add to my cloud" `{"postId","title","author","authorUuid"}`: the post, its title when saved, and its author (`author` is the author's current name as last seen at a login, else the name when saved). |
| `PUT /api/files/{path}` | Raw body. It must be gzip (`1f 8b`) and at most 256 MiB. Optional `X-Sha256` must match. Returns 201 (new) or 200 (replaced) with the file entry. Over quota gives 413 `{"error":"quota exceeded","usedBytes","quotaBytes"}`; the quota counts the new size minus the size of the file being replaced. Replacing a file with different bytes drops its (now stale) thumbnail and its `source` (it is the user's own file from then on); the same bytes keep both. |
| `GET /api/files/{path}` | Bytes, `application/octet-stream`, `Content-Disposition: attachment`, `X-Sha256` |
| `GET /api/export/{path}?format=slopmatic\|nbt\|litematic` | The file converted to another format: bytes, `application/octet-stream`, `Content-Disposition: attachment` named like the original with the new extension (no folder). 400 for a missing, unknown or same format, for a file that cannot be parsed, or for more than 64 million blocks of volume. 404 if the file is missing. A read, so cookie requests need no `X-Requested-With`. |
| `DELETE /api/files/{path}` | 204. The thumbnail is deleted too. |
| `POST /api/files/move` | `{"from","to"}` returns 200 with the new entry. The thumbnail and `source` move along. 404 if the source is missing, 409 if the target exists. |
| `PUT /api/thumbnails/{path}` | Raw PNG, at most 2 MiB, for an existing file (404 otherwise). Returns 200 with the file entry. Thumbnails don't count toward the quota. |
| `GET /api/thumbnails/{path}` | `image/png` or 404 |

### Schematics board

Players post one of their cloud files to the board with a title (1 to 100 characters), an optional
plain-text description (at most 5000 characters), any number of tags and a visibility:

- `public`: listed and searchable on the board; anyone can view and download it, logged out too.
- `unlisted`: never listed or searchable (except in the author's own list); anyone with the link can view and download it.
- `private`: only the author, the Minecraft names on its access list (at most 50, stored by uuid, so renames keep access) and admins. To anyone else every endpoint for the post, the page included, answers 404 as if it didn't exist.

A post keeps its own snapshot of the file and its thumbnail, so later edits to or deletion of
the cloud file don't affect it; the author can re-take the snapshot from the same path. Snapshots
are stored as `.slopmatic`: a `.nbt` or `.litematic` is converted when it is posted or refreshed
(block states and data version kept, as for exports) and named `<original stem>.slopmatic`; the
user's own file is untouched. A file that can't be read or converted is refused with 400 (nothing is
stored). Snapshot bytes (the converted size) count toward the author's quota (`usedBytes` includes
them); deleting a post frees them. At most 200 posts per account (409 past that). Enclosing size and
block count (non-air, non-void blocks) are read from the snapshot when it is taken.

Tags come from a fixed list (any other is 400): `redstone`, `farm`, `decoration`, `building`,
`survival-friendly`, `creative-only`, `map-art`, `transport`, `technical`, `terrain`, `statue`,
`interior`. The size category follows the block count: `small` under 1 000, `medium` under 10 000,
`big` under 100 000, `very-big` from 100 000 (stored per post, null for an old post that can't be read).

Post ids are 10 URL-safe characters. Names on an access list are looked up with Mojang's
profile API (10 s timeout): an unknown name is 400 naming it, Mojang unreachable is 502.

| Method & path | Result |
|---|---|
| `GET /api/board?tab=public\|shared\|mine&sort=top\|hot\|new&q=&tag=&size=&page=` | `{"posts":[post],"tab","sort","q","tags","size","page","pageSize"(24),"total","pages"}`. `public` works logged out; `shared` (private posts listing the caller) and `mine` need a login (`mine` adds `postCount`, `postBytes`). `q` matches every word against title, description and author name, case-insensitively. `tag` may repeat: the post must have every one (unknown tag: 400). `size` is one size category (else 400). Top: score, then newest. Hot: score / (hours + 2)^1.5. Default sort: `top` for public, `new` otherwise. |
| `POST /api/board` | `{"path","title","description"?,"visibility","allowed"?:[names],"tags"?:[tags]}` returns 201 with the post. 404 if the file is missing, 400 if it can't be converted, 413 over quota. |
| `GET /api/board/{id}` | The post, plus `description`, `myVote` (null logged out), and for the author and admins `sourcePath` and `allowed: [{uuid,name}]`. |
| `PATCH /api/board/{id}` | Author only: any of `title`, `description`, `visibility`, `allowed`, `tags` (replaces the list). |
| `POST /api/board/{id}/refresh` | Author only: snapshot the original path again (409 if it is gone, 400 if it can't be converted). Drops the 3D view's cache. |
| `DELETE /api/board/{id}` | Author or admin: 204. |
| `POST /api/board/{id}/vote` | `{"value": 1\|-1\|0}` (0 clears). Not on your own post (403). Returns `{"score","ups","downs","myVote"}`. |
| `POST /api/board/{id}/save` | Copies the snapshot (and thumbnail) into the caller's files as `board/<title>.<ext>`, adding ` (2)` etc. if taken, and records the post as the file's `source`. 201 with the file entry, 413 over quota. |
| `GET /api/board/{id}/file`, `/thumbnail`, `/export?format=` | Download, PNG thumbnail, conversion (same rules and limits as `/api/export`). |
| `GET /api/board/{id}/model` | The 3D view's data (below), gzip-encoded JSON for clients that accept it. 413 `{"error","visibleBlocks","maxVisibleBlocks"}` above 300 000 visible blocks, 400 for an old post that can't be read. Cached in the post's folder per snapshot. |

A post is `{"id","title","visibility","author":{"uuid","name"},"filename","format","size","sha256","dimensions":[x,y,z]|null,"blockCount"|null,"sizeClass"|null,"tags":[...],"hasThumbnail","created","updated","score","ups","downs"}` (times in unix ms).

The model is `{"v":1,"size":[sx,sy,sz],"count","palette":[{"id","shape"}],"groups":[{"p","n","d"}]}`. It lists
only visible blocks: no air or structure voids, and no block whose six neighbours inside the schematic are all
opaque full blocks (a neighbour with no map colour in `static/blockcolors.json`, such as glass, or a slab or
carpet, doesn't hide it). `id` is the block id without properties; `shape` is `full`, `bottom`/`top` (slabs) or
`thin` (carpets, pressure plates). Each group is one palette entry `p` with `n` blocks; `d` is base64 of
little-endian uint32 values: the block indices `(y * sz + z) * sx + x` in ascending order, each as the
difference from the previous one (the first from 0). Several Litematica regions are merged over their
enclosing box as for `.nbt` exports.
Logged out is fine for public and unlisted posts, except voting and saving (401). Website pages: `/board`, `/board/<id>`.

### Releases

Mod jars live in `<data>/releases/<filename>`, their metadata in the `releases` table. A version is semver-ish
(`1.2.3` or `1.2.3-beta.1`), `mc` a Minecraft version (`26.3`), `loader` a lowercase name (default `fabric`), and a
file name one plain `.jar` name (letters, digits, `._+-`, not starting with `.` or `-`). Each (version, mc, loader)
and each file name can be published once (409 otherwise). "Newest" is semver precedence, never upload time.
A release is `{"version","mc","loader","filename","sha256","size","uploaded"(unix ms),"changelog","hidden","downloads","download","page"}`.

| Method & path | Result |
|---|---|
| `GET /api/releases/latest?mc=26.3&loader=fabric` | The mod's update check: `{"version","download","page","sha256","mc","loader"}` for the highest non-hidden version for that mc and loader (`loader` defaults to `fabric`). `download` is the jar's absolute URL, `page` the releases page with that build highlighted (`.../releases?highlight=<v>&mc=<mc>#v<v>`). 404 `{"error"}` if there is none, 400 for a malformed mc or loader. |
| `GET /api/releases` | `{"releases":[release]}`, newest first. Hidden ones only for admins. |
| `POST /api/releases` | Admins only (403 otherwise; cookie requests need `X-Requested-With`). `multipart/form-data` with `jar` (the file; it must start like a zip), `version`, `mc`, `loader`?, `changelog`?, `filename`? (else the upload's own name, without folders), `hidden`? (`true`/`1`/`on`). 201 with the release; at most 64 MiB (413). |
| `PATCH /api/releases/{filename}` | Admins only: `{"hidden"?: bool, "changelog"?: text}`. |
| `GET /releases/{filename}` | The jar, `Content-Disposition: attachment`, `X-Sha256`; counts a download. 404 for hidden releases (except for admins). |

The page `/releases` shows the newest version with a download per Minecraft version, then every version grouped by
Minecraft version with its changelog (markdown-ish: `#` headings, `-`/`1.` lists, `**bold**`, `` `code` ``,
`[text](https://...)`; everything else is escaped). `?highlight=<version>` (plus `&mc=`) highlights and scrolls to
that build, which also carries the id `v<version>`.

`publish_release.py <jar> --mc 26.3 [--loader fabric] [--changelog-file notes.md|-] [--version V] [--filename F] [--hidden] [--data DIR]`
publishes on this machine without HTTP, for deploy scripts. The version defaults to the one in the jar's
`fabric.mod.json`; the data directory to `--data`, `SLOPMATICA_DATA`, then `SLOPMATICA_DATA` in
`~/.config/slopmatica-cloud/env`. It runs itself under `.venv` if started with a Python without FastAPI, needs the
database at schema 4 (start the 0.6.0 server once), and may run while the server does. Exit status 0 or 1.

### Feedback

`/feedback` (the mod's Credits button; `?mod=&mc=&type=` prefill the form) takes bug reports, suggestions and other
messages, logged in or not. `POST /api/feedback` `{"type":"bug"|"suggestion"|"other","message","modVersion"?,"mcVersion"?,"contact"?}`
needs `X-Requested-With: slopmatica` unless it has a bearer token, and records the logged-in account. Message at most
5000 characters, versions 40, contact 200. Limits per 15 minutes: 5 per address logged out, 20 per account
(429 past that; counted in `login_failures` under `feedback-ip:`/`feedback-user:` keys). Admins only:
`GET /api/feedback?status=open|resolved|all` returns `{"feedback":[...],"open","resolved"}`,
`POST /api/feedback/{id}/resolve` `{"resolved": bool}`, and the page `/admin/feedback` (404 to anyone else).
A feedback item is `{"id","type","message","modVersion","mcVersion","contact","user":{"uuid","name"}|null,"created","resolved"(unix ms|null)}`.

### Database

`PRAGMA user_version` records the schema. At startup, `cloud.db` is brought up to date in place,
one transaction per step: 0 is the 0.3.0 schema; 1 adds `posts`, `post_access` and `votes`;
2 adds `users.password_hash`, `users.password_set`, `tokens.next` and `login_failures`;
3 (0.5.0) adds `posts.size_class` (filled from the stored block counts), `post_tags`, and `files.source_post`,
`source_title`, `source_author_uuid`, `source_author_name`. 4 (0.6.0) adds `releases` and `feedback`. Before step 3, board snapshots that aren't
`.slopmatic` are converted in place, each committed on its own (size, hash, format, file name, so quota
usage follows); one that fails to convert is left as it is and logged. An interrupted run converts the
rest at the next start.
Board snapshot folders without a post are removed at startup, and posts whose snapshot is missing are dropped.
Other files in a post's folder (left over from an interrupted refresh or conversion, or a 3D view cache of an
older snapshot) are removed too.

### Export (format conversion)

`slopmatica_cloud/convert.py` converts between the three formats through one in-memory model
(regions with a palette and an index per block, block entities, entities), using the small
NBT reader/writer in `slopmatica_cloud/nbt.py`. Block state compounds and the data version
are copied unchanged, so Minecraft's data fixers see the output as they would have seen
the input. Entity and block entity NBT is passed through apart from its coordinates.

- Several Litematica regions become one `.nbt` box over their enclosing bounds. Where regions
  overlap, a real block beats air and structure void, and air beats structure void.
  Otherwise the later region wins, except that a structure void never overwrites anything.
- In `.nbt`, positions missing from `blocks` are structure voids. They become a
  `minecraft:structure_void` palette entry in the other formats, and are left out again on
  the way back. Air is listed.
- `.nbt` has no name, so the file name is used.
- Two conversions run at once (`Settings.export_slots`). Other requests wait up to 30 s, then
  get 503. The conversion is pure Python. At the 64-million-block cap, a `.litematic`
  takes about 7 s to read. Worst case, writing a fully listed `.nbt` takes about 40 s.
  A `.nbt` input is also limited to 8 million NBT tags, about 1.3 million listed blocks,
  because each tag is a Python object.

Disk is the source of truth for bytes. At startup the SQLite metadata is reconciled with
what is under `users/<uuid>/files/`.
