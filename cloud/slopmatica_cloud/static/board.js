"use strict";
// Schematics board: posting from the files page, and the post page's votes, downloads and owner tools.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica" };
  const SCHEMATIC = /\.(slopmatic|nbt|litematic|schem)$/i;
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
  const lines = (text) => text.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);

  // the board's Minecraft version filter applies as soon as one is picked
  document.querySelectorAll("form[data-autosubmit] select").forEach((sel) => {
    sel.addEventListener("change", () => sel.form.submit());
  });

  function syncNames(form) {
    const vis = form.querySelector("input[name=visibility]:checked");
    const box = form.querySelector("[data-names]");
    if (box) box.hidden = !vis || vis.value !== "private";
    // a members-only group decides who sees the build, whatever the visibility says
    const group = form.querySelector("select[data-group-select]");
    const note = form.querySelector("[data-group-note]");
    if (group && note) {
      const opt = group.selectedOptions[0];
      note.hidden = !opt || !opt.hasAttribute("data-members-only");
    }
  }

  document.querySelectorAll("form[data-post-form]").forEach((form) => {
    syncNames(form);
    form.addEventListener("change", (ev) => { if (ev.target.name === "visibility" || ev.target.name === "group") syncNames(form); });
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = form.querySelector(".form-msg");
      const vis = form.querySelector("input[name=visibility]:checked");
      const body = {
        title: form.elements.title.value.trim(),
        description: form.elements.description.value,
        visibility: vis ? vis.value : "public",
        tags: Array.from(form.querySelectorAll("input[name=tags]:checked")).map((i) => i.value),
      };
      if (!body.title) { say(msg, "Give it a title.", true); form.elements.title.focus(); return; }
      if (form.elements.players) {
        body.coauthors = names(form.elements.players.value).map((player) => ({ player }))
          .concat(lines(form.elements.names.value).map((name) => ({ name })));
        body.links = lines(form.elements.links.value);
        body.versionLabel = form.elements.versionLabel.value.trim();
        body.mc = form.elements.mc.value.trim() || null;
        body.credit = form.elements.credit.value.trim() || null;
      }
      if (form.elements.group) body.group = form.elements.group.value || null;
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

  // ---- favourites: the heart on cards, post pages and the files page's Favourites (one post may show several)
  function showFav(postId, state) {
    document.querySelectorAll("[data-fav]").forEach((b) => {
      if (b.dataset.fav !== postId) return;
      b.classList.toggle("on", state.favorited);
      b.setAttribute("aria-pressed", state.favorited ? "true" : "false");
      b.title = state.favorited ? "Remove from favourites" : "Add to favourites";
      const count = b.querySelector("[data-fav-count]");
      if (count) count.textContent = Number(state.favorites).toLocaleString();
    });
    const item = document.querySelector('[data-fav-item="' + CSS.escape(postId) + '"]');
    if (item) item.classList.toggle("unfav", !state.favorited);
  }

  document.addEventListener("click", async (ev) => {
    const fav = ev.target.closest("[data-fav]");
    if (fav) {
      ev.preventDefault();  // a card's heart sits over the card's link
      if (fav.disabled) return;
      const on = fav.getAttribute("aria-pressed") !== "true";
      fav.disabled = true;
      try {
        const r = await send(on ? "POST" : "DELETE", apiPost(fav.dataset.fav) + "/favorite");
        if (r.ok) showFav(fav.dataset.fav, await r.json());
        else alert((on ? "Could not favourite it: " : "Could not remove it: ") + (await errorText(r)));
      } catch (_) {
        alert("Network error, try again.");
      } finally {
        fav.disabled = false;
      }
      return;
    }
    const save = ev.target.closest("[data-fav-save]");
    if (save && !save.disabled) {
      const msg = save.closest("[data-fav-item]").querySelector("[data-fav-msg]");
      save.disabled = true;
      say(msg, "Adding…");
      try {
        const r = await send("POST", apiPost(save.dataset.favSave) + "/save");
        if (r.ok) {
          const f = await r.json();
          msg.textContent = "Added as ";
          const code = document.createElement("code");
          code.textContent = f.path;
          msg.append(code, ". Reload to see it in your files.");
          msg.classList.remove("err");
        } else {
          say(msg, "Could not add it: " + (await errorText(r)), true);
        }
      } catch (_) {
        say(msg, "Could not add it: network error", true);
      } finally {
        save.disabled = false;
      }
    }
  });

  // ---- post page
  const post = document.querySelector(".post[data-post-id]");
  if (!post) return;
  const id = post.dataset.postId;
  const vote = post.querySelector(".vote");
  const ownerMsg = document.querySelector("[data-owner-msg]");
  const saveMsg = document.querySelector("[data-save-msg]");
  const versionQ = post.dataset.versionQ || ""; // "?version=<n>" while an older version is shown
  const withVersion = (url) => url + (versionQ ? (url.includes("?") ? "&" + versionQ.slice(1) : versionQ) : "");

  // ---- versions: pick one to view, upload a new one, delete an old one
  const versionSelect = post.querySelector("select[data-version-select]");
  if (versionSelect) {
    versionSelect.addEventListener("change", () => {
      location.href = postPage(id) + (versionSelect.value ? "?version=" + encodeURIComponent(versionSelect.value) : "");
    });
  }

  const versionForm = document.querySelector("form[data-version-form]");
  if (versionForm) {
    versionForm.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = versionForm.querySelector(".form-msg");
      const el = versionForm.elements;
      const file = el.file.files[0];
      const label = el.label.value.trim();
      const mc = el.mc.value.trim();
      if (!file && !el.path.value) { say(msg, "Pick one of your files or choose a file to upload.", true); return; }
      if (file && !SCHEMATIC.test(file.name)) { say(msg, "Only .slopmatic, .nbt, .litematic and .schem files.", true); return; }
      const submit = versionForm.querySelector("button[type=submit]");
      submit.disabled = true;
      say(msg, file ? "Uploading and converting…" : "Converting…");
      try {
        let r;
        if (file) {
          const q = new URLSearchParams({ filename: file.name });
          if (label) q.set("label", label);
          if (mc) q.set("mc", mc);
          r = await fetch(apiPost(id) + "/versions?" + q.toString(), {
            method: "POST", credentials: "same-origin",
            headers: Object.assign({ "Content-Type": "application/octet-stream" }, HDR), body: file,
          });
        } else {
          r = await send("POST", apiPost(id) + "/versions", { path: el.path.value, label, mc: mc || null });
        }
        if (!r.ok) { say(msg, await errorText(r), true); return; }
        location.href = postPage(id);
      } catch (_) {
        say(msg, "Network error, try again.", true);
      } finally {
        submit.disabled = false;
      }
    });
  }

  document.querySelectorAll("[data-delete-version]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const num = btn.dataset.deleteVersion;
      if (!confirm("Delete version " + num + "? Its file is gone for good.")) return;
      btn.disabled = true;
      const r = await send("DELETE", apiPost(id) + "/versions/" + encodeURIComponent(num));
      if (r.ok || r.status === 404) { location.href = postPage(id); return; }
      btn.disabled = false;
      say(ownerMsg, "Delete failed: " + (await errorText(r)), true);
    });
  });

  // ---- share link
  const copy = post.querySelector("[data-copy-share]");
  if (copy) {
    const field = post.querySelector("[data-share-url]");
    field.addEventListener("focus", () => field.select());
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(field.value);
        copy.textContent = "Copied";
      } catch (_) {
        field.select();
        copy.textContent = "Press Ctrl+C";
      }
      setTimeout(() => { copy.textContent = "Copy"; }, 2000);
    });
  }

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
        const r = await send("POST", withVersion(apiPost(id) + "/save"));
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

  // ---- Picture / 3D model. The viewer (and three.js) is only loaded on the first switch to it.
  const media = post.querySelector(".post-media");
  const modelPane = media && media.querySelector("[data-pane=model]");
  if (modelPane) {
    const picturePane = media.querySelector("[data-pane=picture]");
    const modelMsg = media.querySelector("[data-model-msg]");
    const buttons = media.querySelectorAll("[data-view]");
    let viewer = null;
    let loading = false;

    const show = (view) => {
      picturePane.hidden = view !== "picture";
      modelPane.hidden = view !== "model";
      buttons.forEach((b) => {
        const on = b.dataset.view === view;
        b.classList.toggle("on", on);
        b.setAttribute("aria-pressed", on ? "true" : "false");
      });
      if (viewer && view === "model") viewer.resize();
    };

    const webglAvailable = () => {
      try {
        const c = document.createElement("canvas");
        return !!(window.WebGLRenderingContext && (c.getContext("webgl2") || c.getContext("webgl")));
      } catch (_) {
        return false;
      }
    };

    async function openModel() {
      if (viewer) { show("model"); return; }
      if (loading) return;
      if (!webglAvailable()) {
        say(modelMsg, "The 3D view needs WebGL, which this browser has turned off or doesn't support.", true);
        return;
      }
      loading = true;
      say(modelMsg, "Loading the 3D model…");
      try {
        const r = await fetch(withVersion(apiPost(id) + "/model?v=" + encodeURIComponent(media.dataset.modelV)), { credentials: "same-origin" });
        if (r.status === 413) {
          let detail = "";
          try {
            const j = await r.json();
            if (j.visibleBlocks) detail = " It has " + j.visibleBlocks.toLocaleString() + " visible blocks; the limit is " + j.maxVisibleBlocks.toLocaleString() + ".";
          } catch (_) {}
          say(modelMsg, "Too big for the 3D view." + detail, true);
          return;
        }
        if (!r.ok) { say(modelMsg, "Could not load the 3D model: " + (await errorText(r)), true); return; }
        const model = await r.json();
        const mod = await import(base + "/static/model3d.js?v=" + encodeURIComponent(media.dataset.staticV));
        show("model");
        viewer = await mod.mount(modelPane.querySelector("[data-model-canvas]"), model);
        modelPane.querySelector("[data-model-reset]").addEventListener("click", () => viewer.reset());
        say(modelMsg, model.count.toLocaleString() + " visible blocks.");
      } catch (e) {
        show("picture");
        viewer = null;
        say(modelMsg, "Could not show the 3D model: " + ((e && e.message) || "error"), true);
      } finally {
        loading = false;
      }
    }

    buttons.forEach((b) => b.addEventListener("click", () => {
      if (b.dataset.view === "model") openModel();
      else show("picture");
    }));
  }

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
        const r = await fetch(withVersion(apiPost(id) + "/export?format=" + encodeURIComponent(fmt)), { credentials: "same-origin" });
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
