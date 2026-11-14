# SANTA LÚCIA — Documento de Design (GDD)

> Terror em primeira pessoa · exploração, perseguição, esconderijo e gerenciamento de risco.
> Sanatório Santa Lúcia, 1974: hospício com necrotério próprio, abandonado às pressas.
> O protótipo jogável que implementa este documento está em `index.html` + `game.js`.

---

## 0. Crítica antes de começar

Leia esta parte primeiro. Ela trata dos pontos fracos do pedido.

1. **"Jogo completo, realista, altamente imersivo" é o maior risco do projeto.** Fotorrealismo custa caro (modelagem, PBR, iluminação, animação de criatura). Projetos solo que começam pela arte quase sempre morrem antes de descobrir se o jogo é divertido. Por isso este plano começa por um **protótipo de gameplay feio, mas jogável**. Esse protótipo já existe aqui.
2. **"Coletar N fusíveis e abrir a porta" é o loop mais batido do gênero indie.** Pense em Granny, Poppy e clones de Slender. Esse loop sozinho não diferencia o jogo. O diferencial tem que vir da relação **luz ↔ criatura**, e ela só funciona com um conflito real. Se a lanterna só ajuda, o jogador anda com ela ligada o tempo todo, e o "medo do escuro" morre.
   - **Decisão central:** a lanterna é **escudo e farol ao mesmo tempo**. Ligada, ela queima o rosto da criatura. Ligada, ela também faz você ser visto a 26 m em vez de 9 m. Cada segundo de lanterna ligada vira uma decisão.
3. **"A criatura fica mais forte no escuro" e "o jogador pode apagar a lanterna para se esconder" puxam em direções opostas.** Resolvi assim: no escuro, *ela* é mais rápida e escuta melhor, mas *você* é mais difícil de ver. A escuridão protege e ameaça ao mesmo tempo, e esse é o tipo de tensão que você quer.
4. **Nenhuma imagem de referência chegou nesta conversa.** A direção de arte abaixo foi construída a partir da sua descrição em texto. Se você reenviar as imagens, ajusto paleta, silhueta da criatura e composição.

---

## 1. Conceito geral

**Resumo.** Você acorda na recepção do Sanatório Santa Lúcia. O portão principal tem trava eletromagnética e está sem energia. O diretor espalhou os 4 fusíveis do painel pelo prédio para que "ninguém saia" enquanto o **Paciente 17**, vítima de um experimento de fototerapia em que suas pálpebras foram removidas, estivesse solto. Ele continua solto.

**Proposta.** Cerca de 30–60 minutos de terror sistêmico. A IA reage ao que você faz (som, luz, posição), e cada encontro sai diferente.

**Diferenciais:**
- **A lanterna é um dilema, não uma arma.** O feixe difuso só desacelera a criatura e a deixa furiosa. O **feixe concentrado no rosto** a faz recuar, mas esquenta, gasta bateria, exige mira precisa num alvo que se contorce, e a criatura **aprende**: cada queimadura exige mais exposição na próxima.
- **A lore explica a mecânica.** Ele não tem pálpebras e passou meses sob luz forçada. Por isso a luz no rosto dói e o escuro é o território dele. O jogador entende a regra pela história, sem tutorial.
- **Pegar um fusível é um evento.** Ao retirar o fusível, as luzes daquele setor caem e o estalo elétrico atrai a criatura. Achar o item vira o começo do perigo, não o alívio.
- **O final é um cerco.** Instalar o 4º fusível dispara o alarme por 30 s. Nesse tempo a criatura sabe onde você está, e você precisa sobreviver perto da saída.

---

## 2. Gameplay loop

```
 EXPLORAR (lanterna ligada = vê mais, é visto mais)
     │  ouve sons, lê documentos, acha pilhas
     ▼
 ACHAR FUSÍVEL ──► luzes do setor apagam + barulho ──► criatura INVESTIGA
     ▼
 VOLTAR À RECEPÇÃO (backtracking com o setor agora escuro)
     │   encontro? → esconder / segurar o ar / queimar o rosto / correr pelos loops
     ▼
 INSTALAR NO PAINEL ──► checkpoint salvo
     ▼  (repete até 4/4)
 ALARME 30 s: criatura em CAÇA, sabe sua posição ──► PORTA ABRE ──► FUGA
```

- **Objetivo:** 4 fusíveis no painel e sair pela porta principal.
- **Progresso:** só conta o fusível **instalado**. Os fusíveis que você carrega voltam para o lugar se você morrer. Isso dá peso ao caminho de volta e faz cada ida à recepção parecer uma "entrega".
- **Ordem livre, dificuldade crescente:** a distância e a escuridão dos fusíveis criam uma ordem natural (Arquivo → Celas → Necrotério → Subsolo), mas o jogador escolhe.

---

## 3. Mecânicas principais

| Mecânica | Regra | Por que assim |
|---|---|---|
| **Movimento** | andar 2,9 m/s · correr 4,9 m/s (fôlego de 4,5 s) · agachado 1,4 m/s | A criatura corre a 4,35 m/s no escuro: correr salva, mas dura pouco |
| **Ruído** | passo: 6 m · correndo: 15 m · agachado: 1,8 m · pegar fusível: 32 m · instalar: 22 m | Som é o principal canal de informação da IA |
| **Fusíveis** | 4, brilho âmbar fraco (achável varrendo com a lanterna). Pegar = apagar luzes do setor + ruído | Transforma recompensa em tensão |
| **Painel** | instala todos os carregados de uma vez; salva checkpoint; luzes vermelho→verde | Feedback claro e progresso concreto |
| **Porta final** | 30 s de partida do gerador com alarme | Clímax garantido e escrito pelo sistema |
| **Lanterna** | bateria 100% (0,32%/s difuso, 2,6%/s foco) · pilhas +45% · foco aquece 30%/s, superaquece em 100% = **5 s apagada** | Foco é recurso de emergência, não postura padrão |
| **Esconderijos** | armários (22) e debaixo de macas (9). Ao entrar, a lanterna desliga. Segure **Espaço** para prender o ar (6 s) | Esconder vira tensão ativa, não uma pausa |
| **Respiração** | criatura a < 3,4 m de você escondido sem prender o ar → suspeita sobe; passou de 1,7 → te arranca de lá. Soltar o ar perto dela (fôlego zerado) = +1 de suspeita | Jogador escuta a respiração dela e controla a sua |
| **Esconder à vista** | se ela te viu entrar (< 1,2 s), o esconderijo não protege | Evita "armário mágico" |
| **Morte** | contato. Jumpscare curto (1 s), sem gore excessivo | Morte rápida → reinício rápido → menos frustração |
| **Reinício** | volta à recepção; instalados ficam; carregados voltam ao lugar; criatura renasce no ponto mais distante, adormecida por 8 s; bateria mínima 60% | Punição justa, sem grind |

---

## 4. IA da criatura

### Estados

```
           ┌───────── vê jogador ─────────────────────────────┐
           ▼                                                   │
 DORMENTE → PATRULHA ──ruído──► INVESTIGAÇÃO ──chega──► BUSCA ─┤
              ▲  ▲  diretor (40 s, longe >28 m) ─┘          │   │
              │  └───────────── 18 s sem achar ─────────────┘   │
              │                                                 ▼
              │                         PERSEGUIÇÃO (repath 0,35 s)
              │                           │ perdeu LOS > 2,6 s → BUSCA
              │                           │ exposição facial ≥ 1+resistência
              │                           ▼
              └──── (alarme: CAÇA) ◄── RECUO: cambaleia 0,9 s, foge 5,5 s
                                          para o cômodo escuro mais distante
```

| Estado | Velocidade | Comportamento |
|---|---|---|
| Dormente | — | Início do jogo: acorda aos 75 s ou ao 1º fusível (grito vindo do subsolo) |
| Patrulha | 1,5 | Cômodo a cômodo; A* penaliza tiles iluminados (evita luz); 35% de chance de escolher salas perto do jogador |
| Investigação | 2,5 | Vai até a origem do ruído |
| Busca | 2,0 | 4 pontos aleatórios num raio de 4 tiles, preferindo escuro; pausas para "farejar" (respiração muda) |
| Caça | 3,3 | Sabe a área (alarme/diretor); atualiza posição a cada 2,5 s |
| Perseguição | 4,35 escuro / 3,1 luz | Linha de visão; ×0,45 enquanto o feixe estiver no rosto |
| Recuo | 4,8 | Grito, fumaça, cobre o rosto com os braços, foge |

### Percepção

- **Visão:** cone de 124°. O alcance depende do **jogador**: lanterna ligada 26 m · em área iluminada 17 m · no escuro 9 m · agachado no escuro 5,5 m. A < 2,6 m ela sente você em qualquer direção. Se o feixe passa pelo corpo dela, você é denunciado mesmo fora do cone.
- **Audição:** raio do ruído ×1,25 quando *ela* está no escuro e ×0,65 se houver parede no caminho.
- **Diretor:** se ela passa 40 s em patrulha a mais de 28 m de você, recebe uma posição aproximada sua (±6 m). Isso evita longos períodos sem ameaça, sem trapacear de forma visível.

### Reação à luz (o coração do jogo)

```
exposição += taxa × (1 − 0,45 × dist/alcance) × dt     só se o feixe estiver no ROSTO e ela estiver de frente
  feixe difuso: taxa 0,4, teto em 60% do limiar → só desacelera e enfurece
  feixe de foco: taxa 1,9, cone 7°, alcance 20 m → ~0,6 s para queimar
limiar = 1 + resistência     (resistência +0,35 por queimadura, máx. 1,6; cai 0,015/s)
sem luz: exposição −0,7/s
de costas: luz não queima, só a alerta (ela se vira)
```

**Resultado:** a 1ª queimadura leva cerca de 0,6 s de foco. A 4ª seguida leva cerca de 1,7 s, ou seja, mais de 50% do calor. Spam de foco termina em superaquecimento, com 5 s no escuro e ela furiosa a poucos metros.

---

## 5. Level design

Grid de 44×32 tiles de 2,4 m (≈ 105×77 m). Corredores de **1 tile**: apertados de propósito.

```
    01234567890123456789012345678901234567890123
 0  #####X######################################
 1  #L.....B..####...#...#...#...#...#...#.2.###
 2  #L........####...#...#...#...#...#...#...###
 3  #.........#####.#L#.###.###.#L#.###.###.#L##
 4  #..........................................#
 5  #....P....#.########.##############.########
 6  #.......W.#.##............L##.............##
 7  #..W......#.##.............##.............##
 8  #####.#####.##..S..S..S....##...S.........##
 9  #........L#.##.............##............3##
10  #.........#.##.............##.............##
11  #...........##..S..S..S....##...S.....S...##
12  #KK.......#.##...........W.##.............##
13  ###.####.##.##L............##L............##
14  #......#..#.L###########.##########.########
15  #......#..#.############.####.............##
16  #.1...L#.L#.#..KKK.........##.............##
17  ###########.#..............##.............##
18  ###########......................T...T....##
19  ###########.#..............##.............##
20  ##########L.#.......KK.....##.............##
21  ###########.#.............L##L............##
22  ###########.#############################.##
23  ###########.L############################.##
24  ###########.########L############L#######.##
25  #..........................................#
26  ######.########.#########.#########.########
27  #.......c............#.......#.K.K........##
28  #..cc...c.......cc...#..KKK..#.......KK...##
29  #.......c...cc.......#.......#..........4.##
30  #L..c.......c.....c..#......L#L...........##
31  ############################################
```
Legenda: `#` parede · `X` porta principal · `B` painel · `P` início · `1–4` fusíveis · `L` armário · `S` maca (esconder embaixo) · `T` mesa de autópsia · `K` bancada/gerador/caldeira · `W` cadeira de rodas · `c` caixotes.

Linhas 0–13: térreo norte (Recepção, Celas, Enfermaria, Necrotério). 14–21: Arquivo, Guarita, Laboratório, Autópsia. 22–24: escada e poço de serviço. 25–30: Subsolo (corredor, Porão, Caldeiras, Sala Elétrica).


### Setores e função

| Setor | Luz | Função de design |
|---|---|---|
| **Recepção** | fria, estável | Hub e "lugar seguro". Painel e porta. Ver a porta em todo retorno lembra o objetivo |
| **Administração / Arquivo / Guarita** | piscando / âmbar | Tutorial: **F1** perto, 2 documentos que ensinam as regras |
| **Corredor Leste** | 2 lâmpadas piscando | Espinha do andar. 1 tile de largura: impossível desviar. 3 nichos com armário |
| **Ala de Contenção** | breu | **F2** na última cela (a mais distante). Celas sem saída = armadilha: entrar é aposta |
| **Enfermaria** | verde piscando | 6 macas para se esconder embaixo. Atalho para o Laboratório |
| **Necrotério** | azul fria | **F3** numa gaveta aberta com saco de corpo. Ao pegar, a sala apaga |
| **Autópsia** | foco cirúrgico forte | Sala mais clara do prédio: ilha de segurança relativa no meio do perigo |
| **Laboratório** | verde | Documento que ensina o feixe concentrado. Conecta Enfermaria ↔ Autópsia |
| **Corredor Central** | 1 lâmpada fraca | Única descida "oficial" para o subsolo. Nichos com armário |
| **Subsolo / Porão / Caldeiras** | vermelho pulsante / breu | Porão = labirinto de caixotes no escuro total, território dele. Spawn da criatura |
| **Sala Elétrica** | vermelho | **F4** + documento avisando do alarme. Mais longe da saída = volta mais tensa |

### Loops e atalhos (essenciais para perseguição)
1. **Corredor Central ↔ Laboratório ↔ Autópsia ↔ Poço de Serviço ↔ Subsolo ↔ Corredor Central.** É o grande loop do meio e do fundo do mapa.
2. **Enfermaria ↔ Laboratório** pela porta lateral.
3. **Recepção ↔ Administração ↔ Corredor Central** pela porta do lado leste da admin.

Com isso, toda perseguição tem pelo menos uma rota circular. Corredor sem saída só existe onde ele é a aposta (celas, porão).

### Progressão
- **Início (0–10 min):** recepção, admin, arquivo. A criatura dorme. O jogador aprende interação, painel e documentos. Pegar F1 é o "despertar" e o primeiro susto, com um grito vindo do subsolo.
- **Meio (10–30 min):** Celas, Enfermaria, Necrotério. Primeiros encontros, uso de esconderijos, aprendizado do foco no Laboratório.
- **Fim (30+ min):** Subsolo no breu, F4 longe da saída. Retorno longo pelo Corredor Central ou pelo Poço. Cerco final na recepção com o alarme.

### Melhorias para a versão de produção
- Portas reais (abrir/fechar, bater, trancar por dentro por alguns segundos) como ferramenta de fuga.
- Um elevador de carga como atalho que se desbloqueia (estilo *Resident Evil*), aberto ao trazer o F3.
- Variar a posição dos fusíveis entre 2–3 candidatas por setor (replay).

---

## 6. Direção de arte

**Estilo:** realismo sujo dos anos 70, sob uma lente de "fotografia forense": pouca luz e alto contraste, com o que importa iluminado e o resto engolido pelo escuro.

**Regra de ouro da iluminação:** *luz = informação e segurança relativa; escuro = ele*. O jogador deve conseguir ler o mapa pelas ilhas de luz.

| Elemento | Decisão |
|---|---|
| **Paleta** | Verde-hospital desbotado (#4F6658), azulejo creme-esverdeado (#B9C4B0), aço frio (#8A9195), sombras verde-petróleo quase pretas (#030606). Acentos: âmbar dos fusíveis (#FFA040), vermelho de emergência (#FF2410), branco cirúrgico na autópsia |
| **Temperatura** | Fluorescentes frias/esverdeadas nas alas · azul no necrotério · vermelho pulsante no subsolo · âmbar quente **só** no arquivo, um "abrigo" emocional |
| **Materiais** | Azulejo até 1,5 m + tinta descascada acima (o visual clássico de hospital antigo) · acolchoado manchado com marcas de unha nas celas · gavetas de aço com etiquetas numeradas · concreto úmido com escorridos no subsolo |
| **Desgaste** | Grime concentrado no pé das paredes, escorridos de umidade, papéis no chão, fios pendurados, placas de forro faltando, manchas antigas (marrom, não vermelho vivo, porque o horror é o tempo que passou) |
| **Composição** | Corredores longos com ponto de fuga e uma lâmpada no fim. Portas de cela entreabertas. Macas com lençol caído até o chão (esconderijo legível). Silhueta da criatura sempre contra um fundo mais claro quando ela "posa" |
| **Pós-processo** | Grão de filme, vinheta pesada, vinheta vermelha por proximidade. Em produção: aberração cromática leve e motion blur só no jumpscare |

---

## 7. Lista de assets

### Cenário modular (grid de 2,4 m × 3 m de pé-direito)
- Parede reta, canto, batente de porta 1 tile, nicho de armário, meia parede quebrada · em 5 variantes: azulejo/tinta, acolchoado, gavetas de necrotério, tinta+lambri (admin), concreto.
- Pisos: linóleo xadrez, ladrilho com ralo, concreto. Forro: placa, placa quebrada, buraco.
- Escada/rampa para o subsolo, poço de serviço com escada marinheiro.

### Props
Macas (com/sem corpo, lençol caído), mesa de autópsia com bandeja de instrumentos, foco cirúrgico, gavetas (fechada/aberta/aberta com saco), carrinho de instrumentos, cadeira de rodas (em pé/tombada), suporte de soro, camisa de força, cama de cela com correias, arquivos de aço, fichas e prontuários espalhados, máquina de escrever, telefone de disco, caixotes, caldeira, canos, quadros elétricos, gerador, extintores, placa "SAÍDA", relógio parado.

### Portas
Porta principal dupla de aço com trava eletromagnética e luzes de status · porta de cela com visor · porta vai-e-vem hospitalar · porta corta-fogo · porta de câmara fria.

### Itens interativos
Fusível 30A (vidro, âmbar, brilho) · pilhas · documentos (6) · painel de 4 encaixes com lâmpadas.

### Esconderijos
Armário de vestiário · armário clínico grande · debaixo de maca com lençol · cortina de enfermaria (produção) · nicho escuro (produção, sem interação: só funciona com lanterna desligada e agachado).

### Criatura
Malha principal (~25–40k tris), rig com dedos e mandíbula, blendshapes de rosto (mandíbula, olhos girando), material SSS de pele de cera.

### Animações
`idle_twitch`, `head_tilt_slow`, `walk_patrol`, `walk_sniff`, `run_chase` (tronco baixo, braços à frente), `turn_snap`, `flinch_cover_face`, `burn_stagger`, `retreat_run`, `open_locker`, `grab_kill`, `crawl_vent` (produção). Jogador: `hide_enter/exit`, `under_stretcher_enter/exit`, `fuse_insert`, `flashlight_overheat_shake`.

### Áudio
Ver seção 8 e a tabela de áudio abaixo.

---

## 8. A criatura — "Paciente 17" (o Sem-Pálpebras)

**Lore curta.** Dr. Abelardo Vasconcellos acreditava que luz contínua "curava" a psicose. No Paciente 17 levou o protocolo ao extremo: removeu as pálpebras para garantir exposição ininterrupta. Meses depois, o paciente não dormia, não piscava e não morria. A pele descorou como cera, os membros se alongaram com a fome e ele aprendeu a viver onde a luz não chega. Os 14 corpos do necrotério têm queimaduras de retina. Ele não os matou com a luz. Ele os fez olhar para ela.

### Visual
- **Silhueta:** cerca de 2,6 m se estivesse ereto, mas sempre **curvado para frente**. Tronco estreito, costelas aparentes, pelve pequena. **Braços longos demais**: as mãos chegam abaixo do joelho, com 4 dedos de 40 cm que se mexem sem parar. Pescoço longo projetado para frente, cabeça alongada verticalmente. Reconhecível de longe pela forma de "ponto de interrogação" com braços pendurados.
- **Rosto:** pele esticada sem pálpebras. Órbitas fundas e escuras com **olhos brancos sempre abertos** e pupilas minúsculas. Mandíbula pendurada e desencaixada, aberta mais do que o normal. Dentes amarelados. Nada de olhos brilhantes: o horror está no olho que **não pode fechar**.
- **Pele:** cera pálida e levemente translúcida que **reflete muito a lanterna** (fácil de ver com luz, quase invisível no escuro). Cicatrizes cirúrgicas na testa, ataduras velhas nos punhos.

### Movimento
Andar lento e rígido em patrulha, com a cabeça em **inclinações lentas de até 70°** e tiques bruscos. Na caça, o tronco desce, os braços vão à frente e a passada fica longa e desajeitada, rápida demais para aquele corpo. Para quando "fareja": fica imóvel e a respiração muda.

### Reação à luz
- **Feixe difuso no rosto:** vira a cabeça, ergue os braços na frente do rosto e desacelera para 45%, mas continua vindo. Chiado de pele.
- **Feixe concentrado:** pele esquenta (emissivo laranja), **fumaça** sai do rosto, **grito agudo**, cambaleio de 0,9 s cobrindo o rosto, depois fuga para o escuro mais distante.
- **De costas:** a luz não dói. Ele para, se vira lentamente e vem.

### Ataque
Contato: agarra com as duas mãos, aproxima o rosto (mandíbula escancarada) até encher a tela. Corte para o preto em 1 s.

### VFX
Fumaça de queimadura, emissivo de pele, falha da lanterna por proximidade (aviso diegético), lâmpadas que enfraquecem quando ele passa embaixo, partículas de poeira no feixe.

### Sons (mapa de comunicação com o jogador)
| Som | Significa |
|---|---|
| Respiração úmida e lenta | Patrulhando por perto |
| **Respiração para** | Está escutando ou farejando: **prenda o ar** |
| Estalos secos (articulações e dentes) | Está se movendo perto |
| Rosnado distorcido + stinger | Te viu: perseguição |
| Chiado de pele | Seu feixe está acertando o rosto |
| Grito agudo + reverb | Queimou e está recuando |
| Batida de metal distante | Ambiente. Às vezes é ele, às vezes não (paranoia) |

---

## 9. Implementação técnica

### Protótipo atual (navegador, Three.js)
Arquivos: `index.html` (HUD e telas) e `game.js` (todo o jogo, cerca de 1.000 linhas). Sistemas:

| Sistema | Onde em `game.js` | Notas |
|---|---|---|
| Config de balanceamento | `CFG` | Todos os números da seção 3/4 |
| Mapa | `carve/put`, `ZONES`, `FUSES`, `LIGHTS` | Mapa por retângulos; troca fácil |
| Lightmap de gameplay | `computeLightmap()` | Luz por tile com linha de visão. **A IA usa isso, não a renderização** |
| Pathfinding | `astar()` | 8 direções, custo extra opcional (luz) |
| IA | `updateCreature()` | Máquina de estados da seção 4 |
| Lanterna × criatura | `lampVsCreature()` | Cone, mira no rosto, facing, exposição, resistência |
| Ruído | `emitNoise()` | Raio × escuridão × oclusão |
| Esconderijo | `enterHide/leaveHide` + bloco de suspeita | Respiração, "viu você entrar" |
| Fusíveis/painel/porta | `pickFuse`, `usePanel`, `startEndgame`, `updateEndgame` | |
| Checkpoint/save | `save/loadSave` (localStorage) | Instalados + documentos + mortes |
| Áudio | objeto `AU` (WebAudio procedural, HRTF, reverb, oclusão) | Placeholders fiéis ao design final |
| Animação | `animateCreature()` | Procedural (substituir por rig na produção) |

### Para produção: Unreal Engine 5 (recomendado) ou Unity HDRP
**Por que UE5:** Lumen e iluminação volumétrica entregam o realismo pedido quase de graça, e há Blueprints para quem não programa C++. Custo: curva de aprendizado e hardware. Se você nunca usou nenhuma engine, **Unity URP** é mais leve para aprender, mas para chegar ao mesmo visual você vai brigar mais com a iluminação. Godot 4 serve para o jogo, mas não para "altamente realista".

```
/Game
  /Core        BP_GameMode_SantaLucia, BP_GameState (fusíveis, endgame), SaveGame_SantaLucia
  /Player      BP_Player (FirstPersonCharacter), BPC_Flashlight, BPC_Stamina, BPC_Breath, BPC_Interactor
  /AI          BP_Creature, AIC_Creature, BT_Creature (Behavior Tree), BB_Creature (Blackboard),
               BTT_Retreat, BTT_SearchArea, BTS_LightExposure, EQS_FindDarkRetreat, EQS_SearchPoints
  /Director    BP_Director (dicas de posição, ritmo)
  /Interact    BPI_Interactable, BP_Fuse, BP_FusePanel, BP_ExitDoor, BP_Locker, BP_Stretcher, BP_Note, BP_Battery
  /World       BP_FlickerLight, BP_LightZone (volume que escreve "nível de luz" para a IA), BP_NoiseEmitter
  /Audio       MetaSounds: MS_CreatureBreath, MS_Heartbeat, MS_LampHum, Submix com reverb por zona
  /UI          WBP_HUD, WBP_Note, WBP_Death, WBP_Win
  /Maps        L_SantaLucia_Persistent + sublevels por setor (streaming)
```

**Pontos de implementação (UE5):**
- **Percepção:** `AIPerception` com `Sight` (alcance alterado em runtime pelo estado da lanterna e pelo `BP_LightZone` do jogador) e `Hearing` (`ReportNoiseEvent` com `Loudness`/`MaxRange` dos valores da seção 3).
- **Behavior Tree:** um Selector no topo com prioridade *Recuo > Perseguição > Caça > Investigação > Busca > Patrulha*, por decoradores de Blackboard (`State`, `LastKnownLocation`, `Exposure`).
- **Luz para a IA:** **não** use a luz renderizada. Use volumes `BP_LightZone` com valor 0–1 e liga/desliga sincronizado com as luzes, igual ao `lightmap` do protótipo. É determinístico, barato e fácil de balancear.
- **Lanterna:** um `SpotLight` no `BPC_Flashlight` e, a cada tick, um teste de cone entre a câmera e o socket `head` da criatura (ângulo + distância + LineTrace + Dot do forward da criatura). Exposição como float no Blackboard (`BTS_LightExposure`).
- **Navegação:** NavMesh com `NavArea_Lit` (custo 5) nos volumes de luz, que faz a criatura evitar luz na patrulha. Desligue o custo na perseguição (use outro `QueryFilter`).
- **Esconderijo:** `BP_Locker` possui o jogador (attach da câmera e input restrito). Guarda `bSeenEntering` se a criatura tinha LOS nos últimos 1,2 s.
- **Save:** `SaveGame` com `InsertedFuses[]`, `NotesRead[]` e `Deaths`. Checkpoint ao instalar fusível.

---

## 10. Roteiro de produção

| Fase | Duração (solo, meio período) | Entrega | Critério para avançar |
|---|---|---|---|
| **1. Protótipo cinza** | ✅ feito (este repo) | Loop completo jogável | — |
| **2. Teste de diversão** | 1 semana | 5 pessoas jogando o protótipo, gravando tela e voz | Ninguém trivializa com o foco; todos sentem medo no subsolo; mortes < 6 no total. **Se falhar, ajuste `CFG` antes de qualquer arte** |
| **3. Port para a engine** | 3–5 semanas | Mesmo jogo em UE5 com cubos (greybox), BT, NavMesh, percepção | Paridade com o protótipo |
| **4. Greybox do level final** | 2–3 semanas | Mapa em escala real, portas, loops, escala de luz | Playtest: ninguém se perde, todas as perseguições têm saída |
| **5. Criatura** | 4–6 semanas | Modelo, rig, animações principais, VFX | Lê de longe? Assusta parada? |
| **6. Arte do ambiente** | 6–10 semanas | Kit modular, props, materiais PBR, iluminação final | Performance: 60 fps em placa média |
| **7. Som** | 3–4 semanas (em paralelo com a 6) | Ambiência, foley, criatura, mix, oclusão | Teste **com olhos fechados**: dá para saber onde ele está? |
| **8. Balanceamento e polish** | 3–4 semanas | Tuning de `CFG`, dicas, acessibilidade (FOV, sensibilidade, legendas) | 10 playtests completos |

**Assets de terceiros** (Fab/Quixel/Unity Asset Store) cortam a fase 6 pela metade. Não é vergonha: gaste o tempo na criatura e no som, que são o diferencial.

---

## 11. Versão mínima jogável (MVP)

**Essencial (está no protótipo):**
- [x] 1 mapa com 10 setores, loops e esconderijos
- [x] 4 fusíveis, painel, porta, checkpoint
- [x] Criatura com 7 estados, visão, audição e diretor
- [x] Lanterna com difuso/foco, calor, bateria, mira no rosto e resistência aprendida
- [x] Armário/maca + prender a respiração
- [x] Ruído por tipo de movimento
- [x] Áudio espacial com oclusão, coração e respiração da criatura
- [x] Morte, reinício e vitória
- [x] 6 documentos que ensinam as regras pela história

**Pode esperar:**
- Portas físicas, elevador e atalhos desbloqueáveis
- Animação por rig, captura de movimento
- Dificuldades (sugestão: *Paciente*, *Interno* e *Isolamento*, que mexem em `seeDark`, `chaseDark` e `resistStep`)
- Posições aleatórias dos fusíveis
- Segunda criatura/variação, eventos roteirizados, final alternativo
- Menu de opções, controle, localização

---

## Riscos que eu vigiaria

1. **O foco virar botão de "ganhar".** Se nos testes as pessoas só seguram Q, aumente `resistStep` ou reduza `exposureFocus`. Se ninguém usa, aumente `focusHalfCone`.
2. **Armário virar zona segura.** Se as pessoas passam o jogo dentro do armário, reduza `hideSuspicion` ou faça o diretor checar esconderijos depois de X s parado.
3. **Backtracking chato.** Se voltar à recepção vira tédio, é sinal de que faltam eventos no caminho (luzes caindo, bangs, aparições curtas sem perseguição).
4. **Escuro demais = frustração, não medo.** O jogador precisa *sempre* ter alguma referência visual. Calibre a ambiente mínima e o brilho dos fusíveis com gente real, em monitores diferentes.
