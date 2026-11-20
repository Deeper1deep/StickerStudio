# Changelog

## 1.0.41 - Imported packs section + full-count import

- New **Imported packs** section with its own entry in the bottom navigation: packs imported from files (.wastickers / .zip) live here, fully separated from "My packs". Each pack shows its thumbnail, name, real sticker count, static/animated badge and import date, with its own options menu (open, rename, re-import / update, delete).
- Every pack now knows its **source** (created / imported / unknown legacy), stored in the database - never guessed from the pack's name or contents. The migration keeps every existing pack, sticker, thumbnail, name and setting untouched; legacy packs stay where they were.
- **Import keeps the whole file now**: a pack with 31, 60 or 75 stickers imports completely, in the original order. It was silently cut at 30 before, because the import reused WhatsApp's export limit. Storage and export limits are now separate; WhatsApp's 30-sticker rule is only checked when a pack is actually sent to WhatsApp, and reported honestly.
- Importing the exact same file twice no longer creates a duplicate pack: the file is fingerprinted and the existing pack is shown instead.
- Re-import refreshes an imported pack in place (same pack, no copies); the original file is never modified.
- Not device-tested yet: verified by CI build + unit tests only.

## 1.0.39 - Export section

- New **Export stickers** section with its own entry in the bottom navigation bar (next to Home), a screen of its own, and its own selection / order / run state. Nothing about export appears in the packs list, pack screen, save-to-pack or the editors (guarded by a unit test).
- Tick several of your packs; the app remembers the **order you ticked them in** (not the list order). Un-tick and tick again = goes to the end.
- **Export order** list with numbers, drag-and-drop handle and a remove button (removing never deletes the pack).
- "Export N packs to WhatsApp" sends the packs one by one through WhatsApp's official add-pack screen. WhatsApp always asks the user to confirm each pack, so the app moves to the next pack when you come back. A pack counts as **added only if WhatsApp confirms it**; backing out is shown as failed, never as success.
- Progress screen (pack X of N, added / remaining, per-pack status), pause, cancel, continue, retry and skip. Invalid packs are explained and do not stop the others.
- The selection order and a running export are saved; after the app is closed the export comes back **paused** and never restarts by itself.
- Not device-tested yet: verified by CI build + unit tests only.

## 1.0.38 - static editor: picture layers, sharpen, lines & arrows

- **Picture layers**: add any photo on top of the sticker (Layers tab); move, scale, rotate, fade, reorder and lock it like text. Up to 8 per sticker; stored with the project so it re-opens.
- **Sharpen** slider in Effects (non-destructive, applied at render time; transparent edges untouched).
- New shapes: **line** and **arrow**; shapes can be **outline only**.
- **Pen opacity** (each stroke fades as one piece).
- **Snap guides**: dragging a layer sticks to the sticker's centre lines with a visible guide.
- Old projects open unchanged (new fields have defaults).
- Not device-tested yet: verified by CI build + unit tests only.

## 1.0.37 - animated editor: frame timeline

- **Edit frames** in the GIF / video editor: a strip of frames where you can delete, duplicate and
  reorder frames, set how long each frame lasts, speed the whole animation up or down, and undo / redo.
  The animation can never exceed 10 s; at least 2 frames always remain.
- The timeline only stores which moment of the clip each frame shows, so long clips are not loaded
  into memory; the export keeps your order and per-frame timing.
- Animated stickers can be re-opened from the pack (Edit): crop, caption and the edited timeline come back.

## 1.0.36 - static editor: layers panel, text options, picture adjustments

- New **Layers** tab: show/hide, lock, reorder (front/back), duplicate, rename and delete layers.
  A locked layer cannot be moved by accident.
- Text: opacity, left/centre/right alignment, letter spacing (not applied to Arabic so letters stay
  joined), line spacing and an optional background box.
- Shapes: opacity.
- Effects: brightness, contrast, saturation, warmth, exposure, blur, black & white and sepia.
  They are applied when rendering (non-destructive) and saved with the project.
- Build: a full GitHub Actions storage quota no longer fails a good build.

## 1.0.32 - share dialog hardening and crash report

- The QR is now built off the main thread and any failure is contained instead of closing the app.
- If the app closes unexpectedly, the next launch shows the error with a "send report" button
  (nothing is sent automatically).

## 1.0.31 - QR share, reorder, multi-select, undo

- QR code with the app icon in the centre (pack screen and Settings) so a friend can download the app;
  the pack itself is sent as a `.wastickers` file from the same dialog.
- Drag stickers to reorder them; tap the emoji on a sticker to change it.
- Multi-select mode: delete, change emoji, copy or move many stickers at once.
- Copy / move stickers to another pack, and duplicate a whole pack.
- Undo after deleting a sticker or a pack.

## 1.0.30 - share, open files, background conversion, update notices

- A notification when a new version is published (checked twice a day in the background).
- A "what is new" message after the app was updated.
- The update popup now shows the real release notes from this file.

- "Share" a picture, GIF or video from any app to Sticker Studio (one file opens the editor, several are
  added to the selected pack).
- Tap a `.wastickers` / `.zip` file (or share it) to import it as a pack.
- Conversion now runs with a foreground service and a progress notification (Android 13+ asks for the
  notification permission once), so leaving the app does not stop it; a "ready" notification appears if
  the app is in the background when it finishes.
- The first pack is pre-selected in the create section, so picking a file no longer asks for a new pack
  when packs already exist.

## 1.0.28 - update test

- Smaller APK: only phone architectures (arm64-v8a, armeabi-v7a) are packaged.
- Multi-select import, new dark design, fast GIF/MP4 decoding, .wastickers import/export.

## Unreleased - new visual design

- New design system: deep-ink dark theme with violet + aqua accents (now the default; light theme kept,
  same system), WhatsApp-green action colour, gradient progress bars.
- Home: gradient "create" hero card with one big call-to-action, outlined source tiles, pack cards that
  show the first four stickers.
- Conversion screen: progress card with percentage and estimated time left.

## Unreleased - import / export .wastickers

- New "Import" button on the home screen: creates a pack from a `.wastickers` or `.zip` file
  (title/author, emojis and order are read when present; valid WhatsApp WebP files are kept as they are,
  other pictures and non-512 static WebP are fitted into proper stickers; max 30, the rest is reported).
- Packs are now saved as `.wastickers` (ZIP with title.txt, author.txt, tray, numbered .webp, pack.json).
- Several files can be picked at once; each becomes its own pack, with one summary at the end.
- `PackArchive` (pure Kotlin) + `PackArchiveTest`.

## Unreleased - fast GIF conversion

- GIFs are now read with an own sequential decoder (`GifDecoder`) instead of `android.graphics.Movie`,
  which was extremely slow per frame (conversion took minutes, the editor preview stayed on one frame).
- Conversion predicts the size from a few sample frames and starts at the first quality setting that
  should fit, instead of fully encoding and discarding several too-big attempts.
- MP4 / other videos are decoded in order with MediaExtractor + MediaCodec (frames shrunk to <= 960 px,
  rotation and colour range handled) instead of one slow `getFrameAtTime` per frame; the old reader
  stays as an automatic fallback if the fast one fails on a device.
- New unit test `GifDecoderTest` (frames checked against Pillow, including transparency and disposal).

## Unreleased - one-screen home

- Removed the bottom navigation bar. Home now has a small settings button on top, the create section
  below it and the existing packs under that (rename / delete / WhatsApp action on each card).
- Settings opens from that button and has a back arrow.
- Pack screen: grid thumbnails are static and downsized (animation only in the big preview), which
  removes the small stutter while scrolling.

## 1.0.0 — unreleased
- Pack screen is now a grid of 30 slots; each empty slot has a "+" that opens the gallery (pictures, GIFs and videos). One file opens its editor; several files are added in one go (GIFs / videos are converted to animated stickers, whole clips up to WhatsApp's 10 s limit, with progress). The "+" in the top bar was removed.
- "Update in WhatsApp" appears when a pack that is already in WhatsApp has been edited since it was last sent.
- Smoother animated preview: up to 90 frames (was 30), timed from the display clock, and no half-built loop while frames are still being prepared.
- "Add to WhatsApp" is replaced by "Added to WhatsApp" while the pack is installed there, and comes back after it is removed (checked each time the app returns to the front).
- Credits card in Settings (tap opens the author's WhatsApp chat).
- WebM decoding no longer waits on stalled decoders (prefers the software VP9 decoder, feeds ahead, fails fast). A one-frame GIF opens in the normal picture editor.
- WebM / video support (VP9 WebM is demuxed and decoded frame by frame, so files without a seek index animate, and a separate alpha stream keeps transparency): file type is detected from the file contents (not only the MIME type), then converted like a GIF to an animated WebP sticker. Transparency inside WebM stickers is not preserved (Android decodes them without alpha).
- Telegram-style update popup at launch (version, size, changelog: Update now / Later / Skip). Every CI build on main now publishes a test release with `update.json`, so the app sees it.
- Cairo font bundled (SIL Open Font License) for Arabic and Latin text.
- Redesign of all screens (violet + white) with new filled/outlined bottom-navigation icons (launcher icon unchanged), Home, Create, Packs, Pack detail (WhatsApp readiness banner), Settings, Updates, bottom navigation.
- Static and animated stickers can now share one pack. Static ones are exported as non-moving 2-frame animated WebPs because WhatsApp needs a pack to be all static or all animated; originals are untouched.
- First version: pack management (Room), static sticker editor, background removal (on-device),
  animated stickers (GIF/video → animated WebP), WhatsApp / WhatsApp Business export,
  GitHub Releases updater with SHA-256 + signature checks, Arabic/English UI.
