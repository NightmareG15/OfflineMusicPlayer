# Offline Music Player

Player Android nativo, simples e offline, feito em Kotlin sem bibliotecas de terceiros.

## O que já está implementado

- Leitura da biblioteca de áudio local pelo MediaStore (a lista se atualiza sozinha ao abrir o app).
- Reprodução, pausa, faixa anterior e próxima, com botões em formato de ícone.
- Barra de progresso (toque ou arraste para avançar/voltar na música).
- Modo aleatório e repetir (desligado, lista inteira ou uma faixa).
- Playlists simples: crie, renomeie e exclua listas; segure o dedo numa música para adicioná-la a uma lista.
- Reprodução em segundo plano por serviço em primeiro plano, com notificação de mídia.
- Controle pelos botões do fone (play/pause, próxima, anterior), com fio ou Bluetooth.
- Pausa automática quando o fone é desconectado.
- Imagem de fundo opcional (botão de imagem no topo da tela).
- Tema Escuro (padrão) ou Claro, nas configurações.
- Atalho nas configurações para as configurações de som do Android.
- Sem conta, rede, anúncios, streaming ou download de capas.

## Requisitos para compilar localmente

- Android Studio recente.
- JDK 17.
- Android SDK Platform 35 (o Android Studio pode instalar isso pelo SDK Manager).

Abra a pasta do projeto no Android Studio e aguarde a sincronização do Gradle. Depois use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

O APK de depuração será criado em `app/build/outputs/apk/debug/app-debug.apk`.

## Gerar APK sem instalar Android SDK no seu PC

O projeto inclui o workflow `.github/workflows/build-apk.yml`. Abra a aba **Actions** do repositório e execute **Build Android APK** (ele também roda sozinho a cada envio de arquivos). Ao terminar, baixe o artefato `offline-music-debug-apk` na execução. Isso usa os servidores do GitHub para compilar.

## Observações

- O Android solicitará permissão para acessar arquivos de áudio.
- Em Android 13 ou superior, também poderá solicitar permissão para mostrar notificações.
- A compatibilidade exata depende do codec interno do aparelho. Arquivos MP3, WAV PCM e OGG Vorbis padrão são os alvos; formatos encapsulados ou codecs incomuns podem não tocar.
- Ainda não inclui busca, equalizador, letras ou download de capas.
