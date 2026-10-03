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
./scripts/build-apk.sh -PrelayUrl=https://musica.seudominio.com
adb install -r artifacts/impulsefy.apk
```

O script gera um APK otimizado com R8, assinado com a chave de desenvolvimento da máquina. Guarde essa
chave para instalar atualizações sem remover o app. O build `release` produz
um APK otimizado sem assinatura, para assinatura com sua própria chave.

## Login no carro

1. Publique a ponte seguindo [server/README.md](server/README.md).
2. Inclua seu endereço HTTPS no build acima, ou informe-o uma vez em
   **Endereço de conexão** no aplicativo.
3. Toque em conectar, leia o QR no celular, confira o código e autorize no Spotify.
4. Para autorizar o áudio na primeira vez, conecte celular e carro à mesma rede
   Wi-Fi. No Spotify do celular, usando a mesma conta, abra **Dispositivos** e
   selecione **Impulsefy**. O aplicativo aguarda essa seleção por cinco minutos;
   para tentar novamente, toque em reproduzir no carro.
5. O carro salva as autorizações da biblioteca e do áudio para os próximos usos.

A senha é digitada apenas no Spotify. O verificador PKCE e os tokens permanecem
no carro; a ponte transporta somente o código temporário e o descarta após a
entrega. As credenciais persistentes usam AES-GCM com chave no Android Keystore.

O login segue a estratégia Authorization Code + PKCE S256 do
[Spotifast](https://github.com/crmne/spotifast/blob/main/src/auth.rs).
O callback loopback do desktop não funciona entre celular e carro: ele voltaria
ao próprio celular. A ponte adapta esse retorno para dois dispositivos e exige
um Client ID próprio com o callback HTTPS cadastrado. Nenhum client secret é
necessário. O login por QR só precisa de acesso à internet. A autorização inicial
do áudio usa Spotify Connect na rede local; depois disso, o carro reutiliza a
credencial salva e reproduz diretamente pela internet.

O token OAuth da biblioteca não é usado como autorização de áudio no login5:
esse fluxo rejeita tokens de apps próprios com `INVALID_CREDENTIALS` ou
`BAD_REQUEST`. A autorização recebida pelo Spotify Connect precisa corresponder
à conta verificada pelo OAuth; uma conta diferente é recusada. O anúncio local
é encerrado após a seleção, ao cancelar ou quando o prazo expira.

É necessário Spotify Premium para reprodução pelo librespot. Apps Spotify em
Development Mode também exigem Premium do proprietário e têm limites de
usuários e de acesso a playlists. Consulte as
[regras atuais de Development Mode](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

## Escopo

Biblioteca, músicas curtidas, busca, playlists, fila local, controles de
reprodução e integração com os controles de mídia do Android. Sem downloads
offline, anúncios próprios, vídeos, equalizador ou telas de configuração extensas.
**Conhecer a interface** abre uma prévia claramente identificada, sem login e
sem simular áudio real.

## Componentes

- [Spotifast](https://github.com/crmne/spotifast): referência para autenticação e arquitetura.
- [librespot](https://github.com/librespot-org/librespot): cliente e decodificação de áudio, licença MIT.
- [ZXing](https://github.com/zxing/zxing): geração local do QR, Apache 2.0.
- [Inter](https://github.com/google/fonts/tree/main/ofl/inter): fonte, SIL OFL (incluída nos assets).

Estado das verificações e limites de validação: [tasks/todo.md](tasks/todo.md).

## Verificar

```sh
node --test server/relay.test.mjs
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
ANDROID_SERIAL=emulator-5554 ./scripts/test-android.sh
```

Use um emulador de teste sem uma conta conectada e deixe a porta local 8787
livre. O teste Android percorre a prévia, decodifica o QR efetivamente renderizado,
confere PKCE e cancelamento pelo celular, exercita o Keystore, chama Rust via JNI
e escreve PCM silencioso no AudioTrack, incluindo recuperação de saída inválida
e restauração de volume após interrupção. Os testes de OAuth usam uma autoridade
local simulada; não substituem a validação com uma conta Spotify real.
