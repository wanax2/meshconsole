#include "serial.h"
#include <stdexcept>
#include <cstring>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <fcntl.h>
#include <termios.h>
#include <unistd.h>
#include <poll.h>
#include <sys/socket.h>
#include <netdb.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <cerrno>
#endif

// ---------------------------------------------------------------- SerialPort

#ifdef _WIN32
SerialPort::SerialPort(const std::string& name, int baud) {
    std::string path = name.rfind("\\\\.\\", 0) == 0 ? name : "\\\\.\\" + name;
    HANDLE h = CreateFileA(path.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) throw std::runtime_error("cannot open " + name + " (in use or missing?)");
    DCB dcb{}; dcb.DCBlength = sizeof(dcb);
    GetCommState(h, &dcb);
    dcb.BaudRate = baud; dcb.ByteSize = 8; dcb.StopBits = ONESTOPBIT; dcb.Parity = NOPARITY;
    dcb.fBinary = TRUE; dcb.fDtrControl = DTR_CONTROL_ENABLE; dcb.fRtsControl = RTS_CONTROL_ENABLE;
    dcb.fOutxCtsFlow = FALSE; dcb.fOutxDsrFlow = FALSE; dcb.fOutX = FALSE; dcb.fInX = FALSE;
    if (!SetCommState(h, &dcb)) { CloseHandle(h); throw std::runtime_error("SetCommState failed on " + name); }
    COMMTIMEOUTS to{}; to.ReadIntervalTimeout = MAXDWORD; to.ReadTotalTimeoutMultiplier = MAXDWORD;
    to.ReadTotalTimeoutConstant = 100; to.WriteTotalTimeoutConstant = 2000;
    SetCommTimeouts(h, &to);
    PurgeComm(h, PURGE_RXCLEAR | PURGE_TXCLEAR);
    handle_ = h;
}
SerialPort::~SerialPort() { close(); }
int SerialPort::read(uint8_t* buf, size_t len, int timeout_ms) {
    if (!handle_) return -1;
    COMMTIMEOUTS to{}; to.ReadIntervalTimeout = MAXDWORD; to.ReadTotalTimeoutMultiplier = MAXDWORD;
    to.ReadTotalTimeoutConstant = timeout_ms; to.WriteTotalTimeoutConstant = 2000;
    SetCommTimeouts((HANDLE)handle_, &to);
    DWORD n = 0;
    if (!ReadFile((HANDLE)handle_, buf, (DWORD)len, &n, nullptr)) return -1;
    return (int)n;
}
bool SerialPort::write(const uint8_t* buf, size_t len) {
    DWORD n = 0;
    return handle_ && WriteFile((HANDLE)handle_, buf, (DWORD)len, &n, nullptr) && n == len;
}
void SerialPort::close() { if (handle_) { CloseHandle((HANDLE)handle_); handle_ = nullptr; } }
#else
SerialPort::SerialPort(const std::string& name, int baud) {
    int fd = ::open(name.c_str(), O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) throw std::runtime_error("cannot open " + name + ": " + strerror(errno) + " (are you in the dialout group?)");
    termios t{};
    if (tcgetattr(fd, &t) != 0) { ::close(fd); throw std::runtime_error("tcgetattr failed"); }
    cfmakeraw(&t);
    speed_t sp = B115200;
    if (baud == 9600) sp = B9600; else if (baud == 57600) sp = B57600; else if (baud == 921600) sp = B921600;
    cfsetispeed(&t, sp); cfsetospeed(&t, sp);
    t.c_cflag |= CLOCAL | CREAD; t.c_cflag &= ~CRTSCTS;
    t.c_cc[VMIN] = 0; t.c_cc[VTIME] = 0;
    if (tcsetattr(fd, TCSANOW, &t) != 0) { ::close(fd); throw std::runtime_error("tcsetattr failed"); }
    tcflush(fd, TCIOFLUSH);
    fd_ = fd;
}
SerialPort::~SerialPort() { close(); }
int SerialPort::read(uint8_t* buf, size_t len, int timeout_ms) {
    if (fd_ < 0) return -1;
    pollfd p{fd_, POLLIN, 0};
    int r = poll(&p, 1, timeout_ms);
    if (r < 0) return errno == EINTR ? 0 : -1;
    if (r == 0) return 0;
    if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    ssize_t n = ::read(fd_, buf, len);
    if (n < 0) return (errno == EAGAIN || errno == EINTR) ? 0 : -1;
    if (n == 0) return -1;
    return (int)n;
}
bool SerialPort::write(const uint8_t* buf, size_t len) {
    if (fd_ < 0) return false;
    size_t done = 0;
    while (done < len) {
        ssize_t n = ::write(fd_, buf + done, len - done);
        if (n < 0) { if (errno == EAGAIN || errno == EINTR) { usleep(1000); continue; } return false; }
        done += (size_t)n;
    }
    return true;
}
void SerialPort::close() { if (fd_ >= 0) { ::close(fd_); fd_ = -1; } }
#endif

// ---------------------------------------------------------------- TcpStream

TcpStream::TcpStream(const std::string& host, int port) {
#ifdef _WIN32
    static bool wsa = false;
    if (!wsa) { WSADATA d; WSAStartup(MAKEWORD(2, 2), &d); wsa = true; }
#endif
    addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &res) != 0 || !res)
        throw std::runtime_error("cannot resolve " + host);
    intptr_t s = -1;
    for (addrinfo* a = res; a; a = a->ai_next) {
        s = (intptr_t)socket(a->ai_family, a->ai_socktype, a->ai_protocol);
        if (s < 0) continue;
        if (connect((int)s, a->ai_addr, (int)a->ai_addrlen) == 0) break;
#ifdef _WIN32
        closesocket((SOCKET)s);
#else
        ::close((int)s);
#endif
        s = -1;
    }
    freeaddrinfo(res);
    if (s < 0) throw std::runtime_error("cannot connect to " + host + ":" + std::to_string(port));
    int one = 1;
    setsockopt((int)s, IPPROTO_TCP, TCP_NODELAY, (const char*)&one, sizeof(one));
    sock_ = s;
}
TcpStream::~TcpStream() { close(); }
int TcpStream::read(uint8_t* buf, size_t len, int timeout_ms) {
    if (sock_ < 0) return -1;
    fd_set fds; FD_ZERO(&fds); FD_SET((int)sock_, &fds);
    timeval tv{timeout_ms / 1000, (timeout_ms % 1000) * 1000};
    int r = select((int)sock_ + 1, &fds, nullptr, nullptr, &tv);
    if (r < 0) return -1;
    if (r == 0) return 0;
    int n = (int)recv((int)sock_, (char*)buf, (int)len, 0);
    return n <= 0 ? -1 : n;
}
bool TcpStream::write(const uint8_t* buf, size_t len) {
    if (sock_ < 0) return false;
    size_t done = 0;
    while (done < len) {
        int n = (int)send((int)sock_, (const char*)buf + done, (int)(len - done), 0);
        if (n <= 0) return false;
        done += (size_t)n;
    }
    return true;
}
void TcpStream::close() {
    if (sock_ >= 0) {
#ifdef _WIN32
        closesocket((SOCKET)sock_);
#else
        ::close((int)sock_);
#endif
        sock_ = -1;
    }
}
