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
- [ ] Build, lint e verificação independente do código consolidado.
- [x] Capturas de tela, tamanho e medidas de execução.
- [x] Documentar instalação, publicação da ponte e limites verificados.
- [ ] Login e reprodução reais com conta Premium após publicação/configuração da ponte.

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
- `cargo test --manifest-path native/Cargo.toml --locked`: três testes da fila,
  credencial e ordenação de eventos. Clippy com `-D warnings` passou.
- `./scripts/test-android.sh`: passou no emulador API 28 ARM64, 1920×720,
  densidade 240. Navegação, QR renderizado decodificado, confirmação no celular,
  negação devolvida ao Android, Keystore real, JNI e AudioTrack com PCM silencioso.
- `./scripts/build-apk.sh`: passou com R8 e lint release (sem erros).
- `docker compose config --quiet`: configuração válida com domínio e Client ID de exemplo.
- Medida inicial da prévia: aproximadamente 31 MB de PSS, nenhum WebView;
  abertura quente de 177 ms no emulador. Não é medida de reprodução autenticada
  nem benchmark do hardware real do carro.
- Capturas em `artifacts/preview-1920x720.png`, `login-1920x720.png`, `qr-smoke.png`.

Pendente: revisão independente do código consolidado. Não há conta Premium,
Client ID próprio ou domínio de publicação configurados nesta árvore. Por isso,
o consentimento bem-sucedido no Spotify, refresh ao vivo, reprodução de uma música
real e comportamento no hardware físico permanecem sem validação. A ponte é
entregue para o usuário publicar, conforme solicitado; não foi publicada.
