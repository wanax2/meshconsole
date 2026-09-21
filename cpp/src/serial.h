#pragma once
#include <string>
#include <cstdint>
#include <cstddef>

/**
 * Minimal cross-platform byte stream: a serial port (POSIX termios / Win32) or a TCP socket.
 * read() blocks for at most timeout_ms and returns the number of bytes read (0 on timeout,
 * -1 when the connection is gone).
 */
class ByteStream {
public:
    virtual ~ByteStream() = default;
    virtual int read(uint8_t* buf, size_t len, int timeout_ms) = 0;
    virtual bool write(const uint8_t* buf, size_t len) = 0;
    virtual void close() = 0;
};

class SerialPort : public ByteStream {
public:
    /** name: "/dev/ttyACM0" or "COM5". Throws std::runtime_error on failure. */
    explicit SerialPort(const std::string& name, int baud = 115200);
    ~SerialPort() override;
    int read(uint8_t* buf, size_t len, int timeout_ms) override;
    bool write(const uint8_t* buf, size_t len) override;
    void close() override;
private:
#ifdef _WIN32
    void* handle_ = nullptr;
#else
    int fd_ = -1;
#endif
};

class TcpStream : public ByteStream {
public:
    TcpStream(const std::string& host, int port);
    ~TcpStream() override;
    int read(uint8_t* buf, size_t len, int timeout_ms) override;
    bool write(const uint8_t* buf, size_t len) override;
    void close() override;
private:
    intptr_t sock_ = -1;
};
