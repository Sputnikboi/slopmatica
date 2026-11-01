"use strict";
// Password login (landing page), and the account page's profile, password, Discord and session tools.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica", "Content-Type": "application/json" };

  async function errorText(resp) {
    try { const j = await resp.json(); if (j && j.error) return j.error; } catch (_) {}
    return "HTTP " + resp.status;
  }

  function say(el, text, isErr) {
    el.textContent = text || "";
    el.classList.toggle("err", !!isErr);
  }

  async function send(method, url, body, msg, button) {
    button.disabled = true;
    try {
      const r = await fetch(url, { method, headers: HDR, credentials: "same-origin", body: JSON.stringify(body || {}) });
      if (!r.ok) say(msg, await errorText(r), true);
      return r.ok ? r : null;
    } catch (_) {
      say(msg, "Network error, try again.", true);
      return null;
    } finally {
      button.disabled = false;
    }
  }

  // ---- landing page: log in with name + password
  const login = document.getElementById("login-form");
  if (login) {
    login.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = login.querySelector(".form-msg");
      const name = login.elements.name.value.trim();
      const password = login.elements.password.value;
      if (!name || !password) { say(msg, "Enter your Minecraft name and password.", true); return; }
      say(msg, "Logging in…");
      const r = await send("POST", base + "/api/login", { name, password }, msg, login.querySelector("button"));
      if (r) location.href = base + "/";
      else login.elements.password.select();
    });
  }

  // ---- account page: set, change or remove the password
  document.querySelectorAll("form[data-password-form]").forEach((form) => {
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = form.querySelector(".form-msg");
      const el = form.elements;
      const current = el.current ? el.current.value : undefined;
      if (el.current && !current) { say(msg, "Enter your current password.", true); return; }
      let r;
      if (form.dataset.passwordForm === "remove") {
        if (!confirm("Remove your website password? You will log in through the game again.")) return;
        r = await send("DELETE", base + "/api/account/password", { current }, msg, form.querySelector("button[type=submit]"));
      } else {
        const pw = el.new.value;
        if (pw.length < 8) { say(msg, "Use at least 8 characters.", true); return; }
        if (pw !== el.repeat.value) { say(msg, "The two passwords don't match.", true); return; }
        const body = { new: pw };
        if (current !== undefined) body.current = current;
        r = await send("POST", base + "/api/account/password", body, msg, form.querySelector("button[type=submit]"));
      }
      if (r) location.reload();
    });
  });

  // ---- account page: display name and tag
  const display = document.querySelector("form[data-display-form]");
  if (display) {
    const msg = display.querySelector(".form-msg");
    const shown = async (body, button) => {
      const r = await send("PUT", base + "/api/account/display-name", body, msg, button);
      if (!r) return;
      const j = await r.json();
      if (j.warning) {
        say(msg, j.warning, false);
        display.elements.tag.value = j.tag || "";
        setTimeout(() => location.reload(), 4000);
      } else {
        location.reload();
      }
    };
    display.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const name = display.elements.name.value.trim();
      const tag = display.elements.tag.value.trim();
      if (name.length < 3) { say(msg, "Use at least 3 characters.", true); return; }
      if (tag && !/^[A-Za-z0-9]{3,5}$/.test(tag)) { say(msg, "A tag is 3 to 5 letters or digits.", true); return; }
      // An unchanged tag field is left to the server, so a taken name gets a tag picked (and a warning).
      const body = { name };
      if (tag !== (display.elements.tag.defaultValue || "")) body.tag = tag || null;
      shown(body, display.querySelector("button[type=submit]"));
    });
    const reset = display.querySelector("[data-display-reset]");
    if (reset) reset.addEventListener("click", () => shown({ name: null }, reset));
  }

  // ---- account page: picture (choosing, cropping and uploading one is avatar.js)
  const avatar = document.querySelector("form[data-avatar-form]");
  if (avatar) {
    const msg = avatar.querySelector(".form-msg");
    const reset = avatar.querySelector("[data-avatar-reset]");
    if (reset) {
      reset.addEventListener("click", async () => {
        const r = await send("DELETE", base + "/api/account/avatar", {}, msg, reset);
        if (r) location.reload();
      });
    }
  }

  // ---- account page: Discord
  const unlink = document.querySelector("[data-discord-unlink]");
  if (unlink) {
    unlink.addEventListener("click", async () => {
      if (!confirm("Unlink your Discord account?")) return;
      const r = await send("DELETE", base + "/api/account/discord", {}, document.querySelector("[data-discord-msg]"), unlink);
      if (r) location.href = base + "/account";
    });
  }

  const out = document.querySelector("[data-logout-all]");
  if (out) {
    out.addEventListener("click", async () => {
      if (!confirm("Log out of every browser, including this one?")) return;
      const r = await send("POST", base + "/api/account/logout-all", {}, document.querySelector("[data-logout-msg]"), out);
      if (r) location.href = base + "/";
    });
  }
})();
