"use strict";
// Groups: the New group form, joining and leaving, the settings page, the members page, and a group moderator's tools
// on a post's page.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica" };
  const holder = document.querySelector("[data-group-id]");
  const groupId = holder ? holder.dataset.groupId : "";
  const apiGroup = (id) => base + "/api/groups/" + encodeURIComponent(id);

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

  // Send, then reload (or go to next) on success, or show the error in msg.
  async function act(btn, msg, method, url, body, next) {
    if (btn) btn.disabled = true;
    say(msg, "Saving…");
    try {
      const r = await send(method, url, body);
      if (!r.ok) { say(msg, await errorText(r), true); return null; }
      if (next !== null) { if (next) location.href = next; else location.reload(); }
      return r;
    } catch (_) {
      say(msg, "Network error, try again.", true);
      return null;
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  // ---- the New group form (/groups) and the settings page
  function settingsBody(form) {
    const el = form.elements;
    const checked = (name) => { const i = form.querySelector("input[name=" + name + "]:checked"); return i ? i.value : undefined; };
    const memberPerms = {};
    form.querySelectorAll("input[name=perm]").forEach((i) => { memberPerms[i.value] = i.checked; });
    const body = {
      name: el.name.value.trim(), description: el.description.value, visibility: checked("visibility"),
      joinPolicy: checked("joinPolicy"), postPolicy: checked("postPolicy"), postVisibility: checked("postVisibility"), memberPerms,
    };
    if (el.slug) body.slug = el.slug.value.trim();
    return body;
  }

  document.querySelectorAll("form[data-group-form]").forEach((form) => {
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = form.querySelector(".form-msg");
      const body = settingsBody(form);
      if (!body.name) { say(msg, "Give the group a name.", true); form.elements.name.focus(); return; }
      const create = form.dataset.groupForm === "create";
      const r = await act(form.querySelector("button[type=submit]"), msg, create ? "POST" : "PATCH",
        create ? base + "/api/groups" : apiGroup(groupId), body, null);
      if (r) {
        const page = base + "/g/" + encodeURIComponent((await r.json()).slug);
        location.href = create ? page : page + "/settings";
      }
    });
  });

  // ---- join, request, withdraw, leave, delete
  document.addEventListener("click", async (ev) => {
    const btn = ev.target.closest("[data-group-act]");
    if (!btn || btn.disabled) return;
    const what = btn.dataset.groupAct;
    const msg = document.querySelector("[data-group-msg]") || document.querySelector("[data-members-msg]");
    if (what === "join") await act(btn, msg, "POST", apiGroup(groupId) + "/join");
    if (what === "request") {
      const message = prompt("Ask to join. A message for the group's admins and moderators (optional):", "");
      if (message === null) return;
      await act(btn, msg, "POST", apiGroup(groupId) + "/requests", { message: message.slice(0, Number(btn.dataset.max) || 500) || null });
    }
    if (what === "withdraw") await act(btn, msg, "DELETE", apiGroup(groupId) + "/requests");
    if (what === "leave" && confirm("Leave this group?")) await act(btn, msg, "POST", apiGroup(groupId) + "/leave", undefined, base + "/groups");
    if (what === "delete" && confirm("Delete this group for good?\n\nIts builds stay, outside any group.")) {
      await act(btn, msg, "DELETE", apiGroup(groupId), undefined, base + "/groups");
    }
  });

  // ---- members page
  const members = document.querySelector("[data-owner]");
  if (members) {
    const listMsg = document.querySelector("[data-members-msg]");
    const rowOf = (el) => el.closest("[data-member]");

    document.querySelectorAll("form[data-member-add], form[data-member-ban]").forEach((form) => {
      form.addEventListener("submit", async (ev) => {
        ev.preventDefault();
        const player = form.elements.player.value.trim();
        const msg = form.parentElement.querySelector(".form-msg");
        if (!player) { say(msg, "Enter a Minecraft name.", true); return; }
        const ban = form.matches("[data-member-ban]");
        if (ban && !confirm("Ban " + player + "? They leave the group and can't join again until the ban is lifted.")) return;
        say(msg, "Looking them up…");
        const url = ban ? apiGroup(groupId) + "/bans" : apiGroup(groupId) + "/members";
        await act(form.querySelector("button"), msg, "POST", url, { player });
      });
    });

    members.addEventListener("change", async (ev) => {
      const row = rowOf(ev.target);
      if (!row) return;
      const url = apiGroup(groupId) + "/members/" + encodeURIComponent(row.dataset.member);
      if (ev.target.matches("[data-member-role]")) {
        await act(ev.target, listMsg, "PATCH", url, { role: ev.target.value });
      } else if (ev.target.matches("[data-member-perm]")) {
        // the member's own permissions: every select set to Yes or No (Group default leaves a key out)
        const perms = {};
        row.querySelectorAll("[data-member-perm]").forEach((s) => { if (s.value) perms[s.dataset.memberPerm] = s.value === "yes"; });
        if (await act(ev.target, listMsg, "PATCH", url, { perms: Object.keys(perms).length ? perms : null }, null)) say(listMsg, "Saved.");
      }
    });

    members.addEventListener("click", async (ev) => {
      const btn = ev.target.closest("[data-member-act], [data-request-act], [data-unban]");
      if (!btn || btn.disabled) return;
      if (btn.matches("[data-request-act]")) {
        const uuid = btn.closest("[data-request]").dataset.request;
        await act(btn, listMsg, "POST", apiGroup(groupId) + "/requests/" + encodeURIComponent(uuid) + "/" + btn.dataset.requestAct);
        return;
      }
      if (btn.matches("[data-unban]")) {
        await act(btn, listMsg, "DELETE", apiGroup(groupId) + "/bans/" + encodeURIComponent(btn.closest("[data-ban]").dataset.ban));
        return;
      }
      const row = rowOf(btn);
      const name = row.dataset.name;
      const what = btn.dataset.memberAct;
      if (what === "remove" && confirm("Remove " + name + " from the group?")) {
        await act(btn, listMsg, "DELETE", apiGroup(groupId) + "/members/" + encodeURIComponent(row.dataset.member));
      }
      if (what === "ban" && confirm("Ban " + name + "? They leave the group and can't join again until the ban is lifted.")) {
        await act(btn, listMsg, "POST", apiGroup(groupId) + "/bans", { player: row.dataset.member });
      }
      if (what === "transfer" && confirm("Make " + name + " the owner? You stay an admin, but only they can hand it over again.")) {
        await act(btn, listMsg, "POST", apiGroup(groupId) + "/transfer", { player: row.dataset.member });
      }
    });
  }

  // ---- a post's page: the group's admins and moderators
  const panel = document.querySelector("[data-group-post]");
  if (panel) {
    const postId = panel.dataset.groupPost;
    const msg = panel.querySelector("[data-group-msg]");
    panel.addEventListener("click", async (ev) => {
      const btn = ev.target.closest("[data-group-post-act]");
      if (!btn || btn.disabled) return;
      const url = apiGroup(groupId) + "/posts/" + encodeURIComponent(postId);
      if (btn.dataset.groupPostAct === "hide") await act(btn, msg, "POST", url + "/hide", { hidden: btn.dataset.hidden === "true" });
      if (btn.dataset.groupPostAct === "remove" && confirm("Take this build out of the group? The post itself stays.")) {
        await act(btn, msg, "DELETE", url);
      }
    });
    const form = panel.querySelector("form[data-group-edit]");
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const fmsg = form.querySelector(".form-msg");
      const body = {
        title: form.elements.title.value.trim(), description: form.elements.description.value,
        tags: Array.from(form.querySelectorAll("input[name=tags]:checked")).map((i) => i.value),
        credit: form.elements.credit.value.trim() || null,
        folder: form.elements.folder.value.trim(),
      };
      if (!body.title) { say(fmsg, "Give it a title.", true); return; }
      await act(form.querySelector("button[type=submit]"), fmsg, "PATCH", base + "/api/board/" + encodeURIComponent(postId), body);
    });
  }
})();
