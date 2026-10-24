# ARC-DNA Home

Architect-DNA styled Android launcher for the Pixel 9a.

- Laid out like architect-dna.ch/home: ARCHITECT · DNA row with online status, clock with rust colon,
  German date and greeting, web search, eight line-icon app tiles, project chips, seismograph line
- Long-press empty space = Mediathek (all apps with search) slides up; Back or ✕ closes it
- Musik card: title, artist and cover of whatever plays (needs notification access), ⏮ ▶ ⏭ (work
  without it via media keys), the output device such as a JBL speaker with "seit HH:MM" and a fox
  message on connect/disconnect, a music volume slider, and a spectrum visualizer (needs the
  microphone permission for Android's Visualizer; nothing is recorded) that also moves the seismograph
- Zuletzt: the 4 (home) / 8 (Mediathek) most recently used apps; needs Settings → Usage access,
  until then a button leads there
- Soft fade behind the navigation buttons; fox and seismograph sit clear of them
- Sections only partly visible above the bottom fade are frosted (blur + fade) until scrolled fully into view
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
- Tap empty space = everything fades to a faint ghost and the wallpaper shows; tap again to bring it back
- Shake the phone = earthquake
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
