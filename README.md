# Mesh Console

![build](../../actions/workflows/build.yml/badge.svg)

Desktop client (Java 17+, Swing) for a Meshtastic radio — e.g. a RAK WisBlock / RAK4631 —
plugged into a Windows or Linux PC over USB. Same code runs on both; no OS switch needed.

**Tabs**

| Tab | What it does |
|---|---|
| Status & signal | Own node, firmware, LoRa region/preset/tx power, channels, battery, channel utilisation, noise floor, TX queue. Live strip chart of RSSI + SNR of every received packet (filterable per node). Device log + app log. |
| Messages | Full message log (persisted to `messages.log` across restarts). Send to broadcast or a specific node on any channel. Delivery status per message: queued → sent → **delivered** (real ACK from the destination) or **failed** (NO_ROUTE, MAX_RETRANSMIT, …). **Resend selected** re-transmits a message with a fresh packet id. **Auto-retry DMs**: the firmware gives up after its own 3 retransmissions; with this on, a NAK'd or un-acknowledged direct message is sent again after a pause, up to the chosen number of attempts. |
| Nodes | Every node in the radio's DB: last heard, hops, SNR/RSSI, battery, distance, position, hardware, role, flags, packets heard, direct %, average RSSI/SNR, first seen. Buttons: message node, **traceroute** (per-hop SNR both ways), request position, show on map, request node info, remote info (admin), store-&-forward history. |
| Map | OpenStreetMap tiles with node markers (colour = age), your node highlighted, lines to direct neighbours, the **neighbour graph** (who hears whom, with SNR), **tracks** of moving nodes, **waypoints** (right-click to create), and a **coverage** layer (RSSI-coloured dots where your node was when it heard packets). Tiles are cached in `tilecache/`. |
| Telemetry | Battery, voltage, utilisation, uptime, environment (temperature, humidity, pressure, IAQ, lux, wind) and power-monitor channels per node. |
| Traffic | Airtime by node, app and channel from packet size × modem preset; share of elapsed time; last relay node. |
| Analysis | Link-quality heat map and link margin per node, mesh graph with critical relays and relay share, churn, utilisation by hour, air-time budget, delivery by hops/distance/time, antenna A/B, one-click HTML report. |
| Weather | METARs from NOAA aviationweather.gov (KDCA by default, nearest-station lookup), fetched every 30 min; correlation of each node's signal with temperature, humidity, wind, pressure and rain. |
| BBS | A bulletin-board bot answering DMs to this node (bulletins, mail, node list, signal report, weather). See the tab for the command list. |
| Alerts | Silent node, low battery, high utilisation, reboot, key change, admin audit, detection sensor, new DM — with tray notifications and `alerts.log`. |
| Stats & export | Delivery statistics per destination (success %, attempts, time to ack) and CSV export of nodes, signal samples, telemetry, messages, coverage points and neighbour links. |
| Antenna SWR | Sweeps a **NanoVNA** on a second USB port and plots SWR vs frequency with band presets (US 915, EU 868, 433, …). |
| Settings & MQTT | Edit and write back owner name, LoRa (region, preset, hop limit, tx power, frequency override), device role, the MQTT module config, and all 8 channel slots (name, PSK, role, uplink/downlink). Reboot. **MQTT client proxy**: this PC relays the radio's MQTT traffic through its own internet connection — the only way a WiFi-less RAK4631 reaches MQTT. |

Connection: USB serial, or **TCP** (port 4403) for WiFi/Ethernet nodes and the bundled fake radio.

Tested on a RAK4631. Any board running Meshtastic firmware uses the same protocol — Station G2, LilyGo T-Deck /
T-Deck Pro, Heltec V3 / V4, T-Beam, etc. ESP32 boards need two allowances the app makes automatically: DTR/RTS are
left de-asserted on open (otherwise the board can be held in reset) and the config request is repeated until the
radio answers; boards with native USB (T-Deck, Heltec V4) drop their COM port whenever they reboot, so
**Auto-reconnect** in the toolbar waits for it to return. Heltec V3 and Station G2 use a CP2102 USB bridge — install
Silicon Labs' CP210x driver if no COM port appears on Windows.

### About SWR — read this first

A Meshtastic radio **cannot report SWR**. The SX1262 LoRa chip in the RAK4631 has no forward/reflected
power detector, and the firmware exposes nothing of the kind, so no software talking to the radio can
show it. The options are:

* **NanoVNA** (≈ $50, USB, very common) — supported by the SWR tab. Unplug the antenna from the radio, plug it
  into the VNA, sweep. This is what most Meshtastic users do.
* An inline SWR/power meter between radio and antenna — read on its own display; the radio doesn't see it.

What the radio *can* tell you, and what this app shows live: RSSI / SNR of received packets, noise floor,
channel utilisation, and per-hop SNR from traceroute — good for comparing antennas empirically.

## Build

Two ways; both need a JDK 17+ and git.

**A. No Gradle (simplest):**

```
./build.sh          # Linux/macOS      ->  dist/meshconsole.sh
.\build.ps1         # Windows          ->  dist\meshconsole.bat
```
Classes are compiled with `--release 17`, so the output runs on any Java 17+ runtime regardless of which JDK built it. The script downloads protoc, `protobuf-java` and `jSerialComm` once into `lib/`, clones the Meshtastic
protobufs, generates the classes and compiles. `dist/` is self-contained — copy it anywhere.

**B. Gradle** (Gradle 8.x): (Windows: `winget install EclipseAdoptium.Temurin.21.JDK Gradle.Gradle`;
Ubuntu/Debian: `sudo apt install openjdk-21-jdk gradle git`.)

```
./fetch-protos.sh          # Windows: .\fetch-protos.ps1   – clones github.com/meshtastic/protobufs
gradle run                 # builds (protobuf code generation included) and starts the GUI
gradle installDist         # optional: standalone launcher in build/install/meshconsole/bin/
```

The protobufs are generated at build time from the upstream repo, so re-running `fetch-protos` picks up new
firmware fields automatically.

No hardware? Start the bundled fake node and connect to it with the **TCP…** button (`127.0.0.1:4403`):

```
gradle fakeRadio
```

Headless self-tests (framing/decoding, admin session-key flow): `gradle smokeTest`; `AdminTest` in `src/test` runs the same way.

## Running

1. Plug the RAK in. It enumerates as a USB CDC serial port (Windows: `COMx`, Linux: `/dev/ttyACM0`).
2. Start the app, pick the port (it auto-selects anything that looks like a RAK/USB serial device), **Connect**.
3. Within a few seconds the status line shows your node name and the node DB loads.

The Meshtastic app on your phone can stay paired over Bluetooth at the same time; only one *serial* client
can hold the port.

### Linux notes
* Add yourself to the serial group once: `sudo usermod -aG dialout $USER` (log out/in).
* ModemManager sometimes grabs `/dev/ttyACM0` for a few seconds after plug-in and the port looks busy.
  Either wait, or `sudo systemctl disable --now ModemManager` if you don't use a cellular modem.

### Windows notes
* Windows 10/11 needs no driver for the RAK4631's CDC port. If it shows up in Device Manager with a warning,
  install the Meshtastic web-flasher's serial driver.
* Only one program can open the COM port — close the Meshtastic CLI / web client first.

## Logging

Status tab, above the log: **Record packets** saves every frame to a `.mcap` capture; **Replay capture…** plays one back through the whole app. **Verbose** logs every packet (from/to, port, hops, RSSI/SNR, ack references), every
config/channel/admin item, and MQTT proxy transfers; **Trace frames** additionally dumps the protobuf content of every
frame in both directions; **Write meshconsole.log** appends everything to `meshconsole.log`. Copy/Clear buttons for
pasting into a bug report. The radio only streams its *own* debug output to a connected client when
`security.debug_log_api_enabled` is on — Settings tab → Logging → "Stream firmware debug log".

## Files it writes

By default everything goes in the folder the app is started from; **File → Data folder…** moves that anywhere (a synced drive, a second disk), and `meshconsole.bat D:\meshdata` does the same from the command line. Formats are plain text and append-only; a newer version keeps writing to the same files, and before its first write it copies them to `backup/<old-version>-<date>/`.

* `nodes.json` — persistent node database (identity, first/last seen, counters). Reset from the Nodes tab.
* `signal_history.csv` — every RSSI/SNR sample for one year (pruned at startup). The chart keeps 7 days at full resolution and hourly per-node averages beyond that; in a busy mesh expect the file to grow by a few MB per day.
* `sessions.log`, `alerts.log` — one line per connection / alert.
* `weather_history.csv`, `util_history.csv`, `antenna_log.csv`, `mesh_report.html` — analysis inputs and output.
* `*.mcap` — packet captures (Status tab → Record packets).
* `meshconsole.log` — app/device log when "Write meshconsole.log" is ticked.
* `messages.log` — tab-separated message history (time, direction, from, to, channel, packet id, status, RSSI, SNR, hops, text). Pass a path as the first command-line argument to use a different file.
* `tilecache/` — downloaded OSM tiles.

## How it talks to the radio (for extending it)

* `mesh/MeshSerial.java` — the stream protocol: `0x94 0xC3 <len16> <protobuf>` frames carrying `ToRadio`/`FromRadio`.
  On connect it sends `want_config_id`, the firmware answers with `my_info`, one `node_info` per node,
  config, channels, then `config_complete_id`, after which live `packet`s arrive. A `heartbeat` goes out every minute.
* `mesh/MeshState.java` — decodes packets by port number (`TEXT_MESSAGE_APP`, `POSITION_APP`, `NODEINFO_APP`,
  `TELEMETRY_APP`, `ROUTING_APP` for ACK/NAK, `TRACEROUTE_APP`, `NEIGHBORINFO_APP`) and keeps the node DB,
  message list and signal history.
* `mesh/MeshClient.java` — builds outgoing packets (`sendText`, `resend`, `traceroute`, `requestPosition`).
  Direct messages to nodes that advertise a public key are flagged `pki_encrypted` like the official apps.
* `swr/NanoVna.java` — NanoVNA shell commands (`sweep`, `frequencies`, `data 0`) → SWR = (1+|Γ|)/(1−|Γ|).

Adding another port number is a new `case` in `MeshState.handlePacket`; adding an admin operation (e.g.
changing config) means building an `AdminMessage` from `AdminProtos` and sending it on `ADMIN_APP`.

## Settings tab: how writes work

The firmware only accepts state-changing admin messages that carry a fresh *session passkey*. `MeshClient.ensureSessionKey()`
asks for device metadata (any "get" answer includes the key), then wraps each write in
`begin_edit_settings` / `set_*` / `commit_edit_settings`. Some LoRa/device changes make the radio reboot itself.

MQTT proxy: needs *MQTT module enabled* + *Proxy to client* saved on the radio. The app then connects to the
configured broker (default `mqtt.meshtastic.org`, user `meshdev`), subscribes to `<root>/2/e/#`, publishes
whatever the radio hands it, and forwards broker traffic back into the radio. Uplink/downlink are per channel
(Channels table). The MQTT client is a small built-in MQTT 3.1.1 implementation (`mqtt/MqttClient.java`), no library needed.

## C++ port (`cpp/`)

`cpp/` is a dependency-light C++17 port of the core (framing, node DB, decoding, acks, traceroute) as a CLI:

```
sudo apt install libprotobuf-dev protobuf-compiler cmake g++     # Windows: vcpkg install protobuf
./fetch-protos.sh
cd cpp && mkdir build && cd build && cmake .. && make
./meshcli --port /dev/ttyACM0 info                # or --port COM5 on Windows, or --tcp host[:4403]
./meshcli --port /dev/ttyACM0 nodes
./meshcli --port /dev/ttyACM0 listen              # live messages with RSSI/SNR/hops
./meshcli --port /dev/ttyACM0 send "hello" --to !aabbccdd --ch 0     # waits for the delivery ack
./meshcli --port /dev/ttyACM0 traceroute !aabbccdd
```

* `serial.cpp` — self-contained serial port (termios / Win32) and TCP stream, no third-party library.
* `mesh_stream.cpp` — the framed stream protocol, reader thread, heartbeat.
* `mesh_state.cpp` — node DB and packet decoding (same logic as the Java `MeshState`).
* `main.cpp` — the CLI; a GUI (Qt, wxWidgets, ImGui…) would sit on top of the same two classes.

Tested against the fake radio over TCP; the Windows serial path is written but was compiled only on Linux.

## License

MIT — see `LICENSE`. The Meshtastic protobuf definitions are fetched from the upstream repository at build time and remain under their own (GPL-3.0) license; they are not redistributed here.

## Headless (Raspberry Pi)

The same build runs without a display — useful for a Pi sitting next to the radio:

```
sudo apt install -y openjdk-17-jre unzip && sudo usermod -aG dialout $USER   # log out/in once
./meshconsole.sh --headless --port /dev/ttyACM0 --data /home/pi/meshdata --bbs
```

It connects (and reconnects after unplugs/reboots), writes the same log and history files as the GUI, runs the alerts and the BBS, and saves the node DB every 5 minutes. As a service:

```
sudo tee /etc/systemd/system/meshconsole.service > /dev/null << 'EOF'
[Unit]
Description=Mesh Console headless
After=network.target
[Service]
User=pi
WorkingDirectory=/home/pi/meshconsole
ExecStart=/home/pi/meshconsole/meshconsole.sh --headless --port /dev/ttyACM0 --data /home/pi/meshdata --bbs
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl enable --now meshconsole
```

Point the desktop GUI's data folder at a copy (or a share) of the Pi's folder to analyse it.
