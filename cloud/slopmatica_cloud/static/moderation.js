"use strict";
// The post page's report form, and an admin's Hide / Unhide button.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica", "Content-Type": "application/json" };

  async function errorText(resp) {
    try { const j = await resp.json(); if (j && j.error) return j.error; } catch (_) {}
    return "HTTP " + resp.status;
  }

  function say(el, text, isErr) {
    if (!el) return;
    el.textContent = text || "";
    el.classList.toggle("err", !!isErr);
  }

  const post = (url, body) => fetch(url, { method: "POST", headers: HDR, credentials: "same-origin", body: JSON.stringify(body) });

  const form = document.querySelector("form[data-report-form]");
  if (form) {
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const msg = form.querySelector(".form-msg");
      const kind = form.querySelector("input[name=kind]:checked").value;
      const reason = form.querySelector("input[name=reason]:checked").value;
      const details = form.elements.details.value.trim();
      if (reason === "other" && !details) { say(msg, "Say what is wrong in the details.", true); form.elements.details.focus(); return; }
      const button = form.querySelector("button[type=submit]");
      button.disabled = true;
      say(msg, "Sending…");
      try {
        const target = kind === "post" ? form.dataset.postId : form.dataset.author;
        const r = await post(base + "/api/reports", { kind, target, reason, details: details || null });
        if (!r.ok) { say(msg, await errorText(r), true); return; }
        form.replaceChildren();
        const done = document.createElement("p");
        done.textContent = "Thanks, an admin will look at it.";
        form.appendChild(done);
      } catch (_) {
        say(msg, "Network error, try again.", true);
      } finally {
        button.disabled = false;
      }
    });
  }

  const hide = document.querySelector("[data-hide-post]");
  if (hide) {
    hide.addEventListener("click", async () => {
      const hidden = hide.dataset.hidden === "true";
      let reason = null;
      if (hidden) {
        reason = prompt("Hide this post from everyone but its author? Reason, shown to the author (optional):", "");
        if (reason === null) return;
      }
      hide.disabled = true;
      const r = await post(base + "/api/admin/posts/" + encodeURIComponent(hide.dataset.hidePost) + "/hide", { hidden, reason: reason || null });
      hide.disabled = false;
      if (!r.ok) { say(document.querySelector("[data-owner-msg]"), await errorText(r), true); return; }
      location.reload();
    });
  }
})();
