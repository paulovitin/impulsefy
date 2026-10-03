# Impulsefy

Spotify mínimo para a central Android do carro, com interface nativa inspirada
no Impulse Home, reprodução local com librespot e login pelo celular via QR.

## Android

Mesmos SDKs do `impulse-home`: **compileSdk 36, minSdk 23, targetSdk 28**.
Java e Views nativas, sem WebView. A fonte é Inter; as cores e os cartões
seguem a referência visual de `impulsefy.pen`. O áudio usa AudioTrack e MediaSession.

Pré-requisitos: JDK 17, Android SDK 36, Build Tools 36.0.0, NDK
27.2.12479018, Rust e Node 22 ou mais novo para os testes da ponte.

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018'
export ANDROID_HOME=/caminho/do/android-sdk
./scripts/build-apk.sh
adb install -r artifacts/impulsefy.apk
```

O script gera um APK otimizado com R8, assinado com a chave de desenvolvimento da máquina. Guarde essa
chave para instalar atualizações sem remover o app. O build `release` produz
um APK otimizado sem assinatura, para assinatura com sua própria chave.

## Login no carro

1. Instale o APK e toque em **Conectar Spotify**.
2. Leia o QR no celular. Ele abre `https://spotify.com/pair` com o código preenchido.
3. Entre e confirme no Spotify. O carro recebe a autorização automaticamente.

Funciona pelo navegador do Android ou iPhone, sem app auxiliar, cópia de URLs,
Client ID próprio ou servidor de login. A mesma sessão carrega playlists, músicas
curtidas e busca, e autoriza o áudio. Nos próximos usos, o carro reutiliza a credencial
salva; uma sessão revogada ou dados apagados exigem novo pareamento. É necessário
Spotify Premium para reprodução com librespot.

O fluxo usa a autorização de dispositivo implementada pelo
[go-librespot](https://github.com/devgianlu/go-librespot/blob/master/session/oauth2.go),
com o cliente público `65b708073fc0480ea92a077233ca87bd` e a permissão `streaming`.
O carro solicita e consulta o código diretamente no Spotify. O QR contém apenas o
código público; o segredo de consulta fica em memória no carro. Tokens e credenciais
persistem criptografados com AES-GCM e chave no Android Keystore.

Biblioteca e busca usam os endpoints da sessão librespot, sem depender da cota do
cliente compartilhado na Web API. Essa integração não oficial depende da
compatibilidade desses endpoints e do cliente público com o Spotify.

Sessões anteriores continuam compatíveis. A [ponte HTTPS](server/README.md)
permanece publicada para APKs antigos, na porta local 8787 de tonton. O APK atual
não usa essa ponte; cada instalação precisa apenas de acesso à internet.

## Escopo

Biblioteca, músicas curtidas, busca, playlists, fila local, controles de
reprodução e integração com os controles de mídia do Android. Sem downloads
offline, anúncios próprios, vídeos, equalizador ou telas de configuração extensas.
**Conhecer a interface** abre uma prévia claramente identificada, sem login e
sem simular áudio real.

## Componentes

- [Spotifast](https://github.com/crmne/spotifast): referência para autenticação e arquitetura.
- [librespot](https://github.com/librespot-org/librespot): cliente e decodificação de áudio, licença MIT.
  A cópia de `librespot-core` tem um [patch de compatibilidade Android](native/vendor/librespot-core/IMPULSEFY.md) para a identidade do pareamento.
- [ZXing](https://github.com/zxing/zxing): geração local do QR, Apache 2.0.
- [Inter](https://github.com/google/fonts/tree/main/ofl/inter): fonte, SIL OFL (incluída nos assets).

Estado das verificações e limites de validação: [tasks/todo.md](tasks/todo.md).

## Verificar

```sh
npm --prefix server test
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
ANDROID_SERIAL=emulator-5554 ./scripts/test-android.sh
```

Use um emulador de teste sem uma conta conectada. O teste Android percorre a prévia, decodifica o QR efetivamente renderizado,
confere a autorização de dispositivo, polling, backoff e cancelamento, exercita o Keystore, chama Rust via JNI
e escreve PCM silencioso no AudioTrack, incluindo recuperação de saída inválida
e restauração de volume após interrupção. Os testes de autenticação usam respostas
simuladas; não substituem a validação com uma conta Spotify real.
