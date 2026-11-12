package meshconsole.ui;

/** /meshcore – the MeshCore radio in the browser: chat (channels and DMs), contacts, signal, radio. */
final class MeshCorePage {
    private MeshCorePage() { }

    static final String HTML = """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MeshCore – Mesh Console</title>
<style>
:root { --bg:#0d1117; --card:#161b22; --line:#30363d; --text:#e6edf3; --dim:#8b949e; --acc:#d29922; --ok:#3fb950; --bad:#f85149; }
* { box-sizing:border-box; } body { margin:0; background:var(--bg); color:var(--text); font:14px system-ui,-apple-system,Segoe UI,sans-serif; }
header { display:flex; gap:10px; align-items:center; padding:8px 12px; border-bottom:1px solid var(--line); flex-wrap:wrap; }
h1 { font-size:16px; margin:0; } .dim { color:var(--dim); } a { color:#58a6ff; }
.tabs button, button { background:var(--card); color:var(--text); border:1px solid var(--line); border-radius:6px; padding:5px 11px; cursor:pointer; }
.tabs button.on { border-color:var(--acc); color:var(--acc); }
main { padding:10px 12px; } .pane[hidden] { display:none; }
.dot { display:inline-block; width:10px; height:10px; border-radius:50%; background:var(--bad); } .dot.on { background:var(--ok); }
#chat { display:grid; grid-template-columns:270px 1fr; gap:0; height:calc(100dvh - 64px); border:1px solid var(--line); border-radius:8px; overflow:hidden; }
#side { display:flex; flex-direction:column; min-height:0; border-right:1px solid var(--line); background:var(--card); }
#find { margin:8px; padding:7px; background:var(--bg); color:var(--text); border:1px solid var(--line); border-radius:6px; }
#convs { overflow:auto; flex:1; }
.conv { padding:8px 10px; border-bottom:1px solid #1d242b; cursor:pointer; } .conv:hover { background:#1a2129; } .conv.on { background:#1f2a37; }
.conv .t { display:flex; gap:6px; justify-content:space-between; } .conv .t b { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; } .conv .t span { font-size:11px; color:var(--dim); flex:none; }
.conv .p { font-size:12.5px; color:var(--dim); display:flex; justify-content:space-between; gap:6px; } .conv .p span:first-child { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.badge { background:var(--acc); color:#000; border-radius:10px; padding:0 7px; font-size:11px; font-weight:700; flex:none; }
.sec { padding:8px 10px 3px; font-size:11px; color:var(--dim); text-transform:uppercase; letter-spacing:.06em; }
#main { display:flex; flex-direction:column; min-height:0; min-width:0; }
#head { display:flex; align-items:center; gap:8px; padding:8px 10px; border-bottom:1px solid var(--line); background:var(--card); } #head b { font-size:15px; } #back { display:none; }
#thread { flex:1; overflow:auto; padding:8px 12px; }
.day { text-align:center; margin:12px 0 6px; } .day span { background:#1a2027; color:var(--dim); font-size:11.5px; padding:2px 10px; border-radius:10px; }
.row { display:flex; margin:3px 0; position:relative; } .row.out { justify-content:flex-end; }
.bub { max-width:min(620px, 80%); padding:6px 10px 5px; border-radius:14px; background:#222a32; white-space:pre-wrap; word-wrap:break-word; }
.row.out .bub { background:#7a5a10; } .row.mention .bub { box-shadow:0 0 0 2px var(--acc) inset; }
.bub .nm { font-size:12px; font-weight:700; margin-bottom:1px; } .bub .meta { font-size:11px; opacity:.72; margin-top:2px; text-align:right; }
.reacts { display:flex; gap:4px; flex-wrap:wrap; margin-top:3px; }
.react { background:rgba(0,0,0,.35); border:1px solid transparent; border-radius:10px; padding:0 6px; font-size:13px; cursor:pointer; } .react.mine { border-color:var(--acc); background:rgba(210,153,34,.2); }
.acts { position:absolute; top:-16px; display:none; gap:1px; background:#1c232a; border:1px solid var(--line); border-radius:10px; padding:2px; z-index:3; }
.row:not(.out) .acts { left:6px; } .row.out .acts { right:6px; } .row:hover .acts, .row.touch .acts { display:flex; }
.acts button { border:0; background:transparent; padding:2px 6px; font-size:16px; } .acts .sep { width:1px; background:var(--line); margin:3px 2px; }
.hint { font-size:11px; color:var(--dim); text-align:center; padding:2px 6px 4px; }
#compose { border-top:1px solid var(--line); padding:7px 9px; background:var(--card); }
#replyChip { display:none; align-items:center; gap:8px; font-size:12.5px; border-left:3px solid var(--acc); padding:2px 8px; margin-bottom:6px; } #replyChip span { flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
#quick { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:6px; } #quick button { padding:2px 9px; font-size:12.5px; border-radius:12px; }
.crow { display:flex; gap:6px; align-items:flex-end; } #text { flex:1; resize:none; min-height:38px; max-height:130px; padding:8px; background:var(--bg); color:var(--text); border:1px solid var(--line); border-radius:8px; font:inherit; }
#count { font-size:11px; min-width:50px; text-align:right; } #count.over { color:var(--bad); font-weight:700; } #send { background:var(--acc); color:#000; font-weight:600; border-color:var(--acc); }
.pop { position:fixed; z-index:20; background:#1c232a; border:1px solid var(--line); border-radius:12px; padding:6px; display:none; flex-wrap:wrap; width:240px; box-shadow:0 6px 24px rgba(0,0,0,.5); } .pop button { border:0; background:transparent; font-size:22px; padding:3px 5px; }
#toast { position:fixed; bottom:20px; left:50%; transform:translateX(-50%); background:#1f6feb; color:#fff; padding:8px 14px; border-radius:10px; display:none; z-index:50; max-width:90vw; }
@media (max-width:720px) { main { padding:4px; } #chat { grid-template-columns:1fr; height:calc(100dvh - 100px); } #main { display:none; } #chat.open #side { display:none; } #chat.open #main { display:flex; } #back { display:inline-block; } }
table { border-collapse:collapse; width:100%; } td, th { padding:5px 8px; border-bottom:1px solid var(--line); text-align:left; } th { color:var(--dim); font-weight:500; }
.card { background:var(--card); border:1px solid var(--line); border-radius:8px; padding:12px; margin-bottom:10px; }
input[type=text] { padding:7px; background:var(--bg); color:var(--text); border:1px solid var(--line); border-radius:6px; width:min(420px,90%); }
#login { position:fixed; inset:0; background:rgba(0,0,0,.75); display:flex; align-items:center; justify-content:center; } #login[hidden] { display:none; }
canvas { width:100%; height:220px; background:var(--card); border:1px solid var(--line); border-radius:8px; }
</style></head><body>
<header><span id="dot" class="dot"></span><h1 id="title">MeshCore</h1><span id="sub" class="dim"></span><span style="flex:1"></span>
<a href="./">← Meshtastic</a>
<span class="tabs"><button data-tab="chat" class="on">Chat</button><button data-tab="contacts">Contacts</button><button data-tab="signal">Signal</button><button data-tab="radio">Radio</button></span></header>
<main>
 <div class="pane" id="pane-chat"><div id="chat">
   <div id="side"><input id="find" type="search" placeholder="Search chats"><div id="convs"></div></div>
   <div id="main"><div id="head"><button id="back">←</button><b id="hname">Pick a chat</b><span id="hsub" class="dim"></span></div>
     <div id="thread"></div><div class="hint">Tap a message for reactions, reply and more · double-tap to react with your favourite · tap a reaction to add yours</div>
     <div id="compose"><div id="replyChip">↩ <span id="replyText"></span><button id="replyX">✕</button></div><div id="quick"></div>
       <div class="crow"><button id="emojiBtn" title="Emoji">😀</button><textarea id="text" rows="1" placeholder="Message"></textarea><span id="count" class="dim"></span><button id="send">Send</button></div></div></div>
 </div></div>
 <div class="pane" id="pane-contacts" hidden><table><thead><tr><th>Name</th><th>Type</th><th>Last heard</th><th>Hops</th><th>Direct</th><th>RSSI</th><th>SNR</th><th>Position</th><th></th></tr></thead><tbody id="ctab"></tbody></table></div>
 <div class="pane" id="pane-signal" hidden><div class="card" id="sigsum"></div><canvas id="cv" width="1200" height="260"></canvas>
   <p class="dim">Every packet the radio heard in the last 24 h. Dots: SNR (left scale); orange = heard directly, grey = relayed. MeshCore reports one RSSI/SNR per packet – for relayed packets it's the last repeater's signal, not the sender's.</p>
   <table><thead><tr><th>Time</th><th>Type</th><th>Hops</th><th>RSSI</th><th>SNR</th><th>Sender</th><th>Last hop</th></tr></thead><tbody id="stab"></tbody></table></div>
 <div class="pane" id="pane-radio" hidden>
   <div class="card" id="radio"></div>
   <div class="card"><b>Connection</b><p class="dim">The radio must run MeshCore <b>Companion</b> firmware. WiFi: <code>tcp:192.168.1.50:5000</code> &nbsp; USB: <code>serial:/dev/ttyACM1</code> (Windows: <code>serial:COM7</code>). Empty = off.</p>
     <input type="text" id="conn"> <button id="save">Save &amp; connect</button> <span id="cnote" class="dim"></span></div>
   <div class="card"><b>Position</b><p class="dim">No GPS needed – type it in. Longitude is negative in the US.</p>
     Lat <input type="text" id="plat" style="width:140px"> Lon <input type="text" id="plon" style="width:140px">
     <label><input type="checkbox" id="pshare" checked> include in adverts</label> <button id="psave">Set position</button> <span id="pnote" class="dim"></span></div>
   <div class="card"><b>Advert</b><p class="dim">Tell nearby MeshCore nodes you're here. Flood reaches the whole mesh through repeaters; zero-hop only nodes in direct range.</p>
     <button id="adv1">Send flood advert</button> <button id="adv0">Zero-hop advert</button> <span id="anote" class="dim"></span></div>
 </div>
</main>
<div class="pop" id="emojis"></div><div id="toast"></div>
<div id="login"><div class="card"><b>Mesh Console password</b><p class="dim">The same one as the main page (web-password.txt).</p><input type="text" id="pw"> <button id="pwGo">OK</button><div id="pwNote" class="dim"></div></div></div>
<script>
const $ = id => document.getElementById(id), KEY = "mc.pass." + location.port;
let pass = localStorage.getItem(KEY) || "", S = null, M = [], cur = localStorage.getItem("mc.core.cur") || "#0", replyTo = null, MM = new Map(), loaded = false, notified = 0;
const EMO = ["👍", "❤️", "😂", "😮", "😢", "🙏", "👋", "✅", "🔥", "📡", "👀", "🎉"], QUICK_DEF = ["Copy, thanks", "Signal check?", "Hearing you well", "73"];
const RK = "mc.core.read." + location.port + ".";
const RX = /^(\\p{Extended_Pictographic}(?:\\uFE0F|\\u200D\\p{Extended_Pictographic}|\\p{Emoji_Modifier})*)(?:\\s+@\\[(.+?)\\])?$/u;
async function api(path, body) {
  const r = await fetch(path, body ? { method:"POST", headers:{ "X-Pass":pass, "Content-Type":"application/x-www-form-urlencoded" }, body:new URLSearchParams(body) } : { headers:{ "X-Pass":pass } });
  if (r.status === 401) { $("login").hidden = false; throw new Error("password"); }
  return r.json();
}
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;" }[c]));
const ago = t => { if (!t) return "never"; const s = (Date.now() - t) / 1000; return s < 90 ? Math.round(s) + " s ago" : s < 5400 ? Math.round(s / 60) + " min ago" : s < 172800 ? Math.round(s / 3600) + " h ago" : Math.round(s / 86400) + " d ago"; };
const hm = t => new Date(t).toLocaleString([], { month:"short", day:"numeric", hour:"2-digit", minute:"2-digit" });
document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => {
  document.querySelectorAll(".tabs button").forEach(x => x.classList.toggle("on", x === b));
  document.querySelectorAll(".pane").forEach(p => p.hidden = p.id !== "pane-" + b.dataset.tab);
  if (b.dataset.tab === "signal") signal();
});
$("pwGo").onclick = async () => { pass = $("pw").value.trim(); localStorage.setItem(KEY, pass); try { await api("api/meshcore/state"); $("login").hidden = true; refresh(); } catch (e) { $("pwNote").textContent = "Wrong password."; } };
function peerName(p) {
  if (!S) return p;
  if (p.startsWith("#")) { const c = S.channels.find(x => "#" + x.idx === p); return c ? (c.name.startsWith("#") ? c.name : "# " + c.name) : p; }
  const c = S.contacts.find(x => x.prefix === p); return c ? c.name : p;
}
const mkey = m => m.t + "|" + m.peer + "|" + (m.out ? 1 : 0) + "|" + m.text;
const isReact = m => RX.test(m.text.trim());
const bytes = t => new TextEncoder().encode(t).length;
const hue = s => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) % 360; return h; };
function toast(t) { const el = $("toast"); el.textContent = t; el.style.display = "block"; clearTimeout(el._t); el._t = setTimeout(() => el.style.display = "none", 3500); }
function msgs(peer) { return [...MM.values()].filter(m => m.peer === peer).sort((a, b) => a.t - b.t); }
function unread(peer) { const r = +localStorage.getItem(RK + peer) || 0; return msgs(peer).filter(m => !m.out && !isReact(m) && m.t > r).length; }
function markRead() { if (cur) localStorage.setItem(RK + cur, String(Date.now())); }
function convList() {
  const out = [];
  S.channels.forEach(c => out.push({ id:"#" + c.idx, name:(c.name.startsWith("#") ? c.name : "# " + c.name), kind:"ch" }));
  S.contacts.filter(c => c.type === "chat" || c.type === "room").forEach(c => out.push({ id:c.prefix, name:(c.type === "room" ? "🏠 " : "") + c.name, kind:"dm", heard:Math.max(c.lastHeard, c.lastAdvert) }));
  for (const o of out) { const l = msgs(o.id).filter(m => !isReact(m)); o.last = l.length ? l[l.length - 1] : null; o.unread = unread(o.id); }
  return out;
}
function drawPeers() {
  const q = $("find").value.trim().toLowerCase(), L = convList().filter(o => !q || o.name.toLowerCase().includes(q));
  const row = o => `<div class="conv ${o.id === cur ? "on" : ""}" data-p="${esc(o.id)}"><div class="t"><b>${esc(o.name)}</b><span>${o.last ? ago(o.last.t) : o.heard ? "heard " + ago(o.heard) : ""}</span></div>
    <div class="p"><span>${o.last ? esc((o.last.out ? "You: " : o.kind === "ch" ? o.last.from + ": " : "") + o.last.text) : "no messages yet"}</span>${o.unread ? `<span class="badge">${o.unread}</span>` : ""}</div></div>`;
  const ch = L.filter(o => o.kind === "ch"), dm = L.filter(o => o.kind === "dm").sort((a, b) => (b.last ? b.last.t : 0) - (a.last ? a.last.t : 0) || (b.heard || 0) - (a.heard || 0));
  $("convs").innerHTML = '<div class="sec">Channels</div>' + ch.map(row).join("") + '<div class="sec">People &amp; rooms</div>' + (dm.map(row).join("") || '<div class="hint">Nobody heard yet – send a flood advert (Radio tab)</div>');
  const total = convList().reduce((n, o) => n + o.unread, 0);
  document.title = (total ? "(" + total + ") " : "") + "MeshCore – Mesh Console";
}
$("convs").onclick = e => { const d = e.target.closest("[data-p]"); if (d) openConv(d.dataset.p); };
$("find").oninput = () => drawPeers();
function openConv(p) {
  cur = p; localStorage.setItem("mc.core.cur", cur); replyTo = null; $("replyChip").style.display = "none";
  $("chat").classList.add("open"); markRead(); drawPeers(); drawMsgs(true); $("text").focus();
}
$("back").onclick = () => { $("chat").classList.remove("open"); drawPeers(); };
// reactions: a message that is only an emoji (optionally "@[name]") attaches to the message it answers
function targetOf(r, list) {
  const mt = r.text.trim().match(RX), who = mt && mt[2];
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i];
    if (m.t > r.t || m === r || isReact(m)) continue;
    if (who) { if ((m.out ? (S.name || "") : m.from) === who) return m; }
    else if (m.out !== r.out) return m;
  }
  return null;
}
function drawMsgs(scroll) {
  const box = $("thread"), atEnd = box.scrollTop + box.clientHeight > box.scrollHeight - 40;
  const list = msgs(cur), isCh = cur.startsWith("#");
  $("hname").textContent = peerName(cur);
  const c = isCh ? null : S.contacts.find(x => x.prefix === cur);
  $("hsub").textContent = c ? " · " + c.type + (c.hops >= 0 ? " · " + c.hops + " hops" : "") + " · heard " + ago(Math.max(c.lastHeard, c.lastAdvert)) : "";
  const reacts = new Map();
  for (const r of list) if (isReact(r)) { const t = targetOf(r, list); if (t) { const k = mkey(t), a = reacts.get(k) || []; a.push(r); reacts.set(k, a); } }
  let h = "", lastDay = "", lastFrom = null;
  for (const m of list) {
    if (isReact(m) && reacts.size && [...reacts.values()].some(a => a.includes(m))) continue;
    const day = new Date(m.t).toDateString();
    if (day !== lastDay) { h += `<div class="day"><span>${new Date(m.t).toLocaleDateString([], { weekday:"short", month:"short", day:"numeric" })}</span></div>`; lastDay = day; lastFrom = null; }
    const showName = isCh && !m.out && m.from !== lastFrom; lastFrom = m.out ? "~me~" : m.from;
    const groups = {};
    for (const r of reacts.get(mkey(m)) || []) { const e = r.text.trim().match(RX)[1]; (groups[e] = groups[e] || []).push(r.out ? "you" : r.from); }
    const rh = Object.keys(groups).length ? `<div class="reacts">${Object.entries(groups).map(([e, who]) => `<span class="react${who.includes("you") ? " mine" : ""}" data-re="${esc(e)}" title="${esc(who.join(", "))}">${esc(e)}${who.length > 1 ? " " + who.length : ""}</span>`).join("")}</div>` : "";
    const meta = new Date(m.t).toLocaleTimeString([], { hour:"2-digit", minute:"2-digit" }) + (!m.out && m.hops >= 0 ? " · " + (m.hops ? m.hops + " hop" + (m.hops > 1 ? "s" : "") : "direct") : "") + (!m.out && m.snr != null ? " · SNR " + m.snr : "") + (m.out && m.status ? " · " + esc(m.status) : "");
    const mention = !m.out && S.name && m.text.includes("@[" + S.name + "]");
    const dm = isCh && !m.out && S.contacts.find(x => x.name === m.from && x.type === "chat");
    h += `<div class="row ${m.out ? "out" : ""} ${mention ? "mention" : ""}" data-k="${esc(mkey(m))}"><div class="acts">${quickReacts()}<button data-act="more" title="More reactions">➕</button><span class="sep"></span><button data-act="reply" title="Reply">↩</button><button data-act="copy" title="Copy">⧉</button>${dm ? `<button data-act="dm" data-to="${dm.prefix}" title="Reply privately">✉</button>` : ""}</div>
      <div class="bub">${showName ? `<div class="nm" style="color:hsl(${hue(m.from)},70%,70%)">${esc(m.from)}</div>` : ""}${esc(m.text)}${rh}<div class="meta">${meta}</div></div></div>`;
  }
  box.innerHTML = h || '<p class="dim" style="text-align:center">No messages here yet.</p>';
  if (scroll || atEnd) { box.scrollTop = box.scrollHeight; markRead(); }
  countBytes();
}
function recentEmo() { const r = JSON.parse(localStorage.getItem("mc.recentEmo") || "[]"); return [...new Set([...r, ...EMO])]; }
function quickReacts() { return recentEmo().slice(0, 4).map(e => `<button data-act="qr" data-e="${esc(e)}" title="React ${esc(e)}">${esc(e)}</button>`).join(""); }
function rememberEmo(e) { const r = JSON.parse(localStorage.getItem("mc.recentEmo") || "[]").filter(x => x !== e); r.unshift(e); localStorage.setItem("mc.recentEmo", JSON.stringify(r.slice(0, 8))); }
const rowMsg = row => row ? MM.get(row.dataset.k) : null;
function react(m, e) {
  const list = msgs(cur);
  if (list.some(r => r.out && isReact(r) && r.text.trim().match(RX)[1] === e && targetOf(r, list) === m)) { toast("You already reacted " + e); return; }
  rememberEmo(e);
  const who = m.out ? S.name : m.from;
  sendText(cur.startsWith("#") ? e + " @[" + who + "]" : e);
  document.querySelectorAll(".row.touch").forEach(r => r.classList.remove("touch"));
}
let lastTap = { k:null, t:0 };
$("thread").addEventListener("dblclick", e => { const m = rowMsg(e.target.closest(".row")); if (m && !e.target.closest("[data-act]")) { e.preventDefault(); react(m, recentEmo()[0]); } });
$("thread").addEventListener("click", e => {
  const row = e.target.closest(".row"); if (!row) return;
  const m = rowMsg(row), act = e.target.closest("[data-act]"), chip = e.target.closest("[data-re]");
  if (!m) return;
  if (chip) { react(m, chip.dataset.re); return; }
  if (!act) {
    const now = Date.now();
    if (matchMedia("(pointer:coarse)").matches && lastTap.k === row.dataset.k && now - lastTap.t < 350) { react(m, recentEmo()[0]); lastTap = { k:null, t:0 }; return; }
    lastTap = { k:row.dataset.k, t:now };
    document.querySelectorAll(".row.touch").forEach(r => r !== row && r.classList.remove("touch")); row.classList.toggle("touch"); return;
  }
  const a = act.dataset.act;
  if (a === "qr") react(m, act.dataset.e);
  else if (a === "more") openEmojis(act, em => react(m, em));
  else if (a === "reply") { replyTo = m; $("replyText").textContent = (m.out ? "You" : m.from) + ": " + m.text; $("replyChip").style.display = "flex"; $("text").focus(); }
  else if (a === "copy") { navigator.clipboard?.writeText(m.text); toast("Copied"); }
  else if (a === "dm") openConv(act.dataset.to);
});
$("replyX").onclick = () => { replyTo = null; $("replyChip").style.display = "none"; };
function openEmojis(anchor, pick) {
  const p = $("emojis"); p.innerHTML = recentEmo().map(x => `<button>${x}</button>`).join("");
  const r = anchor.getBoundingClientRect(); p.style.display = "flex";
  p.style.left = Math.max(8, Math.min(innerWidth - 250, r.left - 100)) + "px"; p.style.top = Math.max(8, r.top - 110) + "px";
  p.onclick = e => { const b = e.target.closest("button"); if (b) { p.style.display = "none"; pick(b.textContent); } };
}
$("emojiBtn").onclick = e => openEmojis(e.target, em => { $("text").value += em; countBytes(); $("text").focus(); });
document.addEventListener("click", e => { if (!e.target.closest("#emojis") && !e.target.closest("[data-act=more]") && e.target.id !== "emojiBtn") $("emojis").style.display = "none"; });
function quick() {
  const q = JSON.parse(localStorage.getItem("mc.core.quick") || "null") || QUICK_DEF;
  $("quick").innerHTML = q.map(t => `<button data-q="${esc(t)}">${esc(t)}</button>`).join("") + '<button data-qedit title="Edit quick replies">✎</button>';
}
$("quick").onclick = e => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.qedit !== undefined) { const v = prompt("Quick replies, separated by |", (JSON.parse(localStorage.getItem("mc.core.quick") || "null") || QUICK_DEF).join(" | ")); if (v !== null) { localStorage.setItem("mc.core.quick", JSON.stringify(v.split("|").map(x => x.trim()).filter(Boolean))); quick(); } return; }
  $("text").value = b.dataset.q; countBytes(); $("text").focus();
};
function limit() { return cur.startsWith("#") ? 140 - bytes((S && S.name || "") + ": ") : 150; }
function outgoing() { const t = $("text").value.trim(); return replyTo && cur.startsWith("#") && !replyTo.out ? "@[" + replyTo.from + "] " + t : t; }
function countBytes() {
  const t = $("text"), n = bytes(outgoing()), L = limit();
  $("count").textContent = n + "/" + L; $("count").classList.toggle("over", n > L);
  t.style.height = "auto"; t.style.height = Math.min(130, t.scrollHeight) + "px";
}
$("text").addEventListener("input", countBytes);
$("text").addEventListener("keydown", e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("send").click(); } });
async function sendText(text) {
  if (!cur) { toast("Pick a chat first"); return false; }
  if (bytes(text) > limit()) { toast("Too long – " + bytes(text) + " of " + limit() + " bytes"); return false; }
  if (window.Notification && Notification.permission === "default") Notification.requestPermission();
  try { const r = await api("api/meshcore/send", { to:cur, text }); if (/refused|unknown|too long|not connected|no reply/.test(r.result)) { toast(r.result); return false; } refresh(); return true; }
  catch (e) { toast(e.message); return false; }
}
$("send").onclick = async () => {
  const t = $("text").value.trim(); if (!t) return;
  $("send").disabled = true;
  if (await sendText(outgoing())) { $("text").value = ""; replyTo = null; $("replyChip").style.display = "none"; countBytes(); }
  $("send").disabled = false; $("text").focus();
};
function notify(fresh) {
  if (!document.hidden || !fresh.length || !window.Notification || Notification.permission !== "granted") return;
  const m = fresh[fresh.length - 1];
  new Notification(peerName(m.peer) + (m.peer.startsWith("#") ? " – " + m.from : ""), { body:m.text, tag:"meshcore" });
}
function drawContacts() {
  $("ctab").innerHTML = S.contacts.map(c => `<tr><td>${esc(c.name)}<div class="dim" style="font-size:11px">${c.prefix}</div></td><td>${c.type}</td><td>${ago(Math.max(c.lastHeard, c.lastAdvert))}</td><td>${c.hops >= 0 ? c.hops : ""}</td><td>${c.direct || ""}</td><td>${c.rssi ?? ""}</td><td>${c.snr ?? ""}</td><td>${c.lat || c.lon ? c.lat.toFixed(4) + ", " + c.lon.toFixed(4) : ""}</td><td>${c.type === "chat" || c.type === "room" ? `<button data-dm="${c.prefix}">Message</button>` : ""}</td></tr>`).join("");
  $("ctab").querySelectorAll("[data-dm]").forEach(b => b.onclick = () => { document.querySelector('[data-tab="chat"]').click(); openConv(b.dataset.dm); });
}
function drawRadio() {
  $("dot").classList.toggle("on", !!S.connected);
  if (S.connected && !posFilled) { posFilled = true; $("plat").value = S.lat || "38.8895"; $("plon").value = S.lon || "-77.0353"; $("pshare").checked = S.shareLoc !== false; }
  $("title").textContent = S.name ? "MeshCore – " + S.name : "MeshCore";
  $("sub").textContent = !S.configured ? "not set up – see Radio" : S.connected ? S.freq.toFixed(3) + " MHz · SF" + S.sf + " · " + S.bw + " kHz" : "connecting to " + S.where + "…";
  $("radio").innerHTML = !S.configured ? "<b>No MeshCore radio set up yet.</b> Enter its connection below." :
    `<b>${esc(S.name || "(not connected)")}</b><table>
     <tr><td class="dim">Connection</td><td>${esc(S.where)} – ${S.connected ? "connected" : "not connected"}</td></tr>
     <tr><td class="dim">Firmware</td><td>${esc(S.firmware)} ${S.build ? "(" + esc(S.build) + ")" : ""} on ${esc(S.board)}</td></tr>
     <tr><td class="dim">Radio</td><td>${S.freq.toFixed(3)} MHz, ${S.bw} kHz, SF${S.sf}, CR${S.cr}</td></tr>
     <tr><td class="dim">TX power setting</td><td>${S.tx} dBm (max ${S.txMax}) – on a Station G2/G3 this drives the amplifier; check MeshCore's FAQ for the real output</td></tr>
     <tr><td class="dim">Position</td><td>${S.lat || S.lon ? S.lat.toFixed(5) + ", " + S.lon.toFixed(5) + (S.shareLoc ? " (in adverts)" : " (not in adverts)") : "not set"}${S.lon > 0 && S.lat > 20 && S.lat < 50 ? ' <b style="color:var(--bad)">– longitude should be negative for the US</b>' : ""}</td></tr>
     <tr><td class="dim">Battery</td><td>${S.battMv ? (S.battMv / 1000).toFixed(2) + " V" : "–"}</td></tr>
     <tr><td class="dim">Public key</td><td style="font-family:monospace;font-size:11px;word-break:break-all">${esc(S.key)}</td></tr></table>`;
}
async function refresh() {
  try {
    S = await api("api/meshcore/state");
    const got = await api("api/meshcore/messages" + (loaded ? "?since=" + (Date.now() - 1800000) : ""));
    const fresh = [];
    for (const m of got) { const k = mkey(m); if (!MM.has(k) && loaded && !m.out && !isReact(m)) fresh.push(m); MM.set(k, m); }
    loaded = true;
    $("login").hidden = true;
    drawRadio();
    if (S.configured) {
      drawPeers();
      if (!document.querySelector(".row.touch") && document.activeElement !== $("emojis")) drawMsgs(false);
      drawContacts();
      notify(fresh);
    }
  } catch (e) { }
}
async function signal() {
  const d = await api("api/meshcore/signal"); if (!d.points) return;
  $("sigsum").innerHTML = `<b>${d.packets}</b> packets in 24 h, <b>${d.direct}</b> heard directly · ` + Object.entries(d.byType).map(([k, v]) => k + " " + v).join(" · ");
  const cv = $("cv"), g = cv.getContext("2d"), W = cv.width, H = cv.height, t0 = Date.now() - 86400000;
  g.clearRect(0, 0, W, H); g.strokeStyle = "#30363d"; g.fillStyle = "#8b949e"; g.font = "12px sans-serif";
  for (let s = -20; s <= 15; s += 5) { const y = H - 20 - (s + 20) / 35 * (H - 30); g.beginPath(); g.moveTo(30, y); g.lineTo(W, y); g.stroke(); g.fillText(s, 4, y + 4); }
  d.points.forEach(p => { const x = 30 + (p[0] - t0) / 86400000 * (W - 30), y = H - 20 - (Math.max(-20, Math.min(15, p[2])) + 20) / 35 * (H - 30); g.fillStyle = p[3] === 0 ? "#d29922" : "#6e7681"; g.fillRect(x - 1.5, y - 1.5, 3, 3); });
  $("stab").innerHTML = d.points.slice(-60).reverse().map(p => { const c = S && p[5] ? S.contacts.find(x => x.prefix === p[5]) : null; return `<tr><td>${hm(p[0])}</td><td>${p[4]}</td><td>${p[3] === 0 ? "direct" : p[3]}</td><td>${p[1]}</td><td>${p[2]}</td><td>${c ? esc(c.name) : p[5]}</td><td>${p[6]}</td></tr>`; }).join("");
}
$("save").onclick = async () => { const r = await api("api/meshcore/config/save", { connect:$("conn").value.trim() }); $("cnote").textContent = r.result; setTimeout(refresh, 3000); };
$("psave").onclick = async () => { const r = await api("api/meshcore/position", { lat:$("plat").value, lon:$("plon").value, share:$("pshare").checked ? "1" : "0" }); $("pnote").textContent = r.result; refresh(); };
let posFilled = false;
$("adv1").onclick = async () => $("anote").textContent = (await api("api/meshcore/advert", { flood:"1" })).result;
$("adv0").onclick = async () => $("anote").textContent = (await api("api/meshcore/advert", { flood:"0" })).result;
quick();
(async () => { try { $("conn").value = (await api("api/meshcore/config")).connect; } catch (e) { } refresh(); setInterval(refresh, 4000); })();
</script></body></html>
""";
}
