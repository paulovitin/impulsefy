# Impulsefy

Objetivo: Spotify mínimo para a central do carro, visual do impulse-home,
SDK 36 / mínimo 23 / alvo 28, áudio local e login PKCE por QR no celular.

Wave 1: [login + ponte, motor de áudio, aplicativo e build]
- [x] OAuth PKCE, sessão protegida no Android e ponte HTTPS publicável.
- [x] Librespot Android + AudioTrack + serviço/MediaSession.
- [x] Interface nativa com biblioteca, busca, curtidas e player.
- [x] Compilar APKs ARM, instalar e verificar em emulador.

Wave 2: [integração e verificações]
- [x] Testes de autenticação, fronteiras HTTP e expiração.
- [x] Build, lint e verificação independente do código consolidado.
- [x] Capturas de tela, tamanho e medidas de execução.
- [x] Documentar instalação, publicação da ponte e limites verificados.
- [x] Login e reprodução reais com conta Premium após publicação/configuração da ponte.

## Decisões

- Java com views nativas: sem WebView, React ou navegador no processo do player.
- Librespot 0.8, igual à família usada pelo Spotifast, com PCM em AudioTrack.
- PKCE S256 com verificador no carro. A ponte só transporta o código temporário.
- Aplicativo Spotify próprio para registrar o callback HTTPS da ponte. Os IDs
  públicos de terceiros têm callbacks loopback que não voltam do celular ao carro.
- O projeto começou vazio. Nenhum dispositivo ADB estava conectado.

## Revisão

Verificado em 2026-10-03:

- APK otimizado assinado localmente em `artifacts/impulsefy.apk`, cerca de 6 MB,
  com ARM64 e ARMv7. APK inspecionado: compile 36, mínimo 23, alvo 28.
- `node --test server/relay.test.mjs`: seis testes, incluindo autoridade OAuth
  simulada com verificação efetiva do desafio PKCE, negação, replay e expiração.
- `cargo test --manifest-path native/Cargo.toml --locked`: quatro testes da fila,
  credencial, ordenação de eventos e continuidade entre faixas. Clippy com `-D warnings` passou.
- `./scripts/test-android.sh`: passou no emulador API 28 ARM64, 1920×720,
  densidade 240. Navegação, QR renderizado decodificado, confirmação no celular,
  negação devolvida ao Android, Keystore real, JNI e AudioTrack com PCM silencioso.
  Regressões também verificam recriação da saída após falha de escrita e restauração
  do volume quando o foco retorna durante carregamento.
- `./scripts/build-apk.sh`: passou com R8 e lint release (sem erros).
- `docker compose config --quiet`: configuração válida com domínio e Client ID de exemplo.
- Medida inicial da prévia: aproximadamente 31 MB de PSS, nenhum WebView;
  abertura quente de 177 ms no emulador. Não é medida de reprodução autenticada
  nem benchmark do hardware real do carro.
- Capturas em `artifacts/preview-1920x720.png`, `login-1920x720.png`, `qr-smoke.png`.
- APK final: assinatura e SHA-256 verificados; instalação e abertura da versão
  otimizada no emulador passaram.
- Revisão independente dos commits `7cb6508` e `25cdd70`, com árvore limpa:
  corrigidos foco de áudio entre faixas e em pré-carregamentos indisponíveis,
  restauração do volume e descarte de AudioTrack inválido. Segunda revisão sem
  achados acionáveis. Build, lint, quatro testes Rust e Clippy confirmados pelo revisor.

Atualização de deploy: ponte instalada em `tonton`, no Compose de `~/docker`,
porta local 8787, container saudável. Client ID e callback configurados em
`https://impulsefy.paulovitor.app`; login real confirmado pelo usuário.
Sete testes da ponte passaram também na imagem de produção. Detalhes em `server/README.md`.

Validação no carro em 2026-10-03:

- O token OAuth carregava a biblioteca, mas o login5 recusava a autorização de áudio.
  O player agora recebe uma credencial própria pelo Spotify Connect na mesma rede,
  aceita somente a conta verificada pelo OAuth e salva a credencial no Keystore.
- APK atualizado por ADB com os dados preservados. Descoberta local confirmada,
  usuário selecionou Impulsefy no Spotify e confirmou estar ouvindo a música.
  MediaSession do carro em PLAYING, sem erro; multicast liberado após a autorização.
- Cinco testes Rust, incluindo recusa de credenciais de outra conta, Clippy sem
  avisos e build ARM64/ARMv7 com R8 e lint release passaram.
- A reconexão após reiniciar o app e a renovação por expiração ainda não foram
  verificadas ao vivo após essa correção; a reprodução em andamento foi preservada.

## Pareamento direto do Spotify (2026-10-03)

- [x] Autorização de dispositivo em `spotify.com/pair`, uma confirmação no navegador
      do celular, sem Client ID próprio, app auxiliar ou retorno manual de URLs.
- [x] Código privado, token e renovação diretamente entre Android e Spotify.
      Credenciais persistidas no Android Keystore; logout remove as duas credenciais.
- [x] Biblioteca, curtidas, busca e playlists pela mesma sessão do librespot.
      Leituras reais confirmadas, incluindo segunda página de biblioteca/curtidas/busca.
- [x] Corrigida a identidade do client-token no librespot Android: o cliente de
      pareamento desktop precisa de um client-token correspondente. Patch local
      em `native/vendor/librespot-core/src/spclient.rs`, sem alterar sessões legadas.
- [x] Tela de QR existente reutilizada; configuração de servidor removida do login.
      Erros do player também aparecem na biblioteca, com botão para tentar novamente.
- [x] Sete testes Rust e Clippy passaram. A dependência vendorizada mantém um aviso
      de lint upstream (`expect(deprecated)` sem depreciação nesta versão do compilador).
- [x] Instrumentação Android API 28 passou: QR real decodificado, negação, polling,
      slow_down, cancelamento, renovação, restauração do Keystore, JNI do catálogo,
      áudio, foco e compatibilidade com autorizações anteriores.
- [x] Build ARM64/ARMv7, R8/lint, assinatura e instalação no carro passaram.
- [x] Usuário autorizou o QR do próprio carro. Biblioteca carregou e reprodução
      foi confirmada pelo usuário: “ta tocando a musica meu amigo”.
- [x] A autorização foi preservada em duas atualizações do APK por ADB. O app
      reconectou usando o login salvo depois da correção do client-token.
- [x] Navegador remoto e protótipos de duas autorizações removidos da produção.
      Ponte legada na mesma porta 8787, sete testes na imagem, HTTPS saudável,
      cerca de 13 MiB em repouso. O APK novo não usa essa ponte.
- [x] APK atualizado copiado para `../impulsefy.apk`.
- [ ] Renovação real após expiração e reconexão com a credencial de reprodução
      persistida ainda não observadas ao vivo; a música em andamento foi preservada.

O teste local com token da conta foi temporário; arquivos de token foram removidos.
A integração continua dependendo dos endpoints e do cliente público do Spotify.
Não houve publicação do APK no servidor, conforme o escopo corrigido pelo usuário.
