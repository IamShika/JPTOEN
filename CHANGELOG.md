# Changelog

## 1.0.0
- First public release. MIT license.
- New app ID `io.github.iamshika.jpsubs` (installs next to old test builds, which can be uninstalled).
- Release builds signed with a private key kept in GitHub Secrets (docs/RELEASING.md);
  pushing a tag like v1.0.0 publishes a versioned release with `JP-Subs.apk`.
- In-app update check: Update, or remind me in 1 to 7 days, or never (Tuning > Other).
- Tested on Realme 12 Pro+ and Oppo A96.

## 0.9.1
- Fixes the build error in 0.9.0 (progress bar style attribute in the new layout).

## 0.9.0
- New dark interface with three tabs (Live, Models, Tuning) and a first-run guide
  ("Set up for me" downloads everything and picks defaults).
- Shorter README; version history moved here.

## 0.8.0
- Translator picker: Google ML Kit (offline), Google Translate, DeepL, AI chat model
  (any OpenAI-compatible service). Online failures fall back to ML Kit. "Test translator" button.

## 0.7.0
- Backup loudness detector for high-pitched voices and sudden loud sounds; Loudness debug line.

## 0.6.x
- Recognizer picker with download/delete: ReazonSpeech, SenseVoice, Whisper small, Qwen3-ASR, Fun-ASR Nano.
- Floating box follows rotation and fits its text. Leaked control text and invented Chinese
  words from the newer recognizers are filtered out.

## 0.5.0
- Floating subtitles over other apps.

## 0.4.x
- Noise suppression (GTCRN) for the detector, padding around each piece, "shortest sound" slider.
- Translator tidy-up: reactions table, no full stop on very short words, failed answers hidden.

## 0.3.x
- Live settings sliders and debug view. Fixed signing key so updates install over old versions.
  App icon.

## 0.1 to 0.2
- Milestones 1 to 3: capture playback audio, Japanese speech recognition, translation.
