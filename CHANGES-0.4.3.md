# 0.4.3 — removal of races

Scripts
- appops.sh: flock instead of a mkdir lock (mkdir lock kept as fallback); desired state in
  appops.want, `apply` loops until appops.applied == appops.want, so a later "off" is never lost.
- sync_config.sh: whole script under flock (sync.lock), waits instead of skipping; config for the
  app is copied via temp file + rename; appops work runs in the background without the sync lock.
- a11y.sh: read-back verification of enabled_accessibility_services, up to 3 retries.
- service.sh: no pkill-then-start; the daemon is a singleton itself.
- WebUI: config.json is saved via temp file + mv.

Daemon (hgd.c)
- singleton via flock on hgd.lock;
- config change detection by mtime (ns) + inode + size; empty read keeps the old values;
- mark files (pass, power_wake) written via temp file + rename.
- NOT changed on purpose: the SETTLE_NS screen-state prediction (see reply: a confirmation-only
  scheme has a hole after force_sleep(); needs on-device trace before touching).

App (Kotlin)
- screen_state written via temp file + rename (daemon never reads an empty file);
- consumeMark(): atomic claim by rename (fallback to the old way if rename is refused);
  "pass" now goes through it as well.

# 0.4.4 — "settings are not saved" hotfix
- sync_config.sh: the flock is best-effort now: after 5 s without the lock (or if flock misbehaves)
  the sync goes on without it and writes a line to /data/adb/habitguard/sync.log. It never skips.
- WebUI save: temp file + mv, with a fallback to the plain `echo > config.json` if that fails.

# 0.4.5 — slow WebUI save
Cause: Android's /system/bin/sh is mksh, which marks fds opened with `exec` close-on-exec, so the
external `flock -n -x 9` of 0.4.3/0.4.4 failed with EBADF every time (tested: dash passes, mksh does
not). sync_config.sh therefore always waited its full timeout (5 s) before saving, and appops.sh
waited 120 s in the background and never applied.
- sync_config.sh / appops.sh: lock is a mkdir lock again (atomic everywhere); a lock of a dead
  process is taken over at once; sync gives up waiting after 30 s and goes on (sync.log).
- a11y.sh: reads the list back only after it really wrote something.
- gestures.sh: 3x `settings list` instead of 39x `settings get` (black true-sleep mode: the old
  loop alone took 8-20 s).
- new scripts/facetrace.sh: read-only diagnostic for the face-unlock problems (not wired anywhere).
- Face unlock / countdown behaviour is NOT changed in this release.
