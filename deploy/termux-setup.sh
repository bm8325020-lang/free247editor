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
# OpenCV acha a webcam na live; o nome do pacote já mudou entre versões do Termux
pkg install -y python-opencv-python >/dev/null 2>&1 || pkg install -y opencv-python >/dev/null 2>&1 || true
python -c "import cv2" 2>/dev/null || { echo "ERRO: não consegui instalar o OpenCV (pkg search opencv). Mande print disso no chat."; exit 1; }
pip install --upgrade streamlink websocket-client >/dev/null

say "2/4 Baixando o bot…"
if [ -d "$APP/.git" ]; then git -C "$APP" pull --ff-only -q; else git clone -q --depth 1 -b "$BRANCH" https://github.com/bm8325020-lang/free247editor.git "$APP"; fi
cd "$APP"

say "3/4 Configuração (Enter aceita o que está entre colchetes)"
# as perguntas leem do teclado mesmo quando o script veio pelo "curl | bash"
ask() { local v; read -r -p "$1 [$2]: " v </dev/tty; echo "${v:-$2}"; }
CANAIS=$(ask "Canais da Twitch, separados por espaço" "alanzoka loud_coringa paulinholokobr")
AUTORIZACAO=$(ask "De onde vem a autorização" "Grupo de clipadores autorizados pelos streamers (permissão coletiva)")
HASHTAGS=$(ask "Hashtags" "#gtarp #gta6 #cortes #clips")
read -r -s -p "Chave do Groq (gsk_..., para as legendas; não aparece na tela): " GROQ </dev/tty; echo
# a senha entre o bot e o app: gerada aqui, só vale dentro deste celular
CODE="cel-$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"

CANAIS="$CANAIS" AUTORIZACAO="$AUTORIZACAO" HASHTAGS="$HASHTAGS" python - <<'PY'
import json, os
canais = [c.strip().lstrip("@").lower() for c in os.environ["CANAIS"].replace(",", " ").split() if c.strip()]
json.dump({"canais": [{"plataforma": "twitch", "canal": c, "autorizacao": os.environ["AUTORIZACAO"], "ativo": True,
                       "creditos": "@" + c} for c in canais],
           "hashtags": os.environ["HASHTAGS"],
           # celular: uma live por vez, em 720p (menos internet e menos calor)
           "max_simultaneos": 1, "qualidade": "720p60,720p,best"},
          open("live-canais.json", "w"), ensure_ascii=False, indent=2)
PY
printf 'GROQ_API_KEY=%s\nAPP_CODE=%s\nAPP_PORT=8080\nAPP_HOST=127.0.0.1\n' "$GROQ" "$CODE" > .env
chmod 600 .env

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

say "4/4 Pronto!"
cat <<TXT
No app Fábrica de Clipes → Configurações → Servidor do bot de lives:
   Endereço:  127.0.0.1
   Código:    $CODE
Toque em "Conectar e buscar agora".

Para ligar o bot (agora e sempre que reiniciar o celular):
   ~/ligar-clipador.sh

Importante: deixe o celular no carregador e no Wi-Fi, e tire o Termux da economia de bateria
(Configurações do Android → Apps → Termux → Bateria → Sem restrições).
TXT
