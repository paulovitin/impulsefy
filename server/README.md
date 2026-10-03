# Relay de login Impulsefy

Node 22, sem dependências. Um único consentimento Spotify com Authorization Code + PKCE S256, inspirado no [Spotifast](https://github.com/crmne/spotifast). O Client ID deve ser do aplicativo Spotify do operador; nenhum Client Secret é necessário.

1. Crie um aplicativo no [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), habilite Web API e registre **exatamente** `https://SEU-DOMINIO/callback` como Redirect URI. Se o aplicativo estiver em modo de desenvolvimento, adicione a conta que vai autorizar à lista de usuários permitidos. Disponibilidade de escopos e reprodução depende das políticas da Spotify e do plano da conta.
2. Aponte o DNS do domínio para seu servidor. Copie `.env.example` para `.env` e configure `PUBLIC_URL` (somente a origem HTTPS, sem caminho) e `SPOTIFY_CLIENT_ID`.
3. Execute `docker compose up -d --build` nesta pasta, com portas 80 e 443 acessíveis. Caddy provisiona HTTPS. Não exponha a porta interna 8787 publicamente.
4. No Impulsefy, configure a mesma `PUBLIC_URL`, escaneie o QR pelo celular, compare o código nas duas telas e confirme o consentimento no Spotify. Celular e carro só precisam de internet; podem usar redes diferentes.

Para desenvolvimento local: `PUBLIC_URL=http://127.0.0.1:8787 SPOTIFY_CLIENT_ID=SEU_CLIENT_ID node relay.mjs`. HTTP só é aceito para IPs de loopback (`127.0.0.1` ou `[::1]`), e esse endereço não funciona entre dispositivos. `PORT` é opcional (8787). Execute os testes com `npm test`.

Sem `SPOTIFY_CLIENT_ID`, o servidor inicia para permitir a verificação do deploy,
mas bloqueia os endpoints de login com HTTP 503 (`spotify_not_configured`).
`/health` informa `spotifyConfigured: false`. Configure um Client ID válido e
recrie o container para habilitar o login; valores preenchidos inválidos impedem a inicialização.

O carro cria o verificador PKCE, guarda-o apenas na memória e envia somente o desafio SHA-256 e o estado aleatório. O relay devolve uma URL pública com ID aleatório e, separadamente, um segredo de consulta de 256 bits. O segredo nunca aparece no QR, HTML ou OAuth URL. O celular confirma o código e abre o Spotify; o relay conserva o código de autorização por até cinco minutos e o entrega uma vez ao carro autenticado. O carro troca o código diretamente em `accounts.spotify.com` e armazena tokens com AES-GCM no Android Keystore. O relay nunca recebe verificador, senha, access token ou refresh token.

O relay guarda apenas sessões efêmeras na RAM; reiniciar invalida QRs pendentes. Limites: 500 sessões, corpo de 2 KiB, URL de 4 KiB, 20 novas sessões/minuto e 600 requisições/minuto por IP de conexão. Atrás do Caddy, esse limite é compartilhado pelo proxy; suficiente para uma instalação doméstica. Não confia em `X-Forwarded-For`. Use uma instância; mais réplicas exigiriam armazenamento compartilhado. Não ative logs de URL no proxy/CDN, pois `/callback` contém um código de uso único.

`GET /v1/config` retorna `clientId`, `redirectUri` e `scopes`. `POST /v1/pair` recebe `{state, challenge}` e retorna `{id, pollSecret, url, displayCode, expiresAt}`. `POST /v1/pair/:id` consulta com `Authorization: Bearer pollSecret` (202 pendente, 200 código/erro, 404 expirado/usado); `DELETE` autenticado cancela. Nenhum endpoint aceita tokens Spotify. Os testes injetam uma autoridade OAuth local no construtor; a CLI de produção fixa a autoridade Spotify e não oferece override por variável de ambiente.

Referências: [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [refresh](https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens).

## Deploy em tonton

Instalado em 2026-10-03 dentro de `/home/paulo/docker`, como serviço/container
`impulsefy`, integrado ao Compose existente. A porta é **8787**, publicada em
`127.0.0.1:8787`. O Cloudflare Tunnel já executa no mesmo host e pode acessar
esse endereço. O APK não foi publicado, conforme a revisão do pedido.

- Compose: `~/docker/compose/impulsefy.yml`.
- Fontes e Dockerfile: `~/docker/dockerfiles/impulsefy/`.
- Configuração: `~/docker/appdata/impulsefy/.env` (`SPOTIFY_CLIENT_ID`).
- Origem pública configurada: `https://impulsefy.paulovitor.app`.
- Callback a cadastrar no Spotify: `https://impulsefy.paulovitor.app/callback`.

Verificado: container saudável, sete testes aprovados dentro da imagem de
produção e `/health` respondendo. O Client ID ainda está vazio, portanto o
login retorna 503. O domínio ainda não resolvia no DNS na verificação do deploy.
O túnel usa configuração remota e um token de execução; sua rota/DNS não pôde
ser administrada por essa configuração local. No Cloudflare, configure o hostname
`impulsefy.paulovitor.app` com serviço **`http://localhost:8787`** no túnel desse host.

Após preencher `SPOTIFY_CLIENT_ID` no arquivo de configuração:

```sh
cd ~/docker
docker compose up -d --no-deps --force-recreate impulsefy
curl --fail http://127.0.0.1:8787/health
```

O campo `spotifyConfigured` deve ficar `true`. Para atualizar o código, substitua
os arquivos em `dockerfiles/impulsefy/` e rode
`docker compose up -d --build --no-deps impulsefy`. Os demais serviços não precisam
ser reiniciados.
