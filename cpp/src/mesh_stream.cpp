#include "mesh_stream.h"
#include <chrono>
#include <vector>

static constexpr uint8_t START1 = 0x94, START2 = 0xC3;
static constexpr size_t MAX_LEN = 512;

MeshStream::MeshStream(std::unique_ptr<ByteStream> io) : io_(std::move(io)) {}
MeshStream::~MeshStream() { stop(); }

void MeshStream::start(uint32_t config_nonce) {
    running_ = true;
    reader_ = std::thread(&MeshStream::read_loop, this);
    // wake bytes, then ask for config (same as the reference clients)
    std::vector<uint8_t> wake(32, START2);
    { std::lock_guard<std::mutex> l(write_mutex_); io_->write(wake.data(), wake.size()); }
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    meshtastic::ToRadio tr;
    tr.set_want_config_id(config_nonce);
    send(tr);
    heartbeat_ = std::thread(&MeshStream::heartbeat_loop, this);
}

void MeshStream::stop() {
    if (!running_) return;
    running_ = false;
    meshtastic::ToRadio bye;
    bye.set_disconnect(true);
    send(bye);
    io_->close();
    if (heartbeat_.joinable()) heartbeat_.join();
    if (reader_.joinable() && reader_.get_id() != std::this_thread::get_id()) reader_.join();
}

bool MeshStream::send(const meshtastic::ToRadio& msg) {
    std::string payload;
    if (!msg.SerializeToString(&payload) || payload.size() > MAX_LEN) return false;
    std::vector<uint8_t> frame;
    frame.reserve(payload.size() + 4);
    frame.push_back(START1);
    frame.push_back(START2);
    frame.push_back((uint8_t)(payload.size() >> 8));
    frame.push_back((uint8_t)(payload.size() & 0xFF));
    frame.insert(frame.end(), payload.begin(), payload.end());
    std::lock_guard<std::mutex> l(write_mutex_);
    return io_->write(frame.data(), frame.size());
}

void MeshStream::heartbeat_loop() {
    int ticks = 0;
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        if (++ticks >= 240) {          // 60 s
            ticks = 0;
            meshtastic::ToRadio hb;
            hb.mutable_heartbeat();
            if (!send(hb)) fail("heartbeat write failed");
        }
    }
}

void MeshStream::read_loop() {
    uint8_t buf[4096];
    std::string payload, text;
    int state = 0;
    size_t len = 0;
    while (running_) {
        int n = io_->read(buf, sizeof buf, 250);
        if (n < 0) { if (running_) fail("read failed / port closed"); return; }
        for (int i = 0; i < n; i++) {
            uint8_t b = buf[i];
            switch (state) {
            case 0:
                if (b == START1) state = 1;
                else if (b == '\n') { if (!text.empty() && on_text) on_text(text); text.clear(); }
                else if (b != '\r') { text.push_back((char)b); if (text.size() > 512) { if (on_text) on_text(text); text.clear(); } }
                break;
            case 1:
                if (b == START2) state = 2;
                else if (b == START1) state = 1;
                else { state = 0; if (b != '\r' && b != '\n') text.push_back((char)b); }
                break;
            case 2: len = (size_t)b << 8; state = 3; break;
            case 3:
                len |= b;
                if (len == 0 || len > MAX_LEN) state = 0;
                else { payload.clear(); state = 4; }
                break;
            case 4:
                payload.push_back((char)b);
                if (payload.size() == len) {
                    state = 0;
                    meshtastic::FromRadio fr;
                    if (fr.ParseFromString(payload)) { if (on_from_radio) on_from_radio(fr); }
                    else if (on_text) on_text("[bad frame]");
                }
                break;
            }
        }
    }
}

void MeshStream::fail(const std::string& why) {
    bool was = running_.exchange(false);
    if (was && on_disconnect) on_disconnect(why);
}
