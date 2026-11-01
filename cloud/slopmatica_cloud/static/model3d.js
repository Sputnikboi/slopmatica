// The post page's 3D view: draws the visible blocks from GET /api/board/<id>/model with three.js.
// Loaded (with three.js) only when someone switches to "3D model". See model3d.py for the data.
import * as THREE from "./vendor/three/three.module.js";
import { OrbitControls } from "./vendor/three/OrbitControls.js";

const CLEAR = "#dfe8f0"; // blocks with no map colour (glass, panes, ...): light and see-through
const CLEAR_OPACITY = 0.35;
const UNKNOWN = "#8c9199"; // ids missing from blockcolors.json
// brightness per face, in BoxGeometry's face order: +x, -x, +y (top), -y (bottom), +z, -z
const FACE_SHADE = [0.86, 0.72, 1.0, 0.5, 0.8, 0.94];
// [height, vertical offset] of each shape the server sends
const SHAPES = { full: [1, 0], bottom: [0.5, -0.25], top: [0.5, 0.25], thin: [1 / 16, -15 / 32] };
const FOV = 40;

let colorsPromise = null;
function blockColors() {
  if (!colorsPromise) {
    colorsPromise = fetch(new URL("./blockcolors.json", import.meta.url), { credentials: "same-origin" })
      .then((r) => (r.ok ? r.json() : {}))
      .catch(() => ({}));
  }
  return colorsPromise;
}

// base64 little-endian uint32 deltas -> ascending block indices
function decodeIndices(b64, n) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  const view = new DataView(bytes.buffer);
  const out = new Uint32Array(n);
  let acc = 0;
  for (let i = 0; i < n; i++) {
    acc += view.getUint32(i * 4, true);
    out[i] = acc;
  }
  return out;
}

const geometryCache = new Map();
function shapeGeometry(shape) {
  if (geometryCache.has(shape)) return geometryCache.get(shape);
  const [h, dy] = SHAPES[shape] || SHAPES.full;
  const g = new THREE.BoxGeometry(1, h, 1);
  g.translate(0, dy, 0);
  // per-face shading on top of the lights, so faces read clearly even straight on
  const count = g.attributes.position.count; // 24: four per face
  const colors = new Float32Array(count * 3);
  for (let v = 0; v < count; v++) colors.fill(FACE_SHADE[Math.floor(v / 4)], v * 3, v * 3 + 3);
  g.setAttribute("color", new THREE.Float32BufferAttribute(colors, 3));
  geometryCache.set(shape, g);
  return g;
}

export async function mount(container, model) {
  const colors = await blockColors();
  const [sx, sy, sz] = model.size;

  // throws if WebGL can't start; the caller reports it and keeps the picture
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(0x000000, 0); // the container's CSS background shows through (light or dark)
  container.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  scene.add(new THREE.HemisphereLight(0xffffff, 0x8a90a0, 1.9));
  const sun = new THREE.DirectionalLight(0xffffff, 1.5);
  sun.position.set(0.45, 1, 0.3);
  scene.add(sun);

  // one InstancedMesh per colour, shape and see-through-ness
  const batches = new Map();
  for (const g of model.groups) {
    const entry = model.palette[g.p];
    const hex = colors[entry.id];
    const clear = hex === null;
    const color = clear ? CLEAR : hex || UNKNOWN;
    const shape = SHAPES[entry.shape] ? entry.shape : "full";
    const key = color + "|" + shape + "|" + (clear ? "c" : "");
    let b = batches.get(key);
    if (!b) batches.set(key, (b = { color, shape, clear, parts: [], n: 0 }));
    b.parts.push(decodeIndices(g.d, g.n));
    b.n += g.n;
  }
  const cx = sx / 2, cy = sy / 2, cz = sz / 2;
  const materials = [];
  for (const b of batches.values()) {
    const material = new THREE.MeshLambertMaterial({
      color: b.color, vertexColors: true,
      transparent: b.clear, opacity: b.clear ? CLEAR_OPACITY : 1, depthWrite: !b.clear,
    });
    materials.push(material);
    const mesh = new THREE.InstancedMesh(shapeGeometry(b.shape), material, b.n);
    const m = mesh.instanceMatrix.array;
    let o = 0;
    for (const idx of b.parts) {
      for (let i = 0; i < idx.length; i++, o += 16) {
        const v = idx[i];
        const x = v % sx;
        const rest = (v - x) / sx;
        const z = rest % sz;
        const y = (rest - z) / sz;
        m.fill(0, o, o + 16);
        m[o] = m[o + 5] = m[o + 10] = m[o + 15] = 1;
        m[o + 12] = x + 0.5 - cx;
        m[o + 13] = y + 0.5 - cy;
        m[o + 14] = z + 0.5 - cz;
      }
    }
    mesh.instanceMatrix.needsUpdate = true;
    mesh.frustumCulled = false;
    if (b.clear) mesh.renderOrder = 1; // after the opaque blocks
    scene.add(mesh);
  }

  // frame the model's bounds
  const radius = Math.max(0.5 * Math.hypot(sx, sy, sz), 1);
  const camera = new THREE.PerspectiveCamera(FOV, 1, radius / 200, radius * 40);
  const controls = new OrbitControls(camera, renderer.domElement);
  controls.target.set(0, 0, 0);
  controls.maxDistance = radius * 12;
  controls.minDistance = 0.5;
  controls.zoomToCursor = true;

  // the closest distance (along a fixed 3/4 view) at which all eight corners of the box are in view
  function frame(aspect) {
    const dir = new THREE.Vector3(1, 0.8, 1.3).normalize();
    const corners = [];
    for (const x of [-cx, cx]) for (const y of [-cy, cy]) for (const z of [-cz, cz]) corners.push(new THREE.Vector3(x, y, z));
    const p = new THREE.Vector3();
    const fits = (d) => {
      camera.position.copy(dir).multiplyScalar(d);
      camera.lookAt(0, 0, 0);
      camera.updateMatrixWorld();
      return corners.every((c) => {
        p.copy(c).project(camera);
        return Math.abs(p.x) <= 0.9 && Math.abs(p.y) <= 0.9 && p.z > -1 && p.z < 1;
      });
    };
    // the bounding sphere always fits: search between it and nothing
    let hi = radius / Math.sin(THREE.MathUtils.degToRad(FOV / 2)) / Math.min(aspect, 1) * 1.1;
    let lo = 0;
    for (let i = 0; i < 30; i++) {
      const mid = (lo + hi) / 2;
      if (fits(mid)) hi = mid; else lo = mid;
    }
    camera.position.copy(dir).multiplyScalar(hi);
    controls.target.set(0, 0, 0);
    controls.update();
  }

  const render = () => renderer.render(scene, camera);
  function resize() {
    const w = Math.max(1, container.clientWidth), h = Math.max(1, container.clientHeight);
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    render();
  }
  resize();
  frame(camera.aspect);
  controls.saveState();
  controls.addEventListener("change", render);
  render();

  const observer = new ResizeObserver(resize);
  observer.observe(container);

  renderer.domElement.addEventListener("webglcontextlost", (ev) => {
    ev.preventDefault();
    const p = document.createElement("p");
    p.className = "model-lost";
    p.textContent = "The 3D view stopped (the graphics driver reset). Reload the page to try again.";
    container.appendChild(p);
  });

  return {
    reset() {
      controls.reset();
      render();
    },
    resize,
    dispose() {
      observer.disconnect();
      controls.dispose();
      materials.forEach((mat) => mat.dispose());
      renderer.dispose();
      renderer.domElement.remove();
    },
  };
}
