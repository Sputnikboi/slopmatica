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
| `SLOPMATICA_DATA` | `cloud/data` | Data directory: `cloud.db` plus `users/<uuid>/files/...`, `users/<uuid>/thumbs/<path>.png`, a custom picture `users/<uuid>/avatar.png` or `.jpeg`, cached skins `skins/<uuid>.png` and faces `skins/<uuid>.face.png`, and board snapshots in `posts/<id>/` (`<original stem>.slopmatic`, `thumb.png`, and the 3D view's cache `model-<sha256[:16]>.json.gz` or `.toobig`; earlier versions the same way in `posts/<id>/v<n>/`) |
| `SLOPMATICA_ROOT_PATH` | *(empty)* | URL prefix when mounted under a path, e.g. `/slopmatica`. Works whether or not the proxy strips the prefix. |
| `SLOPMATICA_ADMINS` | `sputnikboi` | Comma-separated Minecraft names with unlimited storage. Names are matched case-insensitively at login, and the account's uuid then stays an admin even after a rename. |
| `SLOPMATICA_QUOTA_BYTES` | `1073741824` | Default per-account quota (1 GiB). An admin can set another one per account (storage requests, or the admin page's user list); admins are always unlimited. |
| `SLOPMATICA_WHITELIST_ENFORCED` | `true` | Only players on the Fruitbowl whitelist (and admins) may use the cloud. `0`, `false`, `no` or `off` switch the gate off. |
| `SLOPMATICA_WHITELIST_SEED` | *(unset)* | A Minecraft `whitelist.json` (`[{"uuid","name"}, ...]`) read **once**, when the database migrates to schema 5, to fill the whitelist (source `seed`). Unset: the whitelist starts empty. Set but missing or malformed: the server refuses to start and nothing is migrated. Later changes go through the admin page. |
| `SLOPMATICA_PUBLIC_URL` | `http://127.0.0.1:8095` | External origin used to build web-login links (`<PUBLIC_URL><ROOT_PATH>/login/<token>`) and the absolute release URLs (`<PUBLIC_URL><ROOT_PATH>/...`, with ROOT_PATH left out if PUBLIC_URL already ends in it). If it is `https://`, session cookies are marked Secure. |
| `SLOPMATICA_MOJANG_HASJOINED` | `https://sessionserver.mojang.com/session/minecraft/hasJoined` | Override for tests |
| `SLOPMATICA_MOJANG_PROFILE` | `https://api.mojang.com/users/profiles/minecraft` | Name to uuid lookups (`<url>/<name>`) for private board posts. Override for tests |
| `SLOPMATICA_MOJANG_SESSION_PROFILE` | `https://sessionserver.mojang.com/session/minecraft/profile` | Skins for avatars (`<url>/<uuid without dashes>`). The skin itself must be on `textures.minecraft.net`. Override for tests |
| `SLOPMATICA_DISCORD_CLIENT_ID` | *(unset)* | Discord linking (OAuth2, scope `identify`). Off unless all three Discord variables are set; the account page then says it isn't set up yet. |
| `SLOPMATICA_DISCORD_CLIENT_SECRET` | *(unset)* | The Discord application's client secret. Keep it in the `0600` env file. |
| `SLOPMATICA_DISCORD_REDIRECT` | *(unset)* | The redirect URL registered with the Discord application, exactly: `<PUBLIC_URL><ROOT_PATH>/account/discord/callback`. |

## API

All errors look like `{"error": "..."}`. All paths below are relative to `ROOT_PATH`.

### Auth

1. `POST /api/auth/challenge` returns `{"serverId": "<40 hex>"}`. It is valid for 2 minutes and can be used once.
2. The mod calls Mojang's `join` with that serverId.
3. `POST /api/auth/verify` with `{"username", "serverId"}` returns
   `{"token", "uuid", "name", "usedBytes", "quotaBytes", "whitelisted", "badges", "displayName", "tag", "avatar", "apply"?}`
   (`quotaBytes: null` means unlimited; `apply`, the absolute URL of the apply page, only when `whitelisted` is false;
   `name` is the Minecraft name, `displayName` and `tag` the name shown on the site, `avatar` the absolute URL of the picture).
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
| `GET /api/me` | `{"uuid","name","usedBytes","quotaBytes","whitelisted","badges","displayName","tag","avatar","apply"?}` as for `/api/auth/verify`. Open to players without access, so the mod can tell them. |
| `GET /api/files` | `{"files":[{"path","size","sha256","modified"(unix ms),"hasThumbnail","source"}],"usedBytes","quotaBytes"}`. `source` is `null`, or for a file saved with "Add to my cloud" `{"postId","title","author","authorUuid"}`: the post, its title when saved, and its author (`author` is the author's current name as last seen at a login, else the name when saved). |
| `PUT /api/files/{path}` | Raw body. It must be gzip (`1f 8b`) and at most 256 MiB. Optional `X-Sha256` must match. A schematic whose blocks, block entities or entities use a namespace other than `minecraft` is refused with 400 `{"error","code":"modded","namespaces":[...]}` (a file the server can't parse is stored as before). Returns 201 (new) or 200 (replaced) with the file entry. Over quota gives 413 `{"error":"quota exceeded","usedBytes","quotaBytes"}`; the quota counts the new size minus the size of the file being replaced. Replacing a file with different bytes drops its (now stale) thumbnail and its `source` (it is the user's own file from then on); the same bytes keep both. |
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
stored), and so is a modded one (400 code `modded`, as for uploads). Snapshot bytes (the converted size) count toward the author's quota (`usedBytes` includes
them); deleting a post frees them. At most 200 posts per account (409 past that). Enclosing size and
block count (non-air, non-void blocks) are read from the snapshot when it is taken.

A post can also credit up to 10 co-authors besides its owner (the uploader, always the owner): players with an account
here, given by Minecraft name and linked to their profile, or free-text names (1 to 40 characters) of people without one.
It can carry up to 4 links (`http://` or `https://`, at most 300 characters, no user name or password in them), shown by
their domain.

Tags come from a fixed list (any other is 400): `redstone`, `farm`, `decoration`, `building`,
`survival-friendly`, `creative-only`, `map-art`, `transport`, `technical`, `terrain`, `statue`,
`interior`. The size category follows the block count: `small` under 1 000, `medium` under 10 000,
`big` under 100 000, `very-big` from 100 000 (stored per post, null for an old post that can't be read).

Post ids are 10 URL-safe characters. Names on an access list are looked up with Mojang's
profile API (10 s timeout): an unknown name is 400 naming it, Mojang unreachable is 502.

| Method & path | Result |
|---|---|
| `GET /api/board?tab=public\|shared\|mine&sort=top\|hot\|new&q=&tag=&size=&complexity=&mc=&author=&page=` | `{"posts":[post],"tab","sort","q","tags","size","complexity","mc","author","page","pageSize"(24),"total","pages"}`. `public` works logged out; `shared` (private posts listing the caller) and `mine` need a login (`mine` adds `postCount`, `postBytes`, every version counted). `q` matches every word against title, description, the author's Minecraft and display names and the co-authors' names, case-insensitively. `tag` may repeat: the post must have every one (unknown tag: 400). `size` is one size category, `complexity` one complexity category (else 400). `mc` (a Minecraft version) matches posts with any version for it; `author` (a uuid) posts that account owns or co-authored. Top: score, then newest. Hot: score / (hours + 2)^1.5. Default sort: `top` for public, `new` otherwise. |
| `POST /api/board` | `{"path","title","description"?,"visibility","allowed"?:[names],"tags"?:[tags],"coauthors"?:[{"player": name} \| {"name": text}],"links"?:[urls],"versionLabel"?,"mc"?}` returns 201 with the post. `versionLabel` (at most 60 characters) and `mc` (e.g. `26.3`) describe version 1. A co-author `player` must have an account here (400 `code: "unknown_player"`, naming it); the owner and repeats are dropped. 404 if the file is missing, 400 if it can't be converted, 413 over quota. |
| `GET /api/board/{id}` | The post, plus `description`, `myVote` (null logged out), and for the author and admins `sourcePath` and `allowed: [{uuid,name}]`. |
| `PATCH /api/board/{id}` | Author only: any of `title`, `description`, `visibility`, `allowed`, `tags`, `coauthors`, `links` (each list replaces the old one), `versionLabel`, `mc` (of the current version). |
| `POST /api/board/{id}/refresh` | Author only: snapshot the current version's cloud file again, in place (409 if it is gone or the version was uploaded directly, 400 if it can't be converted). Drops the 3D view's cache. |
| `GET /api/board/{id}/versions` | `{"versions":[version]}`, newest first. A version is `{"version","label","mc","filename","format","size","sha256","hasThumbnail","dimensions","blockCount","sizeClass","distinctTypes","complexity","created","current"}`. |
| `POST /api/board/{id}/versions` | Author only, a new version: JSON `{"path","label"?,"mc"?}` snapshots one of the author's cloud files (its thumbnail comes along), any other body is the schematic itself (gzip, at most 256 MiB) named by `?filename=` (plus `&label=&mc=`; no thumbnail). Same conversion, checks and 3D cache as posting. The current snapshot moves to `posts/<id>/v<n>/` and stays downloadable. 201 with the post; 413 over quota. |
| `DELETE /api/board/{id}/versions/{n}` | Author only: delete an earlier version (204), freeing its bytes. The current one: 409 (it goes with the post). |
| `DELETE /api/board/{id}` | Author or admin: 204. |
| `POST /api/board/{id}/vote` | `{"value": 1\|-1\|0}` (0 clears). Not on your own post (403). Returns `{"score","ups","downs","myVote"}`. |
| `POST /api/board/{id}/save` | Copies the snapshot (and thumbnail) into the caller's files as `board/<title>.<ext>`, adding ` (2)` etc. if taken, and records the post as the file's `source`. 201 with the file entry, 413 over quota. |
| `GET /api/board/{id}/file`, `/thumbnail`, `/export?format=` | Download, PNG thumbnail, conversion (same rules and limits as `/api/export`). `?version=<n>` picks an earlier version (404 if there is none), here and for `/model` and `/save`. |
| `GET /api/board/{id}/model` | The 3D view's data (below), gzip-encoded JSON for clients that accept it. 413 `{"error","visibleBlocks","maxVisibleBlocks"}` above 300 000 visible blocks, 400 for an old post that can't be read. Cached in the post's folder per snapshot. |

A post is `{"id","title","visibility","author":{"uuid","name","badges","displayName"},"filename","format","size","sha256","dimensions":[x,y,z]|null,"blockCount"|null,"sizeClass"|null,"tags":[...],"hasThumbnail","created","updated","score","ups","downs","hidden","coauthors":[{"uuid"|null,"name","displayName"}],"links":[...],"version","versionLabel","mc"|null,"versionCount","distinctTypes"|null,"complexity"|null,"downloads","shareUrl"}` (times in unix ms; `hidden`: an admin hid it, see Reports; `author.name` and a co-author's `name` are Minecraft names, `displayName` the name shown; the snapshot fields are the current version's). With detail, the author and admins also get `hiddenReason`.

**Versions.** Each version records a label (optional, at most 60 characters) and the Minecraft version it is for. The post's
page has a version list (the newest is shown by default, `?version=<n>` an older one). Every version counts toward the
author's quota until deleted.

**Share links and previews.** `/b/<id>` redirects (302) to `/board/<id>`. A post's page carries OpenGraph and Twitter card
tags (title, description or a summary of the build, the thumbnail as an absolute URL) so a pasted link previews in
Discord, YouTube descriptions and the like.

**Downloads.** `downloads` counts website downloads, exports and "Add to my cloud" saves together, once per account (the
author's own never count), or once per address and UTC day when logged out. Addresses are only kept as an HMAC (keyed with a
random secret in the `meta` table) for two days. The 3D view doesn't count.

**Complexity.** The number of distinct block types (properties ignored; no air or structure void) plus entity types in the
snapshot, `distinctTypes`, gives `complexity`: `simple` under 10, `moderate` under 30, `complex` under 80, `very-complex`
from 80. Posts store the count and the category is worked out when read, so the fixed thresholds (`complexity_classes()`)
can later come from the distribution of real uploads, as planned for size.

The model is `{"v":1,"size":[sx,sy,sz],"count","palette":[{"id","shape"}],"groups":[{"p","n","d"}]}`. It lists
only visible blocks: no air or structure voids, and no block whose six neighbours inside the schematic are all
opaque full blocks (a neighbour with no map colour in `static/blockcolors.json`, such as glass, or a slab or
carpet, doesn't hide it). `id` is the block id without properties; `shape` is `full`, `bottom`/`top` (slabs) or
`thin` (carpets, pressure plates). Each group is one palette entry `p` with `n` blocks; `d` is base64 of
little-endian uint32 values: the block indices `(y * sz + z) * sx + x` in ascending order, each as the
difference from the previous one (the first from 0). Several Litematica regions are merged over their
enclosing box as for `.nbt` exports.
Logged out is fine for public and unlisted posts, except voting and saving (401). Website pages: `/board`, `/board/<id>`,
`/b/<id>`, and a player's profile `/u/<uuid>`.

### Settings profiles

Named sets of the mod's settings, kept per account, that game instances can follow. Login and the whitelist gate as for
files; cookie requests need `X-Requested-With`.

| Method & path | Result |
|---|---|
| `GET /api/profiles` | `{"profiles":[{"id","name","updated"}],"limit"}`: oldest first; `updated` in unix **seconds**; `limit` 6, or null for admins (unlimited). |
| `GET /api/profiles/{id}` | `{"id","name","updated","settings":{...}}`. |
| `POST /api/profiles` | `{"name","settings"}`: 201 `{"id","name","updated"}`. 409 `{"error","code":"limit"}` at the limit. |
| `PUT /api/profiles/{id}` | `{"settings","name"?}` (either one at least): `{"id","name","updated"}`. |
| `DELETE /api/profiles/{id}` | 204. |

`settings` is any JSON object of at most 64 KiB (as compact JSON; 413 above). A name is 1 to 40 characters on one line and
goes through the word filter. Another account's profile id answers 404, as an unknown one does.

### Telemetry

`POST /api/telemetry` takes the mod's opt-in usage reports: anonymous (no login, no CSRF header), a JSON object of at most
32 KiB: `{install, mod, mc, loader, start, end, specs:{os, cpuCores, maxMemoryMb, gpu, gpuVendor, backend, renderDistance,
width, height}, render:{frames, avgMs, p50Ms, p99Ms, over33Ms, maxSections}, schematics:[...], settingsChanged:[...],
keys:{name: count}}`. Only `install` is required; each other field, if present, must have the right type (numbers within
sane ranges), else 400. Unknown `schematics` entries, setting names and key names are dropped rather than refused. Answers:
204 stored; 400 for garbage, including a body over 32 KiB (the mod deletes a report on 2xx and 400); 429 past 30 reports per
install or 60 per address in 15 minutes (the mod keeps it and retries later).

Nothing that identifies a player is stored: not the address (the rate limits are counted in memory, under a keyed hash), and
`install` (a random id per game install) only as its SHA-256. A `board:<id>` source is kept only if that post is public
(and not hidden) when the report arrives; otherwise it is stored as `board:private`, `board:unlisted` or `board:gone`.
Reports older than 180 days are deleted at startup.

`GET /api/admin/telemetry` (admins) and the admin page's Telemetry tab summarise the last 30 days: reports and distinct
installs over 7 and 30 days; the medians of the reports' p50 and p99 frame times by GPU vendor and by mod version; the most
changed settings and the operating system and memory buckets (per install, from its latest report); key uses; where loaded
builds came from, and the most used public board posts (only while still public).

### Identity: display names, pictures, Discord

Every account is one Minecraft account. On the site it shows a **display name**, at first the Minecraft name (which then
follows Minecraft renames until the player picks another). `PUT /api/account/display-name` `{"name"?,"tag"?}`:

- `name`: 3 to 24 characters, at least one letter or digit, no `#` or control characters, through the word filter; `null`
  goes back to the Minecraft name.
- Names are unique together with a hidden **tag** of 3 to 5 letters or digits, case-insensitively. The first account to
  take a name has no tag; a later one gets a random tag, and the answer carries a `warning` saying so. `tag` sets one
  instead (409 `code: "taken"` if that name and tag are in use; `null` or `""`: no tag, if nobody uses the name plainly).
- Returns `{"displayName","tag","warning"?}`. The tag is shown only to the player (account page) and admins (user list,
  reports, applications); public pages and the post API show the name alone. Migration 6 gave every existing account its
  Minecraft name; where several shared one, the oldest kept it plain and the others got tags.

**Pictures.** `GET /avatar/<uuid>` (dashes optional) serves the player's custom icon if they set one, else their Minecraft
face: the skin is fetched through the session server profile (textures, `SKIN` url), cached in `skins/` for a day, and the
front of the head plus the hat layer is cut out server-side (no image library: `images.py` reads the skin PNG) and served
as a 128 x 128 PNG. Only accounts and whitelisted players are looked up; anyone else, a player without a skin, an
unreadable skin or Mojang being down (retried after 10 minutes, the old face kept meanwhile) gets a plain placeholder face.
Pages only use this endpoint, so the CSP allows images from the site itself only. `PUT /api/account/avatar` (raw PNG or
JPEG, at most 256 KiB and 512 x 512 pixels, checked by magic bytes and header dimensions) sets a custom icon and
`DELETE /api/account/avatar` goes back to the face; both return `{"avatar","custom"}`.

**Discord.** With the three `SLOPMATICA_DISCORD_*` variables set, the account page offers linking: `/account/discord/link`
sends the player to Discord's authorization page (scope `identify`) with a single-use `state` (10 minutes) tied to the
browser session; `/account/discord/callback` checks it, trades the code for a token, reads the Discord id and username,
and stores them (one Slopmatica account per Discord account), then returns to `/account?discord=linked` (or `state`,
`cancelled`, `failed`, `taken`, `off`). `DELETE /api/account/discord` unlinks. Admins see the link in the user list.

**Profiles.** `/u/<uuid>` shows a player's display name, badges, picture and public posts (their own and those they
co-authored); `GET /api/users/<uuid>` returns `{"uuid","displayName","badges","avatar","page"}`.

### Access: the Fruitbowl whitelist

The cloud is for players on the Fruitbowl server's whitelist, plus admins. Anyone can log in (the game, login links,
passwords, `/api/me`, `/api/application`), and anyone, logged in or not, can view the board: lists, posts, downloads,
thumbnails, exports, the 3D view. Everything else that uses an account's cloud answers players without access with

```json
403 {"error": "Slopmatica Cloud is only open to Fruitbowl members for now. You can apply for access on the website.",
     "code": "not_whitelisted", "apply": "<PUBLIC_URL><ROOT_PATH>/apply"}
```

after the usual 401 (no or bad token) and CSRF checks. That covers `/api/files*`, `/api/thumbnails*`, `/api/export*`,
`POST /api/board`, `PATCH`/`DELETE /api/board/{id}`, `/refresh`, `/versions` (posting and deleting), `/vote`, `/save`,
`POST /api/reports`, `/api/account/storage-request`, `/api/profiles*`, `/api/account/display-name`, `/api/account/avatar`
and `/api/account/discord`. Nothing is deleted when a player has no access: their files and posts stay, and come
back the moment they are (re)added. Their public posts stay on the board; admins can hide them. On the website
`/` sends them to `/apply`, which explains this and holds the application form (logged out, it says how to log in).

The whitelist (table `whitelist`: `uuid`, `name`, `source` = `seed` | `upload` | `approved` | `manual`, `added`,
`added_by`) is seeded once from `SLOPMATICA_WHITELIST_SEED`, then managed on the admin page.

### Applications and storage requests

| Method & path | Result |
|---|---|
| `GET /api/application` | `{"whitelisted","application": application\|null,"apply"}` (the latest one). |
| `POST /api/application` | `{"reason","community","contact"?}` (1 to 2000 characters each, contact at most 200). 201 with the application. 409 if the player already has access or an application is pending; 429 after 3 within 7 days. |
| `GET /api/account/storage-request` | Members only: `{"usedBytes","quotaBytes","choices":[bytes],"maxBytes","request": request\|null}`. |
| `POST /api/account/storage-request` | Members only: `{"bytes","reason"}`: the total quota wanted (more than the current one, at most 1 TiB; the form offers 2, 5 and 10 GiB or a number of GiB) and why (at most 1000 characters). 409 if one is pending or the account is unlimited; 429 after 3 within 7 days. |

An application is `{"id","user":{"uuid","name","displayName","tag"},"reason","community","contact","status":"pending"|"approved"|"rejected","created","decided","note"}`;
a storage request `{"id","user","requestedBytes","reason","usedBytes","quotaBytes"(both when asked),"status","created","decided","grantedBytes","note"}`.
`note` is the admin's reason, shown to the player. The account page offers the storage form to members, open when usage is
above 80 %, folded otherwise, and shows the last request's answer.

### Reports

`POST /api/reports` `{"kind":"post"|"user","target": post id | uuid or name,"reason","details"?}`, members only. Reasons:
`spam`, `inappropriate`, `stolen` (not their build), `broken` (file), `other` (needs `details`; at most 2000 characters).
404 for a post the reporter can't see or an unknown user, 400 for one's own post or oneself, 409 while the same reporter
has an open report on the same target, 429 past 10 reports per 15 minutes (counted in `login_failures` under
`report-user:`). A report is `{"id","kind","target":{"id","label","uuid"},"reason","details","status":"open"|"resolved"|"dismissed","created","decided","note"}`.

Admins can hide a post (`posts.hidden`, with a reason): it then behaves as private to everyone but its author and admins,
whatever visibility the author picks, and is left out of the public and shared lists. The author sees it in their own
list and on its page, marked hidden with the reason.

### Badges

`verified`, `mod` and `dev`, given and taken by admins, shown after names on the board's cards, post pages and the
account page, and in the API as `author.badges` (posts) and `badges` (`/api/me`), always in that order.

### Word filter

`slopmatica_cloud/textfilter.py` holds a short, deliberately incomplete list of blocked words (extend `BLOCKED`). Text
is compared word by word after case folding, accent stripping, leetspeak (`sh1t`), stretched letters (`fuuuck`) and
spaced-out letters (`f u c k`), so words that merely contain a blocked one ("Scunthorpe", "class") pass. It checks post
titles and descriptions, co-author names, links and version labels, display names and tags, settings profile names,
applications, report details and storage request reasons, and answers 400
`{"error","code":"filtered","field"}`. Minecraft names of accounts or whitelisted players inside the text are never held
against it (the names themselves come from Mojang and are never filtered), and admins are not filtered.
Feedback is not filtered (bug reports may quote anything).

### Admin

The page `/admin` (tabs: Applications, Storage, Reports, Feedback = `/admin/feedback`, Whitelist, Badges, Users, Telemetry) and
`/admin/feedback` answer 404, the same page as any unknown address, to everyone but admins. Unknown non-API addresses
now get that HTML page; the API keeps `{"error": "Not Found"}`. The admin API answers 401 logged out and 403
`{"error": "only admins can do that"}` to everyone else; cookie requests need `X-Requested-With`.

| Method & path | Result |
|---|---|
| `GET /api/admin/applications?status=pending\|approved\|rejected\|all` | `{"applications":[...],"counts"}`, pending first, newest first. |
| `POST /api/admin/applications/{id}/approve` / `/reject` | `{"note"?}`. Approving adds the player to the whitelist (source `approved`). 409 if already decided. |
| `GET /api/admin/storage-requests?status=` | `{"requests":[...],"counts"}`, each with `current` usage and quota. |
| `POST /api/admin/storage-requests/{id}/approve` | `{"quotaBytes"?,"note"?}`: sets the account's quota (to what was asked unless given). `/reject` `{"note"?}`. |
| `GET /api/admin/reports?status=open\|resolved\|dismissed\|all` | `{"reports":[...],"counts"}`, with `reporter`, `target.name` and, for posts, `post: {"visibility","hidden"}\|null`. |
| `POST /api/admin/reports/{id}/resolve` / `/dismiss` | `{"note"?}`. |
| `POST /api/admin/posts/{id}/hide` | `{"hidden": bool, "reason"?}`; returns the post. |
| `GET /api/admin/whitelist` | `{"entries":[{"uuid","name","source","added","accountName"}],"count"}`. |
| `POST /api/admin/whitelist` | `{"player": name or uuid, "name"?}`: a name is looked up among accounts, then at Mojang; a uuid nobody here has used needs `name`. 201 (`"added": true`) or 200 if already on it. Approves the player's pending application. |
| `DELETE /api/admin/whitelist/{uuid}` | 204, or 404 if not on it. |
| `POST /api/admin/whitelist/import` | Body: a `whitelist.json` (at most 4 MiB). Adds new players (source `upload`), takes over changed names, removes nobody: `{"added":[{uuid,name}],"renamed":[{uuid,from,to}],"unchanged","missing":[entries not in the file],"count"}`. |
| `POST /api/admin/whitelist/remove` | `{"uuids":[...]}`: `{"removed","count"}` (the page's "remove all missing"). |
| `GET /api/admin/users?q=&page=` | Accounts matching part of the Minecraft, display or Discord name, or the uuid, most recently seen first, 50 a page: `{"users":[{"uuid","name","displayName","tag","discord":{"id","name"}\|null,"avatarVersion","customAvatar","admin","usedBytes","quotaBytes","quotaOverride","badges","whitelisted","whitelistSource","posts","created","lastSeen"}],"total","page","pages"}`. |
| `GET /api/admin/telemetry` | The telemetry summary (see Telemetry). |
| `PATCH /api/admin/users/{name or uuid}` | `{"quotaBytes": n \| null}` (null: back to the default). |
| `PUT` / `DELETE /api/admin/users/{name or uuid}/badges/{badge}` | Give or take a badge; returns the user. |

`users.last_seen` is written on authenticated requests at most every 5 minutes.

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
`source_title`, `source_author_uuid`, `source_author_name`. 4 (0.6.0) adds `releases` and `feedback`. 5 (0.7.0) adds `users.quota_bytes`, `users.last_seen`
(filled from `last_login`), `posts.hidden` and `posts.hidden_reason`, and the tables `whitelist`, `applications`,
`storage_requests`, `badges` and `reports` (one open application, storage request, or report per reporter and target, by
partial unique indexes); the whitelist seed is inserted in the same transaction. 6 (0.8.0) adds `users.display_name`,
`display_key` (case-folded), `display_tag`, `display_custom`, `avatar`, `avatar_version`, `discord_id` (unique) and
`discord_name`; `posts.version`, `version_label`, `mc_version`, `distinct_count`, `links` and `downloads`; the tables
`post_versions` (earlier snapshots), `post_authors`, `post_downloads`, `profiles`, `telemetry` and `meta` (the address
hashing secret, made at the first start). In the same transaction every account gets its Minecraft name as display name
(older accounts first; a repeated name gets a random tag) and the unique index on (name, tag) is created. After it, the
startup reads each existing snapshot once to count its block types. Before step 3, board snapshots that aren't
`.slopmatic` are converted in place, each committed on its own (size, hash, format, file name, so quota
usage follows); one that fails to convert is left as it is and logged. An interrupted run converts the
rest at the next start.
Board snapshot folders without a post are removed at startup, and posts whose snapshot is missing are dropped.
Other files in a post's folder (left over from an interrupted refresh or conversion, or a 3D view cache of an
older snapshot) are removed too, and so are `v<n>/` folders without a version; a version whose snapshot is missing is dropped.

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
