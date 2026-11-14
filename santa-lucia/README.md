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

## Android (APK)
`santa-lucia.apk` vem pronto. Para instalar, envie o arquivo ao celular, abra-o e permita "instalar apps desconhecidos". Se já houver uma versão instalada assinada com outra chave, desinstale-a antes.
Joga offline, em paisagem e tela cheia. O botão Voltar pausa o jogo.

Para gerar de novo após mudar o jogo:
```bash
ANDROID_SDK=/caminho/do/sdk ./android/build-apk.sh
```
Requer JDK, `platforms;android-34` e `build-tools;34.0.0`. Assina com `android/debug.keystore` (chave de **teste**, senha `android`). Para publicar na Play Store, gere uma chave própria e passe `KEYSTORE=...`.

## Trailer
`trailer/` gera o trailer a partir do jogo real, renderizado quadro a quadro:
```bash
python3 -m http.server 8765 &                                   # dentro de santa-lucia/
node trailer/capture.js "http://localhost:8765/?trailer" frames 30   # quadros JPEG (Playwright)
python3 trailer/soundtrack.py trilha.wav                         # trilha sintetizada (numpy)
ffmpeg -framerate 30 -i frames/f%05d.jpg -i trilha.wav -c:v libx264 -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest trailer.mp4
```
Os planos de câmera ficam em `trailer/director.js`.
