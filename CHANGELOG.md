# Changelog

## 1.2.1 — 2026-09-22

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
