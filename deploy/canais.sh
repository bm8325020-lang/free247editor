#!/data/data/com.termux/files/usr/bin/bash
# Fábrica de Clipes: ver, adicionar (Twitch ou YouTube), remover e ordenar os canais que o bot vigia (Termux).
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
    print("\nO bot grava uma live por vez. Se duas estiverem ao vivo, grava a de cima.")
    print("Legendas nos clipes: " + ("LIGADAS" if d.get("legendas", True) else "DESLIGADAS"))
    op = ask("\n[a] adicionar  [r] remover  [p] prioridade  [l] legendas  [s] sair: ").lower()
    if op.startswith("a"):
        print("Twitch: o nome (alanzoka) ou o link twitch.tv/alanzoka")
        print("YouTube: o @ do canal (@alanzoka) ou o link youtube.com/@alanzoka")
        nomes = ask("Canal(is), separados por espaço: ")
        padrao = canais[0].get("autorizacao", "") if canais else ""
        aut = ask(f"De onde vem a autorização [{padrao}]: ") or padrao
        if not aut:
            print("Sem autorização o bot não vigia o canal (clipar sem permissão dá strike).")
            continue
        existentes = {(c.get("plataforma", "twitch"), c["canal"].lower()) for c in canais}
        novo_youtube = False
        for n in nomes.replace(",", " ").split():
            n = n.strip().strip("/")
            yt = re.match(r"^(?:https?://)?(?:www\.|m\.)?youtube\.com/(@[\w.-]{3,40})", n, re.I)
            if yt or n.startswith("@"):
                handle = yt.group(1) if yt else n
                if not re.fullmatch(r"@[\w.-]{3,40}", handle):
                    print(f"  ignorado: '{n}' não parece um canal do YouTube")
                    continue
                if ("youtube", handle.lower()) in existentes:
                    print(f"  {handle} (YouTube) já está na lista")
                    continue
                canais.append({"plataforma": "youtube", "canal": handle, "url": f"https://www.youtube.com/{handle}/live",
                               "autorizacao": aut, "ativo": True, "creditos": handle})
                existentes.add(("youtube", handle.lower()))
                novo_youtube = True
                print(f"  + {handle}  [youtube]")
                continue
            n = re.sub(r"^(https?://)?(www\.|m\.)?twitch\.tv/", "", n.lower())
            if not re.fullmatch(r"[a-z0-9_]{3,25}", n):
                print(f"  ignorado: '{n}' não parece um nome da Twitch")
                continue
            if ("twitch", n) in existentes:
                print(f"  {n} já está na lista")
                continue
            canais.append({"plataforma": "twitch", "canal": n, "autorizacao": aut, "ativo": True,
                           "creditos": "@" + n})
            existentes.add(("twitch", n))
            print(f"  + {n}  [twitch]")
        d["canais"] = canais
        save(d)
        if novo_youtube:
            try:
                import chat_downloader  # noqa: F401
            except ImportError:
                print("  instalando o leitor do chat do YouTube (uma vez só)…")
                import subprocess, sys
                subprocess.run([sys.executable, "-m", "pip", "install", "-q", "chat-downloader"])
    elif op.startswith("p"):
        num = ask("Número do canal que vai para o topo (prioridade): ")
        if num.isdigit() and 1 <= int(num) <= len(canais):
            canais.insert(0, canais.pop(int(num) - 1))
            d["canais"] = canais
            save(d)
            print(f"  {canais[0]['canal']} agora é o primeiro")
        else:
            print("  número inválido")
    elif op.startswith("l"):
        d["legendas"] = not d.get("legendas", True)
        save(d)
        print("  legendas " + ("ligadas" if d["legendas"] else "desligadas"))
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
