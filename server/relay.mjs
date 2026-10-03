import http from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const SCOPES = 'streaming user-read-private user-library-read playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state user-read-recently-played';
const random = (size = 32) => randomBytes(size).toString('base64url');
const equal = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
};
const escape = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));

function origin(value) {
  const url = new URL(value);
  const loopback = ['127.0.0.1', '[::1]'].includes(url.hostname);
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('PUBLIC_URL deve ser uma origem HTTPS (HTTP somente em 127.0.0.1 ou [::1]).');
  }
  return url.origin;
}

function page(title, description, content = '') {
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#080c12"><title>${escape(title)} · Impulsefy</title><style>
  :root{color-scheme:dark;font-family:"DM Sans",system-ui,-apple-system,sans-serif;background:#080c12;color:#f4f8fc}*{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;padding:28px;background:radial-gradient(ellipse at 50% 0,#14343e 0,transparent 60%),#080c12}main{width:min(100%,440px);padding:36px 28px;border:1px solid #243039;border-radius:28px;background:#0d141deb;box-shadow:0 24px 96px #0006}.brand{font-size:14px;letter-spacing:3px;text-transform:uppercase;color:#4fd6e8;font-weight:700}.symbol{height:56px;width:56px;display:grid;place-items:center;border-radius:18px;background:#4fd6e81a;margin:32px 0 24px;color:#4fd6e8;font-size:28px}h1{font-size:32px;line-height:1.12;letter-spacing:-1px;margin:0 0 16px}p{font-size:16px;line-height:1.65;color:#a7b6c5;margin:0 0 24px}.code{font:700 30px ui-monospace,monospace;letter-spacing:5px;text-align:center;padding:22px 10px;border:1px solid #305260;background:#101f2a;border-radius:16px;color:#4fd6e8;margin:24px 0}.label{text-align:center;margin-bottom:8px;font-size:12px;letter-spacing:1.5px;text-transform:uppercase;color:#8eabba}button{border:0;border-radius:14px;background:#4fd6e8;color:#071217;padding:19px 20px;min-height:60px;font-family:inherit;font-size:16px;font-weight:700;width:100%;cursor:pointer}button:hover{background:#81e6f3}button:focus-visible{outline:3px solid white;outline-offset:4px}.note{font-size:13px;line-height:1.6;margin:22px 0 0;color:#8193a5}footer{font-size:11px;color:#627787;letter-spacing:1px;text-transform:uppercase;margin-top:32px}
  </style></head><body><main><div class="brand">Impulsefy</div><div class="symbol" aria-hidden="true">↗</div><h1>${escape(title)}</h1><p>${escape(description)}</p>${content}<footer>Seu Spotify. Seu caminho.</footer></main></body></html>`;
}

// testOnly is constructor injection for node:test; the production CLI never reads overrides from env.
export function createRelay({ publicUrl, clientId, testOnly = {} }) {
  const base = origin(publicUrl);
  if (!/^[a-f\d]{32}$/i.test(clientId || '')) throw new Error('Configure SPOTIFY_CLIENT_ID com o Client ID do seu aplicativo Spotify.');
  const authorizeUrl = testOnly.authorizeUrl || 'https://accounts.spotify.com/authorize';
  const ttl = testOnly.ttlMs ?? 5 * 60_000;
  const capacity = testOnly.capacity ?? 500;
  const rateLimit = testOnly.rateLimit ?? 600;
  const createLimit = testOnly.createLimit ?? 20;
  const sessions = new Map();
  const states = new Map();
  const rates = new Map();
  const redirectUri = `${base}/callback`;

  function forget(id) {
    const session = sessions.get(id);
    if (session) states.delete(session.state);
    sessions.delete(id);
  }
  function cleanup() {
    const now = Date.now();
    // ponytail: bounded in-memory scan (500 sessions); use a shared TTL store for multiple replicas.
    for (const [id, session] of sessions) if (session.expiresAt <= now) forget(id);
    for (const [ip, rate] of rates) if (rate.until <= now) rates.delete(ip);
  }
  async function readBody(req, json = true) {
    if (Number(req.headers['content-length'] || 0) > 2048) throw { status: 413 };
    let size = 0;
    const chunks = [];
    const deadline = setTimeout(() => req.destroy(), 10_000).unref();
    try {
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 2048) throw { status: 413 };
        chunks.push(chunk);
      }
    } finally { clearTimeout(deadline); }
    const body = Buffer.concat(chunks).toString('utf8');
    try { return json ? JSON.parse(body) : new URLSearchParams(body); }
    catch { throw { status: 400 }; }
  }

  const server = http.createServer({ maxHeaderSize: 8192 }, async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Content-Security-Policy', `default-src 'none'; style-src 'unsafe-inline'; form-action 'self' ${new URL(authorizeUrl).origin}; frame-ancestors 'none'; base-uri 'none'`);
    if (base.startsWith('https:')) res.setHeader('Strict-Transport-Security', 'max-age=31536000');
    const send = (status, value, html = false) => {
      res.writeHead(status, { 'Content-Type': html ? 'text/html; charset=utf-8' : 'application/json; charset=utf-8' });
      res.end(html ? value : JSON.stringify(value));
    };
    try {
      cleanup();
      if (!req.url || req.url.length > 4096 || !req.url.startsWith('/') || req.url.startsWith('//')) return send(400, { error: 'invalid_request' });
      const url = new URL(req.url, base);
      const ip = req.socket.remoteAddress; // Do not trust client-supplied X-Forwarded-For.
      if (!rates.has(ip)) {
        if (rates.size >= 2000) return send(503, { error: 'busy' });
        rates.set(ip, { count: 0, creates: 0, until: Date.now() + 60_000 });
      }
      const rate = rates.get(ip);
      if (++rate.count > rateLimit) {
        res.setHeader('Retry-After', '60');
        return send(429, { error: 'rate_limited' });
      }
      if (req.headers.origin && req.headers.origin !== base) return send(403, { error: 'origin_mismatch' });
      if (req.method === 'GET' && url.pathname === '/health') return send(200, { ok: true });
      if (req.method === 'GET' && url.pathname === '/v1/config') return send(200, { clientId, redirectUri, scopes: SCOPES });
      if (req.method === 'POST' && url.pathname === '/v1/pair') {
        if (++rate.creates > createLimit) {
          res.setHeader('Retry-After', '60');
          return send(429, { error: 'rate_limited' });
        }
        if (!req.headers['content-type']?.startsWith('application/json')) return send(415, { error: 'content_type' });
        const input = await readBody(req);
        if (!input || typeof input !== 'object' || Object.keys(input).some(k => !['challenge', 'state'].includes(k)) || typeof input.challenge !== 'string' || typeof input.state !== 'string' || !/^[A-Za-z\d_-]{43}$/.test(input.challenge) || !/^[A-Za-z\d_-]{43}$/.test(input.state)) return send(400, { error: 'invalid_request' });
        if (states.has(input.state)) return send(409, { error: 'state_reused' });
        if (sessions.size >= capacity) return send(503, { error: 'busy' });
        const id = random(24);
        const pollSecret = random();
        const displayCode = randomBytes(4).toString('hex').toUpperCase().replace(/(.{4})/, '$1-');
        const session = { ...input, pollSecret, displayCode, consent: random(), expiresAt: Date.now() + ttl, started: false };
        sessions.set(id, session);
        states.set(session.state, id);
        return send(201, { id, pollSecret, displayCode, expiresAt: session.expiresAt, url: `${base}/pair/${id}` });
      }
      const apiMatch = /^\/v1\/pair\/([A-Za-z\d_-]{32})$/.exec(url.pathname);
      if (apiMatch && ['POST', 'DELETE'].includes(req.method)) {
        const session = sessions.get(apiMatch[1]);
        if (!session || !equal(req.headers.authorization, `Bearer ${session.pollSecret}`)) return send(404, { error: 'pairing_unavailable' });
        if (req.method === 'DELETE') { forget(apiMatch[1]); return send(200, { status: 'cancelled' }); }
        if (!session.result) return send(202, { status: 'pending' });
        const result = session.result;
        forget(apiMatch[1]); // One authenticated consumer; never return the code twice.
        return send(200, { ...result, state: session.state });
      }
      const pairMatch = /^\/pair\/([A-Za-z\d_-]{32})(\/authorize)?$/.exec(url.pathname);
      if (pairMatch) {
        const session = sessions.get(pairMatch[1]);
        if (!session || session.result) return send(410, page('Este QR já expirou', 'Gere um novo QR na tela do carro para continuar.'), true);
        if (req.method === 'GET' && !pairMatch[2]) {
          if (session.started) return send(409, page('Login em andamento', 'Conclua a autorização na aba do Spotify ou gere outro QR no carro.'), true);
          return send(200, page('Sua música, no carro.', 'Confira se este código é igual ao da tela do carro. Depois, conecte sua conta Spotify.', `<div class="label">Código de confirmação</div><div class="code">${session.displayCode}</div><form method="post" action="/pair/${pairMatch[1]}/authorize"><input type="hidden" name="consent" value="${session.consent}"><button type="submit">Confirmar e conectar Spotify →</button></form><p class="note">Use este QR apenas se você iniciou o login no carro. Sua senha é digitada somente no Spotify. O QR expira em poucos minutos.</p>`), true);
        }
        if (req.method === 'POST' && pairMatch[2]) {
          if (!req.headers['content-type']?.startsWith('application/x-www-form-urlencoded')) return send(415, { error: 'content_type' });
          const body = await readBody(req, false);
          if (body.getAll('consent').length !== 1 || !equal(body.get('consent'), session.consent)) return send(403, { error: 'invalid_confirmation' });
          if (session.started) return send(409, { error: 'already_started' });
          session.started = true;
          const target = new URL(authorizeUrl);
          target.search = new URLSearchParams({ client_id: clientId, response_type: 'code', redirect_uri: redirectUri, code_challenge_method: 'S256', code_challenge: session.challenge, state: session.state, scope: SCOPES }).toString();
          res.writeHead(303, { Location: target.href });
          return res.end();
        }
      }
      if (req.method === 'GET' && url.pathname === '/callback') {
        const state = url.searchParams.get('state');
        const id = states.get(state);
        const session = sessions.get(id);
        const code = url.searchParams.get('code');
        const error = url.searchParams.get('error');
        if (!session || !session.started || session.result || url.searchParams.getAll('state').length !== 1 || !equal(state, session.state) || (code && error) || (!code && !error) || url.searchParams.getAll('code').length > 1 || url.searchParams.getAll('error').length > 1 || (code && (code.length > 2048 || /[\x00-\x20\x7f]/.test(code)))) {
          return send(400, page('Não foi possível conectar', 'Esta autorização é inválida ou já foi usada. Gere um novo QR no carro.'), true);
        }
        session.result = error ? { error: 'access_denied' } : { code };
        return send(200, error ? page('Login cancelado', 'Volte à tela do carro para tentar novamente.') : page('Tudo pronto por aqui.', 'Autorização recebida. O carro concluirá a conexão com o Spotify. Você já pode fechar esta página.'), true);
      }
      if (req.method === 'GET' && url.pathname === '/') return send(200, page('Sua música, no carro.', 'Abra o Impulsefy no carro e escaneie o QR para conectar seu Spotify.'), true);
      return send(404, { error: 'not_found' });
    } catch (error) {
      if (!res.headersSent && !res.destroyed) send(error.status || 500, { error: error.status === 413 ? 'too_large' : 'invalid_request' });
    }
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  server.keepAliveTimeout = 5000;
  server.maxRequestsPerSocket = 100;
  // Expired codes also disappear while the relay is idle.
  const sweeper = setInterval(cleanup, 30_000).unref();
  server.on('close', () => { clearInterval(sweeper); sessions.clear(); states.clear(); rates.clear(); });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const server = createRelay({ publicUrl: process.env.PUBLIC_URL, clientId: process.env.SPOTIFY_CLIENT_ID });
    const port = Number(process.env.PORT || 8787);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT inválida.');
    server.listen(port, '0.0.0.0', () => console.log(`Impulsefy relay listening on ${port}`));
    for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => { server.close(); setTimeout(() => process.exit(0), 5000).unref(); });
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
