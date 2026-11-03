package meshconsole.ui;

/**
 * /chat – messaging the way a chat app does it: conversations on the left (channels and DMs, unread counts), the thread
 * in the middle (replies quoted, emoji reactions, delivery ticks, retry for failed messages, hops and signal on every
 * received message), and the other node's details on the right (signal history, traceroute, battery). Live: new
 * messages, delivery receipts and traceroute results arrive as they happen (server-sent events).
 */
final class ChatPage {
    private ChatPage() { }
    static final String HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Mesh chat</title>
<style>
:root { --bg:#0e1114; --panel:#161b20; --panel2:#1c232a; --line:#28313a; --text:#e6e9ec; --dim:#8b949e; --me:#1d4ed8; --meText:#fff; --them:#222a32; --accent:#58a6ff; --bad:#f85149; --ok:#3fb950; --warn:#e3b341; }
* { box-sizing:border-box; }
html, body { height:100%; }
body { margin:0; background:var(--bg); color:var(--text); font:14px/1.4 system-ui, -apple-system, "Segoe UI", sans-serif; display:flex; flex-direction:column; padding-top:env(safe-area-inset-top, 0px); padding-bottom:env(safe-area-inset-bottom, 0px); }
button { background:var(--panel2); color:var(--text); border:1px solid var(--line); border-radius:8px; padding:6px 10px; font:inherit; cursor:pointer; }
button:hover { border-color:var(--accent); }
input, textarea, select { background:#0b0e11; color:var(--text); border:1px solid var(--line); border-radius:8px; padding:7px 9px; font:inherit; }
.dim { color:var(--dim); }
#top { display:flex; align-items:center; gap:10px; padding:8px 12px; background:var(--panel); border-bottom:1px solid var(--line); }
#top h1 { font-size:15px; margin:0; } #dot { width:9px; height:9px; border-radius:50%; background:var(--bad); }
#dot.ok { background:var(--ok); } #live { font-size:12px; }
#app { flex:1; display:grid; grid-template-columns:300px 1fr 300px; min-height:0; }
#app.noinfo { grid-template-columns:300px 1fr 0; }
/* conversation list */
#side { border-right:1px solid var(--line); display:flex; flex-direction:column; min-height:0; background:var(--panel); }
#side .bar { display:flex; gap:6px; padding:8px; } #side .bar input { flex:1; }
#convs { overflow:auto; flex:1; }
.conv { display:flex; gap:10px; padding:9px 10px; cursor:pointer; border-bottom:1px solid #1d242b; }
.conv:hover { background:#1a2129; } .conv.on { background:#1f2a36; }
.av { flex:none; width:38px; height:38px; border-radius:50%; display:flex; align-items:center; justify-content:center; font-weight:700; font-size:12px; color:#0b0e11; }
.conv .mid { flex:1; min-width:0; } .conv .t { display:flex; justify-content:space-between; gap:6px; }
.conv .t b { white-space:nowrap; overflow:hidden; text-overflow:ellipsis; } .conv .t span { font-size:11px; color:var(--dim); flex:none; }
.conv .p { font-size:12.5px; color:var(--dim); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; display:flex; justify-content:space-between; gap:6px; }
.badge { background:var(--accent); color:#000; border-radius:10px; padding:0 7px; font-size:11px; font-weight:700; flex:none; }
.sec { padding:8px 10px 4px; font-size:11px; text-transform:uppercase; letter-spacing:.06em; color:var(--dim); }
/* thread */
#main { display:flex; flex-direction:column; min-height:0; min-width:0; }
#head { display:flex; align-items:center; gap:10px; padding:8px 12px; border-bottom:1px solid var(--line); background:var(--panel); }
#head .who { flex:1; min-width:0; } #head .who b { display:block; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
#back { display:none; }
#thread { flex:1; overflow:auto; padding:10px 14px 6px; }
.day { text-align:center; margin:14px 0 8px; } .day span { background:#1a2027; color:var(--dim); font-size:11.5px; padding:2px 10px; border-radius:10px; }
.row { display:flex; margin:2px 0; position:relative; } .row.out { justify-content:flex-end; }
.bub { max-width:min(640px, 78%); padding:6px 10px 5px; border-radius:14px; background:var(--them); position:relative; word-wrap:break-word; white-space:pre-wrap; }
.row.out .bub { background:var(--me); color:var(--meText); }
.row.mention .bub { box-shadow:0 0 0 2px var(--warn) inset; }
.bub .nm { font-size:12px; font-weight:700; margin-bottom:1px; cursor:pointer; }
.bub .meta { font-size:11px; opacity:.72; margin-top:2px; display:flex; gap:6px; justify-content:flex-end; flex-wrap:wrap; }
.row.out .bub .meta { color:#dbe6ff; }
.quote { border-left:3px solid var(--accent); background:rgba(255,255,255,.06); border-radius:6px; padding:3px 7px; margin:2px 0 4px; font-size:12.5px; opacity:.9; cursor:pointer; white-space:normal; }
.reacts { display:flex; gap:4px; flex-wrap:wrap; margin-top:3px; }
.react { background:rgba(0,0,0,.35); border-radius:10px; padding:0 6px; font-size:13px; cursor:default; }
.tick.ok { color:#9be9a8; } .tick.bad { color:#ffb3ad; }
.retry { background:transparent; border:1px solid #ffb3ad; color:#ffb3ad; padding:0 6px; font-size:11px; border-radius:6px; }
.acts { position:absolute; top:-14px; display:none; gap:2px; background:var(--panel2); border:1px solid var(--line); border-radius:10px; padding:2px; z-index:3; }
.row:not(.out) .acts { left:8px; } .row.out .acts { right:8px; }
.row:hover .acts, .row.touch .acts { display:flex; }
.acts button { border:0; background:transparent; padding:2px 6px; font-size:15px; }
.flash .bub { animation:fl 1.2s; } @keyframes fl { 0% { box-shadow:0 0 0 3px var(--accent); } 100% { box-shadow:none; } }
/* composer */
#compose { border-top:1px solid var(--line); padding:8px 10px; background:var(--panel); }
#replyChip { display:none; align-items:center; gap:8px; font-size:12.5px; border-left:3px solid var(--accent); padding:2px 8px; margin-bottom:6px; }
#replyChip span { flex:1; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
#quick { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:6px; } #quick button { padding:2px 9px; font-size:12.5px; border-radius:12px; }
.crow { display:flex; gap:6px; align-items:flex-end; }
#text { flex:1; resize:none; min-height:38px; max-height:140px; }
#count { font-size:11px; min-width:52px; text-align:right; } #count.over { color:var(--bad); font-weight:700; }
#send { background:var(--me); border-color:var(--me); color:#fff; font-weight:600; }
/* info panel */
#info { border-left:1px solid var(--line); background:var(--panel); overflow:auto; padding:12px; min-width:0; }
#app.noinfo #info { display:none; }
#info h2 { font-size:16px; margin:6px 0 2px; } .kv { display:grid; grid-template-columns:auto 1fr; gap:3px 10px; font-size:13px; margin:10px 0; }
.kv span:nth-child(odd) { color:var(--dim); }
#spark { width:100%; height:70px; background:#0b0e11; border:1px solid var(--line); border-radius:8px; }
#trace { font-size:12.5px; white-space:pre-wrap; background:#0b0e11; border:1px solid var(--line); border-radius:8px; padding:6px 8px; display:none; margin-top:6px; }
/* popovers */
.pop { position:fixed; z-index:20; background:var(--panel2); border:1px solid var(--line); border-radius:12px; padding:6px; display:none; box-shadow:0 6px 24px rgba(0,0,0,.5); }
#emojis button { border:0; background:transparent; font-size:22px; padding:3px 5px; }
#modal { position:fixed; inset:0; background:rgba(0,0,0,.65); display:none; align-items:flex-start; justify-content:center; z-index:30; padding-top:10vh; }
#modal .box { background:var(--panel); border:1px solid var(--line); border-radius:12px; width:min(460px, 94vw); max-height:70vh; display:flex; flex-direction:column; padding:10px; }
#pick { overflow:auto; margin-top:8px; }
#login { position:fixed; inset:0; background:rgba(0,0,0,.8); display:flex; align-items:center; justify-content:center; z-index:40; }
#login[hidden] { display:none; }
#login .box { background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:16px; width:min(360px, 92vw); }
#toast { position:fixed; bottom:calc(16px + env(safe-area-inset-bottom, 0px)); left:50%; transform:translateX(-50%); background:#1f6feb; color:#fff; padding:8px 14px; border-radius:10px; display:none; z-index:50; max-width:90vw; }
@media (max-width: 1000px) { #app, #app.noinfo { grid-template-columns:280px 1fr 0; } #info { display:none; position:fixed; inset:48px 0 0 auto; width:min(340px, 92vw); z-index:15; box-shadow:-8px 0 24px rgba(0,0,0,.5); }
  #app.showinfo #info { display:block; } }
@media (max-width: 720px) { #app, #app.noinfo { grid-template-columns:1fr; } #main { display:none; } #app.open #side { display:none; } #app.open #main { display:flex; } #back { display:inline-block; } }
</style>
</head>
<body>
<div id="top"><span id="dot"></span><h1 id="title">Mesh chat</h1><span id="live" class="dim">connecting…</span><span style="flex:1"></span>
  <span id="radio" class="dim"></span><a href="./" style="color:var(--accent)">Console</a></div>
<div id="app" class="noinfo">
  <div id="side"><div class="bar"><input id="find" type="search" placeholder="Search chats"><button id="newDm" title="New direct message">✎</button></div><div id="convs"></div></div>
  <div id="main">
    <div id="head"><button id="back">←</button><div class="av" id="hav"></div><div class="who"><b id="hname">Select a chat</b><span class="dim" id="hsub"></span></div>
      <input id="tfind" type="search" placeholder="Search in chat" size="12"><button id="infoBtn" title="Details">ⓘ</button></div>
    <div id="thread"></div>
    <div id="compose">
      <div id="replyChip">↩ <span id="replyText"></span><button id="replyX">✕</button></div>
      <div id="quick"></div>
      <div class="crow"><button id="emojiBtn" title="Emoji">😀</button><textarea id="text" rows="1" placeholder="Message"></textarea><span id="count" class="dim">0/200</span><button id="send">Send</button></div>
    </div>
  </div>
  <div id="info"></div>
</div>
<div class="pop" id="emojis"></div>
<div id="modal"><div class="box"><div style="display:flex;gap:6px"><input id="pickFind" placeholder="Find a node by name or ID" style="flex:1"><button id="pickX">✕</button></div><div id="pick"></div></div></div>
<div id="login" hidden><div class="box"><b>Mesh chat</b><p class="dim">Web console password (web-password.txt in this radio's data folder).</p>
  <input id="pw" type="password" style="width:100%"><p><button id="pwGo">Open</button> <span id="pwNote" class="dim"></span></p></div></div>
<div id="toast"></div>
<script>
"use strict";
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const KEY = "mc.pass." + location.port, RK = "mc.read." + location.port + ".";
let pass = localStorage.getItem(KEY) || "", session = "";
let MY = "", MYNAME = "", MYSHORT = "", CH = [], MSG = new Map(), NODES = new Map(), cur = null, replyTo = null, es = null, statusS = {};
const EMO = ["👍", "❤️", "😂", "😮", "😢", "🙏", "👋", "✅", "🔥", "📡", "73", "👀"];
const QUICK_DEF = ["Copy, thanks", "Signal check?", "Got you, good signal", "73"];

// ---------------------------------------------------------------- plumbing
async function api(path, body) {
  const r = await fetch(path, body ? { method: "POST", headers: { "X-Pass": pass, "Content-Type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(body) } : { headers: { "X-Pass": pass } });
  if (r.status === 401) { $("login").hidden = false; throw new Error("password"); }
  return r.json();
}
function toast(t) { const el = $("toast"); el.textContent = t; el.style.display = "block"; clearTimeout(el._t); el._t = setTimeout(() => el.style.display = "none", 4000); }
const bytes = s => new TextEncoder().encode(s).length;
const hue = s => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) % 360; return h; };
const avatar = (label, seed) => `<div class="av" style="background:hsl(${hue(seed)},65%,65%)">${esc(String(label || "?").slice(0, 4))}</div>`;
const when = t => { const d = new Date(t), now = new Date(); return d.toDateString() === now.toDateString() ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : d.toLocaleDateString([], { month: "short", day: "numeric" }); };
const ago = ms => { const s = Math.round(ms / 1000); return s < 90 ? s + " s" : s < 5400 ? Math.round(s / 60) + " min" : s < 172800 ? Math.round(s / 3600) + " h" : Math.round(s / 86400) + " d"; };
const mkey = m => m.id + ":" + m.from;
const convOf = m => m.to === "^all" ? "ch" + m.channel : "dm" + (m.out ? m.to : m.from);
const node = id => NODES.get(id) || {};
const nodeName = id => { const n = node(id); return n.long || n.short || id; };
const readAt = k => +(localStorage.getItem(RK + k) || 0);

// ---------------------------------------------------------------- conversations
function conversations() {
  const map = new Map();
  for (const c of CH) map.set("ch" + c.index, { key: "ch" + c.index, title: "#" + c.name, chan: c.index, list: [] });
  for (const m of MSG.values()) {
    if (m.emoji) continue;
    const k = convOf(m);
    if (!map.has(k)) { const peer = k.slice(2); map.set(k, { key: k, title: nodeName(peer), peer, list: [] }); }
    map.get(k).list.push(m);
  }
  for (const c of map.values()) {
    c.list.sort((a, b) => a.time - b.time);
    c.last = c.list[c.list.length - 1];
    c.unread = c.list.filter(m => !m.out && m.time > readAt(c.key)).length;
  }
  return [...map.values()];
}
function renderConvs() {
  const q = $("find").value.trim().toLowerCase();
  const all = conversations().filter(c => !q || c.title.toLowerCase().includes(q) || c.list.some(m => m.text.toLowerCase().includes(q)));
  const chans = all.filter(c => c.chan !== undefined), dms = all.filter(c => c.peer).sort((a, b) => (b.last?.time || 0) - (a.last?.time || 0));
  const row = c => {
    const l = c.last, who = l ? (l.out ? "You: " : c.chan !== undefined ? (l.fromShort || l.fromName) + ": " : "") : "";
    const n = c.peer ? node(c.peer) : null;
    return `<div class="conv ${cur === c.key ? "on" : ""}" data-k="${esc(c.key)}">${c.peer ? avatar(n.short || c.title, c.peer) : `<div class="av" style="background:#30363d;color:var(--text)">#</div>`}
      <div class="mid"><div class="t"><b>${esc(c.title)}</b><span>${l ? when(l.time) : ""}</span></div>
      <div class="p"><span>${l ? esc(who + l.text) : (c.peer ? "" : "No messages yet")}</span>${c.unread ? `<span class="badge">${c.unread}</span>` : ""}</div></div></div>`;
  };
  $("convs").innerHTML = `<div class="sec">Channels</div>${chans.map(row).join("")}<div class="sec">Direct messages</div>${dms.map(row).join("") || '<div class="dim" style="padding:8px 12px">None yet – ✎ starts one</div>'}`;
  const total = all.reduce((a, c) => a + c.unread, 0);
  document.title = (total ? "(" + total + ") " : "") + (MYNAME || "Mesh") + " – chat";
}
$("convs").onclick = e => { const c = e.target.closest(".conv"); if (c) openConv(c.dataset.k); };
$("find").oninput = renderConvs;

function openConv(k) {
  cur = k; replyTo = null; $("replyChip").style.display = "none";
  $("app").classList.add("open");
  const c = conversations().find(x => x.key === k) || { key: k, title: k.startsWith("dm") ? nodeName(k.slice(2)) : k, peer: k.startsWith("dm") ? k.slice(2) : null, list: [] };
  $("hname").textContent = c.title;
  if (c.peer) { const n = node(c.peer); $("hav").outerHTML = avatar(n.short || c.title, c.peer).replace('class="av"', 'class="av" id="hav"');
    $("hsub").textContent = [n.heard ? "heard " + ago(Date.now() - n.heard) + " ago" : "", n.hops === 0 ? "direct" : n.hops > 0 ? n.hops + " hops" : "", n.rssi ? n.rssi + " dBm" : ""].filter(Boolean).join(" · ");
    showInfo(c.peer, false); }
  else { $("hav").outerHTML = '<div class="av" id="hav" style="background:#30363d;color:var(--text)">#</div>'; $("hsub").textContent = "channel " + k.slice(2) + " · everyone on it"; }
  renderThread(true); markRead(); renderConvs(); $("text").focus();
}
function markRead() { if (cur) localStorage.setItem(RK + cur, String(Date.now())); }
$("back").onclick = () => { $("app").classList.remove("open"); cur = null; renderConvs(); };

// ---------------------------------------------------------------- thread
function renderThread(scroll) {
  const box = $("thread");
  if (!cur) { box.innerHTML = '<p class="dim" style="text-align:center;margin-top:20vh">Pick a chat on the left.</p>'; return; }
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  const q = $("tfind").value.trim().toLowerCase();
  const list = [...MSG.values()].filter(m => !m.emoji && convOf(m) === cur && (!q || m.text.toLowerCase().includes(q) || (m.fromName || "").toLowerCase().includes(q))).sort((a, b) => a.time - b.time);
  const byId = new Map([...MSG.values()].map(m => [m.id, m]));
  const reacts = new Map();
  for (const m of MSG.values()) if (m.emoji && m.replyId) { const a = reacts.get(m.replyId) || []; a.push(m); reacts.set(m.replyId, a); }
  const me = [MYSHORT, MYNAME].filter(x => x && x.length > 2).map(x => x.toLowerCase());
  let html = "", lastDay = "", lastFrom = "";
  for (const m of list) {
    const day = new Date(m.time).toDateString();
    if (day !== lastDay) { html += `<div class="day"><span>${new Date(m.time).toLocaleDateString([], { weekday: "long", month: "short", day: "numeric" })}</span></div>`; lastDay = day; lastFrom = ""; }
    const mention = !m.out && me.some(x => m.text.toLowerCase().includes(x));
    const showName = !m.out && cur.startsWith("ch") && m.from !== lastFrom;
    lastFrom = m.from;
    const q2 = m.replyId && byId.get(m.replyId);
    const quote = m.replyId ? `<div class="quote" data-jump="${m.replyId}">${q2 ? `<b>${esc(q2.out ? "You" : q2.fromName)}</b> ${esc(q2.text.slice(0, 90))}` : "replying to an earlier message"}</div>` : "";
    const rs = reacts.get(m.id) || [], groups = {};
    for (const r of rs) (groups[r.text] = groups[r.text] || []).push(r.out ? "you" : r.fromName);
    const reactHtml = Object.keys(groups).length ? `<div class="reacts">${Object.entries(groups).map(([e, who]) => `<span class="react" title="${esc(who.join(", "))}">${esc(e)}${who.length > 1 ? " " + who.length : ""}</span>`).join("")}</div>` : "";
    let meta = when(m.time);
    if (m.out) {
      const st = m.status;
      meta += st === "DELIVERED" ? ` <span class="tick ok" title="delivered${m.to === "^all" ? " (a node relayed it)" : ""}">✓✓</span>` : st === "TRANSMITTED" ? ' <span class="tick" title="sent, waiting for acknowledgement">✓</span>'
        : st === "FAILED" ? ` <span class="tick bad" title="${esc(m.detail || "not acknowledged")}">⚠ failed</span> <button class="retry" data-retry="${m.id}">Retry</button>` : ' <span class="tick" title="queued">⏳</span>';
      if (m.attempt > 1) meta += ` <span title="automatic retries">try ${m.attempt}</span>`;
    } else {
      if (m.hops === 0) meta = "direct · " + meta; else if (m.hops > 0) meta = m.hops + " hop" + (m.hops > 1 ? "s" : "") + " · " + meta;
      if (m.rssi) meta = m.rssi + " dBm / " + m.snr + " dB · " + meta;
    }
    html += `<div class="row ${m.out ? "out" : ""} ${mention ? "mention" : ""}" data-id="${m.id}" data-from="${esc(m.from)}">
      <div class="acts"><button data-act="reply" title="Reply">↩</button><button data-act="react" title="React">😀</button><button data-act="copy" title="Copy">⧉</button>${m.out ? "" : `<button data-act="dm" title="Direct message">✉</button><button data-act="info" title="Node details">ⓘ</button>`}</div>
      <div class="bub">${showName ? `<div class="nm" data-node="${esc(m.from)}" style="color:hsl(${hue(m.from)},70%,70%)">${esc(m.fromName)}</div>` : ""}${quote}${esc(m.text)}${reactHtml}<div class="meta">${meta}</div></div></div>`;
  }
  box.innerHTML = html || `<p class="dim" style="text-align:center;margin-top:12vh">${q ? "Nothing matches." : "No messages yet. Say hello!"}</p>`;
  if (scroll || atBottom) box.scrollTop = box.scrollHeight;
}
$("tfind").oninput = () => renderThread(false);
$("thread").addEventListener("click", async e => {
  const row = e.target.closest(".row"), act = e.target.closest("[data-act]");
  if (e.target.closest("[data-retry]")) { const r = await api("api/retry", { id: e.target.closest("[data-retry]").dataset.retry }); toast(r.ok ? "Sending again…" : r.error); return; }
  if (e.target.closest("[data-jump]")) { const t = document.querySelector(`.row[data-id="${e.target.closest("[data-jump]").dataset.jump}"]`); if (t) { t.scrollIntoView({ block: "center" }); t.classList.add("flash"); setTimeout(() => t.classList.remove("flash"), 1300); } return; }
  if (e.target.closest("[data-node]")) { showInfo(e.target.closest("[data-node]").dataset.node, true); return; }
  if (!row) return;
  if (!act) { document.querySelectorAll(".row.touch").forEach(r => r !== row && r.classList.remove("touch")); row.classList.toggle("touch"); return; }
  const m = [...MSG.values()].find(x => String(x.id) === row.dataset.id && x.from === row.dataset.from);
  if (!m) return;
  if (act.dataset.act === "reply") { replyTo = m; $("replyText").textContent = (m.out ? "You" : m.fromName) + ": " + m.text; $("replyChip").style.display = "flex"; $("text").focus(); }
  if (act.dataset.act === "react") openEmojis(act, em => send(em, m.id, true));
  if (act.dataset.act === "copy") { navigator.clipboard?.writeText(m.text); toast("Copied"); }
  if (act.dataset.act === "dm") openConv("dm" + m.from);
  if (act.dataset.act === "info") showInfo(m.from, true);
});
$("replyX").onclick = () => { replyTo = null; $("replyChip").style.display = "none"; };

// ---------------------------------------------------------------- composer
function quick() {
  const q = JSON.parse(localStorage.getItem("mc.quick") || "null") || QUICK_DEF;
  $("quick").innerHTML = q.map(t => `<button data-q="${esc(t)}">${esc(t)}</button>`).join("") + '<button data-qedit title="Edit quick replies">✎</button>';
}
$("quick").onclick = e => {
  const b = e.target.closest("button"); if (!b) return;
  if (b.dataset.qedit !== undefined) { const cur2 = (JSON.parse(localStorage.getItem("mc.quick") || "null") || QUICK_DEF).join(" | ");
    const v = prompt("Quick replies, separated by |", cur2); if (v !== null) { localStorage.setItem("mc.quick", JSON.stringify(v.split("|").map(s => s.trim()).filter(Boolean))); quick(); } return; }
  $("text").value = b.dataset.q; countBytes(); $("text").focus();
};
function countBytes() {
  const t = $("text"), n = bytes(t.value);
  $("count").textContent = n + "/200"; $("count").classList.toggle("over", n > 200);
  t.style.height = "auto"; t.style.height = Math.min(140, t.scrollHeight) + "px";
}
$("text").addEventListener("input", countBytes);
$("text").addEventListener("keydown", e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); $("send").click(); } });
$("send").onclick = () => { const t = $("text").value.trim(); if (t) send(t, replyTo ? replyTo.id : 0, false); };
async function send(text, replyId, emoji) {
  if (!cur) { toast("Pick a chat first"); return; }
  if (bytes(text) > 200) { toast("Over 200 bytes – shorten it"); return; }
  const body = cur.startsWith("ch") ? { text, to: "^all", channel: cur.slice(2) } : { text, to: cur.slice(2), channel: 0 };
  if (replyId) body.replyTo = replyId;
  if (emoji) body.emoji = true;
  try {
    const r = await api("api/send", body);
    if (!r.ok) { toast(r.error || "not sent"); return; }
    if (!emoji) { $("text").value = ""; countBytes(); replyTo = null; $("replyChip").style.display = "none"; }
    if (!es || es.readyState !== 1) setTimeout(loadMessages, 1200);
  } catch (e) { toast(e.message); }
}
function openEmojis(anchor, pick) {
  const p = $("emojis"); p.innerHTML = EMO.map(x => `<button>${x}</button>`).join("");
  const r = anchor.getBoundingClientRect(); p.style.display = "flex"; p.style.flexWrap = "wrap"; p.style.width = "232px";
  p.style.left = Math.max(8, Math.min(innerWidth - 240, r.left - 100)) + "px"; p.style.top = Math.max(8, r.top - 110) + "px";
  p.onclick = e => { const b = e.target.closest("button"); if (b) { p.style.display = "none"; pick(b.textContent); } };
}
$("emojiBtn").onclick = e => openEmojis(e.target, em => { $("text").value += em; countBytes(); $("text").focus(); });
document.addEventListener("click", e => { if (!e.target.closest("#emojis") && !e.target.closest("[data-act=react]") && e.target.id !== "emojiBtn") $("emojis").style.display = "none"; });

// ---------------------------------------------------------------- node details
async function showInfo(id, open) {
  const n = node(id);
  $("app").classList.remove("noinfo"); if (open) $("app").classList.add("showinfo");
  const pos = n.lat !== undefined ? `<a href="https://www.openstreetmap.org/?mlat=${n.lat}&mlon=${n.lon}#map=14/${n.lat}/${n.lon}" target="_blank" rel="noopener" style="color:var(--accent)">${(+n.lat).toFixed(4)}, ${(+n.lon).toFixed(4)}</a>` : "–";
  $("info").innerHTML = `<div style="display:flex;justify-content:space-between;align-items:center">${avatar(n.short || id, id)}<button id="infoX">✕</button></div>
    <h2>${esc(n.long || id)}</h2><div class="dim">${esc(n.short || "")} · ${esc(id)}</div>
    <div class="kv"><span>Hardware</span><span>${esc(n.hw || "–")}</span><span>Role</span><span>${esc(n.role || "–")}</span>
      <span>Last heard</span><span>${n.heard ? ago(Date.now() - n.heard) + " ago" : "never"}</span><span>Hops</span><span>${n.hops === 0 ? "direct" : n.hops > 0 ? n.hops : "–"}</span>
      <span>Signal</span><span>${n.rssi ? n.rssi + " dBm / " + n.snr + " dB" : "–"}</span><span>Battery</span><span>${n.batt === undefined || n.batt < 0 ? "–" : n.batt > 100 ? "powered" : n.batt + " %"}</span>
      <span>Position</span><span>${pos}</span><span>Packets</span><span>${n.pkts ? n.pkts + " (" + n.direct + " direct)" : "–"}</span>${n.mqtt ? "<span>Via</span><span>MQTT</span>" : ""}</div>
    <div class="dim" style="font-size:12px">Signal from this node, last 7 days (RSSI)</div><svg id="spark" viewBox="0 0 280 70" preserveAspectRatio="none"></svg>
    <p style="display:flex;gap:6px;flex-wrap:wrap"><button id="iDm">✉ Message</button><button id="iTrace">Trace route</button><button id="iCopy">Copy ID</button></p><div id="trace"></div>`;
  $("infoX").onclick = () => { $("app").classList.add("noinfo"); $("app").classList.remove("showinfo"); };
  $("iDm").onclick = () => openConv("dm" + id);
  $("iCopy").onclick = () => { navigator.clipboard?.writeText(id); toast("Copied " + id); };
  $("iTrace").onclick = async () => { await api("api/traceroute", { to: id }); $("trace").style.display = "block"; $("trace").textContent = "Traceroute sent – waiting for the reply (can take a minute)…"; };
  try {
    const d = await api("api/signal?hours=168&node=" + encodeURIComponent(id)), pts = d.points;
    if (pts.length > 1) {
      const t0 = pts[0][0], t1 = pts[pts.length - 1][0], lo = Math.min(...pts.map(p => p[1])) - 2, hi = Math.max(...pts.map(p => p[1])) + 2;
      const x = t => (t - t0) / Math.max(1, t1 - t0) * 276 + 2, y = v => 68 - (v - lo) / Math.max(1, hi - lo) * 64;
      $("spark").innerHTML = pts.map(p => `<circle cx="${x(p[0]).toFixed(1)}" cy="${y(p[1]).toFixed(1)}" r="1.6" fill="#58a6ff"/>`).join("") +
        `<text x="4" y="11" fill="#8b949e" font-size="9">${hi - 2} dBm</text><text x="4" y="66" fill="#8b949e" font-size="9">${lo + 2}</text>`;
    } else $("spark").innerHTML = '<text x="8" y="38" fill="#8b949e" font-size="11">not enough packets yet</text>';
  } catch (e) { }
}
$("infoBtn").onclick = () => { if (cur && cur.startsWith("dm")) showInfo(cur.slice(2), true); else toast("Tap a name in the chat to see that node"); };

// ---------------------------------------------------------------- new DM picker
$("newDm").onclick = () => { $("modal").style.display = "flex"; $("pickFind").value = ""; renderPick(); $("pickFind").focus(); };
$("pickX").onclick = () => { $("modal").style.display = "none"; };
$("pickFind").oninput = renderPick;
function renderPick() {
  const q = $("pickFind").value.trim().toLowerCase(), now = Date.now();
  const list = [...NODES.values()].filter(n => !n.me && n.heard && (!q || (n.long + " " + n.short + " " + n.id).toLowerCase().includes(q))).sort((a, b) => b.heard - a.heard).slice(0, 80);
  $("pick").innerHTML = list.map(n => `<div class="conv" data-pick="${esc(n.id)}">${avatar(n.short, n.id)}<div class="mid"><div class="t"><b>${esc(n.long || n.id)}</b><span>${ago(now - n.heard)}</span></div>
    <div class="p"><span>${esc(n.short)} · ${esc(n.id)}${n.hops === 0 ? " · direct" : n.hops > 0 ? " · " + n.hops + " hops" : ""}</span></div></div></div>`).join("") || '<p class="dim">No match.</p>';
}
$("pick").onclick = e => { const p = e.target.closest("[data-pick]"); if (p) { $("modal").style.display = "none"; openConv("dm" + p.dataset.pick); } };

// ---------------------------------------------------------------- data + live updates
async function loadMessages() {
  const d = await api("api/messages");
  MY = d.my; CH = d.channels.length ? d.channels : [{ index: 0, name: "Primary" }];
  for (const m of d.messages) MSG.set(mkey(m), m);
  if (!localStorage.getItem("mc.seen." + location.port)) {   // first visit: older history doesn't count as unread
    for (const c of conversations()) localStorage.setItem(RK + c.key, String(Date.now()));
    localStorage.setItem("mc.seen." + location.port, "1");
  }
  renderConvs(); renderThread(false);
}
async function loadNodes() { const d = await api("api/nodes"); NODES = new Map(d.nodes.map(n => [n.id, n])); const me = d.nodes.find(n => n.me); if (me) { MYSHORT = me.short; } }
async function loadStatus() {
  statusS = await api("api/status");
  MYNAME = statusS.name || ""; $("title").textContent = (MYNAME || "Mesh") + " – chat";
  $("dot").classList.toggle("ok", statusS.connected);
  $("radio").textContent = statusS.connected ? statusS.heardHour + " nodes heard this hour · channel " + (+statusS.chUtil).toFixed(1) + " %" : "radio not connected";
}
function notifyIfNeeded(m) {
  if (m.out || m.emoji || !document.hidden) return;
  const k = convOf(m), mention = [MYSHORT, MYNAME].some(x => x && x.length > 2 && m.text.toLowerCase().includes(x.toLowerCase()));
  if (!k.startsWith("dm") && !mention) return;
  if ("Notification" in window && Notification.permission === "granted") new Notification(m.fromName + (k.startsWith("dm") ? "" : " in #" + (CH.find(c => c.index === m.channel) || {}).name), { body: m.text, tag: k });
}
function live() {
  if (es) es.close();
  es = new EventSource("api/events?session=" + session);
  es.onopen = () => { $("live").textContent = "live"; $("live").style.color = "var(--ok)"; };
  es.onerror = () => { $("live").textContent = "reconnecting…"; $("live").style.color = "var(--warn)"; };
  es.addEventListener("message", e => {
    const m = JSON.parse(e.data), isNew = !MSG.has(mkey(m));
    MSG.set(mkey(m), m);
    if (isNew) notifyIfNeeded(m);
    if (cur === convOf(m) && !document.hidden) markRead();
    renderConvs(); if (cur) renderThread(false);
  });
  es.addEventListener("nodes", () => loadNodes().then(renderConvs));
  es.addEventListener("status", () => loadStatus());
  es.addEventListener("trace", e => { const t = JSON.parse(e.data); toast("Traceroute to " + nodeName(t.node) + " answered");
    if ($("trace")) { $("trace").style.display = "block"; $("trace").textContent = t.text; } });
}
document.addEventListener("visibilitychange", () => { if (!document.hidden) { markRead(); renderConvs(); } });
async function start() {
  try {
    await loadStatus(); await loadNodes(); await loadMessages();
    session = (await api("api/session", {})).session; live();
    quick(); renderThread(false);
    if ("Notification" in window && Notification.permission === "default") setTimeout(() => Notification.requestPermission(), 3000);
    const k = new URLSearchParams(location.search).get("c"); if (k) openConv(k);
    setInterval(() => { if (!es || es.readyState === 2) { loadMessages(); live(); } }, 30000);
    setInterval(loadStatus, 30000);
  } catch (e) { if (e.message !== "password") toast("Mesh Console isn't answering"); }
}
$("pwGo").onclick = async () => { pass = $("pw").value.trim(); localStorage.setItem(KEY, pass);
  try { await api("api/status"); $("login").hidden = true; start(); } catch (e) { $("pwNote").textContent = "Wrong password."; } };
$("pw").onkeydown = e => { if (e.key === "Enter") $("pwGo").click(); };
if (!pass) $("login").hidden = false; else start();
</script>
</body>
</html>
""";
}
