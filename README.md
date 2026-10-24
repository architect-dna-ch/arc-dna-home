# ARC-DNA Home

Architect-DNA styled Android launcher for the Pixel 9a.

- Brightness lever right of the clock: HI / MID / LO (drag or tap, snaps with a click), long-press =
  automatic brightness; needs "Modify system settings" once
- F1 mode: tap the clock for a half-screen timing board (live time to the ms, lap counter, sector bars
  over 5 s laps) where an F1 car whooshes past at every lap; tap the board or clock to hide
- Laid out like architect-dna.ch/home: ARCHITECT · DNA row with online status, clock with rust colon,
  German date and greeting, web search, eight line-icon app tiles, project chips, seismograph line
- Long-press empty space = Mediathek (all apps with search) slides up; Back or ✕ closes it
- Musik card (tap it to open the playing app): title, artist and cover of whatever plays (needs notification access), ⏮ ▶ ⏭ (work
  without it via media keys), the output device such as a JBL speaker with "seit HH:MM" and a fox
  message on connect/disconnect, a music volume slider, and a spectrum visualizer (needs the
  microphone permission for Android's Visualizer; nothing is recorded) that also moves the seismograph
- Zuletzt: the 4 (home) / 8 (Mediathek) most recently used apps; needs Settings → Usage access,
  until then a button leads there
- Soft fade behind the navigation buttons; fox and seismograph sit clear of them
- Sections only partly visible above the bottom fade are frosted (blur + fade) until scrolled fully into view
- Kopf-Scroll pro App (Mediathek → Einstellungen): per app AUS / NICKEN / BLICK for other apps such as
  Chrome. Needs the accessibility service "ARC-DNA Scroll" switched on once; it runs the camera as a
  foreground service with a notification while such an app is in front, scrolls with gentle swipes and
  shows a small pill (tap = pause). Taps or typing in the app pause eye scrolling for 4 s
- Kopf-Scroll (button in the Mediathek cycles AUS → NICKEN → BLICK; off by default), all on-device,
  and only while the Mediathek is open (the camera stops when it closes):
  - NICKEN: head tilt via ML Kit face detection; nod up/down to scroll
  - BLICK: eyes via MediaPipe Face Landmarker. A short calibration (dot at top, middle, bottom)
    sets your personal zones: bottom part = scroll down slowly, wide middle band = stop,
    top part / camera = scroll up. Long-press the mode button to recalibrate
  - BLICK rests for 4 seconds after any touch or finger scroll
  - the button shows the mode and ↑ / ↓ / · (‖ = resting) for what the camera currently reads
- "Heute zählt": one focus for the day (resets at midnight)
- Dock of default apps: phone, SMS, camera, browser, maps, mail, calendar, ARC-DNA web home
- All apps with search (Enter opens the first hit), long-press for app info
- The fox: tap = fox call you can hear (synthesized yips, gekkering, barks, like the website),
  long-press = ARC-DNA web home with the AI chat
- Rabbit button (bottom left, above the fox) = Fuchsjagd: a landscape runner where the fox chases the rabbit; tap to jump over
  cubes; distance + record; 3 quick taps or Home twice to leave
- Shake the phone = earthquake
- Swipe right→left = dark, left→right = light for ARC-DNA (colour floods out from the finger);
  long-press the clock → "System folgen" to follow the system again
- Draw a circle on the home screen = turn the screen 90° (clockwise circle turns clockwise:
  portrait → landscape → upside down → other landscape; four circles bring it back)
- Light/dark follows the system, and the ARC-DNA wallpaper switches with it (home + lock screen).
  Long-press the clock to choose: ARC-DNA Live (live wallpaper: on the lock screen a rainbow
  glitter halo around the fingerprint sensor, a rainbow ring around the front camera and rave
  circles on the beat; calm at home), static light/dark, or off

## Updates in the app
Mediathek → bottom line "UPDATES EINRICHTEN": paste a fine-grained GitHub token
(repository access: only arc-dna-home, Contents: Read-only). The launcher then checks every 6 hours,
shows "Update 1.0.x → installieren" on the home screen and installs it with one tap
(Android and Play Protect still ask to confirm). Long-press the line to change the token.

Each build also produces `ARC-DNA-Home.aab` (workflow artifact) for Google Play.

## Install
Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release.
On the phone: open the latest release, download `ARC-DNA-Home.apk`, allow installing from that source,
then Settings → Apps → Default apps → Home app → ARC-DNA.

## Bären-Wecker

The **⏰ WECKER** line under the greeting: tap it to set the time (repeats daily, "Test" shows the
bear right away); long-press switches it on or off. When it rings you are in a dark cave and a bear
walks towards you out of the darkness. Each step shakes the screen and vibrates, the growl gets
louder and it roars every few seconds. **Hold AUFWACHEN for 1 s** to stop it; **SNOOZE** sends it
back for 5 minutes. Rings over the lock screen (needs notifications, and on Android 14+
"Full-screen notifications"); it is re-armed after a reboot or update.

Raising the volume (keys, music slider or speaker) twists the fox around, one spin per step, with its own "wheee" twirl call; lowering it makes the fox droop and whimper, then slowly pick itself up.

Coming back from an app keeps your scroll position on the home screen; Home pressed while already
home still scrolls to the top.

## Home layout (priorities)

Always open: clock + brightness lever, **search**, **Apps**, **Musik**. Folding sections below
(tap the title, ▸ AUF / ▾ ZU, remembered): **Mediathek** (all apps inline, frosted row by row
while scrolling; link to the full Mediathek), **Heute & Wecker**, **Projekte**. **Zuletzt** is
always on, one row under the apps.

**Diagnose:** Mediathek → "DIAGNOSE-LOG TEILEN (ANONYM)" shares device/permission state and a
technical event log (crashes, visualizer and wallpaper events, alarm, updates) as plain text.
No app names, song titles or tokens are logged.
Backup of the previous layout: branch `backup/home-v1.0.35`.

## Releases

Every push to `main` builds and publishes `v1.0.<run>`; only the 10 newest releases are kept.
A commit marked `[final]` is published as "· FINAL" and moves the `final` tag to it.

## Ring light (Kopf-Scroll im Dunkeln)

While head / eye scrolling runs (Mediathek or per app, e.g. Chrome) and the light sensor reads
below ~6 lux, a few pixels of bright white light up along the curved display edge, with LED
spots in the corners, and the screen goes to full brightness so the front camera still sees
your face. Off again above ~15 lux.

## Dynamischer Sperrbildschirm – Einführung

Long-press the clock → "Dynamischer Sperrbildschirm – Einführung": what the lock screen shows,
✓/✗ for each setup step, and a button for the next one. Opens once by itself while the lock
screen is still static.

## 3D cube

With ARC-DNA Live as wallpaper, drag **two fingers** on the home screen to turn the cube on
both axes (left/right = around the vertical axis, up/down = tilt). Let go and it keeps spinning,
slowing down; edges further back are drawn fainter.
