# Fábrica de clipes (foco em jogos)

Transforma uma live ou um gameplay longo em vários clipes verticais (TikTok, Reels, Shorts), prontos para postar.
Nada é publicado: os arquivos ficam na sua máquina.

## No celular (APK)

Instale o `FabricaDeClipes.apk` (permita "instalar apps desconhecidos" quando o Android pedir). Depois:

1. Abra o app e toque em **Escolher vídeo**. Ou, na galeria ou em qualquer app, toque em **Compartilhar →
   Fábrica de Clipes**.
2. (Recomendado) Cole a chave grátis do Groq em **Chaves**. Sem ela, os clipes saem sem legenda e são escolhidos
   só pelo áudio.
3. Toque em **CRIAR CLIPES**. Pode bloquear a tela: o progresso fica na notificação.
4. Os clipes aparecem no app e na galeria, pasta `Movies/Clipes`.
5. **Postar com um toque:** cada clipe tem botões **TikTok, Shorts, Reels, Kwai e Outros**. O botão copia a legenda
   pronta (título + "Créditos: @streamer" + hashtags, configurados em **4. Postagem**) e abre o app com o vídeo
   carregado: é só colar a legenda e publicar. Postar 100% automático exige a API oficial de cada rede; a do
   TikTok só publica vídeos privados até o app passar por uma auditoria deles.

O código do app está em `android/`.

## No computador

### Instalar (uma vez)

Precisa de **Python 3.10+** e **FFmpeg**.

```
python clip.py --setup
```

Se faltar o FFmpeg, o comando mostra como instalar (Windows: `winget install Gyan.FFmpeg`, Mac: `brew install ffmpeg`).

### Usar

| Quero... | Comando |
| --- | --- |
| Clipar os vídeos que coloquei em `streams/` | `python clip.py` |
| Clipar um arquivo | `python clip.py "C:\lives\live-sabado.mp4"` |
| Clipar um link (VOD da Twitch, YouTube, Kick...) | `python clip.py https://www.twitch.tv/videos/123456789` |
| Deixar rodando sozinho: tudo que cair em `streams/` vira clipe | `python clip.py --watch` |
| Mais clipes / outra duração | `python clip.py --clips 15 --min 20 --max 60` |
| Vídeo de conversa ou podcast (não jogo) | `python clip.py --mode talk` |

O resultado sai em `outbox/<nome-do-video>-<data>/`:

- `clip-01-....mp4`, `clip-02-....mp4`, ... (do melhor para o pior), em 1080x1920 e 30 fps
- uma capa `.jpg` e um `.txt` (título e transcrição) para cada clipe
- `index.html`: abra no navegador para assistir e baixar todos os clipes numa página só

Depois de processado, o vídeo original vai para `streams/done/`.

## Bot de lives (Twitch e YouTube)

Fica ligado no PC (ou num servidor) e clipa sozinho os melhores momentos das lives **que você tem autorização
para clipar**. Clipar sem permissão gera strike de direitos autorais e pode derrubar o seu canal. Use campanhas
de clipping, regras de clipping publicadas pelo streamer ou acordo por escrito.

1. Copie `live-canais.example.json` para `live-canais.json` e liste os canais. Cada um **precisa** do campo
   `autorizacao` (de onde vem a permissão); sem ele, o bot recusa o canal.
2. `python clip.py --setup` (uma vez) e depois `python live.py`. Deixe rodando; Ctrl+C para parar.
3. Os clipes saem em `outbox/live/<canal>/<data>/`, com `index.html` para revisar.

Como ele decide:
- **Grava em loop** só os últimos 3 minutos de cada live (não lota o disco).
- **Lê o chat** sem login e compara com o normal daquele canal: explosão de mensagens, KEKW, LUL, kkkk, "clipa",
  "CLIP IT"... O chat é o sinal principal: é o público dizendo que foi bom.
- **Ouve o áudio** (grito, risada, explosão) comparado com o ritmo da própria live.
- Só dispara depois de 2 minutos de live (para conhecer o "normal") e respeita um intervalo entre clipes.
- O chat digita **depois** que a cena acontece. Por isso, quando ele dispara, o bot acha o segundo em que o chat
  **começou** a reagir e volta mais `volta_segundos` (padrão 3) a partir dali: esse é o início da cena.
- Corta cerca de 20 s antes da cena e vai até 10 s depois da reação, sem cortar frase no meio, com webcam em
  cima, legenda e gancho.

Ajustes em `live-canais.json`: `limiar` (menor = mais clipes; padrão 3), `volta_segundos` (quanto voltar antes
do chat reagir; padrão 3), `antes`, `depois`, `intervalo_minimo`,
`buffer_segundos`, `qualidade`.

**Clipe oficial da Twitch (opcional):** com `"clip_oficial_twitch": true` e, no `.env`, `TWITCH_CLIENT_ID` e
`TWITCH_USER_TOKEN` (token da sua conta com a permissão `clips:edit`, criado em dev.twitch.tv), o bot também cria o
clipe oficial na Twitch no mesmo instante e mostra o link.

Precisa de um computador ligado o tempo todo. No celular não funciona: o Android encerra apps em segundo plano.

## O que ele faz sozinho

1. **Ouve a live inteira** e marca onde o áudio explode em relação ao próprio ritmo da live: gritos, risadas,
   explosões, a hora em que você perde a cabeça. Início e fim (intro e "tchau, chat") ficam com peso menor.
2. **Transcreve só os trechos candidatos**, não a live inteira. Por isso uma live de 4 horas não leva 4 horas.
3. **Dá nota pelo que foi dito**: palavras de hype em português, inglês e espanhol ("não acredito", "meu deus",
   "let's go", "no way", "clipa isso"...), risadas (kkkk, haha), palavras repetidas ("vai vai vai") e fala acelerada.
4. **Escolhe os melhores sem sobreposição** e ajusta o começo e o fim para não cortar no meio de uma frase.
   Cerca de 70% do clipe fica antes do pico (o contexto) e 30% depois (a reação).
5. **Monta o vertical**:
   - **Com webcam** (formato TikTok/Shorts, padrão): a **webcam inteira em cima, na largura toda**, e o gameplay
     embaixo. Detecta se a webcam é quadrada/retangular ou redonda; nada do jogo entra no painel da webcam.
     Para ter certeza de que é uma webcam, ele exige rosto parado **e** uma borda fixa em volta: personagem de
     jogo parado numa cena (GTA, por exemplo) não vira webcam. Webcam pequena no canto: `--layout pip`.
   - **Sem webcam**: gameplay ampliado no meio, com fundo desfocado em cima e embaixo.
   - **Câmera grande** (just chatting): segue o rosto de quem está falando.
6. **Legenda palavra por palavra** com a palavra atual destacada, e um **título de gancho** nos primeiros 3 segundos.
7. **Normaliza o volume** (-14 LUFS, o padrão das plataformas).

## Deixar melhor (opcional, mas recomendado)

Crie um arquivo `.env` na pasta (copie o `.env.example`) e adicione:

- `GROQ_API_KEY=...`: transcrição grátis, rápida e melhor em português (chave em console.groq.com/keys).
  Sem ela, a transcrição roda no seu computador. Funciona, mas é mais lenta sem placa NVIDIA, e o modelo
  (~1,6 GB) é baixado na primeira vez.
- `ANTHROPIC_API_KEY=...`: o Claude passa a escolher os momentos e a escrever os títulos (é pago por uso, centavos
  por live). Sem ela, um seletor automático escolhe e os títulos vêm das próprias falas no pico.

## Ajustes fixos

Copie `clipper.config.example.json` para `clipper.config.json` e mude o que quiser: cor da legenda, tamanho,
quantidade de clipes, idioma, layout (`split`, `game`, `follow`), altura da webcam (`cam_height`), zoom do
gameplay (`game_zoom`) e mais. Todas as opções estão em `tools/clipper/config.py`.

## Limites honestos

- Ele detecta momentos pelo **áudio e pelas falas**. Uma jogada incrível feita em silêncio, sem reação nenhuma,
  passa despercebida. Ele não "assiste" o jogo.
- Não lê o chat da Twitch, que seria um ótimo sinal de hype.
- A webcam precisa mostrar um rosto em boa parte da live para ser detectada. Webcam com filtro pesado, máscara
  ou VTuber cai no layout sem webcam. Nesse caso force com `--layout split` (usa a área do rosto) ou mude o layout.
- Clipe só o seu conteúdo ou o de quem te autorizou.
