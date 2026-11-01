"use strict";
// The account page's picture. After a file is chosen, a crop step: the picture is dragged under a square (mouse, touch,
// or the arrow keys) and zoomed (wheel, slider, pinch, + and -); the square is drawn on a 256 x 256 canvas and saved as
// PNG, JPEG or WebP (if the browser can write it), whose size is shown before uploading it to PUT /api/account/avatar.
(function () {
  const form = document.querySelector("form[data-avatar-form]");
  if (!form) return;
  const base = document.body.dataset.base || "";
  const q = (sel) => form.querySelector(sel);
  const crop = q("[data-crop]"), view = q("[data-crop-view]"), preview = q("[data-crop-preview]");
  const small = q("[data-crop-preview-small]"), zoomInput = q("[data-crop-zoom]");
  const qualityField = q("[data-quality-field]"), qualityInput = q("[data-crop-quality]"), qualityOut = q("[data-crop-quality-out]");
  const sizeOut = q("[data-crop-size]"), webpChoice = q("[data-webp-choice]"), msg = q(".form-msg");
  const submit = q("button[type=submit]"), fileInput = form.elements.icon;
  const OUT = 256; // what is uploaded, whatever the page's canvases say
  preview.width = preview.height = small.width = small.height = OUT;
  const MAX_BYTES = 256 * 1024, MAX_ZOOM = 8, INSET = 0.08; // the square leaves 8% of the view free on each side

  let img = null, iw = 0, ih = 0; // the picture (an ImageBitmap, or an <img> from a data: URL) and its size
  let zoom = 1, cx = 0, cy = 0; // the square: top left corner (cx, cy) and side min(iw, ih) / zoom, in picture pixels
  let blob = null, seq = 0, encodeTimer = 0, frame = 0;

  function say(text, isErr) {
    msg.textContent = text || "";
    msg.classList.toggle("err", !!isErr);
  }

  const side = () => Math.min(iw, ih) / zoom;
  const kb = (n) => (n / 1024).toFixed(1) + " KB";
  const format = () => (form.querySelector("input[name=crop-format]:checked") || {}).value || "image/png";

  function clamp() {
    const s = side();
    cx = Math.min(Math.max(cx, 0), iw - s);
    cy = Math.min(Math.max(cy, 0), ih - s);
  }

  // zoom to z, keeping the picture point at (fx, fy) of the square (0..1 each) where it is
  function setZoom(z, fx, fy) {
    z = Math.min(Math.max(z, 1), MAX_ZOOM);
    const s0 = side(), px = cx + fx * s0, py = cy + fy * s0;
    zoom = z;
    const s1 = side();
    cx = px - fx * s1;
    cy = py - fy * s1;
    clamp();
    zoomInput.value = String(z);
    changed();
  }

  function fit() {
    zoom = 1;
    zoomInput.value = "1";
    cx = (iw - side()) / 2;
    cy = (ih - side()) / 2;
    changed();
  }

  // ---- drawing

  function sizeView() {
    const px = Math.round(view.clientWidth * (window.devicePixelRatio || 1)) || 320;
    if (view.width !== px) view.width = view.height = px;
  }

  function draw() {
    frame = 0;
    if (!img) return;
    const c = view.getContext("2d"), D = view.width, m = Math.round(D * INSET), B = D - 2 * m, k = B / side();
    c.fillStyle = "#3a3f47";
    c.fillRect(0, 0, D, D);
    c.imageSmoothingEnabled = true;
    c.imageSmoothingQuality = "high";
    c.drawImage(img, m - cx * k, m - cy * k, iw * k, ih * k);
    c.fillStyle = "rgba(0, 0, 0, 0.55)"; // dim what falls outside the square
    c.beginPath();
    c.rect(0, 0, D, D);
    c.rect(m, m, B, B);
    c.fill("evenodd");
    c.strokeStyle = "#ffffff";
    c.lineWidth = Math.max(1, Math.round(D / 200));
    c.strokeRect(m, m, B, B);
  }

  // The square at most twice the output size, halving it step by step: one big downscale in drawImage aliases.
  function source() {
    let src = img, x = cx, y = cy, s = side();
    while (s > OUT * 2) {
      const n = Math.ceil(s / 2), t = document.createElement("canvas");
      t.width = t.height = n;
      const tc = t.getContext("2d");
      tc.imageSmoothingQuality = "high";
      tc.drawImage(src, x, y, s, s, 0, 0, n, n);
      src = t; x = 0; y = 0; s = n;
    }
    return { src, x, y, s };
  }

  function render(type) {
    const c = preview.getContext("2d");
    c.clearRect(0, 0, OUT, OUT);
    if (type === "image/jpeg") { c.fillStyle = "#ffffff"; c.fillRect(0, 0, OUT, OUT); } // JPEG has no transparency
    c.imageSmoothingEnabled = true;
    c.imageSmoothingQuality = "high";
    const { src, x, y, s } = source();
    c.drawImage(src, x, y, s, s, 0, 0, OUT, OUT);
    const sc = small.getContext("2d");
    sc.clearRect(0, 0, OUT, OUT);
    sc.drawImage(preview, 0, 0);
  }

  function encode() {
    encodeTimer = 0;
    if (!img) return;
    const type = format(), mine = ++seq;
    render(type);
    submit.disabled = true;
    sizeOut.textContent = "Compressing…";
    preview.toBlob((b) => {
      if (mine !== seq) return; // changed again meanwhile
      if (b && type === "image/webp" && b.type !== "image/webp") {
        // this browser can't write WebP (it sends a PNG instead): offer the other two only
        webpChoice.hidden = true;
        form.querySelector("input[name=crop-format][value='image/jpeg']").checked = true;
        formatChanged();
        return;
      }
      blob = b;
      if (!b) { sizeOut.textContent = "This browser couldn't make the picture."; return; }
      const big = b.size > MAX_BYTES;
      sizeOut.textContent = OUT + " × " + OUT + " " + type.slice(6).toUpperCase() + ", " + kb(b.size) +
        (big ? " — over 256 KB: lower the quality or pick JPEG." : "");
      sizeOut.classList.toggle("err", big);
      submit.disabled = big;
    }, type, type === "image/png" ? undefined : Number(qualityInput.value));
  }

  function changed() {
    blob = null;
    if (!frame) frame = requestAnimationFrame(draw);
    clearTimeout(encodeTimer);
    encodeTimer = setTimeout(encode, 150);
  }

  function formatChanged() {
    qualityField.hidden = format() === "image/png";
    changed();
  }

  // ---- choosing a picture

  function viaDataUrl(file) {
    // the CSP allows images from data: URLs, not blob: ones
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const im = new Image();
        im.onload = () => resolve(im);
        im.onerror = reject;
        im.src = reader.result;
      };
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
  }

  async function open(file) {
    let pic = null;
    try { pic = window.createImageBitmap ? await createImageBitmap(file) : null; } catch (_) { pic = null; }
    if (!pic) { try { pic = await viaDataUrl(file); } catch (_) { pic = null; } }
    const w = pic ? pic.naturalWidth || pic.width : 0, h = pic ? pic.naturalHeight || pic.height : 0;
    if (!w || !h) { say("That file isn't a picture this browser can read.", true); return; }
    img = pic; iw = w; ih = h;
    crop.hidden = false;
    sizeView();
    fit();
    say(iw === ih ? "Your picture is square: upload it as it is (scaled to " + OUT + " × " + OUT + "), or zoom in to crop it."
      : "Drag the picture to place it in the square, and zoom to crop closer.");
    view.focus({ preventScroll: true });
  }

  function close() {
    img = null; blob = null; seq++;
    clearTimeout(encodeTimer);
    crop.hidden = true;
    fileInput.value = "";
  }

  fileInput.addEventListener("change", () => {
    const file = fileInput.files[0];
    if (file) open(file); else close();
  });
  q("[data-crop-cancel]").addEventListener("click", () => { close(); say(""); });
  q("[data-crop-fit]").addEventListener("click", fit);
  zoomInput.addEventListener("input", () => setZoom(Number(zoomInput.value), 0.5, 0.5));
  qualityInput.addEventListener("input", () => { qualityOut.textContent = Number(qualityInput.value).toFixed(2); changed(); });
  form.querySelectorAll("input[name=crop-format]").forEach((r) => r.addEventListener("change", formatChanged));
  window.addEventListener("resize", () => { if (img) { sizeView(); changed(); } });

  // ---- moving and zooming: one pointer drags, two pinch; the wheel and keys too

  const pointers = new Map(); // pointerId -> {x, y} (client pixels)
  function fraction(clientX, clientY) {
    const r = view.getBoundingClientRect(), m = r.width * INSET, B = r.width - 2 * m;
    const f = (v) => Math.min(Math.max(v / B, 0), 1);
    return [f(clientX - r.left - m), f(clientY - r.top - m)];
  }
  const boxCss = () => view.getBoundingClientRect().width * (1 - 2 * INSET);

  view.addEventListener("pointerdown", (ev) => {
    if (!img) return;
    view.setPointerCapture(ev.pointerId);
    pointers.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
    ev.preventDefault();
  });
  view.addEventListener("pointermove", (ev) => {
    const prev = pointers.get(ev.pointerId);
    if (!prev) return;
    const now = { x: ev.clientX, y: ev.clientY };
    if (pointers.size === 1) {
      const k = side() / boxCss();
      cx -= (now.x - prev.x) * k;
      cy -= (now.y - prev.y) * k;
      clamp();
      changed();
    } else if (pointers.size === 2) {
      const other = [...pointers.entries()].find(([id]) => id !== ev.pointerId)[1];
      const before = Math.hypot(prev.x - other.x, prev.y - other.y), after = Math.hypot(now.x - other.x, now.y - other.y);
      if (before > 0) setZoom(zoom * (after / before), ...fraction((now.x + other.x) / 2, (now.y + other.y) / 2));
    }
    pointers.set(ev.pointerId, now);
  });
  ["pointerup", "pointercancel", "lostpointercapture"].forEach((t) => view.addEventListener(t, (ev) => pointers.delete(ev.pointerId)));
  view.addEventListener("wheel", (ev) => {
    if (!img) return;
    ev.preventDefault();
    setZoom(zoom * Math.exp(-ev.deltaY * (ev.deltaMode === 1 ? 0.05 : 0.0015)), ...fraction(ev.clientX, ev.clientY));
  }, { passive: false });
  view.addEventListener("keydown", (ev) => {
    if (!img) return;
    const step = side() * (ev.shiftKey ? 0.1 : 0.02);
    const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    if (moves[ev.key]) {
      cx += moves[ev.key][0];
      cy += moves[ev.key][1];
      clamp();
      changed();
    } else if (ev.key === "+" || ev.key === "=") setZoom(zoom * 1.1, 0.5, 0.5);
    else if (ev.key === "-" || ev.key === "_") setZoom(zoom / 1.1, 0.5, 0.5);
    else return;
    ev.preventDefault();
  });

  // ---- uploading

  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    if (!img) { say("Choose a picture first.", true); return; }
    if (!blob) { clearTimeout(encodeTimer); encode(); say("Still compressing, try again in a moment.", true); return; }
    if (blob.size > MAX_BYTES) { say("That picture is larger than 256 KB: lower the quality or pick JPEG.", true); return; }
    submit.disabled = true;
    say("Uploading…");
    try {
      const r = await fetch(base + "/api/account/avatar", {
        method: "PUT", credentials: "same-origin",
        headers: { "X-Requested-With": "slopmatica", "Content-Type": blob.type || "application/octet-stream" }, body: blob,
      });
      if (!r.ok) {
        let text = "HTTP " + r.status;
        try { const j = await r.json(); if (j && j.error) text = j.error; } catch (_) {}
        say(text, true);
        return;
      }
      location.reload();
    } catch (_) {
      say("Network error, try again.", true);
    } finally {
      submit.disabled = false;
    }
  });
})();
