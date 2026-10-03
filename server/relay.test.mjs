import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { createRelay, SCOPES } from './relay.mjs';

const clientId = '0123456789abcdef0123456789abcdef';
const token = () => randomBytes(32).toString('base64url');
const hash = value => createHash('sha256').update(value).digest('base64url');
async function listen(t, server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}`;
}
async function relay(t, testOnly = {}) {
  const server = createRelay({ publicUrl: 'https://login.example.test', clientId, testOnly });
  return listen(t, server);
}
async function pair(base, state = token(), challenge = hash(token())) {
  const response = await fetch(`${base}/v1/pair`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ state, challenge }) });
  return { response, value: await response.json(), state, challenge };
}
async function consent(base, pairing) {
  const page = await (await fetch(`${base}/pair/${pairing.id}`)).text();
  const csrf = /name="consent" value="([A-Za-z\d_-]+)"/.exec(page)?.[1];
  assert.ok(csrf);
  assert.ok(page.includes(pairing.displayCode));
  assert.ok(!page.includes(pairing.pollSecret));
  return fetch(`${base}/pair/${pairing.id}/authorize`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ consent: csrf }), redirect: 'manual' });
}
const poll = (base, pairing, secret = pairing.pollSecret) => fetch(`${base}/v1/pair/${pairing.id}`, { method: 'POST', headers: { Authorization: `Bearer ${secret}` } });

test('QR -> explicit consent -> PKCE code -> one authenticated poll -> direct token exchange', async t => {
  let grant;
  let redeemed = false;
  const authority = await listen(t, http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/authorize') {
      assert.equal(url.searchParams.get('client_id'), clientId);
      assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
      assert.equal(url.searchParams.get('scope'), SCOPES);
      grant = { challenge: url.searchParams.get('code_challenge'), code: token() };
      res.writeHead(302, { Location: `${url.searchParams.get('redirect_uri')}?code=${grant.code}&state=${url.searchParams.get('state')}` });
      return res.end();
    }
    let raw = '';
    for await (const chunk of req) raw += chunk;
    const form = new URLSearchParams(raw);
    const valid = !redeemed && form.get('code') === grant.code && hash(form.get('code_verifier') || '') === grant.challenge;
    if (valid) redeemed = true;
    res.writeHead(valid ? 200 : 400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(valid ? { access_token: 'test-access', refresh_token: 'test-refresh' } : { error: 'invalid_grant' }));
  }));
  const base = await relay(t, { authorizeUrl: `${authority}/authorize` });
  const config = await (await fetch(`${base}/v1/config`)).json();
  assert.equal(config.redirectUri, 'https://login.example.test/callback');
  const verifier = token();
  const created = await pair(base, token(), hash(verifier));
  assert.equal(created.response.status, 201);
  assert.ok(!created.value.url.includes(created.value.pollSecret));
  assert.equal((await poll(base, created.value, token())).status, 404);
  assert.equal((await poll(base, created.value)).status, 202);
  const authorized = await consent(base, created.value);
  assert.equal(authorized.status, 303);
  const redirect = await fetch(authorized.headers.get('location'), { redirect: 'manual' });
  const callback = new URL(redirect.headers.get('location'));
  assert.equal((await fetch(`${base}${callback.pathname}${callback.search}`)).status, 200);
  assert.equal((await fetch(`${base}${callback.pathname}${callback.search}`)).status, 400);
  const code = await (await poll(base, created.value)).json();
  assert.equal(code.state, created.state);
  assert.equal((await poll(base, created.value)).status, 404);
  assert.equal((await fetch(`${base}/pair/${created.value.id}`)).status, 410);
  const exchange = verifier => fetch(`${authority}/token`, { method: 'POST', body: new URLSearchParams({ code: code.code, code_verifier: verifier }) });
  assert.equal((await exchange(token())).status, 400);
  assert.equal((await exchange(verifier)).status, 200);
  assert.equal((await exchange(verifier)).status, 400);
});

test('rejects unknown, duplicate and early state; denied grant consumed once', async t => {
  const base = await relay(t);
  const created = await pair(base);
  assert.equal((await pair(base, created.state)).response.status, 409);
  assert.equal((await fetch(`${base}/callback?code=test&state=${created.state}`)).status, 400);
  await consent(base, created.value);
  assert.equal((await fetch(`${base}/callback?code=test&state=${token()}`)).status, 400);
  assert.equal((await fetch(`${base}/callback?code=test&state=${created.state}&state=${created.state}`)).status, 400);
  assert.equal((await fetch(`${base}/callback?code=test&error=denied&state=${created.state}`)).status, 400);
  assert.equal((await poll(base, created.value)).status, 202);
  assert.equal((await fetch(`${base}/callback?error=private-secret&state=${created.state}`)).status, 200);
  assert.deepEqual(await (await poll(base, created.value)).json(), { error: 'access_denied', state: created.state });
  assert.equal((await poll(base, created.value)).status, 404);
});

test('confirmation requires same-origin form nonce and cannot be replayed', async t => {
  const base = await relay(t);
  const created = (await pair(base)).value;
  const path = `${base}/pair/${created.id}/authorize`;
  assert.equal((await fetch(path, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: 'consent=wrong' })).status, 403);
  assert.equal((await fetch(path, { method: 'POST', headers: { Origin: 'https://evil.example' } })).status, 403);
  const response = await consent(base, created);
  assert.equal(response.status, 303);
  assert.equal((await fetch(`${base}/pair/${created.id}`)).status, 409);
  assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
});

test('cancellation is authenticated and expiry destroys pending codes', async t => {
  const base = await relay(t, { ttlMs: 80 });
  const first = (await pair(base)).value;
  const remove = secret => fetch(`${base}/v1/pair/${first.id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${secret}` } });
  assert.equal((await remove(token())).status, 404);
  assert.equal((await poll(base, first)).status, 202);
  assert.equal((await remove(first.pollSecret)).status, 200);
  assert.equal((await poll(base, first)).status, 404);
  const second = (await pair(base)).value;
  await new Promise(resolve => setTimeout(resolve, 90));
  assert.equal((await poll(base, second)).status, 404);
  assert.equal((await fetch(`${base}/pair/${second.id}`)).status, 410);
});

test('bounded request size, capacity and create rate', async t => {
  const base = await relay(t, { capacity: 1, createLimit: 3 });
  const send = body => fetch(`${base}/v1/pair`, { method: 'POST', headers: { 'content-type': 'application/json' }, body });
  assert.equal((await send('x'.repeat(2049))).status, 413);
  assert.equal((await pair(base)).response.status, 201);
  assert.equal((await pair(base)).response.status, 503);
  const limited = await pair(base);
  assert.equal(limited.response.status, 429);
  assert.equal(limited.response.headers.get('retry-after'), '60');
  const limitedBase = await relay(t, { rateLimit: 1 });
  assert.equal((await fetch(`${limitedBase}/health`)).status, 200);
  assert.equal((await fetch(`${limitedBase}/health`)).status, 429);
});

test('enforces public HTTPS origins and fixed configuration', () => {
  for (const publicUrl of ['http://example.com', 'http://localhost:8787', 'https://user:pass@example.com', 'https://example.com/prefix', 'https://example.com?x=y']) {
    assert.throws(() => createRelay({ publicUrl, clientId }));
  }
  assert.throws(() => createRelay({ publicUrl: 'https://example.com', clientId: 'not-a-client-id' }));
  const local = createRelay({ publicUrl: 'http://127.0.0.1:8787', clientId });
  local.emit('close');
});

test('unconfigured deployment reports health but never creates login sessions', async t => {
  const base = await listen(t, createRelay({ publicUrl: 'https://login.example.test' }));
  assert.deepEqual(await (await fetch(`${base}/health`)).json(), { ok: true, spotifyConfigured: false });
  assert.match(await (await fetch(base)).text(), /ainda não está disponível/);
  for (const path of ['/v1/config', '/callback?state=test&code=test', '/pair/test']) {
    const response = await fetch(`${base}${path}`, { redirect: 'manual' });
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'spotify_not_configured' });
  }
  const created = await pair(base);
  assert.equal(created.response.status, 503);
  assert.equal(created.value.pollSecret, undefined);
});
