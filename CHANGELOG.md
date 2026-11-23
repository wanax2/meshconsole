# Changelog

## 1.29.4 — 2026-11-22
Ideas taken from NodeMonitor (github.com/swiings/NodeMonitor).
- **Ask unnamed nodes for their name.** Nodes tab → "Ask unnamed nodes for their name". Once a minute it sends a
  node-info request to the most recently heard node that still has no name – heard over the air in the last hour,
  within the hop limit, each asked at most once per session – and pauses while the channel is more than 20% busy.
  Sent without a delivery ack to save airtime. Off by default and remembered per data folder; turn it on for one
  radio, not every window. The line next to it says what it last did.
- **Packets per minute** (Traffic tab, and the web console's Signal tab): stacked bars by kind – text, position, node
  info, telemetry, routing, traceroute, neighbours, encrypted, other – for the last 1, 3 or 6 hours, with the radio's
  channel-busy reading as a line. Hover a bar for that minute's breakdown.
- **Hop usage** (same places): packets heard from other nodes, by the hops they were sent with (rows) against the hops
  they used to reach this radio (columns), with a plain-words summary – how many used every hop, how many hops were
  left unused on average, and how many were sent with more than 3 hops and needed them. Evidence for choosing a hop
  limit. Both reset with the Traffic tab's "Reset counters".
- **Map filters** (desktop map and web map): hops away (direct only, up to 1/2/3), heard within (1 h … 7 days), and
  where the position came from – the radio's own GPS, a phone or computer, typed in by hand, or unknown – so hand-typed
  positions can be hidden. Clicking a node says where its position came from and its altitude in feet.
- **Marker shapes by kind:** router ▲, base ■, tracker ◆, moving (positions more than 150 m apart within the hour) as
  a pentagon, airborne (above 20,000 ft) ✈, mute ○, everything else ●; colour still says how recently it was heard.
  The web map uses 🏠 📡 🐾 🚗 ✈️ 🔇 🌡️ icons.
- **Outline & area:** a line around the nodes shown, with the area it encloses in square miles and km² – one number
  to compare before and after an antenna change.
- **Town names:** the nearest town for each node with a position ("Aurora, NY"), from OpenStreetMap's Nominatim
  service (one lookup every 3 s at most, cached on a ~2 km grid in towns.csv, nodes heard in the last week only).
  Shown when you click a node on either map, and saved in nodes.json.

## 1.29.3 — 2026-11-22
- **Weather reply (`W`, `WX`, `WEATHER`) in plain words.** Before: `KDCA 3:52 PM: 9°C/48°F RH 80% wind 4 kt 1013 hPa`.
  Now the station's name and time, then °F first (°C in brackets) and "feels like" when wind chill or heat index
  differs by 2° or more; sky cover from the METAR (clear, a few clouds, partly cloudy, mostly cloudy, overcast) with
  the cloud base when it's under 10,000 ft and "storm clouds" for CB/TCU; rain, snow, drizzle, fog, mist, haze,
  thunderstorms etc. in words ("light rain, mist", "showers nearby"); wind in mph with its compass direction and
  gusts ("Calm" when there's none); visibility when under 10 mi; humidity; pressure in inHg with the 3-hour trend
  (rising, falling, steady, fast). Kept to one packet: humidity, then the pressure figure, are dropped if it would
  pass 200 bytes. An observation more than 90 minutes old says how old it is.
- A second message follows with the next two NWS forecast periods: "Tonight: Mostly cloudy, low 41, precip 30%.
  Monday: Chance light rain, high 55, precip 60%." (left out if the forecast can't be fetched).
- The daily `SUB WX` digest uses the same plain-words line.

## 1.29.2 — 2026-11-16
- **MQTT proxy reconnects by itself.** The proxy used to start once per radio connection; if the broker connection
  then dropped ("Connection timed out" on weak WiFi) or the broker wasn't reachable at start, it stayed down until
  Mesh Console was next restarted, so a USB radio silently fell off MQTT for days. Now a stopped proxy is started again
  every minute for as long as it is meant to be on and the radio has MQTT and proxy-to-client enabled. The log says
  when it reconnects, and notes every 30 minutes while the broker can't be reached. Stopping it with the button still
  keeps it stopped.

## 1.29.1 — 2026-11-16
- **Web console: the "To" box no longer falls back to the public channel.** Picking someone for a first direct message
  (by clicking their name) added them to the list, but the 10-second refresh rebuilt the list from the DM history and
  dropped them, so the box silently went back to "To #0 Primary" while you were typing. The chosen person now stays
  selected until you change it.
- **Chat page: "✉ Message directly" is labelled**, with a tooltip naming the person and saying it is private. On a
  computer the action bar now sits beside the message instead of on top of it, so it no longer covers the sender's
  name. (Phones keep the compact icons.)
- **Dashboard allow-list is a setting.** The password-free peer feed (`/api/peer/…`) is readable from this computer and
  from the hosts listed in `dashboard-hosts.txt` in the data folder – one name or IP address per line or
  comma-separated, `#` for comments. Re-read within a minute, no restart needed. Without the file the previous
  built-in list applies.

## 1.29.0 — 2026-11-12
- **Rotating bulletins.** BBS tab: a list of sysop bulletins (separated by a line of `---`), posted to the board
  one at a time at the times you set (e.g. `08:00, 18:00`). **Shuffle** posts every bulletin once per cycle in random
  order (reshuffled each cycle and whenever the list changes, never the same one twice in a row); off = in order.
  **Replace the previous one** keeps just the latest rotating bulletin on the board, so it doesn't fill up; users'
  own posts are never touched. **Post next bulletin now** for an immediate one. Posted as the board's name, so they
  show in `B` and read with `R` like any bulletin. Each is kept within 170 bytes so `R n` fits one packet.
- Sysop by DM: `X BULLETIN` posts the next rotating bulletin.

## 1.28.4 — 2026-11-11
- **Easier replies and reactions in both web chats.**
  - Chat (Meshtastic): tap a message → one-tap reactions (your four most-used emoji first) plus ➕ for the rest;
    double-tap (phone) or double-click (computer) reacts with your favourite; tap someone's reaction to add the same
    one; your own reactions are highlighted; no accidental double reactions.
  - MeshCore page: rebuilt chat like the Meshtastic one – conversation list with unread badges, last message and
    search; bubbles with day dividers, sender colours, hops/SNR, delivery status; Reply (channel replies start with
    @[name], the MeshCore mention format), Copy, Reply privately; the same reactions (sent as "👍 @[name]" in
    channels, plain emoji in DMs, shown as chips under the message they answer); quick replies; emoji picker; byte
    counter that knows the channel/DM limits; "(3) MeshCore" unread count in the tab title and a phone/desktop
    notification while the page is in the background; phone layout (list / conversation with a back button).
- MeshCore link watchdog: a USB radio that resets or disappears is noticed at once, and a radio that goes silent for
  3 minutes is asked for its battery level – no answer twice and Mesh Console reconnects. (Overnight on Nov 10–11 the
  G3 went quiet at 00:13 and the link never recovered.)

## 1.28.3 — 2026-11-11
- **MeshCore radio settings in the desktop window** (MeshCore tab → Radio): name, TX power, position (with "include
  position in adverts"), radio settings (frequency, bandwidth, SF, CR), flood/zero-hop advert and reboot. Each is saved
  on the radio and read back, so what's shown is what the radio has. TX power on a Station G2/G3 asks before going
  above MeshCore's G3 default of 7 dBm (the number drives the amplifier); a positive US longitude offers the fix.

## 1.28.2 — 2026-11-10
- MeshCore over USB: stays connected. A quiet serial port reports a read "timeout" every 250 ms; 1.28.0/1.28.1 took
  that for a lost link and dropped the radio a second after connecting (reconnecting every 20 s). Now it just keeps
  listening. (WiFi/TCP connections weren't affected.)

## 1.28.1 — 2026-11-10
- **MeshCore tab in the desktop window** (last tab, "MeshCore"). Connecting no longer needs the browser: plug the MeshCore
  radio in by USB, pick it from the list (Station G3/G2 radios are named; on Linux the list uses the /dev/serial/by-id
  names, which never change), click **Connect**. WiFi radios: type tcp:address:5000. Inside: Chat (channels and DMs,
  delivery confirmations), Contacts, Signal (every packet heard) and Radio (details, flood/zero-hop adverts).
  Same radio and data as the web console's /meshcore page – connecting in one shows in the other.

## 1.28.1 — 2026-11-11
- MeshCore Radio tab: **Set position** – type in latitude/longitude (no GPS needed; the phone app only offers GPS),
  and choose whether adverts include it.

## 1.28.0 — 2026-11-10
- **MeshCore** (first stage): a MeshCore radio running Companion firmware alongside the Meshtastic one, in the web
  console at **/meshcore** (📡 MeshCore link at the top of the main page). Connects over WiFi (`tcp:<address>:5000`)
  or USB (`serial:/dev/ttyACM1`), set on its Radio tab (saved in `meshcore.properties`); reconnects on its own.
  - Chat: channels and direct messages, send and receive, delivery confirmation with round-trip time for DMs,
    room-server posts show who wrote them.
  - Contacts: everything the radio knows (chat nodes, repeaters, rooms, sensors), last heard, hops, direct-signal
    RSSI/SNR averages, position.
  - Signal: every packet the radio hears (its SNR/RSSI, type, hops, last repeater), 24-hour chart, kept in
    `meshcore_signal.csv`.
  - Radio: firmware, board, frequency/bandwidth/SF, TX power setting, position (warns about a positive US
    longitude), battery, public key; flood and zero-hop adverts.
  - Kept in the data folder: `meshcore_messages.log`, `meshcore_contacts.tsv`, `meshcore_signal.csv`.
  Protocol checked against MeshCore's companion_radio source (frames `<`/`>` + 16-bit length, same over USB and TCP).

## 1.27.0 — 2026-11-09

- BBS: new **ASK** command (alias AI). Mesh users send `ASK <question>` and get a one-message answer (≤200 bytes)
  from a local AI model (Ollama). Servers are tried best first with automatic fallback, e.g. a GPU machine, then a
  Pi with a small model; a server that fails is rested for 2 minutes. Limited per person per hour; runs on its own
  thread so slow answers never delay other BBS replies; listed in the ? menu only when switched on.
  Switched on by `ai.properties` in the data folder (enabled, servers=url|model,…, perHour, name) – no restart needed.
- Linux/Pi launcher: Java memory is now a share of the machine's RAM (10 %) instead of a fixed size
  (override with MC_JAVA_OPTS).

## 1.26.5 — 2026-11-08

- WiFi radios (TCP): a radio that loses power doesn't close the connection, it just goes quiet – so Mesh Console
  could sit "connected" to nothing. Now 10 minutes of total silence on a TCP link counts as lost, and the
  auto-reconnect takes over.
- Auto-reconnect to a WiFi radio keeps trying for as long as it takes (every 2 s at first, then every 30 s),
  instead of giving up after 30 minutes.


## 1.26.4 — 2026-11-08

- WiFi radios (TCP): if a radio connects but sends no settings within 90 s – typically because it is still holding
  the previous connection after Mesh Console restarted for an overnight update – Mesh Console now drops the
  connection and tries again (every 2 minutes until the radio answers), instead of waiting forever at
  "config still loading". This is what left both Mesh Console windows without their radios for 7 hours on Nov 8.
- Self-update: waits 3 s after closing the radio connection before restarting, so the radio sees it close.


## 1.26.3 — 2026-11-07

- Web console: the message list no longer shows through on the other tabs (Heard by, BBS, Settings…).
- Heard by: the map frames your node and its gateways properly when the tab opens; the private server defaults to
  localhost; the example text in empty boxes is clearly marked as an example.


## 1.26.2 — 2026-11-07

- **Heard by** is now also a tab in the desktop window (next to MQTT witness): the same settings, gateway table, daily
  counts and status as the web console's tab – both share one background service per window. "Open map in browser"
  jumps straight to the web console's Heard by map.


## 1.26.1 — 2026-11-07

- MQTT client proxy (radios without WiFi, e.g. the lake G3 on USB) now follows the radio's settings: when its MQTT
  server, user, password or proxy switch change, the proxy reconnects to the new server by itself (or stops if proxy
  was switched off) – no restart of Mesh Console or the Pi needed.


## 1.26.0 — 2026-11-07

- **Heard by** (web console tab): runs in the background, listening to the public Meshtastic MQTT server and/or
  your own private one, and records every time another station's gateway hears one of your nodes – which gateway,
  how far away, how many hops, the signal at that gateway when it heard you directly. Map (green = heard you
  directly, amber = via relays), table per gateway (24 h / 7 days, fewest hops, best SNR/RSSI) and gateways per day.
  Track the other house, the G2 and the T-Beam too (comma-separated node IDs). Works for encrypted channels as well
  (sender and gateway are outside the encryption). Gateway names/positions are learned from their own position,
  node-info and map-report packets. Log in heard_by.csv; 30 days shown.


## 1.25.0 — 2026-11-04

- **Weekly check-in net** (Settings → BBS extras: day and time): announced on the public channel 30 min before,
  callers DM **CHECKIN** [comment] until 60 min after and get their signal back; then a roll call is broadcast
  (direct neighbours starred) and posted as a bulletin with signal reports and comments. net_log.csv keeps every check-in.
- **SUB METRO ORANGE** (any line, or RD/OR/BL/SV/GR/YL): a DM whenever WMATA posts a disruption on that line.
- **SAT**: next ISS, NOAA 18/19 and SO-50 passes over the caller (or the board) – needs a free N2YO key (BBS extras).
- **MAP**: the caller's position as a map link, with distance and bearing to the board.
- **Welcome** for first-time callers (editable in BBS extras), added to their first reply.
- **WORD**: a daily Wordle-style word game, six guesses, 🟩🟨⬜ feedback; a new word every day.
- **Which adverts bring callers**: every advert and caller is now logged, and the web console's BBS tab shows, for each
  advert, how many callers (and first-timers) turned up within two hours of it – so the rotation can be pared down to
  what works.


## 1.24.1 — 2026-11-04

- This is a Buffalo board: the DC-team shortcuts (CAPS, NATS, HTTR, DCSPORTS) and the DC line in the menu are gone.
  SCORE is Bills and Sabres; SCORE DC still answers if someone asks, but isn't advertised.


## 1.24.0 — 2026-11-04

- **Discord bridge** (web console → Settings): one mesh channel ↔ one Discord channel. Mesh → Discord through a webhook,
  each message under the sender's node name with hops and signal in small print. Discord → mesh with a bot token:
  messages go out as "[D] name: text" (cut to 200 bytes), at most N an hour (default 10) and one a minute per person,
  never from bots or webhooks (no loops); anything held back gets a short note in Discord. Status and counts shown.
- BBS **BIKE [station]**: Capital Bikeshare bikes, e-bikes and free docks – matching stations, or the three nearest the
  caller (if their node shares its position) or the board.
- BBS **AQI**: current US air-quality index, PM2.5 and ozone for the board's location (Open-Meteo, no key).
- BBS **SCORE DC** (also DCSPORTS, HTTR, CAPS, NATS): today's Commanders, Capitals, Wizards, Nationals, D.C. United
  and Mystics games – only the teams playing.


## 1.23.0 — 2026-11-04

- BBS **RANGE** (also TEST, HEARD; and PING now answers with it): how well this station hears you – direct or how many
  hops, distance and direction, the latest signal and how much margin it has above what LongFast can still decode
  (excellent / good / fair / marginal), your last 24 h (packets, how many direct, best signal), and your place among
  the nodes heard directly. Example: "Lyon's Den hears you DIRECT, 3.2 km NE, -113 dBm / -4.5 dB = good (13 dB above
  the limit). 24h: 18 pkts, 14 direct, best -103 dBm. #4 of 5 direct."
- BBS **ME**: your history with this station – heard since when, packets and % direct, average signal, today against
  your week (better or worse), 7-day packet count, BBS commands, battery.
- Both listed in the menu under the mesh commands.


## 1.22.0 — 2026-11-02

- **Chat** (http://<computer>:3500/chat, also linked from the web console): a proper messaging page.
  Conversations on the left – every channel and every DM, newest first, unread counts, previews, search, ✎ to start
  a DM with any node heard. The thread: day separators, sender names in their own colours, **replies** (quoted, tap
  to jump to the original), **emoji reactions** (shown under the message, who reacted on hover), delivery ticks
  (⏳ queued, ✓ sent, ✓✓ acknowledged, ⚠ failed with **Retry**), hops and signal on every received message, your
  name highlighted when mentioned, search within a chat. Composer with a live 200-byte counter, Enter to send
  (Shift+Enter for a new line), emoji picker and editable quick replies. Node details on the right: hardware, role,
  last heard, hops, signal, battery, position, a 7-day signal plot, Trace route (the answer appears there) and Copy ID.
  **Live**: new messages, delivery receipts and traceroute answers arrive instantly (server-sent events), browser
  notifications for DMs and mentions while the tab is in the background, unread count in the tab title.
  Works on phones (list ↔ thread).
- Web console messages carry reply / reaction / delivery details; the old Messages tab hides reactions.


## 1.21.0 — 2026-11-02

- Web console → **Settings**: owner, role, LoRa (region, preset, hops, RX boost, OK to MQTT), fixed position, WiFi,
  channels (name, key, uplink/downlink – and **copy channels from the other window** on the same computer), MQTT.
- **Mesh alerts** (Settings tab, ntfy + e-mail): watched nodes not heard for N hours (and back again), new node heard
  directly, first-time BBS callers (any check-in by a watched node), radio not connected for 10 minutes.
- Read-only **peer feed** (/api/peer/summary, nodes, public messages) for the PiHealth dashboard's both-houses page and
  daily digest – answers only this computer and the dashboard's Pi, without the password.
- Desktop windows no longer stay blank grey on the Pi's desktop after an update restart (or via meshconsole.sh).


## 1.20.2 — 2026-11-02

- Web console → Download logs: now a normal browser download (with the browser's own progress and save prompt) via a
  one-use link, instead of being assembled inside the page first – which could look stuck on large data folders.


## 1.20.1 — 2026-11-02

- Updating from the web console (or Help → Updates) no longer leaves the window closed on Linux: the restarted copy is
  fully detached (survives the old process and any SSH session it came from), gets the desktop session settings it
  needs, is started the same way as the autostart entries (so watchdogs recognise it), and writes what happened to
  restart.log in the data folder.


## 1.20.0 — 2026-11-02

- Web console → **Signal** tab: received-signal charts over 6 h to 14 days – median RSSI and SNR with packet counts
  per time slot, or every packet from one chosen node (the 60 most-heard are listed) – plus channel utilisation and
  this radio's air-time.
- Web console → Status: **Download logs (zip)** – the whole data folder (like the app's export, without the web
  password) straight to the device you're browsing from.


## 1.19.2 — 2026-11-02

- Web console: the password box now goes away once the password is accepted (its own styling kept it on screen).


## 1.19.1 — 2026-11-01

- Web console → Status: **Check for update now** (runs Mesh Console's updater from the browser, using the source set
  in Help → Updates…; if a newer version is found it installs and the window restarts), plus the update source and
  schedule at a glance.


## 1.19.0 — 2026-11-01

- **Web console** (replaces the 1.18 web map, same address: `http://<computer>:3500`, 3501 for a second window):
  Messages (all channels and DMs, send to a channel or DM anyone – click a sender's name), Map (nodes heard in the
  last 24 h, lines to direct neighbours), Nodes (search, DM, traceroute), BBS (posts and mail), Status (radio, channel
  use, nodes heard, uptime, BBS). Password-protected: the password is made up on first start, shown in the log and kept
  in `web-password.txt` in the data folder (edit it to choose your own). Refreshes every 10 s; works on phones.


## 1.18.0 — 2026-11-01

- **Web map**: each window serves a live, read-only map of every node it knows at `http://<computer>:3500`
  (a second window on the same computer gets 3501, and so on – remembered per data folder; the address is in the
  log at start-up). Colours by how recently heard (green = heard directly, with optional lines to them), a
  searchable list beside the map, filters for age and MQTT-only nodes, refreshes every 30 s. Works on phones too –
  open it over Tailscale instead of screen-sharing the Pi.
- BBS tab: the Usage box can no longer be squeezed until its title and summary line overlap.


## 1.17.1 — 2026-11-01

- BBS scanner replies say where each call is from (ARL, ALX, DC, CAY …), and take an area: `SCAN ALX`,
  `FIRE ARL`, `INC ALX 12`. Ready for more systems (e.g. DC) as they're added to the ScannerDeck.


## 1.17.0 — 2026-11-01

- BBS scanner commands, from a ScannerDeck page (BBS tab → Scanner: e.g. `http://scanner.local:3100`):
  `SCAN` newest transcribed calls, `FIRE` / `POLICE` / `EMS` the same by service, `INC [hours]` recent incidents
  (places named in transcripts), and `SUB SCAN` – new fire and crash incidents sent to subscribers, at most one
  message each every 15 minutes. Everything else is pulled on request, so the channel isn't flooded.


## 1.16.4 — 2026-10-31

- Nodes tab: **Find** box – type part of a name, a node ID (with or without "!", e.g. 33357952), hardware or role, and the list narrows as you type.


## 1.16.3 — 2026-10-31

- BBS adverts: "Advertise daily at" takes several times, e.g. `08:00, 17:30` – each time sends the next advert in the rotation (or shuffle). After a restart, missed times send a single advert rather than a burst.
- The window title shows the data folder ("Mesh Console 1.16.3 – mesh_data"), so two windows on one machine are easy to tell apart.


## 1.16.2 — 2026-10-30

- Start-up auto-connect no longer gives up: if the last radio isn't there when the app opens (a USB radio can appear a few seconds after boot), it keeps looking every 10 s until it appears or you connect by hand.
- It also finds the same radio by its USB identity (vendor, product, serial number) if its port name changed – ttyACM0 → ttyACM1 – and remembers that identity on every connect.
- A Raspberry Pi's built-in serial ports (ttyAMA*, ttyS*) are never picked as the default port any more; ESP32-S3 boards ("USB JTAG/serial debug unit", e.g. Station G2/G3) and common USB-serial chips are preferred.


## 1.16.1 — 2026-10-30

- Messages: the **Channel** picker and the channel column show names ("1 · Family", "2 · Friends", "0 · primary") instead of bare numbers; direct messages show "DM".


## 1.16.0 — 2026-10-29

- Map → Nearby on MQTT: choose the **source** next to the box. **Map reports** – nodes that opted in to the public map (few, each about hourly); **Channel positions** – any node uplinking position packets on the default channel (LongFast, or your preset's channel), decrypted with the public default key, with names from their node-info packets – the same feed meshmap.net largely draws from, so many more nodes; **Map reports + channel** – both. Click details and the Nearby list show how each node was seen. Still this computer's internet only; nothing goes through the radio.


## 1.15.3 — 2026-10-29

- Fix: toolbars wider than the window (the Map tab's in particular, on a 1280-wide Pi screen) hid their right-hand controls – including the new **Nearby on MQTT** box, its radius and **Nearby list…**. Every left-aligned toolbar now wraps onto a second line instead.


## 1.15.2 — 2026-10-29

- Map: **− / +** zoom buttons at the left of the toolbar, and the **-** / **+** keys once the map has been clicked – for remote desktops, touch screens and touchpads without a scroll wheel.


## 1.15.1 — 2026-10-29

- Fix: "Save and check now" could leave the window frozen. Its result ("Up to date", "Updating…", an error) was a modal message box, and on the Pi's desktop it could open behind the main window or off-screen, blocking every click while invisible. The result now goes to the log and to a non-modal box that stays on top, centred on the screen.
- The main window now fits the usable screen when it opens (a headless Pi under screen sharing may run at 1280×720), so its title bar is always reachable.


## 1.15.0 — 2026-10-29

- Map: **Nearby on MQTT** layer. Mesh Console listens to the public server's map reports (root/2/map/) on this computer's internet connection – nothing goes to or through the radio – and draws the nodes within a radius you choose as grey squares (filled = your radio has heard it), with the reported precision area at street zoom. Click one for its name, preset, role, distance and bearing from you, last report, and whether your radio has heard it. **Nearby list…** shows them in a table, nearest first; double-click to centre the map. On/off and radius are remembered per data folder; reports are kept for 7 days in mqtt_map_reports.json so the layer fills straight away after a restart. Uses the radio's MQTT server settings when loaded, else the public defaults.


## 1.14.7 — 2026-10-29

- Fix: the MQTT client proxy subscribed to the whole region (`msh/US/2/e/#`) and pushed every packet on the public server down the USB link into the radio, which couldn't keep up ("Invalid MQTT service envelope" / "Can't decode protobuf" errors, many per second). It now subscribes only to channels with **downlink** enabled, as the firmware itself does; with no downlink channel the proxy is uplink-only (map reports and your own packets go up, nothing comes down).


## 1.14.6 — 2026-10-29

- Settings → Channels: the **Uplink** and **Downlink** tick boxes now come right after the name, before the long PSK column, and the role/name columns are capped — on a narrow screen (a Pi over screen sharing) the uplink boxes were pushed out of view.


## 1.14.5 — 2026-10-29

- Fix: the Settings → Common page kept showing values from before a change made in All settings (e.g. "MQTT module enabled" unticked after enabling it there), and saving that section from Common could switch the setting back. Common now re-reads the radio's settings after every reconnect, including the reboot that follows a write.


## 1.14.4 — 2026-10-29

- MQTT client proxy remembers that it was started (per data folder) and starts itself again a few seconds after the radio's configuration loads — so an unattended station keeps uplinking (map reports, witness) through reboots, reconnects and self-updates. Stopping it with the button turns the auto-start off.


## 1.14.3 — 2026-10-28

- Fix: rows of controls (BBS, Alerts, Analysis, Weather, Schedule, radio tab and others) were clipped when the window was narrower than the row — the overflow wrapped onto a second line that had no height, so buttons such as "Board profile…" were cut off. Rows now grow to fit when they wrap.


## 1.14.2 — 2026-10-28

- Map: **Go to** box. Type an address or place ("Main St, Aurora NY") or coordinates ("42.75, -76.70"), press Enter, and the map jumps there at street level with a red marker; then right-click the roof → Set as my fixed position. Addresses are looked up with OpenStreetMap's Nominatim (free, no key).


## 1.14.1 — 2026-10-27

- BBS adverts: **Shuffle** option. Each advert goes out once per cycle in random order (dealt like cards), reshuffled at the end of each cycle and whenever the advert list changes; never the same advert twice in a row; the position survives restarts and updates. Off = the existing top-to-bottom rotation.


## 1.14.0 — 2026-10-27

- **Board profile** (BBS tab → Board profile…), saved per data folder so two boards on one program can differ:
  - **Command groups** on/off: community, mesh tools, weather, aviation, ham, transit (WMATA), games, Buffalo. Switched-off commands answer "Unknown" and vanish from `?` and `?2`, which are now built from what the board offers.
  - **Trivia categories** on/off: mesh & radio, ham, computing history, nerd, DC & Arlington, Buffalo & WNY, and a new **Finger Lakes & upstate NY** set (18 questions: Cayuga and Seneca lakes, Taughannock Falls, Seneca Falls 1848, Tubman and Seward in Auburn, Henry Wells, Cornell, Montezuma, Glenn Curtiss, Watkins Glen, Corning, Elmira, Syracuse, Rochester, Owasco). The board's own trivia.txt is always used.
  - **Region presets**: DC / Arlington, Finger Lakes / upstate NY (no transit, no DC trivia; airports KITH/KSYR, CAT list KITH KSYR KROC KELM KBGM KPEO, weather KITH), and Universal (no local features).
- Built-in trivia bank: 159 questions.


## 1.13.2 — 2026-10-26

- BBS: send just an airport code — `KBUF`, `BUF`, `EGLL` — and the board replies with its METAR (any ICAO station worldwide). Real commands always take precedence; a code with no METAR gets the usual "Unknown" reply. `AV` help updated.


## 1.13.1 — 2026-10-26

- BBS **DELAY [ids | ALL]** — FAA airport status: ground stops, ground delay programs, arrival/departure delays and closures for the CAT airports (default DCA, HEF, IAD, BWI, JYO, ADW) or any you name; `DELAY ALL` for the national picture. Source: nasstatus.faa.gov, no key.
- BBS **PLANES [km]** — aircraft within 10 km of the board (2–50 km with an argument), nearest first: callsign, altitude, heading, speed, distance and direction. Source: OpenSky Network (anonymous), cached 30 s.


## 1.13.0 — 2026-10-26

BBS aviation weather (aviationweather.gov and api.weather.gov, no keys), defaults set on the BBS tab ("Aviation: KDCA,KHEF"):
- **METAR [id …]** — raw METAR without remarks, plus flight category and density altitude; with no id, one line for each default airport. Three-letter ids work (`METAR HEF`).
- **TAF [id]** — now takes an airport (default: the first aviation airport).
- **CAT [ids]** — flight categories at a glance for a list of airports (default KDCA, KHEF, KIAD, KBWI, KJYO, KADW): category, ceiling, visibility, wind.
- **DA [id]** — density altitude with the working (field elevation, temperature, altimeter, pressure altitude).
- **WINDS [site]** — winds and temperatures aloft, 3,000–24,000 ft, decoded (direction/speed, temperature), from the nearest FB forecast point.
- **PIREP [id]** — pilot reports within 100 nm in the last 3 hours.
- **FCST** — the next three NWS forecast periods for the board's position.
- **AV** lists them all; the main menu shows `AV`.
Responses are cached for five minutes.


## 1.12.4 — 2026-10-25

- Alerts tab settings (alerts on/off, desktop/tray notifications, beep, DM alerts, silent-node hours, favourites only, battery and utilisation thresholds, push out) are saved per data folder and restored at start-up — previously they reset to the defaults on every restart, including after a self-update.


## 1.12.3 — 2026-10-25

- Self-update restarts keep the Java options the app was started with (e.g. `-Xmx768m`), not just its arguments, on Windows and Linux.


## 1.12.2 — 2026-10-25

- `pi/install-pi.sh` installs the full Java runtime (`openjdk-21-jre`) instead of the headless one, so the GUI starts on a Pi desktop. Headless services work with it too.


## 1.12.1 — 2026-10-25

- Self-update now installs on **Windows** too: the new version is staged in `update-pending\`, the app exits, and a small helper copies the files in as soon as no instance is holding them (each instance does the same at its check time), then relaunches with the same data folder. If the files stay locked for ten minutes it starts the old version again.


## 1.12.0 — 2026-10-25

- **Self-update** (Help → Updates…, or headless `--auto-update <source>`): a daily check (default 04:15) for a newer `meshconsole-x.y.z-ready-to-run.zip`, either in a folder — a NAS share mounted on the machine — or in a GitHub repository's latest release (`owner/repo`). On Linux and macOS the jars are replaced in place and the app restarts itself onto the new version with the same arguments (under systemd it exits and systemd restarts it); a second instance sharing the install folder restarts onto the new jars at its next check. On Windows, where running jars are locked, it reports that an update is waiting. "Save and check now" runs a check immediately.
- `pi/install-pi.sh` gives your user ownership of `/opt/meshconsole` so updates can be written.


## 1.11.4 — 2026-10-25

- Headless: `--export HH:MM` (nightly zip of all logs), `--export-dir <folder>`, `--label <name>` (used in the file name).
- `pi/`: a systemd template (`meshconsole@.service`), one settings file per radio (`/etc/default/meshconsole-dc`, `-ne`) and `install-pi.sh`, so a Raspberry Pi runs both radios as services that start at boot and restart on failure.


## 1.11.3 — 2026-10-24

- BBS: `GAMES` lists the games; `COMMANDS`, `COMMAND`, `START`, `HI` and `HELLO` all give the menu, like `?`. (The DC mesh maintainer typed "Games" and "Commands" and got "Unknown" — twice.)


## 1.11.2 — 2026-10-25

- Fix: the radio tab treated tx_power **0** (the Meshtastic default) as the lowest setting. 0 means the firmware default, which is the maximum: on a stock G2/G3 that's the chip cap of 19 (about 1.6 W with factory jumpers), on the T-Beam 1W and the station-g3-fem build 30 dBm. The tab now shows the real output, the over-the-limit warning, and says what 0 means.


## 1.11.1 — 2026-10-25

- Radio tab: **pick the output in watts.** An "Output power" list shows every setting the connected radio accepts as what comes out of the antenna port ("500 mW at the antenna (27.0 dBm, tx_power 27) ← legal maximum"), marks the ones over the limit for the selected antenna, and sets tx_power when you choose one. **Show every setting…** opens the full table for this radio and antenna: tx_power, watts and dBm at the port, watts and dBm EIRP, legal or not. Output, EIRP and the legal maximum are shown in watts throughout.


## 1.11.0 — 2026-10-25

- **The radio's own tab**, next to Status & signal and titled after the connected radio ("Station G3", "Station G2", "T-Beam 1W", otherwise "This radio"). Everything that depends on the hardware, on one page with one **Save to radio**:
  - **Transmit power and antenna**: pick the antenna (from My antennas or presets), gain and cable/filter loss; it shows the expected output at the antenna port and the EIRP, the US legal maximum for that antenna (≤ 30 dBm conducted, ≤ 36 dBm EIRP), whether you're over, and **Set to the legal maximum**. It knows what tx_power means on each radio: antenna dBm on the T-Beam 1W and the station-g3-fem build, PA drive on a stock G2/G3 (with the jumper level).
  - **Receive**: external LNA (a saved setting on station-g3-fem; jumper guidance or GPIO drive on the stock G3), chip RX boost with advice for the radio, PA fan.
  - **Role**: role, hop limit, transmit enabled (receive-only listener).
  - **Power monitor and firmware**: INA219 supply telemetry, live supply / noise floor / channel readout, Firmware update…, Reboot.
- Saving warns before writing a power that's over the limit for the selected antenna.
- The Station G3 section in Settings moved to the new tab.


## 1.10.8 — 2026-10-24

- Station G3 panel understands the **station-g3-fem** firmware (see `station-g3-fem-firmware.zip`): detected from the LoRa config (the stock build reports the FEM LNA as not present), or tick the box by hand. With it, tx_power is shown and set as antenna dBm (the 30 / 26 dBm buttons set 30 / 26), an **LNA on** switch writes `fem_lna_mode`, and the GPIO "drive from here" controls are disabled because the firmware owns those lines.
- Chip RX boost tooltip corrected: with an external LNA in front it adds little and costs linearity.


## 1.10.7 — 2026-10-24

- Settings, LoRa row: **PA fan off** (`pa_fan_disabled`, Station G2/G3 and T-Beam 1W fans) and **Chip RX boost** (`sx126x_rx_boosted_gain`, ~+2 dB from the SX1262's own LNA).
- Settings → Power monitor: enable INA219 **power telemetry**; the Status tab then shows **Supply: V, A, W** for the connected radio (the G3's motherboard monitor — watch the current on transmit).
- Receive-only reminder: TX enabled off makes a pure listener (no EIRP question, no desense of the neighbouring radio).


## 1.10.6 — 2026-10-24

- **Station G3 amplifier control from the client.** With the PA PL1 and LNA P jumpers removed, the G3 panel can drive the amplifier lines over Meshtastic's Remote Hardware module: pick the PA level pair (PL1 low/high) and LNA on/off, **Enable GPIO control on radio** once (turns the module on with undefined-pin access; the radio reboots), then **Apply**. **Read back** asks the radio for the pin states and logs them. "Re-apply on every connect" resends the setting after the radio reboots, since GPIO writes don't persist. Pins from the board definition: PA PL1 = GPIO 9 (high = high level pair), primary LNA enable = GPIO 10 (low = on).


## 1.10.5 — 2026-10-24

- **Station G3**: Tools → Firmware update knows the `station-g3` build (and `t-beam-1w`). Settings gains a G3 section, shown when a G3 is connected: tick the jumpers as they are on the board (PA PL1, PA PL2, LNA P) and it shows the amplifier level and the expected output at the antenna port for the current `tx_power` (the manufacturer's 915 MHz table, interpolated), with a warning past 30 dBm, and two buttons — set tx_power for ~30 dBm at this level, or ~26 dBm for a 10 dBi yagi. Supply and antenna cautions on the panel.


## 1.10.4 — 2026-10-22

- Trivia: 141 questions. Thirty on computing history (Hopper's moth, the 4004, the Altair, Bell Labs, the Lisa, GNU, Linux 0.01, Mariner 1's hyphen, the Morris worm, Spacewar!, Babbage and Lovelace, Brooks, Dijkstra on BASIC, K&R's "Hello, World") and twenty-six for nerds (42, 0x1F, port 22, 418, sudo, vim, RAID 0, Kirk's home state, the TARDIS, Neuromancer, PEBKAC, the Konami code, Voyager's record, a googol).


## 1.10.3 — 2026-10-22

- Trivia bank grown from 31 to 85 questions: more Meshtastic/LoRa and antenna questions, a ham-radio set (Morse, Q-codes, bands, licence classes, solar indices), a DC/Arlington set (Metro, bridges, the Beltway, 22201), and a few computing-history ones. Answers accept common alternates.


## 1.10.2 — 2026-10-22

- **Daily export per instance**: the Schedule tab's settings are now kept per data folder, so each instance has its own nightly export (and report/traceroute/beacon) schedule. Leave the export folder blank and each instance writes to `<shared export folder>\<its label>\` (e.g. `C:\mesh_exports\DC\meshconsole-DC-<date>.zip`). Scheduled jobs fire on the first tick after their time rather than only on the exact minute, and a time already passed at launch waits for tomorrow.


## 1.10.1 — 2026-10-21

BBS services:
- **ALERTS** — active National Weather Service alerts for the board's location. With "Broadcast NWS warnings" ticked on the BBS tab, each new *warning* (not watches/advisories) is broadcast once on the primary channel — a 200-byte public service.
- **SCORE** — Bills and Sabres, today's game, live score and status (ESPN public scoreboard).
- **SUN** / **MOON** — sunrise, sunset (local time) and moon phase, computed on the board; **ISS** — where the station is right now and how far from here (wheretheiss.at); **SOLAR** — SFI, sunspots, A/K index, X-ray, geomagnetic field and daytime band conditions (hamqsl.com).
Map:
- **Antenna beam overlay** (right-click → Antenna beam overlay…): a wedge from your position along a bearing with a beam width, labelled, 15 km long — the yagi on the map. Per data folder, so each instance draws its own.


## 1.10.0 — 2026-10-21

- **Analysis → Radio vs radio**: pick two radios (or this instance's radio and another instance's data folder), a window and a hop limit; a compass rose shows, per 30° sector, how many dB better radio B hears the nodes both radios heard, with a per-node table. The tool for aiming a yagi.
- **Nodes → right-click → "This is one of my radios"**: marks any node as yours in this instance so it is excluded from signal statistics and analysis (and "Not one of my radios" to undo).
- **Trivia from `trivia.txt`** in the data folder — one `question|answer|alt1;alt2` per line, re-read when the file changes — on top of the built-in bank; an unanswered question expires after 24 h.
- **Congestion alerts digested**: one line per hour ("Congested 7 of the last 12 readings, peak 38%") instead of an alert on every reading above the threshold.
- **Settings → Remote admin**: pick one of your other nodes and add its public key to this radio's admin keys in one click, so that node can configure this one over the mesh via the config editor's Target selector.
- **BBS reply delivery**: replies acknowledged are counted per caller and overall ("replies acked 41/46 (89%)" on the dashboard).


## 1.9.11 — 2026-10-19

- BBS: twice the Buffalo — 26 `GO BILLS` lines, 19 `GO SABRES`, 25 `716`, all under 200 bytes, all lighter. The sign-off on a reply ("Go Bills." or "Go Sabres.") now lands about one reply in twelve.


## 1.9.10 — 2026-10-19

- BBS: `GO SABRES` (French Connection, No Goal, Hasek, The Aud, Rick Jeanneret) and `716` / `BUFFALO` (wings, beef on weck, sponge candy, loganberry, lake effect, the Erie Canal, Amherst Central '92); five more trivia questions. Still sly.


## 1.9.9 — 2026-10-18

- BBS: `GO BILLS` (also `BILLS`) — twelve lines of Buffalo Bills material, one at random; six sly Bills questions in the trivia bank; a Sunday-in-season MOTD when none is set; and, one reply in twenty-five, a quiet "Go Bills." on the end. The sysop is from Amherst, NY. The mesh is in Virginia. The board doesn't care.


## 1.9.8 — 2026-10-18

- **Export settings** (File menu): a shared export folder used by every instance, and a per-instance label; exports are named `meshconsole-<label>-<date>.zip` (label defaults to the data folder's name, e.g. `meshconsole-mesh_data_nova-20261018-212751.zip`).
- BBS: a leading "DM " is ignored (`DM TRIVIA` works like `TRIVIA`).


## 1.9.7 — 2026-10-16

- Messages: reactions are a row of emoji buttons directly in the right-click menu (no hover submenu), drawn in an emoji-capable font on Windows. Click one and it's sent.


## 1.9.6 — 2026-10-14

- Messages: the right-click menu always opens; when React/Reply can't be used it says why ("no packet id on this message" or "not connected") instead of silently doing nothing. Reactions are logged when sent.


## 1.9.5 — 2026-10-11

- **Own radios excluded from statistics**: packets from nodes listed on My radios are kept out of the live chart, signal history, coverage and Analysis (they still show on Nodes, marked "(mine)", and on the Map). View → "Ignore my other radios…" turns it off for an A/B test between your own radios.


## 1.9.4 — 2026-10-10

- Two instances on different data folders (`meshconsole.bat C:\other_folder`) now keep separate "last radio" and TCP-host lists, so auto-connect doesn't make them fight over one radio.
- BBS tab: the advert buttons ("Save advert", "Send next advert now") and the message counter are on their own row so they can't wrap out of view; the WMATA key is masked.


## 1.9.3 — 2026-10-10

- Fix: the Settings tab kept showing the previous radio's values after switching to another radio in the same session (it populated once and never again). It now re-populates whenever the connected node changes and shows "Loading settings from …" until the new config has arrived.
- The laptop's Intel AMT "SOL" port is never auto-selected as a radio.


## 1.9.2 — 2026-10-09

- **Auto-connect at startup**: with Auto-reconnect ticked, the app connects to the radio used last time (COM port or TCP host) a couple of seconds after launch, if it is present. Upgrading no longer leaves the board sitting at "Not connected" until someone presses the button.


## 1.9.1 — 2026-10-09

- BBS advert rotates: put several messages in the advert box separated by a line containing only `---`; one goes out per day, in order (and "Send next now" steps through them). A counter shows how many and the longest in bytes.


## 1.9.0 — 2026-10-09

BBS: WMATA transit, with the free developer.wmata.com key on the BBS tab:
- **NEXT station** — next trains at any Metrorail station (`NEXT CLARENDON`, `NEXT METRO CENTER`), line → destination and minutes.
- **BUS stopid** — next buses at a Metrobus stop (the number on the sign), route, direction, minutes.
- **METRO** rail incidents (as before); **METRO BUS [route]** bus incidents; **ELEV [station]** elevator/escalator outages.
- Station names are matched by prefix or substring; the station list is cached for a day.


## 1.8.10 — 2026-10-09

- BBS settings are protected across versions: a `bbs.json` that fails to parse is repaired if it is the known 1.8.0–1.8.6 double-comma fault, otherwise kept as `bbs.json.broken-<date>` and **never overwritten with defaults** (the BBS tab says so); every save keeps one generation back in `bbs.json.bak`.


## 1.8.9 — 2026-10-09

- BBS scheduler: the daily advert, subscriber digest and MeshNet exchange fire on the first tick **after** their set time (once per day) instead of only if a tick lands exactly on that minute — a reconnect at 08:32 cost the 09:00 advert.


## 1.8.8 — 2026-10-09

- Fix: after a radio reboot mid-session, auto-reconnect could sit on "config still loading" indefinitely — packets were arriving, so the "no reply" safeguard never fired. The config request is now repeated every 3 s until config_complete actually arrives (up to 20 times), and a freshly rebooted radio is given 5 s to finish booting before the first ask.


## 1.8.7 — 2026-10-08

- BBS replies (and MeshNet lines) are sent **once** and never re-queued by the Messages tab's auto-retry; a far caller's un-acked menu was being transmitted six times.
- Shorter `?` menu (fits a PKI-encrypted DM with room to spare); `?2` has the full list.
- Fix: `bbs.json` was written with a double comma in the usage-dashboard counters (strict JSON readers rejected it). The file is rewritten correctly on the next save.


## 1.8.6 — 2026-10-07

- Status & signal shows the radio's **WiFi IP** (and the toolbar status carries it) as soon as the firmware announces it, with a hint that it is what goes into "TCP host… (add)".


## 1.8.5 — 2026-10-05

- **Single instance**: a second copy of Mesh Console started on the same data folder now says "already running" and exits instead of silently sharing the radio and the log files (a `.lock` file in the data folder; headless mode too).
- Log rotation that can't rename the file (held open by another process) keeps writing to the current file and retries later instead of turning file logging off.


## 1.8.4 — 2026-10-04

- Every disconnect now records its cause and the code that asked for it ("Disconnect button", "window closing", "firmware update", "switching to TCP…") in the log and `sessions.log`.
- The app-log file writer reports a failure (disk full, file locked, folder gone) with a dialog instead of silently stopping.


## 1.8.3 — 2026-10-04

- All remaining Swing file choosers (CSV/KML export, record/replay capture, nightly-export folder) replaced with native Windows dialogs. `hang.log` confirmed the freeze was `JFileChooser` waiting on the Windows shell for a drive icon (an offline mapped drive).


## 1.8.2 — 2026-10-04

- Fix for the export freeze: the folder chooser (`JFileChooser`) is gone from Export and Data folder. On Windows it enumerates every mapped drive and can block the window for minutes if a network share is offline. Export now writes to `exports\` inside the data folder by default (background, with "Show in folder"), with a native Windows dialog as the alternative; the data folder is typed in.
- README points to the standalone MeshNet repository.


## 1.8.1 — 2026-10-03

- **MeshNet v1** is now a proper protocol, documented in `meshnet/MESHNET.md`: global ids (`origin:n`) so posts dedupe across any route, `HELLO v1` with capability flags, **ACK with retry** (10 min, 3 attempts) and pacing (2 lines/min), **forwarding** to other peers up to 3 hops with loop protection, and optional **HMAC-SHA256 signatures** per peer (`!id=Name=secret` on the BBS tab).
- **`meshnet/meshnet.py`** — a dependency-free Python reference implementation (~150 lines) any Meshtastic BBS can drop in: `handle_dm()`, `queue_post()`, `queue_mail()`, `exchange()`, `tick()`. Verified interoperating with the Java board, signatures on.


## 1.8.0 — 2026-10-03

BBS 2.1 + **MeshNet**:
- **MeshNet** — a WWIVnet-style store-and-forward network between boards running Mesh Console, over ordinary DMs: boards exchange new bulletin headlines daily (`NET POST`, deduplicated, shown as "author via Board"), route mail (`M board!user text`), and announce themselves (`NET HELLO`). Peers are configured by node id on the BBS tab; callers see them with `NET`. Protocol documented in the README so other BBS software can interoperate.
- **Usage dashboard** on the BBS tab: calls per day, busiest hour, top commands, callers this week.
- **Subscriptions**: `SUB WX` / `SUB EVENTS` — a daily digest DM at a set time; `UNSUB`.
- **MOTD** shown on a caller's first `?` of the day; `B` reports "n new since your last visit".
- **WHO name** node profile card; **POLL** / `V n` polls (sysop creates them on the tab); **GB text** guestbook.
- **Admin by DM**: `SYSOP password` then `X POST|DEL|CALLERS|BAN|UNBAN|MOTD|ADVERT|STATS|NET` — run the board from a handheld.
- **TOP** adds farthest caller and most-hops DM.
- **Discord → board**: with a bot token and channel id, messages starting `!post` in that channel become bulletins (polled every 5 min); bulletins, events and guestbook entries go out to the webhook as before.


## 1.7.3 — 2026-10-01

- UI refreshes are coalesced: the Nodes, Messages, Telemetry, Status and Map views rebuild at most once a second instead of on every packet — with 800 nodes the per-packet re-sorting could stall the window for minutes.
- A UI watchdog writes `hang.log` (with the UI thread's stack) if the window stops responding for more than 8 s, so a freeze can be diagnosed.


## 1.7.2 — 2026-10-01

- Any uncaught error is now written to `crash.log` in the data folder (with the stack trace) and shown in a dialog; the app keeps running instead of silently dying. `crash.log` is included in exports.
- Export: a file that is rotated or locked while the zip is being written is skipped instead of failing the whole export.


## 1.7.1 — 2026-09-30

- BBS: **daily advertisement** — one broadcast at a chosen time while the board is online (BBS tab: text, time, on/off, "Send now"). Default text names the board and the ? command. Once a day only, by design.


## 1.7.0 — 2026-09-29

BBS 2.0 — new commands for callers:
- **T** traceroute on demand (the BBS traces the caller and replies with the path and per-hop SNR); **S** now includes distance.
- **STATS** mesh snapshot (nodes/hour, direct, known, utilisation, farthest node heard); **TOP** leaderboards (farthest node, most hops, top poster, trivia champion).
- **TAF** aviation forecast and **METRO** WMATA rail alerts (needs a free key from developer.wmata.com, set on the BBS tab); **C callsign** FCC lookup via callook.info.
- **E** events (sysop-posted, with dates), plus games: **TRIVIA** (A answer; points kept), **GUESS** (G n), **ADV** (a five-room text adventure).
- **Discord/Slack webhook bridge**: bulletins and events are mirrored to a channel (read-only from that side).
- Menu split into `?` and `?2`; everything stays under 200 bytes per reply.


## 1.6.10 — 2026-09-29

- Messages: **right-click a message** → React (👍 👎 ❤️ 😂 😮 😢 🔥 👋 ✅ ❓ or any emoji), Reply… (threaded reply the other end shows as quoting your message), Direct message the sender, Copy text. Reactions and replies go where the original went — the channel for a broadcast, the sender for a DM.


## 1.6.9 — 2026-09-29

- The automatic 5-minute stats request introduced in 1.6.8 is now **off by default** (View → "Auto-refresh radio stats"). A self-addressed telemetry request coincided with repeated watchdog resets on a T-Deck running 2.7.26; the Refresh stats button still works on demand.
- "Send failed" is logged once per 10 s during an outage instead of once per queued packet.


## 1.6.8 — 2026-09-28

- Status: **Refresh stats** button asks the radio for fresh device metrics and local statistics (noise floor, rx/bad/dupe, utilisation, battery); the app also asks every 5 minutes, so those lines no longer wait for the firmware's own 15–30 minute report.


## 1.6.7 — 2026-09-28

- Status: uptime counts forward from the radio's last statistics report instead of freezing between reports (device telemetry arrives every few minutes); the age of the last report is shown beside it, and the panel refreshes every 30 s.


## 1.6.6 — 2026-09-28

- Fix: the Nodes right-click menu (and double-click to message) announced in 1.6.1 was never actually attached to the table. It is now.
- Fix: "Request node info" now sends our **public key** (and hardware model) with our name, so the other node can accept PKI direct messages from us afterwards; the log line says when it went out.


## 1.6.5 — 2026-09-28

- Nodes table: click a column header to sort (click again to reverse). Numeric columns — last heard, hops, SNR, RSSI, battery, distance, packets, averages, sessions, airtime, neighbours — sort by value, with unknowns last; the selection and right-click menu follow the sorted order.


## 1.6.4 — 2026-09-28

- TCP: heartbeat every 20 s (was 60), socket keep-alive on, reconnect retries every 2 s for 30 minutes — a WiFi radio that drops out is noticed and picked up again quickly.


## 1.6.3 — 2026-09-28

- The **Radio port** drop-down now lists remembered TCP hosts alongside the COM ports, plus a "TCP host… (add)" entry; Connect does the right thing for whichever is selected. When no radio is on USB the last-used TCP host is preselected.
- Auto-reconnect works for TCP too (retries every 5 s for 5 minutes after a lost connection — radio reboot, WiFi blip).


## 1.6.2 — 2026-09-28

- The app now sets the radio's clock from the PC on connect (`set_time_only`), like the phone and web clients — a radio with no GPS, phone or NTP otherwise runs on its build date.
- KEY_CHANGE alerts only when a node's public key changed together with its name or hardware; a key change alone (firmware 2.7 rotates keys on reflash/reset) is logged as INFO.


## 1.6.1 — 2026-09-27

- Messages: destination list is alphabetical, with a **find** box beside it — type part of a name, short name or !id to narrow it (a single match is selected automatically).
- Nodes: **right-click menu** — Send direct message, Traceroute, Request node info, Show on map, Watch/unwatch, Favourite on radio, Copy !id. **Double-click** a node to message it.


## 1.6.0 — 2026-09-27

- **Firmware update** (Tools → Firmware update…): lists Meshtastic releases from GitHub (stable, optionally pre-releases), downloads the right platform package for the connected board, lets you pick the file (best guess preselected), and flashes it:
  - **nRF52 boards** (RAK4631/3401, T-Echo, T1000-E, Heltec T114, …): fully in-app — the radio is put into DFU mode, the UF2 bootloader drive is detected, the file copied; settings are kept.
  - **ESP32 boards** (Station G2, T-Deck, Heltec V3/V4, T-Beam, …): via `esptool` if installed (`pip install esptool`) with the correct arguments — update at 0x10000 keeps settings, full install erases first; otherwise the file is downloaded and the web flasher opened.
- Fix: the Common settings tab could write empty defaults (region UNSET, tx power 0) if "Save LoRa" was pressed before the radio's config had arrived. Saves now refuse until the section has been received.


## 1.5.2 — 2026-09-27

- Settings → Common: **WiFi / network** section (WiFi on/off, SSID, password, Ethernet, NTP) for ESP32 boards, so a Station G2 or T-Deck can be put on the LAN and then used over TCP.


## 1.5.1 — 2026-09-24

- Fix: "Export all logs as zip" ran on the UI thread and froze the window for the duration of a large export. It now runs in the background with a progress dialog.
- `meshconsole.log` rotates at 50 MB (keeps .1, .2, .3) so the firmware debug stream can't grow without limit.


## 1.5.0 — 2026-09-23

- **MQTT witness** tab — read-only subscription to a public Meshtastic broker; decodes gateway envelopes (default key and your radio's channel keys) and lists **your node's packets as heard by gateways**: an independent "does the mesh hear me?" check. Gateway table, feed view, `mqtt_witness.csv`, WITNESS alerts.
- **Second radio window** (Tools) — connect a second radio (USB or TCP) alongside the main one; live signal chart, packet log and a 15-minute side-by-side RSSI/packet comparison; its samples feed the shared history for the My-radios comparison.
- **Path / LOS** (Analysis) — terrain profile between two nodes (SRTM via opentopodata.org), 4/3-earth curvature, 60 % first-Fresnel clearance at the chosen frequency, verdict and the antenna height that would clear an obstruction.
- **Channel URL import/export** (Tools) — paste a `meshtastic.org/e/#…` link to write its channels and LoRa settings; export your own.
- **Schedule** tab — daily report, nightly zip export to a folder, weekly traceroutes to watched nodes, periodic beacon message (an automated range test tracked in the delivery stats). Runs in headless mode too.
- **Web dashboard** (Tools, or `--web 8080` headless) — built-in HTTP server: status, node table, Leaflet map, recent messages, report; check the Pi from a phone.
- **Notifications** — Discord/Slack-style webhook and Pushover for alerts and DMs (Alerts tab).
- **Watch-list** — per-node watch flag with lost/back alerts; **favourite on radio** syncs the star with the phone app.
- **Google Earth (KML) export** — nodes, tracks, coverage grid.
- **Message search** and **thread view** (replies and reactions).
- **Remote node admin** — the All-settings editor can target another node you own: read a section from it, edit, write back (uses that node's session key and PKI).
- **Firmware check** — Status shows whether the radio's firmware is the latest stable release.


## 1.4.4 — 2026-09-23

- **View → Show BBS features** (off by default): hides the BBS tab, toolbar toggle and sysop button. The BBS code stays isolated in its own package and does nothing while disabled.


## 1.4.3 — 2026-09-23

- **Sysop screen** — a separate window in the style of a classic BBS sysop console: status line (online/offline, uptime, calls today, callers, posts, mail), "waiting for caller" line showing the current caller and command, caller list with last command and ban status, live activity, and function keys: F1 BBS on/off, F2 chat with the selected caller (sent as a DM tagged [sysop]), F3 post bulletin, F4 ban/unban, F5 clear, F6 mail list, F9 save, Esc close.
- **BBS: ON/OFF** toggle and **Sysop screen** button in the main toolbar. Callers are tracked (first/last seen, command count) in `bbs.json`; all BBS activity continues to go to `bbs.log`.


## 1.4.2 — 2026-09-23

More logging:
- **Node-count history** — every 15 min: nodes heard in the last hour, nodes online per the radio, nodes known (`util_history.csv` C rows); charted on Analysis → Channel health (the mesh's growth/activity curve).
- **Traceroute log** (`traceroute_log.csv`) — every traceroute result with route, per-hop SNR, and the return path, so route changes over weeks are visible.
- **MQTT proxy traffic log** (`mqtt_log.csv`) — direction, topic, bytes for every message relayed.
- **Per-message airtime** — estimated time-on-air for every message (new column in the Messages table and in `messages.log`).
- **Admin log** (`admin_log.txt`) — every setting changed from the app, with old → new values per field, plus owner/position/channel/reboot commands.


## 1.4.1 — 2026-09-23

- **All settings editor** (Settings → "All settings" sub-tab): every field of every Config and ModuleConfig section (device, position, power, network, display, lora, bluetooth, security; mqtt, serial, external notification, store & forward, range test, telemetry, canned messages, audio, remote hardware, neighbor info, ambient lighting, detection sensor, paxcounter…), generated from the protobuf definitions so new firmware fields appear automatically. Enums and booleans get drop-downs, lists are comma-separated, keys are base64. Writes the whole section back, preserving untouched fields.
- BBS defaults to "Lyon's Den" (Lyon Village, Arlington VA); BBS activity also logged to `bbs.log`.
- Own-node battery/voltage history (`util_history.csv`, P rows).


## 1.4.0 — 2026-09-23

- **BBS tab** — a bulletin-board bot that answers direct messages to this node: `?` menu, `B` bulletins (paged), `R n` read, `P text` post, `D n` delete own, `M` read mail, `M name text` leave mail for a node (delivered when it is next heard), `N` nodes heard in the last hour, `S` the sender's signal as seen here, `W` weather (from the METAR feed), `I` info, `PING`. Sysop tools: name and welcome text, post as sysop, delete, ban, per-node cooldown, optional single trigger word on the public channel (off by default). Replies never exceed 200 bytes. Stored in `bbs.json`.
- **Headless mode** for a Raspberry Pi or server: `meshconsole.sh --headless --port /dev/ttyACM0 [--data <folder>] [--bbs]` (or `--tcp host`). Logs, node DB, signal/utilisation history, alerts and the BBS all run without a display; reconnects forever.
- **Noise floor history**: the radio's reported noise floor is logged (`util_history.csv`), charted on Analysis → Channel health with a 24 h median and best/worst, and included in the report.


## 1.3.9 — 2026-09-22

- **Setup log** (`setup_log.csv`): record the physical setup of the connected radio — antenna, antenna placement (indoor desk / window / attic / balcony / outdoor wall / roof / mast / vehicle / handheld), height above ground in metres, radio location, notes. Each recording starts a new test period; signal samples are attributed to the setup in force for the receiving radio. **By setup** table on the My radios tab compares samples, average RSSI/SNR, nodes heard and direct % per setup, best first.


## 1.3.8 — 2026-09-22

- **My radios** (Analysis tab): every node you connect is registered automatically (`radios.json`) with ID, hardware, firmware, tx power, role; you add a name, the antenna fitted (from the antenna library), location and notes. Per radio: hours connected, samples heard, average RSSI/SNR, distinct nodes heard, direct %, DMs sent and delivered — so a RAK, a Heltec and a Station G2 can be compared as receivers and senders. Signal samples now record which radio received them (7th column in `signal_history.csv`).


## 1.3.7 — 2026-09-22

- **Antenna library** (`antennas.json`): add, edit and remove the antennas you own — type, advertised gain, length, band, connector, mounting/location, notes. Pick the one in use from a drop-down; the library table shows each antenna's measured SWR (best, at what frequency, worst, sweep date), hours used, and on-air average RSSI/SNR and node count from the A/B comparison. Antenna SWR tab: **Save sweep to antenna…** attaches a NanoVNA sweep to a library entry.


## 1.3.6 — 2026-09-22

- **LoRa config changes are tracked**: every change (region, preset, frequency slot, hop limit, tx power) is logged, written to `config_log.csv`, raised as an INFO alert, and included in `sessions.log`. Signal samples now carry the frequency slot they were heard on (new 6th column in `signal_history.csv`; older rows read as slot 0). Analysis → "Antenna / slot A/B" adds a per-slot comparison (samples, avg RSSI/SNR, distinct nodes, time span).


## 1.3.5 — 2026-09-22

- Settings → LoRa: **frequency slot** (`channel_num`), **OK to MQTT** and **Ignore MQTT** — enough to join a regional mesh on a non-default slot (e.g. NoVa-Mesh: LongFast, slot 9, hop limit 7) from the PC.


## 1.3.4 — 2026-09-22

- **File → Export all logs as zip…**: bundles every data file (message log, node DB, signal/weather/utilisation history, alerts, sessions, app log, report; optionally packet captures) into `meshconsole-data-YYYYMMDD-HHmmss.zip` with an `export_info.txt` stamp, in a folder you choose.


## 1.3.3 — 2026-09-22

- **File → Data folder…**: choose where all logs, the node database and history files live (remembered per user; takes effect on restart), with the option to copy existing data over. **File → Open data folder** opens it in Explorer/Finder. A folder can also be given as the first command-line argument.
- **Version-safe upgrades**: on the first start of a new version, existing data files are copied to `backup/<old-version>-<date>/` before being opened, and `data_version.txt` records who wrote the data. File formats are append-only and forward/backward tolerant, so newer and older versions can share a folder.


## 1.3.2 — 2026-09-22

- Fix: nodes with unknown battery/hops were written to `nodes.json` as 4294967295 and silently dropped on reload. Existing files are read correctly now.


## 1.3.1 — 2026-09-22

- **Fixed position**: set the radio's coordinates from the Settings tab (lat/lon/altitude) or by right-clicking the map → "Set as my fixed position"; uses the firmware's `set_fixed_position` admin command, which also enables fixed-position mode. "Remove fixed position" reverts to GPS.


## 1.3.0 — 2026-09-22

Analysis and weather:

- **Weather tab** — automatic METAR download from NOAA aviationweather.gov (default KDCA, or **Find nearest…** / auto-nearest from the node's position), every 30 min, with 48 h backfill; `weather_history.csv`. Per-node correlation of hourly RSSI with temperature, humidity, wind and pressure (Pearson r) and the wet-vs-dry RSSI difference; scatter plot per node.
- **Analysis tab** with sub-tabs:
  - *Link quality* — day-of-week × hour-of-day RSSI heat map per node; link margin (avg SNR minus the modem's demodulation limit) with verdicts.
  - *Mesh structure* — graph from neighbour reports, traceroutes and direct reception: degree, hops from you, **critical relays** (articulation points), relay share from `relay_node`; node churn chart (active per day, arrivals) and median lifetime.
  - *Channel health* — utilisation by hour of day (`util_history.csv`, 90 days), per-node air-time budget with the 10 % guideline flagged, duplicate-packet trend.
  - *Delivery* — success rate by hops, distance and time of day.
  - *Antenna A/B* — record which antenna is in use (`antenna_log.csv`); compare average RSSI/SNR per antenna on all nodes and on the nodes heard under every antenna.
  - *Report* — one-click `mesh_report.html` (new/gone nodes, busiest talkers, weakest links, critical relays, utilisation curve, delivery, weather correlation, antenna comparison, recent alerts), opened in the browser.
- **Coverage grid** map layer: 100 m cells with median RSSI.


## 1.2.1 — 2026-09-22

- Signal history kept for one year (was 7 days): full resolution for 7 days, hourly per-node averages beyond; chart ranges for 30 days and 1 year. ANSI colour codes stripped from firmware log lines.
- Fix: RAK4631 / nRF52 radios never answered in 1.2.0 because DTR was de-asserted on connect (added in 1.1 for ESP32 boards). DTR and RTS are now both asserted, which nRF52 needs and which does not reset ESP32 boards.


## 1.2.0 — 2026-09-22

Capture, analysis, history and alerts:

- **Packet capture & replay** — record every frame from the radio to a `.mcap` file (Status tab) and replay it later at real time, 10× or full speed, with no radio attached. Ideal for bug reports.
- **Session log** (`sessions.log`) — connect/disconnect, reason, duration, firmware and node count per session.
- **Traffic tab** — airtime by node, by app (port) and by channel, computed from packet size and the modem preset; share of elapsed time; radio-reported duplicate count; last relay node per node.
- **Relay path** — `relay_node`/`next_hop` shown in verbose logs and the Traffic tab.
- **Alerts tab** — node silent for N hours (favourites or all), battery below X %, utilisation above Y %, radio reboot, public-key change of a known node, admin commands seen between other nodes, detection-sensor triggers, new direct messages. Desktop tray notifications, optional beep, `alerts.log`.
- **Persistent node database** (`nodes.json`) — first/last seen, packet counts, averages, sessions and keys survive restarts. Nodes only remembered from earlier sessions are shown grey/italic until heard again; "Last seen" column with absolute time and colour-coded age; **Save DB now** / **Reset DB** buttons; automatic save every 5 min and on disconnect/exit.
- **Long-term signal history** (`signal_history.csv`, 7 days) — Status chart range selector: live, last hour, 24 h, 7 days.
- **Radio clock vs PC** drift on the Status tab (spots nodes without a time source).
- **Public-key change detection** and **admin audit** of config changes between other nodes.
- **Range Test module** — sequence tracking with loss % per node; **Paxcounter** (wifi/ble device counts) and **Detection Sensor** messages.
- Own-node local packets no longer counted as airtime.


## 1.1.0 — 2026-09-22

New data captured and shown:

- **Per-node link statistics** — packets heard, direct %, average RSSI/SNR, first seen (Nodes tab, new columns; table now scrolls horizontally).
- **Node identity** — role (router/client/…), favourite, licensed-ham, unmessagable, ignored, via-MQTT flags.
- **Telemetry tab** — battery, voltage, utilisation, uptime, plus environment (temperature, humidity, pressure, IAQ, lux, wind) and power-monitor channels (voltage/current) from nodes with sensors.
- **Channel-utilisation chart** for this radio on the Status tab; heap memory and cancelled relays in the status text.
- **Neighbour graph** on the map — who hears whom, with SNR labels (from nodes running the Neighbor Info module).
- **Position detail and tracks** — speed, satellites, precision; moving nodes leave a breadcrumb trail.
- **Waypoints** — received markers drawn on the map; right-click the map to create and broadcast one.
- **Coverage layer** — RSSI-coloured dots where this radio was when it heard packets (drive-test map; needs GPS on the node).
- **Replies and reactions** — quoted reply context and emoji reactions shown in the message log.
- **Store & Forward** — request history from a S&F server node; replayed messages tagged [S&F].
- **Remote node admin** — ask another node for firmware/metadata and LoRa config (needs admin rights on it); **Request node info** refreshes a node's user record.
- **Stats & export tab** — delivery statistics per destination (success %, attempts, time to ack) and CSV export of nodes, signal samples, telemetry, messages, coverage points and neighbour links.

## 1.0.0 — 2026-09-21

Initial release: messaging with delivery tracking and auto-retry, nodes, map, live signal, traceroute, settings, MQTT client proxy, TCP, NanoVNA SWR, splash/About, C++ CLI.
