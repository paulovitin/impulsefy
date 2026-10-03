# Relay de login Impulsefy

Node 22, sem dependências. Um único consentimento Spotify com Authorization Code + PKCE S256, inspirado no [Spotifast](https://github.com/crmne/spotifast). O Client ID deve ser do aplicativo Spotify do operador; nenhum Client Secret é necessário.

1. Crie um aplicativo no [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), habilite Web API e registre **exatamente** `https://SEU-DOMINIO/callback` como Redirect URI. Se o aplicativo estiver em modo de desenvolvimento, adicione a conta que vai autorizar à lista de usuários permitidos. Disponibilidade de escopos e reprodução depende das políticas da Spotify e do plano da conta.
2. Aponte o DNS do domínio para seu servidor. Copie `.env.example` para `.env` e configure `PUBLIC_URL` (somente a origem HTTPS, sem caminho) e `SPOTIFY_CLIENT_ID`.
3. Execute `docker compose up -d --build` nesta pasta, com portas 80 e 443 acessíveis. Caddy provisiona HTTPS. Não exponha a porta interna 8787 publicamente.
4. No Impulsefy, configure a mesma `PUBLIC_URL`, escaneie o QR pelo celular, compare o código nas duas telas e confirme o consentimento no Spotify. Celular e carro só precisam de internet; podem usar redes diferentes.

Para desenvolvimento local: `PUBLIC_URL=http://127.0.0.1:8787 SPOTIFY_CLIENT_ID=SEU_CLIENT_ID node relay.mjs`. HTTP só é aceito para IPs de loopback (`127.0.0.1` ou `[::1]`), e esse endereço não funciona entre dispositivos. `PORT` é opcional (8787). Execute os testes com `npm test`.

O carro cria o verificador PKCE, guarda-o apenas na memória e envia somente o desafio SHA-256 e o estado aleatório. O relay devolve uma URL pública com ID aleatório e, separadamente, um segredo de consulta de 256 bits. O segredo nunca aparece no QR, HTML ou OAuth URL. O celular confirma o código e abre o Spotify; o relay conserva o código de autorização por até cinco minutos e o entrega uma vez ao carro autenticado. O carro troca o código diretamente em `accounts.spotify.com` e armazena tokens com AES-GCM no Android Keystore. O relay nunca recebe verificador, senha, access token ou refresh token.

O relay guarda apenas sessões efêmeras na RAM; reiniciar invalida QRs pendentes. Limites: 500 sessões, corpo de 2 KiB, URL de 4 KiB, 20 novas sessões/minuto e 600 requisições/minuto por IP de conexão. Atrás do Caddy, esse limite é compartilhado pelo proxy; suficiente para uma instalação doméstica. Não confia em `X-Forwarded-For`. Use uma instância; mais réplicas exigiriam armazenamento compartilhado. Não ative logs de URL no proxy/CDN, pois `/callback` contém um código de uso único.

`GET /v1/config` retorna `clientId`, `redirectUri` e `scopes`. `POST /v1/pair` recebe `{state, challenge}` e retorna `{id, pollSecret, url, displayCode, expiresAt}`. `POST /v1/pair/:id` consulta com `Authorization: Bearer pollSecret` (202 pendente, 200 código/erro, 404 expirado/usado); `DELETE` autenticado cancela. Nenhum endpoint aceita tokens Spotify. Os testes injetam uma autoridade OAuth local no construtor; a CLI de produção fixa a autoridade Spotify e não oferece override por variável de ambiente.

Referências: [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [refresh](https://developer.spotify.com/documentation/web-api/tutorials/refreshing-tokens).
