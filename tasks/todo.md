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

## Login compartilhado com navegador temporário (2026-10-03)

- [x] Mesmos dois clientes públicos usados pelo Spotifast, sem Client ID por usuário.
- [x] Navegador Chromium efêmero no servidor, controlado pelo celular via HTTPS/WS.
- [x] Dois verificadores PKCE no Android; códigos consumidos uma vez; tokens separados
      para biblioteca/áudio, criptografados em um registro, com renovação independente.
- [x] Librespot usa a identidade de reprodução e recusa divergência entre contas.
- [x] Preservada compatibilidade com OAuth antigo e credenciais Spotify Connect.
- [x] Doze testes Node/Chromium locais e no Docker restrito, cinco testes Rust, Clippy,
      build ARM64/ARMv7, R8/lint e testes Android API 28 passaram.
- [x] Deploy na mesma porta 8787; tela oficial Spotify validada por HTTPS/WS público.
- [x] APK instalado no carro sem desinstalação; sessão antiga restaurou a biblioteca.
      Depois foi encerrada pela interface para validar o novo fluxo do zero.
- [ ] Usuário concluir as duas autorizações reais e confirmar biblioteca/reprodução.
- [ ] Reiniciar o app e verificar reprodução com a nova sessão persistida.
- [ ] Renovação automática por expiração ainda não verificada ao vivo.

A digitação do navegador remoto passa pelo servidor. A confirmação no celular
informa isso; senhas/cookies não são registrados. O processo efêmero usa sandbox,
capabilities removidas, seccomp e tmpfs. Não há persistência de sessão no servidor.
O cliente compartilhado continua dependendo de sua cota/disponibilidade no Spotify.
