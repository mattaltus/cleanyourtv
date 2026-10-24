# Speaker LEDs

Optional Android TV add-on, maintained by [mattaltus](https://github.com/mattaltus). It keeps the four
indicator LEDs on a TV's built-in speaker bar lit while the TV is on, after the cleanup has disabled
whatever used to light them.

On a TCL 43C725 (Onkyo speaker bar) the four LEDs come on when the standby LED goes off. They are driven
by the Google assistant LED service, `com.google.android.tv.assistant` (`/system/priv-app/LedService`),
and on stock firmware something keeps them in one of that service's patterns. Once the voice packages are
disabled nothing does, and the bar stays dark. The LED service itself exports
`com.google.android.assistant.led.FixedStateLedService` without a permission, so this app binds to it and
asks for a pattern.

## What it does

- While the screen is on, it holds one pattern. Standby darkens the LEDs by itself, so it sends nothing then:
  the service's own `off` pattern is an animation that flashes the LEDs first.
- It starts at boot and after an update. Each time the TV wakes, or a screensaver ends, it lets go of the LED
  service (which then shuts down) and binds a fresh one: sending the pattern the service already holds does
  not relight the LEDs. The LEDs may go dark for about two seconds while that happens.
- After each connect it sets a different steady pattern first (`listening`, or `mute` when `listening` is the
  choice), then the chosen one 2 s later. After a screensaver the LEDs were dark while the LED service still
  ran its animation, and restarts alone left them dark; a real change of pattern relit them. So each wake
  shows the other pattern for about two seconds.
- After a power cut the pattern set on the first connect is accepted but the LEDs stay dark. So after a boot
  it restarts the LED service twice more, 10 s and 60 s after it starts; the second one blinks the LEDs off for
  a moment if they were already on. The add-on itself can't start sooner: TCL holds third-party apps' boot
  broadcast back (about 20 s after `BOOT_COMPLETED` here, `Delay to launch app ... schedule by tboost`).
  Each restart is logged with the seconds since boot.
- The card opens a screen: **OK** switches the LEDs on or off, **Left/Right** pick the pattern live,
  **Back** closes it.

The patterns are the LED service's own: `listening`, `thinking`, `responding`, `timeout`, `mute` and
`error`. The default is `mute`, a steady light that matches the stock look on the 43C725; `listening` is
steady there too. Some of the others animate. The names come from the Google Assistant, so the light no
longer says anything about the microphones: with this add-on it is a power light.

## Setup

Build (JDK 17, Android SDK 34): `./gradlew testReleaseUnitTest lintRelease assembleRelease`, then install as
[`addons/README.md`](../README.md) shows.

- **The LED service must be enabled.** Some lists disable `com.google.android.tv.assistant` with the voice
  packages. Re-enable it with `pm enable --user 0 com.google.android.tv.assistant`; on its own it does nothing.
- **Pick the pattern** on the card screen, or from ADB during setup:
  `tools/tv.py 'am start -n io.github.mattaltus.speakerleds/.LedActivity --es pattern mute'`.
  The default for a build is `led_pattern` in `app/src/main/res/values/config.xml`.
- **TCL firmware blocks start at boot** for apps without TCL's auto-start app op (`TclAppBoot` in logcat:
  `callee_does't_have_OP_AUTO_START_permission`). Allow it once, and write it out so it survives a reboot:
  `tools/tv.py 'appops set io.github.mattaltus.speakerleds APP_AUTO_START allow; appops write-settings'`.
  `appops reset io.github.mattaltus.speakerleds` undoes it.
- Open the app once after installing, so it starts without a reboot.
- TCL firmware also refuses `startForeground` whenever the app is not on screen (`block startForeground` in
  logcat). After a reboot the service then runs as an ordinary started service, and TCL's `FreezeManager`
  freezes it, and the LED service, a minute or so later; a frozen LED service can leave the LEDs dark. The
  add-on also listens in its manifest for TCL's own wake broadcast, `tcl.sys.intent.action.STR_RESUME_COMPLETED`,
  which the firmware delivers to background apps (logcat shows it starting other apps' receivers), as a second
  way to hear about a wake when the frozen app's own `SCREEN_ON` receiver may not run. That path is not
  confirmed on a frozen add-on yet.
  Opening the card makes it a foreground service.
- The LED service connects to its own worker asynchronously and drops calls until then, so after connecting
  the other pattern is set 1.5 s later and the chosen one at 3.5 s and 6 s. Each call is logged
  (`logcat -s SpeakerLeds`). Only these three, seconds apart: the service's animation loop crashes (`LedAnimationLoop.setNextFrame`, NullPointerException) when its pattern is switched several times
  in quick succession.

**Known limit:** while the TV sits idle, `FreezeManager` freezes the LED service a few minutes in (here about 4
minutes before the screensaver started), and the LEDs go dark until the next wake or the end of the
screensaver. The add-on can't keep it awake without being a foreground service, which TCL refuses.

A TV without the LED service shows a note on the card screen and does nothing.

## Permissions

- `RECEIVE_BOOT_COMPLETED`: start again after a reboot.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`: one small **background service** stays bound to
  the LED service while the add-on is on (a foreground service where the firmware allows it). It does no work
  between wakes: no polling, no wake locks. Switching the LEDs off on the card stops it and sets them off.

No network, telemetry, ads or other background work. Nothing under `launcher/` is involved.

## Tested on

TCL 43C725 (Smart TV Pro, BeyondTV4, G03_4K_GB, Realtek R51MT05), Android TV 11 with Google TV, firmware
V8-R51MT05-LF1V620: LEDs on with `mute` and `listening`; after a cold boot (auto-start granted) they came on
without opening the card, and the service was still running 6 minutes later. Standby with the remote darkens
them and they come back on wake, also after `FreezeManager` had frozen the add-on. An earlier version that
only re-sent the pattern on wake left the LEDs dark after the TV had sat in the launcher's clock screensaver
for an hour; this version's restart on wake brought them back. After a 31-minute screensaver the add-on was
thawed and restarted the LED service the moment the screensaver ended, and the LEDs came back on.
After a wall-power cut (power on at 0 s, remote at 10 s, Home at 47 s) the add-on started at about 1:20 and the
LEDs came on at 1:32, at the restart 10 s after it started. On 2026-10-24 the LEDs were dark after a screensaver with the LED service running, and three restarts didn't
relight them; switching to `listening` and back to `mute` did, which is why version 7 does that on every
connect. 4 JVM tests, lintRelease and the release build pass.
