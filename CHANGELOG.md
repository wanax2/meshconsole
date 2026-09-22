# Changelog

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
