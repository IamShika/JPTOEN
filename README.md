# JP Subs (version 0.6.0: choose your recognizer)

## What is new in 0.6.0

1. **The floating box follows rotation.** Its position is now stored as a fraction of the screen,
   so after rotating it stays on screen (it re-centres sideways). Drag it again if you want.
2. **Pick the speech recognizer** in Settings > "Speech recognizer". Five are listed:
   ReazonSpeech (built in), SenseVoice (~230 MB), Whisper small (~370 MB), Qwen3-ASR 0.6B (~940 MB),
   Fun-ASR Nano (~950 MB). Each has its own **Download** and **Delete** button, and the choice takes
   effect the next time you tap Start. If the chosen model is not downloaded, the app falls back
   to ReazonSpeech and says so in the status line.
3. The first GitHub build after this update downloads each model from the sherpa-onnx project and
   puts it on your Releases page (a few minutes each). If one of those steps fails, the APK still
   builds; open that step in Actions and send Claude the log.

Translators: still Google ML Kit for all recognizers. Better translators are the next step.

## 0.5.0

## What is new in 0.5.0 (Milestone 4)

The subtitles can now float on top of other apps (YouTube, Twitter/X, anything).

1. Install the update, open the app, tap **Show settings**.
2. Tick **Floating subtitles over other apps**. Android opens a screen called
   "Display over other apps": switch **JP Subs** on, then press Back.
3. Tap Start and play a stream. A dark box with the English (and Japanese below) appears.
   It hides itself 6 seconds after the last sentence. **Drag it** with a finger to move it.

New file: `SubtitleOverlay.kt`. New permission: "display over other apps" (you grant it yourself).
Known limits: the box has fixed text size and hide time for now (settings for those can come
next), and it will not show over some secure screens (banking apps, DRM video).

## 0.4.2

**0.4.2:** fixes short words (よし, やばい) being shown as "." — the full stop added in 0.4.1 confused the translator on very short text. It is now only added to longer sentences, and a result with no letters is retried or hidden. More reactions added to the table in `TranslationManager.kt`.

## 0.4.1 notes

## What is new in 0.4.1

Your screenshots showed that almost every piece was cut at exactly the "longest piece" limit
(2.0 s + 0.2 s padding on each side = 2.4 s). Sentences were chopped in the middle, and the
translator was asked to translate half sentences, which is why it gave odd English.
**This is mostly a settings problem, not only a translator problem.** Try: threshold 0.30,
longest piece 5 to 6 s, pause 0.30 s. With noise suppression on, you no longer need 0.15.

Code changes (translator only):
- Spaces the recognizer puts inside sentences are removed before translating.
- A full stop is added so ML Kit treats the text as a sentence.
- If the English still contains Japanese letters (a translator failure), it retries once, and
  if it fails again only the Japanese is shown instead of garbage.
- Short reactions (えっと, はい, あはは, うわ ...) and leading filler words are handled by a small
  table in `TranslationManager.kt`, which you can extend.

---


![JP Subs icon](docs/app-icon.png)

## What is new in 0.4.0

Some words were being missed. Two things can cause that, and this version addresses both.

**1. Noise suppression for the voice detector.** The audio is cleaned (GTCRN, a very small noise
removal model that runs in the speech engine the app already contains) before the voice detector
listens to it, so background noise is less likely to fool it or hide a voice.
By default the **recognizer still gets the original audio**, not the cleaned one. Noise removal can
leave small distortions that confuse a speech recognizer, so the safe design is to clean the audio
only for the part that decides *where* sentences are. There is a checkbox to test the cleaned audio
for the recognizer as well.

**2. Padding.** The detector sometimes starts a sentence a moment late or ends it a moment early,
which clips the first or last sound of a word. Now each piece is given 0.2 s of extra audio on
both sides (adjustable). Pieces that were cut only because they hit the maximum length are not
padded at the end, so the same words are not repeated.

Also new: a "Shortest sound counted as speech" slider (short words like はい can be thrown away by the
detector), and new debug lines for noise suppression and padding.

**Why not RNNoise?** RNNoise is a good noise remover, but the repo you linked is a desktop
plug-in built on it. On Android it would have to be compiled as native code, which is a bigger and
riskier change to build blind. The speech engine already contains a similar noise remover, so
that is tried first. If it is not good enough, RNNoise is the next thing to try.

**The speed cost** is small: the noise remover is tiny compared with the recognizer. Check the gray
"Last piece" line against your earlier numbers to see for yourself.

**Update:** this is a normal update. It installs over your current version (the signing fix from
0.3.2 is in place), keeps your settings and models, and downloads one extra file of about 0.5 MB when
you open the app (tap the download button).

### How to test it

1. Open the settings. **Noise suppression for the detector** should be ticked.
2. Play a noisy stream and compare with the box unticked. Watch the **Recent** line and the
   **Pieces** counts, and check whether fewer words are missing.
3. Try **Also give the recognizer the cleaned audio** on and off, and see which reads better.
4. Check the **Padding** debug line: it should say mostly `ok`. If it says `mismatch` for every
   piece, the padding is switched off for safety and Claude needs to know.

---

## What was new in 0.3.2

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

Put `jpsubs-v0.4.0.zip` in your Downloads folder, then run these one at a time:

```bash
cd ~/downloads
unzip -o jpsubs-v0.4.0.zip -d jpsubs-milestone1
cd jpsubs-milestone1/jpsubs
git add .
git commit -m "Noise suppression and padding"
git push
```

Wait for the green check in the **Actions** tab, then install the APK from
**Releases > Latest build** over the old one. No uninstall needed.
Open the app and tap the download button to fetch the small noise-suppression file.

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
