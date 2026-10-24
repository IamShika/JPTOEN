# Releasing JP Subs (no computer needed)

The APK is built by GitHub Actions. To sign it with **your own private key** (so only you can
publish updates), do this once. You can do all of it in Termux on your phone.

## 1. Make the key (once)

```bash
pkg install openjdk-17 gh
cd ~
keytool -genkeypair -v -keystore jpsubs-release.jks -alias jpsubs \
  -keyalg RSA -keysize 4096 -validity 36500
```

`keytool` asks for a password (twice) and some details (name and so on; anything is fine).
Write the password down. When it asks for the key password, press Enter to use the same one.

**Back up `jpsubs-release.jks` somewhere safe** (cloud storage, another device). If you lose it
or its password you can never publish an update that installs over the old app. **Never put it
in the repo or send it to anyone.** (`.gitignore` already blocks `*.jks`.)

## 2. Save it as four GitHub secrets (once)

Still in Termux. `gh auth login` asks you to log in (choose GitHub.com, HTTPS, and paste your
token; the token needs the `repo` scope).

```bash
gh auth login
base64 -w0 jpsubs-release.jks > jpsubs-release.b64
gh secret set KEYSTORE_BASE64 --repo IamShika/JPTOEN < jpsubs-release.b64
gh secret set KEYSTORE_PASSWORD --repo IamShika/JPTOEN     # it asks you to type the password
gh secret set KEY_ALIAS --repo IamShika/JPTOEN             # type: jpsubs
gh secret set KEY_PASSWORD --repo IamShika/JPTOEN          # type the same password again
rm jpsubs-release.b64
```

(Without Termux: GitHub website > your repo > Settings > Secrets and variables > Actions >
New repository secret, with the same four names.)

From now on every build is signed with your key. In the build log, the step
"Prepare the release key" says "Release key found, signing with it."

## 3. Publish a release

Push your code as usual, wait for the green check, then in the folder with your repo:

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub builds again and creates **Releases > JP Subs v1.0.0** with `JP-Subs.apk` and automatic
release notes. Use a new tag (v1.0.1, v1.1.0 ...) for each release, and raise `versionName` in
`app/build.gradle.kts` to match. Every normal push to `main` still updates the **Latest build**
page (a pre-release for testers).

## How users get the update

Publishing a tag (step 3) is what makes the app offer the update: it asks GitHub for the newest
official release and compares its tag (for example `v1.0.1`) with the installed `versionName`.
So always raise `versionName` in `app/build.gradle.kts` to match the tag, and sign with the same
release key, or Android will refuse to install the update over the old app. The "Latest build"
page is a pre-release and is never offered.

## Notes

- The app ID is `io.github.iamshika.jpsubs`. Older test builds used `com.example.jpsubs`; that is
  a different app and can be uninstalled (it frees its downloaded models).
- `app/debug.keystore` is only a fallback so forks without secrets can still build (testing only).
