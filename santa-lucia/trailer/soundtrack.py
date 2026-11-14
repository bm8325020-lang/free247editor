# Trilha do trailer sintetizada (numpy). Sincronizada com os planos de director.js.
import numpy as np, wave, sys
SR = 44100; DUR = 53.7; N = int(SR * DUR); rng = np.random.default_rng(7)
L = np.zeros(N); R = np.zeros(N); SEND = np.zeros(N)
def tt(d): return np.arange(int(SR * d)) / SR
def env(n, a, d):  # ataque/decaimento exponencial
    t = np.arange(n) / SR; e = np.minimum(1, t / max(a, 1e-4)) * np.exp(-t / max(d, 1e-4)); return e
def add(sig, at, g=1.0, pan=0.0, verb=0.3):
    i = int(at * SR); j = min(N, i + len(sig)); s = sig[:j - i] * g
    L[i:j] += s * (1 - pan) ** .5 / 1.4142 * 1.4142 * min(1, 1 - pan); R[i:j] += s * min(1, 1 + pan); SEND[i:j] += s * verb
def lp(x, fc):  # passa-baixa 1 polo
    a = np.exp(-2 * np.pi * fc / SR); y = np.empty_like(x); acc = 0.0
    b = 1 - a
    for k in range(len(x)): acc = b * x[k] + a * acc; y[k] = acc
    return y
def lpf(x, fc):  # rápido via FFT
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR); X /= np.sqrt(1 + (f / fc) ** 4); return np.fft.irfft(X, len(x))
def bpf(x, fc, bw):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR); X *= np.exp(-((f - fc) / bw) ** 2); return np.fft.irfft(X, len(x))
def noise(d): return rng.standard_normal(int(SR * d))
def sine(f, d, f2=None):
    t = tt(d); fr = f if f2 is None else f * (f2 / f) ** (t / d); return np.sin(2 * np.pi * np.cumsum(fr) / SR)
def saw(f, d, f2=None):
    t = tt(d); fr = f if f2 is None else f * (f2 / f) ** (t / d); ph = np.cumsum(fr) / SR; return 2 * (ph % 1) - 1

# --- cama: drone dissonante com respiração lenta
def drone(a, b, g):
    d = b - a; t = tt(d); x = sum(saw(f, d) for f in (41.2, 41.9, 61.7, 87.3)) / 4
    x = lpf(x, 160) * (0.6 + 0.4 * np.sin(2 * np.pi * .07 * t)) + lpf(noise(d), 120) * .6
    fade = np.minimum(1, np.minimum(t / 1.5, (d - t) / .4)); add(x * fade, a, g, 0, .4)
drone(0, 44.4, .5); drone(46.7, 53.7, .45)
# --- impacto "braam" de trailer
def braam(at, g=1.0, d=3.2):
    x = sum(saw(f, d, f * .97) for f in (36.7, 55, 73.4, 110, 116.5)) / 5
    x = lpf(x, 700) * env(len(x), .02, d / 3.2); sub = sine(55, d, 32) * env(int(SR * d), .005, .6)
    hit = lpf(noise(.6), 900) * env(int(SR * .6), .002, .12)
    add(x * 1.1 + sub * 1.2, at, g, 0, .6); add(hit, at, g * 1.4, 0, .8)
def boom(at, g=1.0):
    add(sine(70, 2.5, 28) * env(int(SR * 2.5), .003, .7), at, g * 1.6, 0, .7); add(lpf(noise(1.5), 300) * env(int(SR * 1.5), .002, .3), at, g, 0, .9)
def step(at, g=.25, f=1300):
    x = bpf(noise(.12), f * rng.uniform(.85, 1.15), 500) * env(int(SR * .12), .002, .03); add(x, at, g, rng.uniform(-.2, .2), .25)
def heartbeat(at, g=.8):
    for dt, v in ((0, 1), (.17, .6)): add(sine(62, .25, 36) * env(int(SR * .25), .008, .07), at + dt, g * v, 0, .02)
def crackle(at, g=.12):
    for k in range(rng.integers(3, 8)): add(bpf(noise(.03), 4000, 2000) * env(int(SR * .03), .001, .008), at + k * rng.uniform(.02, .07), g, rng.uniform(-.5, .5), .2)
def breath(a, d, g=.3, pan=0, rate=.45):
    t = tt(d); x = bpf(noise(d), 520, 160); m = np.maximum(0, np.sin(2 * np.pi * rate * t)) ** 1.5; add(x * m, a, g, pan, .3)
def scream(at, g=1.0, d=1.5):
    x = saw(1400, d, 260) * .5 + saw(1950, d, 400) * .3 + bpf(noise(d), 2600, 900) * .6
    x *= env(len(x), .02, d / 2.2) * (1 + .3 * np.sin(2 * np.pi * 31 * tt(d))); add(np.tanh(x * 2) * .6, at, g, 0, .7)
def growl(a, d, g=.25):
    t = tt(d); x = np.tanh(6 * (saw(58, d) + saw(61.5, d))) ; x = lpf(x * (0.7 + .3 * np.sin(2 * np.pi * 9 * t)), 600); add(x * np.minimum(1, t / .3), a, g, 0, .3)
def riser(a, d, g=.5):
    t = tt(d); k = (t / d) ** 2; nz = noise(d); x = (lpf(nz, 300) * (1 - k) + lpf(nz, 6000) * k) * k; x += saw(110, d, 440) * .25 * (t / d) ** 2; add(x, a, g, 0, .5)
def toms(a, b, bpm, g=.7):
    k = a
    while k < b: add(sine(95, .35, 45) * env(int(SR * .35), .002, .12), k, g, 0, .4); add(lpf(noise(.1), 1500) * env(int(SR * .1), .001, .02), k, g * .5, 0, .3); k += 60 / bpm

# 0: abertura
boom(0.15, .9)
# 3.6–8.6 corredor: passos, zumbido de lâmpada, estalos
for k in range(9): step(3.9 + k * .55)
add(np.sin(2 * np.pi * 120 * tt(5)) * .03 + bpf(noise(5), 240, 40) * .05, 3.6, 1, 0, .1)
for at in (5.1, 6.9, 7.8): crackle(at)
add(sine(1900, .1, 900) * env(int(SR * .1), .001, .02), 6.2, .05, .4, .9)  # goteira
# 8.6–12.6 necrotério: tom grave crescente
add(sine(98, 4) * np.linspace(0, .15, int(SR * 4)), 8.6, 1, 0, .5)
# 14.1 pegar fusível: zap + desligamento
add(np.sign(np.sin(2 * np.pi * 60 * tt(.7))) * env(int(SR * .7), .002, .25) * .25, 14.1, 1, 0, .4); crackle(14.1, .3); crackle(14.3, .25)
add(saw(180, 1.4, 25) * env(int(SR * 1.4), .01, .6) * .35, 14.15, 1, 0, .6)
# 16.6 card
braam(16.6, .9)
# 18.4–24.4 revelação: respiração, estalos, coração acelerando, riser
breath(18.6, 5.8, .35, -.1)
for k in range(10): add(np.sign(np.sin(2 * np.pi * 1800 * tt(.012))) * .15, 19 + k * .52 + rng.uniform(0, .3), 1, .1, .3)
hb = 19.0; per = 1.0
while hb < 28.2: heartbeat(hb, .7); per = max(.38, per * .93); hb += per
riser(22.4, 2.0, .45)
# 24.4–28.4 perseguição
braam(24.4, 1.0, 2.5); toms(24.45, 28.3, 260, .6); growl(24.4, 4.0, .22)
for k in range(14): step(24.5 + k * .28, .35, 900)
# 28.4 corte seco + card
braam(28.4, .8, 2)
# 30–35 feixe: clique, chiado, GRITO, recuo
add(bpf(noise(.03), 3000, 600) * .4, 31.0, 1, .3, 0); growl(30.0, 1.8, .2)
add(bpf(noise(.8), 6000, 2500) * env(int(SR * .8), .05, .5) * .3, 31.05, 1, 0, .2)
scream(31.8, 1.2, 1.6); braam(31.8, .6, 2.0)
for k in range(8): step(32.9 + k * .22, .25, 800)
# 35–40 armário: abafado, respiração dela, seu coração
breath(35.2, 4.6, .22, 0, .35); add(lpf(noise(4.8), 200) * .08, 35.1, 1, 0, .1)
for k in range(6): heartbeat(35.4 + k * .75, .9)
add(bpf(noise(1.2), 900, 300) * np.linspace(.3, 0, int(SR * 1.2)) * .3, 37.2, 1, .2, .3)  # farejando
# 40–44.4 alarme
t = tt(4.4); siren = np.sign(np.sin(2 * np.pi * np.cumsum(620 + 180 * np.sin(2 * np.pi * 1.6 * t)) / SR)); add(lpf(siren, 1800) * .09, 40.0, 1, 0, .6)
braam(40.0, 1.0, 3); toms(40.0, 44.3, 300, .7); riser(42.4, 2.0, .5); growl(40.6, 3.4, .18)
# 44.4–45.4 silêncio com zumbido no ouvido
add(np.sin(2 * np.pi * 7800 * tt(1.0)) * np.linspace(0, .03, int(SR * 1.0)), 44.4, 1, 0, 0)
# 45.4 jumpscare
scream(45.4, 1.6, 1.0); add(np.tanh(lpf(noise(.6), 2000) * 4) * env(int(SR * .6), .001, .25) * .7, 45.4, 1, 0, .5); boom(45.4, 1.2)
# 46.7 título
boom(46.75, 1.0); braam(46.75, .7, 4.5); add(sine(1250, 2.5) * env(int(SR * 2.5), .01, .9) * .05, 50.1, 1, 0, .9); boom(50.1, .5)

# reverb: IR de ruído com decaimento (prédio de concreto)
ir = rng.standard_normal(int(SR * 2.6)) * np.exp(-np.arange(int(SR * 2.6)) / SR / .55); ir = lpf(ir, 4000); ir /= np.abs(ir).sum() ** .5 * 6
n = 1 << int(np.ceil(np.log2(N + len(ir))))
wet = np.fft.irfft(np.fft.rfft(SEND, n) * np.fft.rfft(ir, n), n)[:N]
Lo, Ro = L + wet, R + np.roll(wet, 331)
# silêncio real nos cortes secos
for a, b in ((44.4, 45.38), (46.0, 46.7)):
    i, j = int(a * SR), int(b * SR); fade = np.ones(j - i); fade[:400] = np.linspace(1, 0, 400)
    for ch in (Lo, Ro): ch[i:j] *= np.concatenate([np.linspace(1, 0, min(1200, j - i)), np.zeros(max(0, j - i - 1200))])
mix = np.stack([Lo, Ro], 1); mix = np.tanh(mix / (np.abs(mix).max() * .55)) ; mix /= np.abs(mix).max() / .95
out = sys.argv[1] if len(sys.argv) > 1 else 'trailer.wav'
with wave.open(out, 'wb') as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes((mix * 32767).astype('<i2').tobytes())
print('ok', out, DUR)
