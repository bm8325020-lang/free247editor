# Roadmap e pedidos anotados

## Pedidos do usuário (anotados, ainda não implementados)

### Layout webcam + gameplay (anotado em 2026-11-13; FEITO na 1.3.0 do jeito que o usuário explicou depois:
a webcam inteira, no formato dela (quadrada ou redonda, detectado), pequena no canto esquerdo sobre o gameplay em
tela cheia, sem cortar o rosto. Ainda falta: tamanho e posição ajustáveis dentro do app)
Pedido, nas palavras do usuário: "na parte da web cam deixar só o rosto do cara, literalmente só a web cam, e mais
partes da gameplay; diminuir o tamanho da web cam e aumentar o tamanho da tela da gameplay".

Como está hoje (`tools/clipper/layout.py` e `android/core/.../Layout.kt`):
- O painel da webcam ocupa de 28% a 45% da altura (no teste, 45%: 864 de 1920 px), porque ele tenta caber a
  webcam inteira com a proporção dela.
- O gameplay ocupa o resto e mostra só a faixa central, cerca de 49% da largura original. As laterais do jogo
  ficam de fora.

O que mudar:
1. Recortar o painel de cima **justo no rosto**, só a webcam, sem sobra de jogo nem de fundo.
2. Painel da webcam **menor**: algo entre 22% e 28% da altura, ajustável.
3. Gameplay **maior** (72% a 78% da altura) e mostrando **mais largura do jogo**. Hoje é um recorte central
   apertado; a opção é diminuir o zoom (por exemplo, mostrar de 60% a 70% da largura) aceitando bordas desfocadas
   pequenas, ou deixar escolher entre "preencher" e "mostrar mais".
4. Deixar tamanho e posição ajustáveis por usuário (o prompt mestre também pede isso, seção 7).
Arquivos: `tools/clipper/layout.py`, `tools/clipper/config.py`, `android/core/src/main/kotlin/clipfactory/core/Layout.kt`,
`android/app/src/main/java/clipfactory/app/LayoutEffect.kt` e os testes de paridade.

## Plano por fases (prompt mestre)
Veja `docs/AUDITORIA.md`, seção 6.
