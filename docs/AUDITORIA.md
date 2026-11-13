# Auditoria técnica (Fase A): 2026-11-13

Esta auditoria foi feita lendo o código real e rodando o que dava para rodar. Onde diz **testado**, houve execução
de verdade; onde diz **não testado**, não houve. Nada aqui é estimativa vendida como resultado.

## 1. Arquitetura atual

Não existe frontend web nem backend/servidor. São três peças locais, sem nuvem própria:

| Peça | Onde roda | Tecnologia | Tamanho |
| --- | --- | --- | --- |
| Editor de vídeo curto ("edit this") | PC, dentro de um agente de IA | `setup.mjs`, `tools/transcribe.mjs`, Herk Edit Kit (HyperFrames + GSAP) em `kit/` | herdado, não auditado a fundo |
| Fábrica de clipes para PC | PC, linha de comando | Python 3.10+, FFmpeg, OpenCV (YuNet), faster-whisper, yt-dlp, SDK Anthropic | `clip.py` + `tools/clipper/` (~1.650 linhas) |
| App Android | No celular, offline exceto as APIs opcionais | Kotlin, MediaCodec, ML Kit Face, Media3 Transformer + shader GLSL, OkHttp, SDK Anthropic Java | `android/` (~2.050 linhas) |

Fluxo da fábrica (PC e Android, modo jogo):
`vídeo → loudness do áudio inteiro → picos de "hype" (z-score sobre mediana móvel) → transcrição só das janelas
candidatas (Groq) → nota (pico de áudio, palavras de hype, risada, repetição, ritmo) → [Claude escolhe e dá títulos,
opcional] → ajuste de corte em pausas → layout (webcam detectada / só gameplay / seguir rosto) → render vertical
com legendas e volume normalizado → galeria (Android) ou outbox/ + index.html (PC)`.

O PC também tem o **modo conversa** (`--mode talk`): transcreve tudo, escolhe pensamentos completos, corta pausas,
câmera segue quem fala. O Android **não** tem esse modo.

Divisão de responsabilidades com IA, hoje:
- **Groq** (`whisper-large-v3`): transcrição com tempo por palavra. No PC há alternativa local (faster-whisper).
- **Claude** (`claude-opus-5-5`, opcional): escolhe entre os candidatos e escreve os títulos.
- **Visão**: YuNet (PC) e ML Kit (Android), locais e grátis.
- **FFmpeg** (PC) e **Media3/MediaCodec** (Android): cortes, composição, legendas, áudio, render.

## 2. O que funciona (testado)

| Sistema | Evidência |
| --- | --- |
| PC, modo jogo de ponta a ponta | Vídeo sintético com webcam e 3 gritos plantados: os 3 gritos viraram clipes; webcam detectada a cerca de 2 px da posição real. |
| PC, modo conversa de ponta a ponta | Entrevista real (CC, archive.org): troca de câmera entre falantes, legendas, ganchos. |
| PC, download por link (yt-dlp) | Testado com archive.org. Twitch e YouTube não foram testados daqui. |
| PC, transcrição local (faster-whisper) | Testada: cerca de 4,5x mais rápida que o tempo real em 4 CPUs. |
| Port Kotlin = Python | 6 testes JUnit comparam número a número (excitação, picos, cortes, hype, ganchos, webcam, escolha final, limite de duração). |
| Android de ponta a ponta | App instalado num emulador Android 14: leu o áudio, achou a webcam, escolheu o pico em 0:33 (grito plantado em 0:32), renderizou, salvou na galeria. Layout correto e volume no alvo (-16,1 dB, alvo -16). |
| Sincronia das legendas no Android | Áudio do clipe transcrito de novo e comparado aos quadros: palavra destacada coincide com a fala (precisão de quadro). |
| Progresso real | PC: etapas reais. Android: progresso do Transformer (`getProgress`) e da decodificação; nada simulado. |
| Cancelamento real (Android) | O cancelamento chega ao `transformer.cancel()` pela corrotina. Não foi testado no emulador. |

## 3. Não testado (funciona em teoria, sem prova)

- **Groq com chave real**: nem no PC nem no Android. Não há chave nesta sessão. É o ponto mais crítico: sem
  transcrição, o Android não tem legenda.
- **Claude com chave real**: só a assinatura do SDK e o parse da resposta (com resposta simulada no PC).
- **Velocidade em celular real**: o emulador é de 50 a 100 vezes mais lento; não há número real.
- **Celulares com codificador problemático**: existe nova tentativa automática, mas sem teste em aparelho real.
- **Vídeos HDR, 60 fps, 4K, MKV/WebM no Android**: não testados.
- **Lives de várias horas**: a lógica faz streaming (não carrega tudo na memória), mas não testei com 4 h reais.
- **Editor "edit this" (Herk Edit Kit)**: código herdado, não reexecutado nesta auditoria.

## 4. Problemas encontrados

Já corrigidos (achados ao rodar de verdade):
1. Android: estouro de `Long.MAX_VALUE` fazia ler só o começo do áudio (saía 1 clipe em vez de 3).
2. Android: o watchdog do Media3 abortava a renderização em aparelhos lentos.
3. Android: codificador ocupado falhava ao iniciar; agora tenta de novo até 3 vezes.
4. PC e Android: o clipe podia passar da duração máxima (30 s com limite de 20 s).
5. PC: detecção de webcam quebrava quando o rosto se mexia dentro dela; o recorte pegava jogo junto.

Abertos, por gravidade:
1. **Quantidade de clipes "forçada"**: os dois motores sempre tentam entregar N clipes, mesmo que alguns sejam
   fracos, e não explicam quando sai menos (por exemplo, se a renderização falha). → Fase B.
2. **Seleção depende de pico de áudio**: um momento ótimo e calmo (história, opinião forte) nunca vira candidato
   no modo jogo, mesmo com Claude, porque o Claude só vê janelas de pico. O modo conversa (só PC) não tem esse
   problema. → Fase C.
3. **Nenhum teste automatizado no Python**: só o Kotlin tem testes. → Fase B.
4. **Chaves no Android em texto puro** (SharedPreferences privado, `allowBackup=false`). Outros apps não leem,
   mas o arquivo não é criptografado. Não há como testar a chave dentro do app. → Fase B.
5. **Android sem modo conversa, sem seguir o rosto durante o clipe** (o layout "seguir rosto" é um recorte fixo
   por clipe), **sem pré-visualização, sem editar cortes, sem renderizar de novo um clipe só**. → Fases D e E.
6. **Trabalho perdido se o Android matar o app no meio**: a análise fica em cache, mas a fila não é retomada. → Fase E.
7. **Duas implementações (Python e Kotlin)**: risco de divergirem. Os testes de paridade reduzem esse risco, mas
   não o eliminam.
8. **Chave de assinatura do APK no repositório** (senha fraca, documentada). Serve para instalação direta; para
   a Play Store precisa de outra chave.
9. **Legendas sem quebra de linha**: grupos de até 3 palavras e 16 caracteres; palavras muito longas são reduzidas,
   não quebradas.

## 5. O que o prompt mestre pede e não se aplica sem uma decisão sua

O prompt assume um **SaaS** (servidor, autenticação, chaves guardadas no backend, fila no servidor, ZIP para
download, painel web). Isso não existe e **não é uma etapa técnica, é um produto novo**: servidor com GPU ou CPU
forte, armazenamento de vídeo, contas, custos mensais, termos de uso e direitos autorais. Hoje tudo roda no
aparelho do usuário, com custo de servidor zero e o vídeo nunca saindo do aparelho, o que é uma vantagem.
Recomendação: consolidar app e PC (Fases B a E) e só decidir sobre SaaS depois de validar com lives reais.

## 6. Plano por etapas (baseado no código real)

| Fase | Entrega | Arquivos principais |
| --- | --- | --- |
| **B. Estabilização** (em andamento) | testes automatizados no Python; quantidade honesta (máximo + piso de qualidade + relatório "pediu N, saíram M, por quê"); testar chave dentro do app; chaves criptografadas no Android | `tools/clipper/gaming.py`, `pipeline.py`, `report.py`, `tests/`; `android/core/.../Hype.kt`, `app/.../Engine.kt`, `Prefs.kt`, `MainActivity.kt` |
| **C. Inteligência** | candidatos também por conteúdo (Claude lê a transcrição inteira quando há chave), modo conversa no Android, nota multi-critério (retenção, contexto, abertura, fechamento) vinda do Claude, deduplicação de momentos parecidos | `gaming.py`, `highlights.py`, `Hype.kt`, `ClaudePicker.kt`, `Transcriber.kt` |
| **D. Edição** | layout webcam do ROADMAP (painel menor, mais gameplay, ajustável); estilos de legenda; formatos 1:1, 4:5, 16:9; editar início e fim e renderizar de novo um clipe só | `layout.py`, `Layout.kt`, `LayoutEffect.kt`, `CaptionOverlay.kt`, `captions.py`, `MainActivity.kt` (+ tela de edição) |
| **E. Exportação** | fila por clipe com status próprio; tentar de novo e cancelar por clipe; retomar após o app ser morto; resolução, fps e codec configuráveis; verificação do arquivo exportado | `Engine.kt`, `ClipService.kt`, `Renderer.kt`, `render.py` |
| **F. Experiência** | painel de projetos (renomear, apagar, favoritar, buscar), pré-visualização dentro do app, título, descrição e hashtags editáveis | `MainActivity.kt` (dividir em telas), `History` |
| **G. Avançado** | música, marca d'água, B-roll, redução de ruído, várias versões do mesmo corte | novos módulos |
| **H. Testes reais** | lives reais de 30 min, 2 h e 4 h em celulares reais | — |
