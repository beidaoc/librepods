#include <linux/input.h>
#include <linux/uhid.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iterator>
#include <optional>
#include <poll.h>
#include <signal.h>
#include <string>
#include <unistd.h>

namespace {

using SteadyClock = std::chrono::steady_clock;

constexpr char kDeviceName[] = "LibrePods Virtual Head Tracker";
constexpr char kSensorDescription[] = "#AndroidHeadTracker#1.0";
constexpr double kPi = 3.14159265358979323846;

// Android Head Tracker HID v1 descriptor from the AOSP protocol specification.
// Report 2 contains the read-only description and persistent unique ID.
// Report 1 contains controls and the pose input data.
constexpr uint8_t kReportDescriptor[] = {
        0x05, 0x20,              // Usage Page (Sensors)
        0x09, 0xE1,              // Usage (Other: Custom)
        0xA1, 0x01,              // Collection (Application)

        0x85, 0x02,              //   Report ID (2)
        0x0A, 0x08, 0x03,        //   Sensor Description
        0x15, 0x00,              //   Logical Minimum (0)
        0x25, 0xFF,              //   Logical Maximum (255)
        0x75, 0x08,              //   Report Size (8)
        0x95, 0x17,              //   Report Count (23)
        0xB1, 0x03,              //   Feature (Constant, Variable, Absolute)

        0x0A, 0x02, 0x03,        //   Persistent Unique ID
        0x15, 0x00,
        0x25, 0xFF,
        0x75, 0x08,
        0x95, 0x10,              //   Report Count (16)
        0xB1, 0x03,

        0x85, 0x01,              //   Report ID (1)
        0x0A, 0x16, 0x03,        //   Reporting State
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95, 0x01,
        0xA1, 0x02,              //   Collection (Logical)
        0x0A, 0x40, 0x08,        //     No Events
        0x0A, 0x41, 0x08,        //     All Events
        0xB1, 0x00,              //     Feature (Data, Array, Absolute)
        0xC0,

        0x0A, 0x19, 0x03,        //   Power State
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95, 0x01,
        0xA1, 0x02,
        0x0A, 0x55, 0x08,        //     D4 Power Off
        0x0A, 0x51, 0x08,        //     D0 Full Power
        0xB1, 0x00,
        0xC0,

        0x0A, 0x0E, 0x03,        //   Report Interval
        0x15, 0x00,
        0x25, 0x3F,
        0x35, 0x0A,              //   Physical Minimum (10 ms)
        0x45, 0x64,              //   Physical Maximum (100 ms)
        0x75, 0x06,
        0x95, 0x01,
        0x66, 0x01, 0x10,        //   Unit (Second)
        0x55, 0x0D,              //   Unit Exponent (-3)
        0xB1, 0x02,              //   Feature (Data, Variable, Absolute)

        0x0A, 0x44, 0x05,        //   Custom Value 1: orientation
        0x16, 0x01, 0x80,        //   Logical Minimum (-32767)
        0x26, 0xFF, 0x7F,        //   Logical Maximum (32767)
        0x37, 0x60, 0x4F, 0x46, 0xED,  // Physical Minimum (-pi * 1e8)
        0x47, 0xA1, 0xB0, 0xB9, 0x12,  // Physical Maximum ( pi * 1e8)
        0x55, 0x08,              //   Unit Exponent (-8)
        0x75, 0x10,
        0x95, 0x03,
        0x81, 0x02,              //   Input (Data, Variable, Absolute)

        0x0A, 0x45, 0x05,        //   Custom Value 2: angular velocity
        0x16, 0x01, 0x80,
        0x26, 0xFF, 0x7F,
        0x35, 0xE0,              //   Physical Minimum (-32 rad/s)
        0x45, 0x20,              //   Physical Maximum ( 32 rad/s)
        0x55, 0x00,
        0x75, 0x10,
        0x95, 0x03,
        0x81, 0x02,

        0x0A, 0x46, 0x05,        //   Custom Value 3: discontinuity counter
        0x16, 0x00, 0x00,
        0x26, 0xFF, 0x00,
        0x35, 0x00,
        0x45, 0x00,
        0x55, 0x00,
        0x75, 0x08,
        0x95, 0x01,
        0x81, 0x02,

        0xC0                    // End Collection
};

static_assert(sizeof(kSensorDescription) - 1 == 23);
static_assert(kReportDescriptor[std::size(kReportDescriptor) - 1] == 0xC0);

std::atomic_bool gRunning{true};

void onSignal(int) {
    gRunning = false;
}

bool writeEvent(int fd, const uhid_event& event) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(&event);
    size_t remaining = sizeof(event);
    while (remaining > 0) {
        const ssize_t written = write(fd, bytes + sizeof(event) - remaining, remaining);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) {
            std::fprintf(stderr, "UHID write failed: %s\n", std::strerror(errno));
            return false;
        }
        remaining -= static_cast<size_t>(written);
    }
    return true;
}

bool parseMac(const char* text, std::array<uint8_t, 6>* mac) {
    unsigned values[6]{};
    if (std::sscanf(text, "%02x:%02x:%02x:%02x:%02x:%02x",
                    &values[0], &values[1], &values[2],
                    &values[3], &values[4], &values[5]) != 6) {
        return false;
    }
    for (size_t i = 0; i < mac->size(); ++i) {
        (*mac)[i] = static_cast<uint8_t>(values[i]);
    }
    return true;
}

class VirtualHeadTracker {
public:
    explicit VirtualHeadTracker(std::array<uint8_t, 6> mac) : mac_(mac) {
        // Android's Bluetooth association UUID format: 8 zero bytes, "BT", then MAC.
        uniqueId_.fill(0);
        uniqueId_[8] = 'B';
        uniqueId_[9] = 'T';
        std::copy(mac_.begin(), mac_.end(), uniqueId_.begin() + 10);
    }

    ~VirtualHeadTracker() {
        destroy();
    }

    bool openAndCreate() {
        fd_ = open("/dev/uhid", O_RDWR | O_CLOEXEC);
        if (fd_ < 0) {
            std::fprintf(stderr, "Cannot open /dev/uhid: %s\n", std::strerror(errno));
            return false;
        }

        uhid_event event{};
        event.type = UHID_CREATE2;
        std::snprintf(reinterpret_cast<char*>(event.u.create2.name),
                      sizeof(event.u.create2.name), "%s", kDeviceName);
        std::snprintf(reinterpret_cast<char*>(event.u.create2.phys),
                      sizeof(event.u.create2.phys), "librepods/uhid-headtracker");
        std::snprintf(reinterpret_cast<char*>(event.u.create2.uniq),
                      sizeof(event.u.create2.uniq),
                      "%02X:%02X:%02X:%02X:%02X:%02X",
                      mac_[0], mac_[1], mac_[2], mac_[3], mac_[4], mac_[5]);
        event.u.create2.rd_size = static_cast<uint16_t>(std::size(kReportDescriptor));
        event.u.create2.bus = BUS_BLUETOOTH;
        event.u.create2.vendor = 0x004C;  // Apple Bluetooth company identifier.
        event.u.create2.product = 0x200E;
        event.u.create2.version = 1;
        std::copy(std::begin(kReportDescriptor), std::end(kReportDescriptor),
                  event.u.create2.rd_data);

        if (!writeEvent(fd_, event)) return false;
        created_ = true;
        std::fprintf(stderr,
                     "Created %s for %02X:%02X:%02X:%02X:%02X:%02X\n",
                     kDeviceName, mac_[0], mac_[1], mac_[2], mac_[3], mac_[4], mac_[5]);
        return true;
    }

    int run(bool stdinMode) {
        const auto started = SteadyClock::now();
        auto nextPose = started;
        auto nextLog = started;

        while (gRunning) {
            const auto now = SteadyClock::now();
            int timeoutMs = 1000;
            if (shouldReport() && (!stdinMode || latestPose_.has_value())) {
                timeoutMs = static_cast<int>(std::max<int64_t>(
                        0, std::chrono::duration_cast<std::chrono::milliseconds>(nextPose - now).count()));
            }

            std::array<pollfd, 2> pollFds{{
                    {fd_, POLLIN, 0},
                    {stdinMode ? STDIN_FILENO : -1, POLLIN, 0},
            }};
            const nfds_t pollCount = stdinMode ? 2 : 1;
            const int result = poll(pollFds.data(), pollCount, timeoutMs);
            if (result < 0 && errno == EINTR) continue;
            if (result < 0) {
                std::fprintf(stderr, "UHID poll failed: %s\n", std::strerror(errno));
                return 1;
            }
            if (result > 0 && (pollFds[0].revents & POLLIN)) {
                if (!readAndHandleEvent()) return 1;
            }
            if (stdinMode && result > 0 && (pollFds[1].revents & (POLLIN | POLLHUP))) {
                if (!readPoseInput()) return 1;
            }

            const auto afterPoll = SteadyClock::now();
            if (stdinMode && shouldReport() && latestPose_.has_value() && afterPoll >= nextPose) {
                const PoseSample& pose = *latestPose_;
                const auto ageMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                        afterPoll - pose.receivedAt).count();
                const bool stale = ageMs > std::max<int64_t>(60, reportIntervalMs() * 3L);
                if (!sendPose(pose.rx, pose.ry, pose.rz,
                              stale ? 0.0 : pose.vx,
                              stale ? 0.0 : pose.vy,
                              stale ? 0.0 : pose.vz,
                              pose.discontinuityCounter)) return 1;
                ++reportedPoseCount_;
                nextPose = afterPoll + std::chrono::milliseconds(reportIntervalMs());

                if (afterPoll >= nextLog) {
                    std::fprintf(stderr,
                                 "stream poses received=%llu reported=%llu age=%lld ms "
                                 "interval=%d ms stale=%d\n",
                                 static_cast<unsigned long long>(receivedPoseCount_),
                                 static_cast<unsigned long long>(reportedPoseCount_),
                                 static_cast<long long>(ageMs), reportIntervalMs(), stale);
                    nextLog = afterPoll + std::chrono::seconds(2);
                }
            }
            if (!stdinMode && shouldReport() && afterPoll >= nextPose) {
                const double seconds = std::chrono::duration<double>(afterPoll - started).count();
                // A slow +/- 0.6 rad yaw motion, rotating about the head's Z axis.
                const double yaw = 0.6 * std::sin(seconds * 0.5);
                const double yawVelocity = 0.3 * std::cos(seconds * 0.5);
                if (!sendPose(0.0, 0.0, yaw, 0.0, 0.0, yawVelocity,
                              discontinuityCounter_)) return 1;
                nextPose = afterPoll + std::chrono::milliseconds(reportIntervalMs());

                if (afterPoll >= nextLog) {
                    std::fprintf(stderr, "pose yaw=%+.3f rad interval=%d ms\n",
                                 yaw, reportIntervalMs());
                    nextLog = afterPoll + std::chrono::seconds(2);
                }
            }
        }
        return 0;
    }

private:
    bool shouldReport() const {
        // The interval field is an index in the logical [0..63] range. Zero is
        // the valid minimum (10 ms), not a disabled sentinel.
        return opened_ && reportingEnabled_ && powerEnabled_;
    }

    int reportIntervalMs() const {
        // Logical [0..63] maps linearly to physical [10..100] ms.
        return 10 + static_cast<int>(reportIntervalRaw_) * 90 / 63;
    }

    bool readAndHandleEvent() {
        uhid_event event{};
        const ssize_t size = read(fd_, &event, sizeof(event));
        if (size < 0 && errno == EINTR) return true;
        if (size <= 0) {
            std::fprintf(stderr, "UHID read failed: %s\n",
                         size == 0 ? "device closed" : std::strerror(errno));
            return false;
        }

        switch (event.type) {
            case UHID_START:
                std::fprintf(stderr, "UHID started, flags=0x%llx\n",
                             static_cast<unsigned long long>(event.u.start.dev_flags));
                break;
            case UHID_STOP:
                std::fprintf(stderr, "UHID stopped\n");
                break;
            case UHID_OPEN:
                opened_ = true;
                std::fprintf(stderr, "hidraw client opened device\n");
                break;
            case UHID_CLOSE:
                opened_ = false;
                std::fprintf(stderr, "hidraw client closed device\n");
                break;
            case UHID_GET_REPORT:
                return replyGetReport(event.u.get_report);
            case UHID_SET_REPORT:
                return handleSetReport(event.u.set_report);
            case UHID_OUTPUT:
                break;
            default:
                break;
        }
        return true;
    }

    bool readPoseInput() {
        std::array<char, 512> buffer{};
        const ssize_t count = read(STDIN_FILENO, buffer.data(), buffer.size());
        if (count < 0 && errno == EINTR) return true;
        if (count < 0) {
            std::fprintf(stderr, "Pose input read failed: %s\n", std::strerror(errno));
            return false;
        }
        if (count == 0) {
            std::fprintf(stderr, "Pose input closed\n");
            gRunning = false;
            return true;
        }

        poseInputBuffer_.append(buffer.data(), static_cast<size_t>(count));
        size_t newline = 0;
        while ((newline = poseInputBuffer_.find('\n')) != std::string::npos) {
            const std::string line = poseInputBuffer_.substr(0, newline);
            poseInputBuffer_.erase(0, newline + 1);

            double rx = 0.0;
            double ry = 0.0;
            double rz = 0.0;
            double vx = 0.0;
            double vy = 0.0;
            double vz = 0.0;
            unsigned counter = discontinuityCounter_;
            const int fields = std::sscanf(line.c_str(), "%lf %lf %lf %lf %lf %lf %u",
                                           &rx, &ry, &rz, &vx, &vy, &vz, &counter);
            if (fields < 6 || !std::isfinite(rx) || !std::isfinite(ry) || !std::isfinite(rz)
                    || !std::isfinite(vx) || !std::isfinite(vy) || !std::isfinite(vz)) {
                std::fprintf(stderr, "Ignoring malformed pose input: %s\n", line.c_str());
                continue;
            }

            discontinuityCounter_ = static_cast<uint8_t>(counter & 0xFF);
            latestPose_ = PoseSample{
                    rx, ry, rz, vx, vy, vz, discontinuityCounter_, SteadyClock::now()};
            ++receivedPoseCount_;
            if (receivedPoseCount_ == 1 || receivedPoseCount_ % 200 == 0) {
                std::fprintf(stderr,
                             "input pose rx=%+.3f ry=%+.3f rz=%+.3f counter=%u\n",
                             rx, ry, rz, discontinuityCounter_);
            }
        }

        if (poseInputBuffer_.size() > 4096) {
            std::fprintf(stderr, "Pose input line exceeded 4096 bytes; dropping it\n");
            poseInputBuffer_.clear();
        }
        return true;
    }

    bool replyGetReport(const uhid_get_report_req& request) {
        uhid_event reply{};
        reply.type = UHID_GET_REPORT_REPLY;
        reply.u.get_report_reply.id = request.id;

        if (request.rtype != UHID_FEATURE_REPORT) {
            reply.u.get_report_reply.err = EIO;
        } else if (request.rnum == 2) {
            reply.u.get_report_reply.data[0] = 2;
            std::memcpy(reply.u.get_report_reply.data + 1,
                        kSensorDescription, sizeof(kSensorDescription) - 1);
            std::copy(uniqueId_.begin(), uniqueId_.end(),
                      reply.u.get_report_reply.data + 1 + sizeof(kSensorDescription) - 1);
            reply.u.get_report_reply.size = 1 + (sizeof(kSensorDescription) - 1) + uniqueId_.size();
        } else if (request.rnum == 1) {
            reply.u.get_report_reply.data[0] = 1;
            reply.u.get_report_reply.data[1] = controlByte();
            reply.u.get_report_reply.size = 2;
        } else {
            reply.u.get_report_reply.err = EIO;
        }

        return writeEvent(fd_, reply);
    }

    bool handleSetReport(const uhid_set_report_req& request) {
        if (request.rtype == UHID_FEATURE_REPORT && request.rnum == 1 && request.size > 0) {
            size_t offset = request.data[0] == 1 && request.size > 1 ? 1 : 0;
            const uint8_t control = request.data[offset];
            reportingEnabled_ = (control & 0x01) != 0;
            powerEnabled_ = (control & 0x02) != 0;
            reportIntervalRaw_ = static_cast<uint8_t>((control >> 2) & 0x3F);
            std::fprintf(stderr,
                         "control reporting=%d power=%d intervalRaw=%u (%d ms)\n",
                         reportingEnabled_, powerEnabled_, reportIntervalRaw_, reportIntervalMs());
        }

        uhid_event reply{};
        reply.type = UHID_SET_REPORT_REPLY;
        reply.u.set_report_reply.id = request.id;
        reply.u.set_report_reply.err = 0;
        return writeEvent(fd_, reply);
    }

    uint8_t controlByte() const {
        return static_cast<uint8_t>((reportingEnabled_ ? 1 : 0)
                                    | (powerEnabled_ ? 2 : 0)
                                    | ((reportIntervalRaw_ & 0x3F) << 2));
    }

    static int16_t orientationRaw(double radians) {
        const double clamped = std::clamp(radians, -kPi, kPi);
        return static_cast<int16_t>(std::lround(clamped / kPi * 32767.0));
    }

    static int16_t velocityRaw(double radiansPerSecond) {
        const double clamped = std::clamp(radiansPerSecond, -32.0, 32.0);
        return static_cast<int16_t>(std::lround(clamped / 32.0 * 32767.0));
    }

    static void putInt16(uint8_t* destination, int16_t value) {
        destination[0] = static_cast<uint8_t>(value & 0xFF);
        destination[1] = static_cast<uint8_t>((static_cast<uint16_t>(value) >> 8) & 0xFF);
    }

    bool sendPose(double rx, double ry, double rz,
                  double vx, double vy, double vz,
                  uint8_t discontinuityCounter) {
        uhid_event event{};
        event.type = UHID_INPUT2;
        event.u.input2.size = 14;
        uint8_t* data = event.u.input2.data;
        data[0] = 1;
        putInt16(data + 1, orientationRaw(rx));
        putInt16(data + 3, orientationRaw(ry));
        putInt16(data + 5, orientationRaw(rz));
        putInt16(data + 7, velocityRaw(vx));
        putInt16(data + 9, velocityRaw(vy));
        putInt16(data + 11, velocityRaw(vz));
        data[13] = discontinuityCounter;
        return writeEvent(fd_, event);
    }

    void destroy() {
        if (fd_ < 0) return;
        if (created_) {
            uhid_event event{};
            event.type = UHID_DESTROY;
            writeEvent(fd_, event);
        }
        close(fd_);
        fd_ = -1;
        created_ = false;
    }

    int fd_ = -1;
    bool created_ = false;
    bool opened_ = false;
    bool reportingEnabled_ = false;
    bool powerEnabled_ = true;
    uint8_t reportIntervalRaw_ = 7;  // 20 ms after physical scaling.
    uint8_t discontinuityCounter_ = 0;
    uint64_t receivedPoseCount_ = 0;
    uint64_t reportedPoseCount_ = 0;
    std::string poseInputBuffer_;
    struct PoseSample {
        double rx;
        double ry;
        double rz;
        double vx;
        double vy;
        double vz;
        uint8_t discontinuityCounter;
        SteadyClock::time_point receivedAt;
    };
    std::optional<PoseSample> latestPose_;
    std::array<uint8_t, 6> mac_{};
    std::array<uint8_t, 16> uniqueId_{};
};

void printUsage(const char* argv0) {
    std::fprintf(stderr,
                 "Usage: %s --mac XX:XX:XX:XX:XX:XX [--stdin]\n"
                 "  --stdin reads: rx ry rz vx vy vz [discontinuity]\\n\n",
                 argv0);
}

}  // namespace

int main(int argc, char** argv) {
    std::array<uint8_t, 6> mac{};
    bool hasMac = false;
    bool stdinMode = false;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--mac") == 0 && i + 1 < argc) {
            hasMac = parseMac(argv[++i], &mac);
        } else if (std::strcmp(argv[i], "--stdin") == 0) {
            stdinMode = true;
        } else if (std::strcmp(argv[i], "--help") == 0) {
            printUsage(argv[0]);
            return 0;
        } else {
            printUsage(argv[0]);
            return 2;
        }
    }
    if (!hasMac) {
        printUsage(argv[0]);
        return 2;
    }

    signal(SIGINT, onSignal);
    signal(SIGTERM, onSignal);

    VirtualHeadTracker tracker(mac);
    if (!tracker.openAndCreate()) return 1;
    return tracker.run(stdinMode);
}
