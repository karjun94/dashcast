# DashCast

Mirror an Android phone's screen to **any web browser** on the same Wi-Fi, with touch control and sound.
Nothing needs installing on the viewing side: a laptop, a tablet or a car's built-in browser (for example a Tesla's)
just opens an address.

It mirrors whatever is on screen, so it works with any app: maps, music, video, games and so on.

**Download:** get the latest APK from [chopsignpdf.online/dashcast](https://chopsignpdf.online/dashcast/).
Android 10 or newer.

It replaces the Termux + ADB + ws-scrcpy setup:

| | ws-scrcpy | DashCast |
|---|---|---|
| Needs ADB / wireless debugging | Yes (ports change on reboot) | No |
| Needs Termux / Node.js | Yes | No |
| Touch control | Via ADB | Android Accessibility (one-time switch) |
| Video in browser | H.264 decoders | JPEG frames on a `<canvas>` (no `<video>` element) |

## How it works

```
any app on the phone --screen--> DashCast --Wi-Fi--> any browser
                                    ^                     |
                                    +-- taps / swipes ----+
```

* **CaptureService** uses Android's MediaProjection API to capture the screen and JPEG-encode frames at up to 30 fps
  on three encoder threads. It also captures app sound (AudioPlaybackCapture) for the browser.
* **FrameServer** is a small built-in HTTP + WebSocket server on port 8080, with no external libraries. The browser
  acknowledges each frame before it gets the next one, so the delay doesn't build up on a slow link.
* **TouchService** is an Accessibility service that replays taps, long-presses and swipes sent by the browser.
* **index.html** is the viewer. It letterboxes the picture and has Back and Home buttons.

## Build

1. Install Android Studio (Koala or newer) and open this folder.
2. If Android Studio offers to create or upgrade the Gradle wrapper, accept.
3. **Build → Build App Bundle(s)/APK(s) → Build APK(s)**, then install the APK on the phone
   (USB, or copy the file over and open it).

Requirements: Android 10+ (minSdk 29). Tested to compile against API 34.

## First-time setup on the phone

1. **Settings → Accessibility → DashCast touch control → On**.
2. **Developer options → Stay awake** (while charging). Capture stops when the screen is off.
3. **Battery → DashCast → Unrestricted** so Android doesn't kill it.
4. Turn on the phone's **Wi-Fi hotspot** and turn off its auto-off timer.

## Every drive

1. Open the app you want to show.
2. Open **DashCast → Start casting** and choose **Entire screen** when Android asks.
3. Switch back to that app.
4. On the viewing device (e.g. the car): join the phone's hotspot, open the browser, and go to the `http://…:8080` address DashCast shows.
   Bookmark it. Use the hotspot address (usually labelled `wlan1`, `ap0` or `swlan0`).

## Sound

Tap **Sound** in the viewer to play the phone's media/game audio through the browser.
Allow the audio permission when DashCast first opens (it records app playback, not the microphone).

* Unpair or disconnect the phone's Bluetooth from the car, or you'll hear the sound twice (Bluetooth plus browser).
* Phone calls and apps that opt out of capture can't be streamed. Use Bluetooth for calls.
* `?lat=0.15` in the URL sets the audio buffer in seconds. Lower means less delay, higher means fewer dropouts.

## Car mode

Some car browsers (Tesla's, for one) refuse private addresses like 192.168.x.x. Switch **Car mode** on in the app
before casting: DashCast then also answers at **http://6.6.6.6:8080** over the phone's hotspot. The address is the same
every time, so bookmark it once in the car.

It uses a local-only VPN (Android asks once) that claims just that address. No other traffic goes through it and
nothing leaves the phone. Other VPNs, such as Tailscale, pause while it's on.

Navigation voice and calls can't be captured by Android apps, so in a car use Bluetooth for sound and leave the
viewer's Sound off.

**Using DiPlay for CarPlay?** In DiPlay's settings, set the audio stream to **3** (Android's media stream,
`STREAM_MUSIC`, the same number on every phone). Its navigation prompts and music then play as media sound that
DashCast can capture. Tested working.

## Phone screen while casting

In the app, under **Phone screen while casting** (both need Touch control on):

* **Orientation:** Auto, Landscape or Portrait. Locks to the side the phone is on when casting starts, so the stream
  doesn't change shape when the phone moves. You can also set it from the viewer's rotate button.
* **Brightness:** Lowest dims the phone's backlight for less heat and battery. The stream in the car stays bright.

Both are released when casting stops.

## Tuning

Set **frame rate**, **resolution** and **image quality** in the app under **Stream quality**. Changes apply right away
while casting. You can also add these to the viewer URL, e.g. `http://192.168.43.1:8080/?q=50&fps=30&edge=800`. Bookmark the URL that works best.
They apply until casting is restarted.

* `edge` (960): longest side of the image in px. 800 means less delay, heat and data; higher means sharper text.
* `q` (55): JPEG quality. Lower is faster, higher is sharper.
* `fps` (30): frame-rate cap. 15 is plenty for maps.

The stats line at the bottom left of the viewer shows where the bottleneck is:
`fps` is frames drawn in the browser; `cap` is how often the phone screen changed; `enc N@Xms` is frames encoded
and the encode time per frame. If `cap` is low, the app on screen is the limit. If the encode time is high,
lower `q` or `edge`. If both look fine but `fps` is low, the limit is Wi-Fi or the browser.

Defaults live at the top of `CaptureService.kt`; `FrameServer.MAX_IN_FLIGHT` (3) is how many frames can be
encoding or sent ahead of the browser.

## Known limits

* **Browser audio is media only.** Calls can't be captured, and the browser adds about 0.15 s of audio delay.
  Bluetooth is still the most reliable option for calls.
* **Secure screens show black.** Apps that block screenshots (FLAG_SECURE) can't be captured.
  If part of an app shows black, that's the cause.
* **The address can change.** Newer Android versions may pick a different hotspot subnet. Check the app if the bookmark stops working.
* **Heat.** The app on screen, JPEG encoding, the hotspot and charging all at once warm an old phone. Keep it out of the sun.
* **One-way gestures.** Each swipe is replayed when your finger lifts, so drags show up just after you finish them.
  Map panning and scrolling work, but live "follow the finger" dragging isn't supported.
* Use it while parked or let a passenger operate it. Don't interact with it while driving.

## Notes

* DashCast has no third-party dependencies.

## License

Apache License 2.0. See [LICENSE](LICENSE).

DashCast is an independent project. It isn't affiliated with or endorsed by Tesla, Apple (CarPlay) or DiPlay.
Those names are used only to describe compatibility.
