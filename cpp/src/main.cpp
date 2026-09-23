// meshcli — command-line Meshtastic client (serial USB or TCP).
//
//   meshcli --port /dev/ttyACM0 info
//   meshcli --port COM5 nodes
//   meshcli --port COM5 listen                  # live messages / packets until Ctrl-C
//   meshcli --port COM5 send "hello" [--to !aabbccdd] [--ch 0]
//   meshcli --port COM5 traceroute !aabbccdd
//   meshcli --tcp 192.168.1.50[:4403] nodes
#include "mesh_state.h"
#include "mesh_stream.h"
#include "meshtastic/portnums.pb.h"
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <iostream>
#include <memory>
#include <random>
#include <string>

namespace {

std::string ago(uint32_t epoch) {
    if (epoch == 0) return "never";
    long s = (long)std::time(nullptr) - (long)epoch;
    if (s < 0) s = 0;
    char b[32];
    if (s < 60) snprintf(b, sizeof b, "%lds", s);
    else if (s < 3600) snprintf(b, sizeof b, "%ldm", s / 60);
    else if (s < 86400) snprintf(b, sizeof b, "%ldh%02ldm", s / 3600, (s % 3600) / 60);
    else snprintf(b, sizeof b, "%ldd%02ldh", s / 86400, (s % 86400) / 3600);
    return b;
}

std::string now_str() {
    std::time_t t = std::time(nullptr);
    char b[32];
    std::strftime(b, sizeof b, "%H:%M:%S", std::localtime(&t));
    return b;
}

uint32_t parse_node(const std::string& s) {
    std::string h = s;
    if (!h.empty() && h[0] == '!') h = h.substr(1);
    return (uint32_t)std::strtoul(h.c_str(), nullptr, 16);
}

uint32_t random_id() {
    static std::mt19937 rng((unsigned)std::chrono::steady_clock::now().time_since_epoch().count());
    uint32_t id;
    do { id = rng(); } while (id == 0);
    return id;
}

constexpr const char* VERSION = "1.4.3";
constexpr const char* COPYRIGHT = "Copyright (c) 2026 Mesh Console contributors. MIT License.";   // <- put your name here

void usage() {
    std::fprintf(stderr, "meshcli %s - %s\n", VERSION, COPYRIGHT);
    std::fprintf(stderr,
        "usage: meshcli (--port <serial> | --tcp <host[:port]>) <command>\n"
        "  info                       radio + own node summary\n"
        "  nodes                      node table\n"
        "  listen                     print messages and events until Ctrl-C\n"
        "  send <text> [--to !id] [--ch n]   send text (waits for ack)\n"
        "  traceroute !id             route + per-hop SNR\n");
}

} // namespace

int main(int argc, char** argv) {
    GOOGLE_PROTOBUF_VERIFY_VERSION;
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::string port, tcp, cmd, text, to = "!ffffffff";
    int ch = 0;
    std::vector<std::string> args(argv + 1, argv + argc);
    for (size_t i = 0; i < args.size(); i++) {
        const std::string& a = args[i];
        auto next = [&](std::string& dst) { if (i + 1 < args.size()) dst = args[++i]; };
        if (a == "--version") { std::printf("meshcli %s\n%s\n", VERSION, COPYRIGHT); return 0; }
        if (a == "--port") next(port);
        else if (a == "--tcp") next(tcp);
        else if (a == "--to") next(to);
        else if (a == "--ch") { std::string s; next(s); ch = std::atoi(s.c_str()); }
        else if (cmd.empty()) cmd = a;
        else if (cmd == "send" && text.empty()) text = a;
        else if (cmd == "traceroute") to = a;
    }
    if ((port.empty() && tcp.empty()) || cmd.empty() || (cmd == "send" && text.empty())) { usage(); return 2; }

    std::unique_ptr<ByteStream> io;
    try {
        if (!port.empty()) io = std::make_unique<SerialPort>(port);
        else {
            std::string host = tcp; int p = 4403;
            auto c = tcp.rfind(':');
            if (c != std::string::npos) { host = tcp.substr(0, c); p = std::atoi(tcp.substr(c + 1).c_str()); }
            io = std::make_unique<TcpStream>(host, p);
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }

    MeshState state;
    MeshStream stream(std::move(io));
    std::mutex mu;
    std::condition_variable cv;
    bool done = false, connected = true;
    uint32_t waiting_for_id = 0;
    bool verbose = cmd == "listen";

    state.on_log = [&](const std::string& s) { if (verbose || s.rfind("traceroute", 0) == 0) std::printf("%s  %s\n", now_str().c_str(), s.c_str()); if (s.rfind("traceroute", 0) == 0) { std::lock_guard<std::mutex> l(mu); done = true; cv.notify_all(); } };
    state.on_message = [&](const ChatMessage& m) {
        if (!verbose) return;
        std::printf("%s  %s -> %s [ch %u]  %s   (rssi %d  snr %.1f  hops %d)\n", now_str().c_str(),
                    state.node_name(m.from).c_str(), state.node_name(m.to).c_str(), m.channel, m.text.c_str(), m.rssi, m.snr, m.hops);
    };
    state.on_ack = [&](uint32_t id, uint32_t from, meshtastic::Routing_Error err) {
        if (id != waiting_for_id) return;
        bool from_me = from == state.my_node_num();
        if (err == meshtastic::Routing_Error_NONE) {
            if (from_me) std::printf("%s  radio transmitted the packet%s\n", now_str().c_str(), parse_node(to) == 0xFFFFFFFF ? " (broadcast, done)" : ", waiting for delivery ack…");
            else std::printf("%s  DELIVERED to %s\n", now_str().c_str(), state.node_name(from).c_str());
            if (!from_me || parse_node(to) == 0xFFFFFFFF) { std::lock_guard<std::mutex> l(mu); done = true; cv.notify_all(); }
        } else {
            std::printf("%s  FAILED: %s (from %s)\n", now_str().c_str(), meshtastic::Routing_Error_Name(err).c_str(), state.node_name(from).c_str());
            std::lock_guard<std::mutex> l(mu); done = true; cv.notify_all();
        }
    };
    state.on_config_complete = [&] { cv.notify_all(); };
    stream.on_from_radio = [&](const meshtastic::FromRadio& fr) { state.handle(fr); };
    stream.on_text = [&](const std::string& s) { if (verbose) std::printf("%s  [serial] %s\n", now_str().c_str(), s.c_str()); };
    stream.on_disconnect = [&](const std::string& why) { std::fprintf(stderr, "disconnected: %s\n", why.c_str()); std::lock_guard<std::mutex> l(mu); connected = false; done = true; cv.notify_all(); };

    stream.start(random_id() & 0x7FFFFFFF);
    {
        std::unique_lock<std::mutex> l(mu);
        if (!cv.wait_for(l, std::chrono::seconds(15), [&] { return state.config_complete() || !connected; }) || !connected) {
            std::fprintf(stderr, "error: radio did not send its config (is it a Meshtastic device? is another client using the port?)\n");
            return 1;
        }
    }

    if (cmd == "info") {
        auto nodes = state.nodes();
        std::printf("node       %s  %s\n", format_node_id(state.my_node_num()).c_str(), state.node_name(state.my_node_num()).c_str());
        std::printf("firmware   %s\n", state.firmware().c_str());
        std::printf("hop limit  %d\n", state.hop_limit());
        std::printf("nodes      %zu known\n", nodes.size());
        for (auto& n : nodes) if (n.num == state.my_node_num()) {
            std::printf("hardware   %s\n", n.hw_model.c_str());
            if (n.battery >= 0) std::printf("battery    %s (%.2f V)\n", n.battery > 100 ? "external" : (std::to_string(n.battery) + "%").c_str(), n.voltage);
            std::printf("ch util    %.1f%%  air-util tx %.2f%%\n", n.channel_util, n.air_util_tx);
            if (n.has_position) std::printf("position   %.5f, %.5f  alt %d m\n", n.lat, n.lon, n.altitude);
        }
    } else if (cmd == "nodes") {
        std::printf("%-10s %-22s %-5s %-8s %-5s %-7s %-8s %-5s %s\n", "id", "name", "short", "heard", "hops", "snr", "rssi", "batt", "position");
        for (auto& n : state.nodes()) {
            bool me = n.num == state.my_node_num();
            char pos[48] = "";
            if (n.has_position) snprintf(pos, sizeof pos, "%.4f, %.4f", n.lat, n.lon);
            std::printf("%-10s %-22.22s %-5s %-8s %-5s %-7s %-8s %-5s %s\n", n.id().c_str(), (me ? "* " + n.name() : n.name()).c_str(), n.short_name.c_str(),
                        me ? "-" : ago(n.last_heard).c_str(),
                        me ? "" : n.hops_away < 0 ? "?" : n.hops_away == 0 ? "direct" : std::to_string(n.hops_away).c_str(),
                        me || n.snr == 0 ? "" : (std::to_string(n.snr).substr(0, 5) + "dB").c_str(),
                        me || n.rssi == 0 ? "" : (std::to_string(n.rssi) + "dBm").c_str(),
                        n.battery < 0 ? "" : n.battery > 100 ? "ext" : (std::to_string(n.battery) + "%").c_str(), pos);
        }
    } else if (cmd == "listen") {
        std::printf("listening as %s — Ctrl-C to stop\n", state.node_name(state.my_node_num()).c_str());
        std::unique_lock<std::mutex> l(mu);
        cv.wait(l, [&] { return !connected; });
    } else if (cmd == "send" || cmd == "traceroute") {
        uint32_t dest = parse_node(to);
        meshtastic::ToRadio tr;
        auto* pkt = tr.mutable_packet();
        pkt->set_to(dest);
        pkt->set_channel(cmd == "send" ? ch : 0);
        pkt->set_id(random_id());
        pkt->set_want_ack(true);
        pkt->set_hop_limit(state.hop_limit());
        auto* d = pkt->mutable_decoded();
        if (cmd == "send") {
            d->set_portnum(meshtastic::TEXT_MESSAGE_APP);
            d->set_payload(text);
            if (dest != 0xFFFFFFFF && ch == 0 && state.has_public_key(dest)) pkt->set_pki_encrypted(true);
        } else {
            d->set_portnum(meshtastic::TRACEROUTE_APP);
            d->set_payload(meshtastic::RouteDiscovery().SerializeAsString());
            d->set_want_response(true);
        }
        waiting_for_id = pkt->id();
        if (!stream.send(tr)) { std::fprintf(stderr, "error: send failed\n"); return 1; }
        std::printf("%s  sent to %s (id %08x), waiting…\n", now_str().c_str(), state.node_name(dest).c_str(), pkt->id());
        std::unique_lock<std::mutex> l(mu);
        if (!cv.wait_for(l, std::chrono::seconds(cmd == "send" ? 60 : 120), [&] { return done; }))
            std::printf("%s  no response within timeout\n", now_str().c_str());
    } else {
        usage();
        return 2;
    }
    stream.stop();
    return 0;
}
