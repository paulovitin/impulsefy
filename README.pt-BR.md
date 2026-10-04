<p align="center">
  <img src="design/assets/impulsefy.svg" width="144" height="144" alt="Logo do Impulsefy">
</p>

<h1 align="center">Impulsefy</h1>

<p align="center">Uma experiência Spotify nativa para o seu carro.</p>

<p align="center">Android 6.0+ · Login por QR pelo celular · Reprodução local</p>

<p align="center"><a href="README.md">English</a> · <strong>Português (Brasil)</strong></p>

<p align="center"><a href="#compilar-e-instalar">Compilar</a> · <a href="#como-contribuir">Contribuir</a> · <a href="#licença">Licença</a></p>

Impulsefy é um cliente Spotify não oficial desenvolvido para centrais Android
Haval/GWM. Combina uma interface pensada para toque, reprodução local com
librespot e login pelo navegador do celular. **A reprodução exige Spotify Premium
e acesso à internet.** Não é necessário servidor do Impulsefy, Client ID próprio
do Spotify ou aplicativo auxiliar.

## Capturas de tela

![Início do Impulsefy com dados fictícios](docs/screenshots/02-home.png)

| Biblioteca | Playlist |
| --- | --- |
| ![Biblioteca](docs/screenshots/03-library.png) | ![Playlist](docs/screenshots/04-playlist.png) |

[Veja as nove telas](docs/screenshots/README.md). Capturadas em um emulador
Android 9 a 1920×720. Maya Costa, os artistas, as músicas e as playlists são
fictícios; as 12 capas são ilustrações vetoriais originais. O QR de login é ilustrativo.

## Funcionalidades

- Login por QR pelo site do Spotify no navegador de um celular Android ou iPhone.
- Biblioteca com playlists, álbuns, artistas e faixas recentes; músicas curtidas,
  filtros de busca, pesquisas recentes e busca por voz quando disponível.
- Controles de reprodução, aleatório, repetição, curtir/descurtir, fila local e
  controles de mídia do Android por MediaSession.
- Volume nativo de mídia em centrais Haval compatíveis, com controle do volume
  do app nos demais aparelhos. As barras do Android continuam disponíveis.
- Prévia identificada pelo botão **Conhecer a interface**, sem login, com dados
  fictícios e sem reprodução de áudio.

A fila contém a seleção carregada no Impulsefy. O histórico recente guarda até
60 faixas e é apagado ao desconectar a conta. Downloads offline não estão
implementados. A interface atual está em português do Brasil.

**Estado:** versão 0.1.0. Pareamento, biblioteca, busca de músicas, reprodução e
volume nativo foram verificados no carro. Alguns controles e operações de
catálogo mais recentes ainda precisam de validação completa com conta real;
consulte o [histórico de desenvolvimento e as pendências](tasks/todo.md).

## Compilar e instalar

Compile no macOS ou Linux com:

- JDK 17 e Rust instalado pelo rustup.
- Ferramentas de linha de comando do Android SDK (`sdkmanager`) no `PATH`.
- Android SDK 36, Build Tools 36.0.0 e NDK 27.2.12479018.
- Android platform-tools (`adb`) para instalação e testes no emulador.

O app usa **compileSdk 36, minSdk 23 e targetSdk 28**. Configure os caminhos
locais e execute:

```sh
export JAVA_HOME=/caminho/do/jdk-17
export ANDROID_HOME=/caminho/do/android-sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.2.12479018'
./scripts/build-apk.sh
```

Isso compila a biblioteca nativa ARM64 para Haval, executa R8 e lint release e
gera `artifacts/impulsefy.apk`, com um arquivo SHA-256 ao lado. O script assina
com a chave de desenvolvimento da máquina; guarde-a para instalar atualizações
sobre o mesmo app. Executar `./gradlew assembleRelease` sem `-PlocalSigning=true`,
após compilar as bibliotecas nativas, gera um APK release sem assinatura para
seu próprio processo de assinatura.

O APK release contém apenas `arm64-v8a`, a arquitetura usada pela central Haval
testada. O build x86-64 fica reservado aos testes no emulador.

Escolha o aparelho em `adb devices`, substitua `DEVICE_SERIAL` e instale:

```sh
adb devices
adb -s DEVICE_SERIAL install -r artifacts/impulsefy.apk
```

Mantenha os arquivos de build, as chaves de assinatura e as configurações locais
fora do Git. O repositório inclui o Gradle wrapper e o lockfile do Cargo para
preservar as versões usadas na compilação.

## Entrar na conta

1. Abra o Impulsefy no carro. A tela de login gera um QR automaticamente.
2. Leia o QR com o celular para abrir `https://spotify.com/pair` com o código preenchido.
3. Entre na conta e confirme no Spotify. O carro recebe a autorização automaticamente.

A credencial salva é reutilizada nas próximas aberturas. Revogar a sessão ou
apagar os dados do app exige um novo pareamento. O carro solicita a autorização
de dispositivo e renova tokens diretamente com o Spotify; tokens e credenciais
de reprodução são criptografados com AES-GCM e uma chave no Android Keystore.

Biblioteca e busca usam a sessão do librespot. Essa integração não oficial
depende da compatibilidade dos endpoints e do cliente público com o Spotify.
O servidor de login legado foi removido do repositório.

## Desenvolvimento

A interface usa Java e Views nativas do Android. Rust gerencia as sessões Spotify
e a decodificação pelo librespot; JNI conecta esse motor ao AudioTrack e ao
serviço Android.

| Local | Finalidade |
| --- | --- |
| `app/src/main/java/com/impulsefy/` | Telas, autenticação, serviço de reprodução, capas e volume do carro |
| `app/src/androidTest/` | Verificações Android e captura de screenshots |
| `native/src/` | Motor Rust, integração JNI e operações do catálogo |
| `native/proto/` | Esquemas de protocolo usados para gerar código Rust durante o build |
| `native/vendor/librespot-core/` | Dependência local com patch documentado para pareamento no Android |
| `design/` | Referência Pencil, logo e imagens de design |
| `docs/screenshots/` | Capturas mais recentes com dados fictícios |
| `scripts/` | Comandos de build, teste e captura |
| `tasks/todo.md` | Etapas do desenvolvimento e validações pendentes |

O [arquivo Pencil](design/impulsefy.pen) é uma referência de design. O
[patch de compatibilidade do librespot](native/vendor/librespot-core/IMPULSEFY.md)
alinha a identidade do client-token para o pareamento de dispositivo e preserva
as sessões legadas.

### Verificações

Na raiz do repositório, com os pré-requisitos de build configurados:

```sh
cargo fmt --manifest-path native/Cargo.toml -- --check
cargo test --manifest-path native/Cargo.toml --locked
cargo clippy --manifest-path native/Cargo.toml --locked --all-targets -- -D warnings
```

Para as verificações Android, inicie um emulador de teste sem conta Spotify
conectada. Compile primeiro a biblioteca nativa para a ABI dele: use `arm64-v8a`
abaixo para um emulador ARM64 ou substitua por `x86_64` para um emulador x86-64.

```sh
./scripts/build-native.sh arm64-v8a
./gradlew lintDebug --console=plain
ANDROID_SERIAL=emulator-5554 ./scripts/test-android.sh
```

O script Android compila e instala o app debug e a instrumentação no emulador.
Verifica navegação, decodificação do QR renderizado, autorização e renovação,
Keystore, JNI, recuperação do AudioTrack, foco de áudio, volume e privacidade da
prévia. As respostas de autenticação são simuladas; esses testes não substituem
a validação com conta real. Os scripts recusam números de série de aparelhos físicos.

### Atualizar screenshots

Use um emulador Android 9 sem conta conectada e compile sua biblioteca nativa
como acima. A captura atualiza os nove PNGs em `docs/screenshots/`:

```sh
adb -s emulator-5554 shell wm size 1920x720
adb -s emulator-5554 shell wm density 160
ANDROID_SERIAL=emulator-5554 ./scripts/capture-screenshots.sh
```

Mantenha o perfil e as capas fictícios e revise as imagens antes do commit.
Não inclua dados pessoais da conta, códigos ativos de pareamento ou credenciais.

## Como contribuir

Pull requests executam verificações automáticas de Rust e Android. Quando os
workflows estiverem na `main`, um PR mergeado com CI aprovado poderá gerar uma
release com APK assinado e changelog automático. Veja [CI, releases e assinatura](docs/releases.md)
para entender o versionamento e os secrets necessários no GitHub Actions.

Relatos de bugs, informações sobre compatibilidade de aparelhos, melhorias de
documentação e alterações de código com escopo definido são bem-vindos.

1. Consulte as issues existentes e as [tarefas pendentes](tasks/todo.md). Para uma
   funcionalidade maior, descreva primeiro o caso de uso e o escopo em uma issue.
2. Faça um fork e crie uma branch para uma alteração específica. Preserve a
   interface nativa, as barras do Android e o controle alternativo de volume.
3. Execute as verificações relevantes para a mudança. Inclua um teste de regressão
   ao corrigir comportamento; em mudanças apenas de documentação, confira links
   e comandos.
4. Atualize os dois READMEs quando alterar a configuração ou o funcionamento.
   Use dados fictícios nos screenshots e preserve licenças e créditos de terceiros.
5. Abra um pull request descrevendo o problema, o comportamento resultante,
   as validações executadas e as verificações pendentes. Inclua capturas de antes
   e depois em alterações visuais.

Ao relatar um bug, informe o modelo do carro/central, versões do Android e do app,
passos para reproduzir, comportamento esperado e observado e logs sem dados
sensíveis, quando disponíveis. Teste no carro estacionado. Nunca publique tokens,
cookies, senhas, chaves de assinatura ou dados pessoais em issues e pull requests.

Agentes de IA devem ler [AGENTS.md](AGENTS.md) antes de alterar o código. O arquivo
apresenta a estrutura, as restrições de implementação e os comandos de validação.

## Licença

O código-fonte do Impulsefy está sob a **GNU AGPLv3**; veja [LICENSE](LICENSE).

- Uso, modificação, redistribuição e uso comercial são permitidos.
- A distribuição exige preservar os avisos e fornecer o código-fonte
  correspondente nos termos da licença, incluindo modificações abrangidas.
- Se usuários interagirem com sua versão modificada pela rede, ela deve oferecer
  a eles o código-fonte correspondente, conforme a seção 13.

Este é um resumo; prevalece o [texto completo da AGPLv3](https://www.gnu.org/licenses/agpl.en.html).
Contribuições ao código do Impulsefy devem usar a mesma licença.

Componentes e recursos de terceiros mantêm suas próprias licenças. Principais exemplos:

| Componente | Licença / créditos |
| --- | --- |
| librespot e a cópia local de `librespot-core` | [MIT](native/vendor/librespot-core/LICENSE) |
| ZXing core | [Apache 2.0](https://github.com/zxing/zxing/blob/zxing-3.5.4/LICENSE) |
| Fonte Inter | [SIL Open Font License 1.1](app/src/main/assets/fonts/OFL.txt) |
| Fotografias do Unsplash incluídas | [Licença Unsplash](https://unsplash.com/license); autores nos [créditos de imagens](app/src/main/assets/artwork-credits.txt) |

As demais dependências têm seus próprios termos; mantenha os avisos ao
redistribuir. Músicas, capas do catálogo e marcas do Spotify não são licenciadas
por este repositório.

Agradecimentos ao [librespot](https://github.com/librespot-org/librespot) pelo
motor de áudio, ao [go-librespot](https://github.com/devgianlu/go-librespot) pela
referência de pareamento e ao [Spotifast](https://github.com/crmne/spotifast) pelas
referências de arquitetura e autenticação. Impulsefy é um projeto independente,
sem vínculo com Spotify ou Haval/GWM.
