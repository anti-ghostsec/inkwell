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
- **Picks up where you left off**, if you want (it asks the first time;
  *Remember my page…* in a comic's menu):
  - *Page only*: your page (and manga or not) is kept under a fingerprint
    of the comic, made from what's in it and scrambled with a key made on
    this phone. No names, no addresses, no dates. Open the comic again,
    from anywhere, renamed or moved, and it's at your page.
  - *And list*: also lists the comics you open in Inkwell under *Continue
    reading* (this keeps their names and where they are). One opened from
    another app (a file manager's "Open with") still opens at your page,
    but isn't listed: only its fingerprint is kept.
  - *Nothing*: nothing is kept; choosing it forgets everything kept.

  A listed comic whose file Android no longer lets Inkwell see is found
  in the folders you added, or Inkwell asks you to find it again (and
  keeps it from then on).
- **Pictures:** PNG, JPEG, WebP, GIF, BMP, HEIF and AVIF (as your Android
  version supports them). On Android 9 and newer, animated GIFs and WebPs move
  and photos turn the right way up. Very large photos are shown at a size
  Android can draw.
- **Folders:** add a folder and browse it: its folders, then its comics and
  pictures as thumbnails (a comic shows its cover). Swipe through all the
  pictures in a folder, even one opened from another app (Inkwell asks once
  whether it may see your photos for that).
- **Make comics (`.cbz`)**, as FerroOS's Files does: in a folder you added,
  *Make a comic* puts its pictures into "Folder.cbz" beside it (inside it,
  for the folder you added itself); hold a folder for a comic of it, or hold
  pictures to pick some. The pages go in the order people count (`p2`
  before `p10`), stored as they are, under their names alone, with no dates
  or other details. Nothing is overwritten, and the pictures stay where
  they were.
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
    across a page freely. Held sideways, they sit one under the other
    instead, each as wide as the screen: slide up to read down, a fling
    rolls on, and the next page comes the same way, holding back at each
    page's end. At its edge the page holds back: push on past a
    point (or a little, then flick) for the next page; less, and it springs
    back. A fling stops at the page's edge.
  - Tap anywhere to show or hide the bars: the page slider, and Edit,
    Share, Delete and More. *Keep the bars hidden* stops taps bringing
    them; hold a page for the menu (with the same setting, to undo it).
    Comics and pictures each have their own.
  - Pinch or double-tap to zoom (then drag up and down too).
  - Pictures are shown whole, one to a screen, and any slide goes to the
    next; comics' pages are as tall as the screen. Each has its own
    setting: *Whole pictures* and *Whole pages*.
  - *Right to left (manga)* is off until you turn it on, and kept for each
    comic; a comic you haven't chosen for goes the way you chose last.
  - A keyboard's arrows, Page Up/Down and Space turn pages too; Delete
    deletes, E edits.

## Privacy

- **No internet:** Inkwell has no permission to go online, so Android
  won't let it: nothing is sent anywhere, and it never checks for updates.
  Every release build is checked for that, and for no network code in
  Inkwell's own code; it uses no libraries.
- **No logs:** Inkwell writes nothing to the phone's log, not even if it
  crashes (it just closes, rather than Android writing what went wrong,
  which could name your files).
- **Nothing written without asking:** Inkwell keeps your choices (its
  settings) and the folders you add. Your page in comics is kept only as
  you choose when it first asks (above): by scrambled fingerprint alone,
  or with names for *Continue reading*, never with dates or times. Small pictures in a folder are made in memory only.
  Files are written only when you ask: an edit or a copy you save, a comic
  you make, or a picture you hand on (below).
- **Only what you choose:** it sees the files and folders you open with it,
  through Android's own picker or another app's "Open with". It asks to see
  your photos only when you want to swipe through the rest of a folder a
  picture was opened from (or delete one); on Android 14 you can let it see
  just some, but then it can't show whole folders: it says so, and opens
  Android's settings for *Allow all* if you want (or ⋮ → *Show the whole
  folder* later).
- **Handing a picture on** (Share, Open with, Set as) gives the other app a
  copy of just that picture, which Inkwell clears at its next start.
- **No backups:** what it keeps (your settings, the folders you added, and
  the comics you're reading if you said so) stays on the phone and isn't put in cloud backups or
  device transfers.
- **Hidden by default:** it's kept out of screenshots, screen recording and
  the recent-apps view. Turn this off in the viewer's ⋮ menu.
- **Only from apps, not web pages:** a link on a web page can't open
  Inkwell.
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
