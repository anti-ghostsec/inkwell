# Inkwell

A picture and comic book viewer for Android: quick, private, and good at
`.cbz` comics. It's the Android version of FerroOS's viewer (`ferro-viewer`),
and it reads comics the same way.

## What it does

- **Comic books (`.cbz`)** are read in place. Each page is unpacked in memory
  when it's shown, and nothing is ever unpacked to storage. Pages go in the
  order people count, so "page2" comes before "page10". The hidden files Macs
  add (`__MACOSX`, `._page.jpg`) are skipped. A page that claims to be small
  but unpacks to more than 256 MB (a "zip bomb") stops there, and only that
  page is affected.
- **Picks up where you left off.** Every comic you open is listed under
  *Continue reading*, with how far you've got.
- **Pictures:** PNG, JPEG, WebP, GIF, BMP, HEIF and AVIF (as your Android
  version supports them). On Android 9 and newer, animated GIFs and WebPs move
  and photos turn the right way up. Very large photos are shown at a size
  Android can draw.
- **Folders:** add a folder and browse its folders, comics and pictures. Swipe
  through all the pictures in a folder.
- **Reading:**
  - Tap the right or left side to turn the page, or swipe.
  - Tap the middle to show the bars and the page slider.
  - Pinch or double-tap to zoom. A zoomed-in page is looked around before a
    swipe turns it.
  - *Fit the width* shows tall pages at full width and scrolls down them
    before turning the page.
  - *Right to left (manga)* is kept for each comic.
  - Volume keys can turn pages, and so can a keyboard's arrows, Page Up/Down
    and Space.

## Privacy

- **No internet:** Inkwell asks for no permissions at all.
- **Only what you choose:** it sees only the files and folders you open with
  it, through Android's own picker or another app's "Open with".
- **No backups:** what it keeps (the comics you're reading, your page, the
  folders you added) stays on the phone and isn't put in cloud backups or
  device transfers.
- **Hidden by default:** it's kept out of screenshots, screen recording and
  the recent-apps view. Turn this off in the viewer's ⋮ menu.
- **Easy to undo:** hold a comic or folder on the home screen to remove it.
  Inkwell then gives back its permission to read it.

## Install

Download `inkwell-android.apk` from the
[latest release](../../releases/latest) and open it. Android asks once
whether to allow installing apps from your browser or file manager. One APK
works on every phone and tablet with Android 8 or newer.

Each release says the SHA-256 of the certificate it's signed with. Every
build is signed with the same key, so an update installs over the last one.

## Building

```bash
cd android
./gradlew testDebugUnitTest assembleRelease
```

This needs the Android SDK (platform 36) and JDK 21. Every push to `main` is
tested, built, signed and published as the newest release by
`.github/workflows/release.yml`. The build never sees the signing key: a
separate job signs and publishes.

## License

MIT or Apache-2.0, at your choice.
