# JP Subs (Milestone 1: audio level test)

An open-source Android app that will listen to whatever your phone is playing,
turn Japanese speech into text, translate it to English and show it as subtitles.

**This is Milestone 1.** It only does the first job: capture the phone's playback
audio and show a volume bar. If the bar moves while a YouTube stream plays, the
hardest permission step is solved and we can build the rest on top.

## Roadmap

- [x] **Milestone 1:** capture playback audio, show a volume bar (this version)
- [ ] **Milestone 2:** Japanese speech-to-text on the captured audio
- [ ] **Milestone 3:** translate the Japanese text to English
- [ ] **Milestone 4:** floating subtitle overlay on top of other apps

## What is in each file

| File | What it does |
|---|---|
| `app/src/main/java/com/example/jpsubs/MainActivity.kt` | The screen. Asks for permissions, has the Start/Stop button, draws the volume bar. |
| `app/src/main/java/com/example/jpsubs/CaptureService.kt` | The worker. Runs in the background, records the playback audio, measures loudness. Has a `FUTURE HOOK` comment where speech-to-text will plug in. |
| `app/src/main/res/layout/activity_main.xml` | How the screen looks. |
| `app/src/main/res/values/strings.xml` | All the text shown in the app. |
| `app/src/main/AndroidManifest.xml` | The app's "ID card": name, permissions, which screens and services exist. |
| `app/build.gradle.kts` | App settings: Android version support, libraries. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Project-wide build settings. |
| `.github/workflows/build.yml` | Tells GitHub to build the APK for you after every push. |

## How to build it with no computer (Termux + GitHub)

1. On github.com, create a new empty repository, for example `jpsubs`.
   Do not add a README or other files to it.
2. Make sure you have a GitHub token that can push code **and workflow files**.
   Classic token: tick `repo` and `workflow`. Fine-grained token: give the repo
   "Contents: Read and write" and "Workflows: Read and write".
3. In Termux, run these one by one (replace `YOUR_USERNAME`):

```bash
pkg update && pkg install git unzip
termux-setup-storage
cd ~
unzip ~/storage/downloads/jpsubs-milestone1.zip
cd jpsubs
git init -b main
git add .
git commit -m "Milestone 1: audio level meter"
git remote add origin https://github.com/YOUR_USERNAME/jpsubs.git
git push -u origin main
```

When git asks for a password, paste your token (not your GitHub password).

4. Open the repo on GitHub, tap the **Actions** tab. A run called "Build APK"
   should be going. It takes about 3 to 6 minutes.
5. When it turns green, tap the **Releases** section on the repo's main page,
   open **Latest build**, and download `app-debug.apk`.
6. Open the downloaded file. Android will ask you to allow installs from your
   browser. Allow it. If Play Protect warns about an unknown app, choose
   "More details" then "Install anyway" (it is your own app).

## How to test it

1. Open **JP Subs** and tap **Start listening**.
2. Allow the microphone permission. (Android requires it for capturing playback
   audio. The app never records your microphone.) Allow notifications too.
3. On the "Start recording or casting?" popup, tap **Start now**.
4. Open a YouTube stream, then switch back to JP Subs. The green bar should move
   with the sound.

If the bar stays flat while sound is clearly playing:

- Try the same video in **Chrome** instead of the YouTube app. If the bar moves
  there but not in the YouTube app, then the YouTube app blocks audio capture
  and we will need a different plan.
- Make sure the notification says "Listening to phone audio".
- Make sure the media volume is not zero.

## If the GitHub build fails

Open the failed run in the **Actions** tab, tap the red step, and copy the last
20 or so lines of the error. Paste them to Claude and it can fix the code.
Fixing a failed first build is very normal.

## Notes

- Needs Android 10 or newer (tested target: Android 16).
- The APK is a *debug* build, which is fine for personal use and testing.
- Edit files on GitHub's website (pencil icon) or in Termux, then push. Every push
  builds a fresh APK automatically.
