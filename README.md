# ARC-DNA Home

Architect-DNA styled Android launcher for the Pixel 9a.

- Clock with rust colon, German date and greeting
- "Heute zählt": one focus for the day (resets at midnight)
- Dock of default apps: phone, SMS, camera, browser, maps, mail, calendar, ARC-DNA web home
- All apps with search (Enter opens the first hit), long-press for app info
- The fox: tap = fox call, long-press = ARC-DNA web home with the AI chat
- Shake the phone = earthquake
- Light/dark follows the system, and the ARC-DNA wallpaper switches with it (home + lock screen);
  long-press the clock to turn auto-wallpaper off or on

## Install
Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release.
On the phone: open the latest release, download `ARC-DNA-Home.apk`, allow installing from that source,
then Settings → Apps → Default apps → Home app → ARC-DNA.
