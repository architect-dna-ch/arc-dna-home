# ARC-DNA Home

Architect-DNA styled Android launcher for the Pixel 9a.

- Laid out like architect-dna.ch/home: ARCHITECT · DNA row with online status, clock with rust colon,
  German date and greeting, web search, eight line-icon app tiles, project chips, seismograph line
- Long-press empty space = Mediathek (all apps with search) slides up; Back or ✕ closes it
- Kopf-Scroll (button in the Mediathek cycles AUS → NICKEN → BLICK; off by default), all on-device,
  only while the launcher is on screen:
  - NICKEN: head tilt via ML Kit face detection; nod up/down to scroll
  - BLICK: eyes via MediaPipe Face Landmarker; look at the bottom edge to scroll down slowly,
    the middle to stop, the top edge / camera to scroll up
  - the top bar shows ◉ mode and ↑ / ↓ / · for what the camera currently reads
- "Heute zählt": one focus for the day (resets at midnight)
- Dock of default apps: phone, SMS, camera, browser, maps, mail, calendar, ARC-DNA web home
- All apps with search (Enter opens the first hit), long-press for app info
- The fox: tap = fox call, long-press = ARC-DNA web home with the AI chat
- Tap empty space = everything fades to a faint ghost and the wallpaper shows; tap again to bring it back
- Shake the phone = earthquake
- Light/dark follows the system, and the ARC-DNA wallpaper switches with it (home + lock screen);
  long-press the clock to turn auto-wallpaper off or on

## Install
Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release.
On the phone: open the latest release, download `ARC-DNA-Home.apk`, allow installing from that source,
then Settings → Apps → Default apps → Home app → ARC-DNA.
