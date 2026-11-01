"use strict";
// Releases page: ?highlight=<version> scrolls to that version when the URL has no #v<version> to do it.
(function () {
  const hl = document.querySelector(".release.hl");
  if (!hl) return;
  const target = location.hash && document.getElementById(decodeURIComponent(location.hash.slice(1)));
  if (!target) hl.scrollIntoView({ block: "center" });
})();
