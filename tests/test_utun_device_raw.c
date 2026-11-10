#include "utun_device.h"

#include "core/utun_frame.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

static size_t build_v4(uint8_t *out, size_t payload_len) {
    size_t total = 20 + 8 + payload_len;
    memset(out, 0, total);
    out[0] = 0x45;
    out[2] = (uint8_t)(total >> 8);
    out[3] = (uint8_t)total;
    out[8] = 64;
    out[9] = 17;
    out[12] = 10; out[15] = 2;
    out[16] = 203; out[18] = 113; out[19] = 7;
    out[24] = (uint8_t)((8 + payload_len) >> 8);
    out[25] = (uint8_t)(8 + payload_len);
    return total;
}

static size_t build_v6(uint8_t *out, size_t payload_len) {
    size_t total = 40 + payload_len;
    memset(out, 0, total);
    out[0] = 0x60;
    out[4] = (uint8_t)(payload_len >> 8);
    out[5] = (uint8_t)payload_len;
    out[6] = 17;
    out[7] = 64;
    out[23] = 1;
    out[39] = 2;
    return total;
}

static void open_pair(utun_device_t *device, int *peer) {
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, fds) != 0) {
        perror("socketpair");
        _exit(2);
    }
    utun_device_init(device);
    ok("adopt_ip", utun_device_adopt_ip(device, fds[0], "tun0") == UTUN_DEVICE_OK);
    *peer = fds[1];
}

static void read_adds_family_header(void) {
    utun_device_t device;
    int peer;
    open_pair(&device, &peer);

    uint8_t packet[64];
    size_t packet_len = build_v4(packet, 8);
    ok("peer write v4", write(peer, packet, packet_len) == (ssize_t)packet_len);

    uint8_t frame[UTUN_FRAME_MAX];
    size_t frame_len = 0;
    ok("read v4", utun_device_read_frame(&device, frame, sizeof frame, &frame_len) ==
                  UTUN_DEVICE_OK);
    ok("v4 frame length", frame_len == packet_len + UTUN_FRAME_HEADER_LEN);
    ok("v4 body kept", memcmp(frame + UTUN_FRAME_HEADER_LEN, packet, packet_len) == 0);

    const uint8_t *body = NULL;
    size_t body_len = 0;
    uint8_t address_len = 0;
    ok("v4 frame parses", utun_frame_parse(frame, frame_len, &body, &body_len,
                                           &address_len) == UTUN_FRAME_OK);
    ok("v4 address length", address_len == 4);

    packet_len = build_v6(packet, 12);
    ok("peer write v6", write(peer, packet, packet_len) == (ssize_t)packet_len);
    ok("read v6", utun_device_read_frame(&device, frame, sizeof frame, &frame_len) ==
                  UTUN_DEVICE_OK);
    ok("v6 frame parses", utun_frame_parse(frame, frame_len, &body, &body_len,
                                           &address_len) == UTUN_FRAME_OK);
    ok("v6 address length", address_len == 16);

    ok("nothing left", utun_device_read_frame(&device, frame, sizeof frame, &frame_len) ==
                       UTUN_DEVICE_AGAIN);
    ok("frames counted", device.frames_in == 2);
    utun_device_close(&device);
    close(peer);
}

static void read_marks_a_non_ip_packet(void) {
    utun_device_t device;
    int peer;
    open_pair(&device, &peer);

    uint8_t junk[24];
    memset(junk, 0x11, sizeof junk);
    ok("peer write junk", write(peer, junk, sizeof junk) == (ssize_t)sizeof junk);

    uint8_t frame[UTUN_FRAME_MAX];
    size_t frame_len = 0;
    ok("junk is delivered", utun_device_read_frame(&device, frame, sizeof frame,
                                                   &frame_len) == UTUN_DEVICE_OK);
    const uint8_t *body = NULL;
    size_t body_len = 0;
    uint8_t address_len = 0;
    ok("junk is refused by the parser",
       utun_frame_parse(frame, frame_len, &body, &body_len, &address_len) ==
       UTUN_FRAME_ERR_FAMILY);
    utun_device_close(&device);
    close(peer);
}

static void read_needs_room_for_the_header(void) {
    utun_device_t device;
    int peer;
    open_pair(&device, &peer);
    uint8_t frame[UTUN_FRAME_HEADER_LEN];
    size_t frame_len = 99;
    ok("cap of four bytes is refused",
       utun_device_read_frame(&device, frame, sizeof frame, &frame_len) ==
       UTUN_DEVICE_ERR_ARG);
    ok("length is cleared", frame_len == 0);
    utun_device_close(&device);
    close(peer);
}

static void write_drops_the_family_header(void) {
    utun_device_t device;
    int peer;
    open_pair(&device, &peer);

    uint8_t packet[64];
    size_t packet_len = build_v4(packet, 4);
    uint8_t frame[UTUN_FRAME_MAX];
    ok("header built", utun_frame_header(packet[0], frame) == UTUN_FRAME_OK);
    memcpy(frame + UTUN_FRAME_HEADER_LEN, packet, packet_len);
    ok("write v4", utun_device_write_frame(&device, frame, packet_len +
                                           UTUN_FRAME_HEADER_LEN) == UTUN_DEVICE_OK);

    uint8_t got[128];
    ssize_t n = read(peer, got, sizeof got);
    ok("peer got the bare packet", n == (ssize_t)packet_len &&
                                   memcmp(got, packet, packet_len) == 0);
    ok("bytes out count the packet", device.bytes_out == packet_len);

    ok("header only is refused",
       utun_device_write_frame(&device, frame, UTUN_FRAME_HEADER_LEN) ==
       UTUN_DEVICE_ERR_ARG);
    utun_device_close(&device);
    close(peer);
}

static void family_mode_is_unchanged(void) {
    int fds[2];
    ok("socketpair", socketpair(AF_UNIX, SOCK_DGRAM, 0, fds) == 0);
    utun_device_t device;
    utun_device_init(&device);
    ok("adopt", utun_device_adopt(&device, fds[0], "utun3") == UTUN_DEVICE_OK);

    uint8_t packet[64];
    size_t packet_len = build_v4(packet, 0);
    uint8_t frame[UTUN_FRAME_MAX];
    utun_frame_header(packet[0], frame);
    memcpy(frame + UTUN_FRAME_HEADER_LEN, packet, packet_len);
    ok("write keeps the header", utun_device_write_frame(&device, frame,
                                 packet_len + UTUN_FRAME_HEADER_LEN) == UTUN_DEVICE_OK);
    uint8_t got[128];
    ssize_t n = read(fds[1], got, sizeof got);
    ok("peer got header and packet", n == (ssize_t)(packet_len + UTUN_FRAME_HEADER_LEN) &&
                                     memcmp(got, frame, (size_t)n) == 0);
    utun_device_close(&device);
    close(fds[1]);
}

int main(void) {
    read_adds_family_header();
    read_marks_a_non_ip_packet();
    read_needs_room_for_the_header();
    write_drops_the_family_header();
    family_mode_is_unchanged();
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
