"use strict";
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica" };
  const SCHEMATIC = /\.(slopmatic|nbt|litematic|schem)$/i;
  const enc = (path) => path.split("/").map(encodeURIComponent).join("/");
  const fileUrl = (path) => base + "/api/files/" + enc(path);

  // usage bar width (CSP forbids inline styles)
  document.querySelectorAll(".bar-fill[data-pct]").forEach((el) => { el.style.width = el.dataset.pct + "%"; });

  // a thumbnail that fails to load falls back to the placeholder
  document.querySelectorAll(".thumb img").forEach((img) => {
    const fallback = () => { const ph = document.createElement("div"); ph.className = "ph"; img.replaceWith(ph); };
    if (img.complete && img.naturalWidth === 0) fallback(); else img.addEventListener("error", fallback);
  });

  // localise timestamps
  document.querySelectorAll("time[datetime]").forEach((el) => {
    const d = new Date(el.getAttribute("datetime"));
    if (!isNaN(d)) el.textContent = d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
  });

  // ---- client-side path validation (the server re-checks everything)
  const BAD = /[\x00-\x1f\x7f<>:"|?*\\]/;
  function checkPath(p) {
    if (!p || p.length > 300) return "path is empty or longer than 300 characters";
    const segs = p.split("/");
    if (segs.length > 8) return "too many folder levels";
    for (const s of segs) {
      if (!s) return "empty folder name";
      if (s.startsWith(".")) return "names may not start with '.'";
      if (s.length > 100) return "a name is longer than 100 characters";
      if (BAD.test(s)) return 'names may not contain <>:"|?*\\';
      if (s !== s.trim() || s.endsWith(".")) return "names may not start/end with spaces or end with '.'";
    }
    if (!SCHEMATIC.test(segs[segs.length - 1])) return "only .slopmatic, .nbt, .litematic and .schem files are allowed";
    return null;
  }
  const normFolder = (f) => f.trim().replace(/\\/g, "/").replace(/^\/+|\/+$/g, "").replace(/\/{2,}/g, "/");

  async function errorText(resp) {
    try { const j = await resp.json(); if (j && j.error) return j.error; } catch (_) {}
    return "HTTP " + resp.status;
  }

  const cards = Array.from(document.querySelectorAll(".card"));
  const existing = new Set(cards.map((c) => c.dataset.path));

  // ---- search + folder filter
  const search = document.getElementById("search");
  const filter = document.getElementById("folder-filter");
  const noMatch = document.getElementById("no-match");
  function applyFilter() {
    const q = (search.value || "").trim().toLowerCase();
    const folder = filter.value;
    let shown = 0;
    for (const c of cards) {
      const okFolder = folder === "*" || c.dataset.folder === folder || (folder && c.dataset.folder.startsWith(folder + "/"));
      const ok = okFolder && (!q || c.dataset.search.includes(q));
      c.hidden = !ok;
      if (ok) shown++;
    }
    if (noMatch) noMatch.hidden = shown !== 0 || cards.length === 0;
  }
  if (search) search.addEventListener("input", applyFilter);
  if (filter) filter.addEventListener("change", applyFilter);

  // ---- card actions
  document.addEventListener("click", async (ev) => {
    const btn = ev.target.closest("[data-act]");
    if (!btn) return;
    const card = btn.closest(".card");
    const path = card && card.dataset.path;
    const act = btn.dataset.act;
    if (act === "folder") {
      filter.value = card.dataset.folder;
      applyFilter();
      return;
    }
    if (act === "delete") {
      if (!confirm('Delete "' + path + '"?\n\nIt will also disappear from every game instance synced with this account.')) return;
      const r = await fetch(fileUrl(path), { method: "DELETE", headers: HDR, credentials: "same-origin" });
      if (r.ok || r.status === 404) location.reload();
      else alert("Delete failed: " + (await errorText(r)));
    }
    if (act === "rename") {
      let to = prompt("New name (use / for folders):", path);
      if (to === null) return;
      to = normFolder(to);
      if (!to || to === path) return;
      // Keep the original format's extension if the new name has none.
      if (!SCHEMATIC.test(to)) to += path.match(SCHEMATIC)[0];
      const err = checkPath(to);
      if (err) { alert("Invalid name: " + err); return; }
      const r = await fetch(base + "/api/files/move", {
        method: "POST",
        headers: Object.assign({ "Content-Type": "application/json" }, HDR),
        credentials: "same-origin",
        body: JSON.stringify({ from: path, to: to }),
      });
      if (r.ok) location.reload();
      else alert("Rename failed: " + (await errorText(r)));
    }
  });

  // ---- export as another format (converted on the server)
  document.addEventListener("change", async (ev) => {
    const sel = ev.target.closest("select[data-export]");
    if (!sel) return;
    const fmt = sel.value;
    sel.value = "";
    if (!fmt) return;
    const path = sel.closest(".card").dataset.path;
    const label = sel.options[0];
    const text = label.textContent;
    sel.disabled = true;
    label.textContent = "Converting\u2026";
    try {
      const r = await fetch(base + "/api/export/" + enc(path) + "?format=" + encodeURIComponent(fmt), { credentials: "same-origin" });
      if (!r.ok) { alert("Export failed: " + (await errorText(r))); return; }
      const url = URL.createObjectURL(await r.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = path.split("/").pop().replace(SCHEMATIC, "." + fmt);
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60000);
    } catch (_) {
      alert("Export failed: network error");
    } finally {
      sel.disabled = false;
      label.textContent = text;
    }
  });

  // ---- uploads
  const drop = document.getElementById("drop");
  const input = document.getElementById("file-input");
  const folderInput = document.getElementById("upload-folder");
  const progress = document.getElementById("progress");
  let busy = false;
  const queue = [];

  function row(name) {
    const li = document.createElement("li");
    li.innerHTML = '<span class="pname"></span><span class="pstate muted"></span><div class="pbar"><div></div></div>';
    li.querySelector(".pname").textContent = name;
    progress.appendChild(li);
    return {
      set(state, frac, isErr) {
        li.querySelector(".pstate").textContent = state;
        if (frac != null) li.querySelector(".pbar > div").style.width = Math.round(frac * 100) + "%";
        li.classList.toggle("err", !!isErr);
      },
    };
  }

  function put(path, file, ui) {
    return new Promise((resolve) => {
      const xhr = new XMLHttpRequest();
      xhr.open("PUT", fileUrl(path));
      xhr.setRequestHeader("X-Requested-With", "slopmatica");
      xhr.setRequestHeader("Content-Type", "application/octet-stream");
      xhr.upload.onprogress = (e) => { if (e.lengthComputable) ui.set(Math.round((e.loaded / e.total) * 100) + "%", e.loaded / e.total); };
      xhr.onload = () => {
        if (xhr.status === 200 || xhr.status === 201) { ui.set("done", 1); resolve(true); return; }
        let msg = "HTTP " + xhr.status;
        try { const j = JSON.parse(xhr.responseText); if (j.error) msg = j.error; } catch (_) {}
        ui.set(msg, 1, true);
        resolve(false);
      };
      xhr.onerror = () => { ui.set("network error", 1, true); resolve(false); };
      xhr.send(file);
    });
  }

  async function pump() {
    if (busy) return;
    busy = true;
    let anyOk = false;
    while (queue.length) {
      const job = queue.shift();
      if (await put(job.path, job.file, job.ui)) anyOk = true;
    }
    busy = false;
    if (anyOk && !progress.querySelector("li.err")) setTimeout(() => location.reload(), 600);
    else if (anyOk) {
      const li = document.createElement("li");
      li.innerHTML = '<span class="pname"><a href="">Refresh to see the uploaded files</a></span>';
      progress.appendChild(li);
    }
  }

  function enqueue(files) {
    const folder = normFolder(folderInput ? folderInput.value : "");
    for (const file of files) {
      const path = (folder ? folder + "/" : "") + file.name;
      const ui = row(path);
      const err = checkPath(path);
      if (err) { ui.set(err, 1, true); continue; }
      if (existing.has(path) && !confirm('"' + path + '" already exists. Replace it?')) { ui.set("skipped", 0, true); continue; }
      ui.set("queued", 0);
      queue.push({ path, file, ui });
    }
    pump();
  }

  if (input) input.addEventListener("change", () => { enqueue(Array.from(input.files)); input.value = ""; });
  if (drop) {
    ["dragenter", "dragover"].forEach((t) => drop.addEventListener(t, (e) => { e.preventDefault(); drop.classList.add("over"); }));
    ["dragleave", "drop"].forEach((t) => drop.addEventListener(t, (e) => { e.preventDefault(); drop.classList.remove("over"); }));
    drop.addEventListener("drop", (e) => { if (e.dataTransfer && e.dataTransfer.files.length) enqueue(Array.from(e.dataTransfer.files)); });
    // dropping outside the zone should not navigate away
    window.addEventListener("dragover", (e) => e.preventDefault());
    window.addEventListener("drop", (e) => e.preventDefault());
  }
})();
