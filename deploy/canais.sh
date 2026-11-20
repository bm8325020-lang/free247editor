#!/data/data/com.termux/files/usr/bin/bash
# Fábrica de Clipes: ver, adicionar e remover os canais que o bot vigia (Termux).
#   bash $HOME/clipador/deploy/canais.sh     (ou só: canais)
# Depois de mudar, reinicie o bot: CTRL+C e  bash $HOME/ligar-clipador.sh
cd "$HOME/clipador" 2>/dev/null || { echo "Clipador não instalado. Rode o instalador primeiro."; exit 1; }
[ -f live-canais.json ] || { echo "Sem live-canais.json. Rode o instalador primeiro."; exit 1; }

python - <<'PY'
import json, re

PATH = "live-canais.json"

def load():
    return json.load(open(PATH, encoding="utf-8"))

def save(d):
    json.dump(d, open(PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

def ask(msg):
    try:
        with open("/dev/tty") as tty:
            print(msg, end="", flush=True)
            return tty.readline().strip()
    except OSError:
        return input(msg).strip()

while True:
    d = load()
    canais = d.get("canais", [])
    print("\n=== Canais que o bot vigia ===")
    if not canais:
        print("(nenhum)")
    for i, c in enumerate(canais, 1):
        estado = "" if c.get("ativo", True) else "  (pausado)"
        print(f"{i:2}. {c['canal']}  [{c.get('plataforma', 'twitch')}]{estado}")
    print("\nO bot grava uma live por vez: a primeira que entrar ao vivo.")
    op = ask("\n[a] adicionar   [r] remover   [s] sair: ").lower()
    if op.startswith("a"):
        nomes = ask("Nome(s) na Twitch (o que vem depois de twitch.tv/), separados por espaço: ")
        padrao = canais[0].get("autorizacao", "") if canais else ""
        aut = ask(f"De onde vem a autorização [{padrao}]: ") or padrao
        if not aut:
            print("Sem autorização o bot não vigia o canal (clipar sem permissão dá strike).")
            continue
        existentes = {c["canal"] for c in canais}
        for n in nomes.replace(",", " ").split():
            n = n.strip().lstrip("@").lower()
            n = re.sub(r"^https?://(www\.)?twitch\.tv/", "", n).strip("/")
            if not re.fullmatch(r"[a-z0-9_]{3,25}", n):
                print(f"  ignorado: '{n}' não parece um nome da Twitch")
                continue
            if n in existentes:
                print(f"  {n} já está na lista")
                continue
            canais.append({"plataforma": "twitch", "canal": n, "autorizacao": aut, "ativo": True,
                           "creditos": "@" + n})
            existentes.add(n)
            print(f"  + {n}")
        d["canais"] = canais
        save(d)
    elif op.startswith("r"):
        num = ask("Número do canal para remover: ")
        if num.isdigit() and 1 <= int(num) <= len(canais):
            tirado = canais.pop(int(num) - 1)
            d["canais"] = canais
            save(d)
            print(f"  - {tirado['canal']}")
        else:
            print("  número inválido")
    else:
        break

print("\nPronto. Para valer, reinicie o bot: CTRL+C e depois  bash $HOME/ligar-clipador.sh")
PY
