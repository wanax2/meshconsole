#pragma once
#include "meshtastic/mesh.pb.h"
#include "meshtastic/telemetry.pb.h"
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <vector>

struct NodeEntry {
    uint32_t num = 0;
    std::string long_name, short_name, hw_model, public_key;
    bool has_position = false;
    double lat = 0, lon = 0;
    int altitude = 0;
    uint32_t last_heard = 0;     // epoch seconds
    float snr = 0;
    int rssi = 0;
    int hops_away = -1;
    int battery = -1;
    float voltage = 0, channel_util = 0, air_util_tx = 0;
    std::string id() const;
    std::string name() const;
};

struct ChatMessage {
    uint32_t from = 0, to = 0, channel = 0, packet_id = 0;
    std::string text;
    int rssi = 0; float snr = 0; int hops = -1;
};

/** Decodes FromRadio into a node DB and events. Thread-safe. */
class MeshState {
public:
    std::function<void(const ChatMessage&)> on_message;
    std::function<void(uint32_t packet_id, uint32_t from, meshtastic::Routing_Error err)> on_ack;
    std::function<void(const std::string&)> on_log;
    std::function<void()> on_config_complete;

    void handle(const meshtastic::FromRadio& fr);

    uint32_t my_node_num() const;
    bool config_complete() const;
    std::string firmware() const;
    std::vector<NodeEntry> nodes() const;             // most recently heard first
    std::string node_name(uint32_t num) const;
    int hop_limit() const;
    bool has_public_key(uint32_t num) const;

private:
    void handle_packet(const meshtastic::MeshPacket& p);
    mutable std::mutex m_;
    std::map<uint32_t, NodeEntry> nodes_;
    uint32_t my_num_ = 0;
    bool complete_ = false;
    std::string firmware_;
    int hop_limit_ = 3;
};

std::string format_node_id(uint32_t num);
