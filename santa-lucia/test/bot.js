// Robô que joga uma partida inteira (sem render). Registra travadas, mortes, tempos e erros.
(() => {
  const g = __game, T = 2.4, log = (window.__botlog = []), L = (m) => { log.push(`[${g.G.t.toFixed(0)}s] ${m}`); };
  const st = (window.__bot = { done: false, deaths: 0, stuck: 0, fuseT: [], lastPos: null, lastMoveT: 0, path: [], pathT: 0, goalKey: '' });
  let unstuck = 0;
  function goal() {
    if (g.G.exitOpen) return { k: 'exit', x: 5, y: 1 };
    if (g.P.carried.length) return { k: 'panel', x: 7, y: 1 };
    if (g.G.endgame) return { k: 'wait', x: 3, y: 4 };
    const fs = g.FUSES.filter(f => f.state === 'world'); if (!fs.length) return { k: 'wait', x: 6, y: 3 };
    const f = fs.sort((a, b) => g.tc(a.x, a.y).distanceTo(g.P.pos) - g.tc(b.x, b.y).distanceTo(g.P.pos))[0]; return { k: 'fuse', x: f.x, y: f.y, f };
  }
  function tick() {
    if (st.done) return; const G = g.G, P = g.P;
    if (G.won) { st.done = true; L(`VENCEU. tempo ${G.t.toFixed(0)}s, mortes ${st.deaths}, travadas ${st.stuck}`); return; }
    if (G.dead) { g.keys.KeyW = false; if (!st.deadAt) { st.deadAt = performance.now(); st.deaths++; L(`MORREU em ${P.zone && P.zone.name} (${g.C.state}) carregando ${P.carried.length}`); } if (performance.now() - st.deadAt > 1500) { st.deadAt = 0; g.respawn(); } return; }
    if (P.hidden) { g.interact(); return; }
    if (G.readingNote) { document.dispatchEvent(new KeyboardEvent('keydown', { code: 'Escape' })); }
    const gl = goal(); const [px, py] = g.tileOf(P.pos); const key = gl.k + gl.x + ',' + gl.y;
    // chegou?
    const d = g.tc(gl.x, gl.y).distanceTo(P.pos);
    if (gl.k === 'fuse' && d < 1.6) { g.pickFuse(gl.f); st.fuseT.push(G.t); L(`pegou fusível em ${gl.f.where}`); st.path = []; return; }
    if (gl.k === 'panel' && d < 1.8) { const n = G.inserted.length; g.usePanel(); L(`instalou -> ${G.inserted.length}/4`); if (G.inserted.length === n) L('BUG: painel não aceitou'); return; }
    if (gl.k === 'exit' && d < 1.8) { g.win(); return; }
    // caminho
    if (key !== st.goalKey || G.t - st.pathT > 1.5 || !st.path.length) { st.goalKey = key; st.pathT = G.t; st.path = g.astar(px, py, gl.x, gl.y, null, true) || []; if (!st.path.length && d > 2) L(`SEM CAMINHO de ${px},${py} para ${gl.k} ${gl.x},${gl.y}`); }
    while (st.path.length && g.tc(...st.path[0]).distanceTo(P.pos) < .45) st.path.shift();
    const nx = st.path[0]; if (!nx) { g.keys.KeyW = false; return; }
    if (g.grid[nx[1]][nx[0]] === 'D' && g.tc(...nx).distanceTo(P.pos) < 2.6) { const door = g.DOORS.find(o => o.x === nx[0] && o.y === nx[1]); if (door) { g.toggleDoor(door, true); L(`abriu porta ${nx}`); } }
    const tgt = g.tc(...nx); const dx = tgt.x - P.pos.x, dz = tgt.z - P.pos.z; P.yaw = Math.atan2(-dx, -dz) + (unstuck > 0 ? 1.2 : 0); P.pitch = 0;
    g.keys.KeyW = true; const danger = g.C.state === 'chase' && g.C.pos.distanceTo(P.pos) < 14; g.keys.ShiftLeft = danger || !!G.fuga; P.crouch = false;
    if (G.fuga) { for (const o of g.OBST) if (o.crouch && o.landed && o.at.distanceTo(P.pos) < 2.2) P.crouch = true; }
    // detecção de travada
    if (!st.lastPos || st.lastPos.distanceTo(P.pos) > .4) { st.lastPos = P.pos.clone(); st.lastMoveT = G.t; }
    else if (G.t - st.lastMoveT > 3) { st.stuck++; L(`TRAVOU em tile ${px},${py} (${P.zone && P.zone.name}) indo para ${gl.k} ${gl.x},${gl.y}${G.fuga ? ' [FUGA]' : ''}`); st.lastMoveT = G.t; unstuck = 1; setTimeout(() => unstuck = 0, 700); st.path = []; }
  }
  setInterval(tick, 50);
})();
