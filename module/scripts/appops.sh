#!/system/bin/sh
# usage: appops.sh on | off | apply | want on|off | status | probe
#
# Stops third-party apps from lighting the screen (a wake lock with ACQUIRE_CAUSES_WAKEUP) through
# the AppOps op TURN_SCREEN_ON. Plain `cmd appops`, no framework hook: it works the same on every
# ROM that has the op, and `probe` tells whether this one does.
#
# Not touched: system apps (dialer, SystemUI, clock of the ROM...), this app, the default dialer
# role holder, and every package listed (one per line) in /data/adb/habitguard/appops.allow.
# The previous mode of every package we change is remembered and restored by `off`.
# New apps installed later are picked up the next time sync_config.sh (WebUI save / boot) runs.
#
# Concurrency (v0.4.3): the DESIRED state lives in appops.want (written atomically, last writer
# wins); `apply` runs under an flock and loops until appops.applied == appops.want. So two quick
# WebUI saves can neither run twice at once nor lose the later "off". The flock is released by the
# kernel when the process dies: there is no stale lock. Without `flock` a mkdir lock is used.
#   want on|off   only record the desired state (fast, atomic)
#   apply         converge to the recorded state (slow; run in the background)
#   on | off      want + apply in one go (manual use, uninstall.sh)
D=${HG_DIR:-/data/adb/habitguard}
SAVE=$D/appops.saved
ALLOW=$D/appops.allow
WANT=$D/appops.want
APPLIED=$D/appops.applied
LOCKFILE=$D/appops.flock
LOCKDIR=$D/appops.lock # fallback when there is no flock
OP=TURN_SCREEN_ON
SELF=com.habitguard

op_supported() {
  out=$(cmd appops get "$SELF" $OP 2>&1) || true
  case "$out" in
    ""|*nknown*|*rror*|*xception*|*nvalid*|*"not found"*) return 1 ;;
  esac
  return 0
}

# current mode of package $1: allow / ignore / deny / foreground / default
cur_mode() {
  m=$(cmd appops get "$1" $OP 2>/dev/null | sed -n "s/.*$OP: \([a-z_]*\).*/\1/p" | head -n 1)
  if [ -n "$m" ]; then echo "$m"; else echo default; fi
}

dialer_pkg() {
  cmd role get-role-holders --user 0 android.app.role.DIALER 2>/dev/null | head -n 1
}

skip_pkg() {
  [ "$1" = "$SELF" ] && return 0
  [ -n "$DIALER" ] && [ "$1" = "$DIALER" ] && return 0
  [ -f "$ALLOW" ] && grep -qx "$1" "$ALLOW" && return 0
  return 1
}

nap() { sleep 0.2 2>/dev/null || sleep 1; }

# take_lock SECONDS: wait up to SECONDS for the lock (0 = try once)
take_lock() {
  mkdir -p "$D" 2>/dev/null
  max=$(($1 * 5))
  i=0
  # (a failing `exec` redirection would end the shell, so test that the file is writable first)
  if command -v flock >/dev/null 2>&1 && ( : >> "$LOCKFILE" ) 2>/dev/null; then
    exec 8>>"$LOCKFILE"
    until flock -n -x 8 2>/dev/null; do
      i=$((i + 1))
      [ "$i" -gt "$max" ] && return 1
      nap
    done
    return 0
  fi
  # fallback: mkdir lock; a lock whose pid is dead is stale and is taken over
  until mkdir "$LOCKDIR" 2>/dev/null; do
    old=$(cat "$LOCKDIR/pid" 2>/dev/null)
    if [ -n "$old" ] && ! kill -0 "$old" 2>/dev/null; then
      # two waiters may both see the stale lock: the rename is atomic, only one of them wins it
      # (fallback path only; with flock this whole branch does not exist)
      mv "$LOCKDIR" "$LOCKDIR.stale.$$" 2>/dev/null && rm -rf "$LOCKDIR.stale.$$"
      continue
    fi
    i=$((i + 1))
    [ "$i" -gt "$max" ] && return 1
    nap
  done
  echo $$ > "$LOCKDIR/pid"
  trap 'rm -rf "$LOCKDIR"' EXIT
  return 0
}

set_want() {
  mkdir -p "$D" 2>/dev/null
  echo "$1" > "$WANT.tmp.$$" && mv -f "$WANT.tmp.$$" "$WANT"
}

want_now() { cat "$WANT" 2>/dev/null; }

do_on() {
  op_supported || { echo "appops $OP is not supported on this ROM"; return 2; }
  DIALER=$(dialer_pkg)
  [ -f "$SAVE" ] || : > "$SAVE"
  n=0
  for p in $(pm list packages -3 2>/dev/null | sed 's/^package://'); do
    # the desired state changed while we work: stop, apply() will converge to the new one
    [ "$(want_now)" = on ] || { echo "appops: want changed, stopping"; return 0; }
    skip_pkg "$p" && continue
    if ! grep -q "^$p " "$SAVE"; then
      m=$(cur_mode "$p")
      [ "$m" = ignore ] && continue # the user set it before us: leave it alone, never restore it
      echo "$p $m" >> "$SAVE"
    fi
    cmd appops set "$p" $OP ignore 2>/dev/null && n=$((n + 1))
  done
  echo "appops: $OP=ignore for $n package(s)"
  return 0
}

do_off() {
  [ -f "$SAVE" ] || return 0
  while read -r p m; do
    [ -n "$p" ] || continue
    cmd appops set "$p" $OP "${m:-default}" 2>/dev/null
  done < "$SAVE"
  rm -f "$SAVE"
  echo "appops: restored"
  return 0
}

apply() {
  take_lock 120 || { echo "appops: busy, the running instance will pick up the change"; return 0; }
  guard=0
  while [ "$guard" -lt 6 ]; do
    guard=$((guard + 1))
    want=$(want_now)
    case "$want" in on|off) ;; *) return 0 ;; esac
    [ "$want" = "$(cat "$APPLIED" 2>/dev/null)" ] && return 0
    echo busy > "$APPLIED" # a run that is cut short (killed, want changed) must be redone, never trusted
    if [ "$want" = on ]; then do_on; rc=$?; else do_off; rc=$?; fi
    if [ "$rc" != 0 ]; then
      # cannot do it (op missing on this ROM): nothing was changed, so "off" is the truth
      echo off > "$APPLIED"
      return "$rc"
    fi
    # if the want changed meanwhile do_on stopped early: do not claim it was applied
    [ "$want" = "$(want_now)" ] && echo "$want" > "$APPLIED"
  done
  return 0
}

case "$1" in
on|off)
  set_want "$1"
  apply
  exit $?
  ;;
want)
  case "$2" in on|off) set_want "$2" ;; *) echo "usage: appops.sh want on|off"; exit 1 ;; esac
  ;;
apply)
  apply
  exit $?
  ;;
status)
  if op_supported; then echo "supported: yes"; else echo "supported: no"; fi
  if [ -f "$SAVE" ]; then echo "changed packages: $(wc -l < "$SAVE")"; else echo "changed packages: 0"; fi
  echo "want: $(want_now) applied: $(cat "$APPLIED" 2>/dev/null)"
  ;;
probe)
  if op_supported; then echo "yes"; else echo "no"; fi
  ;;
*)
  echo "usage: appops.sh on|off|apply|want on|off|status|probe"
  exit 1
  ;;
esac
exit 0
