# JP Subs (version 0.3.2: easier updates and an app icon)

![JP Subs icon](docs/app-icon.png)

## What is new in 0.3.2

**Updates now install over the old version.** Until now, every GitHub build was signed with a
brand-new random key, and Android refuses to update an app unless the new version is signed with
the same key as the old one. That is the "package conflicts with an existing package" message.
Now every build is signed with the same key file (`app/debug.keystore`), and each build gets a
higher version number automatically.

> **You must uninstall JP Subs one last time** before installing this version, because the version
> on your phone was signed with an old random key. Uninstalling also deletes the downloaded models,
> so the app will download them again (about 185 MB). From the next update on, you just install the
> new APK over the old one, and your models and settings stay.

**App icon.** Your icon is now the app's icon. A copy of the original is in `docs/app-icon.png`.

The keystore file is a personal *development* key and is public in this repo. That is fine for your
own phones. If the app is ever published for other people, switch to a private key stored in
GitHub Secrets.

---

# Milestone 3.1: settings and debug view

An open-source Android app that listens to whatever your phone is playing,
turns Japanese speech into text, translates it to English and (soon) shows it as
floating subtitles.

## Roadmap

- [x] **Milestone 1:** capture playback audio, show a volume bar
- [x] **Milestone 2:** Japanese speech-to-text on the captured audio
- [x] **Milestone 3:** translate the Japanese text to English
- [x] **Milestone 3.1:** live settings and a debug view to tune it on real streams (this version)
- [ ] **Milestone 4:** floating subtitle overlay on top of other apps

## How it works

```
phone audio -> voice detector -> speech pieces -> Japanese recognizer -> translator -> screen
               (Silero VAD)                       (ReazonSpeech,         (Google ML Kit)
                                                   run by sherpa-onnx)
```

Everything runs on the phone. After the one-time model downloads it needs no internet.

## What is new in 3.1

You told Claude about four problems on real streams: fast talkers, several people talking,
long stretches of noise, and noise that makes the app output nothing. Fixing these needs
experimenting, and rebuilding the app for every experiment takes 5 minutes. So this version
lets you change the important numbers **while the app is running**.

| File | What changed |
|---|---|
| `Settings.kt` | New. The numbers you can change live. They are remembered between launches. |
| `DebugInfo.kt` | New. Notes about what the detector hears and which pieces came back empty. |
| `MainActivity.kt` | New "Show settings" panel with sliders, a debug checkbox and a reset button. |
| `CaptureService.kt` | Reads the settings live, can boost quiet audio, rebuilds the detector when you move a detector slider. |
| `activity_main.xml` | Layout for the panel. |

## How to update your repo from Termux

Put `jpsubs-milestone3c.zip` in your Downloads folder, then run these one at a time:

```bash
cd ~/downloads
unzip -o jpsubs-milestone3c.zip -d jpsubs-milestone1
cd jpsubs-milestone1/jpsubs
git add .
git commit -m "Fixed signing key, app icon"
git push
```

Wait for the green check in the **Actions** tab. **Uninstall the old JP Subs from your phone**,
then install the APK from **Releases > Latest build**. This is the last time you need to uninstall.

## How to use the settings panel

Tap **Show settings**. Move a slider while a stream is playing, and watch what changes.
Detector sliders rebuild the detector when you let go of them (this takes a moment).

| Slider | What it does | Try this when |
|---|---|---|
| Detector threshold | How sure the detector must be that someone is talking | **Quiet voices missed:** lower it (0.3). **Noise treated as speech:** raise it (0.6 to 0.7). |
| Pause that ends a sentence | How long a pause cuts a sentence | **Too many tiny pieces:** raise it. **Subtitles feel late:** lower it. |
| Longest piece before cutting | Cuts speech that has no pauses | **Fast talkers, or noise that never stops:** lower it (2 to 3 s). |
| Volume boost | Makes quiet audio louder before it is analyzed | **Quiet streamers:** try 2x to 3x. |
| Hide short results | Hides one-letter results like "え" | Raise it to hide more clutter. |

**Tip:** if you can, shrink the app into a small window next to the stream (like in your
screenshots) so you can move sliders and watch the video at the same time.

## How to read the debug lines

The lines under the Start button (switch off with "Show debug info"):

- **Detector: hearing speech for 6.0 s**: the detector thinks someone has been talking for that long.
  If this number keeps hitting the maximum while only noise plays, lower the "longest piece"
  slider and raise the threshold.
- **Pieces: ... sent, ... with text, ... empty ...**: how many pieces the detector handed over,
  and how many the recognizer turned into words. Many **empty** pieces means the detector sent
  audio but the recognizer heard no words in it (noise, overlapping voices, or too fast).
- **Recent: 6.0s ok | 6.0s empty | 2.1s ok ...**: the length and result of the last pieces.
  Lots of pieces at exactly the "longest piece" length means the detector never finds a pause.
- **skipped**: the phone could not keep up.

If problems continue, send Claude these debug lines from the moment it fails (a screenshot is
perfect), plus the slider values you tried. That tells Claude whether the detector or the
recognizer is the problem.

## Things that can go wrong

- **The translation download fails:** it needs internet and Google's servers. Try again on Wi-Fi.
- **Only Japanese appears, no English:** the translator could not start. The status line says why.
- **The English sounds odd:** the translator works one sentence at a time with no context, and
  streamers use slang and names. Names of streamers are often written wrongly by the recognizer.
- **Several people talking at once:** the recognizer is built for one voice at a time, so overlapping
  voices often give nothing. The settings can reduce the damage but not remove it.
- **The GitHub build fails:** open the failed run in the **Actions** tab, tap the red step,
  and copy the last 20 or so lines of the error to Claude.

## Notes

- Needs Android 10 or newer (tested target: Android 16). The APK only contains the 64-bit
  ARM version of the speech engine.
- The repo must stay **public**, or the app cannot download the speech model.
- Third-party code and models are listed in `NOTICE.md`.
