import { chromium } from 'playwright';

export const WEB_GRANT = Object.freeze({ clientId: 'd420a117a32841c2b3474932e49fb54b', redirectUri: 'http://127.0.0.1:8989/login', scopes: 'user-read-private user-library-read playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state user-read-recently-played' });
export const PLAYBACK_GRANT = Object.freeze({ clientId: '65b708073fc0480ea92a077233ca87bd', redirectUri: 'http://127.0.0.1:8898/login', scopes: 'streaming' });
const HOSTS = ['spotify.com', 'scdn.co', 'spotifycdn.com', 'spotifycdn.net', 'google.com', 'gstatic.com', 'googleusercontent.com', 'recaptcha.net', 'apple.com', 'appleid.cdn-apple.com'];
export function allowedRequest(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password && (!url.port || url.port === '443') && HOSTS.some(host => url.hostname === host || url.hostname.endsWith('.' + host));
  } catch { return false; }
}
export function callbackResult(value, grant) {
  const url = new URL(value);
  if (url.origin + url.pathname !== grant.redirectUri || url.hash || url.username || url.password) return null;
  const params = url.searchParams, code = params.get('code'), error = params.get('error');
  if (params.getAll('state').length !== 1 || params.get('state') !== grant.state || !!code === !!error || params.getAll('code').length > 1 || params.getAll('error').length > 1 || (code && (code.length > 2048 || /[\x00-\x20\x7f]/.test(code)))) return null;
  return error ? { error: 'access_denied' } : { code, state: grant.state };
}

// One disposable, sandboxed Chromium process per login; no persistent profile or token exchange.
export class BrowserLogin {
  constructor(grants, complete, testOnly = {}) {
    this.grants = grants; this.complete = complete; this.testOnly = testOnly;
    this.index = 0; this.codes = []; this.closed = false; this.socket = null;
    this.queue = Promise.resolve(); this.pending = 0;
  }
  send(value) {
    if (this.socket?.readyState === 1 && (value.type !== 'frame' || this.socket.bufferedAmount < 1024 * 1024)) this.socket.send(JSON.stringify(value));
  }
  async start() {
    this.browser = await chromium.launch({ chromiumSandbox: true });
    if (this.closed) { await this.browser.close(); return; }
    this.context = await this.browser.newContext({ viewport: { width: 390, height: 700 }, locale: 'pt-BR', acceptDownloads: false, serviceWorkers: 'block', permissions: [] });
    await this.context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      const grant = this.grants[this.index];
      if (grant && url.origin + url.pathname === grant.redirectUri) {
        const result = callbackResult(url, grant);
        if (!request.isNavigationRequest() || request.frame().parentFrame() || !result || this.transitioning) return route.abort();
        this.transitioning = true;
        await route.fulfill({ status: 200, contentType: 'text/html', body: '<html lang="pt-BR"><body>Autorização recebida. Aguarde…</body></html>' });
        if (result.error) { this.finish(result); return; }
        this.codes.push(result); this.index++;
        if (this.index === this.grants.length) { this.finish({ ...this.codes[0], playback: this.codes[1] }); return; }
        // Let the loopback document commit before opening the second grant.
        setTimeout(() => { this.transitioning = false; this.navigate().catch(() => this.finish({ error: 'browser_unavailable' })); }, 100);
        return;
      }
      const localTest = this.testOnly.authorizeUrl && url.origin === new URL(this.testOnly.authorizeUrl).origin;
      if (!allowedRequest(url.href) && !localTest) return route.abort();
      return route.continue();
    });
    await this.context.routeWebSocket('**/*', route => route.close());
    this.context.on('page', page => {
      page.on('dialog', dialog => dialog.dismiss().catch(() => {}));
      page.on('download', download => download.cancel().catch(() => {}));
      // Keep identity-provider popups controllable and under the same request allowlist.
      this.show(page).catch(() => this.finish({ error: 'browser_unavailable' }));
    });
    const page = await this.context.newPage();
    // show() is also called by the page event; wait for its setup.
    await this.showing;
    this.page = page;
    await this.navigate();
  }
  async show(page) {
    this.showing = (async () => {
      if (this.cdp) await this.cdp.detach().catch(() => {});
      this.page = page;
      const cdp = await this.context.newCDPSession(page); this.cdp = cdp;
      page.on('framenavigated', frame => {
        if (frame === page.mainFrame()) this.send({ type: 'location', host: new URL(page.url()).hostname, path: new URL(page.url()).pathname });
      });
      page.on('close', () => {
        const previous = this.context?.pages().filter(p => !p.isClosed()).at(-1);
        if (!this.closed && previous) this.show(previous).catch(() => {});
      });
      cdp.on('Page.screencastFrame', frame => {
        this.send({ type: 'frame', data: frame.data });
        cdp.send('Page.screencastFrameAck', { sessionId: frame.sessionId }).catch(() => {});
      });
      await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 78, maxWidth: 390, maxHeight: 700, everyNthFrame: 1 });
    })();
    return this.showing;
  }
  async navigate() {
    if (this.closed) return;
    const grant = this.grants[this.index];
    this.send({ type: 'status', text: this.index === 0 ? '1 de 2 · Conecte sua biblioteca Spotify' : '2 de 2 · Autorize a reprodução no carro' });
    const target = new URL(this.testOnly.authorizeUrl || 'https://accounts.spotify.com/authorize');
    target.search = new URLSearchParams({ client_id: grant.clientId, redirect_uri: grant.redirectUri, response_type: 'code', scope: grant.scopes, state: grant.state, code_challenge: grant.challenge, code_challenge_method: 'S256' }).toString();
    await this.page.goto(target.href, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  }
  attach(socket) {
    this.socket = socket;
    let count = 0, since = Date.now();
    socket.on('message', (raw, binary) => {
      if (Date.now() - since > 1000) { count = 0; since = Date.now(); }
      if (binary || ++count > 60 || this.pending >= 30) { socket.close(1008); return; }
      let event;
      try { event = JSON.parse(raw.toString()); } catch { socket.close(1008); return; }
      this.pending++;
      this.queue = this.queue.then(() => this.input(event)).catch(() => {}).finally(() => this.pending--);
    });
    socket.on('error', () => {});
    socket.on('close', () => { if (this.socket === socket) this.socket = null; });
    if (!this.starting) this.starting = this.start().catch(() => this.finish({ error: 'browser_unavailable' }));
    else this.starting.then(async () => {
      if (this.closed || !this.page || this.page.isClosed()) return;
      this.send({ type: 'frame', data: (await this.page.screenshot({ type: 'jpeg', quality: 78 })).toString('base64') });
      this.send({ type: 'location', host: new URL(this.page.url()).hostname });
    }).catch(() => {});
  }
  async input(event) {
    if (this.closed || !this.page || this.page.isClosed()) return;
    const point = Number.isFinite(event.x) && Number.isFinite(event.y) && event.x >= 0 && event.x <= 390 && event.y >= 0 && event.y <= 700;
    if (event.type === 'click' && point) {
      await this.page.mouse.click(event.x, event.y);
      const editable = await this.page.evaluate(() => ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName) || !!document.activeElement?.isContentEditable);
      this.send({ type: 'focus', editable });
    } else if (event.type === 'scroll' && Number.isFinite(event.delta) && Math.abs(event.delta) <= 1500) {
      await this.page.mouse.wheel(0, event.delta);
    } else if (event.type === 'text' && typeof event.text === 'string' && event.text.length <= 1024) {
      await this.page.keyboard.insertText(event.text);
    } else if (event.type === 'key' && ['Backspace', 'Delete', 'Enter', 'Tab', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'ControlOrMeta+A'].includes(event.key)) {
      await this.page.keyboard.press(event.key);
    }
  }
  finish(result) {
    if (this.closed) return;
    this.send({ type: 'done', ok: !result.error });
    this.complete(result);
    this.close();
  }
  close() {
    if (this.closed) return;
    this.closed = true;
    this.socket?.close(1000); this.socket = null;
    this.browser?.close().catch(() => {});
  }
}
