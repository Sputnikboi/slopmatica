"use strict";
// Hover cards on players' names: any element with data-user="<uuid>" (an account here) shows the player's picture,
// display name and badges, Minecraft name, public posts and join date. Mouse: hover. Keyboard: focus. Touch: the
// first tap on a name shows the card (a second tap follows the link), a tap anywhere else closes it.
(function () {
  const card = document.getElementById("user-card");
  if (!card) return;
  const base = document.body.dataset.base || "";
  let labels = {};
  try { labels = JSON.parse(card.dataset.badges || "{}"); } catch (_) {}
  const cache = new Map(); // uuid -> Promise of the card's data, or of null (no account, network error)
  const SHOW_DELAY = 300, HIDE_DELAY = 200, GAP = 8, MARGIN = 8;
  let anchor = null; // the name the card is open for
  let showTimer = 0, hideTimer = 0;
  let lastPointer = "mouse";

  function load(uuid) {
    if (!cache.has(uuid)) {
      const p = fetch(base + "/api/users/" + encodeURIComponent(uuid) + "/card", { credentials: "same-origin" })
        .then((r) => (r.ok ? r.json() : null))
        .catch(() => { cache.delete(uuid); return null; }); // a network error may be retried on the next hover
      cache.set(uuid, p);
    }
    return cache.get(uuid);
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function badge(key) {
    // as the _badges.html macro draws them
    const label = labels[key] || key;
    const b = el("span", "ubadge ub-" + key);
    b.title = label;
    if (key === "verified") { b.textContent = "✓"; b.appendChild(el("span", "sr", " " + label)); }
    else b.textContent = label;
    return b;
  }

  function fill(u) {
    card.replaceChildren();
    const img = el("img", "head");
    img.src = base + "/avatar/" + u.uuid + (u.avatarVersion ? "?v=" + u.avatarVersion : "");
    img.alt = "";
    img.width = img.height = 48;
    const text = el("div", "ucard-text");
    const name = el("div", "ucard-name", u.displayName);
    if (u.tag) name.appendChild(el("span", "tag muted", "#" + u.tag)); // admins only
    (u.badges || []).forEach((k) => name.appendChild(badge(k)));
    text.appendChild(name);
    const ign = el("div", "ucard-ign muted small");
    ign.append("IGN ", el("strong", null, u.name));
    text.appendChild(ign);
    const n = u.contributions;
    const joined = new Date(u.joined);
    const facts = el("div", "ucard-facts small",
      n + (n === 1 ? " contribution" : " contributions") +
      (isNaN(joined) ? "" : " · Joined " + joined.toLocaleDateString(undefined, { dateStyle: "medium" })));
    text.appendChild(facts);
    card.append(img, text);
  }

  function place() {
    if (!anchor) return;
    const r = anchor.getBoundingClientRect();
    const vw = document.documentElement.clientWidth, vh = window.innerHeight;
    const w = card.offsetWidth, h = card.offsetHeight;
    // below the name if it fits, else above, else wherever there is more room
    let top = r.bottom + GAP;
    if (top + h > vh - MARGIN && r.top - GAP - h >= MARGIN) top = r.top - GAP - h;
    top = Math.max(MARGIN, Math.min(top, vh - h - MARGIN));
    const left = Math.max(MARGIN, Math.min(r.left, vw - w - MARGIN));
    card.style.top = Math.round(top) + "px"; // CSSOM, which the CSP allows (inline style attributes it doesn't)
    card.style.left = Math.round(left) + "px";
  }

  async function show(target) {
    clearTimeout(hideTimer);
    if (anchor === target && !card.hidden) return;
    if (anchor) anchor.removeAttribute("aria-describedby");
    anchor = target;
    const uuid = target.dataset.user;
    card.replaceChildren(el("span", "muted small", "Loading…"));
    card.hidden = false;
    target.setAttribute("aria-describedby", "user-card");
    place();
    const u = await load(uuid);
    if (anchor !== target) return; // moved on meanwhile
    if (!u) { hide(); return; }
    fill(u);
    place();
  }

  function hide() {
    clearTimeout(showTimer);
    clearTimeout(hideTimer);
    if (anchor) anchor.removeAttribute("aria-describedby");
    anchor = null;
    card.hidden = true;
  }

  const nameOf = (node) => (node instanceof Element ? node.closest("[data-user]") : null);

  // what the visitor is using right now: "mouse", "touch", "pen" or "keyboard"
  document.addEventListener("pointerover", (ev) => { lastPointer = ev.pointerType || "mouse"; }, true);
  document.addEventListener("pointerdown", (ev) => {
    lastPointer = ev.pointerType || "mouse";
    if (!card.hidden && !card.contains(ev.target) && nameOf(ev.target) !== anchor) hide(); // a tap elsewhere
  }, true);

  // mouse: hover, with a short delay each way so moving across the page doesn't flash cards
  document.addEventListener("mouseover", (ev) => {
    if (lastPointer !== "mouse") return;
    const target = nameOf(ev.target);
    if (target) {
      clearTimeout(hideTimer);
      if (target !== anchor) { clearTimeout(showTimer); showTimer = setTimeout(() => show(target), SHOW_DELAY); }
    } else if (card.contains(ev.target)) {
      clearTimeout(hideTimer); // the pointer moved onto the card itself
    }
  });
  document.addEventListener("mouseout", (ev) => {
    if (lastPointer !== "mouse") return;
    const from = nameOf(ev.target) || (card.contains(ev.target) ? card : null);
    if (!from) return;
    const to = ev.relatedTarget;
    if (to instanceof Node && (from.contains(to) || card.contains(to) || (anchor && anchor.contains(to)))) return;
    clearTimeout(showTimer);
    hideTimer = setTimeout(hide, HIDE_DELAY);
  });

  // keyboard: focus shows it, leaving or Escape hides it
  document.addEventListener("focusin", (ev) => {
    const target = nameOf(ev.target);
    if (target && lastPointer !== "touch") show(target);
  });
  document.addEventListener("focusout", (ev) => { if (nameOf(ev.target) === anchor) hide(); });
  document.addEventListener("keydown", (ev) => {
    if (ev.key === "Tab") lastPointer = "keyboard";
    if (ev.key === "Escape" && !card.hidden) hide();
  });

  // touch and pen: the first tap on a name opens its card instead of following the link
  document.addEventListener("click", (ev) => {
    if (lastPointer === "mouse") return;
    const target = nameOf(ev.target);
    if (!target) return;
    if (anchor === target && !card.hidden) return; // second tap: go
    ev.preventDefault();
    show(target);
  });

  window.addEventListener("scroll", place, { passive: true, capture: true });
  window.addEventListener("resize", place);
})();
