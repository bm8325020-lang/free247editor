# Fábrica de Clipes: app Android

O mesmo motor de clipes de jogos do `clip.py`, rodando dentro do celular. Não precisa de PC nem de servidor.

- `core/`: os algoritmos em Kotlin puro (picos de hype, palavras de hype, webcam, legendas). Os testes em
  `core/src/test` comparam com o motor Python, número a número.
- `app/`: o app. Áudio decodificado pelo próprio celular, rosto/webcam com ML Kit (offline), vídeo vertical
  montado na GPU com Media3 Transformer (shader em `LayoutEffect.kt`) e legendas em `CaptionOverlay.kt`.
  Os clipes vão para a galeria, em `Movies/Clipes`.

## Gerar o APK

Precisa de JDK 17+ e do Android SDK (platform 36). Crie `local.properties` com `sdk.dir=...`, depois:

```
./gradlew :core:test :app:assembleRelease
```

O APK sai em `app/build/outputs/apk/release/app-release.apk`.

## Chave de assinatura

`keystore/clipfactory.jks` (senha `clipfactory`) assina o APK para instalação direta (sideload). Ela fica no
repositório para que qualquer build futuro possa atualizar o app já instalado sem desinstalar. Não use essa chave
para publicar na Play Store: lá, gere uma chave nova e privada.
