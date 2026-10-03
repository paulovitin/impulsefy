import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { randomBytes } from 'node:crypto';
import WebSocket from 'ws';
import { createRelay } from './relay.mjs';
import { BrowserLogin, allowedRequest, callbackResult, WEB_GRANT, PLAYBACK_GRANT } from './browser-login.mjs';
const random = () => randomBytes(32).toString('base64url');
const origin = 'https://login.example.test';
async function listen(t, server) {
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}`;
}
async function pair(base) {
  const grant = { state: random(), challenge: random(), mode: 'browser', playback: { state: random(), challenge: random() } };
  const response = await fetch(base + '/v1/pair', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(grant) });
  assert.equal(response.status, 201);
  return { ...await response.json(), grant };
}
async function confirm(base, flow) {
  const html = await (await fetch(base + '/pair/' + flow.id)).text();
  const nonce = /name="consent" value="([A-Za-z\d_-]+)"/.exec(html)[1];
  const response = await fetch(base + '/pair/' + flow.id + '/authorize', { method: 'POST', headers: { Origin: origin }, body: new URLSearchParams({ consent: nonce }), redirect: 'manual' });
  assert.equal(response.status, 303);
  return response.headers.get('set-cookie').split(';')[0];
}
const poll = (base, flow) => fetch(base + '/v1/pair/' + flow.id, { method: 'POST', headers: { Authorization: 'Bearer ' + flow.pollSecret } });
const socket = (base, flow, cookie, from = origin) => new WebSocket(base.replace('http:', 'ws:') + '/pair/' + flow.id + '/browser/socket', { headers: { Cookie: cookie, Origin: from } });
async function rejected(ws) {
  await new Promise((resolve, reject) => { ws.on('unexpected-response', (_, response) => { assert.equal(response.statusCode, 403); ws.terminate(); resolve(); }); ws.on('error', () => {}); ws.on('open', () => { ws.close(); reject(new Error('unauthorized websocket opened')); }); });
}

test('browser navigation and callbacks reject external/private URLs, wrong state and duplicate parameters', () => {
  for (const url of ['http://accounts.spotify.com', 'https://spotify.com.evil.test', 'https://127.0.0.1', 'https://192.168.3.43', 'file:///etc/passwd', 'https://user:password@spotify.com', 'https://spotify.com:8989']) assert.equal(allowedRequest(url), false, url);
  for (const url of ['https://accounts.spotify.com/login', 'https://accounts.google.com', 'https://www.gstatic.com']) assert.equal(allowedRequest(url), true);
  const grant = { ...WEB_GRANT, state: random() }, callback = grant.redirectUri + '?state=' + grant.state;
  assert.deepEqual(callbackResult(callback + '&code=accepted', grant), { code: 'accepted', state: grant.state });
  for (const suffix of ['&code=x&code=y', '&code=x&error=no', '&code=x&state=wrong', '&code=%20', '&error=']) assert.equal(callbackResult(callback + suffix, grant), null);
  assert.equal(callbackResult(PLAYBACK_GRANT.redirectUri + '?state=' + grant.state + '&code=x', grant), null);
});

test('two isolated PKCE grants through actual browser, authenticated control and one-time delivery', { timeout: 30_000 }, async t => {
  let flow, visits = 0;
  const authority = await listen(t, http.createServer((req, res) => {
    const url = new URL(req.url, 'http://local');
    const stage = visits++;
    const expected = stage === 0 ? WEB_GRANT : PLAYBACK_GRANT;
    assert.equal(url.searchParams.get('client_id'), expected.clientId);
    assert.equal(url.searchParams.get('code_challenge'), stage === 0 ? flow.grant.challenge : flow.grant.playback.challenge);
    if (stage === 1) assert.match(req.headers.cookie || '', /login=temporary/);
    res.setHeader('Set-Cookie', 'login=temporary; SameSite=Lax');
    res.end(`<html><body style="margin:0"><form action="${expected.redirectUri}"><input name="state" type="hidden" value="${url.searchParams.get('state')}"><input name="code" style="position:absolute;left:10px;top:10px;width:200px;height:40px"><button style="position:absolute;left:10px;top:80px;width:200px;height:40px">Confirm</button></form></body></html>`);
  }));
  const server = createRelay({ publicUrl: origin, browserLogin: true, testOnly: { authorizeUrl: authority + '/authorize' } });
  const base = await listen(t, server);
  const config = await (await fetch(base + '/v1/config?browser=1')).json();
  assert.equal(config.clientId, WEB_GRANT.clientId); assert.equal(config.playback.clientId, PLAYBACK_GRANT.clientId);
  flow = await pair(base);
  const cookie = await confirm(base, flow);
  assert.equal((await fetch(base + '/pair/' + flow.id + '/browser')).status, 403);
  await rejected(socket(base, flow, cookie, 'https://evil.test'));
  await rejected(socket(base, flow, 'impulsefy_browser=wrong'));
  const ws = socket(base, flow, cookie); t.after(() => ws.terminate());
  let frames = 0, stage = 0;
  const complete = new Promise((resolve, reject) => {
    ws.on('message', async raw => {
      const value = JSON.parse(raw);
      if (value.type === 'frame') frames++;
      if (value.type === 'location' && value.host === '127.0.0.1' && value.path === '/authorize' && stage < 2) {
        const code = stage++ === 0 ? 'web-code' : 'playback-code';
        // Wait for document render, then exercise the same controls as the phone.
        setTimeout(() => {
          ws.send(JSON.stringify({ type: 'click', x: 30, y: 30 }));
          ws.send(JSON.stringify({ type: 'text', text: code }));
          ws.send(JSON.stringify({ type: 'click', x: 30, y: 95 }));
        }, 250);
      }
      if (value.type === 'done') value.ok ? resolve() : reject(new Error('browser flow failed'));
    }); ws.on('error', reject);
  });
  await complete;
  const result = await (await poll(base, flow)).json();
  assert.deepEqual(result, { code: 'web-code', state: flow.grant.state, playback: { code: 'playback-code', state: flow.grant.playback.state } });
  assert.ok(frames > 0);
  assert.equal((await poll(base, flow)).status, 404);
  assert.equal((await fetch(base + '/pair/' + flow.id + '/browser', { headers: { Cookie: cookie } })).status, 410);
});

test('browser cookie is per pairing; public callback cannot complete browser flow; cancellation closes access', async t => {
  const base = await listen(t, createRelay({ publicUrl: origin, browserLogin: true }));
  const first = await pair(base), second = await pair(base), cookie = await confirm(base, first);
  await confirm(base, second);
  await rejected(socket(base, second, cookie));
  assert.equal((await fetch(base + '/callback?state=' + first.grant.state + '&code=forged')).status, 400);
  assert.equal((await poll(base, first)).status, 202);
  await fetch(base + '/v1/pair/' + first.id, { method: 'DELETE', headers: { Authorization: 'Bearer ' + first.pollSecret } });
  await rejected(socket(base, first, cookie));
});

test('expired browser authorization cannot be resumed with its old cookie', async t => {
  const base = await listen(t, createRelay({ publicUrl: origin, browserLogin: true, testOnly: { ttlMs: 100 } }));
  const flow = await pair(base), cookie = await confirm(base, flow);
  await new Promise(resolve => setTimeout(resolve, 110));
  assert.equal((await poll(base, flow)).status, 404);
  await rejected(socket(base, flow, cookie));
  assert.equal((await fetch(base + '/pair/' + flow.id + '/browser', { headers: { Cookie: cookie } })).status, 410);
});

test('slow phone drops stale frames but still receives completion', () => {
  const sent = [], login = new BrowserLogin([], () => {});
  login.socket = { readyState: 1, bufferedAmount: 2 * 1024 * 1024, send: value => sent.push(JSON.parse(value)) };
  login.send({ type: 'frame', data: 'stale' });
  login.send({ type: 'done', ok: true });
  assert.deepEqual(sent, [{ type: 'done', ok: true }]);
});
