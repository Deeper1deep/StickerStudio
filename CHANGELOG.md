# Changelog

## Unreleased - fast GIF conversion

- GIFs are now read with an own sequential decoder (`GifDecoder`) instead of `android.graphics.Movie`,
  which was extremely slow per frame (conversion took minutes, the editor preview stayed on one frame).
- Conversion predicts the size from a few sample frames and starts at the first quality setting that
  should fit, instead of fully encoding and discarding several too-big attempts.
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
