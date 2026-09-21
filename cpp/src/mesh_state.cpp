#include "mesh_state.h"
#include "meshtastic/portnums.pb.h"
#include <cstdio>
#include <ctime>

std::string format_node_id(uint32_t num) { char b[16]; snprintf(b, sizeof b, "!%08x", num); return b; }
std::string NodeEntry::id() const { return format_node_id(num); }
std::string NodeEntry::name() const { return !long_name.empty() ? long_name : !short_name.empty() ? short_name : id(); }

uint32_t MeshState::my_node_num() const { std::lock_guard<std::mutex> l(m_); return my_num_; }
bool MeshState::config_complete() const { std::lock_guard<std::mutex> l(m_); return complete_; }
std::string MeshState::firmware() const { std::lock_guard<std::mutex> l(m_); return firmware_; }
int MeshState::hop_limit() const { std::lock_guard<std::mutex> l(m_); return hop_limit_; }
bool MeshState::has_public_key(uint32_t num) const {
    std::lock_guard<std::mutex> l(m_);
    auto it = nodes_.find(num);
    return it != nodes_.end() && !it->second.public_key.empty();
}

std::vector<NodeEntry> MeshState::nodes() const {
    std::lock_guard<std::mutex> l(m_);
    std::vector<NodeEntry> v;
    for (auto& kv : nodes_) v.push_back(kv.second);
    uint32_t me = my_num_;
    std::sort(v.begin(), v.end(), [me](const NodeEntry& a, const NodeEntry& b) {
        if (a.num == me) return true;
        if (b.num == me) return false;
        return a.last_heard > b.last_heard;
    });
    return v;
}

std::string MeshState::node_name(uint32_t num) const {
    if (num == 0xFFFFFFFF) return "Broadcast";
    std::lock_guard<std::mutex> l(m_);
    auto it = nodes_.find(num);
    return it == nodes_.end() ? format_node_id(num) : it->second.name();
}

static void apply_user(NodeEntry& n, const meshtastic::User& u) {
    n.long_name = u.long_name(); n.short_name = u.short_name();
    n.hw_model = meshtastic::HardwareModel_Name(u.hw_model());
    if (!u.public_key().empty()) n.public_key = u.public_key();
}
static void apply_position(NodeEntry& n, const meshtastic::Position& p) {
    if (p.has_latitude_i() && p.has_longitude_i() && (p.latitude_i() != 0 || p.longitude_i() != 0)) {
        n.lat = p.latitude_i() * 1e-7; n.lon = p.longitude_i() * 1e-7; n.altitude = p.altitude(); n.has_position = true;
    }
}
static void apply_metrics(NodeEntry& n, const meshtastic::DeviceMetrics& m) {
    if (m.has_battery_level()) n.battery = (int)m.battery_level();
    if (m.has_voltage()) n.voltage = m.voltage();
    if (m.has_channel_utilization()) n.channel_util = m.channel_utilization();
    if (m.has_air_util_tx()) n.air_util_tx = m.air_util_tx();
}

void MeshState::handle(const meshtastic::FromRadio& fr) {
    using PV = meshtastic::FromRadio;
    switch (fr.payload_variant_case()) {
    case PV::kMyInfo: { std::lock_guard<std::mutex> l(m_); my_num_ = fr.my_info().my_node_num(); break; }
    case PV::kMetadata: { std::lock_guard<std::mutex> l(m_); firmware_ = fr.metadata().firmware_version(); break; }
    case PV::kConfig:
        if (fr.config().has_lora() && fr.config().lora().hop_limit() > 0) { std::lock_guard<std::mutex> l(m_); hop_limit_ = (int)fr.config().lora().hop_limit(); }
        break;
    case PV::kNodeInfo: {
        const auto& ni = fr.node_info();
        std::lock_guard<std::mutex> l(m_);
        NodeEntry& n = nodes_[ni.num()];
        n.num = ni.num();
        if (ni.has_user()) apply_user(n, ni.user());
        if (ni.has_position()) apply_position(n, ni.position());
        if (ni.snr() != 0) n.snr = ni.snr();
        if (ni.last_heard() != 0) n.last_heard = ni.last_heard();
        if (ni.has_hops_away()) n.hops_away = (int)ni.hops_away();
        if (ni.has_device_metrics()) apply_metrics(n, ni.device_metrics());
        break;
    }
    case PV::kConfigCompleteId: {
        { std::lock_guard<std::mutex> l(m_); complete_ = true; }
        if (on_config_complete) on_config_complete();
        break;
    }
    case PV::kPacket: handle_packet(fr.packet()); break;
    case PV::kLogRecord:
        if (on_log) on_log("[" + meshtastic::LogRecord_Level_Name(fr.log_record().level()) + "] " + fr.log_record().message());
        break;
    default: break;
    }
}

void MeshState::handle_packet(const meshtastic::MeshPacket& p) {
    uint32_t from = p.from();
    int hops = p.hop_start() > 0 ? (int)p.hop_start() - (int)p.hop_limit() : -1;
    bool from_me;
    {
        std::lock_guard<std::mutex> l(m_);
        from_me = from == my_num_;
        NodeEntry& n = nodes_[from];
        n.num = from;
        if (!from_me) {
            n.last_heard = p.has_rx_time() && p.rx_time() ? p.rx_time() : (uint32_t)std::time(nullptr);
            if (p.rx_snr() != 0) n.snr = p.rx_snr();
            if (p.has_rx_rssi() && p.rx_rssi() != 0) n.rssi = p.rx_rssi();
            if (hops >= 0) n.hops_away = hops;
        }
    }
    if (!p.has_decoded()) {
        if (on_log) on_log("encrypted packet from " + node_name(from) + " (channel this radio can't decrypt)");
        return;
    }
    const auto& d = p.decoded();
    switch (d.portnum()) {
    case meshtastic::TEXT_MESSAGE_APP: {
        ChatMessage m;
        m.from = from; m.to = p.to(); m.channel = p.channel(); m.packet_id = p.id(); m.text = d.payload();
        m.rssi = p.has_rx_rssi() ? p.rx_rssi() : 0; m.snr = p.rx_snr(); m.hops = hops;
        if (on_message) on_message(m);
        break;
    }
    case meshtastic::POSITION_APP: {
        meshtastic::Position pos;
        if (pos.ParseFromString(d.payload())) { std::lock_guard<std::mutex> l(m_); apply_position(nodes_[from], pos); }
        break;
    }
    case meshtastic::NODEINFO_APP: {
        meshtastic::User u;
        if (u.ParseFromString(d.payload())) { std::lock_guard<std::mutex> l(m_); apply_user(nodes_[from], u); }
        break;
    }
    case meshtastic::TELEMETRY_APP: {
        meshtastic::Telemetry t;
        if (t.ParseFromString(d.payload()) && t.has_device_metrics()) { std::lock_guard<std::mutex> l(m_); apply_metrics(nodes_[from], t.device_metrics()); }
        break;
    }
    case meshtastic::ROUTING_APP: {
        meshtastic::Routing r;
        if (r.ParseFromString(d.payload()) && r.variant_case() == meshtastic::Routing::kErrorReason && on_ack)
            on_ack(d.request_id(), from, r.error_reason());
        break;
    }
    case meshtastic::TRACEROUTE_APP: {
        meshtastic::RouteDiscovery rd;
        if (!rd.ParseFromString(d.payload()) || !on_log) break;
        auto snr = [](const auto& list, int i) {
            char b[32];
            if (i < list.size() && list.Get(i) != -128) snprintf(b, sizeof b, " -(%.2f dB)-> ", list.Get(i) / 4.0);
            else snprintf(b, sizeof b, " -(?)-> ");
            return std::string(b);
        };
        std::string s = "traceroute " + node_name(p.to()) + " -> " + node_name(from) + ": " + node_name(p.to());
        for (int i = 0; i < rd.route_size(); i++) s += snr(rd.snr_towards(), i) + node_name(rd.route(i));
        s += snr(rd.snr_towards(), rd.route_size()) + node_name(from);
        if (rd.route_back_size() > 0 || rd.snr_back_size() > 0) {
            s += "  | back: " + node_name(from);
            for (int i = 0; i < rd.route_back_size(); i++) s += snr(rd.snr_back(), i) + node_name(rd.route_back(i));
            s += snr(rd.snr_back(), rd.route_back_size()) + node_name(p.to());
        }
        on_log(s);
        break;
    }
    default: break;
    }
}
