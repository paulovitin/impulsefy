# Ponte de compatibilidade

**O APK atual não precisa desta ponte para login, biblioteca ou reprodução.**
Ele solicita um código diretamente ao Spotify, mostra o QR de `spotify.com/pair`
e recebe a autorização automaticamente. Biblioteca e busca usam a sessão do player.

Este serviço Node 22, sem dependências npm, permanece disponível para versões
anteriores que usam OAuth PKCE com callback HTTPS. Não executa Chromium, não
transmite telas e não recebe senhas ou cookies do Spotify.

## Publicar para APKs antigos

1. Configure DNS e HTTPS.
2. Copie `.env.example` para `.env`; preencha `PUBLIC_URL` e `SPOTIFY_CLIENT_ID`.
3. Cadastre `https://SEU-DOMINIO/callback` no aplicativo Spotify do operador.
4. Execute `docker compose up -d --build` nesta pasta.

Os APKs antigos geram um estado e verificador PKCE. A ponte recebe somente o estado
e o desafio SHA-256; o verificador fica no carro. Após a confirmação pelo celular,
a ponte recebe o callback e entrega o código uma única vez a quem possui o segredo
de consulta. A troca e renovação dos tokens acontecem diretamente no Android.

Sessões ficam na memória por cinco minutos, com no máximo 500 pareamentos.
Reiniciar o serviço invalida os QRs abertos. O serviço valida Origin/CSRF, estado,
callback e consumo único. Não registre códigos de callback, corpos ou cabeçalhos
de autorização no proxy/CDN. Use uma instância.

## Testes

```sh
npm test
PUBLIC_URL=http://127.0.0.1:8787 SPOTIFY_CLIENT_ID=SEU_ID node relay.mjs
```

HTTP é aceito somente em loopback; o celular usa HTTPS público. Os sete testes
cobrem PKCE, consentimento, expiração, CSRF/Origin, consumo único e indisponibilidade
de configuração. O container usa usuário `node`, filesystem somente leitura,
sem capabilities, com `no-new-privileges`, 128 MiB, uma CPU e 64 processos.

## Deploy em tonton

- Serviço/container: `impulsefy`, no Compose de `/home/paulo/docker`.
- Porta: **127.0.0.1:8787**, publicada pelo Cloudflare Tunnel existente.
- Domínio: **https://impulsefy.paulovitor.app**.
- Compose: `~/docker/compose/impulsefy.yml`.
- Fontes: `~/docker/dockerfiles/impulsefy/`.
- Configuração: `~/docker/appdata/impulsefy/.env`, permissão 600.
- O APK não é publicado pelo servidor.

```sh
cd ~/docker
docker compose up -d --build --no-deps impulsefy
curl --fail http://127.0.0.1:8787/health
```

O navegador remoto e o fluxo de copiar duas URLs foram substituídos pelo
pareamento direto do Spotify no APK. Duzentos usuários do APK atual não criam
duzentos navegadores ou sessões nesta ponte: o app conversa diretamente com o Spotify.
