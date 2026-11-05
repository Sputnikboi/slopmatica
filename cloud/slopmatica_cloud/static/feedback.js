"use strict";
// The feedback form (/feedback) and the admin list's resolve buttons (/admin/feedback).
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

  // ---- send feedback
  const form = document.getElementById("feedback-form");
  if (form) {
    const thanks = document.querySelector(".fb-thanks");
    const msg = form.querySelector(".form-msg");
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      const el = form.elements;
      const body = {
        type: el.type.value,
        message: el.message.value.trim(),
        modVersion: el.modVersion.value.trim() || null,
        mcVersion: el.mcVersion.value.trim() || null,
        contact: el.contact.value.trim() || null,
        anonymous: el.anonymous && el.anonymous.checked ? "true" : null,
      };
      if (!body.message) { say(msg, "Write a message first.", true); el.message.focus(); return; }
      const button = form.querySelector("button[type=submit]");
      // with a log or a schematic attached: the same fields as multipart/form-data
      const files = ["log", "schematic"].filter((k) => el[k] && el[k].files.length);
      let request = { method: "POST", headers: HDR, credentials: "same-origin", body: JSON.stringify(body) };
      if (files.length) {
        const data = new FormData();
        Object.entries(body).forEach(([k, v]) => { if (v !== null) data.append(k, v); });
        files.forEach((k) => data.append(k, el[k].files[0], el[k].files[0].name));
        request = { method: "POST", headers: { "X-Requested-With": "slopmatica" }, credentials: "same-origin", body: data };
      }
      button.disabled = true;
      say(msg, files.length ? "Uploading…" : "Sending…");
      try {
        const r = await fetch(base + "/api/feedback", request);
        if (!r.ok) { say(msg, await errorText(r), true); return; }
        say(msg, "");
        el.message.value = "";
        files.forEach((k) => { el[k].value = ""; });
        form.hidden = true;
        thanks.hidden = false;
      } catch (_) {
        say(msg, "Network error, try again.", true);
      } finally {
        button.disabled = false;
      }
    });
    document.querySelector("[data-fb-again]").addEventListener("click", () => {
      thanks.hidden = true;
      form.hidden = false;
      form.elements.message.focus();
    });
  }

  // ---- admin: delete a report's attachments
  document.querySelectorAll("[data-delete-attachments]").forEach((button) => {
    button.addEventListener("click", async () => {
      if (!confirm("Delete this report's attachments? The report itself stays.")) return;
      button.disabled = true;
      try {
        const r = await fetch(base + "/api/feedback/" + encodeURIComponent(button.dataset.deleteAttachments) + "/attachments", {
          method: "DELETE", headers: { "X-Requested-With": "slopmatica" }, credentials: "same-origin",
        });
        if (!r.ok && r.status !== 404) { say(document.querySelector("[data-fb-msg]"), await errorText(r), true); return; }
        location.reload();
      } catch (_) {
        say(document.querySelector("[data-fb-msg]"), "Network error, try again.", true);
      } finally {
        button.disabled = false;
      }
    });
  });

  // ---- admin: resolve / reopen
  document.querySelectorAll("[data-resolve]").forEach((button) => {
    button.addEventListener("click", async () => {
      const resolved = button.dataset.resolved !== "true";
      button.disabled = true;
      try {
        const r = await fetch(base + "/api/feedback/" + encodeURIComponent(button.dataset.resolve) + "/resolve", {
          method: "POST", headers: HDR, credentials: "same-origin", body: JSON.stringify({ resolved }),
        });
        if (!r.ok) { say(document.querySelector("[data-fb-msg]"), await errorText(r), true); return; }
        location.reload();
      } catch (_) {
        say(document.querySelector("[data-fb-msg]"), "Network error, try again.", true);
      } finally {
        button.disabled = false;
      }
    });
  });
})();
