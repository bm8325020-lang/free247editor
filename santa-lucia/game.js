// SANTA LÚCIA — protótipo jogável (vertical slice de gameplay)
// Three.js r160, sem build. Abra via servidor local (ver README.md).
import * as THREE from './three.module.js';

const DEBUG = new URLSearchParams(location.search).has('debug');
const TOUCH = new URLSearchParams(location.search).has('touch') || matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window;
const $ = (id) => document.getElementById(id);
const rand = (a, b) => a + Math.random() * (b - a);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const lerp = (a, b, t) => a + (b - a) * t;
const pick = (arr) => arr[(Math.random() * arr.length) | 0];

// =====================================================================
// CONFIG — todos os números de balanceamento ficam aqui
// =====================================================================
const T = 2.4;          // tamanho do tile (m)
const WALL_H = 3.0;
const CFG = {
  player: { walk: 2.9, sprint: 4.9, crouch: 1.4, radius: 0.32, eye: 1.62, crouchEye: 0.95,
            staminaMax: 4.5, staminaRegen: 0.55, breathMax: 6, breathRegen: 0.8 },
  noise:  { walk: 6, sprint: 15, crouch: 1.8, fusePickup: 32, fuseInsert: 22, door: 40 },
  lamp:   { battery: 100, drain: 0.32, focusDrain: 2.6,          // %/s
            range: 15, focusRange: 20, halfCone: 14, focusHalfCone: 7,  // graus
            heatRate: 30, coolRate: 20, overheat: 5,                 // %/s, s
            exposureNormal: 0.4, exposureFocus: 1.9, normalCap: 0.6 },
  creature: { patrol: 1.5, investigate: 2.5, hunt: 3.3, chaseDark: 4.35, chaseLit: 3.1,
              flinchMul: 0.45, killDist: 1.25, fovHalf: 62,
              seeFlashlight: 26, seeLit: 17, seeDark: 9, seeCrouchDark: 5.5, closeSense: 2.6,
              loseTime: 2.6, searchTime: 18, retreatTime: 5.5, stagger: 0.9,
              resistStep: 0.35, resistMax: 1.6, resistDecay: 0.015,
              hideSuspicion: 1.7, hideSenseDist: 3.4, wakeAfter: 75, director: 40 },
  endgame: { duration: 30 },
};

// =====================================================================
// MAPA — gerado por retângulos (tiles de 2.4 m). '#' parede.
// =====================================================================
const W = 44, H = 32;
const grid = [...Array(H)].map(() => Array(W).fill('#'));
const carve = (x0, y0, x1, y1) => { for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) grid[y][x] = '.'; };
const put = (x, y, c) => { grid[y][x] = c; };

// --- recepção (hub seguro, iluminado)
carve(1, 1, 9, 7); put(10, 4, '.');
put(5, 0, 'X');            // porta principal
put(7, 1, 'B');            // painel de fusíveis
// --- corredor leste (1 tile de largura: desconfortável)
carve(10, 4, 42, 4);
// --- ala de contenção (celas acolchoadas)
for (let i = 0; i < 7; i++) { const x0 = 14 + i * 4; carve(x0, 1, x0 + 2, 2); put(x0 + 1, 3, '.'); }
// --- enfermaria
carve(14, 6, 26, 13); put(20, 5, '.');
// --- necrotério / câmaras frias
carve(29, 6, 41, 13); put(35, 5, '.');
// --- corredor central (desce ao subsolo)
carve(11, 5, 11, 24);
// --- administração / segurança / arquivo
carve(1, 9, 9, 12); put(5, 8, '.'); put(10, 11, '.');
carve(1, 14, 6, 16); put(3, 13, '.');
carve(8, 14, 9, 16); put(8, 13, '.');
// --- sala de autópsia
carve(29, 15, 41, 21); put(35, 14, '.');
// --- laboratório
carve(13, 16, 26, 21); put(12, 18, '.');
// --- atalhos (loops para fuga)
put(24, 14, '.'); put(24, 15, '.');          // enfermaria -> laboratório
put(27, 18, '.'); put(28, 18, '.');          // laboratório -> autópsia
carve(41, 22, 41, 24);                        // poço de serviço autópsia -> subsolo
// --- subsolo
carve(1, 25, 42, 25);
carve(1, 27, 20, 30); put(6, 26, '.'); put(15, 26, '.');   // porão
carve(22, 27, 28, 30); put(25, 26, '.');                   // caldeiras
carve(30, 27, 41, 30); put(35, 26, '.');                   // sala elétrica

// --- nichos com armários (esconderijos no corredor)
for (const [x, y] of [[17, 3], [29, 3], [41, 3], [12, 14], [10, 20], [12, 23], [20, 24], [33, 24]]) put(x, y, 'L');
// --- armários em salas
for (const [x, y] of [[1, 1], [1, 2], [9, 9], [6, 16], [9, 16], [14, 13], [26, 6], [29, 13], [29, 21], [26, 21], [1, 30], [30, 30], [28, 30]]) put(x, y, 'L');
// --- macas (esconder embaixo)
for (const [x, y] of [[16, 8], [19, 8], [22, 8], [16, 11], [19, 11], [22, 11], [32, 8], [32, 11], [38, 11]]) put(x, y, 'S');
// --- mesas de autópsia, bancadas/gabinetes, cadeira de rodas, caixotes
for (const [x, y] of [[33, 18], [37, 18]]) put(x, y, 'T');
for (const [x, y] of [[15, 16], [16, 16], [17, 16], [20, 20], [21, 20], [1, 12], [2, 12], [24, 28], [25, 28], [26, 28], [37, 28], [38, 28], [31, 27], [33, 27]]) put(x, y, 'K');
for (const [x, y] of [[8, 6], [25, 12], [3, 7]]) put(x, y, 'W');
for (const [x, y] of [[3, 28], [4, 28], [8, 27], [8, 28], [8, 29], [12, 29], [12, 30], [13, 29], [16, 28], [17, 28], [18, 30], [4, 30]]) put(x, y, 'c');

const ZONES = [
  { id: 'reception', name: 'Recepção', r: [1, 0, 10, 7], wall: 'tile', floor: 'lino' },
  { id: 'cells', name: 'Ala de Contenção', r: [14, 1, 41, 3], wall: 'padded', floor: 'ftile' },
  { id: 'corrA', name: 'Corredor Leste', r: [10, 4, 42, 4], wall: 'tile', floor: 'lino' },
  { id: 'ward', name: 'Enfermaria', r: [14, 5, 26, 13], wall: 'tile', floor: 'lino' },
  { id: 'morgue', name: 'Necrotério — Câmaras Frias', r: [29, 5, 41, 13], wall: 'drawers', floor: 'ftile' },
  { id: 'admin', name: 'Administração', r: [1, 8, 10, 12], wall: 'paint', floor: 'lino' },
  { id: 'archive', name: 'Arquivo Médico', r: [1, 13, 6, 16], wall: 'paint', floor: 'lino' },
  { id: 'security', name: 'Guarita de Segurança', r: [8, 13, 9, 16], wall: 'paint', floor: 'lino' },
  { id: 'autopsy', name: 'Sala de Autópsia', r: [29, 14, 41, 21], wall: 'tile', floor: 'ftile' },
  { id: 'lab', name: 'Laboratório de Fototerapia', r: [12, 14, 28, 21], wall: 'tile', floor: 'ftile' },
  { id: 'corrB', name: 'Corredor Central', r: [11, 5, 11, 24], wall: 'tile', floor: 'lino' },
  { id: 'shaft', name: 'Poço de Serviço', r: [41, 22, 41, 24], wall: 'concrete', floor: 'concrete' },
  { id: 'base', name: 'Subsolo', r: [1, 24, 42, 25], wall: 'concrete', floor: 'concrete' },
  { id: 'cellar', name: 'Porão', r: [1, 26, 20, 30], wall: 'concrete', floor: 'concrete' },
  { id: 'boiler', name: 'Caldeiras', r: [22, 26, 28, 30], wall: 'concrete', floor: 'concrete' },
  { id: 'elec', name: 'Sala Elétrica / Gerador', r: [30, 26, 41, 30], wall: 'concrete', floor: 'concrete' },
];
function zoneAt(x, y) { for (const z of ZONES) { const r = z.r; if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return z; } return null; }

const FUSES = [
  { id: 0, x: 2, y: 16, where: 'Arquivo Médico', lights: [10, 1] },
  { id: 1, x: 39, y: 1, where: 'Cela de Isolamento 7', lights: [3, 11] },
  { id: 2, x: 41, y: 9, where: 'Gaveta 7 do necrotério', lights: [4] },
  { id: 3, x: 40, y: 29, where: 'Gerador do subsolo', lights: [9] },
];
const BATTERIES = [[8, 10], [25, 7], [17, 20], [5, 29], [39, 20], [36, 2]];
const NOTES = [
  { x: 3, y: 5, title: 'Comunicado da Diretoria — 03/1974', body: 'O portão principal opera com trava eletromagnética. Em caso de pane, o painel ao lado da porta exige <b>quatro fusíveis de 30A</b>.<br><br>Por ordem do Dr. Vasconcellos, os fusíveis foram retirados e guardados em setores distintos. "Até que o Paciente 17 seja contido, ninguém sai."' },
  { x: 2, y: 10, title: 'Prontuário — Paciente 17', body: 'Protocolo de fototerapia contínua, semana 11. Para garantir a exposição ininterrupta, procedeu-se à <b>remoção cirúrgica das pálpebras</b>.<br><br>O paciente agora reage com dor extrema a luz direta no rosto. Em ambiente escuro, apresenta agitação, força e velocidade anormais.' },
  { x: 24, y: 12, title: 'Bilhete — Enf. Marta', body: 'Ele não gosta das salas com luz acesa. Ele passa por elas, mas não fica.<br><br>Nos corredores apagados ele <b>corre</b>. Quando você ouvir a respiração parar, ele está escutando. Se esconda e <b>segure o ar</b>.' },
  { x: 30, y: 7, title: 'Livro de Óbitos', body: '14 corpos em 3 semanas. Todos com queimaduras de retina.<br><br>A gaveta 7 está trancada <i>por dentro</i>. Alguém escondeu algo nela — dá para ver um brilho âmbar pela fresta.' },
  { x: 19, y: 17, title: 'Anotação de Laboratório', body: 'Feixe difuso: o paciente cobre o rosto e desacelera, mas avança.<br>Feixe <b>concentrado</b>, direto nos olhos: recua em agonia por alguns segundos.<br><br>Problema: o refletor superaquece. E ele <b>aprende</b> — cada exposição exige mais tempo que a anterior.' },
  { x: 31, y: 29, title: 'Etiqueta no Gerador', body: 'ATENÇÃO: ao energizar o portão, o <b>alarme geral</b> soará em todo o prédio durante a partida do motor (~30s).<br><br>Rabiscado à caneta: <i>"ele vai saber exatamente onde você está."</i>' },
];
const LIGHTS = [
  { x: 5, y: 3, type: 'steady', color: 0xcfe2e8, int: 16, r: 15 },     // 0 recepção (refúgio)
  { x: 5, y: 10.5, type: 'flicker', color: 0xd6e8c8, int: 10, r: 11 }, // 1 admin
  { x: 20, y: 9.5, type: 'flicker', color: 0xc8e6d0, int: 12, r: 13 }, // 2 enfermaria
  { x: 24, y: 4, type: 'flicker', color: 0xd0e8d8, int: 7, r: 9 },     // 3 corredor
  { x: 35, y: 9.5, type: 'steady', color: 0x8fc0ff, int: 11, r: 13 },  // 4 necrotério
  { x: 35, y: 18, type: 'steady', color: 0xf2f6ff, int: 18, r: 12 },   // 5 autópsia (foco cirúrgico)
  { x: 19.5, y: 18.5, type: 'flicker', color: 0x9cffb0, int: 10, r: 12 }, // 6 laboratório
  { x: 11, y: 14, type: 'flicker', color: 0xd0e0d0, int: 6, r: 8 },    // 7 corredor central
  { x: 25, y: 25, type: 'emergency', color: 0xff2410, int: 8, r: 10 }, // 8 subsolo
  { x: 36, y: 28.5, type: 'emergency', color: 0xff3018, int: 9, r: 10 }, // 9 sala elétrica
  { x: 3.5, y: 15, type: 'steady', color: 0xffd8a0, int: 6, r: 8 },    // 10 arquivo
  { x: 36, y: 4, type: 'flicker', color: 0xd0e8d8, int: 6, r: 8 },     // 11 corredor fundo
];
let PLAYER_START = [5, 5];
const CREATURE_SPAWNS = [[10, 29], [38, 29], [39, 12]];

const SOLID = new Set(['#', 'X', 'L', 'S', 'T', 'c', 'W', 'K', 'G']);  // 'B' (painel) é de parede: tile andável
const inB = (x, y) => x >= 0 && y >= 0 && x < W && y < H;
const isSolid = (x, y) => !inB(x, y) || SOLID.has(grid[y][x]);
const isWall = (x, y) => !inB(x, y) || grid[y][x] === '#' || grid[y][x] === 'X' || grid[y][x] === 'G';
const tileOf = (v) => [Math.floor(v.x / T), Math.floor(v.z / T)];
const tc = (x, y, yy = 0) => new THREE.Vector3((x + 0.5) * T, yy, (y + 0.5) * T);

// line of sight por amostragem no grid (só paredes bloqueiam)
function los(ax, az, bx, bz) {
  const dx = bx - ax, dz = bz - az, d = Math.hypot(dx, dz), n = Math.ceil(d / 0.3);
  for (let i = 1; i < n; i++) { const t = i / n; if (isWall(Math.floor((ax + dx * t) / T), Math.floor((az + dz * t) / T))) return false; }
  return true;
}

// ----- A* em grid, 8 direções, custo extra opcional (luz)
function astar(sx, sy, tx, ty, extraCost) {
  if (isSolid(tx, ty)) { const n = nearestFree(tx, ty); if (!n) return null; [tx, ty] = n; }
  const N = W * H, g = new Float32Array(N).fill(1e9), came = new Int32Array(N).fill(-1), closed = new Uint8Array(N);
  const heap = []; const push = (f, i) => { heap.push([f, i]); let k = heap.length - 1; while (k > 0) { const p = (k - 1) >> 1; if (heap[p][0] <= heap[k][0]) break; [heap[p], heap[k]] = [heap[k], heap[p]]; k = p; } };
  const pop = () => { const top = heap[0], last = heap.pop(); if (heap.length) { heap[0] = last; let k = 0; for (;;) { const l = 2 * k + 1, r = l + 1; let m = k; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === k) break; [heap[m], heap[k]] = [heap[k], heap[m]]; k = m; } } return top; };
  const s = sy * W + sx, t = ty * W + tx; g[s] = 0; push(0, s);
  const hf = (x, y) => Math.hypot(x - tx, y - ty);
  while (heap.length) {
    const [, i] = pop(); if (i === t) break; if (closed[i]) continue; closed[i] = 1;
    const x = i % W, y = (i / W) | 0;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue; const nx = x + dx, ny = y + dy; if (isSolid(nx, ny)) continue;
      if (dx && dy && (isSolid(x + dx, y) || isSolid(x, y + dy))) continue;
      const j = ny * W + nx; const c = g[i] + (dx && dy ? 1.414 : 1) + (extraCost ? extraCost(nx, ny) : 0);
      if (c < g[j]) { g[j] = c; came[j] = i; push(c + hf(nx, ny), j); }
    }
  }
  if (came[t] < 0 && s !== t) return null;
  const path = []; let k = t; while (k !== s && k >= 0) { path.push([k % W, (k / W) | 0]); k = came[k]; } path.reverse(); return path;
}
function nearestFree(x, y) { for (let r = 0; r < 6; r++) for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) if (!isSolid(x + dx, y + dy)) return [x + dx, y + dy]; return null; }
const floorTiles = []; for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (!isSolid(x, y)) floorTiles.push([x, y]);

// =====================================================================
// RENDER BASE
// =====================================================================
const canvas = $('gl');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' });
renderer.setPixelRatio(Math.min(devicePixelRatio, TOUCH ? 1 : 1.5));
renderer.setSize(innerWidth, innerHeight);
renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.15;
renderer.outputColorSpace = THREE.SRGBColorSpace;
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x010203);
scene.fog = new THREE.FogExp2(0x030606, 0.06);
const camera = new THREE.PerspectiveCamera(72, innerWidth / innerHeight, 0.05, 90);
scene.add(camera);
scene.add(new THREE.AmbientLight(0x1a2a2a, 0.35));
scene.add(new THREE.HemisphereLight(0x223333, 0x050302, 0.25));
addEventListener('resize', () => { renderer.setSize(innerWidth, innerHeight); camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); });

// =====================================================================
// TEXTURAS PROCEDURAIS (substituir por PBR reais na produção)
// =====================================================================
function makeTex(size, draw, opts = {}) {
  const c = document.createElement('canvas'); c.width = c.height = size; const g = c.getContext('2d'); draw(g, size);
  const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = 4;
  if (opts.repeat) { t.wrapS = t.wrapT = THREE.RepeatWrapping; t.repeat.set(opts.repeat, opts.repeat); }
  return t;
}
function grainy(g, s, amt) { const img = g.getImageData(0, 0, s, s), d = img.data; for (let i = 0; i < d.length; i += 4) { const n = (Math.random() - 0.5) * amt; d[i] += n; d[i + 1] += n; d[i + 2] += n; } g.putImageData(img, 0, 0); }
function blotch(g, s, n, col, rMin, rMax, yMin = 0, yMax = 1) {
  for (let i = 0; i < n; i++) { const x = Math.random() * s, y = lerp(yMin, yMax, Math.random()) * s, r = rand(rMin, rMax) * s;
    const gr = g.createRadialGradient(x, y, 0, x, y, r); gr.addColorStop(0, col); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(x - r, y - r, r * 2, r * 2); }
}
function drips(g, s, n, y0, col) { for (let i = 0; i < n; i++) { const x = Math.random() * s, len = rand(0.1, 0.5) * s, w = rand(1, 4); const gr = g.createLinearGradient(0, y0, 0, y0 + len); gr.addColorStop(0, col); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(x, y0, w, len); } }
function grime(g, s, from, a) { const gr = g.createLinearGradient(0, s * from, 0, s); gr.addColorStop(0, 'rgba(30,25,15,0)'); gr.addColorStop(1, `rgba(30,24,14,${a})`); g.fillStyle = gr; g.fillRect(0, 0, s, s); }

const TEX = {
  tile: makeTex(512, (g, s) => {
    // metade de cima: tinta verde descascando; metade de baixo: azulejo
    g.fillStyle = '#4f6658'; g.fillRect(0, 0, s, s * 0.47);
    for (let i = 0; i < 40; i++) { // tinta descascando: manchas irregulares de reboco
      const cx = rand(0, s), cy = rand(0, s * .45), r = rand(4, 26); g.fillStyle = `rgba(${118 + rand(0, 14)},${116 + rand(0, 10)},${96},${rand(.35, .6)})`; g.beginPath();
      for (let a = 0; a < 6.28; a += .5) { const rr = r * rand(.5, 1.3); g.lineTo(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr * .7); } g.closePath(); g.fill(); g.strokeStyle = 'rgba(25,30,25,.5)'; g.lineWidth = 1; g.stroke(); }
    g.fillStyle = '#2f3a33'; g.fillRect(0, s * 0.47, s, 10);
    const n = 10, ts = s / n, top = s * 0.47 + 10;
    for (let y = top; y < s; y += ts) for (let x = 0; x < s; x += ts) {
      const v = rand(-14, 10); g.fillStyle = Math.random() < 0.035 ? '#2c2a24' : `rgb(${172 + v},${186 + v},${168 + v})`; g.fillRect(x + 2, y + 2, ts - 4, ts - 4);
      if (Math.random() < 0.08) { g.strokeStyle = 'rgba(40,40,30,.6)'; g.beginPath(); g.moveTo(x + rand(0, ts), y); g.lineTo(x + rand(0, ts), y + ts); g.stroke(); }
    }
    g.fillStyle = 'rgba(60,62,55,.9)'; for (let y = top; y < s; y += ts) g.fillRect(0, y, s, 2); for (let x = 0; x < s; x += ts) g.fillRect(x, top, 2, s);
    drips(g, s, 30, s * 0.45, 'rgba(70,55,30,.55)'); grime(g, s, 0.55, 0.85);
    blotch(g, s, 6, 'rgba(90,60,30,.35)', .05, .14, .5, 1); blotch(g, s, 3, 'rgba(60,10,8,.35)', .03, .08, .6, 1);
    grainy(g, s, 26);
  }),
  padded: makeTex(512, (g, s) => {
    g.fillStyle = '#a9a28c'; g.fillRect(0, 0, s, s); const n = 6, c = s / n;
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) { const gr = g.createRadialGradient(x * c + c / 2, y * c + c / 2, 2, x * c + c / 2, y * c + c / 2, c * .7); gr.addColorStop(0, 'rgba(255,250,230,.25)'); gr.addColorStop(1, 'rgba(40,35,25,.45)'); g.fillStyle = gr; g.fillRect(x * c, y * c, c, c); g.fillStyle = '#3e3a2e'; g.beginPath(); g.arc(x * c, y * c, 4, 0, 7); g.fill(); }
    blotch(g, s, 10, 'rgba(90,70,30,.4)', .04, .16); blotch(g, s, 4, 'rgba(70,15,10,.4)', .02, .07, .3, .9);
    g.strokeStyle = 'rgba(40,30,20,.7)'; g.lineWidth = 2; for (let i = 0; i < 40; i++) { const x = rand(0, s), y = rand(s * .2, s * .8); g.beginPath(); g.moveTo(x, y); g.lineTo(x + rand(-6, 6), y + rand(10, 40)); g.stroke(); } // marcas de unha
    grime(g, s, .6, .7); grainy(g, s, 20);
  }),
  drawers: makeTex(512, (g, s) => {
    g.fillStyle = '#5f6a66'; g.fillRect(0, 0, s, s * .2);
    g.fillStyle = '#1d2122'; g.fillRect(0, s * .2, s, s * .8);
    const cols = 3, rows = 3, w = s / cols, h = (s * .8) / rows;
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
      const x = c * w + 8, y = s * .2 + r * h + 8, v = rand(-10, 10);
      g.fillStyle = `rgb(${128 + v},${138 + v},${140 + v})`; g.fillRect(x, y, w - 16, h - 16);
      for (let i = 0; i < 60; i++) { g.fillStyle = `rgba(255,255,255,${rand(0, .06)})`; g.fillRect(x, y + rand(0, h - 16), w - 16, 1); }
      g.fillStyle = '#2b2f30'; g.fillRect(x + w * .3, y + h * .55, w * .3, 10);
      g.fillStyle = '#d8d2bd'; g.fillRect(x + w * .35, y + h * .2, w * .2, h * .16); g.fillStyle = '#222'; g.font = '14px monospace'; g.fillText(String(r * 3 + c + 1), x + w * .4, y + h * .32);
      blotch(g, s, 1, 'rgba(90,50,20,.35)', .03, .06);
    }
    grime(g, s, .7, .6); grainy(g, s, 18);
  }),
  concrete: makeTex(512, (g, s) => {
    g.fillStyle = '#56564f'; g.fillRect(0, 0, s, s); grainy(g, s, 50);
    blotch(g, s, 18, 'rgba(20,24,20,.35)', .05, .2); blotch(g, s, 8, 'rgba(70,50,25,.35)', .04, .12);
    g.strokeStyle = 'rgba(20,20,18,.7)'; for (let i = 0; i < 6; i++) { g.beginPath(); let x = rand(0, s), y = rand(0, s); g.moveTo(x, y); for (let k = 0; k < 8; k++) { x += rand(-25, 25); y += rand(5, 30); g.lineTo(x, y); } g.stroke(); }
    g.fillStyle = 'rgba(15,20,18,.5)'; g.fillRect(0, s * .82, s, s * .18); drips(g, s, 25, 0, 'rgba(25,30,25,.5)');
  }),
  paint: makeTex(512, (g, s) => {
    g.fillStyle = '#857a5c'; g.fillRect(0, 0, s, s * .62);
    for (let i = 0; i < 18; i++) { g.fillStyle = 'rgba(160,150,125,.8)'; g.beginPath(); g.ellipse(rand(0, s), rand(0, s * .6), rand(8, 50), rand(5, 30), rand(0, 3), 0, 7); g.fill(); }
    g.fillStyle = '#3f2b1c'; g.fillRect(0, s * .62, s, s * .38);
    for (let x = 0; x < s; x += 64) { g.fillStyle = 'rgba(0,0,0,.4)'; g.fillRect(x, s * .62, 3, s); }
    g.fillStyle = '#2a1c12'; g.fillRect(0, s * .62, s, 10); drips(g, s, 20, s * .5, 'rgba(50,40,20,.5)'); grime(g, s, .5, .7); grainy(g, s, 24);
  }),
  lino: makeTex(512, (g, s) => {
    const n = 4, c = s / n; for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) { g.fillStyle = (x + y) % 2 ? '#3c463e' : '#8b8d78'; g.fillRect(x * c, y * c, c, c); }
    blotch(g, s, 14, 'rgba(30,25,15,.45)', .08, .3); blotch(g, s, 3, 'rgba(70,12,8,.3)', .03, .1); grainy(g, s, 34);
  }, { }),
  ftile: makeTex(512, (g, s) => {
    const n = 8, c = s / n; g.fillStyle = '#4c4f48'; g.fillRect(0, 0, s, s);
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) { const v = rand(-12, 8); g.fillStyle = `rgb(${160 + v},${165 + v},${158 + v})`; g.fillRect(x * c + 2, y * c + 2, c - 4, c - 4); }
    blotch(g, s, 10, 'rgba(40,32,20,.5)', .06, .25); blotch(g, s, 4, 'rgba(80,15,10,.35)', .02, .09); grainy(g, s, 28);
    g.fillStyle = '#151515'; g.beginPath(); g.arc(s / 2, s / 2, 10, 0, 7); g.fill();  // ralo
  }),
  ceiling: makeTex(256, (g, s) => {
    g.fillStyle = '#6a6b60'; g.fillRect(0, 0, s, s); g.fillStyle = '#3a3b34'; g.fillRect(0, s / 2 - 2, s, 4); g.fillRect(s / 2 - 2, 0, 4, s);
    blotch(g, s, 5, 'rgba(90,70,30,.5)', .1, .3); if (Math.random() < 1) { g.fillStyle = '#0c0c0a'; g.fillRect(s * .55, s * .1, s * .4, s * .35); }
    grainy(g, s, 22);
  }),
  metal: makeTex(256, (g, s) => { g.fillStyle = '#8a9195'; g.fillRect(0, 0, s, s); for (let i = 0; i < 300; i++) { g.fillStyle = `rgba(255,255,255,${rand(0, .08)})`; g.fillRect(0, rand(0, s), s, 1); } blotch(g, s, 6, 'rgba(70,45,20,.45)', .05, .2); grainy(g, s, 18); }),
  locker: makeTex(256, (g, s) => {
    g.fillStyle = '#4b5b50'; g.fillRect(0, 0, s, s); g.fillStyle = '#1a1f1b'; for (let i = 0; i < 6; i++) g.fillRect(s * .3, s * .08 + i * 9, s * .4, 4);
    g.fillRect(s * .78, s * .45, 10, 34); g.strokeStyle = '#2a332d'; g.lineWidth = 4; g.strokeRect(6, 6, s - 12, s - 12);
    blotch(g, s, 8, 'rgba(110,55,20,.5)', .03, .12); grime(g, s, .6, .6); grainy(g, s, 22);
  }),
  crate: makeTex(256, (g, s) => { g.fillStyle = '#4e3a26'; g.fillRect(0, 0, s, s); for (let y = 0; y < s; y += 42) { g.fillStyle = 'rgba(0,0,0,.5)'; g.fillRect(0, y, s, 3); } g.fillStyle = 'rgba(0,0,0,.35)'; g.fillRect(0, 0, 18, s); g.fillRect(s - 18, 0, 18, s); grainy(g, s, 40); }),
  stain: makeTex(256, (g, s) => { g.clearRect(0, 0, s, s); for (let i = 0; i < 9; i++) { const x = s / 2 + rand(-60, 60), y = s / 2 + rand(-60, 60), r = rand(15, 70); const gr = g.createRadialGradient(x, y, 0, x, y, r); gr.addColorStop(0, 'rgba(45,8,5,.85)'); gr.addColorStop(.6, 'rgba(35,10,5,.5)'); gr.addColorStop(1, 'rgba(0,0,0,0)'); g.fillStyle = gr; g.fillRect(0, 0, s, s); } }),
  dirt: makeTex(256, (g, s) => { g.clearRect(0, 0, s, s); blotch(g, s, 12, 'rgba(25,20,12,.6)', .1, .35, .1, .9); }),
  paper: makeTex(128, (g, s) => { g.fillStyle = '#c9c0a6'; g.fillRect(0, 0, s, s); g.fillStyle = 'rgba(40,30,20,.7)'; for (let y = 18; y < s - 10; y += 9) g.fillRect(12, y, rand(50, 100), 2); blotch(g, s, 3, 'rgba(90,60,20,.4)', .1, .3); }),
  exitSign: makeTex(256, (g, s) => { g.fillStyle = '#1a0503'; g.fillRect(0, 0, s, s); g.fillStyle = '#ff3a20'; g.font = 'bold 72px monospace'; g.textAlign = 'center'; g.fillText('SAÍDA', s / 2, s / 2 + 24); }),
};
for (const k of ['lino', 'ftile', 'concrete']) TEX[k + 'F'] = TEX[k];

const std = (map, o = {}) => new THREE.MeshStandardMaterial({ map, roughness: 0.88, metalness: 0, bumpMap: map, bumpScale: 0.6, ...o });
const MAT = {
  tile: std(TEX.tile, { roughness: 0.6 }), padded: std(TEX.padded, { roughness: 1 }), drawers: std(TEX.drawers, { roughness: 0.45, metalness: 0.5 }),
  concrete: std(TEX.concrete, { roughness: 1 }), paint: std(TEX.paint),
  lino: std(TEX.lino, { roughness: 0.55 }), ftile: std(TEX.ftile, { roughness: 0.5 }), concreteF: std(TEX.concrete, { roughness: 0.95 }),
  ceiling: std(TEX.ceiling, { roughness: 1 }),
  metal: std(TEX.metal, { roughness: 0.4, metalness: 0.75 }), steel: new THREE.MeshStandardMaterial({ color: 0x9aa3a6, roughness: 0.3, metalness: 0.9 }),
  locker: std(TEX.locker, { roughness: 0.6, metalness: 0.4 }), crate: std(TEX.crate),
  mattress: new THREE.MeshStandardMaterial({ color: 0x8c8673, roughness: 1 }), sheet: new THREE.MeshStandardMaterial({ color: 0xb7b4a6, roughness: 1 }),
  bag: new THREE.MeshStandardMaterial({ color: 0x111312, roughness: 0.4 }), black: new THREE.MeshStandardMaterial({ color: 0x0b0b0b, roughness: 0.6 }),
  rust: new THREE.MeshStandardMaterial({ color: 0x5a3220, roughness: 0.9, metalness: 0.3 }),
  pipe: new THREE.MeshStandardMaterial({ color: 0x3c4038, roughness: 0.7, metalness: 0.5 }),
  paper: new THREE.MeshStandardMaterial({ map: TEX.paper, roughness: 1, emissive: 0x111008 }),
  stain: new THREE.MeshStandardMaterial({ map: TEX.stain, transparent: true, depthWrite: false, roughness: 0.3, polygonOffset: true, polygonOffsetFactor: -2 }),
  dirt: new THREE.MeshStandardMaterial({ map: TEX.dirt, transparent: true, depthWrite: false, roughness: 1, polygonOffset: true, polygonOffsetFactor: -1 }),
};

function box3(w, h, d, mat) { const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat); m.castShadow = m.receiveShadow = true; return m; }

// ----- CORPOS: lençol drapeado sobre a silhueta (cabeça, peito, quadril, pés) ou saco preto com zíper
const TEX_SHEET = makeTex(256, (g, s) => { g.fillStyle = '#c4c0b2'; g.fillRect(0, 0, s, s); for (let i = 0; i < 40; i++) { g.strokeStyle = `rgba(80,75,60,${rand(.05, .18)})`; g.lineWidth = rand(1, 4); g.beginPath(); const y = rand(0, s); g.moveTo(0, y); g.bezierCurveTo(s * .3, y + rand(-20, 20), s * .7, y + rand(-20, 20), s, y + rand(-10, 10)); g.stroke(); } blotch(g, s, 6, 'rgba(110,95,60,.35)', .05, .2); grainy(g, s, 14); });
const TEX_SHEET_BLOOD = makeTex(256, (g, s) => { g.drawImage(TEX_SHEET.image, 0, 0); blotch(g, s, 5, 'rgba(95,8,6,.75)', .04, .14, .15, .45); blotch(g, s, 3, 'rgba(70,6,4,.6)', .02, .06, .3, .7); });
const MAT_SHEET = new THREE.MeshStandardMaterial({ map: TEX_SHEET, roughness: .95, side: THREE.DoubleSide });
const MAT_SHEET_BLOOD = new THREE.MeshStandardMaterial({ map: TEX_SHEET_BLOOD, roughness: .9, side: THREE.DoubleSide });
const MAT_BAG = new THREE.MeshStandardMaterial({ color: 0x0c0d0d, roughness: .28, metalness: .1, side: THREE.DoubleSide });
const MAT_DEADSKIN = new THREE.MeshStandardMaterial({ color: 0x8b8f86, roughness: .6 });
function bodyHeight(x, z) { // x: -1 (pés) .. 1 (cabeça)
  const bumps = [[.8, 0, .1, .1, .2], [.62, 0, .05, .12, .1], [.32, 0, .22, .17, .19], [.02, 0, .18, .16, .15], [-.22, 0, .16, .19, .15],
    [-.5, .09, .25, .06, .1], [-.5, -.09, .25, .06, .1], [-.85, .1, .05, .05, .2], [-.85, -.1, .05, .05, .2], [.2, .21, .3, .04, .09], [.2, -.21, .3, .04, .09]];
  let h = 0; for (const [x0, z0, rx, rz, H] of bumps) h = Math.max(h, H * Math.exp(-(((x - x0) / rx) ** 2) - (((z - z0) / rz) ** 2))); return h;
}
function makeBody(kind = 'sheet', o = {}) {
  const g = new THREE.Group(); const bag = kind === 'bag'; const W2 = bag ? .34 : .52;
  const geo = new THREE.PlaneGeometry(2.0, W2 * 2, 70, 28); geo.rotateX(-Math.PI / 2); const p = geo.attributes.position;
  for (let i = 0; i < p.count; i++) { const x = p.getX(i), z = p.getZ(i), az = Math.abs(z); let y = Math.max(bodyHeight(x, z), .025);
    if (bag) { y *= Math.min(1, (W2 - az) / .06 + .15); y = Math.max(y, .01); } else { if (az > .3) y = Math.max(-.32, y - (az - .3) * 1.6); if (Math.abs(x) > .92) y -= (Math.abs(x) - .92) * 1.8; y += Math.sin(x * 23 + z * 7) * .004; }
    p.setY(i, y); }
  geo.computeVertexNormals(); const m = new THREE.Mesh(geo, bag ? MAT_BAG : o.blood ? MAT_SHEET_BLOOD : MAT_SHEET); m.castShadow = m.receiveShadow = true; g.add(m);
  if (bag) { const pts = []; for (let x = -.95; x <= .95; x += .05) pts.push(new THREE.Vector3(x, bodyHeight(x, 0) + .012, 0)); const zip = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 60, .006, 4), MAT.steel); g.add(zip);
    const pull = box3(.05, .012, .025, MAT.steel); pull.position.set(.55, bodyHeight(.55, 0) + .02, 0); g.add(pull); }
  if (o.arm) { // mão pálida escapando por baixo do lençol
    const arm = new THREE.Group(); arm.position.set(.18, .05, W2 - .02); arm.rotation.x = -.15; g.add(arm);
    const fa = new THREE.Mesh(new THREE.CylinderGeometry(.03, .035, .42, 7), MAT_DEADSKIN); fa.position.y = -.21; arm.add(fa);
    const hand = box3(.07, .1, .03, MAT_DEADSKIN); hand.position.y = -.47; arm.add(hand);
    for (let k = 0; k < 4; k++) { const f = new THREE.Mesh(new THREE.CylinderGeometry(.008, .007, .08, 5), MAT_DEADSKIN); f.position.set(-.026 + k * .017, -.56, rand(-.01, .01)); f.rotation.x = rand(-.3, .3); arm.add(f); }
  }
  return g;
}

// =====================================================================
// CONSTRUÇÃO DO CENÁRIO
// =====================================================================
const WALL_PRIORITY = ['tile', 'paint', 'concrete', 'drawers', 'padded'];
function wallMatFor(x, y) {
  const found = new Set();
  for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const nx = x + dx, ny = y + dy; if (inB(nx, ny) && !isWall(nx, ny)) { const z = zoneAt(nx, ny); if (z) found.add(z.wall); } }
  for (const w of WALL_PRIORITY) if (found.has(w)) return w; return null;
}
function buildLevel() {
  const walls = {}, floors = {}, ceils = [];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    if (isWall(x, y)) { const m = wallMatFor(x, y); if (m) (walls[m] ||= []).push([x, y]); }
    else { const z = zoneAt(x, y); const f = z ? z.floor : 'lino'; (floors[f] ||= []).push([x, y]); ceils.push([x, y]); }
  }
  const m4 = new THREE.Matrix4();
  const wallGeo = new THREE.BoxGeometry(T, WALL_H, T);
  for (const [k, list] of Object.entries(walls)) {
    const im = new THREE.InstancedMesh(wallGeo, MAT[k], list.length); im.castShadow = im.receiveShadow = true;
    list.forEach(([x, y], i) => im.setMatrixAt(i, m4.makeTranslation((x + .5) * T, WALL_H / 2, (y + .5) * T))); scene.add(im);
  }
  const fGeo = new THREE.PlaneGeometry(T, T); fGeo.rotateX(-Math.PI / 2);
  for (const [k, list] of Object.entries(floors)) {
    const mat = k === 'concrete' ? MAT.concreteF : MAT[k];
    const im = new THREE.InstancedMesh(fGeo, mat, list.length); im.receiveShadow = true;
    list.forEach(([x, y], i) => im.setMatrixAt(i, m4.makeTranslation((x + .5) * T, 0, (y + .5) * T))); scene.add(im);
  }
  const cGeo = new THREE.PlaneGeometry(T, T); cGeo.rotateX(Math.PI / 2);
  const ci = new THREE.InstancedMesh(cGeo, MAT.ceiling, ceils.length);
  ceils.forEach(([x, y], i) => ci.setMatrixAt(i, m4.makeTranslation((x + .5) * T, WALL_H, (y + .5) * T))); scene.add(ci);
  // decalques de sujeira / sangue
  const dGeo = new THREE.PlaneGeometry(1, 1); dGeo.rotateX(-Math.PI / 2);
  for (const [x, y] of floorTiles) {
    if (Math.random() < 0.35) { const d = new THREE.Mesh(dGeo, MAT.dirt); d.position.copy(tc(x, y, 0.005)); d.position.x += rand(-.6, .6); d.position.z += rand(-.6, .6); d.scale.setScalar(rand(1.2, 2.6)); d.rotation.y = rand(0, 6); scene.add(d); }
    const z = zoneAt(x, y);
    if (z && ['morgue', 'autopsy', 'cells', 'cellar', 'ward'].includes(z.id) && Math.random() < 0.12) { const d = new THREE.Mesh(dGeo, MAT.stain); d.position.copy(tc(x, y, 0.008)); d.scale.setScalar(rand(0.8, 2)); d.rotation.y = rand(0, 6); scene.add(d); }
    if (Math.random() < 0.05) { const p = new THREE.Mesh(new THREE.PlaneGeometry(.22, .3).rotateX(-Math.PI / 2), MAT.paper); p.position.copy(tc(x, y, 0.01)); p.position.x += rand(-1, 1); p.position.z += rand(-1, 1); p.rotation.y = rand(0, 6); scene.add(p); }
  }
  // canos no teto do subsolo e corredor
  for (let x = 1; x <= 42; x++) {
    const p = new THREE.Mesh(new THREE.CylinderGeometry(.09, .09, T, 8), MAT.pipe); p.rotation.z = Math.PI / 2; p.position.copy(tc(x, 25, WALL_H - .25)); p.position.z -= .8; scene.add(p);
    const p2 = p.clone(); p2.position.z += .25; p2.scale.set(.6, 1, .6); scene.add(p2);
  }
  for (let y = 5; y <= 24; y++) { const p = new THREE.Mesh(new THREE.CylinderGeometry(.05, .05, T, 6), MAT.rust); p.position.copy(tc(11, y, WALL_H - .15)); p.position.x += .9; p.rotation.x = Math.PI / 2; scene.add(p); }
  // fios pendurados
  for (let i = 0; i < 26; i++) { const [x, y] = pick(floorTiles); const len = rand(.4, 1.4); const w = new THREE.Mesh(new THREE.CylinderGeometry(.008, .008, len, 4), MAT.black); w.position.copy(tc(x, y, WALL_H - len / 2)); w.position.x += rand(-1, 1); w.position.z += rand(-1, 1); w.rotation.z = rand(-.2, .2); scene.add(w); }
}

// ----- props
const box = (w, h, d, mat) => { const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat); m.castShadow = m.receiveShadow = true; return m; };
const cyl = (r, h, mat, seg = 10) => { const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, h, seg), mat); m.castShadow = true; return m; };
function openSideYaw(x, y) { // yaw para encarar o lado aberto
  for (const [dx, dy] of [[0, 1], [1, 0], [0, -1], [-1, 0]]) if (!isSolid(x + dx, y + dy)) return [Math.atan2(dx, dy), dx, dy];
  return [0, 0, 1];
}
const interactables = [];
const hideSpots = [];
function buildProps() {
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const ch = grid[y][x]; const c = tc(x, y);
    if (ch === 'L') {
      const [yaw, dx, dy] = openSideYaw(x, y); const g = new THREE.Group();
      const body = box(.95, 2.05, .62, MAT.locker); body.position.y = 1.03; g.add(body);
      g.position.copy(c); g.position.x -= dx * (T / 2 - .34); g.position.z -= dy * (T / 2 - .34); g.rotation.y = yaw; scene.add(g);
      const out = c.clone(); const spot = { type: 'locker', pos: g.position.clone(), yaw, tile: [x, y], exit: [x + dx, y + dy] };
      spot.camPos = g.position.clone().add(new THREE.Vector3(dx * .12, 1.55, dy * .12)); hideSpots.push(spot);
      interactables.push({ type: 'locker', pos: g.position.clone().setY(1.2), spot, label: 'Esconder no armário' });
    } else if (ch === 'S') {
      const g = new THREE.Group();
      const top = box(2.0, .07, .8, MAT.metal); top.position.y = .82; g.add(top);
      const mat = box(1.9, .1, .72, MAT.mattress); mat.position.y = .9; g.add(mat);
      for (const sx of [-.9, .9]) for (const sz of [-.33, .33]) { const l = cyl(.025, .8, MAT.steel, 6); l.position.set(sx, .4, sz); g.add(l); const w = cyl(.06, .04, MAT.black); w.rotation.x = Math.PI / 2; w.position.set(sx, .06, sz); g.add(w); }
      const z = zoneAt(x, y); if (z && z.id === 'morgue' || Math.random() < .3) { const b = makeBody(Math.random() < .3 ? 'bag' : 'sheet', { arm: Math.random() < .35, blood: Math.random() < .4 }); b.position.y = .95; b.scale.set(.95, 1, .8); g.add(b); }
      const sheet = box(1.95, .9, .02, MAT_SHEET); sheet.position.set(0, .5, .41); g.add(sheet);  // lençol caído (cortina do esconderijo)
      g.position.copy(c); g.rotation.y = (x + y) % 2 ? 0 : rand(-.15, .15); scene.add(g);
      const spot = { type: 'under', pos: c.clone(), yaw: 0, tile: [x, y], exit: nearestFreeAround(x, y) };
      spot.camPos = c.clone().setY(.35); hideSpots.push(spot);
      interactables.push({ type: 'locker', pos: c.clone().setY(.6), spot, label: 'Esconder debaixo da maca' });
    } else if (ch === 'T') {
      const g = new THREE.Group(); const slab = box(2.1, .1, .85, MAT.steel); slab.position.y = .95; g.add(slab);
      const rim = box(2.1, .08, .05, MAT.steel); rim.position.set(0, 1.02, .42); g.add(rim); const rim2 = rim.clone(); rim2.position.z = -.42; g.add(rim2);
      const ped = cyl(.12, .9, MAT.steel); ped.position.y = .45; g.add(ped);
      const body = makeBody('sheet', { blood: true, arm: Math.random() < .5 }); body.position.y = 1.0; body.scale.set(.98, 1, .78); g.add(body);
      const stain = new THREE.Mesh(new THREE.PlaneGeometry(.8, .5).rotateX(-Math.PI / 2), MAT.stain); stain.position.set(.3, 1.011, 0); g.add(stain);
      const tray = box(.6, .03, .4, MAT.steel); tray.position.set(1.3, 1.1, .6); g.add(tray);
      for (let i = 0; i < 5; i++) { const t = box(.18, .01, .015, MAT.steel); t.position.set(1.15 + i * .07, 1.12, .6); g.add(t); }
      g.position.copy(c); scene.add(g);
    } else if (ch === 'K') {
      const [yaw, dx, dy] = openSideYaw(x, y); const z = zoneAt(x, y); const g = new THREE.Group();
      if (z && z.id === 'boiler') { const b = cyl(1.0, 2.4, MAT.rust, 16); b.rotation.z = Math.PI / 2; b.position.y = 1.2; g.add(b); g.position.copy(c); }
      else if (z && z.id === 'elec') { const b = box(2.0, 1.8, 1.2, MAT.pipe); b.position.y = .9; g.add(b); const lamp = new THREE.Mesh(new THREE.SphereGeometry(.05), new THREE.MeshBasicMaterial({ color: 0xff2000 })); lamp.position.set(.6, 1.5, .61); g.add(lamp); g.position.copy(c); g.rotation.y = yaw; }
      else { const b = box(2.1, 1.0, .8, MAT.metal); b.position.y = .5; g.add(b); for (let i = 0; i < 4; i++) { const j = cyl(.06, rand(.15, .3), new THREE.MeshStandardMaterial({ color: pick([0x4a5a30, 0x3a2a20, 0x7a7050]), roughness: .2, transparent: true, opacity: .8 })); j.position.set(rand(-.8, .8), 1.1, rand(-.2, .2)); g.add(j); } g.position.copy(c); g.position.x -= dx * .6; g.position.z -= dy * .6; g.rotation.y = yaw; }
      scene.add(g);
    } else if (ch === 'W') {
      const g = new THREE.Group(); const seat = box(.5, .05, .5, MAT.black); seat.position.y = .5; g.add(seat); const back = box(.5, .55, .05, MAT.black); back.position.set(0, .8, -.25); g.add(back);
      for (const s of [-.3, .3]) { const w = new THREE.Mesh(new THREE.TorusGeometry(.3, .02, 6, 18), MAT.steel); w.rotation.y = Math.PI / 2; w.position.set(s, .32, 0); g.add(w); }
      g.position.copy(c); g.rotation.y = rand(0, 6); g.rotation.z = Math.random() < .4 ? 1.3 : 0; if (g.rotation.z) g.position.y = .3; scene.add(g);
    } else if (ch === 'c') {
      const s = rand(1.1, 1.6); const b = box(s, s * .8, s, MAT.crate); b.position.copy(c).setY(s * .4); b.rotation.y = rand(-.3, .3); scene.add(b);
      if (Math.random() < .5) { const s2 = s * .7; const b2 = box(s2, s2 * .8, s2, MAT.crate); b2.position.copy(c).setY(s * .8 + s2 * .4); b2.rotation.y = rand(0, 1); scene.add(b2); }
    }
  }
  // portas de cela entreabertas
  for (let i = 0; i < 7; i++) { const x = 14 + i * 4 + 1; const d = box(1.1, 2.2, .1, MAT.metal); d.position.copy(tc(x, 3, 1.1)); d.position.z += T / 2 - .1; d.position.x -= .55; d.rotation.y = rand(.6, 1.4); d.position.x += Math.cos(d.rotation.y) * .55 - .1; scene.add(d); }
}
function nearestFreeAround(x, y) { for (const [dx, dy] of [[0, 1], [0, -1], [1, 0], [-1, 0], [1, 1], [-1, -1], [1, -1], [-1, 1]]) if (!isSolid(x + dx, y + dy)) return [x + dx, y + dy]; return [x, y + 1]; }

// ----- porta principal + painel
let exitDoor, panel, panelSlots = [];
function buildExit() {
  const c = tc(5, 0); const g = new THREE.Group();
  const frame = box(2.3, 2.7, .3, MAT.pipe); frame.position.y = 1.35; g.add(frame);
  exitDoor = new THREE.Group(); for (const s of [-1, 1]) { const leaf = box(1.05, 2.5, .12, MAT.steel); leaf.position.set(s * .53, 1.25, .2); exitDoor.add(leaf); const bar = box(.8, .06, .06, MAT.black); bar.position.set(s * .5, 1.05, .28); exitDoor.add(bar); }
  g.add(exitDoor);
  const sign = new THREE.Mesh(new THREE.PlaneGeometry(.9, .3), new THREE.MeshBasicMaterial({ map: TEX.exitSign })); sign.position.set(0, 2.8, .17); g.add(sign);
  g.position.set(c.x, 0, c.z + T / 2); scene.add(g);
  const p = tc(7, 1); panel = new THREE.Group();
  const cab = box(1.1, 1.5, .35, MAT.pipe); cab.position.y = 1.35; panel.add(cab);
  for (let i = 0; i < 4; i++) { const slot = box(.16, .34, .05, MAT.black); slot.position.set(-.36 + i * .24, 1.4, .19); panel.add(slot); const lamp = new THREE.Mesh(new THREE.SphereGeometry(.035), new THREE.MeshBasicMaterial({ color: 0x550000 })); lamp.position.set(-.36 + i * .24, 1.75, .19); panel.add(lamp); const fuse = makeFuseMesh(); fuse.visible = false; fuse.rotation.z = 0; fuse.position.set(-.36 + i * .24, 1.4, .22); panel.add(fuse); panelSlots.push({ lamp, fuse }); }
  for (let i = 0; i < 3; i++) { const cable = cyl(.025, 1.3, MAT.black, 6); cable.position.set(-.3 + i * .3, 2.4, .05); panel.add(cable); }
  panel.position.set(p.x, 0, p.z - T / 2 + .2); scene.add(panel);
  interactables.push({ type: 'panel', pos: panel.position.clone().setY(1.3), label: 'Painel de energia' });
  interactables.push({ type: 'exit', pos: new THREE.Vector3(c.x, 1.3, c.z + T / 2 + .2), label: 'Porta principal' });
}
function makeFuseMesh() {
  const g = new THREE.Group();
  const glass = new THREE.Mesh(new THREE.CylinderGeometry(.035, .035, .16, 10), new THREE.MeshStandardMaterial({ color: 0xffd9a0, transparent: true, opacity: .45, roughness: .05, emissive: 0x5a2a00, emissiveIntensity: 1.2 }));
  g.add(glass); for (const s of [-1, 1]) { const cap = cyl(.04, .04, MAT.steel); cap.position.y = s * .09; g.add(cap); }
  const fil = new THREE.Mesh(new THREE.BoxGeometry(.006, .14, .006), new THREE.MeshBasicMaterial({ color: 0xffa040 })); g.add(fil);
  g.scale.setScalar(1.6); return g;
}
const glowTex = makeTex(64, (g, s) => { const gr = g.createRadialGradient(s / 2, s / 2, 0, s / 2, s / 2, s / 2); gr.addColorStop(0, 'rgba(255,170,60,1)'); gr.addColorStop(1, 'rgba(255,120,20,0)'); g.fillStyle = gr; g.fillRect(0, 0, s, s); });
const fuseObjs = [];
function buildItems() {
  for (const f of FUSES) {
    const g = new THREE.Group(); const m = makeFuseMesh(); m.rotation.z = Math.PI / 2; g.add(m);
    const glow = new THREE.Sprite(new THREE.SpriteMaterial({ map: glowTex, blending: THREE.AdditiveBlending, depthWrite: false, transparent: true, opacity: .6 })); glow.scale.setScalar(.5); g.add(glow);
    const base = tc(f.x, f.y); let h = 0.9;
    if (f.id === 2) { // gaveta aberta do necrotério
      const tray = box(1.4, .08, .8, MAT.steel); tray.position.set(base.x + T / 2 - .7, .95, base.z); scene.add(tray);
      const bag = makeBody('bag'); bag.scale.set(.68, .9, .9); bag.rotation.y = Math.PI; bag.position.set(base.x + T / 2 - .7, .99, base.z); scene.add(bag);
      g.position.set(base.x + T / 2 - 1.2, 1.05, base.z + .25);
    } else {
      const stand = box(.7, h, .5, MAT.metal); stand.position.copy(base).setY(h / 2); scene.add(stand); g.position.copy(base).setY(h + .06);
    }
    scene.add(g); f.obj = g; f.glow = glow; f.state = 'world'; f.home = g.position.clone();
    interactables.push({ type: 'fuse', pos: g.position.clone(), fuse: f, label: 'Pegar fusível 30A' });
  }
  for (const [x, y] of BATTERIES) {
    const b = new THREE.Group(); const body = cyl(.05, .18, new THREE.MeshStandardMaterial({ color: 0x2a3a6a, roughness: .5, metalness: .3 })); body.rotation.z = Math.PI / 2; b.add(body);
    const tip = cyl(.02, .02, MAT.steel); tip.rotation.z = Math.PI / 2; tip.position.x = .1; b.add(tip);
    b.position.copy(tc(x, y, .03)); b.position.x += rand(-.5, .5); scene.add(b);
    interactables.push({ type: 'battery', pos: b.position.clone(), obj: b, label: 'Pegar pilhas' });
  }
  NOTES.forEach((n, i) => {
    const p = new THREE.Mesh(new THREE.PlaneGeometry(.3, .4).rotateX(-Math.PI / 2), new THREE.MeshStandardMaterial({ map: TEX.paper, roughness: 1, emissive: 0x2a2618 }));
    p.position.copy(tc(n.x, n.y, .02)); p.rotation.y = rand(-.5, .5); scene.add(p); n.obj = p;
    interactables.push({ type: 'note', pos: p.position.clone(), note: n, idx: i, label: 'Ler documento' });
  });
}

// ----- luzes do prédio
const lightObjs = [];
function buildLights() {
  LIGHTS.forEach((L, i) => {
    const pl = new THREE.PointLight(L.color, L.int, L.r, 2); const p = new THREE.Vector3((L.x + .5) * T, WALL_H - .35, (L.y + .5) * T); pl.position.copy(p); scene.add(pl);
    const fix = new THREE.Mesh(L.type === 'emergency' ? new THREE.SphereGeometry(.12, 10, 8) : new THREE.BoxGeometry(1.1, .06, .22), new THREE.MeshBasicMaterial({ color: L.color }));
    fix.position.copy(p).setY(WALL_H - .05); if (L.type === 'emergency') fix.position.y = WALL_H - .3; scene.add(fix);
    if (i === 5) { const lamp = new THREE.Mesh(new THREE.CylinderGeometry(.35, .5, .25, 16, 1, true), MAT.steel); lamp.position.set(p.x, WALL_H - .7, p.z); scene.add(lamp); fix.position.y = WALL_H - .8; }
    lightObjs.push({ ...L, pl, fix, on: true, flick: 1, off: 0, idx: i });
  });
}
let lightmap = [...Array(H)].map(() => new Float32Array(W));
function computeLightmap() {
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let v = 0; const px = (x + .5) * T, pz = (y + .5) * T;
    for (const L of lightObjs) { if (!L.on) continue; const lx = (L.x + .5) * T, lz = (L.y + .5) * T, d = Math.hypot(px - lx, pz - lz); if (d > L.r) continue; if (!los(lx, lz, px, pz)) continue; v = Math.max(v, (1 - d / L.r) * (L.type === 'emergency' ? .3 : 1)); }
    lightmap[y][x] = v;
  }
}
const lightAt = (pos) => { const [x, y] = tileOf(pos); return inB(x, y) ? lightmap[y][x] : 0; };

// =====================================================================
// ÁUDIO PROCEDURAL (WebAudio). Placeholder fiel ao design sonoro final.
// =====================================================================
const AU = {
  ctx: null,
  init() {
    if (this.ctx) return; const ctx = this.ctx = new (window.AudioContext || window.webkitAudioContext)();
    this.master = ctx.createGain(); this.master.gain.value = .9;
    const comp = ctx.createDynamicsCompressor(); this.master.connect(comp); comp.connect(ctx.destination);
    // reverb de prédio (impulso gerado)
    this.verb = ctx.createConvolver(); const len = ctx.sampleRate * 2.8, ir = ctx.createBuffer(2, len, ctx.sampleRate);
    for (let c = 0; c < 2; c++) { const d = ir.getChannelData(c); for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 2.6); }
    this.verb.buffer = ir; this.verbIn = ctx.createGain(); this.verbIn.gain.value = .5; this.verbIn.connect(this.verb); this.verb.connect(this.master);
    this.noise = ctx.createBuffer(1, ctx.sampleRate * 2, ctx.sampleRate); { const d = this.noise.getChannelData(0); for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1; }
    this.brown = ctx.createBuffer(1, ctx.sampleRate * 4, ctx.sampleRate); { const d = this.brown.getChannelData(0); let l = 0; for (let i = 0; i < d.length; i++) { l = (l + .02 * (Math.random() * 2 - 1)) / 1.02; d[i] = l * 3.5; } }
    // ambiência: ronco do prédio + drone dissonante
    const amb = this.loop(this.brown); const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 180; this.ambGain = ctx.createGain(); this.ambGain.gain.value = .35; amb.connect(lp).connect(this.ambGain).connect(this.master);
    this.drone = ctx.createGain(); this.drone.gain.value = .035; const dlp = ctx.createBiquadFilter(); dlp.type = 'lowpass'; dlp.frequency.value = 160; this.drone.connect(dlp).connect(this.master); dlp.connect(this.verbIn);
    for (const f of [41.2, 41.9, 61.7, 87.3]) { const o = ctx.createOscillator(); o.type = 'sawtooth'; o.frequency.value = f; o.connect(this.drone); o.start(); }
    const lfo = ctx.createOscillator(); lfo.frequency.value = .05; const lg = ctx.createGain(); lg.gain.value = 70; lfo.connect(lg).connect(dlp.frequency); lfo.start();
    // zumbido de lâmpada (volume segue a lâmpada acesa mais próxima)
    this.hum = ctx.createGain(); this.hum.gain.value = 0; const hb = ctx.createBiquadFilter(); hb.type = 'bandpass'; hb.frequency.value = 240; hb.Q.value = 3; this.hum.connect(this.master);
    for (const [f, t] of [[120, 'square'], [240, 'sawtooth'], [60, 'sine']]) { const o = ctx.createOscillator(); o.type = t; o.frequency.value = f; const g = ctx.createGain(); g.gain.value = t === 'sine' ? .5 : .12; o.connect(g).connect(hb); o.start(); } hb.connect(this.hum);
    // voz da criatura: respiração + rosnado, espacializada e com oclusão
    this.cPan = ctx.createPanner(); Object.assign(this.cPan, { panningModel: 'HRTF', distanceModel: 'inverse', refDistance: 2, rolloffFactor: 1.3, maxDistance: 60 });
    this.cOcc = ctx.createBiquadFilter(); this.cOcc.type = 'lowpass'; this.cOcc.frequency.value = 8000; this.cOcc.connect(this.cPan); this.cPan.connect(this.master);
    this.cSend = ctx.createGain(); this.cSend.gain.value = .25; this.cOcc.connect(this.cSend).connect(this.verbIn);
    const br = this.loop(this.noise); const bf = ctx.createBiquadFilter(); bf.type = 'bandpass'; bf.frequency.value = 380; bf.Q.value = 1.3; const bf2 = ctx.createBiquadFilter(); bf2.type = 'bandpass'; bf2.frequency.value = 1150; bf2.Q.value = 4;
    this.breath = ctx.createGain(); this.breath.gain.value = 0; const rat = ctx.createGain(); rat.gain.value = .55; const ro = ctx.createOscillator(); ro.type = 'square'; ro.frequency.value = 23; const rg = ctx.createGain(); rg.gain.value = .45; ro.connect(rg).connect(rat.gain); ro.start(); // chocalho na garganta
    br.connect(bf).connect(rat); br.connect(bf2).connect(rat); rat.connect(this.breath).connect(this.cOcc);
    this.growl = ctx.createGain(); this.growl.gain.value = 0; const ws = ctx.createWaveShaper(); const curve = new Float32Array(256); for (let i = 0; i < 256; i++) { const x = i / 128 - 1; curve[i] = Math.tanh(x * 6); } ws.curve = curve;
    const go = ctx.createOscillator(); go.type = 'sawtooth'; go.frequency.value = 58; this.growlOsc = go; const go2 = ctx.createOscillator(); go2.type = 'square'; go2.frequency.value = 61.5; const glp = ctx.createBiquadFilter(); glp.type = 'lowpass'; glp.frequency.value = 700;
    go.connect(ws); go2.connect(ws); ws.connect(glp).connect(this.growl).connect(this.cOcc); go.start(); go2.start();
    // batimento cardíaco (seco, sem reverb, "dentro da cabeça")
    this.heartBus = ctx.createGain(); this.heartBus.gain.value = .9; this.heartBus.connect(this.master);
    // alarme final
    this.loadSamples();
    this.alarm = ctx.createGain(); this.alarm.gain.value = 0; this.alarm.connect(this.master); this.alarm.connect(this.verbIn);
    const ao = ctx.createOscillator(); ao.type = 'square'; ao.frequency.value = 620; const am = ctx.createOscillator(); am.frequency.value = 1.6; const amg = ctx.createGain(); amg.gain.value = 180; am.connect(amg).connect(ao.frequency); const alp = ctx.createBiquadFilter(); alp.type = 'lowpass'; alp.frequency.value = 1800; ao.connect(alp).connect(this.alarm); ao.start(); am.start();
  },
  loop(buf) { const s = this.ctx.createBufferSource(); s.buffer = buf; s.loop = true; s.start(); return s; },
  env(node, t, a, peak, d) { node.gain.setValueAtTime(0, t); node.gain.linearRampToValueAtTime(peak, t + a); node.gain.exponentialRampToValueAtTime(.0001, t + a + d); },
  burst({ f = 1000, q = 1, dur = .1, vol = .3, type = 'bandpass', at = null, verb = .2, rate = 1 }) {
    if (!this.ctx) return; const ctx = this.ctx, t = ctx.currentTime; const s = ctx.createBufferSource(); s.buffer = this.noise; s.playbackRate.value = rate;
    const fl = ctx.createBiquadFilter(); fl.type = type; fl.frequency.value = f; fl.Q.value = q; const g = ctx.createGain(); this.env(g, t, .004, vol, dur);
    s.connect(fl).connect(g); const out = at ? this.spatial(at) : this.master; g.connect(out); if (verb) { const v = ctx.createGain(); v.gain.value = verb; g.connect(v).connect(this.verbIn); }
    s.start(t, Math.random()); s.stop(t + dur + .1);
  },
  tone({ f = 440, f2 = null, dur = .3, vol = .2, type = 'sine', at = null, verb = .3, a = .005 }) {
    if (!this.ctx) return; const ctx = this.ctx, t = ctx.currentTime; const o = ctx.createOscillator(); o.type = type; o.frequency.setValueAtTime(f, t); if (f2) o.frequency.exponentialRampToValueAtTime(f2, t + dur);
    const g = ctx.createGain(); this.env(g, t, a, vol, dur); o.connect(g); g.connect(at ? this.spatial(at) : this.master); if (verb) { const v = ctx.createGain(); v.gain.value = verb; g.connect(v).connect(this.verbIn); } o.start(t); o.stop(t + a + dur + .1);
  },
  spatial(p) { const pn = this.ctx.createPanner(); Object.assign(pn, { panningModel: 'HRTF', distanceModel: 'inverse', refDistance: 2, rolloffFactor: 1.1, maxDistance: 80 }); pn.positionX.value = p.x; pn.positionY.value = p.y; pn.positionZ.value = p.z; pn.connect(this.master); return pn; },
  // passos: gravações reais (CC0) com variação de tom, filtro por piso e eco do prédio. 'at' = passo vindo de outro lugar
  samples: { stone: [], grit: [] }, foot: 0,
  loadSamples() {
    const load = (set, names) => names.forEach(n => fetch(`sounds/steps/${n}.ogg`).then(r => r.arrayBuffer()).then(b => this.ctx.decodeAudioData(b)).then(buf => this.samples[set].push({ n, buf })).catch(() => { }));
    load('stone', ['stonel1', 'stoner1', 'stonel2', 'stoner2', 'stonel3', 'stoner3']); load('grit', ['sandl1', 'sandr1', 'sandl2', 'sandr2', 'sandl3', 'sandr3']);
  },
  step(surface, loud, at = null) {
    if (!this.ctx) return; const set = this.samples[surface === 'concrete' ? 'grit' : 'stone'];
    if (!set.length) { this.burst({ f: rand(900, 1400), q: 2, dur: .08, vol: .1 * loud, verb: .2, at }); return; }
    this.foot ^= 1; const side = set.filter((s, i) => i % 2 === this.foot); let sm = pick(side.length ? side : set); if (sm === this.lastStep && side.length > 1) sm = pick(side); this.lastStep = sm;
    const ctx = this.ctx, t = ctx.currentTime, src = ctx.createBufferSource(); src.buffer = sm.buf; src.playbackRate.value = rand(.84, .97); // mais grave = mais pesado
    const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = P.crouch ? 1300 : surface === 'lino' ? 3200 : surface === 'ftile' ? 9000 : 5000;
    const pk = ctx.createBiquadFilter(); pk.type = 'peaking'; pk.frequency.value = 180; pk.Q.value = 1; pk.gain.value = surface === 'concrete' ? 3 : 5; // corpo no grave
    const g = ctx.createGain(); g.gain.value = Math.min(1, .62 * loud) * rand(.85, 1);
    src.connect(pk).connect(f).connect(g); g.connect(at ? this.spatial(at) : this.master);
    const v = ctx.createGain(); v.gain.value = (surface === 'concrete' ? .45 : .32) * Math.min(1, loud + .2); g.connect(v).connect(this.verbIn); // eco do corredor
    src.start(t);
    if (Math.random() < .06 && loud > .3) this.creak(at, .045 * loud);
  },
  creak(at, vol = .08) { // rangido: dente-de-serra instável em filtro estreito
    if (!this.ctx) return; const ctx = this.ctx, t = ctx.currentTime, d = rand(.25, .7), o = ctx.createOscillator(); o.type = 'sawtooth'; const f0 = rand(80, 170); o.frequency.setValueAtTime(f0, t); o.frequency.linearRampToValueAtTime(f0 * rand(.65, 1.5), t + d);
    const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = rand(600, 1500); bp.Q.value = 9; const g = ctx.createGain(); this.env(g, t, .04, vol, d); o.connect(bp).connect(g); g.connect(at ? this.spatial(at) : this.master);
    const v = ctx.createGain(); v.gain.value = .5; g.connect(v).connect(this.verbIn); o.start(t); o.stop(t + d + .3);
  },
  cstep(p, heavy) { // passo da criatura: baque + articulação estalando
    this.tone({ f: 55, f2: 28, dur: .22, vol: .5 * heavy, at: p, verb: .5 }); this.burst({ f: rand(1800, 2700), q: 5, dur: .035, vol: .4 * heavy, at: p, verb: .3 });
    if (Math.random() < .55) setTimeout(() => this.burst({ f: rand(2600, 3600), q: 4, dur: .03, vol: .3 * heavy, at: p, verb: .3 }), rand(30, 90));
  },
  scrape(p, d = .7) { // unhas no azulejo
    if (!this.ctx) return; const ctx = this.ctx, t = ctx.currentTime, s = ctx.createBufferSource(); s.buffer = this.noise; const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.Q.value = 14;
    bp.frequency.setValueAtTime(rand(2400, 3400), t); bp.frequency.linearRampToValueAtTime(rand(4200, 6500), t + d); const g = ctx.createGain(); this.env(g, t, .06, .4, d);
    s.connect(bp).connect(g).connect(this.spatial(p)); s.start(t, Math.random()); s.stop(t + d + .2);
  },
  whisper(p = null, vol = .2) { // sussurros: ruído em formantes de voz, em "sílabas"
    if (!this.ctx) return; const ctx = this.ctx; let t = ctx.currentTime; const n = 3 + (Math.random() * 5 | 0);
    for (let i = 0; i < n; i++) { const d = rand(.08, .24), s = ctx.createBufferSource(); s.buffer = this.noise; const out = ctx.createGain(); out.gain.setValueAtTime(0, t); out.gain.linearRampToValueAtTime(vol, t + .02); out.gain.exponentialRampToValueAtTime(.0001, t + d);
      for (const [f, q] of [[rand(450, 900), 6], [rand(1100, 2200), 8], [rand(2400, 3100), 10]]) { const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = f; bp.Q.value = q; s.connect(bp).connect(out); }
      out.connect(p ? this.spatial(p) : this.master); const v = ctx.createGain(); v.gain.value = .5; out.connect(v).connect(this.verbIn); s.start(t, Math.random()); s.stop(t + d + .1); t += d + rand(.03, .14); }
  },
  moan(p) { // gemido distante com vibrato
    if (!this.ctx) return; const ctx = this.ctx, t = ctx.currentTime, d = rand(2, 4), o = ctx.createOscillator(); o.type = 'sawtooth'; const f = rand(130, 250); o.frequency.setValueAtTime(f, t); o.frequency.linearRampToValueAtTime(f * rand(.55, .85), t + d);
    const vib = ctx.createOscillator(); vib.frequency.value = rand(4, 6.5); const vg = ctx.createGain(); vg.gain.value = f * .035; vib.connect(vg).connect(o.frequency);
    const out = ctx.createGain(); out.gain.setValueAtTime(0, t); out.gain.linearRampToValueAtTime(.16, t + d * .3); out.gain.linearRampToValueAtTime(0, t + d);
    for (const [ff, q] of [[700, 5], [1150, 7]]) { const bp = ctx.createBiquadFilter(); bp.type = 'bandpass'; bp.frequency.value = ff; bp.Q.value = q; o.connect(bp).connect(out); }
    out.connect(this.spatial(p)); out.connect(this.verbIn); o.start(t); vib.start(t); o.stop(t + d + .1); vib.stop(t + d + .1);
  },
  radio(p) { this.burst({ f: 2500, q: .4, dur: rand(.4, 1), vol: .18, at: p, type: 'highpass', verb: .2 }); this.tone({ f: 50, dur: .5, vol: .06, type: 'square', at: p, verb: .1 }); if (Math.random() < .6) setTimeout(() => this.whisper(p, .3), 250); },
  slam(p, vol = 1) { this.tone({ f: 95, f2: 38, dur: .5, vol: .6 * vol, at: p, verb: .7 }); this.burst({ f: 420, q: 2, dur: .35, vol: .5 * vol, at: p, verb: .7 }); this.tone({ f: rand(1200, 1700), dur: 1.4, vol: .06 * vol, type: 'triangle', at: p, verb: .9 }); },
  crash(p) { for (let i = 0; i < 6; i++) setTimeout(() => { this.burst({ f: rand(300, 3000), q: rand(1, 6), dur: rand(.1, .4), vol: .35, at: p, verb: .6 }); }, i * rand(30, 90)); this.tone({ f: 70, f2: 30, dur: .6, vol: .6, at: p, verb: .7 }); for (let i = 0; i < 4; i++) setTimeout(() => this.tone({ f: rand(2500, 5000), dur: .15, vol: .05, type: 'triangle', at: p, verb: .8 }), 150 + i * rand(40, 120)); },
  ring() { for (let i = 0; i < 2; i++) setTimeout(() => { this.tone({ f: 440, dur: .8, vol: .07, type: 'square', verb: .4, a: .01 }); this.tone({ f: 480, dur: .8, vol: .07, type: 'square', verb: .4, a: .01 }); }, i * 1100); },
  tom(vol = .5) { this.tone({ f: 95, f2: 45, dur: .3, vol, verb: .3 }); this.burst({ f: 1500, q: .8, dur: .06, vol: vol * .4, type: 'lowpass', verb: .2 }); },
  jumpscare() { // grito sem espacialização, distorcido, na cara
    this.tone({ f: 1700, f2: 300, dur: 1.2, vol: .5, type: 'sawtooth', verb: .4, a: .005 }); this.tone({ f: 2300, f2: 500, dur: 1.0, vol: .35, type: 'square', verb: .4, a: .005 });
    this.tone({ f: 61, f2: 30, dur: .9, vol: .9, verb: .3 }); this.burst({ f: 3000, q: .5, dur: 1.0, vol: .6, verb: .4 }); this.burst({ f: 200, q: .6, dur: .5, vol: .7, type: 'lowpass', verb: .5 });
  },
  heartbeat(vol) { if (!this.ctx) return; for (const [d, v] of [[0, 1], [.16, .6]]) setTimeout(() => { const ctx = this.ctx, t = ctx.currentTime, o = ctx.createOscillator(); o.frequency.setValueAtTime(62, t); o.frequency.exponentialRampToValueAtTime(38, t + .12); const g = ctx.createGain(); this.env(g, t, .01, vol * v, .16); o.connect(g).connect(this.heartBus); o.start(t); o.stop(t + .3); }, d * 1000); },
  scream(p) { this.tone({ f: 1400, f2: 260, dur: 1.4, vol: .35, type: 'sawtooth', at: p, verb: .6, a: .02 }); this.tone({ f: 1950, f2: 400, dur: 1.2, vol: .2, type: 'square', at: p, verb: .5, a: .02 }); this.burst({ f: 2600, q: .8, dur: 1.2, vol: .35, at: p, verb: .5 }); },
  sizzle(p) { this.burst({ f: 5000, q: .5, dur: .25, vol: .12, at: p, type: 'highpass', verb: 0 }); },
  stinger() { // cordas guinchando + pancada grave
    for (const f of [1046, 1108, 1480, 1568, 2217]) this.tone({ f, f2: f * rand(.97, 1.03), dur: 1.8, vol: .045, type: 'sawtooth', verb: .7, a: .008 });
    for (const f of [46, 49, 69]) this.tone({ f, f2: f * .9, dur: 2.2, vol: .14, type: 'sawtooth', verb: .6, a: .01 }); this.burst({ f: 300, q: .7, dur: 1.2, vol: .45, type: 'lowpass', verb: .8 });
  },
  bang(p) { this.burst({ f: rand(180, 420), q: 9, dur: 1.4, vol: .5, at: p, verb: 1 }); this.tone({ f: rand(90, 140), f2: 60, dur: 1.2, vol: .25, at: p, verb: 1 }); },
  drip(p) { this.tone({ f: rand(1400, 2400), f2: 900, dur: .08, vol: .06, at: p, verb: .9 }); },
  click() { this.burst({ f: 3000, q: 3, dur: .03, vol: .2, verb: 0 }); },
  zap(p) { this.tone({ f: 60, dur: .7, vol: .25, type: 'square', at: p, verb: .4 }); for (let i = 0; i < 6; i++) setTimeout(() => this.burst({ f: 4000, q: .5, dur: .04, vol: .3, at: p, verb: .2, type: 'highpass' }), i * rand(40, 110)); },
  clunk(p) { this.tone({ f: 80, f2: 40, dur: .4, vol: .5, at: p, verb: .6 }); this.tone({ f: 1250, dur: .9, vol: .07, type: 'triangle', at: p, verb: .8 }); },
  pickup() { this.tone({ f: 600, f2: 900, dur: .12, vol: .08, verb: .2 }); },
  doorOpen(p) { this.tone({ f: 50, f2: 30, dur: 3, vol: .5, type: 'sawtooth', at: p, verb: 1, a: .3 }); this.burst({ f: 900, q: 6, dur: 3, vol: .3, at: p, verb: 1 }); },
  setListener(cam) { if (!this.ctx) return; const L = this.ctx.listener, p = cam.getWorldPosition(new THREE.Vector3()), f = cam.getWorldDirection(new THREE.Vector3());
    if (L.positionX) { L.positionX.value = p.x; L.positionY.value = p.y; L.positionZ.value = p.z; L.forwardX.value = f.x; L.forwardY.value = f.y; L.forwardZ.value = f.z; L.upX.value = 0; L.upY.value = 1; L.upZ.value = 0; } else { L.setPosition(p.x, p.y, p.z); L.setOrientation(f.x, f.y, f.z, 0, 1, 0); } },
};

// =====================================================================
// JOGADOR + LANTERNA
// =====================================================================
const P = {
  pos: tc(...PLAYER_START, 0), yaw: 0, pitch: 0, crouch: false, eye: CFG.player.eye, stamina: CFG.player.staminaMax, exhausted: false,
  battery: CFG.lamp.battery, lampOn: true, focus: false, heat: 0, overheat: 0, hidden: null, breath: CFG.player.breathMax, holding: false,
  stepAcc: 0, bob: 0, carried: [], zone: null, moving: 0, sprinting: false, lastEnterHide: -99,
};
const flash = new THREE.SpotLight(0xfff1d6, 40, CFG.lamp.range + 4, .48, .55, 1.6);
flash.castShadow = true; flash.shadow.mapSize.set(TOUCH ? 512 : 1024, TOUCH ? 512 : 1024); flash.shadow.camera.near = .2; flash.shadow.camera.far = 26; flash.shadow.bias = -.0004;
flash.position.set(.22, -.18, 0); camera.add(flash); camera.add(flash.target); flash.target.position.set(.05, -.05, -6);
const keys = {}; let mouseR = false;

// =====================================================================
// CRIATURA — "Paciente 17 / O Sem-Pálpebras"
// =====================================================================
function buildCreature() {
  const skin = new THREE.MeshStandardMaterial({ color: 0x9c9384, roughness: .62, metalness: 0, emissive: 0x000000 });
  const sinew = new THREE.MeshStandardMaterial({ color: 0x5b3b33, roughness: .6 });
  const voidM = new THREE.MeshBasicMaterial({ color: 0x000000 });
  const gum = new THREE.MeshBasicMaterial({ color: 0x2a0303 });
  const limb = (len, r0, r1, mat = skin, up = false) => { const g = new THREE.CylinderGeometry(r1, r0, len, 7); g.translate(0, up ? len / 2 : -len / 2, 0); const m = new THREE.Mesh(g, mat); m.castShadow = true; return m; };
  const pivot = (parent, x, y, z) => { const p = new THREE.Group(); p.position.set(x, y, z); parent.add(p); return p; };
  const root = new THREE.Group();
  const hips = pivot(root, 0, 1.22, 0);
  const pelvis = new THREE.Mesh(new THREE.SphereGeometry(.15, 10, 8), skin); pelvis.scale.set(1.4, .8, 1); hips.add(pelvis);
  const spine = pivot(hips, 0, .05, 0); spine.rotation.x = .6;
  const torso = limb(1.0, .1, .17, skin, true); spine.add(torso);
  for (let i = 0; i < 6; i++) { const rib = new THREE.Mesh(new THREE.TorusGeometry(.15 - i * .004, .012, 4, 12, Math.PI), sinew); rib.position.set(0, .35 + i * .1, .02); rib.rotation.y = Math.PI / 2; rib.rotation.z = Math.PI / 2; rib.scale.set(1, 1, 1.1); spine.add(rib); }
  const vert = limb(1.0, .025, .025, sinew, true); vert.position.z = -.14; spine.add(vert);
  const chest = pivot(spine, 0, 1.0, 0);
  const neck = pivot(chest, 0, 0, .02); neck.rotation.x = -1.05;
  const neckM = limb(.42, .045, .035, sinew, true); neck.add(neckM);
  const head = pivot(neck, 0, .42, 0);
  const skull = new THREE.Mesh(new THREE.SphereGeometry(.16, 14, 12), skin); skull.scale.set(.9, 1.45, 1.05); skull.position.y = .14; head.add(skull);
  const face = pivot(head, 0, .12, .12); // olhos sem pálpebras: órbitas negras com íris branca minúscula
  const eyes = [];
  for (const s of [-1, 1]) { const sock = new THREE.Mesh(new THREE.SphereGeometry(.045, 10, 8), voidM); sock.position.set(s * .06, .05, .03); sock.scale.set(1.25, 1.45, .5); face.add(sock); const eye = new THREE.Mesh(new THREE.SphereGeometry(.021, 10, 8), new THREE.MeshStandardMaterial({ color: 0xb9b39c, roughness: .2 })); eye.position.set(s * .06, .05, .035); face.add(eye); const pupil = new THREE.Mesh(new THREE.SphereGeometry(.006, 6, 6), voidM); pupil.position.set(s * .06, .05, .057); face.add(pupil); eyes.push(eye); }
  const jaw = pivot(head, 0, -.02, .05); jaw.rotation.x = .5;
  const jawM = new THREE.Mesh(new THREE.BoxGeometry(.13, .28, .07), skin); jawM.position.set(0, -.13, .03); jaw.add(jawM);
  const mouth = new THREE.Mesh(new THREE.PlaneGeometry(.11, .26), gum); mouth.position.set(0, -.12, .07); jaw.add(mouth);
  for (let i = 0; i < 7; i++) { const tooth = new THREE.Mesh(new THREE.ConeGeometry(.008, .04, 4), new THREE.MeshStandardMaterial({ color: 0xcfc6a0 })); tooth.position.set(-.045 + i * .015, -.02, .075); tooth.rotation.x = Math.PI; jaw.add(tooth); }
  const arms = [], legs = [];
  for (const s of [-1, 1]) {
    const sh = pivot(chest, s * .2, -.05, 0); const up = limb(.9, .05, .035); sh.add(up);
    const el = pivot(sh, 0, -.9, 0); const fo = limb(.95, .035, .025); el.add(fo);
    const hand = pivot(el, 0, -.95, 0); const fingers = [];
    for (let k = 0; k < 4; k++) { const f = pivot(hand, (k - 1.5) * .025, 0, 0); f.rotation.z = (k - 1.5) * .18; f.add(limb(.42, .012, .005, sinew)); fingers.push(f); }
    arms.push({ sh, el, hand, fingers, s });
    const hp = pivot(hips, s * .13, 0, 0); const th = limb(.72, .06, .04); hp.add(th);
    const kn = pivot(hp, 0, -.72, 0); const sh2 = limb(.74, .04, .025); kn.add(sh2);
    const ft = pivot(kn, 0, -.74, 0); const foot = new THREE.Mesh(new THREE.BoxGeometry(.07, .03, .26), skin); foot.position.z = .1; ft.add(foot);
    legs.push({ hp, kn, ft, s });
  }
  // fumaça de queimadura
  const smokeGeo = new THREE.BufferGeometry(); const sp = new Float32Array(40 * 3); smokeGeo.setAttribute('position', new THREE.BufferAttribute(sp, 3));
  const smoke = new THREE.Points(smokeGeo, new THREE.PointsMaterial({ color: 0x9a9a90, size: .18, transparent: true, opacity: 0, depthWrite: false }));
  scene.add(smoke);
  scene.add(root);
  return { root, hips, spine, chest, neck, head, face, jaw, arms, legs, skin, eyes, smoke, smokeT: 0 };
}
const CR = buildCreature();
const C = {
  pos: tc(...CREATURE_SPAWNS[0]), yaw: 0, state: 'dormant', path: [], pathI: 0, repath: 0, goal: null,
  lastKnown: null, lastSeenT: -99, lostT: 0, stateT: 0, searchPts: [], exposure: 0, resistance: 0, flinch: 0, speed: 0,
  phase: 0, suspicion: 0, knownHide: null, wakeT: CFG.creature.wakeAfter, sees: false, directorT: CFG.creature.director, twitch: 0, tilt: 0,
};
CR.root.visible = false;

// =====================================================================
// ESTADO DO JOGO, SAVE
// =====================================================================
const G = { started: false, paused: true, dead: false, won: false, t: 0, inserted: [], notesRead: new Set(), endgame: null, exitOpen: false, deaths: 0, msgT: 0, readingNote: false, scare: 0 };
const SAVE_KEY = 'santa_lucia_save_v1';
function save() { try { localStorage.setItem(SAVE_KEY, JSON.stringify({ inserted: G.inserted, notes: [...G.notesRead], deaths: G.deaths })); } catch (e) { } }
function loadSave() { try { const s = JSON.parse(localStorage.getItem(SAVE_KEY)); return s && s.inserted ? s : null; } catch (e) { return null; } }

// =====================================================================
// HUD
// =====================================================================
function msg(text, dur = 4) { const m = $('msg'); m.innerHTML = text; m.style.opacity = 1; G.msgT = dur; }
function setObjective() {
  const left = 4 - G.inserted.length - P.carried.length;
  let o = '';
  if (G.exitOpen) o = 'A porta está aberta. <b>FUJA.</b>';
  else if (G.endgame) o = `Energizando o portão… <b>${Math.floor(G.endgame.t / CFG.endgame.duration * 100)}%</b> — sobreviva.`;
  else if (P.carried.length) o = `Leve ${P.carried.length > 1 ? 'os fusíveis' : 'o fusível'} até o <b>painel da recepção</b>.` + (left ? ` Restam ${left} no prédio.` : '');
  else o = `Encontre os fusíveis. Restam <b>${left}</b>.`;
  $('objective').innerHTML = o;
  $('fuses').innerHTML = `PAINEL · ${G.inserted.length}/4<div class="slots">${[0, 1, 2, 3].map(i => `<span class="${i < G.inserted.length ? 'in' : i < G.inserted.length + P.carried.length ? 'carry' : ''}"></span>`).join('')}</div>`;
}
function showZone(z) { const e = $('zone'); e.textContent = z.name; e.style.opacity = 1; clearTimeout(showZone.t); showZone.t = setTimeout(() => e.style.opacity = 0, 2600); }
const grainCtx = $('grain').getContext('2d'); const grainImg = grainCtx.createImageData(200, 120);
function drawGrain() { const d = grainImg.data; for (let i = 0; i < d.length; i += 4) { const v = Math.random() * 255; d[i] = d[i + 1] = d[i + 2] = v; d[i + 3] = 255; } grainCtx.putImageData(grainImg, 0, 0); }

// =====================================================================
// INPUT
// =====================================================================
addEventListener('keydown', (e) => {
  keys[e.code] = true; if (!G.started || G.dead || G.won) return;
  if (G.readingNote && (e.code === 'KeyE' || e.code === 'Escape')) { closeNote(); return; }
  if (e.code === 'KeyF') toggleLamp();
  if (e.code === 'KeyC' || e.code === 'ControlLeft') P.crouch = !P.crouch;
  if (e.code === 'KeyE') interact();
  if (e.code === 'Space') e.preventDefault();
});
addEventListener('keyup', (e) => { keys[e.code] = false; });
function toggleLamp() { if (P.hidden) return; if (P.battery > 0 && P.overheat <= 0) { P.lampOn = !P.lampOn; AU.click(); } }
addEventListener('contextmenu', (e) => e.preventDefault());
addEventListener('mousedown', (e) => { if (e.button === 2) mouseR = true; if (G.started && !G.paused && document.pointerLockElement !== canvas && !DEBUG && !TOUCH) canvas.requestPointerLock?.(); });
addEventListener('mouseup', (e) => { if (e.button === 2) mouseR = false; });
let dragging = false; addEventListener('mousedown', e => { if (e.button === 0) dragging = true; }); addEventListener('mouseup', e => { if (e.button === 0) dragging = false; });
addEventListener('mousemove', (e) => {
  if (!G.started || G.paused || G.dead || G.lookLock) return; const locked = document.pointerLockElement === canvas; if (!locked && !dragging) return;
  const s = .0022; P.yaw -= e.movementX * s; P.pitch = clamp(P.pitch - e.movementY * s, -1.35, 1.35);
});
document.addEventListener('pointerlockchange', () => { if (!TOUCH && document.pointerLockElement !== canvas && G.started && !G.dead && !G.won && !G.readingNote) pause(true); });
function pause(v) { G.paused = v; $('pause').classList.toggle('on', v); try { if (AU.ctx) v ? AU.ctx.suspend() : AU.ctx.resume(); } catch (e) { } }
window.__pauseGame = () => { if (G.started && !G.dead && !G.won && !G.paused) pause(true); };
$('pause').addEventListener('click', () => { pause(false); if (!TOUCH) canvas.requestPointerLock?.(); });

// =====================================================================
// INTERAÇÃO
// =====================================================================
let focusTarget = null;
function findInteractable() {
  if (P.hidden) return null; const eye = camera.getWorldPosition(new THREE.Vector3()), fwd = camera.getWorldDirection(new THREE.Vector3()); let best = null, bestS = 1e9;
  for (const it of interactables) {
    if (it.gone) continue; if (it.type === 'fuse' && it.fuse.state !== 'world') continue;
    const to = it.pos.clone().sub(eye); const d = Math.hypot(to.x, to.z); if (d > 2.7) continue; const ang = fwd.angleTo(to.normalize()); if (ang > .6) continue;
    const sc = d + ang * 3; if (sc < bestS) { bestS = sc; best = it; }
  }
  return best;
}
function interact() {
  if (P.hidden) { leaveHide(); return; }
  const it = focusTarget; if (!it) return;
  if (it.type === 'fuse') pickFuse(it.fuse);
  else if (it.type === 'battery') { it.gone = true; it.obj.visible = false; P.battery = Math.min(100, P.battery + 45); AU.pickup(); msg('Pilhas novas. <b>+45%</b> de bateria.'); emitNoise(P.pos, 3); }
  else if (it.type === 'note') openNote(it);
  else if (it.type === 'locker') enterHide(it.spot);
  else if (it.type === 'panel') usePanel();
  else if (it.type === 'exit') { if (G.exitOpen) win(); else msg(G.endgame ? 'A trava ainda está energizando…' : 'Trancada. A trava eletromagnética está sem energia. <b>O painel ao lado precisa de 4 fusíveis.</b>'); }
}
function pickFuse(f) {
  f.state = 'carried'; f.obj.visible = false; P.carried.push(f.id); AU.pickup(); AU.zap(f.obj.position);
  for (const li of f.lights) setLight(li, false); computeLightmap();
  emitNoise(f.obj.position, CFG.noise.fusePickup, true);
  if (C.state === 'dormant') { C.wakeT = 2.5; }
  for (const F of FUGAS) if (F.armedBy === f.id) F.armed = true;
  msg(`Fusível encontrado (<i>${f.where}</i>). <b>As luzes do setor caíram.</b> Algo ouviu isso.`, 5); setObjective();
}
function setLight(i, on) { const L = lightObjs[i]; L.on = on; }
function usePanel() {
  if (G.endgame || G.exitOpen) return;
  if (!P.carried.length) { msg(`O painel tem 4 encaixes. ${G.inserted.length} preenchido(s).`); return; }
  for (const id of P.carried) { G.inserted.push(id); FUSES[id].state = 'inserted'; }
  P.carried = []; refreshPanel(); AU.clunk(panel.position); emitNoise(panel.position, CFG.noise.fuseInsert, true); save();
  msg(G.inserted.length < 4 ? `Fusível instalado. <b>${G.inserted.length}/4</b>. Progresso salvo.` : '<b>4/4.</b> O gerador está partindo…'); setObjective();
  if (G.inserted.length >= 4) startEndgame();
}
function refreshPanel() { panelSlots.forEach((s, i) => { const on = i < G.inserted.length; s.fuse.visible = on; s.lamp.material.color.setHex(on ? 0x00ff40 : 0x550000); }); }
function openNote(it) { G.readingNote = true; G.notesRead.add(it.idx); save(); $('note-title').textContent = it.note.title; $('note-body').innerHTML = it.note.body; $('note').classList.add('on'); AU.burst({ f: 3000, q: .6, dur: .3, vol: .06, verb: 0 }); }
function closeNote() { G.readingNote = false; $('note').classList.remove('on'); }
function enterHide(spot) {
  P.hidden = spot; P.lampOn = false; P.lastEnterHide = G.t; C.suspicion = 0;
  // ele viu você entrar?
  if (C.sees || (G.t - C.lastSeenT) < 1.2) C.knownHide = spot;
  $('hidemask').className = 'on' + (spot.type === 'under' ? ' under' : '');
  AU.burst({ f: 700, q: 4, dur: .35, vol: .25, verb: .4 }); emitNoise(spot.pos, 3.5);
  msg(spot.type === 'locker' ? 'Escondido. Segure <b>Espaço</b> para prender a respiração quando ele estiver perto.' : 'Debaixo da maca. Segure <b>Espaço</b> para não respirar alto.', 3);
}
function leaveHide() { const s = P.hidden; P.hidden = null; $('hidemask').className = ''; const [ex, ey] = s.exit; P.pos.copy(tc(ex, ey)); P.yaw = Math.atan2(-(ex - s.tile[0]), -(ey - s.tile[1])); AU.burst({ f: 700, q: 4, dur: .35, vol: .25, verb: .4 }); emitNoise(P.pos, 4); }

// =====================================================================
// SOM COMO INFORMAÇÃO PARA A IA
// =====================================================================
function emitNoise(pos, radius, important = false) {
  if (C.state === 'dormant' || C.state === 'retreat') return;
  const dark = lightAt(C.pos) < .35 ? 1.25 : 1; const d = pos.distanceTo(C.pos);
  const occl = los(pos.x, pos.z, C.pos.x, C.pos.z) ? 1 : .65;
  if (d > radius * dark * occl) return;
  if (C.state === 'chase') return;
  setState(C.state === 'search' && !important ? 'search' : 'investigate'); C.goal = pos.clone(); planPath(C.goal);
  if (DEBUG) console.log('noise heard', radius.toFixed(1), d.toFixed(1));
}

// =====================================================================
// IA
// =====================================================================
function setState(s) {
  if (C.state === s) return; const prev = C.state; C.state = s; C.stateT = 0;
  if (s === 'chase' && prev !== 'chase') { AU.stinger(); }
  if (s === 'search') { C.searchPts = []; }
}
const lightCost = (x, y) => lightmap[y][x] * 5;
function planPath(goal) { const [sx, sy] = tileOf(C.pos), [tx, ty] = tileOf(goal); const p = astar(sx, sy, tx, ty, C.state === 'patrol' || C.state === 'search' ? lightCost : null); C.path = p || []; C.pathI = 0; }
const ROOM_CENTERS = ZONES.filter(z => !['corrA', 'corrB', 'base', 'shaft'].includes(z.id)).map(z => { const [x, y] = nearestFree(((z.r[0] + z.r[2]) / 2) | 0, ((z.r[1] + z.r[3]) / 2) | 0); return { z, x, y }; });
function patrolGoal() {
  const pz = zoneAt(...tileOf(P.pos));
  let cands = ROOM_CENTERS.filter(r => r.z !== zoneAt(...tileOf(C.pos)));
  if (pz && Math.random() < .35) { const near = ROOM_CENTERS.slice().sort((a, b) => tc(a.x, a.y).distanceTo(P.pos) - tc(b.x, b.y).distanceTo(P.pos)); cands = near.slice(0, 3); }
  const r = pick(cands); return tc(r.x, r.y);
}
function canSeePlayer() {
  if (P.hidden) return false; const CC = CFG.creature; const head = new THREE.Vector3(); CR.head.getWorldPosition(head);
  const to = P.pos.clone().sub(C.pos); to.y = 0; const d = to.length(); if (d < CC.closeSense && los(C.pos.x, C.pos.z, P.pos.x, P.pos.z)) return true;
  const pl = lightAt(P.pos); let range = P.lampOn ? CC.seeFlashlight : pl > .35 ? CC.seeLit : P.crouch ? CC.seeCrouchDark : CC.seeDark;
  if (G.endgame) range *= 1.4; if (d > range) return false;
  const fwd = new THREE.Vector3(Math.sin(C.yaw), 0, Math.cos(C.yaw)); if (fwd.angleTo(to.normalize()) > THREE.MathUtils.degToRad(CC.fovHalf)) {
    // feixe da lanterna passando por ele denuncia você mesmo fora do campo de visão
    if (!(P.lampOn && d < 14 && beamOnCreatureBody())) return false;
  }
  return los(C.pos.x, C.pos.z, P.pos.x, P.pos.z);
}
function beamOnCreatureBody() { const eye = camera.getWorldPosition(new THREE.Vector3()), fwd = camera.getWorldDirection(new THREE.Vector3()); const to = C.pos.clone().setY(1.4).sub(eye); return fwd.angleTo(to) < .45; }
function moveAlong(speed, dt) {
  if (C.pathI >= C.path.length) return true;
  const [nx, ny] = C.path[C.pathI]; const target = tc(nx, ny); const to = target.sub(C.pos); to.y = 0; const d = to.length();
  if (d < .35) { C.pathI++; return C.pathI >= C.path.length; }
  to.normalize(); const step = Math.min(d, speed * dt); C.pos.addScaledVector(to, step);
  const ty = Math.atan2(to.x, to.z); let dy = ty - C.yaw; while (dy > Math.PI) dy -= 2 * Math.PI; while (dy < -Math.PI) dy += 2 * Math.PI; C.yaw += dy * Math.min(1, dt * 7);
  C.speed = speed; return false;
}
function faceTowards(p, dt, rate = 6) { const to = p.clone().sub(C.pos); const ty = Math.atan2(to.x, to.z); let dy = ty - C.yaw; while (dy > Math.PI) dy -= 2 * Math.PI; while (dy < -Math.PI) dy += 2 * Math.PI; C.yaw += dy * Math.min(1, dt * rate); }
function burn() {
  setState('retreat'); C.exposure = 0; C.resistance = Math.min(CFG.creature.resistMax, C.resistance + CFG.creature.resistStep);
  const head = new THREE.Vector3(); CR.head.getWorldPosition(head); AU.scream(head); CR.smokeT = 2.2;
  // foge para o cômodo escuro mais distante do jogador
  const opts = ROOM_CENTERS.map(r => ({ p: tc(r.x, r.y), l: lightmap[r.y][r.x] })).sort((a, b) => (b.p.distanceTo(P.pos) - b.l * 20) - (a.p.distanceTo(P.pos) - a.l * 20));
  C.goal = opts[0].p; C.retreatTo = C.goal;
  msg('<b>Ele recuou.</b> Não vai durar.', 2.5);
}
function updateCreature(dt) {
  const CC = CFG.creature; C.stateT += dt;
  if (G.fuga) { updateFugaCreature(dt); return; }
  if (C.state === 'dormant') {
    C.wakeT -= dt; if (C.wakeT <= 0) { CR.root.visible = true; setState('patrol'); C.goal = patrolGoal(); planPath(C.goal); AU.scream(C.pos.clone().setY(2)); AU.bang(C.pos.clone().setY(2)); if (!G.awakeOnce) { G.awakeOnce = true; msg('Um grito vindo do <b>subsolo</b>. Você não está sozinho.', 5); } }
    return;
  }
  const inLit = lightAt(C.pos) > .4; const sees = canSeePlayer(); C.sees = sees;
  if (sees) { C.lastKnown = P.pos.clone(); C.lastSeenT = G.t; }
  // ---------- luz: exposição do rosto
  lampVsCreature(dt);
  if (C.exposure >= 1 + C.resistance && C.state !== 'retreat') burn();
  C.resistance = Math.max(0, C.resistance - CC.resistDecay * dt);
  const flinchMul = C.flinch > 0 ? CC.flinchMul : 1; C.flinch = Math.max(0, C.flinch - dt);

  switch (C.state) {
    case 'patrol': {
      if (sees) { setState('chase'); break; }
      if (moveAlong(CC.patrol, dt)) { C.goal = patrolGoal(); planPath(C.goal); C.pauseT = rand(1, 3.5); }
      C.directorT -= dt; if (C.directorT <= 0) { C.directorT = CC.director; if (C.pos.distanceTo(P.pos) > 28 && !P.hidden) { setState('investigate'); C.goal = P.pos.clone().add(new THREE.Vector3(rand(-6, 6), 0, rand(-6, 6))); planPath(C.goal); } }
      break;
    }
    case 'investigate': {
      if (sees) { setState('chase'); break; }
      if (moveAlong(CC.investigate * flinchMul, dt)) { setState('search'); C.lastKnown = C.goal.clone(); }
      break;
    }
    case 'hunt': { // sabe a área aproximada (alarme / diretor)
      if (sees) { setState('chase'); break; }
      C.repath -= dt; if (C.repath <= 0) { C.repath = 2.5; C.goal = P.pos.clone(); planPath(C.goal); }
      if (moveAlong(CC.hunt * flinchMul, dt)) { setState('search'); C.lastKnown = C.goal.clone(); }
      break;
    }
    case 'chase': {
      C.repath -= dt; if (C.repath <= 0) { C.repath = .35; if (C.lastKnown) planPath(C.lastKnown); }
      const sp = (inLit ? CC.chaseLit : CC.chaseDark) * flinchMul * (G.endgame ? 1.08 : 1);
      const arrived = moveAlong(sp, dt); if (sees) { C.lostT = 0; if (C.pos.distanceTo(P.pos) < 4) { const to = P.pos.clone().sub(C.pos); to.y = 0; to.normalize(); C.pos.addScaledVector(to, sp * dt * .5); faceTowards(P.pos, dt, 10); } }
      else { C.lostT += dt; if (C.lostT > CC.loseTime || arrived) { C.lostT = 0; setState('search'); } }
      break;
    }
    case 'search': {
      if (sees) { setState('chase'); break; }
      // sabe que você entrou no esconderijo
      if (C.knownHide) { const sp = C.knownHide.pos; if (!C.path.length || C.pathI >= C.path.length) { planPath(tc(...C.knownHide.exit)); } moveAlong(CC.investigate * 1.2, dt); if (C.pos.distanceTo(sp) < 2.6 && P.hidden === C.knownHide) { kill('Ele viu você se esconder.'); return; } if (!P.hidden) C.knownHide = null; break; }
      if (C.pathI >= C.path.length) {
        if (C.searchPts.length === 0) { const base = C.lastKnown || C.pos; const [bx, by] = tileOf(base); for (let i = 0; i < 4; i++) { const cand = floorTiles.filter(([x, y]) => Math.abs(x - bx) <= 4 && Math.abs(y - by) <= 4); if (cand.length) { const c = cand.sort((a, b) => lightmap[a[1]][a[0]] - lightmap[b[1]][b[0]] + rand(-.3, .3))[0]; C.searchPts.push(tc(...c)); } } }
        const nextPt = C.searchPts.shift(); if (nextPt) planPath(nextPt); C.sniff = rand(.8, 2);
      }
      if (C.sniff > 0) { C.sniff -= dt; C.yaw += Math.sin(G.t * 2) * dt * 1.5; } else moveAlong(CC.investigate * .8, dt);
      if (C.stateT > CC.searchTime) { setState(G.endgame ? 'hunt' : 'patrol'); C.goal = patrolGoal(); planPath(C.goal); }
      break;
    }
    case 'retreat': {
      if (C.stateT < CC.stagger) { C.speed = 0; break; }
      if (C.stateT < CC.stagger + .05) planPath(C.retreatTo);
      moveAlong(CC.chaseDark * 1.1, dt);
      if (C.stateT > CC.retreatTime) { setState(G.endgame ? 'hunt' : 'search'); C.lastKnown = C.pos.clone(); C.searchPts = []; }
      break;
    }
  }
  // ---------- esconderijo: suspeita por proximidade e respiração
  if (P.hidden && !C.knownHide && ['search', 'investigate', 'hunt', 'patrol'].includes(C.state)) {
    const d = C.pos.distanceTo(P.hidden.pos);
    if (d < CC.hideSenseDist) { const rate = (P.holding ? 0 : 1) * (P.hidden.type === 'under' ? 1.3 : 1) * (G.endgame ? 1.4 : 1); C.suspicion += rate * dt; if (C.state !== 'search') { setState('search'); C.lastKnown = P.hidden.pos.clone(); } }
    else C.suspicion = Math.max(0, C.suspicion - dt * .5);
    if (C.suspicion > CC.hideSuspicion) { kill('Ele ouviu sua respiração.'); return; }
  }
  // ---------- contato = morte
  if (!P.hidden && C.state !== 'retreat' && C.pos.distanceTo(P.pos) < CC.killDist) kill('Ele te pegou no escuro.');
  CR.root.position.copy(C.pos); CR.root.rotation.y = C.yaw;
}
// --------- lanterna contra a criatura
function lampVsCreature(dt) {
  const L = CFG.lamp; let hit = 0;
  if (lampActive() && !P.hidden && !G.fuga) {
    const eye = camera.getWorldPosition(new THREE.Vector3()), fwd = camera.getWorldDirection(new THREE.Vector3()); const head = new THREE.Vector3(); CR.head.getWorldPosition(head); head.y += .12;
    const to = head.clone().sub(eye); const d = to.length(); const range = P.focus ? L.focusRange : L.range; const half = THREE.MathUtils.degToRad(P.focus ? L.focusHalfCone : L.halfCone);
    if (d < range && fwd.angleTo(to) < half && los(eye.x, eye.z, head.x, head.z)) {
      // precisa acertar o ROSTO: a criatura tem que estar virada para você
      const cf = new THREE.Vector3(Math.sin(C.yaw), 0, Math.cos(C.yaw)); const back = eye.clone().sub(C.pos).setY(0).normalize();
      const facing = cf.dot(back) > .25;
      if (facing) hit = P.focus ? L.exposureFocus : L.exposureNormal; else hit = 0.05;
      hit *= 1 - (d / range) * .45;
      if (C.state === 'patrol' || C.state === 'investigate' || C.state === 'search') { C.lastKnown = P.pos.clone(); C.lastSeenT = G.t; setState('chase'); }
      if (facing) { C.flinch = .25; if (Math.random() < dt * 8) AU.sizzle(head); }
    }
  }
  if (hit > 0) { C.exposure += hit * dt; if (!P.focus) C.exposure = Math.min(C.exposure, L.normalCap * (1 + C.resistance)); }
  else C.exposure = Math.max(0, C.exposure - .7 * dt);
  CR.skin.emissive.setRGB(C.exposure * .35, C.exposure * .12, C.exposure * .05);
}
const lampActive = () => P.lampOn && P.battery > 0 && P.overheat <= 0;

// --------- animação procedural da criatura
function animateCreature(dt) {
  if (!CR.root.visible) return; const moving = C.speed > .1; C.phase += dt * (moving ? 1.4 + C.speed * 1.25 : .5);
  const ph = C.phase, amp = moving ? clamp(C.speed / 3, .35, 1) : 0, chase = C.state === 'chase' || C.state === 'hunt';
  CR.hips.position.y = 1.22 - (chase ? .18 : 0) + Math.abs(Math.sin(ph)) * .06 * amp;
  CR.spine.rotation.x = (chase ? .95 : .6) + Math.sin(ph * 2) * .04;
  CR.spine.rotation.z = Math.sin(ph) * .08 * amp;
  CR.legs.forEach((l, i) => { const p = ph + i * Math.PI; l.hp.rotation.x = -.15 - Math.sin(p) * .65 * amp; l.kn.rotation.x = .25 + Math.max(0, Math.cos(p)) * 1.0 * amp + (chase ? .3 : 0); l.ft.rotation.x = -.2; });
  const cover = C.state === 'retreat' && C.stateT < CFG.creature.stagger + .6 || C.flinch > 0;
  CR.arms.forEach((a, i) => {
    const p = ph + (i ? 0 : Math.PI);
    if (cover) { a.sh.rotation.x = lerp(a.sh.rotation.x, -2.3, .3); a.el.rotation.x = lerp(a.el.rotation.x, -1.9, .3); a.sh.rotation.z = -a.s * .5; }
    else if (chase) { a.sh.rotation.x = lerp(a.sh.rotation.x, -1.35 + Math.sin(p) * .35, .2); a.el.rotation.x = -.35; a.sh.rotation.z = a.s * .25; }
    else { a.sh.rotation.x = lerp(a.sh.rotation.x, -.55 + Math.sin(p) * .25 * amp, .2); a.el.rotation.x = -.2 - Math.sin(G.t * .7 + i) * .1; a.sh.rotation.z = a.s * .12; }
    a.fingers.forEach((f, k) => f.rotation.x = Math.sin(G.t * 6 + k + i) * .15 + (chase ? -.4 : 0));
  });
  // cabeça: tiques nervosos, inclinação lenta perturbadora
  C.twitch -= dt; if (C.twitch <= 0) { C.twitch = rand(.4, 2.5); C.twitchAmt = rand(-.5, .5); C.tilt = Math.random() < .3 ? rand(-1.2, 1.2) : 0; }
  const jitter = Math.sin(G.t * 31) * .03 + (C.twitch < .08 ? C.twitchAmt : 0);
  CR.head.rotation.z = lerp(CR.head.rotation.z, C.tilt * (chase ? .3 : 1), .04) + jitter;
  CR.head.rotation.y = jitter * 1.5;
  CR.neck.rotation.x = (chase ? -1.35 : -1.05) + Math.sin(ph * 2) * .05;
  CR.jaw.rotation.x = (chase ? .9 : .35) + Math.sin(G.t * (chase ? 18 : 2)) * (chase ? .12 : .05) + (C.state === 'retreat' ? .5 : 0);
  // fumaça
  const pos = CR.smoke.geometry.attributes.position; const head = new THREE.Vector3(); CR.head.getWorldPosition(head);
  if (CR.smokeT > 0) { CR.smokeT -= dt; for (let i = 0; i < pos.count; i++) { let y = pos.getY(i) + dt * rand(.5, 1.2); if (y > head.y + 1.2 || y < head.y - .3) { pos.setXYZ(i, head.x + rand(-.15, .15), head.y + rand(-.1, .2), head.z + rand(-.15, .15)); } else pos.setY(i, y); } pos.needsUpdate = true; CR.smoke.material.opacity = Math.min(.5, CR.smokeT * .3); }
  else CR.smoke.material.opacity = 0;
}

// =====================================================================
// MORTE / CHECKPOINT / FINAL
// =====================================================================
function kill(reason) {
  if (G.dead || G.won) return; G.dead = true; G.deaths++; save(); G.scare = 1.1;
  if (P.hidden) { const s = P.hidden; P.hidden = null; $('hidemask').className = ''; }
  const eye = camera.getWorldPosition(new THREE.Vector3()); const fwd = camera.getWorldDirection(new THREE.Vector3()); fwd.y = 0; fwd.normalize();
  C.pos.copy(eye).addScaledVector(fwd, .75).setY(0); C.yaw = Math.atan2(-fwd.x, -fwd.z); CR.root.position.copy(C.pos); CR.root.rotation.y = C.yaw; CR.root.visible = true;
  C.state = 'chase'; C.speed = 0; P.lampOn = true;
  AU.jumpscare(); G.shake = 1.2; endFuga(false); G.lookLock = null;
  $('flash').style.opacity = .7; setTimeout(() => $('flash').style.opacity = 0, 120);
  const hh = 3 + Math.floor(G.t / 3600), mm = Math.floor(G.t / 60) % 60;
  const rows = [['Nome', 'NÃO IDENTIFICADO'], ['Hora do óbito', `0${hh}:${String(mm).padStart(2, '0')}`], ['Local', P.zone ? P.zone.name : 'desconhecido'], ['Causa', reason],
    ['Fusíveis no painel', `${G.inserted.length} de 4`], ['Destino', `Gaveta nº ${17 + G.deaths}`]];
  setTimeout(() => {
    document.body.classList.add('over'); $('d-num').textContent = String(1974000 + 17 + G.deaths); $('d-rows').innerHTML = rows.map(([k, v]) => `<div class="row"><span>${k}</span><b>${v}</b></div>`).join('');
    $('death').classList.add('on'); fitScreen($('death'), '.tag'); document.exitPointerLock?.(); $('d-tag').classList.remove('in'); void $('d-tag').offsetWidth; $('d-tag').classList.add('in');
    AU.tone && AU.tone({ f: 55, f2: 40, dur: 3, vol: .25, verb: .8 });
  }, 1100);
}
function respawn() {
  $('death').classList.remove('on'); document.body.classList.remove('over'); G.dead = false;
  for (const id of P.carried) { const f = FUSES[id]; f.state = 'world'; f.obj.visible = true; f.obj.position.copy(f.home); for (const li of f.lights) setLight(li, true); }
  P.carried = []; computeLightmap(); endFuga(false); for (const F of FUGAS) F.armed = false;
  P.pos.copy(tc(...PLAYER_START)); P.yaw = 0; P.pitch = 0; P.battery = Math.max(P.battery, 60); P.heat = 0; P.overheat = 0; P.lampOn = true; P.stamina = CFG.player.staminaMax; P.hidden = null;
  const far = CREATURE_SPAWNS.slice().sort((a, b) => tc(...b).distanceTo(P.pos) - tc(...a).distanceTo(P.pos))[0];
  C.pos.copy(tc(...far)); C.exposure = 0; C.knownHide = null; C.suspicion = 0; C.path = []; C.state = 'dormant'; C.wakeT = 8; CR.root.visible = false;
  if (G.endgame) { G.endgame.t = Math.max(0, G.endgame.t - 10); C.wakeT = 4; }
  setObjective(); pause(false); if (!TOUCH) canvas.requestPointerLock?.();
}
function startEndgame() {
  G.endgame = { t: 0 }; AU.alarm.gain.setTargetAtTime(.08, AU.ctx.currentTime, .5);
  for (const L of lightObjs) L.type = L.type === 'steady' ? 'flicker' : L.type;
  if (C.state === 'dormant') C.wakeT = 1; else { setState('hunt'); C.repath = 0; }
  msg('<b>ALARME.</b> Ele sabe onde você está. Aguente até a trava energizar.', 6);
}
function updateEndgame(dt) {
  if (!G.endgame || G.exitOpen) return; G.endgame.t += dt;
  if (C.state !== 'chase' && C.state !== 'retreat' && C.state !== 'dormant' && C.state !== 'hunt' && !(C.state === 'search' && C.knownHide)) { setState('hunt'); C.repath = 0; }
  if (G.endgame.t >= CFG.endgame.duration) { G.exitOpen = true; AU.doorOpen(exitDoor.getWorldPosition(new THREE.Vector3())); AU.alarm.gain.setTargetAtTime(.03, AU.ctx.currentTime, .5); msg('<b>A PORTA ABRIU. CORRA.</b>', 5); }
  setObjective();
}
function fitScreen(el, inner) { // encolhe o conteúdo para caber na tela (celular na horizontal)
  const box = el.querySelector(inner); box.style.transform = ''; const btn = el.querySelector('.btn');
  const need = [...el.children].reduce((h, c) => h + c.offsetHeight + 24, 24), k = Math.min(1, innerHeight / need, (innerWidth - 24) / box.offsetWidth);
  box.style.transform = `scale(${k})`; box.style.margin = `${-(1 - k) * box.offsetHeight / 2}px 0`;
}
function win() {
  if (G.won) return; G.won = true; document.exitPointerLock?.(); try { localStorage.removeItem(SAVE_KEY); } catch (e) { }
  // "foto" do jornal: a porta aberta, vista de dentro
  const d = tc(5, 3); camera.position.set(d.x + .3, 1.5, d.z + 1.5); camera.rotation.set(.05, 0, 0, 'YXZ'); CR.root.visible = false; renderer.render(scene, camera);
  let photo = ''; try { photo = renderer.domElement.toDataURL('image/jpeg', .8); } catch (e) { }
  const m = Math.floor(G.t / 60), s = Math.floor(G.t % 60), notes = G.notesRead.size;
  const rank = G.deaths === 0 ? 'ALTA MÉDICA' : G.deaths <= 2 ? 'TRANSFERÊNCIA' : G.deaths <= 5 ? 'LIBERDADE CONDICIONAL' : 'FUGA DESESPERADA';
  $('w-photo').style.backgroundImage = photo ? `url(${photo})` : 'none';
  $('w-time').textContent = `${m}:${String(s).padStart(2, '0')}`; $('w-deaths').textContent = G.deaths; $('w-notes').textContent = `${notes}/${NOTES.length}`; $('w-rank').textContent = rank;
  $('w-body').innerHTML = `Na madrugada de ontem, um sobrevivente foi encontrado no portão principal do Sanatório Santa Lúcia, desativado desde março. Ele afirma ter passado <b>${m} minutos</b> preso no prédio antes de restabelecer a energia da trava eletromagnética.` +
    (notes >= NOTES.length ? ' Em seu bolso, a polícia encontrou o prontuário completo do <b>Paciente 17</b>. O Dr. Vasconcellos segue desaparecido.' : ' A polícia não comenta o relato de uma "criatura sem pálpebras" vagando pelos corredores.');
  $('win-flash').classList.add('on'); AU.tone && AU.tone({ f: 220, dur: 3, vol: .06, type: 'sine', verb: .9 });
  setTimeout(() => { document.body.classList.add('over'); $('win').classList.add('on'); fitScreen($('win'), '.paper'); }, 1600);
  setTimeout(() => $('w-last').classList.add('in'), 5200);
}

// =====================================================================
// LOOP PRINCIPAL
// =====================================================================
function collide(pos, r) {
  const [tx, ty] = tileOf(pos);
  for (let y = ty - 1; y <= ty + 1; y++) for (let x = tx - 1; x <= tx + 1; x++) {
    if (!isSolid(x, y)) continue; const minX = x * T, maxX = minX + T, minZ = y * T, maxZ = minZ + T;
    const cx = clamp(pos.x, minX, maxX), cz = clamp(pos.z, minZ, maxZ); const dx = pos.x - cx, dz = pos.z - cz, d = Math.hypot(dx, dz);
    if (d < r) { if (d > 1e-5) { pos.x += dx / d * (r - d); pos.z += dz / d * (r - d); } else pos.z += r; }
  }
  for (const o of OBST) { if (!o.landed || (o.crouch && P.crouch)) continue; const b = o.box;
    const cx = clamp(pos.x, b[0], b[1]), cz = clamp(pos.z, b[2], b[3]); const dx = pos.x - cx, dz = pos.z - cz, d = Math.hypot(dx, dz);
    if (d < r) { if (d > 1e-5) { pos.x += dx / d * (r - d); pos.z += dz / d * (r - d); } else pos.x += r; if (!o.hit) { o.hit = true; P.stumble = .7; G.shake = Math.max(G.shake, .5); AU.tone({ f: 80, f2: 40, dur: .25, vol: .5 }); } } }
}
function updatePlayer(dt) {
  const PC = CFG.player, L = CFG.lamp;
  // lanterna: bateria, foco, calor
  P.focus = (mouseR || keys.KeyQ) && lampActive() && !P.hidden;
  if (lampActive()) P.battery = Math.max(0, P.battery - (P.focus ? L.focusDrain : L.drain) * dt);
  if (P.battery <= 0 && P.lampOn) { P.lampOn = false; msg('A lanterna <b>apagou</b>. Procure pilhas.'); }
  if (P.focus) { P.heat += L.heatRate * dt; if (P.heat >= 100) { P.overheat = L.overheat; P.heat = 100; AU.burst({ f: 2500, q: 2, dur: .4, vol: .2 }); msg('<b>Lanterna superaqueceu!</b>', 2.5); } }
  else P.heat = Math.max(0, P.heat - L.coolRate * dt);
  if (P.overheat > 0) { P.overheat -= dt; if (P.overheat <= 0) P.heat = 40; }
  // respiração
  P.holding = !!P.hidden && keys.Space && P.breath > 0;
  if (P.holding) P.breath -= dt; else P.breath = Math.min(PC.breathMax, P.breath + PC.breathRegen * dt);
  if (P.hidden && keys.Space && P.breath <= 0 && !P.gasped) { P.gasped = true; AU.burst({ f: 900, q: 1, dur: .6, vol: .3, verb: .2 }); if (C.pos.distanceTo(P.hidden.pos) < 6) C.suspicion += 1; }
  if (!keys.Space) P.gasped = false;
  if (P.hidden) { camera.position.copy(P.hidden.camPos); P.moving = 0; return; }
  if (G.lookLock && G.lookLock.freeze) { P.moving = 0; camera.position.set(P.pos.x, P.eye, P.pos.z); return; }
  // movimento
  let f = (keys.KeyW || keys.ArrowUp ? 1 : 0) - (keys.KeyS || keys.ArrowDown ? 1 : 0), s = (keys.KeyD || keys.ArrowRight ? 1 : 0) - (keys.KeyA || keys.ArrowLeft ? 1 : 0);
  let mag = 1; if (TI.stickId !== null) { f = -TI.y; s = TI.x; mag = clamp(Math.hypot(f, s), 0, 1); if (mag < .15) { f = s = 0; } }
  const moving = !!(f || s); P.sprinting = moving && (keys.ShiftLeft || keys.ShiftRight) && !P.exhausted && !P.crouch && f > .3;
  if (P.sprinting) { P.stamina -= dt; if (P.stamina <= 0) { P.exhausted = true; AU.burst({ f: 700, q: 1, dur: .8, vol: .15 }); } }
  else { P.stamina = Math.min(PC.staminaMax, P.stamina + PC.staminaRegen * dt); if (P.stamina > PC.staminaMax * .45) P.exhausted = false; }
  if (G.fuga) { P.stamina = PC.staminaMax; P.exhausted = false; } P.stumble = Math.max(0, (P.stumble || 0) - dt);
  const spd = (P.crouch ? (G.fuga ? 2.2 : PC.crouch) : P.sprinting ? PC.sprint : PC.walk * (P.exhausted ? .75 : 1)) * (P.sprinting ? 1 : Math.max(.35, mag)) * (P.stumble > 0 ? .45 : 1);
  if (moving) {
    const dir = new THREE.Vector3(-Math.sin(P.yaw) * f + Math.cos(P.yaw) * s, 0, -Math.cos(P.yaw) * f - Math.sin(P.yaw) * s).normalize();
    P.pos.addScaledVector(dir, spd * dt); collide(P.pos, PC.radius); collide(P.pos, PC.radius);
    P.stepAcc += spd * dt; const stride = P.sprinting ? 1.25 : P.crouch ? .7 : .95;
    if (P.stepAcc > stride) { P.stepAcc = 0; const z = zoneAt(...tileOf(P.pos)); const loud = P.sprinting ? 1.4 : P.crouch ? .25 : .7; AU.step(z ? z.floor : 'lino', loud); if (!G.fuga && lightAt(P.pos) < .3 && Math.random() < .05) { const back = P.pos.clone().add(new THREE.Vector3(Math.sin(P.yaw) * 3, 1, Math.cos(P.yaw) * 3)); setTimeout(() => AU.step(z ? z.floor : 'lino', loud * .8, back), 300); } emitNoise(P.pos, P.sprinting ? CFG.noise.sprint : P.crouch ? CFG.noise.crouch : CFG.noise.walk); }
    P.bob += dt * spd * 2.4;
  }
  P.moving = moving ? spd : 0;
  P.eye = lerp(P.eye, P.crouch ? PC.crouchEye : PC.eye, Math.min(1, dt * 10));
  camera.position.set(P.pos.x, P.eye + Math.sin(P.bob) * .035 * (moving ? 1 : 0), P.pos.z);
  const z = zoneAt(...tileOf(P.pos)); if (z && z !== P.zone) { P.zone = z; showZone(z); }
}
function updateLights(dt) {
  for (const L of lightObjs) {
    let k = L.on ? 1 : 0;
    if (L.on && L.type === 'flicker') { if (L.off > 0) { L.off -= dt; k = .05; } else if (Math.random() < dt * (G.endgame ? 3 : .7)) { L.off = rand(.04, .35); if (Math.random() < .25) AU.burst({ f: 3500, q: 1, dur: .05, vol: .04, at: L.pl.position, verb: .1 }); } else k = .9 + Math.random() * .1; }
    if (L.on && L.type === 'emergency') k = .55 + .45 * Math.sin(G.t * 2.2 + L.idx);
    if (C.state !== 'dormant' && L.on && C.pos.distanceTo(L.pl.position) < 4 && Math.random() < .5) k *= .3; // luz falha perto dele
    if (G.blackout > 0) k = 0; if (G.fuga && L.on) k *= Math.random() < .25 ? .1 : 1;
    L.pl.intensity = L.int * k; L.fix.material.color.setHex(L.color).multiplyScalar(k);
  }
  // lanterna
  const L = CFG.lamp; const dC = C.state !== 'dormant' ? C.pos.distanceTo(P.pos) : 99;
  let fl = lampActive() ? 1 : 0;
  if (fl && P.battery < 15 && Math.random() < .08) fl = .2; if (G.blackout > 0) fl = 0; if (G.lookLock && Math.random() < .4) fl = rand(0, .25); // lanterna falhando durante o susto
  if (fl && dC < 9 && Math.random() < (1 - dC / 9) * .25) fl = rand(.1, .6); // falha quando ele está perto (aviso diegético)
  flash.intensity = (P.focus ? 95 : 38) * fl; flash.angle = P.focus ? .22 : .5; flash.distance = (P.focus ? L.focusRange : L.range) + 4; flash.penumbra = P.focus ? .3 : .6;
  flash.color.setHex(P.focus ? 0xffffff : 0xfff0d0);
}
let heartT = 0, ambT = 3, dripT = 1, stepPh = 0;
function updateAudio(dt) {
  if (!AU.ctx) return; AU.setListener(camera); const t = AU.ctx.currentTime;
  const cp = C.pos.clone().setY(1.9); AU.cPan.positionX.value = cp.x; AU.cPan.positionY.value = cp.y; AU.cPan.positionZ.value = cp.z;
  const active = C.state !== 'dormant' && !G.dead; const occluded = !los(cp.x, cp.z, camera.position.x, camera.position.z);
  AU.cOcc.frequency.setTargetAtTime(occluded ? 500 : 7000, t, .1);
  const chase = C.state === 'chase' || C.state === 'hunt';
  const breathing = active ? (C.state === 'search' && C.sniff > 0 ? .1 : .28 + .2 * Math.sin(G.t * (chase ? 5 : 1.7))) : 0;
  AU.breath.gain.setTargetAtTime(Math.max(0, breathing) * (C.state === 'search' && C.sniff > 0 ? 3 : 1), t, .08);
  AU.growl.gain.setTargetAtTime(active && chase ? .08 : 0, t, .3); AU.growlOsc.frequency.setTargetAtTime(chase ? 58 + Math.sin(G.t * 9) * 6 : 50, t, .05);
  if (active && Math.random() < dt * (chase ? 3 : .8)) AU.tone({ f: rand(1500, 2600), dur: .015, vol: .12, type: 'square', at: cp, verb: .3 }); // estalos
  // zumbido das lâmpadas
  let hum = 0; for (const Lo of lightObjs) if (Lo.on && Lo.type !== 'emergency') hum = Math.max(hum, (1 - clamp(Lo.pl.position.distanceTo(camera.position) / 7, 0, 1)) * (Lo.pl.intensity / Lo.int));
  AU.hum.gain.setTargetAtTime(hum * .05, t, .05);
  // coração
  const d = active ? C.pos.distanceTo(P.pos) : 99; const fear = clamp(1 - d / 22, 0, 1) + (chase ? .4 : 0);
  heartT -= dt; if (fear > .1 && heartT <= 0) { heartT = 60 / lerp(70, 165, clamp(fear, 0, 1)); AU.heartbeat(.25 + fear * .5); }
  $('danger').style.opacity = clamp(fear * .7 - .2, 0, .55) * (chase ? 1 : .6);
  AU.ambGain.gain.setTargetAtTime(chase ? .15 : .35, t, .5); // "silêncio tenso": o ambiente recua quando ele caça
  ambT -= dt; if (ambT <= 0) { ambT = rand(6, 16); const [x, y] = pick(floorTiles); const p = tc(x, y, 2); if (p.distanceTo(P.pos) > 10) { const r = Math.random();
    if (r < .3) AU.bang(p); else if (r < .5) AU.moan(p); else if (r < .65) AU.creak(p, .25); else if (r < .8) AU.radio(p); else if (r < .92) AU.whisper(p, .25); else AU.scream(p); } }
  if (active && CR.root.visible) { const sp = Math.floor(C.phase / Math.PI); if (sp !== stepPh) { stepPh = sp; if (C.speed > .3) AU.cstep(C.pos.clone().setY(.2), chase || G.fuga ? 1 : .55); } if (chase && Math.random() < dt * .35) AU.scrape(cp); }
  if (active && C.pos.distanceTo(P.pos) < 9 && Math.random() < dt * .12) AU.whisper(cp, .22);
  dripT -= dt; if (dripT <= 0) { dripT = rand(1, 5); const p = P.pos.clone().add(new THREE.Vector3(rand(-6, 6), 2.5, rand(-6, 6))); AU.drip(p); }
}
function updateHUD() {
  const b = $('batt').firstElementChild; b.style.width = P.battery + '%'; $('batt-pct').textContent = Math.ceil(P.battery) + '%' + (P.lampOn ? '' : ' · desligada');
  $('heat').firstElementChild.style.width = (P.overheat > 0 ? 100 : P.heat) + '%'; $('heat').classList.toggle('hot', P.overheat > 0);
  $('stam').firstElementChild.style.width = (P.stamina / CFG.player.staminaMax * 100) + '%';
  $('breath').style.display = P.hidden ? 'block' : 'none'; $('breath').querySelector('i').style.width = (P.breath / CFG.player.breathMax * 100) + '%';
  focusTarget = findInteractable();
  let pr = ''; if (P.hidden) pr = '<b>E</b>sair'; else if (focusTarget) pr = `<b>E</b>${focusTarget.type === 'panel' && P.carried.length ? `Instalar ${P.carried.length} fusível(is)` : focusTarget.label}`;
  $('prompt').innerHTML = pr;
  if (G.msgT > 0) { G.msgT -= 1 / 60; if (G.msgT <= 0) $('msg').style.opacity = 0; }
  if (DEBUG) $('debug').textContent = `state ${C.state}  t ${C.stateT.toFixed(1)}\nexp ${C.exposure.toFixed(2)} res ${C.resistance.toFixed(2)}\nsees ${C.sees} dist ${C.pos.distanceTo(P.pos).toFixed(1)}\nlight@P ${lightAt(P.pos).toFixed(2)} @C ${lightAt(C.pos).toFixed(2)}\nsusp ${C.suspicion.toFixed(2)}`;
}
const clock = new THREE.Clock(); let grainT = 0;
function frame() {
  if (G.trailer) return;
  requestAnimationFrame(frame); const dt = Math.min(clock.getDelta(), 1 / 20);
  if (G.started && !G.paused && !G.readingNote && !G.won) {
    if (!G.dead) { G.t += dt; updatePlayer(dt); updateFuga(dt); updateCreature(dt); updateEndgame(dt); updateScares(dt); }
    updateTweens(dt); G.blackout = Math.max(0, (G.blackout || 0) - dt);
    animateCreature(dt); updateLights(dt); updateAudio(dt);
  }
  if (G.dead && G.scare > 0) { G.scare -= dt; const head = new THREE.Vector3(); CR.head.getWorldPosition(head); camera.lookAt(head); camera.rotation.z += rand(-.08, .08); CR.jaw.rotation.x = 1.3; }
  else { updateLookLock(dt); G.shake = Math.max(0, (G.shake || 0) - dt * 1.5); const sh = G.shake * G.shake * .06; camera.rotation.set(P.pitch + rand(-sh, sh), P.yaw + rand(-sh, sh), rand(-sh, sh) * .5, 'YXZ'); if (P.hidden) { camera.rotation.set(P.pitch * .4, P.hidden.type === 'locker' ? P.hidden.yaw + clamp(P.yaw - P.hidden.yaw, -.5, .5) : P.yaw, 0, 'YXZ'); } }
  if (G.started) { updateHUD(); updateTouchUI(); }
  grainT += dt; if (grainT > 1 / 24) { grainT = 0; drawGrain(); }
  renderer.render(scene, camera);
}


// =====================================================================
// CONTROLES DE TOQUE (celular / APK)
// joystick dinâmico à esquerda · arrastar à direita para olhar · botões
// =====================================================================
const TI = { stickId: null, lookId: null, ox: 0, oy: 0, x: 0, y: 0, lx: 0, ly: 0 };
if (TOUCH) {
  document.body.classList.add('touch');
  const layer = $('touch'), knob = $('stick-knob'), base = $('stick-base'), R = 60;
  layer.addEventListener('pointerdown', (e) => {
    if (e.target.closest('.tbtn')) return;
    if (e.clientX < innerWidth * .42 && TI.stickId === null) { TI.stickId = e.pointerId; TI.ox = e.clientX; TI.oy = e.clientY; TI.x = TI.y = 0; base.style.cssText = `display:block;left:${e.clientX}px;top:${e.clientY}px`; knob.style.transform = 'translate(-50%,-50%)'; }
    else if (TI.lookId === null) { TI.lookId = e.pointerId; TI.lx = e.clientX; TI.ly = e.clientY; }
  });
  layer.addEventListener('pointermove', (e) => {
    if (e.pointerId === TI.stickId) { let dx = e.clientX - TI.ox, dy = e.clientY - TI.oy; const d = Math.hypot(dx, dy); if (d > R) { dx *= R / d; dy *= R / d; } TI.x = dx / R; TI.y = dy / R; knob.style.transform = `translate(calc(-50% + ${dx}px),calc(-50% + ${dy}px))`; }
    else if (e.pointerId === TI.lookId && !G.paused && !G.dead && !G.lookLock) { const sns = .0055; P.yaw -= (e.clientX - TI.lx) * sns; P.pitch = clamp(P.pitch - (e.clientY - TI.ly) * sns, -1.35, 1.35); TI.lx = e.clientX; TI.ly = e.clientY; }
  });
  const up = (e) => { if (e.pointerId === TI.stickId) { TI.stickId = null; TI.x = TI.y = 0; base.style.display = 'none'; } if (e.pointerId === TI.lookId) TI.lookId = null; };
  layer.addEventListener('pointerup', up); layer.addEventListener('pointercancel', up);
  const hold = (id, key) => { const b = $(id); b.addEventListener('pointerdown', (e) => { e.stopPropagation(); keys[key] = true; b.classList.add('held'); }); for (const ev of ['pointerup', 'pointercancel', 'pointerleave']) b.addEventListener(ev, () => { keys[key] = false; b.classList.remove('held'); }); };
  const tap = (id, fn) => $(id).addEventListener('pointerdown', (e) => { e.stopPropagation(); e.preventDefault(); if (G.started && !G.dead && !G.won) fn(); });
  hold('tb-focus', 'KeyQ'); hold('tb-run', 'ShiftLeft'); hold('tb-breath', 'Space');
  tap('tb-lamp', toggleLamp); tap('tb-crouch', () => { P.crouch = !P.crouch; }); tap('tb-use', () => { if (G.readingNote) closeNote(); else interact(); });
  tap('tb-pause', () => pause(true));
  $('note').addEventListener('pointerdown', () => { if (G.readingNote) closeNote(); });
  $('keys-desktop').style.display = 'none'; $('keys-touch').style.display = '';
}
function updateTouchUI() {
  if (!TOUCH) return; const hid = !!P.hidden;
  $('tb-breath').style.display = hid ? '' : 'none'; $('tb-run').style.display = hid ? 'none' : ''; $('tb-focus').style.display = hid ? 'none' : '';
  $('tb-use').classList.toggle('ready', !!focusTarget || hid || G.readingNote);
  $('tb-use').textContent = hid ? 'SAIR' : G.readingNote ? 'FECHAR' : 'USAR';
  $('tb-lamp').classList.toggle('held', P.lampOn); $('tb-crouch').classList.toggle('held', P.crouch);
}


// =====================================================================
// TWEENS / EFEITOS DE TELA
// =====================================================================
const TW = [];
function tween(dur, fn, done) { TW.push({ t: 0, dur, fn, done }); }
const after = (sec, fn) => tween(sec, () => { }, fn); // timer no relógio do jogo (respeita pausa)
const later = (fn, ms) => after(ms / 1000, fn);
function updateTweens(dt) { for (let i = TW.length - 1; i >= 0; i--) { const w = TW[i]; w.t += dt; const k = Math.min(1, w.t / w.dur); w.fn(k); if (k >= 1) { TW.splice(i, 1); w.done && w.done(); } } }
function screenFlash(color = '#7a0000', op = .6, ms = 110) { const f = $('flash'); f.style.background = color; f.style.opacity = op; setTimeout(() => { f.style.opacity = 0; f.style.background = '#7a0000'; }, ms); }

// =====================================================================
// FUGA — perseguição roteirizada: corredor longo, coisas desabando, sem luz que salve
// =====================================================================
const FUGAS = [
  { id: 'leste', armedBy: 1, trigger: (x, y) => y === 4 && x >= 34 && x <= 41, spawn: [42, 4], path: [[41, 4], [10, 4]], end: (x, y) => y === 4 && x <= 10, gate: [11, 4] },
  { id: 'subsolo', armedBy: 3, trigger: (x, y) => y === 25 && x >= 30 && x <= 37, spawn: [41, 25], path: [[37, 25], [11, 25], [11, 11]], end: (x, y) => x === 11 && y <= 11 || x === 10 && y === 11, gate: [11, 13] },
];
const OBST = []; let gateMesh = null, fugaRed = null, fugaBeat = 0;
const debrisMat = new THREE.MeshStandardMaterial({ color: 0x6a6b60, roughness: 1, map: TEX.ceiling });
function buildObstacles(F) {
  const pts = []; for (let i = 0; i < F.path.length - 1; i++) { const [ax, ay] = F.path[i], [bx, by] = F.path[i + 1]; const n = Math.max(Math.abs(bx - ax), Math.abs(by - ay)); for (let k = 0; k < n; k++) pts.push([ax + Math.sign(bx - ax) * k, ay + Math.sign(by - ay) * k, bx !== ax]); }
  const types = ['debris', 'beam', 'locker', 'debris', 'cart', 'beam'];
  let ti = 0;
  for (let i = 3; i < pts.length - 2; i += rand(2.6, 3.6) | 0) {
    const [x, y, horiz] = pts[i]; const c = tc(x, y); if (c.distanceTo(P.pos) < 9) continue; const type = types[ti++ % types.length]; const side = Math.random() < .5 ? -1 : 1; const g = new THREE.Group(); let box, crouch = false;
    if (type === 'beam') { // cano/viga que despenca na altura da cabeça: AGACHE
      const m = box3(horiz ? .3 : T, .3, horiz ? T : .3, MAT.rust); g.add(m); g.position.set(c.x, WALL_H, c.z); crouch = true;
      box = [c.x - (horiz ? .15 : T / 2), c.x + (horiz ? .15 : T / 2), c.z - (horiz ? T / 2 : .15), c.z + (horiz ? T / 2 : .15)];
    } else { // entulho/armário/carrinho ocupando metade do corredor: DESVIE
      const w = type === 'locker' ? .95 : 1.05; const m = type === 'locker' ? box3(horiz ? .9 : w, .6, horiz ? w : .9, MAT.locker) : type === 'cart' ? box3(horiz ? .8 : w, .9, horiz ? w : .8, MAT.metal) : box3(horiz ? 1.1 : w, .5, horiz ? w : 1.1, debrisMat);
      m.position.y = type === 'cart' ? .45 : .3; g.add(m); const off = side * (T / 2 - w / 2 - .02);
      g.position.set(c.x + (horiz ? 0 : off), type === 'cart' ? 0 : WALL_H, c.z + (horiz ? off : 0));
      const hw = (horiz ? .55 : w / 2), hd = (horiz ? w / 2 : .55); box = [g.position.x - hw, g.position.x + hw, g.position.z - hd, g.position.z + hd];
      if (type === 'cart') g.position[horiz ? 'z' : 'x'] -= side * 2.2; // carrinho entra rolando de lado
    }
    scene.add(g); OBST.push({ g, box, type, crouch, landed: false, falling: false, hit: false, at: c, horiz, side });
  }
}
function startFuga(F) {
  if (G.fuga || G.endgame) return; F.armed = false; G.fuga = F; fugaBeat = 0; buildObstacles(F);
  C.pos.copy(tc(...F.spawn)); C.state = 'chase'; C.path = []; C.repath = 0; CR.root.visible = true; C.knownHide = null;
  if (!fugaRed) { fugaRed = new THREE.PointLight(0xff2010, 0, 9, 2); scene.add(fugaRed); }
  AU.jumpscare(); AU.stinger(); G.shake = 1; screenFlash('#7a0000', .55, 140); CR.root.position.copy(C.pos); focusOn(() => { const v = new THREE.Vector3(); CR.head.getWorldPosition(v); return v; }, 1.1, { speed: 10, fov: 55 });
  msg('<b style="font-size:26px;color:#ff5040">CORRA!</b><br>Segure correr · desvie do entulho · <b>agache</b> sob as vigas', 5);
}
function updateFuga(dt) {
  if (!G.fuga) { const [x, y] = tileOf(P.pos); for (const F of FUGAS) if (F.armed && !P.hidden && F.trigger(x, y)) startFuga(F); return; }
  const F = G.fuga; const [x, y] = tileOf(P.pos);
  // obstáculos caem quando você se aproxima
  for (const o of OBST) {
    if (o.falling || o.landed) continue; if (o.at.distanceTo(P.pos) > 7.5) continue; o.falling = true;
    if (o.type === 'cart') { const from = o.g.position.clone(), to = from.clone(); to[o.horiz ? 'z' : 'x'] += o.side * 2.2; AU.creak(o.at.clone().setY(1), .3); tween(.45, k => o.g.position.lerpVectors(from, to, k), () => { o.landed = true; AU.slam(o.at, .5); }); }
    else { const y0 = o.g.position.y, y1 = o.type === 'beam' ? 1.15 : 0; if (o.type === 'beam') AU.creak(o.at.clone().setY(2.5), .35);
      tween(o.type === 'beam' ? .35 : .5, k => { o.g.position.y = lerp(y0, y1, k * k); o.g.rotation.z = (o.type === 'beam' ? 0 : k * .3 * o.side); }, () => { o.landed = true; AU.crash(o.at.clone().setY(1)); G.shake = Math.max(G.shake, .35);
        const b = o.box; if (P.pos.x > b[0] - .3 && P.pos.x < b[1] + .3 && P.pos.z > b[2] - .3 && P.pos.z < b[3] + .3 && !(o.crouch && P.crouch)) { P.stumble = .9; G.shake = 1; screenFlash('#300', .5, 90); } }); }
  }
  // tambores
  fugaBeat -= dt; if (fugaBeat <= 0) { fugaBeat = .22; AU.tom(.35); }
  if (fugaRed) { const h = C.pos.clone().setY(2.2); fugaRed.position.copy(h); fugaRed.intensity = 6 + Math.random() * 6; }
  if (F.end(x, y)) endFuga(true);
}
function updateFugaCreature(dt) {
  const d = C.pos.distanceTo(P.pos); const sp = d > 16 ? 5.6 : d < 5 ? 4.35 : 4.6; // elástico: sempre colado, nunca impossível
  C.repath -= dt; if (C.repath <= 0) { C.repath = .25; planPath(P.pos); } moveAlong(sp, dt); C.state = 'chase';
  CR.root.position.copy(C.pos); CR.root.rotation.y = C.yaw;
  if (d < CFG.creature.killDist) kill('Ele te alcançou na fuga.');
}
function endFuga(survived) {
  if (!G.fuga) return; const F = G.fuga; G.fuga = null;
  for (const o of OBST) scene.remove(o.g); OBST.length = 0; if (fugaRed) fugaRed.intensity = 0;
  if (!survived) return;
  // portão de aço desce atrás de você
  const [gx, gy] = F.gate; put(gx, gy, 'G'); computeLightmap(); const c = tc(gx, gy);
  gateMesh = box3(T, WALL_H, T, MAT.steel); gateMesh.position.set(c.x, WALL_H * 1.5, c.z); scene.add(gateMesh);
  tween(.25, k => gateMesh.position.y = lerp(WALL_H * 1.5, WALL_H / 2, k * k), () => { AU.slam(c, 1.3); G.shake = .9; });
  setTimeout(() => AU.scream(c.clone().setY(2)), 600);
  setTimeout(() => { for (let i = 0; i < 3; i++) setTimeout(() => { AU.slam(c, .7); G.shake = Math.max(G.shake, .3); }, i * 380); }, 1100); // ele esmurra o portão
  C.state = 'dormant'; C.wakeT = 25; CR.root.visible = false; C.pos.copy(tc(...CREATURE_SPAWNS[1]));
  msg('Você escapou. <b>Por enquanto.</b>', 4);
  setTimeout(() => { if (!gateMesh) return; const gm = gateMesh; gateMesh = null; tween(1.2, k => gm.position.y = lerp(WALL_H / 2, WALL_H * 1.5, k), () => { scene.remove(gm); put(gx, gy, '.'); computeLightmap(); }); AU.creak(c, .3); }, 30000);
}

// =====================================================================
// SUSTOS ROTEIRIZADOS (cada um acontece uma vez; intervalo mínimo entre eles)
// =====================================================================
const PH = buildCreature(); PH.root.visible = false; PH.skin.color.setHex(0x4f4941); PH.skin.roughness = .8;
function posePhantom(tilt = .6) { PH.spine.rotation.x = .8; PH.neck.rotation.x = -1.25; PH.jaw.rotation.x = 1.15; PH.head.rotation.z = tilt; PH.arms.forEach(a => { a.sh.rotation.x = -.35; a.el.rotation.x = -.25; a.sh.rotation.z = a.s * .12; }); PH.legs.forEach(l => { l.kn.rotation.x = .3; }); }
function showPhantom(pos, faceTo, dur, sound = true) {
  posePhantom(rand(-.9, .9)); PH.root.position.copy(pos).setY(0); PH.root.rotation.y = Math.atan2(faceTo.x - pos.x, faceTo.z - pos.z); PH.root.visible = true;
  if (sound) { AU.stinger(); AU.whisper(pos.clone().setY(2), .35); }
  PH.root.updateMatrixWorld(true); const hd = new THREE.Vector3(); PH.head.getWorldPosition(hd); focusOn(hd, dur + .35, { speed: 16, fov: 52 });
  after(dur, () => { PH.root.visible = false; AU.radio(pos.clone().setY(2)); for (const L of lightObjs) L.off = rand(.05, .3); });
}
const fwdOf = () => new THREE.Vector3(-Math.sin(P.yaw), 0, -Math.cos(P.yaw));
// ----- CÂMERA FORÇADA: no susto, a cabeça vira sozinha para onde ele está (com zoom curto)
function focusOn(target, dur = 1.2, o = {}) { G.lookLock = { target, t: 0, dur, speed: o.speed || 11, fov: o.fov || 56, freeze: o.freeze !== false }; }
function updateLookLock(dt) {
  const L = G.lookLock; let fovT = 72;
  if (L && !G.dead) {
    L.t += dt; const tg = typeof L.target === 'function' ? L.target() : L.target; const e = camera.position;
    const dx = tg.x - e.x, dy = tg.y - e.y, dz = tg.z - e.z; const wy = Math.atan2(-dx, -dz), wp = Math.atan2(dy, Math.hypot(dx, dz));
    let d = wy - P.yaw; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; const k = 1 - Math.exp(-L.speed * dt);
    P.yaw += d * k; P.pitch += (wp - P.pitch) * k; fovT = L.fov; if (L.t >= L.dur) G.lookLock = null;
  } else if (L) G.lookLock = null;
  if (Math.abs(camera.fov - fovT) > .05) { camera.fov += (fovT - camera.fov) * Math.min(1, dt * (fovT < camera.fov ? 14 : 4)); camera.updateProjectionMatrix(); }
}
const behind = (dist) => P.pos.clone().addScaledVector(fwdOf(), -dist);
function turnScare(dist = 1.7) { // algo atrás de você: a câmera gira 180° e ele está lá
  let at = behind(dist); if (isSolid(...tileOf(at))) at = behind(1.1); if (isSolid(...tileOf(at))) return false;
  posePhantom(rand(-.8, .8)); PH.root.position.copy(at).setY(0); PH.root.rotation.y = Math.atan2(P.pos.x - at.x, P.pos.z - at.z); PH.root.visible = true; PH.root.updateMatrixWorld(true);
  const head = new THREE.Vector3(); PH.head.getWorldPosition(head);
  focusOn(head, 1.25, { speed: 9, fov: 50 });
  after(.3, () => { AU.jumpscare(); G.shake = 1.1; screenFlash('#7a0000', .55, 110); });
  after(1.2, () => { PH.root.visible = false; for (const L of lightObjs) L.off = rand(.1, .3); });
}

const inZone = (id) => P.zone && P.zone.id === id;
const SCARES = [
  { id: 'gaveta', when: () => inZone('morgue') && P.pos.x > tc(33, 0).x, run() { // gaveta abre sozinha com um corpo
      const c = tc(41, 11); const tray = box3(1.4, .08, .8, MAT.steel), bag = makeBody('bag', { arm: true }); bag.scale.set(.68, .9, .9); bag.rotation.y = Math.PI; bag.position.y = .04; tray.add(bag);
      tray.position.set(c.x + T / 2 + .7, 1.35, c.z); scene.add(tray); focusOn(c.clone().setY(1.2).setX(c.x + T / 2 - .6), 1.5, { speed: 13 }); AU.slam(c.clone().setY(1.3), 1); G.shake = .6; screenFlash('#000', .5, 60);
      tween(.18, k => tray.position.x = lerp(c.x + T / 2 + .7, c.x + T / 2 - .7, k)); later(() => AU.whisper(c.clone().setY(1.3), .3), 700); } },
  { id: 'corredor', when: () => P.zone && P.zone.id === 'corrA' && G.awakeOnce && fwdOf().x > .7 && P.pos.x < tc(30, 0).x && C.state !== 'chase', run() { // ele no fim do corredor
      const [x] = tileOf(P.pos); const at = tc(Math.min(41, x + 8), 4); if (!los(P.pos.x, P.pos.z, at.x, at.z)) return false; showPhantom(at, P.pos, .5); } },
  { id: 'armario', when: () => inZone('admin') && P.pos.distanceTo(tc(9, 9)) < 3.2, run() { // corpo cai de dentro do armário
      const c = tc(9, 9); const body = makeBody('sheet', { arm: true, blood: true }); body.scale.set(.88, 1, .7); const inner = new THREE.Group(); inner.rotation.z = Math.PI / 2; inner.add(body); const outer = new THREE.Group(); outer.rotation.y = Math.PI / 2; outer.position.y = .88; outer.add(inner); const piv = new THREE.Group(); piv.position.set(c.x, 0, c.z - .55); piv.add(outer); scene.add(piv);
      AU.slam(c.clone().setY(1.2), 1); G.shake = .8; screenFlash('#000', .6, 70); focusOn(() => { const v = new THREE.Vector3(); body.getWorldPosition(v); return v; }, 1.6, { speed: 12 }); tween(.45, k => piv.rotation.x = k * k * 1.45, () => { AU.tone({ f: 70, f2: 35, dur: .3, vol: .6, at: c, verb: .4 }); }); } },
  { id: 'apagao', when: () => inZone('lab'), run() { // tudo apaga, respiração na nuca; a cabeça vira e ele está atrás de você
      G.blackout = 1.8; AU.tone({ f: 120, f2: 30, dur: .6, vol: .3, type: 'sawtooth', verb: .5 }); later(() => AU.whisper(null, .35), 300); later(() => AU.whisper(null, .3), 900);
      later(() => AU.step('ftile', .8, behind(2).setY(1)), 1100); after(1.5, () => { AU.breath && AU.whisper(behind(.6).setY(1.6), .45); });
      after(1.8, () => { if (turnScare(1.6) === false) { const at = P.pos.clone().addScaledVector(fwdOf(), 2.1); if (!isSolid(...tileOf(at))) { showPhantom(at, P.pos, .35, false); AU.jumpscare(); G.shake = 1; } } }); } },
  { id: 'cadeira', when: () => inZone('ward') && P.pos.z > tc(0, 7).z, run() { // cadeira de rodas atravessa a sala sozinha
      const g = new THREE.Group(); const seat = box3(.5, .05, .5, MAT.black); seat.position.y = .5; g.add(seat); const back = box3(.5, .55, .05, MAT.black); back.position.set(0, .8, -.25); g.add(back);
      for (const sx of [-.3, .3]) { const w = new THREE.Mesh(new THREE.TorusGeometry(.3, .02, 6, 18), MAT.steel); w.rotation.y = Math.PI / 2; w.position.set(sx, .32, 0); g.add(w); }
      const ahead = P.pos.clone().addScaledVector(fwdOf(), 4.5); const side = new THREE.Vector3(Math.cos(P.yaw), 0, -Math.sin(P.yaw));
      const a = ahead.clone().addScaledVector(side, -6), b = ahead.clone().addScaledVector(side, 6); g.position.copy(a); g.rotation.y = Math.atan2(side.x, side.z); scene.add(g);
      AU.creak(ahead.clone().setY(.5), .4); focusOn(() => g.position.clone().setY(.6), 1.5, { speed: 8, fov: 60 }); tween(1.6, k => g.position.lerpVectors(a, b, k), () => { scene.remove(g); AU.slam(b, .5); }); } },
  { id: 'telefone', when: () => inZone('security'), run() { AU.ring(); after(2.3, () => { const ph = tc(9, 16, 1.1); ph.z += .8; AU.radio(ph); AU.scream(ph); G.shake = .5; focusOn(ph, 1.1, { speed: 14 }); }); } },
  { id: 'porao', when: () => inZone('cellar'), run() { const p = tc(13, 28, 1); AU.crash(p); G.shake = .5; focusOn(p, .9, { speed: 12, fov: 62 }); later(() => AU.whisper(tc(4, 29, 1), .4), 900); after(1.6, () => { const at = tc(10, 30); if (los(P.pos.x, P.pos.z, at.x, at.z)) showPhantom(at, P.pos, .4); }); } },
  { id: 'costas', when: () => inZone('corrB') && P.pos.z > tc(0, 15).z && P.pos.z < tc(0, 22).z && G.awakeOnce && C.pos.distanceTo(P.pos) > 15, run() { // passos se aproximando pelas costas
      for (const [ms, d] of [[0, 6], [550, 4.5], [1050, 3], [1450, 1.8]]) later(() => AU.step('lino', 1, behind(d).setY(.3)), ms);
      later(() => AU.whisper(behind(.7).setY(1.6), .5), 1800); after(2.1, () => turnScare(1.5)); } },
  { id: 'recepcao', when: () => inZone('reception') && P.carried.length > 0, run() { // voltando com o fusível: ele está ao lado da porta
      const at = tc(3, 1); for (const L of lightObjs) L.off = rand(.2, .5); later(() => showPhantom(at, P.pos, .3), 250); } },
];
let scareCd = 20;
function updateScares(dt) {
  scareCd -= dt; if (scareCd > 0 || G.fuga || G.endgame || P.hidden || C.state === 'chase') return;
  for (const S of SCARES) { if (S.done || !S.when()) continue; const r = S.run(); if (r === false) continue; S.done = true; scareCd = 25; break; }
}

// =====================================================================
// BOOT
// =====================================================================
buildLevel(); buildProps(); buildExit(); buildItems(); buildLights(); computeLightmap();
const existing = loadSave(); if (existing && existing.inserted.length) $('btn-cont').style.display = '';
function start(cont) {
  AU.init(); AU.ctx.resume?.(); $('title').classList.remove('on'); G.started = true; pause(false);
  if (cont && existing) { G.inserted = existing.inserted.slice(); G.deaths = existing.deaths || 0; for (const id of G.inserted) { FUSES[id].state = 'inserted'; FUSES[id].obj.visible = false; for (const li of FUSES[id].lights) setLight(li, false); } computeLightmap(); refreshPanel(); if (G.inserted.length) C.wakeT = 10; }
  else { try { localStorage.removeItem(SAVE_KEY); } catch (e) { } }
  setObjective(); camera.rotation.set(0, P.yaw, 0, 'YXZ');
  msg('A porta principal está trancada. Há um <b>painel de energia</b> à sua esquerda e um papel no chão.', 6);
  if (TOUCH) { $('touch').classList.add('on'); try { document.documentElement.requestFullscreen?.().then(() => screen.orientation?.lock?.('landscape').catch(() => { })).catch(() => { }); } catch (e) { } }
  else if (!DEBUG) canvas.requestPointerLock?.();
}
$('btn-new').onclick = () => start(false); $('btn-cont').onclick = () => start(true); $('btn-retry').onclick = () => respawn();
frame();

// gancho de teste/depuração
// ----- modo trailer: o jogo para de rodar sozinho e cada quadro é avançado por script (captura determinística)
if (new URLSearchParams(location.search).has('trailer')) {
  document.body.classList.add('trailer'); G.trailer = true;
  renderer.toneMappingExposure = 1.35; CR.skin.color.setHex(0x6e665b); CR.skin.roughness = .75; scene.add(new THREE.AmbientLight(0x2a3a38, .9)); scene.fog.density = .045;
  const temp = [];
  window.__tempLight = (x, y, int = 6, color = 0xcfe0dc, r = 9) => { const l = new THREE.PointLight(color, int, r, 2); l.position.copy(tc(x, y, WALL_H - .4)); scene.add(l); temp.push(l); return l; };
  window.__clearTemp = () => { temp.forEach(l => scene.remove(l)); temp.length = 0; };
  window.__setLight = (i, on) => { setLight(i, on); };
  window.__tick = (dt) => {
    G.t += dt; CR.root.position.copy(C.pos); CR.root.rotation.y = C.yaw;
    animateCreature(dt); updateLights(dt);
    flash.angle = P.focus ? .26 : .78; flash.penumbra = 1; flash.intensity *= 1.3; // feixe mais cinematográfico
    camera.rotation.set(P.pitch, P.yaw, P.roll || 0, 'YXZ');
    if (window.__post) window.__post();
    drawGrain(); renderer.render(scene, camera);
  };
}
window.__game = { win, P, C, G, CR, FUGAS, startFuga, SCARES, OBST, AU, lightObjs, refreshPanel, panelSlots, FUSES, CFG, camera, tc, setState, kill, respawn, computeLightmap, pickFuse, startEndgame, grid };
