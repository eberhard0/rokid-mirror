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

## DeX mode (1.1.0)

Since One UI 8, Samsung DeX starts on a virtual display created with ADB rights, so the glasses can
be a DeX screen while the phone keeps its own. `tools/rokid-dex.sh` (kept at `~/bin/rokid-dex.sh` in
Termux on the phone) runs scrcpy-server 5.0.1 through the phone's own Wireless debugging with
`new_display=640x480/120` and relays its video socket on port 27184; Rokid Screen tries that port on
the hotspot gateway every few seconds and shows the stream when it appears. Mirror and DeX are
chosen on the phone (chips at the top of Rokid Mirror); the glasses show whichever is running. A
Bluetooth mouse/keyboard paired to the phone drives DeX. The "Start DeX screen" button asks Termux to
run the script (Termux needs `allow-external-apps = true` in `~/.termux/termux.properties`);
otherwise run it in Termux by hand.
