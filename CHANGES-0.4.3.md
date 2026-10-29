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
