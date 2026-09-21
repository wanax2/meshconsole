#pragma once
#include "serial.h"
#include "meshtastic/mesh.pb.h"
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

/**
 * Meshtastic stream protocol over any ByteStream:
 *   0x94 0xC3 <len MSB> <len LSB> <protobuf>   (FromRadio in, ToRadio out, len <= 512)
 * Non-framed bytes (firmware debug text) are delivered through on_text.
 */
class MeshStream {
public:
    std::function<void(const meshtastic::FromRadio&)> on_from_radio;
    std::function<void(const std::string&)> on_text;
    std::function<void(const std::string&)> on_disconnect;

    explicit MeshStream(std::unique_ptr<ByteStream> io);
    ~MeshStream();

    /** Starts the reader + heartbeat threads and requests the config/node-db dump. */
    void start(uint32_t config_nonce);
    void stop();
    bool send(const meshtastic::ToRadio& msg);

private:
    void read_loop();
    void heartbeat_loop();
    void fail(const std::string& why);

    std::unique_ptr<ByteStream> io_;
    std::mutex write_mutex_;
    std::atomic<bool> running_{false};
    std::thread reader_, heartbeat_;
};
