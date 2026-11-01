"use strict";
// The admin dashboard (/admin): decisions, whitelist, badges and quotas.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica", "Content-Type": "application/json" };
  const GIB = 1024 * 1024 * 1024;

  async function errorText(resp) {
    try { const j = await resp.json(); if (j && j.error) return j.error; } catch (_) {}
    return "HTTP " + resp.status;
  }

  function say(el, text, isErr) {
    if (!el) return;
    el.textContent = text || "";
    el.classList.toggle("err", !!isErr);
  }

  // the message line of a card (made on first use), else the page's
  function msgFor(item) {
    if (!item) return document.querySelector("[data-adm-msg]");
    let el = item.querySelector(":scope > .form-msg");
    if (!el) {
      el = document.createElement("p");
      el.className = "form-msg small";
      el.setAttribute("role", "status");
      item.appendChild(el);
    }
    return el;
  }

  async function send(method, url, body) {
    const opts = { method, headers: HDR, credentials: "same-origin" };
    if (body !== undefined) opts.body = JSON.stringify(body);
    return fetch(url, opts);
  }

  // ---- any button with data-adm-url (a full URL, base included): POST (or data-method) there, then reload. A card's note field goes
  // along as "note" (or data-note-as), its GB field as quotaBytes (or data-gb-as) unless data-no-gb.
  document.addEventListener("click", async (ev) => {
    const btn = ev.target.closest("[data-adm-url]");
    if (!btn) return;
    if (btn.dataset.confirm && !confirm(btn.dataset.confirm)) return;
    const item = btn.closest("[data-adm-item]");
    const msg = msgFor(item);
    const method = btn.dataset.method || "POST";
    const body = btn.dataset.body ? JSON.parse(btn.dataset.body) : {};
    const note = item && item.querySelector("[data-note]");
    if (note && note.value.trim()) body[btn.dataset.noteAs || "note"] = note.value.trim();
    const gb = item && item.querySelector("[data-gb]");
    if (gb && !("noGb" in btn.dataset)) {
      const n = Number(gb.value);
      if (!(n > 0) || !Number.isInteger(n)) { say(msg, "Enter a whole number of GB.", true); gb.focus(); return; }
      body[btn.dataset.gbAs || "quotaBytes"] = n * GIB;
    }
    btn.disabled = true;
    try {
      const r = await send(method, btn.dataset.admUrl, method === "DELETE" ? undefined : body);
      if (!r.ok) { say(msg, await errorText(r), true); return; }
      if ("inline" in btn.dataset) { const row = btn.closest("li"); if (row) row.remove(); return; }
      location.reload();
    } catch (_) {
      say(msg, "Network error, try again.", true);
    } finally {
      btn.disabled = false;
    }
  });

  // ---- whitelist: add one player
  const addForm = document.querySelector("form[data-wl-add]");
  if (addForm) {
    addForm.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = addForm.parentElement.querySelector(".form-msg");
      const player = addForm.elements.player.value.trim();
      if (!player) { say(msg, "Enter a Minecraft name or uuid.", true); return; }
      say(msg, "Adding…");
      const r = await send("POST", base + "/api/admin/whitelist", { player });
      if (!r.ok) { say(msg, await errorText(r), true); return; }
      const e = await r.json();
      say(msg, e.added ? "Added " + e.name + "." : e.name + " was already on the whitelist.");
      setTimeout(() => location.reload(), 700);
    });
  }

  // ---- whitelist: upload a whitelist.json, then show what changed and who is missing from the file
  const importForm = document.querySelector("form[data-wl-import]");
  if (importForm) {
    const result = document.querySelector("[data-wl-result]");
    const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text) e.textContent = text; return e; };
    const plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");

    function nameList(title, rows, render) {
      const box = el("div", "wl-group");
      box.appendChild(el("h3", "", title));
      const ul = el("ul", "wl-list");
      rows.forEach((row) => ul.appendChild(render(row)));
      box.appendChild(ul);
      return box;
    }

    function show(data) {
      result.replaceChildren();
      result.appendChild(el("p", "small", plural(data.added.length, "player") + " added, " + plural(data.renamed.length, "name") +
        " updated, " + data.unchanged + " unchanged. The whitelist now has " + plural(data.count, "player") + "."));
      if (data.added.length) {
        result.appendChild(nameList("Added", data.added, (p) => {
          const li = el("li"); li.appendChild(el("span", "wl-name", p.name)); li.appendChild(el("code", "wl-uuid muted", p.uuid)); return li;
        }));
      }
      if (data.renamed.length) {
        result.appendChild(nameList("Names updated", data.renamed, (p) => {
          const li = el("li"); li.appendChild(el("span", "wl-name", p.from + " → " + p.to)); li.appendChild(el("code", "wl-uuid muted", p.uuid)); return li;
        }));
      }
      if (data.missing.length) {
        const group = nameList("Not in the new file (" + data.missing.length + ")", data.missing, (p) => {
          const li = el("li");
          li.appendChild(el("span", "wl-name", p.name));
          li.appendChild(el("code", "wl-uuid muted", p.uuid));
          li.appendChild(el("span", "badge src-" + p.source, p.source));
          const b = el("button", "btn small ghost danger", "Remove");
          b.type = "button";
          b.dataset.admUrl = base + "/api/admin/whitelist/" + p.uuid;
          b.dataset.method = "DELETE";
          b.dataset.inline = "";
          li.appendChild(b);
          return li;
        });
        const all = el("button", "btn small ghost danger", "Remove all " + data.missing.length);
        all.type = "button";
        all.addEventListener("click", async () => {
          if (!confirm("Remove all " + data.missing.length + " players that are not in the new file? Their files stay.")) return;
          const r = await send("POST", base + "/api/admin/whitelist/remove", { uuids: data.missing.map((p) => p.uuid) });
          if (!r.ok) { alert(await errorText(r)); return; }
          location.reload();
        });
        group.querySelector("h3").after(el("p", "muted small", "Kept for now. Remove the ones who should lose access."), all);
        result.appendChild(group);
      }
      result.hidden = false;
    }

    importForm.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = importForm.parentElement.querySelector(".form-msg");
      const file = importForm.elements.file.files[0];
      if (!file) { say(msg, "Choose a whitelist.json first.", true); return; }
      say(msg, "Uploading…");
      try {
        const r = await fetch(base + "/api/admin/whitelist/import", {
          method: "POST", headers: HDR, credentials: "same-origin", body: await file.text(),
        });
        if (!r.ok) { say(msg, await errorText(r), true); return; }
        say(msg, "");
        show(await r.json());
      } catch (_) {
        say(msg, "Network error, try again.", true);
      }
    });
  }

  // ---- whitelist: filter the list
  const filter = document.querySelector("[data-wl-filter]");
  if (filter) {
    const rows = Array.from(document.querySelectorAll("[data-wl-row]"));
    filter.addEventListener("input", () => {
      const q = filter.value.trim().toLowerCase();
      rows.forEach((row) => { row.hidden = !!q && !row.dataset.search.includes(q); });
    });
  }

  // ---- badges: give one by name
  const grant = document.querySelector("form[data-badge-grant]");
  if (grant) {
    grant.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = grant.parentElement.querySelector(".form-msg");
      const player = grant.elements.player.value.trim();
      if (!player) { say(msg, "Enter a Minecraft name or uuid.", true); return; }
      const r = await send("PUT", base + "/api/admin/users/" + encodeURIComponent(player) + "/badges/" + encodeURIComponent(grant.elements.badge.value));
      if (!r.ok) { say(msg, await errorText(r), true); return; }
      location.reload();
    });
  }
})();
