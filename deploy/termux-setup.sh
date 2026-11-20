#!/data/data/com.termux/files/usr/bin/bash
# Fábrica de Clipes: o bot de lives rodando no próprio celular (Termux), sem precisar da tela.
# Instale o Termux pelo F-Droid ou pelo GitHub (não pela Play Store, que tem uma versão velha) e rode:
#   curl -fsSL https://raw.githubusercontent.com/bm8325020-lang/free247editor/claude/clip-platform-tools-w2g52z/deploy/termux-setup.sh | bash
set -e
APP="$HOME/clipador"
BRANCH="claude/clip-platform-tools-w2g52z"
say() { printf '\n\033[1;33m%s\033[0m\n' "$1"; }

say "1/4 Instalando os programas (demora uns 10 a 20 minutos na primeira vez)…"
yes | pkg update -y >/dev/null 2>&1 || true
pkg install -y python python-numpy ffmpeg git clang libxml2 libxslt termux-api >/dev/null
# OpenCV acha a webcam na live. Ele fica no repositório extra (x11-repo); o nome do pacote já mudou entre versões.
pkg install -y x11-repo >/dev/null 2>&1 || true
yes | pkg update -y >/dev/null 2>&1 || true
for p in python-opencv-python opencv-python python-opencv; do
  python -c "import cv2" 2>/dev/null && break
  pkg install -y "$p" >/dev/null 2>&1 || true
done
if python -c "import cv2" 2>/dev/null; then echo "OpenCV ok: o clipe sai com a webcam separada."
else echo "Sem OpenCV neste celular: o bot funciona igual, mas o clipe sai com a gameplay no meio (sem webcam separada)."; fi
pip install --upgrade streamlink websocket-client chat-downloader >/dev/null 2>&1 || true

say "2/4 Baixando o bot…"
# a internet do celular oscila: tenta algumas vezes antes de desistir
for i in 1 2 3 4 5; do
  if [ -d "$APP/.git" ]; then git -C "$APP" pull --ff-only -q && break
  else git clone -q --depth 1 -b "$BRANCH" https://github.com/bm8325020-lang/free247editor.git "$APP" && break; fi
  echo "Sem internet (tentativa $i de 5). Tentando de novo em 15 s…"; sleep 15
done
[ -d "$APP/.git" ] || { echo "ERRO: sem internet para baixar o bot. Confira o Wi-Fi e rode o comando de novo."; exit 1; }
python -c "import streamlink" 2>/dev/null || pip install --upgrade streamlink websocket-client >/dev/null || {
  echo "ERRO: não consegui instalar o streamlink (internet?). Rode o comando de novo."; exit 1; }
cd "$APP"

say "3/4 Configuração"
# já configurado antes (atualização): mantém tudo, inclusive o código que o app já conhece
KEEP=n
if [ -f live-canais.json ] && [ -f .env ] && grep -q '^APP_CODE=' .env; then
  read -r -p "Manter a configuração atual (canais, chave e código)? [S/n]: " KEEP </dev/tty
  case "${KEEP:-s}" in [nN]*) KEEP=n ;; *) KEEP=s ;; esac
fi
if [ "$KEEP" = s ]; then
  CODE=$(grep '^APP_CODE=' .env | cut -d= -f2-)
  # versões antigas gravavam em 720p: passa para a melhor qualidade da live
  python -c "import json; p='live-canais.json'; d=json.load(open(p)); d['qualidade']='best'; json.dump(d, open(p, 'w'), ensure_ascii=False, indent=2)"
  echo "Configuração mantida."
else
echo "(Enter aceita o que está entre colchetes)"
# as perguntas leem do teclado mesmo quando o script veio pelo "curl | bash"
ask() { local v; read -r -p "$1 [$2]: " v </dev/tty; echo "${v:-$2}"; }
CANAIS=$(ask "Canais da Twitch, separados por espaço" "alanzoka loud_coringa paulinholokobr")
AUTORIZACAO=$(ask "De onde vem a autorização" "Grupo de clipadores autorizados pelos streamers (permissão coletiva)")
HASHTAGS=$(ask "Hashtags" "#gtarp #gta6 #cortes #clips")
read -r -s -p "Chave do Groq (gsk_..., para as legendas; não aparece na tela): " GROQ </dev/tty; echo
read -r -s -p "Chave da Coarena (coa_..., a IA que descarta momentos fracos; Enter pula): " COARENA </dev/tty; echo
# a senha entre o bot e o app: gerada aqui, só vale dentro deste celular
CODE="cel-$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"

CANAIS="$CANAIS" AUTORIZACAO="$AUTORIZACAO" HASHTAGS="$HASHTAGS" python - <<'PY'
import json, os
canais = [c.strip().lstrip("@").lower() for c in os.environ["CANAIS"].replace(",", " ").split() if c.strip()]
json.dump({"canais": [{"plataforma": "twitch", "canal": c, "autorizacao": os.environ["AUTORIZACAO"], "ativo": True,
                       "creditos": "@" + c} for c in canais],
           "hashtags": os.environ["HASHTAGS"],
           # celular: uma live por vez, na melhor qualidade da live (use Wi-Fi: ~3 GB por hora)
           "max_simultaneos": 1, "qualidade": "best"},
          open("live-canais.json", "w"), ensure_ascii=False, indent=2)
PY
printf 'GROQ_API_KEY=%s\nCOARENA_API_KEY=%s\nAPP_CODE=%s\nAPP_PORT=8080\nAPP_HOST=127.0.0.1\n' "$GROQ" "$COARENA" "$CODE" > .env
chmod 600 .env
fi

# atalho para ligar: segura o celular acordado, pega as correções novas, limpa clipes com mais de 2 dias
cat > "$HOME/ligar-clipador.sh" <<'SH'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock 2>/dev/null || true
cd "$HOME/clipador"
git pull --ff-only -q 2>/dev/null || true
find outbox/live -name '*.mp4' -mtime +2 -delete 2>/dev/null || true
echo "Clipador ligado. Deixe o Termux aberto em segundo plano (pode apagar a tela). Para parar: Ctrl+C."
python live.py
SH
chmod +x "$HOME/ligar-clipador.sh"
# atalhos: "clipador" liga o bot, "canais" muda os canais vigiados
ln -sf "$HOME/ligar-clipador.sh" "$PREFIX/bin/clipador" 2>/dev/null || true
printf '#!/data/data/com.termux/files/usr/bin/bash\nexec bash "$HOME/clipador/deploy/canais.sh"\n' > "$PREFIX/bin/canais" 2>/dev/null \
  && chmod +x "$PREFIX/bin/canais" || true

say "4/4 Pronto!"
# abre o app já com o endereço e o código: é só confirmar
termux-open-url "fabricadeclipes://servidor?addr=127.0.0.1&code=$CODE" 2>/dev/null \
  || am start -a android.intent.action.VIEW -d "fabricadeclipes://servidor?addr=127.0.0.1&code=$CODE" >/dev/null 2>&1 || true
cat <<TXT
O app Fábrica de Clipes vai abrir pedindo para conectar: toque em Conectar.
(Se não abrir: Configurações → Servidor do bot de lives → endereço 127.0.0.1, código $CODE)

Para ligar o bot (agora e sempre que reiniciar o celular):
   ~/ligar-clipador.sh

Importante: deixe o celular no carregador e no Wi-Fi, e tire o Termux da economia de bateria
(Configurações do Android → Apps → Termux → Bateria → Sem restrições).
TXT
