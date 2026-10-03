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
3. Toque em conectar, leia o QR no celular e confira o código.
4. Entre no Spotify pela sessão temporária exibida no celular. Autorize biblioteca
   e áudio com a mesma conta; são dois consentimentos dentro do mesmo navegador.
5. O carro troca os códigos por tokens e salva as autorizações no Android Keystore.
   Nos próximos usos, reutiliza a sessão salva.

O usuário não precisa criar um aplicativo no Spotify Developer Dashboard. Como no
[Spotifast](https://github.com/crmne/spotifast/blob/main/src/auth.rs), a biblioteca usa
um Client ID público compartilhado e o áudio usa uma autorização separada de
reprodução. O aplicativo compartilhado tem cota global e depende da disponibilidade
desses clientes no Spotify; isso não garante acesso ilimitado para qualquer conta.
É necessário Spotify Premium para reproduzir pelo librespot.

O callback loopback do desktop voltaria ao próprio celular. Para recebê-lo, a ponte
abre um Chromium temporário no servidor e transmite sua tela e os comandos do usuário
por HTTPS/WebSocket. O celular controla a página real do Spotify nesse navegador.
A digitação passa pelo servidor; confie no operador do endereço configurado. Senhas,
cookies, telas e teclas não são registrados pelo serviço. O processo e o perfil
temporário são descartados ao terminar, cancelar ou expirar o QR.

Os dois verificadores PKCE ficam somente na memória do carro. A ponte entrega os
códigos uma única vez a quem possui o segredo de consulta; a troca e a renovação dos
tokens OAuth acontecem diretamente entre Android e Spotify. Os tokens e a credencial
de reprodução persistem criptografados com AES-GCM e chave no Android Keystore.
A conta do áudio precisa coincidir com a conta da biblioteca.

Celular e carro precisam de internet, mas podem usar redes diferentes. As sessões
antigas continuam compatíveis: um relay sem `BROWSER_LOGIN=1` usa o Client ID do
operador com callback HTTPS e a primeira autorização de áudio via Spotify Connect
na mesma rede Wi-Fi. Esse caminho continua disponível para instalações existentes.

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
npm --prefix server ci
npm --prefix server exec -- playwright install --only-shell chromium
npm --prefix server test
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
