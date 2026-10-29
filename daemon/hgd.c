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
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/timerfd.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <linux/input.h>
#include <linux/uinput.h>

#define CFG_PATH   "/data/adb/habitguard/config.json"
#define KILL_PATH  "/data/adb/habitguard/disable"
#define LOCK_PATH  "/data/adb/habitguard/hgd.lock"
#define APP_DIR    "/data/data/com.habitguard"
#define STATE_PATH APP_DIR "/files/screen_state"
#define PASS_PATH  APP_DIR "/files/pass"
#define FILES_DIR  APP_DIR "/files"
#define ALIVE_PATH APP_DIR "/files/alive"
#define SLEEPREQ_PATH APP_DIR "/files/sleep_req"
#define WAKE_PATH  APP_DIR "/files/power_wake"
#define WAKE_LEAD_US 120000 /* let the APK raise the countdown before the screen lights */
#define SETTLE_NS 2500000000LL   /* our own screen-state prediction beats the (late) APK report this long */
#define LONG_PRESS_NS 400000000LL /* a power press shorter than this surely switches the screen off */
#define MAX_DEV    8
#define MAX_GATE   8
#define MAX_CAND   32

struct dev { int fd; int ufd; };

static struct dev devs[MAX_DEV];
static int ndev = 0;
static bool grabbed = false;
static int tries = 0;
static time_t last_try = 0;
static int ifd = -1;
static bool watching = false;

static bool cfg_enabled = false, cfg_sleep = false, cfg_countdown = false;
static int cfg_seconds = 15;
static struct stat cfg_st; /* identity of the config file we last parsed */
static bool cfg_have = false;

static int tfd = -1;
static bool pending = false;
static int pend_dev = 0;
static int dstate = 0; /* 0 none, 1 passing through, 2 swallowed */
/* The daemon's own idea of the screen state ('1' awake, '0' asleep). The APK reports the state
 * 0.5-1.5 s late, and a late report of an OLD transition used to confuse the daemon (a wake-up
 * press swallowed as "screen is off", or let through = screen flash). After every transition the
 * daemon causes itself (power press passed through, delayed wake-up injected, forced sleep) this
 * prediction wins for SETTLE_NS, then the APK's state file decides again. */
static int pred = 0;
static long long pred_until_ns = 0;
static bool press_awake = false; /* the current power press was passed through while awake */
static long long press_down_ns = 0;
static int sleepfd = -1; /* uinput device that sends KEY_SLEEP (not a toggle, takes ~ms) */

/* "gate": touchscreens are muted (grabbed, events dropped) while the screen is off,
 * so double-tap-to-wake and similar never reach the system */
static int gfd[MAX_GATE];
static int ngate = 0;
static bool gate_on = false;
static time_t gate_hold = 0, last_verify = 0, burst_t = 0;
static int burst = 0;

static long long now_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_REALTIME, &t);
    return (long long)t.tv_sec * 1000000000LL + t.tv_nsec;
}

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

/* Same file, same content version? mtime in NANOseconds + inode (the WebUI replaces the file with a
 * rename, which changes the inode) + size: two saves of equal size within one second (15 -> 30 s)
 * used to look identical. */
static bool same_file(const struct stat *a, const struct stat *b) {
    return a->st_ino == b->st_ino && a->st_size == b->st_size &&
           a->st_mtim.tv_sec == b->st_mtim.tv_sec && a->st_mtim.tv_nsec == b->st_mtim.tv_nsec;
}

static void load_cfg(void) {
    struct stat st;
    if (stat(CFG_PATH, &st) < 0) {
        cfg_enabled = cfg_sleep = cfg_countdown = false;
        cfg_have = false;
        return;
    }
    if (cfg_have && same_file(&st, &cfg_st)) return;
    int fd = open(CFG_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    struct stat fst; /* what we really read, even if the file was replaced since stat() */
    if (fstat(fd, &fst) < 0) {
        close(fd);
        return;
    }
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return; /* empty / unreadable (half-written by an old writer): keep the old values, retry next loop */
    buf[n] = 0;
    cfg_st = fst;
    cfg_have = true;
    cfg_enabled = jbool(buf, "enabled");
    cfg_sleep = jbool(buf, "true_sleep");
    const char *m = jfind(buf, "mode");
    cfg_countdown = m && m[0] == '"' && strncmp(m + 1, "countdown", 9) == 0;
    cfg_seconds = jint(buf, "seconds", 15);
    if (cfg_seconds < 1) cfg_seconds = 1;
    if (cfg_seconds > 300) cfg_seconds = 300;
    hlog("config: enabled=%d true_sleep=%d countdown=%d seconds=%d", cfg_enabled, cfg_sleep,
         cfg_countdown, cfg_seconds);
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

/* Drop a small timestamp file into the APK's files dir (the APK reads / watches it).
 * Written under a temp name and renamed: the APK (FileObserver MOVED_TO) and its consumeMark()
 * never see an empty or half-written mark, and a mark is either fully there or not at all. */
static void mark_file(const char *path) {
    struct stat st;
    if (stat(APP_DIR, &st) < 0) return;
    char tmp[256];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    char b[32];
    int n = snprintf(b, sizeof b, "%ld", (long)time(NULL));
    if (write(fd, b, n) < 0) { }
    if (fchown(fd, st.st_uid, st.st_gid) < 0) { }
    close(fd);
    if (rename(tmp, path) < 0) unlink(tmp);
}
/* this wake-up is allowed and already delayed (APK must not show the overlay) */
static void mark_pass(void) { mark_file(PASS_PATH); }

/* '1' awake, '0' asleep (as last written by the APK), 0 unknown */
static int read_state(void) {
    char c = 0;
    int fd = open(STATE_PATH, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (read(fd, &c, 1) != 1) c = 0;
        close(fd);
    }
    return c;
}

/* 1: the APK process is alive (it holds a lock on `alive`), 0: dead, -1: cannot tell.
 * Event-driven and free: no process is spawned, nothing can hang. */
static int apk_alive(void) {
    int fd = open(ALIVE_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    struct flock fl;
    memset(&fl, 0, sizeof fl);
    fl.l_type = F_RDLCK;
    fl.l_whence = SEEK_SET;
    int r = fcntl(fd, F_GETLK, &fl);
    close(fd);
    if (r < 0) return -1;
    return fl.l_type != F_UNLCK;
}

static void predict(int st) {
    pred = st;
    pred_until_ns = now_ns() + SETTLE_NS;
}

/* '1' awake, '0' asleep, 0 unknown: our own prediction while it is fresh, else the APK's file */
static int cur_state(void) {
    if (pred) {
        if (now_ns() < pred_until_ns) return pred;
        pred = 0;
    }
    return read_state();
}

/* true only when the screen is off AND the APK is alive (its state file is trustworthy);
 * fail-open otherwise. Used for both the power key and the touch muting. */
static bool asleep(void) {
    return cur_state() == '0' && apk_alive() != 0;
}

/* Tiny virtual keyboard whose only key is KEY_SLEEP: the layout is Generic.kl (key 142 SLEEP),
 * and the key reaches PhoneWindowManager in milliseconds. Created while the power key is grabbed. */
static void sleepdev_open(void) {
    if (sleepfd >= 0) return;
    int u = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) u = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (u < 0) return;
    ioctl(u, UI_SET_EVBIT, EV_KEY);
    ioctl(u, UI_SET_KEYBIT, KEY_SLEEP);
    struct uinput_user_dev uud;
    memset(&uud, 0, sizeof uud);
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "hgd-sleep");
    uud.id.bustype = BUS_VIRTUAL;
    if (write(u, &uud, sizeof uud) != (ssize_t)sizeof uud || ioctl(u, UI_DEV_CREATE) < 0) {
        close(u);
        return;
    }
    sleepfd = u;
    hlog("sleep device ready");
}

static void sleepdev_close(void) {
    if (sleepfd < 0) return;
    ioctl(sleepfd, UI_DEV_DESTROY);
    close(sleepfd);
    sleepfd = -1;
}

/* The APK saw a screen-on that the daemon did not allow (fingerprint, gesture,
 * notification...): put the phone back to sleep right away. KEY_SLEEP is not a toggle.
 * Fast path: our own uinput key. Fallback: `input keyevent 223` (starts a Java process, slow). */
static void force_sleep(void) {
    if (sleepfd >= 0) {
        emit(sleepfd, EV_KEY, KEY_SLEEP, 1);
        emit(sleepfd, EV_SYN, SYN_REPORT, 0);
        usleep(30000);
        emit(sleepfd, EV_KEY, KEY_SLEEP, 0);
        emit(sleepfd, EV_SYN, SYN_REPORT, 0);
        predict('0'); /* mute the touchscreen at once, do not wait for the APK */
        hlog("unexpected wake-up: KEY_SLEEP");
        return;
    }
    pid_t p = fork();
    if (p == 0) {
        execl("/system/bin/input", "input", "keyevent", "223", (char *)NULL);
        _exit(127);
    }
    hlog(p > 0 ? "unexpected wake-up: forcing sleep (input)" : "fork failed: %s", p > 0 ? "" : strerror(errno));
}

static void check_sleep_req(void) {
    struct stat st;
    if (stat(SLEEPREQ_PATH, &st) < 0) return;
    unlink(SLEEPREQ_PATH);
    if (!grabbed || !cfg_enabled || !cfg_sleep || cfg_countdown) return;
    long age = (long)(time(NULL) - st.st_mtime);
    if (age < -3 || age > 3) return; /* stale file from an earlier run */
    force_sleep();
}

static void ensure_watch(void) {
    if (watching) return;
    if (ifd < 0) ifd = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (ifd < 0) return;
    if (inotify_add_watch(ifd, FILES_DIR, IN_CLOSE_WRITE | IN_MOVED_TO) >= 0) watching = true;
}

static void drain_watch(void) {
    char buf[512] __attribute__((aligned(__alignof__(struct inotify_event))));
    ssize_t n = read(ifd, buf, sizeof buf);
    if (n <= 0) return;
    for (char *q = buf; q < buf + n;) {
        struct inotify_event *ev = (struct inotify_event *)q;
        if (ev->mask & IN_IGNORED) watching = false; /* dir was recreated */
        q += sizeof *ev + ev->len;
    }
    check_sleep_req();
}

static void gate_enable(void) {
    if (gate_on || ngate == 0) return;
    int ok = 0;
    for (int i = 0; i < ngate; i++) {
        if (ioctl(gfd[i], EVIOCGRAB, 1) == 0) ok++;
        struct input_event junk[64];
        while (read(gfd[i], junk, sizeof junk) > 0) { }
    }
    gate_on = ok > 0;
    if (gate_on) hlog("gate on: %d touch device(s) muted", ok);
}

static void gate_disable(void) {
    if (!gate_on) return;
    for (int i = 0; i < ngate; i++) ioctl(gfd[i], EVIOCGRAB, 0);
    gate_on = false;
    hlog("gate off");
}

/* mute touch input exactly while the screen is off */
static void refresh_gate(void) {
    /* countdown mode: double tap & co. may wake the phone (the APK shows the countdown) */
    if (!grabbed || ngate == 0 || !cfg_enabled || !cfg_sleep || cfg_countdown) {
        gate_disable();
        return;
    }
    if (gate_on) { /* APK died or screen came on: open at once */
        if (!asleep()) gate_disable();
        return;
    }
    if (time(NULL) < gate_hold) return;
    if (asleep()) gate_enable();
}

/* keys that may still reach the system while asleep (volume, headset media) */
static bool sleep_key_ok(int code) {
    switch (code) {
    case KEY_VOLUMEUP: case KEY_VOLUMEDOWN: case KEY_MUTE:
    case KEY_MEDIA: case KEY_PLAYPAUSE: case KEY_NEXTSONG: case KEY_PREVIOUSSONG:
    case KEY_STOPCD: case KEY_PLAYCD: case KEY_PAUSECD:
        return true;
    }
    return false;
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

static bool is_touch(int fd, const unsigned char *ev, const unsigned char *kb) {
    if (!bit_set(ev, EV_ABS)) return false;
    unsigned char ab[ABS_MAX / 8 + 1];
    memset(ab, 0, sizeof ab);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof ab), ab);
    return bit_set(ab, ABS_MT_POSITION_X) || bit_set(kb, BTN_TOUCH);
}

static void grab_all(void) {
    /* collect first: our own uinput clones appear in /dev/input while we work */
    static char paths[MAX_CAND][300];
    int np = 0;
    DIR *d = opendir("/dev/input");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && np < MAX_CAND) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        snprintf(paths[np++], sizeof paths[0], "/dev/input/%s", e->d_name);
    }
    closedir(d);

    for (int i = 0; i < np; i++) {
        const char *path = paths[i];
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;

        unsigned char ev[EV_MAX / 8 + 1], kb[KEY_MAX / 8 + 1];
        memset(ev, 0, sizeof ev);
        memset(kb, 0, sizeof kb);
        ioctl(fd, EVIOCGBIT(0, sizeof ev), ev);
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof kb), kb);
        char name[80] = "";
        ioctl(fd, EVIOCGNAME(sizeof name), name);

        if (is_touch(fd, ev, kb)) { /* touchscreen: muted only while asleep */
            if (ngate < MAX_GATE) {
                gfd[ngate++] = fd;
                hlog("touch device %s (%s): muted while asleep", path, name);
            } else {
                close(fd);
            }
            continue;
        }
        /* key devices that can wake the phone; never real keyboards, mice, sensors */
        bool wakey = bit_set(kb, KEY_POWER) || bit_set(kb, KEY_WAKEUP) || bit_set(kb, KEY_HOME) ||
                     bit_set(kb, KEY_MENU) || bit_set(kb, KEY_BACK) || bit_set(kb, KEY_CAMERA) ||
                     bit_set(kb, KEY_SEARCH);
        if (!wakey || bit_set(kb, KEY_A) || bit_set(ev, EV_ABS) || bit_set(ev, EV_REL) ||
            ndev >= MAX_DEV) {
            close(fd);
            continue;
        }
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
    grabbed = ndev > 0;
    if (grabbed) sleepdev_open();
    if (!grabbed) {
        hlog("no power-key device found: overlay fallback only");
        for (int i = 0; i < ngate; i++) close(gfd[i]);
        ngate = 0;
    }
}

static void release_all(void) {
    gate_disable();
    for (int i = 0; i < ngate; i++) close(gfd[i]);
    ngate = 0;
    for (int i = 0; i < ndev; i++) {
        ioctl(devs[i].fd, EVIOCGRAB, 0);
        close(devs[i].fd);
        ioctl(devs[i].ufd, UI_DEV_DESTROY);
        close(devs[i].ufd);
    }
    if (ndev) hlog("released %d device(s)", ndev);
    sleepdev_close();
    ndev = 0;
    grabbed = false;
    pending = false;
    press_awake = false;
    dstate = 0;
    disarm_timer();
    wakelock(false);
}

/* ---------- logic ---------- */
static bool on_power(int idx, int val) {
    if (val == 1) {
        if (pending) {
            /* power pressed again while the wake-up is still waiting: it is a "never mind".
             * Cancel it (a third press starts a fresh delay) instead of lighting the screen
             * later, N seconds after the FIRST press. To ignore such presses instead,
             * replace this block with: dstate = 2; return false; */
            pending = false;
            disarm_timer();
            wakelock(false);
            dstate = 2;
            hlog("power pressed while waiting: wake-up cancelled");
            return false;
        }
        if (cfg_enabled && cfg_sleep && cfg_countdown) {
            /* countdown mode: the screen cannot show digits while it is off, so the power key
             * wakes it at once and the APK raises the countdown. Every OTHER wake-up
             * (fingerprint, double tap, notification...) is still put back to sleep. */
            dstate = 1;
            if (asleep()) {
                predict('1');
                press_awake = false;
                mark_file(WAKE_PATH);
                gate_hold = time(NULL) + 3; /* do not re-mute while the screen is waking */
                gate_disable();
                usleep(WAKE_LEAD_US);
                hlog("power wake: countdown");
            } else {
                press_awake = true;
                press_down_ns = now_ns();
            }
            emit(devs[idx].ufd, EV_KEY, KEY_POWER, 1);
            return true;
        }
        if (cfg_enabled && cfg_sleep) wakelock(true); /* stay up during the check */
        if (cfg_enabled && cfg_sleep && asleep()) {
            dstate = 2;
            pending = true;
            pend_dev = idx;
            arm_timer(cfg_seconds);
            hlog("power swallowed, waiting %ds", cfg_seconds);
            return false;
        }
        if (cfg_enabled && cfg_sleep) wakelock(false);
        press_awake = true; /* if this press is short, the screen goes off on its release */
        press_down_ns = now_ns();
        dstate = 1;
        emit(devs[idx].ufd, EV_KEY, KEY_POWER, 1);
        return true;
    }
    if (dstate == 2) { /* release/repeat of a swallowed press */
        if (val == 0) dstate = 0;
        return false;
    }
    emit(devs[idx].ufd, EV_KEY, KEY_POWER, val);
    if (val == 0) {
        dstate = 0;
        if (press_awake && cfg_enabled && cfg_sleep && now_ns() - press_down_ns < LONG_PRESS_NS) {
            predict('0'); /* the screen is going off now: do not wait for the APK to say so */
            refresh_gate();
        }
        press_awake = false;
    }
    return true;
}

static void on_timer(void) {
    uint64_t x;
    if (read(tfd, &x, sizeof x) < 0) { }
    if (!pending) return;
    pending = false;
    if (asleep()) {
        predict('1'); /* the APK learns about the wake-up late: a power press right now means "off" */
        gate_hold = time(NULL) + 3; /* do not re-mute while the screen is waking */
        gate_disable();
        mark_pass();
        inject_power(pend_dev);
        hlog("power injected");
    } else {
        hlog("screen woke by other means, cancel");
    }
    wakelock(false);
}

/* Only one daemon may grab the input devices. The lock lives as long as the process (the kernel
 * drops it when we die), so there is no stale lock and no pkill-then-start race in service.sh. */
static void single_instance(void) {
    int fd = open(LOCK_PATH, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (fd < 0) { /* cannot tell: stay fail-open and run */
        hlog("lock file: %s (continuing without)", strerror(errno));
        return;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) < 0 && errno == EWOULDBLOCK) {
        hlog("another hgd is already running, exiting");
        exit(0);
    }
    /* fd stays open on purpose */
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    single_instance();
    tfd = timerfd_create(CLOCK_BOOTTIME_ALARM, TFD_CLOEXEC | TFD_NONBLOCK);
    if (tfd < 0) tfd = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK);
    if (tfd < 0) {
        hlog("timerfd failed: %s", strerror(errno));
        return 1;
    }
    wakelock(false);
    hlog("hgd started");

    for (;;) {
        while (waitpid(-1, NULL, WNOHANG) > 0) { } /* reap `input` helpers */
        load_cfg();
        if (!want_grab()) {
            if (grabbed) release_all();
            tries = 0;
            last_try = 0;
        } else if (!grabbed && tries < 6 && time(NULL) - last_try >= 10) {
            tries++; /* retry a few times: devices may appear late */
            last_try = time(NULL);
            grab_all();
        }
        ensure_watch();
        check_sleep_req();
        refresh_gate();

        struct pollfd pf[MAX_DEV + MAX_GATE + 2];
        int kind[MAX_DEV + MAX_GATE + 2], kidx[MAX_DEV + MAX_GATE + 2];
        int n = 0;
        for (int i = 0; i < ndev; i++) { /* 0: key device */
            pf[n].fd = devs[i].fd;
            pf[n].events = POLLIN;
            pf[n].revents = 0;
            kind[n] = 0;
            kidx[n] = i;
            n++;
        }
        pf[n].fd = tfd; /* 1: delay timer */
        pf[n].events = POLLIN;
        pf[n].revents = 0;
        kind[n] = 1;
        kidx[n] = 0;
        n++;
        if (watching) { /* 2: files dir (sleep_req / screen_state) */
            pf[n].fd = ifd;
            pf[n].events = POLLIN;
            pf[n].revents = 0;
            kind[n] = 2;
            kidx[n] = 0;
            n++;
        }
        if (gate_on) { /* 3: muted touchscreens */
            for (int i = 0; i < ngate; i++) {
                pf[n].fd = gfd[i];
                pf[n].events = POLLIN;
                pf[n].revents = 0;
                kind[n] = 3;
                kidx[n] = i;
                n++;
            }
        }

        /* while a prediction is fresh, wake up often so the touch muting follows it closely */
        int to = (pred && now_ns() < pred_until_ns) ? 250 : 2000;
        int r = poll(pf, n, to); /* also re-checks the config every 2 s */
        if (r <= 0) continue;

        bool reset = false;
        for (int k = 0; k < n && !reset; k++) {
            if (!(pf[k].revents & (POLLIN | POLLERR | POLLHUP))) continue;
            if (kind[k] == 1) {
                if (pf[k].revents & POLLIN) on_timer();
                continue;
            }
            if (kind[k] == 2) {
                if (pf[k].revents & POLLIN) {
                    drain_watch();
                    refresh_gate();
                }
                continue;
            }
            struct input_event evs[64];
            if (kind[k] == 3) {
                if (!gate_on) continue;
                ssize_t len = read(gfd[kidx[k]], evs, sizeof evs);
                if (len < 0 && (errno == EINTR || errno == EAGAIN)) continue;
                if (len <= 0) { /* device vanished */
                    release_all();
                    tries = 0;
                    last_try = 0;
                    reset = true;
                    break;
                }
                /* events are dropped. Cheap safety valves so a wrong state can never
                 * leave a live screen without touch: re-check state once a second and
                 * open the gate if a dense stream of events looks like real use */
                time_t now = time(NULL);
                if (now - burst_t >= 2) {
                    burst_t = now;
                    burst = 0;
                }
                burst += (int)(len / (ssize_t)sizeof evs[0]);
                if (burst > 300) {
                    hlog("gate: dense touch stream, opening for 30s");
                    gate_hold = now + 30;
                    gate_disable();
                } else if (now != last_verify) {
                    last_verify = now;
                    if (!asleep()) gate_disable();
                }
                continue;
            }
            int i = kidx[k];
            ssize_t len = read(devs[i].fd, evs, sizeof evs);
            if (len < 0 && (errno == EINTR || errno == EAGAIN)) continue;
            if (len <= 0) { /* device vanished */
                release_all();
                tries = 0;
                last_try = 0;
                reset = true;
                break;
            }
            bool sent = false; /* did this batch forward anything (for SYN_REPORT) */
            for (size_t j = 0; j < (size_t)len / sizeof evs[0]; j++) {
                const struct input_event *v = &evs[j];
                if (v->type == EV_KEY && v->code == KEY_POWER) {
                    if (on_power(i, v->value)) sent = true;
                    continue;
                }
                if (gate_on) { /* screen is off: only volume / media keys get through */
                    if (v->type == EV_SYN) {
                        if (!sent) continue;
                        sent = false;
                    } else if (v->type == EV_KEY) {
                        if (!sleep_key_ok(v->code)) continue;
                        sent = true;
                    } else if (v->type == EV_MSC) {
                        continue;
                    } else {
                        sent = true;
                    }
                } else if (v->type != EV_SYN) {
                    sent = true;
                }
                if (write(devs[i].ufd, v, sizeof *v) < 0) { }
                if (v->type == EV_SYN) sent = false;
            }
        }
    }
}
