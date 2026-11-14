// Roteiro de câmera do trailer. window.__frame(t) monta o quadro no tempo t (s).
(() => {
  const g = __game, { P, C, CR, camera } = g, T = 2.4;
  const card = document.getElementById('card'), mask = document.getElementById('hidemask'), flash = document.getElementById('flash');
  const W = (x, y) => ({ x: (x + .5) * T, z: (y + .5) * T });
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v)), lerp = (a, b, t) => a + (b - a) * t, ease = t => t * t * (3 - 2 * t);
  const fade = (lt, dur, fi = .5, fo = .5) => clamp(Math.min(lt / fi, (dur - lt) / fo), 0, 1);
  const nz = (t, f) => Math.sin(t * f) * .6 + Math.sin(t * f * 2.31 + 1.3) * .4;   // "câmera na mão"
  function cam(x, y, z, yaw, pitch, roll = 0) { camera.position.set(x, y, z); P.pos.set(x, 0, z); P.yaw = yaw; P.pitch = pitch; P.roll = roll; }
  function look(x, y, z, tx, ty, tz, shake = 0, t = 0, roll = 0) {
    const dx = tx - x, dy = ty - y, dz = tz - z;
    cam(x, y, z, Math.atan2(-dx, -dz) + nz(t, 1.7) * .012 * shake, Math.atan2(dy, Math.hypot(dx, dz)) + nz(t, 2.3) * .01 * shake, roll + nz(t, 1.1) * .006 * shake);
  }
  const head = () => { CR.root.updateMatrixWorld(true); const v = camera.position.clone(); CR.head.getWorldPosition(v); return v; };
  function crea(x, z, yaw, state, speed, stateT = 0) { CR.root.visible = true; C.pos.set(x, 0, z); C.yaw = yaw; C.state = state; C.speed = speed; C.stateT = stateT; }
  function setCard(html, cls, op) { card.className = cls || ''; if (card._h !== html) { card.innerHTML = html; card._h = html; } card.style.opacity = op; }
  function reset() {
    window.__clearTemp(); window.__post = null; g.lightObjs.forEach((L, i) => { window.__setLight(i, true); L.type = L.baseType || (L.baseType = L.type); });
    mask.className = ''; flash.style.opacity = 0; CR.root.visible = false; C.pos.set(-50, 0, -50); C.speed = 0; C.exposure = 0; C.flinch = 0; CR.smokeT = 0;
    P.lampOn = true; P.focus = false; P.battery = 100; P.overheat = 0; g.FUSES.forEach(f => f.obj.visible = true); g.G.inserted = []; g.refreshPanel();
    setCard('', '', 0);
  }
  const SHOTS = [
    { d: 3.6, f(lt, d) { setCard('<div class="line">SANATÓRIO SANTA LÚCIA</div><div class="small">1974</div>', '', fade(lt, d, .9, .7)); } },
    { d: 5.0, f(lt, d) { // corredor: dolly lento
      const k = lt / d, a = W(12, 4); cam(a.x + ease(k) * 13, 1.6 + Math.sin(lt * 5.5) * .02, a.z + .2, -Math.PI / 2 + nz(lt, .9) * .02, .03 + nz(lt, 1.3) * .01);
      setCard('<div class="line">Eles trancaram as portas.</div>', 'over', fade(lt - 1.2, 3.2, .5, .6)); } },
    { d: 4.0, f(lt, d) { // necrotério: push-in no brilho âmbar
      const k = ease(lt / d), f = g.FUSES[2].obj.position, a = W(35.5, 8.6);
      look(lerp(a.x, a.x + 5, k), 1.6, lerp(a.z, a.z + .6, k), f.x, f.y, f.z, 1, lt);
      setCard('<div class="line">E esconderam os fusíveis.</div>', 'over', fade(lt - .8, 3, .5, .5)); } },
    { d: 4.0, init() { window.__setLight(4, true); }, f(lt, d) { // pegar o fusível: as luzes morrem
      const f = g.FUSES[2].obj.position; const k = clamp((lt - 2) / 1.6, 0, 1);
      const tx = lerp(f.x, (30.5) * T, ease(k)), tz = lerp(f.z, 9.5 * T, ease(k)), ty = lerp(f.y, 1.7, ease(k));
      look(f.x - 1.4, 1.5, f.z + .5, tx, ty, tz, 1.2, lt);
      if (lt > 1.5 && g.FUSES[2].obj.visible) { g.FUSES[2].obj.visible = false; window.__setLight(4, false); }
      flash.style.background = '#cfe8ff'; flash.style.opacity = lt > 1.5 && lt < 1.62 ? .55 : lt > 1.7 && lt < 1.76 ? .3 : 0;
      if (lt > 2.8) { const p = W(30, 9); crea(p.x, p.z, Math.PI / 2, 'patrol', 0); } } },
    { d: 1.8, f(lt, d) { flash.style.opacity = 0; setCard('<div class="line">Ele ainda está aqui.</div>', '', fade(lt, d, .4, .4)); } },
    { d: 6.0, init() { window.__setLight(3, false); window.__setLight(11, false); window.__tempLight(21, 4, 5, 0xbfd8d0, 8); }, f(lt, d) { // revelação no corredor
      const c = W(33, 4), s = W(25.5, 4); const x = lerp(s.x, s.x + 8.5, ease(clamp(lt / d, 0, 1)));
      crea(x, s.z, Math.PI / 2, 'patrol', 1.1); const h = head();
      look(c.x, 1.58, c.z, h.x, h.y - .5, h.z, 1.4, lt); } },
    { d: 4.0, f(lt, d) { // perseguição: câmera recuando, ele ganhando terreno
      const c = W(11, 16), s = W(11, 11); const cz = c.z + lt * 1.9, z = s.z + lt * 5.3;
      crea(s.x, Math.min(z, cz - 1.6), 0, 'chase', 5); const h = head();
      look(c.x, 1.55 + Math.abs(Math.sin(lt * 9)) * .05, cz, h.x, h.y - .3, h.z, 4, lt * 2.2, Math.sin(lt * 4.5) * .03); } },
    { d: 1.6, f(lt, d) { setCard('<div class="line">A luz o fere.</div>', '', fade(lt, d, .3, .4)); } },
    { d: 5.0, init() { window.__setLight(3, false); window.__setLight(11, false); window.__tempLight(22, 4, 3, 0xbfd8d0, 7); }, f(lt, d) { // feixe concentrado
      const c = W(33, 4), s = W(28.6, 4); let x, yaw = Math.PI / 2, st = 'chase', sp = 2.6, stT = 0;
      if (lt < 1.8) x = s.x + lt * 1.6; else if (lt < 2.9) { x = s.x + 1.8 * 1.6 - (lt - 1.8) * .6; st = 'retreat'; stT = lt - 1.8; sp = 0; }
      else { x = s.x + 1.8 * 1.6 - .66 - (lt - 2.9) * 4.8; yaw = -Math.PI / 2; st = 'retreat'; stT = 2; sp = 4.8; }
      crea(x, s.z, yaw, st, sp, stT);
      P.focus = lt > 1.0 && lt < 3.1; C.exposure = lt < 1.0 ? 0 : lt < 1.8 ? (lt - 1) * 1.3 : Math.max(0, 1 - (lt - 1.8) * .6);
      C.flinch = lt > 1.0 && lt < 1.8 ? .3 : 0; CR.skin.emissive.setRGB(C.exposure * .4, C.exposure * .13, C.exposure * .05);
      if (lt >= 1.8 && !this.burned) { this.burned = true; CR.smokeT = 2.4; } if (lt < 1.8) this.burned = false;
      const h = head(); look(c.x, 1.58, c.z, h.x, h.y, h.z, lt > 1.8 && lt < 2.2 ? 6 : 1.5, lt); } },
    { d: 5.0, init() { mask.className = 'on'; window.__tempLight(17, 4, 2.2, 0xa8c8c0, 7); P.lampOn = false; }, f(lt, d) { // dentro do armário
      P.lampOn = false; const s = W(21, 4), stop = W(17, 4); let x, yaw = -Math.PI / 2, sp = 1.2;
      if (lt < 2.0) x = lerp(s.x, stop.x + .3, lt / 2); else if (lt < 3.8) { x = stop.x + .3; yaw = lerp(-Math.PI / 2, Math.PI, ease(clamp((lt - 2) / .6, 0, 1))); sp = 0; }
      else { x = stop.x + .3 - (lt - 3.8) * 1.5; yaw = -Math.PI / 2; }
      crea(x, s.z - .1, yaw, 'search', sp); C.tilt = lt > 2.4 && lt < 3.8 ? 1.1 : 0; C.twitch = 1;
      const lz = (3.5) * T - .86; cam(stop.x, 1.57, lz + .1, Math.PI + nz(lt, .6) * .01, -.02); } },
    { d: 4.4, init() { g.lightObjs.forEach(L => L.type = 'flicker'); this.red = window.__tempLight(5, 2, 0, 0xff1a0a, 14); }, f(lt, d) { // alarme: montagem rápida
      this.red.intensity = (Math.sin(lt * 9) > 0 ? 14 : 2); P.lampOn = true; mask.className = '';
      const cut = Math.floor(lt / .73);
      if (cut === 0) { g.G.inserted = [0, 1, 2, 3].slice(0, 1 + Math.floor(lt / .18)); g.refreshPanel(); const p = W(7, 1); look(p.x, 1.5, p.z + 1.6, p.x, 1.4, p.z - 1, 2, lt); CR.root.visible = false; }
      else if (cut === 1) { const s = W(11, 9); crea(s.x, s.z + (lt - .73) * 5, 0, 'chase', 5.5); const h = head(); const c = W(11, 14); look(c.x, 1.5, c.z, h.x, h.y - .3, h.z, 5, lt * 2); }
      else if (cut === 2) { CR.root.visible = false; const p = W(5, 1); look(p.x + 1, 1.4, p.z + 3.5, p.x, 1.6, p.z - 1, 6, lt * 2); }
      else if (cut === 3) { const p = W(8, 3), c = W(3, 6); crea(p.x - (lt - 2.19) * 1.2, p.z, -2.1, 'search', 1.4); const h = head(); look(c.x, 1.6, c.z, h.x, h.y - .3, h.z, 4, lt * 2); }
      else { CR.root.visible = false; const a = W(5, 6), b = W(5, 2.2); const k = (lt - 2.92) / 1.48; cam(a.x, 1.55 + Math.abs(Math.sin(lt * 11)) * .07, lerp(a.z, b.z, k), nz(lt, 6) * .05, .05, Math.sin(lt * 6) * .04); }
      setCard('<div class="line">30 segundos.</div>', 'over', cut < 3 ? fade(lt, 2.2, .2, .3) : 0); } },
    { d: 1.0, f() { setCard('', '', 1); } },
    { d: .6, init() { this.r = window.__tempLight(5, 4, 9, 0xd8e8e0, 6); }, f(lt) { // jumpscare
      setCard('', '', 0); const p = W(5, 5); const fw = { x: 0, z: -1 };
      crea(p.x, p.z - lerp(2.6, 1.5, clamp(lt / .25, 0, 1)), 0, 'search', 0); window.__post = () => { CR.jaw.rotation.x = 1.35; CR.head.rotation.z = .25 + Math.sin(lt * 40) * .05; };
      const h = head(); const dist = lerp(1.6, .62, clamp(lt / .2, 0, 1)); look(h.x + Math.sin(lt * 50) * .015, h.y - .04, h.z + dist, h.x, h.y - .06, h.z, 8, lt * 3, .1);
      flash.style.background = '#7a0000'; flash.style.opacity = lt < .07 ? .85 : (.35 + .25 * Math.sin(lt * 90)); } },
    { d: .7, f() { window.__post = null; flash.style.opacity = 0; setCard('', '', 1); } },
    { d: 7.0, f(lt, d) {
      const sub = lt < 3.4 ? 'encontre os fusíveis · não olhe nos olhos dele' : 'disponível para android';
      setCard(`<div class="big">SANTA LÚCIA</div><div class="small">${sub}</div>`, '', 1); card.querySelector('.big').style.opacity = clamp(lt / 1.2, 0, 1) * clamp((d - lt) / .8, 0, 1);
      card.querySelector('.small').style.opacity = clamp(Math.min((lt - 1) / .6, lt < 3.4 ? (3.3 - lt) / .3 : (lt - 3.5) / .5), 0, 1) * clamp((d - lt) / .8, 0, 1); } },
  ];
  let acc = 0; SHOTS.forEach(s => { s.start = acc; acc += s.d; }); window.__duration = acc;
  let cur = -1;
  window.__frame = (t, dt) => {
    let i = SHOTS.findIndex(s => t >= s.start && t < s.start + s.d); if (i < 0) i = SHOTS.length - 1;
    const s = SHOTS[i]; if (i !== cur) { cur = i; reset(); s.init && s.init(); }
    s.f(t - s.start, s.d); window.__tick(dt);
  };
  window.__shots = SHOTS.map(s => [s.start, s.d]);
})();
