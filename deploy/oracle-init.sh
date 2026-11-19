#!/bin/bash
# Fábrica de Clipes: bot de lives na Oracle Cloud (Ubuntu). Cole este texto inteiro em
# "Script de inicialização" (cloud-init) ao criar a máquina. Ele instala tudo e liga o bot sozinho.
#
# ===================== PREENCHA SÓ ESTA PARTE =====================
CANAL="nome_do_canal"                 # canal da Twitch, só o nome (o que vem depois de twitch.tv/)
AUTORIZACAO="de onde vem a permissão"  # ex.: "campanha de clipping X" ou "regra de clipping do streamer"
CREDITOS="@nome_do_streamer"           # vai na legenda: "Créditos: @..."
HASHTAGS="#cortes #clips"
APP_CODE="invente_um_codigo_secreto"     # mínimo 8 caracteres; o mesmo vai no app (Configurações → Servidor)
TELEGRAM_BOT_TOKEN=""                  # opcional: os clipes também chegam no Telegram
GROQ_API_KEY=""                        # opcional, mas recomendado (grátis em console.groq.com/keys)
LIMIAR="3.0"                           # menor = mais clipes
# ==================================================================

exec > /var/log/fabrica-setup.log 2>&1
set -eux
export DEBIAN_FRONTEND=noninteractive
APP=/home/clipper/app

apt-get update
apt-get install -y python3-venv python3-pip ffmpeg git
id clipper || useradd -m -s /bin/bash clipper

# 2 GB de memória extra em disco, para a máquina não travar ao montar um clipe
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

[ -d "$APP" ] || git clone --depth 1 -b claude/clip-platform-tools-w2g52z \
  https://github.com/bm8325020-lang/free247editor.git "$APP"
cd "$APP"
python3 -m venv .venv
.venv/bin/pip install --upgrade pip
.venv/bin/pip install -r tools/clipper/requirements.txt

CANAL="$CANAL" AUTORIZACAO="$AUTORIZACAO" CREDITOS="$CREDITOS" HASHTAGS="$HASHTAGS" LIMIAR="$LIMIAR" \
python3 - <<'PY'
import json, os
json.dump({"canais": [{"plataforma": "twitch", "canal": os.environ["CANAL"].strip().lstrip("@").lower(),
                       "autorizacao": os.environ["AUTORIZACAO"], "ativo": True}],
           "creditos": os.environ["CREDITOS"], "hashtags": os.environ["HASHTAGS"],
           "limiar": float(os.environ["LIMIAR"])},
          open("live-canais.json", "w"), ensure_ascii=False, indent=2)
PY
set +x  # as chaves não vão para o log
printf 'TELEGRAM_BOT_TOKEN=%s\nGROQ_API_KEY=%s\nAPP_CODE=%s\nAPP_PORT=8080\n' "$TELEGRAM_BOT_TOKEN" "$GROQ_API_KEY" "$APP_CODE" > .env
set -x
chmod 600 .env
chown -R clipper:clipper /home/clipper

cat > /etc/systemd/system/fabrica-live.service <<UNIT
[Unit]
Description=Fabrica de Clipes - bot de lives
After=network-online.target
Wants=network-online.target

[Service]
User=clipper
WorkingDirectory=$APP
Environment=PATH=$APP/.venv/bin:/usr/local/bin:/usr/bin:/bin
Environment=PYTHONUNBUFFERED=1
# pega as correções novas do código a cada reinício (se falhar, segue com o que já tem)
ExecStartPre=-/usr/bin/git -C $APP pull --ff-only
ExecStart=$APP/.venv/bin/python live.py
Restart=always
RestartSec=30

[Install]
WantedBy=multi-user.target
UNIT

# the app fetches the clips on port 8080: open it in this machine's firewall (Oracle's Ubuntu blocks it by default)
iptables -I INPUT -p tcp --dport 8080 -j ACCEPT
(command -v netfilter-persistent >/dev/null && netfilter-persistent save) || true

systemctl daemon-reload
systemctl enable --now fabrica-live
echo "PRONTO: bot ligado"
