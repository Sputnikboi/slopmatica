"use strict";
// The application for access (the apply page) and the account page's request for more storage.
(function () {
  const base = document.body.dataset.base || "";
  const HDR = { "X-Requested-With": "slopmatica", "Content-Type": "application/json" };
  const GIB = 1024 * 1024 * 1024;

  async function errorText(resp) {
    try { const j = await resp.json(); if (j && j.error) return j.error; } catch (_) {}
    return "HTTP " + resp.status;
  }

  function say(el, text, isErr) {
    el.textContent = text || "";
    el.classList.toggle("err", !!isErr);
  }

  async function submit(form, url, body) {
    const msg = form.querySelector(".form-msg");
    const button = form.querySelector("button[type=submit]");
    button.disabled = true;
    say(msg, "Sending…");
    try {
      const r = await fetch(url, { method: "POST", headers: HDR, credentials: "same-origin", body: JSON.stringify(body) });
      if (!r.ok) { say(msg, await errorText(r), true); return; }
      location.reload();
    } catch (_) {
      say(msg, "Network error, try again.", true);
    } finally {
      button.disabled = false;
    }
  }

  // ---- apply for access
  const apply = document.getElementById("apply-form");
  if (apply) {
    apply.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const el = apply.elements;
      const body = { reason: el.reason.value.trim(), community: el.community.value.trim(), contact: el.contact.value.trim() || null };
      const msg = apply.querySelector(".form-msg");
      if (!body.reason) { say(msg, "Tell us why you'd like access.", true); el.reason.focus(); return; }
      if (!body.community) { say(msg, "Tell us how you know the community.", true); el.community.focus(); return; }
      submit(apply, base + "/api/application", body);
    });
  }

  // ---- ask for more storage
  const storage = document.querySelector("form[data-storage-form]");
  if (storage) {
    const custom = storage.querySelector("[data-custom-gb]");
    const sync = () => {
      const picked = storage.querySelector("input[name=amount]:checked");
      custom.hidden = !picked || picked.value !== "custom";
    };
    storage.addEventListener("change", sync);
    sync();
    storage.addEventListener("submit", (ev) => {
      ev.preventDefault();
      const el = storage.elements;
      const msg = storage.querySelector(".form-msg");
      const picked = storage.querySelector("input[name=amount]:checked");
      let bytes = picked && picked.value !== "custom" ? Number(picked.value) : Number(el.gb.value) * GIB;
      if (!(bytes > 0) || !Number.isInteger(bytes)) { say(msg, "Enter a whole number of GB.", true); el.gb.focus(); return; }
      const reason = el.reason.value.trim();
      if (!reason) { say(msg, "Say what you need the space for.", true); el.reason.focus(); return; }
      submit(storage, base + "/api/account/storage-request", { bytes, reason });
    });
  }
})();
