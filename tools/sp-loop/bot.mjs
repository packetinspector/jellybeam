// Watch Together third member for tools/sp-loop.sh: a SyncPlay client over REST + /socket that
// follows group commands like a real member (Ready after every new queue and seek, docs/28 §5),
// controlled over a localhost HTTP port. Usage: node bot.mjs <base> <accounts.env> <rundir>
import fs from 'node:fs';
import http from 'node:http';

const [base, envFile, runDir] = process.argv.slice(2);
if (!base || !envFile || !runDir) { console.error('usage: node bot.mjs <base> <accounts.env> <rundir>'); process.exit(2); }
const env = Object.fromEntries(fs.readFileSync(envFile, 'utf8').split('\n')
  .map(l => l.trim()).filter(l => l && !l.startsWith('#') && l.includes('=')).map(l => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
const user = env.SP_EVE, pass = env.SP_PASS;
if (!user || !pass) { console.error('bot: SP_EVE/SP_PASS missing'); process.exit(2); }
const others = [env.SP_ALICE, env.SP_BOB, env.SP_EVE].filter(Boolean);

// Redacted log: ids, account names and the room name never reach the file verbatim.
const logFile = fs.openSync(`${runDir}/bot.log`, 'a');
const ids = new Map(); let idn = 0;
let roomSeen = null;
function red(s) {
  s = s.replace(/[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}/gi, m => {
    const k = m.replace(/-/g, '').toLowerCase(); if (!ids.has(k)) ids.set(k, `<id${++idn}>`); return ids.get(k);
  });
  if (roomSeen) s = s.split(roomSeen).join('<room>');
  others.forEach((n, i) => { s = s.split(n).join(['<alice>', '<bob>', '<eve>'][i] ?? '<user>'); });
  return s.replace(/"(AccessToken|Token|ApiKey)":"[^"]*"/g, '"$1":"<redacted>"');
}
const note = (what, obj) => fs.writeSync(logFile, `${new Date().toISOString().slice(11, 23)} ${red(what)} ${obj === undefined ? '' : red(JSON.stringify(obj))}\n`);

const deviceId = `sp-loop-bot-${process.pid}`;
let token = null;
const hdr = () => `MediaBrowser Client="sp-loop", Device="sp-loop-bot", DeviceId="${deviceId}", Version="0.0.1"${token ? `, Token="${token}"` : ''}`;
async function call(method, path, body) {
  const r = await fetch(base + path, { method, headers: { Authorization: hdr(), 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text(); let j; try { j = t ? JSON.parse(t) : null; } catch { j = t.slice(0, 200); }
  // Credentials never reach the log: the sign-in body is left out.
  const shown = method === 'GET' || path.startsWith('/Users/') ? undefined : body ?? undefined;
  note(`${method} ${path.replace(/searchTerm=[^&]*/, 'searchTerm=<term>')} -> ${r.status}`, shown);
  return { status: r.status, body: j };
}

// Server clock offset (docs/28 §5): lowest-RTT sample of a few.
let offsetMs = 0;
async function syncClock() {
  let best = null;
  for (let i = 0; i < 4; i++) {
    const t0 = Date.now(); const r = await call('GET', '/GetUtcTime'); const t3 = Date.now();
    if (r.status !== 200) continue;
    const t1 = Date.parse(r.body.RequestReceptionTime), t2 = Date.parse(r.body.ResponseTransmissionTime);
    const rtt = (t3 - t0) - (t2 - t1), off = ((t1 - t0) + (t2 - t3)) / 2;
    if (!best || rtt < best.rtt) best = { rtt, off };
  }
  if (best) offsetMs = best.off;
  return best;
}
const serverNow = () => Date.now() + offsetMs;
const serverIso = () => new Date(serverNow()).toISOString();

// The member's virtual player.
const st = {
  authed: false, ws: false, groupId: null, groupState: null, participants: [], playlistItemId: null,
  playing: false, posTicks: 0, atServerMs: 0, lastCommand: null, readySent: 0, events: 0, lastUpdate: null,
  queueEnded: false, seeks: 0, lastSeekTicks: null, userLeft: 0, userJoined: 0, commands: 0, lastCommandAtMs: 0,
};
const position = () => st.playing ? st.posTicks + Math.max(0, serverNow() - st.atServerMs) * 10000 : st.posTicks;
async function ready(ticks) {
  if (!st.playlistItemId) return;
  st.readySent++;
  await call('POST', '/SyncPlay/Ready', { When: serverIso(), PositionTicks: Math.round(ticks), IsPlaying: false, PlaylistItemId: st.playlistItemId });
}

function onUpdate(u) {
  st.events++;
  const type = u.Type;
  if (type === 'GroupJoined') { st.groupId = u.GroupId; st.groupState = u.Data?.State ?? st.groupState; st.participants = u.Data?.Participants ?? st.participants; }
  else if (type === 'UserJoined') { st.userJoined++; if (!st.participants.includes(u.Data)) st.participants.push(u.Data); }
  else if (type === 'UserLeft') { st.userLeft++; st.participants = st.participants.filter(p => p !== u.Data); }
  else if (type === 'GroupLeft' || type === 'NotInGroup' || type === 'GroupDoesNotExist') {
    if (type === 'GroupLeft' || st.groupId) { st.groupId = null; st.groupState = null; st.participants = []; st.playlistItemId = null; st.playing = false; }
  } else if (type === 'StateUpdate') { st.groupState = u.Data?.State ?? st.groupState; }
  else if (type === 'PlayQueue') {
    const q = u.Data ?? {};
    if (st.lastUpdate && q.LastUpdate && Date.parse(q.LastUpdate) <= Date.parse(st.lastUpdate)) return;
    st.lastUpdate = q.LastUpdate;
    const item = q.Playlist?.[q.PlayingItemIndex];
    if (!item) { st.playlistItemId = null; st.playing = false; st.queueEnded = true; return; }
    st.queueEnded = false;
    const changed = item.PlaylistItemId !== st.playlistItemId;
    st.playlistItemId = item.PlaylistItemId;
    if (changed || q.Reason === 'NewPlaylist' || q.Reason === 'SetCurrentItem') {
      // Like a client that loaded the item: paused at the start position, then Ready.
      st.playing = false; st.posTicks = q.StartPositionTicks ?? 0; st.atServerMs = serverNow();
      setTimeout(() => ready(st.posTicks), 400);
    }
  }
}
function onCommand(c) {
  st.events++;
  st.lastCommand = c.Command; st.commands++; st.lastCommandAtMs = Date.now();
  const when = Date.parse(c.When) || serverNow();
  if (c.PlaylistItemId && st.playlistItemId && c.PlaylistItemId !== st.playlistItemId) return;
  if (c.Command === 'Unpause') { st.playing = true; st.posTicks = c.PositionTicks ?? position(); st.atServerMs = when; }
  else if (c.Command === 'Pause') { st.playing = false; st.posTicks = c.PositionTicks ?? position(); }
  else if (c.Command === 'Stop') { st.playing = false; }
  else if (c.Command === 'Seek') {
    st.seeks++; st.lastSeekTicks = c.PositionTicks;
    st.playing = false; st.posTicks = c.PositionTicks ?? position();
    setTimeout(() => ready(st.posTicks), 300);
  }
}

let ws = null;
function openWs() {
  return new Promise((res, rej) => {
    ws = new WebSocket(`${base.replace(/^http/, 'ws')}/socket?ApiKey=${token}&deviceId=${deviceId}`);
    ws.onopen = () => { st.ws = true; note('ws open'); res(); };
    ws.onerror = e => { note('ws error'); rej(e); };
    ws.onclose = () => { st.ws = false; note('ws closed'); };
    ws.onmessage = m => {
      let f; try { f = JSON.parse(m.data); } catch { return; }
      if (f.MessageType === 'ForceKeepAlive') { ws.send('{"MessageType":"KeepAlive"}'); return; }
      if (f.MessageType === 'KeepAlive') return;
      if (f.MessageType === 'SyncPlayGroupUpdate') { note(`<< ${f.Data?.Type}`, f.Data?.Data); onUpdate(f.Data ?? {}); }
      else if (f.MessageType === 'SyncPlayCommand') { note(`<< ${f.Data?.Command}`, { PositionTicks: f.Data?.PositionTicks }); onCommand(f.Data ?? {}); }
    };
  });
}

async function login() {
  const r = await call('POST', '/Users/AuthenticateByName', { Username: user, Pw: pass });
  if (r.status !== 200) throw new Error(`login ${r.status}`);
  token = r.body.AccessToken; st.authed = true;
  // Keep-alive cadence the socket expects.
  setInterval(() => { if (ws && ws.readyState === 1) ws.send('{"MessageType":"KeepAlive"}'); }, 30000);
  await openWs();
  await syncClock();
}

const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  try {
    switch (u.pathname) {
      case '/status':
        return json(res, 200, { ...st, posMs: Math.round(position() / 10000), offsetMs: Math.round(offsetMs) });
      case '/list': {
        const r = await call('GET', '/SyncPlay/List');
        return json(res, r.status, r.body ?? []);
      }
      case '/join': {
        const name = (u.searchParams.get('name') || '').trim(); roomSeen = name || roomSeen;
        const r = await call('GET', '/SyncPlay/List');
        const g = (r.body || []).find(x => (x.GroupName || '').trim().toLowerCase() === name.toLowerCase());
        if (!g) return json(res, 404, { error: 'no group with that name' });
        const j = await call('POST', '/SyncPlay/Join', { GroupId: g.GroupId });
        return json(res, j.status < 300 ? 200 : j.status, { status: j.status });
      }
      case '/pause': case '/unpause': case '/leave': case '/buffering': {
        const path = { '/pause': '/SyncPlay/Pause', '/unpause': '/SyncPlay/Unpause', '/leave': '/SyncPlay/Leave', '/buffering': '/SyncPlay/Buffering' }[u.pathname];
        const body = u.pathname === '/buffering' ? { When: serverIso(), PositionTicks: Math.round(position()), IsPlaying: false, PlaylistItemId: st.playlistItemId } : undefined;
        const r = await call('POST', path, body);
        return json(res, r.status < 300 ? 200 : r.status, { status: r.status });
      }
      case '/seek': {
        const ticks = Number(u.searchParams.get('ticks'));
        const r = await call('POST', '/SyncPlay/Seek', { PositionTicks: ticks });
        return json(res, r.status < 300 ? 200 : r.status, { status: r.status });
      }
      case '/lookup': {
        const term = u.searchParams.get('term') || '';
        const r = await call('GET', `/Items?searchTerm=${encodeURIComponent(term)}&Recursive=true&IncludeItemTypes=Movie,Episode,Video&Limit=20`);
        const items = (r.body?.Items || []).filter(i => (i.Name || '').startsWith(term));
        const exact = items.find(i => i.Name === term) || items[0];
        if (!exact) return json(res, 404, { error: 'no item' });
        return json(res, 200, { runtimeTicks: exact.RunTimeTicks, count: items.length });
      }
      case '/quit':
        json(res, 200, {}); if (st.groupId) await call('POST', '/SyncPlay/Leave'); process.exit(0);
      default: return json(res, 404, { error: 'unknown' });
    }
  } catch (e) { note(`error ${u.pathname}`, String(e)); return json(res, 500, { error: String(e) }); }
});

try { await login(); } catch (e) { note('auth failed', String(e)); console.error('bot: auth failed'); process.exit(1); }
server.listen(0, '127.0.0.1', () => {
  fs.writeFileSync(`${runDir}/bot.port`, String(server.address().port));
  note('control port ready');
});
