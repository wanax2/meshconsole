# Changelog

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
