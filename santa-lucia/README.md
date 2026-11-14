# Santa Lúcia — protótipo de terror

Protótipo jogável em primeira pessoa (Three.js, sem build). O design completo está em [`DESIGN.md`](DESIGN.md).

## Como jogar
O jogo precisa de um servidor local, porque módulos ES não carregam via `file://`:

```bash
cd santa-lucia
python3 -m http.server 8000
# abra http://localhost:8000
```
Use Chrome/Edge/Firefox de desktop, com **fones de ouvido**. `?debug` na URL mostra o estado da IA.

## Controles
WASD andar · Mouse olhar · Shift correr · C agachar · F lanterna · **Botão direito / Q** feixe concentrado (mire no rosto) · E interagir/esconder · Espaço (escondido) prender a respiração.

## Balanceamento
Todos os números ficam no objeto `CFG` no topo de `game.js`.
