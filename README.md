# JP Subs (Milestone 3: Japanese to English)

An open-source Android app that listens to whatever your phone is playing,
turns Japanese speech into text, translates it to English and (soon) shows it as
floating subtitles.

**This is Milestone 3.** The app now captures the phone's playback audio, finds the
sentences, writes the Japanese text, and translates it to English. The English is shown
in bold with the Japanese underneath. The floating overlay is the next milestone.

## Roadmap

- [x] **Milestone 1:** capture playback audio, show a volume bar
- [x] **Milestone 2:** Japanese speech-to-text on the captured audio
- [x] **Milestone 3:** translate the Japanese text to English (this version)
- [ ] **Milestone 4:** floating subtitle overlay on top of other apps

## How it works

```
phone audio -> voice detector -> speech pieces -> Japanese recognizer -> translator -> screen
               (Silero VAD)                       (ReazonSpeech,         (Google ML Kit)
                                                   run by sherpa-onnx)
```

Everything runs on the phone. After the one-time model downloads it needs no internet.

## What is new in this version

| File | What it does |
|---|---|
| `TranslationManager.kt` | New. Downloads and runs Google's on-device Japanese to English translator. |
| `Transcript.kt` | Now stores the Japanese and English of each sentence. |
| `CaptureService.kt` | After recognizing a sentence it translates it. Has a `FUTURE HOOK` where the overlay will plug in. |
| `MainActivity.kt` | Shows English in bold with Japanese underneath. |
| `ModelManager.kt` | The download button now also fetches the translation model. |
| `Tuning.kt` | New setting `MIN_CHARACTERS` to hide one-letter noises. |
| `app/build.gradle.kts` | Adds the ML Kit translation library. |

Unchanged from Milestone 2: the speech engine files in `app/src/main/java/com/k2fsa/sherpa/onnx/`
(do not edit these) and the GitHub build in `.github/workflows/build.yml`.

## How to update your repo from Termux

Put `jpsubs-milestone3.zip` in your Downloads folder, then run these one at a time:

```bash
cd ~/downloads
unzip -o jpsubs-milestone3.zip -d jpsubs-milestone1
cd jpsubs-milestone1/jpsubs
git add .
git commit -m "Milestone 3: translate Japanese to English"
git push
```

Then open the **Actions** tab on GitHub and wait for the green check (a few minutes).
Install the new APK from **Releases > Latest build**. It installs over the old version.
The speech model you already downloaded is kept.

## How to test it

1. Open **JP Subs**. A button appears to download the translation model (about 30 MB).
   Tap it and wait for it to finish.
2. Tap **Start listening**, allow the permissions, tap **Start now**.
3. Play a Japanese video or stream and watch the English appear.

The gray line under the Start button shows how long the last sentence took to recognize
and to translate. Please tell Claude those numbers, and how good the English looks.

## Things that can go wrong

- **The translation download fails:** it needs internet and Google's servers. Try again
  on Wi-Fi. If it keeps failing, send Claude the message shown.
- **Only Japanese appears, no English:** the translator could not start. The status line
  says why (for example "translation unavailable"). Send it to Claude.
- **The English sounds odd or wrong:** expected. The translator works one sentence at a
  time with no context, and streamers use slang. This is the main thing to improve next.
- **Earlier problems** (download not found, empty text, sentences chopped up, subtitles late,
  "skipped some speech") are explained in the Milestone 2 notes: change numbers in
  `Tuning.kt` or tell Claude.
- **The GitHub build fails:** open the failed run in the **Actions** tab, tap the red step,
  and copy the last 20 or so lines of the error to Claude.

## Notes

- Needs Android 10 or newer (tested target: Android 16). The APK only contains the 64-bit
  ARM version of the speech engine.
- The repo must stay **public**, or the app cannot download the speech model.
- Third-party code and models are listed in `NOTICE.md`.
