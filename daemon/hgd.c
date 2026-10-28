/*
 * hgd - Habit Guard daemon: "true sleep" delay for the power button.
 *
 * While the screen is asleep, a power-key press is swallowed and re-injected
 * after N seconds, so the phone stays really asleep during the delay.
 * While the screen is awake every event is passed through untouched.
 *
 * Safety: fail-open everywhere (no state file / unknown state / any error ->
 * pass through). The grab is released automatically by the kernel if we die.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define CFG_PATH   "/data/adb/habitguard/config.json"
#define KILL_PATH  "/data/adb/habitguard/disable"
#define APP_DIR    "/data/data/com.habitguard"
#define STATE_PATH APP_DIR "/files/screen_state"
#define PASS_PATH  APP_DIR "/files/pass"
#define MAX_DEV    8

struct dev { int fd; int ufd; };

static struct dev devs[MAX_DEV];
static int ndev = 0;
static bool grabbed = false;
static bool tried = false;

static bool cfg_enabled = false, cfg_sleep = false;
static int cfg_seconds = 15;
static long cfg_stamp = -1;

static int tfd = -1;
static bool pending = false;
static int pend_dev = 0;
static int dstate = 0; /* 0 none, 1 passing through, 2 swallowed */

static void hlog(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/* ---------- tiny JSON helpers (our own flat config, no nesting tricks) ---------- */
static const char *jfind(const char *b, const char *k) {
    char pat[48];
    snprintf(pat, sizeof pat, "\"%s\"", k);
    const char *p = strstr(b, pat);
    if (!p) return NULL;
    p = strchr(p, ':');
    if (!p) return NULL;
    p++;
    while (*p == ' ' || *p == '\n' || *p == '\t' || *p == '\r') p++;
    return p;
}
static bool jbool(const char *b, const char *k) {
    const char *p = jfind(b, k);
    return p && strncmp(p, "true", 4) == 0;
}
static int jint(const char *b, const char *k, int def) {
    const char *p = jfind(b, k);
    return p ? atoi(p) : def;
}

static void load_cfg(void) {
    struct stat st;
    if (stat(CFG_PATH, &st) < 0) {
        cfg_enabled = cfg_sleep = false;
        cfg_stamp = -1;
        return;
    }
    long stamp = (long)st.st_mtime * 1000003L + (long)st.st_size;
    if (stamp == cfg_stamp) return;
    int fd = open(CFG_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n < 0) return;
    buf[n] = 0;
    cfg_stamp = stamp;
    cfg_enabled = jbool(buf, "enabled");
    cfg_sleep = jbool(buf, "true_sleep");
    cfg_seconds = jint(buf, "seconds", 15);
    if (cfg_seconds < 1) cfg_seconds = 1;
    if (cfg_seconds > 300) cfg_seconds = 300;
    hlog("config: enabled=%d true_sleep=%d seconds=%d", cfg_enabled, cfg_sleep, cfg_seconds);
}

static bool want_grab(void) {
    return cfg_enabled && cfg_sleep && access(KILL_PATH, F_OK) != 0;
}

/* ---------- helpers ---------- */
static void wakelock(bool on) {
    int fd = open(on ? "/sys/power/wake_lock" : "/sys/power/wake_unlock", O_WRONLY | O_CLOEXEC);
    if (fd < 0) return;
    char b[64];
    int n;
    if (on) /* timed wakelock: expires by itself even if we die */
        n = snprintf(b, sizeof b, "hgd %lld", (long long)(cfg_seconds + 5) * 1000000000LL);
    else
        n = snprintf(b, sizeof b, "hgd");
    if (write(fd, b, n) < 0) { }
    close(fd);
}

static void arm_timer(int sec) {
    struct itimerspec its;
    memset(&its, 0, sizeof its);
    its.it_value.tv_sec = sec;
    timerfd_settime(tfd, 0, &its, NULL);
}
static void disarm_timer(void) {
    struct itimerspec its;
    memset(&its, 0, sizeof its);
    timerfd_settime(tfd, 0, &its, NULL);
}

static void emit(int ufd, int type, int code, int val) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = type;
    e.code = code;
    e.value = val;
    if (write(ufd, &e, sizeof e) < 0) { }
}

static void inject_power(int idx) {
    int u = devs[idx].ufd;
    emit(u, EV_KEY, KEY_POWER, 1);
    emit(u, EV_SYN, SYN_REPORT, 0);
    usleep(60000);
    emit(u, EV_KEY, KEY_POWER, 0);
    emit(u, EV_SYN, SYN_REPORT, 0);
}

/* Tell the APK that this wake-up is allowed (so it will not show the overlay). */
static void mark_pass(void) {
    struct stat st;
    if (stat(APP_DIR, &st) < 0) return;
    int fd = open(PASS_PATH, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char b[32];
    int n = snprintf(b, sizeof b, "%ld", (long)time(NULL));
    if (write(fd, b, n) < 0) { }
    if (fchown(fd, st.st_uid, st.st_gid) < 0) { }
    close(fd);
}

/* true only when we are sure the screen is off */
static bool asleep(void) {
    char c = 0;
    int fd = open(STATE_PATH, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (read(fd, &c, 1) != 1) c = 0;
        close(fd);
    }
    if (c == '1') return false; /* APK says awake: fast path, no dumpsys */
    FILE *p = popen("dumpsys power 2>/dev/null | grep -m1 mWakefulness=", "r");
    if (!p) return false;
    char line[128] = "";
    if (!fgets(line, sizeof line, p)) line[0] = 0;
    pclose(p);
    return strstr(line, "Asleep") != NULL || strstr(line, "Dozing") != NULL;
}

/* ---------- device discovery / grab ---------- */
static bool bit_set(const unsigned char *a, int n) { return a[n / 8] & (1 << (n % 8)); }

static int open_clone(int src, const char *name) {
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) u = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) return -1;

    unsigned char kb[KEY_MAX / 8 + 1];
    memset(kb, 0, sizeof kb);
    ioctl(src, EVIOCGBIT(EV_KEY, sizeof kb), kb);
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    for (int c = 0; c <= KEY_MAX; c++)
        if (bit_set(kb, c)) ioctl(u, UI_SET_KEYBIT, c);

    unsigned char sw[SW_MAX / 8 + 1];
    memset(sw, 0, sizeof sw);
    if (ioctl(src, EVIOCGBIT(EV_SW, sizeof sw), sw) >= 0) {
        bool any = false;
        for (int c = 0; c <= SW_MAX; c++) {
            if (!bit_set(sw, c)) continue;
            if (!any) { ioctl(u, UI_SET_EVBIT, EV_SW); any = true; }
            ioctl(u, UI_SET_SWBIT, c);
        }
    }
    ioctl(u, UI_SET_EVBIT, EV_MSC);
    ioctl(u, UI_SET_MSCBIT, MSC_SCAN);

    /* legacy setup works on every kernel; same name+id keeps the key layout (.kl) */
    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof uud);
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "%s", name);
    struct input_id id;
    if (ioctl(src, EVIOCGID, &id) == 0) uud.id = id;
    if (write(u, &uud, sizeof uud) != (ssize_t)sizeof uud || ioctl(u, UI_DEV_CREATE) < 0) {
        close(u);
        return -1;
    }
    return u;
}

static void grab_all(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && ndev < MAX_DEV) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        char path[300];
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;

        unsigned char ev[EV_MAX / 8 + 1], kb[KEY_MAX / 8 + 1];
        memset(ev, 0, sizeof ev);
        memset(kb, 0, sizeof kb);
        ioctl(fd, EVIOCGBIT(0, sizeof ev), ev);
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof kb), kb);
        /* only plain key devices that have a power key (never touchscreens/mice) */
        if (!bit_set(kb, KEY_POWER) || bit_set(ev, EV_ABS) || bit_set(ev, EV_REL)) {
            close(fd);
            continue;
        }
        char name[80] = "";
        ioctl(fd, EVIOCGNAME(sizeof name), name);
        int u = open_clone(fd, name);
        if (u < 0) {
            hlog("uinput clone failed for %s (%s)", path, name);
            close(fd);
            continue;
        }
        if (ioctl(fd, EVIOCGRAB, 1) < 0) {
            hlog("grab failed for %s", path);
            ioctl(u, UI_DEV_DESTROY);
            close(u);
            close(fd);
            continue;
        }
        devs[ndev].fd = fd;
        devs[ndev].ufd = u;
        ndev++;
        hlog("grabbed %s (%s)", path, name);
    }
    closedir(d);
    grabbed = ndev > 0;
    if (!grabbed) hlog("no power-key device found: overlay fallback only");
}

static void release_all(void) {
    for (int i = 0; i < ndev; i++) {
        ioctl(devs[i].fd, EVIOCGRAB, 0);
        close(devs[i].fd);
        ioctl(devs[i].ufd, UI_DEV_DESTROY);
        close(devs[i].ufd);
    }
    if (ndev) hlog("released %d device(s)", ndev);
    ndev = 0;
    grabbed = false;
    pending = false;
    dstate = 0;
    disarm_timer();
    wakelock(false);
}

/* ---------- logic ---------- */
static void on_power(int idx, int val) {
    if (val == 1) {
        if (pending) { /* extra press while waiting: ignored */
            dstate = 2;
            return;
        }
        if (cfg_enabled && cfg_sleep && asleep()) {
            dstate = 2;
            pending = true;
            pend_dev = idx;
            wakelock(true);
            arm_timer(cfg_seconds);
            hlog("power swallowed, waiting %ds", cfg_seconds);
        } else {
            dstate = 1;
            emit(devs[idx].ufd, EV_KEY, KEY_POWER, 1);
        }
        return;
    }
    if (dstate == 2) { /* release/repeat of a swallowed press */
        if (val == 0) dstate = 0;
        return;
    }
    emit(devs[idx].ufd, EV_KEY, KEY_POWER, val);
    if (val == 0) dstate = 0;
}

static void on_timer(void) {
    uint64_t x;
    if (read(tfd, &x, sizeof x) < 0) { }
    if (!pending) return;
    pending = false;
    if (asleep()) {
        mark_pass();
        inject_power(pend_dev);
        hlog("power injected");
    } else {
        hlog("screen woke by other means, cancel");
    }
    wakelock(false);
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    tfd = timerfd_create(CLOCK_BOOTTIME_ALARM, TFD_CLOEXEC | TFD_NONBLOCK);
    if (tfd < 0) tfd = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK);
    if (tfd < 0) {
        hlog("timerfd failed: %s", strerror(errno));
        return 1;
    }
    wakelock(false);
    hlog("hgd started");

    for (;;) {
        load_cfg();
        if (!want_grab()) {
            if (grabbed) release_all();
            tried = false;
        } else if (!grabbed && !tried) {
            tried = true;
            grab_all();
        }

        struct pollfd pf[MAX_DEV + 1];
        int n = 0;
        for (int i = 0; i < ndev; i++) {
            pf[n].fd = devs[i].fd;
            pf[n].events = POLLIN;
            pf[n].revents = 0;
            n++;
        }
        pf[n].fd = tfd;
        pf[n].events = POLLIN;
        pf[n].revents = 0;
        n++;

        int r = poll(pf, n, 2000); /* also re-checks the config every 2 s */
        if (r <= 0) continue;

        bool reset = false;
        for (int i = 0; i < ndev && !reset; i++) {
            if (!(pf[i].revents & (POLLIN | POLLERR | POLLHUP))) continue;
            struct input_event evs[32];
            ssize_t len = read(devs[i].fd, evs, sizeof evs);
            if (len < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (len <= 0) { /* device vanished */
                release_all();
                tried = false;
                reset = true;
                break;
            }
            for (size_t j = 0; j < (size_t)len / sizeof evs[0]; j++) {
                if (evs[j].type == EV_KEY && evs[j].code == KEY_POWER)
                    on_power(i, evs[j].value);
                else if (write(devs[i].ufd, &evs[j], sizeof evs[j]) < 0) { }
            }
        }
        if (!reset && (pf[ndev].revents & POLLIN)) on_timer();
    }
}
