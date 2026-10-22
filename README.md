# JP Subs (Milestone 2: Japanese speech to text)

An open-source Android app that listens to whatever your phone is playing,
turns Japanese speech into text, translates it to English and shows it as subtitles.

**This is Milestone 2.** The app now captures the phone's playback audio, finds
the sentences, and writes the Japanese text on screen. There is no translation
yet (that is Milestone 3).

## Roadmap

- [x] **Milestone 1:** capture playback audio, show a volume bar
- [x] **Milestone 2:** Japanese speech-to-text on the captured audio (this version)
- [ ] **Milestone 3:** translate the Japanese text to English
- [ ] **Milestone 4:** floating subtitle overlay on top of other apps

## How it works

```
phone audio -> voice detector -> pieces of speech -> Japanese recognizer -> text on screen
               (Silero VAD)                          (ReazonSpeech, run by sherpa-onnx)
```

Everything runs on the phone. After the one-time model download it needs no internet.

## What is new in each file

| File | What it does |
|---|---|
| `CaptureService.kt` | The worker. Reads audio, cuts it into sentences, runs the recognizer. Has a `FUTURE HOOK` where translation will plug in. |
| `ModelManager.kt` | Downloads the speech model once (about 155 MB) and remembers where it is. |
| `Transcript.kt` | A small notebook where recognized sentences are stored for the screen. |
| `Tuning.kt` | Numbers you can change to trade speed against accuracy. Start here when you want to experiment. |
| `MainActivity.kt` | The screen: download button, Start/Stop, volume bar, Japanese text. |
| `app/src/main/java/com/k2fsa/sherpa/onnx/*` | The speech engine's Kotlin code, copied unchanged from sherpa-onnx v1.13.8. Do not edit these. |
| `.github/workflows/build.yml` | The GitHub build. It now also fetches the engine libraries and publishes the model files. |

## How to update your repo from Termux

Put `jpsubs-milestone2.zip` in your Downloads folder, then run these one at a time:

```bash
cd ~/downloads
unzip -o jpsubs-milestone2.zip -d jpsubs-milestone1
cd jpsubs-milestone1/jpsubs
git add .
git commit -m "Milestone 2: Japanese speech to text"
git push
```

The `-o` means "overwrite the old files". If git asks for a password, paste your token.

Then open the **Actions** tab on GitHub. This first Milestone 2 build takes longer
(about 5 to 10 minutes) because it downloads the speech engine and the model once.
Later builds are faster.

When it is green, install the new APK from **Releases > Latest build**, the same way
as before. It installs over the old version.

> The Releases page will now show two entries. **Latest build** has the APK you install.
> **Speech model files** holds the model that the app downloads. Do not delete it.
> The repo must stay **public**, or the app cannot download the model.

## How to test it

1. Open **JP Subs**. Tap **Download Japanese model**. Use Wi-Fi if you can: it is about 155 MB.
   If it is interrupted, tap the button again and it continues.
2. Tap **Start listening**, allow the permissions, tap **Start now**.
3. Wait for "Listening…" (the first start takes a few seconds while the model loads).
4. Play a Japanese video or stream, switch back to JP Subs, and watch the text.

Text appears a second or two after someone finishes a sentence. You can long-press
the text to select and copy it, for example into a translator.

The small gray line under the Start button shows how long the last sentence took to
recognize. This is the number to tell Claude when you test on each phone:

- recognized in **less than the length of the speech**: the phone keeps up
- **longer**: the phone is too slow for this model, and subtitles fall behind

## Things that can go wrong

- **Download fails with "not found":** the GitHub build has not finished, or the repo is
  private. Wait for the green check and make sure the repo is public.
- **The app closes or shows an error when you tap Start:** send Claude the red error text
  from the status line.
- **The text is empty but the green bar moves:** the speech may be too quiet or covered
  by music. Try lowering `VAD_THRESHOLD` in `Tuning.kt`.
- **Sentences are chopped into tiny pieces:** raise `MIN_SILENCE_SECONDS` in `Tuning.kt`.
- **Subtitles feel late:** lower `MAX_SPEECH_SECONDS` in `Tuning.kt`.
- **"skipped some speech" appears often:** the phone cannot keep up. Tell Claude which phone it is.
- **The GitHub build fails:** open the failed run in the **Actions** tab, tap the red step,
  and copy the last 20 or so lines of the error to Claude.

## Notes

- Needs Android 10 or newer (tested target: Android 16). The APK only contains the 64-bit ARM
  version of the engine, which both of the target phones use.
- The model needs about 160 MB of free storage on the phone.
- Recognition will make mistakes, especially with music, fast slang or several people talking at once.
- Third-party code and models are listed in `NOTICE.md`.
