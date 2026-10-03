const screen = document.getElementById('screen'), keyboard = document.getElementById('keyboard');
const status = document.getElementById('status'), host = document.getElementById('host');
let socket, done = false, start, retry, frameNumber = 0;
const send = value => { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(value)); };
function connect() {
  socket = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}${location.pathname}/socket`);
  socket.onmessage = async event => {
    const value = JSON.parse(event.data);
    if (value.type === 'frame') {
      const number = ++frameNumber;
      const image = new Image(); image.src = 'data:image/jpeg;base64,' + value.data;
      await image.decode(); if (!done && number === frameNumber) screen.getContext('2d').drawImage(image, 0, 0, 390, 700);
    } else if (value.type === 'status') status.textContent = value.text;
    else if (value.type === 'location') host.textContent = value.host;
    else if (value.type === 'focus') keyboard.placeholder = value.editable ? 'Digite no campo selecionado' : 'Toque primeiro em um campo';
    else if (value.type === 'done') {
      done = true; screen.hidden = true; document.getElementById('controls').hidden = true;
      screen.style.display = 'none'; document.getElementById('controls').style.display = 'none';
      keyboard.value = ''; keyboard.blur();
      status.textContent = value.ok ? 'Autorização recebida' : 'Login não concluído';
      const message = document.getElementById('message'); message.hidden = false;
      message.textContent = value.ok ? 'Volte ao carro. Ele concluirá a conexão com o Spotify. Você já pode fechar esta página.' : 'Gere outro QR no carro para tentar novamente.';
    }
  };
  socket.onclose = () => { if (!done) { status.textContent = 'Reconectando… Se o QR expirou, gere outro no carro.'; retry = setTimeout(connect, 2500); } };
}
screen.onpointerdown = event => { start = { x: event.clientX, y: event.clientY }; screen.setPointerCapture(event.pointerId); };
screen.onpointerup = event => {
  if (!start) return;
  const rect = screen.getBoundingClientRect(), delta = (start.y - event.clientY) * 700 / rect.height;
  if (Math.abs(delta) > 12) send({ type: 'scroll', delta: Math.max(-1500, Math.min(1500, delta)) });
  else send({ type: 'click', x: (event.clientX - rect.left) * 390 / rect.width, y: (event.clientY - rect.top) * 700 / rect.height });
  start = null;
};
screen.onwheel = event => { event.preventDefault(); send({ type: 'scroll', delta: Math.max(-1500, Math.min(1500, event.deltaY)) }); };
keyboard.addEventListener('input', event => {
  if (event.isComposing) return;
  if (keyboard.value) send({ type: 'text', text: keyboard.value });
  else if (event.inputType === 'deleteContentBackward') send({ type: 'key', key: 'Backspace' });
  keyboard.value = '';
});
keyboard.addEventListener('keydown', event => {
  if (['Backspace', 'Enter', 'Tab', 'ArrowLeft', 'ArrowRight'].includes(event.key)) { event.preventDefault(); send({ type: 'key', key: event.key }); }
});
document.getElementById('erase').onclick = () => send({ type: 'key', key: 'Backspace' });
document.getElementById('enter').onclick = () => { keyboard.blur(); send({ type: 'key', key: 'Enter' }); };
window.addEventListener('pagehide', () => { done = true; clearTimeout(retry); socket?.close(); keyboard.value = ''; });
connect();
