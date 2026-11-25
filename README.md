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
- **Folders:** add a folder and browse it: its folders, then its comics and
  pictures as thumbnails (a comic shows its cover). Swipe through all the
  pictures in a folder, even one opened from another app (Inkwell asks once
  whether it may see your photos for that).
- **Hold a picture** for its menu: Share, Open with…, Edit, Save a copy…,
  Set as (wallpaper, contact photo), Delete, Slideshow, Actual size, Fit to
  screen, Details.
- **Edit**, as on FerroOS: drag to draw a box, then Crop to it, Black it out
  (what was under it is gone from the copy, not hidden) or Zoom to it; turn
  the picture a quarter either way; Undo step by step. Save writes a new
  file next to the original ("photo-edited.jpg"), with only the pixels: no
  location, camera or other details from the original. The original is
  never changed.
- **Delete** puts the picture in Android's Recycle Bin (Android 11 and newer;
  Android asks you first). On older Androids, and in folders you added that
  Android has no Recycle Bin for, it asks before deleting for good.
- **Reading:**
  - Pages sit side by side, each as tall as the screen: slide sideways
    through them, straight on from one page to the next. A fling glides.
  - Tap anywhere to show or hide the bars: the page slider, and Edit,
    Share, Delete and More.
  - Pinch or double-tap to zoom (then drag up and down too).
  - *Whole pages* shows one whole page to a screen instead, and any slide
    goes to the next.
  - *Right to left (manga)* is kept for each comic.
  - A keyboard's arrows, Page Up/Down and Space turn pages too; Delete
    deletes, E edits.

## Privacy

- **No internet:** Inkwell can't go online at all.
- **Only what you choose:** it sees the files and folders you open with it,
  through Android's own picker or another app's "Open with". It asks to see
  your photos only when you want to swipe through the rest of a folder a
  picture was opened from (or delete one); on Android 14 you can let it see
  just some.
- **Handing a picture on** (Share, Open with, Set as) gives the other app a
  copy of just that picture, which Inkwell clears at its next start.
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
