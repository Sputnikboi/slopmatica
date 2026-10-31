"use strict";
// Password login (landing page) and the account page's password and session tools.
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

  const out = document.querySelector("[data-logout-all]");
  if (out) {
    out.addEventListener("click", async () => {
      if (!confirm("Log out of every browser, including this one?")) return;
      const r = await send("POST", base + "/api/account/logout-all", {}, document.querySelector("[data-logout-msg]"), out);
      if (r) location.href = base + "/";
    });
  }
})();
