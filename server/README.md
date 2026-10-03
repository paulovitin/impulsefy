# Login Impulsefy

Node 22+, Playwright/Chromium e WebSocket. O novo login usa os mesmos clientes
públicos da [autenticação do Spotifast](https://github.com/crmne/spotifast/blob/main/src/auth.rs):

| Permissão | Client ID | Callback interno |
| --- | --- | --- |
| Biblioteca | `d420a117a32841c2b3474932e49fb54b` | `http://127.0.0.1:8989/login` |
| Reprodução | `65b708073fc0480ea92a077233ca87bd` | `http://127.0.0.1:8898/login` |

Nenhum usuário precisa informar Client ID ou Client Secret. O cliente compartilhado
continua sujeito à cota global, às permissões e à disponibilidade definidas pelo
Spotify. Reprodução exige Premium. A autorização real desse novo fluxo ainda está
em validação no carro; os testes locais não provam disponibilidade para todas as contas.

## Publicar

1. Configure DNS e HTTPS para o servidor.
2. Copie `.env.example` para `.env` e preencha `PUBLIC_URL`, somente a origem HTTPS.
3. Execute `docker compose up -d --build` nesta pasta. O Compose habilita
   `BROWSER_LOGIN=1`; Caddy publica HTTPS nas portas 80/443.
4. Use um APK atualizado e o mesmo endereço HTTPS no carro. Escaneie o QR,
   compare os códigos e conclua os dois consentimentos com a mesma conta Spotify.

Não é necessário publicar as portas 8989/8898: os callbacks são interceptados
dentro do navegador temporário. A única porta do serviço é 8787, acessível pelo
proxy. O fluxo antigo continua disponível quando `SPOTIFY_CLIENT_ID` está
configurado com `https://SEU-DOMINIO/callback` cadastrado no Spotify Dashboard.
APKs antigos precisam desse Client ID; uma instalação nova pode deixá-lo vazio.

O Chromium roda como usuário `node`, com sandbox ativo, filesystem somente leitura,
perfil temporário em tmpfs, sem capabilities e com `no-new-privileges`. O perfil
[seccomp do Playwright 1.63.0](https://github.com/microsoft/playwright/blob/v1.63.0/utils/docker/seccomp_profile.json)
está incluído em `chromium-seccomp.json`, com `chroot` permitido também após
`cap_drop: ALL`, para o sandbox dentro do user namespace. Não use `--no-sandbox`.

## Fluxo e dados

O Android gera dois estados e dois verificadores PKCE independentes. Envia somente
estados e desafios SHA-256 ao relay. O QR contém o ID público do pareamento; o segredo
de consulta de 256 bits fica no carro.

Após comparar o código, o celular recebe um cookie HttpOnly/Secure/SameSite=Strict,
restrito ao pareamento, para controlar o navegador via WebSocket da mesma origem.
O relay abre um processo Chromium exclusivo, exibe a página real do Spotify e
encaminha toques, rolagem e digitação. A digitação passa pelo servidor; a página de
confirmação informa isso. O serviço não registra teclas, senhas, cookies ou telas.
O host da página aparece acima da imagem; não há barra para navegar a endereços
arbitrários. As requisições são restritas aos domínios Spotify e provedores de login.

Os dois consentimentos reaproveitam a mesma sessão do navegador. Cada callback
valida origem/caminho, estado, parâmetros duplicados e código; não aceita códigos
pela rota pública `/callback`. Os códigos são entregues uma única vez ao carro
com o segredo correto. O Android os troca diretamente no Spotify, verifica o perfil
e guarda ambos os tokens em um único registro criptografado. O player usa a
autorização de reprodução e verifica que pertence à mesma conta da biblioteca.

O navegador e seus cookies são descartados ao concluir, cancelar ou expirar a
sessão. Limite de nove minutos, três navegadores simultâneos e 500 pareamentos.
Corpo HTTP de 2 KiB, mensagem WebSocket de 4 KiB, fila de entrada limitada; 20 novos
pareamentos e 600 requisições/minuto por IP de conexão. Atrás do proxy, a cota por IP
é compartilhada. Não confia em `X-Forwarded-For`. Sessões ficam na memória: reiniciar
o serviço invalida os QRs abertos. Use uma instância.

Não habilite logs de URLs, corpos, headers de autorização, frames ou tráfego do
navegador no proxy/CDN. O caminho legado `/callback` contém um código de uso único.
Os perfis efêmeros não devem ser montados em volumes persistentes.

## API e testes

`GET /v1/config?browser=1` retorna `mode: browser`, a configuração da biblioteca e
`playback` com a configuração de áudio. `POST /v1/pair` recebe
`{mode:"browser",state,challenge,playback:{state,challenge}}` e devolve
`{id,pollSecret,url,displayCode,expiresAt}`. O APK fixa os IDs/callbacks aceitos.

`POST /v1/pair/:id` consulta com `Authorization: Bearer pollSecret`: 202 pendente,
200 com `{code,state,playback:{code,state}}` ou erro, 404 expirado/usado.
`DELETE` autenticado cancela. `GET /health` informa `spotifyConfigured`; sem
`BROWSER_LOGIN=1` e sem Client ID, o login retorna 503. O protocolo v1 anterior
continua aceitando `{state,challenge}` e callback HTTPS com o ID do operador.

```sh
npm ci
npx playwright install --only-shell chromium
npm test
PUBLIC_URL=http://127.0.0.1:8787 BROWSER_LOGIN=1 node relay.mjs
```

HTTP só é permitido em IPs loopback; para conectar o celular use HTTPS público.
Os testes cobrem PKCE, replay, CSRF/Origin, expiração, limites, cookie por sessão,
controle WebSocket, callbacks bloqueados e os dois consentimentos em Chromium real
com uma autoridade OAuth simulada. A CLI fixa o Spotify como autoridade.

## Deploy em tonton

- Serviço/container: `impulsefy`, no Compose de `/home/paulo/docker`.
- Porta: **127.0.0.1:8787**, publicada pelo Cloudflare Tunnel existente.
- Domínio: **https://impulsefy.paulovitor.app**.
- Compose: `~/docker/compose/impulsefy.yml`.
- Fontes: `~/docker/dockerfiles/impulsefy/`.
- Configuração legada: `~/docker/appdata/impulsefy/.env` (permissão 600).
- `BROWSER_LOGIN=1` configurado no Compose. Os outros serviços não são reiniciados.
- O APK não é publicado pelo servidor.

```sh
cd ~/docker
docker compose up -d --build --no-deps impulsefy
curl --fail http://127.0.0.1:8787/health
```

Verificado em 2026-10-03: doze testes passaram dentro da imagem Docker com sandbox,
mesmas restrições de produção; domínio HTTPS, cookie de confirmação e WebSocket
transmitindo a página real de login Spotify em viewport de celular. O APK atualizado
foi instalado por ADB e gerou o QR de nove minutos. A conclusão real das duas
autorizações e a reprodução estão aguardando a entrada do usuário no Spotify.
