# services/audio: the app's sound

`audio.py` (teslalinux-audio.service, user `tesla`) does two things:

1. **The car's stream.** The app plays into the PipeWire null sink `android` (the default sink: ATL's
   `AudioTrack` opens ALSA "default", which `pipewire-alsa` sends there). While a car page listens on
   `/sockets/audio` (nginx, port 8080), ffmpeg records `android.monitor` and encodes FLAC in
   fragmented MP4, the stream shape of upstream's relay (`docs/UPSTREAM-NOTES.md`).
2. **A speaker on the Pi.** Settings > Audio > "Where Waze's sound plays": **Car** (the browser only,
   the default), **Pi speaker** (only the Pi), **Both**.

## Where the sound plays

```
ATL AudioTrack ─> ALSA default ─> PipeWire "android" (null sink, default) ─┬─> monitor ─> ffmpeg ─> car page   (Car, Both)
                                                                           └─> pw-loopback ─> Pi output         (Pi speaker, Both)
```

- **The Pi's outputs** come from WirePlumber's ALSA monitor (`/etc/wireplumber/wireplumber.conf.d/teslalinux.conf`,
  written by `tools/pios/install.sh`): every ALSA card except HDMI (nothing is ever attached there),
  each sink at priority 1000, below `android`'s 3000, so `android` stays the default sink. The 3.5 mm
  jack exists because the firmware config has `dtparam=audio=on` (the installer's config.txt block;
  the card is probed by the `snd_bcm2835` module that loads anyway: WirePlumber starts in 298 ms with
  it, 288 ms without, nothing else on the boot path changes).
- **The choice**: the newest USB sound card (`device.bus = usb`), else the jack (`bcm2835 Headphones`),
  else any other ALSA output. `audio.py` follows the graph with `pw-dump --monitor`, so a USB card
  plugged in or out moves the sound within a second.
- **The route**: `pw-loopback -n teslalinux-speaker` from the monitor of `android` into the chosen sink,
  restarted when the choice changes, stopped in "Car". Both ends are passive (`node.passive = true`):
  the loopback never keeps the graph or the card running, so the card opens only while the app plays
  (on a Pi 4 the jack runs S16_LE, 2 channels, 48 kHz, 512-frame periods) and suspends 3 s after.
  No reconnect, no fallback: the loopback must never play into the default sink, which is its own source.
- **Volume**: the loopback's stream volume (`wpctl set-volume`, the cubic scale of wpctl and of
  Android's volume curves), 0 to 100 on the page; a newly found card is set to full once (WirePlumber
  starts new cards at 40 %), so the page's slider is the one level.
- **Pi speaker only**: the car's stream stops (ffmpeg is not run, connected pages get nothing).

## Files

| Path | What |
|---|---|
| `/var/lib/teslalinux/state/audio.json` | `{"output": "car"\|"pi"\|"both", "volume": 0..100}`, written by `services/api/api.py` (POST `/api/audioOutput`), read by `audio.py` twice a second |
| `$XDG_RUNTIME_DIR/teslalinux-audio.json` (`/run/tesla/`) | what `audio.py` chose: the setting, the Pi output in use, the outputs there are, whether it plays now, whether the car stream runs; GET `/api/audioOutput` returns it |

## What the app sees

ATL's `AudioManager` (`android.media.HostAudioDevices` in the ATL fork) lists the sound server's sinks
and sources through libpulse (pipewire-pulse): `android` is the car's audio bus (`TYPE_BUS`, from its
`device.form-factor = car`), a USB card `TYPE_USB_DEVICE` (or `TYPE_USB_HEADSET`), the jack
`TYPE_LINE_ANALOG` (its port type is "analog"). `getAudioDevicesForAttributes` returns the default
output and every output a loopback of it reaches (the same `node.link-group` on both ends), and
`AudioDeviceCallback`s hear each device added or removed.

## Checking it on a Pi

```sh
curl -s 127.0.0.1:8081/api/audioOutput                       # the setting and the Pi output in use
sudo -u tesla XDG_RUNTIME_DIR=/run/tesla wpctl status          # sinks, the loopback's two streams
cat /proc/asound/card0/pcm0p/sub0/status                       # the jack: RUNNING while the app speaks
cat /proc/asound/card0/pcm0p/sub0/hw_params
```
