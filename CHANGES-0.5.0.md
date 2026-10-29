# 0.5.0 — "hard wall": no unlock during the delay

Problem: the cover (black window / countdown) only HIDES the screen. Face / fingerprint / Smart Lock
kept working underneath it, so the phone could be unlocked while the delay was still running.

New option `hard_wall` (WebUI: "Жёсткая стена", OFF by default)
- Wall.kt: the app is a device admin with ONE policy (disable-keyguard-features). While the phone is
  asleep or a delay is running it sets KEYGUARD_DISABLE_BIOMETRICS | KEYGUARD_DISABLE_TRUST_AGENTS;
  PIN / pattern / password keep working. The flag is lowered the moment the delay is over.
- Guard.kt: `released` says "this wake-up's delay is over". Wall UP on every switch-off, on the start
  of a delay and on an unexpected wake-up; DOWN on: daemon `pass` mark (black true sleep), end of the
  countdown/overlay delay, emergency bypass, call/alarm/charging exemption.
  A late "display off" event never re-arms a lit screen (the real display state is checked).
- hgd.c: 250 ms between the `pass` mark and the injected power press, so the APK can lower the wall
  before the panel lights (otherwise face unlock would not start on the delayed wake-up).
- GuardService: also watches the `pass` mark.
- scripts/admin.sh: root makes the app an active admin (dpm set-active-admin). Called by sync_config.sh
  when enabled + hard_wall. Switching OFF: the app removes its own admin rights (dpm remove-active-admin
  refuses admins that are not testOnly).
- scripts/wallcheck.sh: read-only check (admin active? disabledKeyguardFeatures? wall lines of the trace).

NOT verified on a device (no Android SDK here, only C and shell syntax were checked):
- setKeyguardDisabledFeatures by a non-owner admin on newer Android / HyperOS may be refused
  (the trace then says "wall FAILED"; the old behaviour stays).
- Countdown mode: when the wall comes down with the screen already on, the face scan may need a swipe
  up on the lock screen to start (fingerprint resumes by itself).
