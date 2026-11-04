# Rokid Mirror

Shows a phone's screen in the Rokid Glasses (the green micro-LED ones). Two apps:

- **Rokid Mirror** (`phone/`): on the phone. Asks for screen-recording consent, captures the
  screen, scales it to the glasses' 480×640, encodes H.264 and streams it over TCP.
- **Rokid Screen** (`glasses/`): on the glasses, installed through Hi Rokid → Toolbox → Glasses App
  Management like any sideloaded APK. Advertises itself by mDNS (`_rokidmirror._tcp`), accepts the
  phone and decodes straight to a SurfaceView, fitted and centred.

No Miracast, no DeX: the glasses can't be a Miracast receiver, so wireless DeX is not possible;
this is plain mirroring. Both devices must be on the same Wi-Fi, or the glasses on the phone's
hotspot. Video only; audio stays on the phone (or in the glasses over Bluetooth).

Wire format: see `Protocol.kt` (identical in both modules). Folding/unfolding sends a new stream
header; the glasses rebuild the decoder.

Build: GitHub Actions builds both signed release APKs on every push to `main`.
