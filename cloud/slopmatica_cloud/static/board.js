"use strict";
// Schematics board: posting from the files page, and the post page's votes, downloads and owner tools.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica" };
  const SCHEMATIC = /\.(slopmatic|nbt|litematic)$/i;
  const apiPost = (id) => base + "/api/board/" + encodeURIComponent(id);
  const postPage = (id) => base + "/board/" + encodeURIComponent(id);

  async function errorText(resp) {
    try { const j = await resp.json(); if (j && j.error) return j.error; } catch (_) {}
    return "HTTP " + resp.status;
  }

  async function send(method, url, body) {
    const opts = { method, headers: Object.assign({}, HDR), credentials: "same-origin" };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetch(url, opts);
  }

  function say(el, text, isErr) {
    if (!el) return;
    el.textContent = text || "";
    el.classList.toggle("err", !!isErr);
  }

  // ---- the post form (create on the files page, edit on the post page)
  const names = (text) => text.split(/[\s,;]+/).map((s) => s.trim()).filter(Boolean);

  function syncNames(form) {
    const vis = form.querySelector("input[name=visibility]:checked");
    const box = form.querySelector("[data-names]");
    if (box) box.hidden = !vis || vis.value !== "private";
  }

  document.querySelectorAll("form[data-post-form]").forEach((form) => {
    syncNames(form);
    form.addEventListener("change", (ev) => { if (ev.target.name === "visibility") syncNames(form); });
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = form.querySelector(".form-msg");
      const vis = form.querySelector("input[name=visibility]:checked");
      const body = {
        title: form.elements.title.value.trim(),
        description: form.elements.description.value,
        visibility: vis ? vis.value : "public",
      };
      if (!body.title) { say(msg, "Give it a title.", true); form.elements.title.focus(); return; }
      // Names are only sent for private posts; an edit to another visibility keeps the stored list.
      if (body.visibility === "private") body.allowed = names(form.elements.allowed.value);
      const create = form.dataset.postForm === "create";
      if (create) body.path = form.elements.path.value;
      const submit = form.querySelector("button[type=submit]");
      submit.disabled = true;
      say(msg, body.allowed && body.allowed.length ? "Looking up players…" : "Saving…");
      try {
        const id = create ? null : document.querySelector(".post").dataset.postId;
        const r = await send(create ? "POST" : "PATCH", create ? base + "/api/board" : apiPost(id), body);
        if (!r.ok) { say(msg, await errorText(r), true); return; }
        const post = await r.json();
        location.href = postPage(post.id);
      } catch (_) {
        say(msg, "Network error, try again.", true);
      } finally {
        submit.disabled = false;
      }
    });
  });

  // ---- files page: "Post to board..." opens the dialog for that file
  const dialog = document.getElementById("post-dialog");
  if (dialog) {
    const form = dialog.querySelector("form");
    document.addEventListener("click", (ev) => {
      const btn = ev.target.closest("[data-act=post]");
      if (!btn) return;
      const path = btn.closest(".card").dataset.path;
      form.reset();
      form.elements.path.value = path;
      form.elements.title.value = path.split("/").pop().replace(SCHEMATIC, "").slice(0, 100);
      dialog.querySelector("[data-post-path]").textContent = path;
      say(form.querySelector(".form-msg"), "");
      syncNames(form);
      dialog.showModal();
      form.elements.title.select();
    });
    dialog.querySelector("[data-close]").addEventListener("click", () => dialog.close());
  }

  // ---- post page
  const post = document.querySelector(".post[data-post-id]");
  if (!post) return;
  const id = post.dataset.postId;
  const vote = post.querySelector(".vote");
  const ownerMsg = document.querySelector("[data-owner-msg]");
  const saveMsg = document.querySelector("[data-save-msg]");

  function showVote(state) {
    vote.dataset.myVote = String(state.myVote);
    vote.querySelector("[data-score]").textContent = state.score;
    vote.querySelector("[data-ups]").textContent = state.ups;
    vote.querySelector("[data-downs]").textContent = state.downs;
    vote.querySelectorAll(".vote-btn").forEach((b) => {
      const on = Number(b.dataset.value) === state.myVote;
      b.classList.toggle("on", on);
      b.setAttribute("aria-pressed", on ? "true" : "false");
    });
  }

  document.addEventListener("click", async (ev) => {
    const btn = ev.target.closest("[data-post-act]");
    if (!btn || btn.disabled) return;
    const act = btn.dataset.postAct;
    if (act === "vote") {
      const value = Number(btn.dataset.value);
      // clicking your current vote again takes it back
      const next = Number(vote.dataset.myVote) === value ? 0 : value;
      btn.disabled = true;
      try {
        const r = await send("POST", apiPost(id) + "/vote", { value: next });
        if (r.ok) showVote(await r.json());
        else alert("Vote failed: " + (await errorText(r)));
      } catch (_) {
        alert("Vote failed: network error");
      } finally {
        btn.disabled = false;
      }
    }
    if (act === "save") {
      btn.disabled = true;
      say(saveMsg, "Copying…");
      try {
        const r = await send("POST", apiPost(id) + "/save");
        if (r.ok) {
          const f = await r.json();
          saveMsg.textContent = "Added to your cloud as ";
          const code = document.createElement("code");
          code.textContent = f.path;
          saveMsg.append(code, ". It syncs to schematics/cloud/ in game. ");
          const a = document.createElement("a");
          a.href = base + "/";
          a.textContent = "Your files";
          saveMsg.append(a);
          saveMsg.classList.remove("err");
        } else {
          say(saveMsg, "Could not add it: " + (await errorText(r)), true);
        }
      } catch (_) {
        say(saveMsg, "Could not add it: network error", true);
      } finally {
        btn.disabled = false;
      }
    }
    if (act === "refresh") {
      btn.disabled = true;
      say(ownerMsg, "Updating…");
      try {
        const r = await send("POST", apiPost(id) + "/refresh");
        if (r.ok) { location.reload(); return; }
        say(ownerMsg, "Update failed: " + (await errorText(r)), true);
      } catch (_) {
        say(ownerMsg, "Update failed: network error", true);
      }
      btn.disabled = false;
    }
    if (act === "delete") {
      if (!confirm("Delete this post from the board?\n\nVotes are lost. Your cloud file is not affected.")) return;
      const r = await send("DELETE", apiPost(id));
      if (r.ok || r.status === 404) location.href = base + "/board" + ("own" in post.dataset ? "?tab=mine" : "");
      else say(ownerMsg, "Delete failed: " + (await errorText(r)), true);
    }
  });

  // export the snapshot as another format (converted on the server)
  const sel = post.querySelector("select[data-post-export]");
  if (sel) {
    sel.addEventListener("change", async () => {
      const fmt = sel.value;
      sel.value = "";
      if (!fmt) return;
      const label = sel.options[0];
      const text = label.textContent;
      sel.disabled = true;
      label.textContent = "Converting…";
      try {
        const r = await fetch(apiPost(id) + "/export?format=" + encodeURIComponent(fmt), { credentials: "same-origin" });
        if (!r.ok) { alert("Export failed: " + (await errorText(r))); return; }
        const url = URL.createObjectURL(await r.blob());
        const a = document.createElement("a");
        a.href = url;
        a.download = post.dataset.postFilename.replace(SCHEMATIC, "." + fmt);
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
  }
})();
