# Veyra Player

Reprodutor de música Android nativo, simples e offline, feito em Kotlin sem bibliotecas de terceiros.
Sem anúncios, sem conta, sem internet. Pensado para ser leve e rodar bem em aparelhos simples.

## O que já está implementado

- Leitura da biblioteca de áudio local pelo MediaStore (a lista se atualiza sozinha ao abrir o app).
- Reprodução, pausa, faixa anterior e próxima, com botões em formato de ícone.
- Continuar de onde parou: o app lembra a última música e o ponto em que estava.
- Timer para dormir (15, 30, 45 minutos, 1 hora ou 1h30), com o volume caindo aos poucos no final.
- Barra de progresso (toque ou arraste para avançar/voltar na música).
- Modo aleatório e repetir (desligado, lista inteira ou uma faixa).
- Barra de pesquisa: filtra por nome, artista ou nome original, sem diferenciar maiúsculas nem acentos ("cancao" acha "Canção").
- Ordenar a lista por nome, artista ou mais recentes.
- Playlists simples: crie, renomeie e exclua listas; segure o dedo numa música para adicioná-la a uma lista.
- Renomear músicas dentro do app (segure o dedo na música); o arquivo no aparelho nunca é alterado.
- Reprodução em segundo plano por serviço em primeiro plano, com notificação de mídia (anterior, tocar/pausar, próxima e fechar).
- Controle pelos botões do fone (play/pause, próxima, anterior), com fio ou Bluetooth.
- Pausa automática quando o fone é desconectado (pode ser desligada nas configurações).
- Tema Escuro (padrão) ou Claro, nas configurações, com fundo padrão do app.
- Atalho nas configurações para as configurações de som do Android.
- Botão para redefinir as configurações ao padrão (não apaga playlists nem nomes personalizados).
- Seção "Sobre o app" com versão, número da compilação, tipo (release ou debug) e créditos.
- Tela sempre em modo retrato.

## Gerar os APKs sem instalar Android SDK no seu PC

O projeto inclui o workflow `.github/workflows/build-apk.yml`. Abra a aba **Actions** do repositório e execute
**Build Android APK** (ele também roda sozinho a cada envio de arquivos). Ao terminar, baixe na execução:

- `VeyraPlayer-debug`: APK de testes (a "garantia").
- `VeyraPlayer-release`: APK enxuto (código e recursos sem uso removidos), que tende a usar menos memória.

Os dois são assinados com a mesma chave (`app/signing/veyra.jks`), então uma versão nova instala por cima da anterior
sem apagar playlists nem configurações. Atenção: essa chave é pública (está no repositório) e serve para uso pessoal.
Para publicar em loja, use uma chave privada.

## Requisitos para compilar localmente

- Android Studio recente, JDK 17 e Android SDK Platform 35.

Abra a pasta do projeto, aguarde a sincronização do Gradle e use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Observações

- O Android solicitará permissão para acessar arquivos de áudio e, no Android 13 ou superior, para mostrar notificações.
- A compatibilidade exata depende do codec do aparelho. MP3, WAV e OGG são os alvos.
- Ainda não inclui equalizador, letras nem capas de álbum, de propósito, para continuar leve.
