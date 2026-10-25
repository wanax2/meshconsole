#!/bin/sh
# Mesh Console on a Raspberry Pi (Pi OS 64-bit). Run from the unzipped ready-to-run folder:  sh pi/install-pi.sh
set -e
sudo apt-get update
sudo apt-get install -y openjdk-21-jre-headless
sudo usermod -aG dialout "$USER"
sudo mkdir -p /opt/meshconsole
sudo cp meshconsole.jar protobuf-java.jar jSerialComm.jar meshconsole.sh /opt/meshconsole/
sudo chmod +x /opt/meshconsole/meshconsole.sh
sudo chown -R "$USER" /opt/meshconsole     # so the self-updater can replace the jars
sudo cp pi/meshconsole@.service /etc/systemd/system/
for n in dc ne; do [ -f /etc/default/meshconsole-$n ] || sudo cp pi/meshconsole-$n.default /etc/default/meshconsole-$n; done
sudo sed -i "s/^User=.*/User=$USER/" /etc/systemd/system/meshconsole@.service
sudo systemctl daemon-reload
mkdir -p "$HOME/mesh_exports/DC" "$HOME/mesh_exports/NE"
echo
echo "Installed. Next:"
echo "  1. copy your data folders to $HOME/mesh_data1 (DC) and $HOME/mesh_data2 (second radio)"
echo "  2. edit /etc/default/meshconsole-ne: put the radio's /dev/serial/by-id/... path in (ls /dev/serial/by-id/)"
echo "  3. log out and back in (dialout group), then:  sudo systemctl enable --now meshconsole@dc meshconsole@ne"
echo "  4. watch:  journalctl -u meshconsole@dc -f      dashboards: http://$(hostname).local:8080 and :8081"
