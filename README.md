# Offline Music Player

Player Android nativo, simples e offline, feito em Kotlin sem bibliotecas de terceiros.

## O que já está implementado

- Leitura da biblioteca de áudio local pelo MediaStore.
- Lista de faixas ordenada pelo título.
- Reprodução, pausa, faixa anterior e próxima.
- Reprodução em segundo plano por serviço em primeiro plano.
- Notificação com controles de reprodução.
- Leitura dos arquivos de áudio suportados pelo decodificador do próprio Android (incluindo MP3, WAV/PCM e OGG Vorbis comuns).
- Sem conta, rede, anúncios, streaming ou download de capas.

## Requisitos para compilar localmente

- Android Studio recente.
- JDK 17.
- Android SDK Platform 35 (o Android Studio pode instalar isso pelo SDK Manager).

Abra a pasta `OfflineMusicPlayer` no Android Studio e aguarde a sincronização do Gradle. Depois use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

O APK de depuração será criado em `app/build/outputs/apk/debug/app-debug.apk`.

## Gerar APK sem instalar Android SDK no seu PC

O projeto inclui o workflow `.github/workflows/build-apk.yml`. Envie esta pasta para um repositório GitHub e abra a aba **Actions**. Execute **Build Android APK**; ao terminar, baixe o artefato `offline-music-debug-apk` na execução. Isso usa os servidores do GitHub para compilar.

## Observações

- O Android solicitará permissão para acessar arquivos de áudio.
- Em Android 13 ou superior, também poderá solicitar permissão para mostrar notificações. A reprodução continua sendo gerenciada pelo serviço do app.
- A compatibilidade exata depende do codec interno do aparelho. Arquivos MP3, WAV PCM e OGG Vorbis padrão são os alvos; formatos encapsulados ou codecs incomuns podem não tocar.
- Esta é a primeira versão funcional e enxuta. Ainda não inclui playlists personalizadas, busca, equalizador, letras ou download de capas.
