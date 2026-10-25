# Changelog

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
