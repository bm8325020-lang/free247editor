# Fábrica de clipes (foco em jogos)

Transforma uma live ou um gameplay longo em vários clipes verticais (TikTok, Reels, Shorts), prontos para postar.
Nada é publicado: os arquivos ficam na sua máquina.

## No celular (APK)

Instale o `FabricaDeClipes.apk` (permita "instalar apps desconhecidos" quando o Android pedir). O app tem estas telas:

1. **Início:** toque em **Novo clipe** (ou, na galeria, **Compartilhar → Fábrica de Clipes**). Embaixo ficam seus
   projetos. Segure um projeto para apagar.
2. **Novo clipe:** quantos clipes (1 a 15), duração máxima, estilo da legenda (Amarelo TikTok, Branco forte,
   Karaokê, Minimalista) e layout (Automático = webcam em cima quando houver webcam). A chave **Escolher os momentos
   antes de montar** vem desligada: o app escolhe sozinho. Ligada, ele para depois de achar os momentos.
3. **Processando:** etapas reais e as miniaturas dos momentos. Pode bloquear a tela: o progresso fica na notificação.
4. **Escolher momentos** (só se a chave estiver ligada): os melhores já vêm marcados. Marque ou desmarque e toque em
   **Montar N clipes**.
5. **Resultados:** os clipes em tela cheia, um por vez, arrastando para cima como no TikTok. Botões:
   - **Postar:** copia a legenda pronta (título + "Créditos: @streamer" + hashtags) e abre TikTok, Shorts, Reels,
     Kwai ou outro app com o vídeo carregado. Você cola e publica. Nada é postado sozinho.
   - **Editar:** muda o início e o fim (com tira de quadros e prévia), o título, o estilo da legenda e o layout.
     **Aplicar** monta de novo só aquele clipe.
   - **Apagar:** tira o clipe do app e da galeria.
6. **Live gravada:** em Novo clipe, toque em **Live gravada**. Não tem limite de clipes: cada momento forte vira
   um clipe (até 50), com a webcam no canto. Escolha quão exigente: **Mais clipes**, **Equilibrado** ou **Só os
   insanos**. O arquivo baixado não traz o chat, então contam o áudio e o que foi falado. Uma live de horas demora
   (o celular precisa ouvir tudo); deixe carregando.
   Para o YouTube: baixe a live gravada (ex.: SnapTube) → Compartilhar → Fábrica de Clipes → Live gravada.
7. **Configurações** (engrenagem): chaves do Groq, da Coarena e da Anthropic com botão de testar, créditos, hashtags e os
   padrões de um clipe novo.

**IA grátis para escolher os momentos (Coarena):** crie uma chave grátis em coarena.ai (página da API) e cole em
Configurações → **Chave da Coarena**. Ela escolhe os melhores momentos e escreve os títulos, sem pagar (usa os
modelos grátis do dia: GPT-6 Luna, DeepSeek e Gemini, nessa ordem, se um falhar). Se você tiver também a chave da
Anthropic, o app usa a Anthropic primeiro.

Os clipes também ficam na galeria, pasta `Movies/Clipes`.

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

## Bot de lives na nuvem, só pelo celular (Oracle + Telegram)

O bot roda num computador da Oracle que fica ligado 24 h (plano grátis) e manda cada clipe para o seu Telegram,
com a legenda pronta. Você não precisa de PC. Funciona com **Twitch**; o YouTube costuma bloquear servidores.

**1. Crie o bot do Telegram (2 minutos)**
1. No Telegram, abra **@BotFather** e mande `/newbot`.
2. Dê um nome (ex.: Meus Cortes) e um usuário terminado em `bot` (ex.: meuscortes_bot).
3. Ele responde com um **token** (algo como `123456:ABC-...`). Copie. Não mostre para ninguém.

**2. Prepare o script**
1. Abra o arquivo `deploy/oracle-init.sh` (no GitHub, botão "Raw") e copie tudo.
2. Cole num app de notas e preencha só o bloco "PREENCHA": canal da Twitch, de onde vem a autorização, créditos,
   hashtags, o token do Telegram e (recomendado) a chave grátis do Groq.

**3. Crie a máquina na Oracle** (no Chrome do celular, ative "Site para computador")
1. Crie a conta em oracle.com/cloud/free (pede cartão só para verificação).
2. Menu → Compute → Instances → **Create instance**.
3. Imagem: **Canonical Ubuntu**. Shape: **Ampere** (VM.Standard.A1.Flex), 2 OCPUs e 12 GB.
   Se der "out of capacity", tente outro "Availability domain" ou outro horário.
4. Em **Show advanced options → Management**, escolha **Paste cloud-init script** e cole o script preenchido.
5. **Create**. Em uns 15 minutos o bot está instalado e ligado.

**4. Ligue o Telegram**
Abra o seu bot no Telegram e mande `/start`. Ele responde "Pronto!". Daí em diante:
- quando a live começar: "🔴 entrou ao vivo";
- a cada momento bom: o **vídeo vertical** + a legenda. Segure o vídeo → Salvar, copie o texto e poste;
- `/status` mostra o que ele está vigiando.

O bot religa sozinho se a máquina reiniciar, e a cada reinício baixa as correções novas do código.

## O que ele faz sozinho

1. **Ouve a live inteira** e marca onde o áudio explode em relação ao próprio ritmo da live: gritos, risadas,
   explosões, a hora em que você perde a cabeça. Início e fim (intro e "tchau, chat") ficam com peso menor.
2. **Transcreve só os trechos candidatos**, não a live inteira. Por isso uma live de 4 horas não leva 4 horas.
3. **Dá nota pelo que foi dito**: palavras de hype em português, inglês e espanhol ("não acredito", "meu deus",
   "let's go", "no way", "clipa isso"...), risadas (kkkk, haha), palavras repetidas ("vai vai vai") e fala acelerada.
4. **Escolhe os melhores sem sobreposição** e ajusta o começo e o fim para não cortar no meio de uma frase.
   Cerca de 70% do clipe fica antes do pico (o contexto) e 30% depois (a reação).
5. **Monta o vertical**:
   - **Com webcam (vídeos, padrão):** a **webcam em cima**, ocupando o espaço de cima; o **gameplay largo** no
     meio (bem mais do jogo do que um recorte vertical); embaixo, fundo desfocado com a legenda. Se a webcam fica
     na lateral do vídeo, essa lateral sai do gameplay, para a webcam não aparecer duplicada nem cortada.
     Detecta webcam quadrada, redonda (inclusive encostada na borda da tela) e webcam sem moldura (rosto no mesmo
     lugar em 60%+ do vídeo). Personagem de jogo parado numa cena não vira webcam.
     Em cima aparece **só a webcam, em tela cheia** (nada de jogo em volta). Se a webcam **some** do vídeo
     (corte de edição, cena sem câmera), a parte de cima fica com o fundo escuro, sem jogo, e o rosto volta quando a
     webcam volta. A troca só acontece depois de 1 segundo, para não piscar quando o streamer vira a cabeça.
   - **Clipes de live (bot):** webcam pequena no canto (`pip`). Outras opções: `--layout split` (webcam em cima e
     recorte alto do jogo) e `--layout pip`.
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
