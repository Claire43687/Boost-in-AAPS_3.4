# Boost in AAPS 4.0

Boost is an independently maintained variant of [AAPS](https://github.com/nightscout/AndroidAPS)
4.0.0 that adds the Boost APS plugin. It is not a release of, or supported by, the AAPS project.
You build it yourself and you are responsible for how you use it. AGPL-3.0, as AAPS.

This line is Boost 6.2.0: the V6 meal logic and the Boost high temp, with each SMB sized by the
oref tiers.

## Installing over your current build

Android installs over an existing AAPS only when the new APK is signed with the same key. The
workflow below uses the signing key already held in your repository's secrets. Android 12 or later
is required.

Before you start, export your settings in AAPS and keep your current APK. The move to 4.0 changes
the database and the settings store, and neither can be read by an older build. To go back, you
reinstall the older APK after uninstalling and import the export.

The same applies to a standard AAPS 4.0 build. It installs over Boost, because the package and
version code are the same, but it cannot open Boost's database and the app stops at start, so the
loop does not run. To move from Boost to standard AAPS, export your settings, uninstall Boost,
install AAPS and import the export.

### You already build AAPS in the browser

1. In your existing AndroidAPS fork on GitHub, choose Add file, Create new file, and name it
   `.github/workflows/boost-ci.yml`.
2. Paste in the contents of [boost-ci.yml](.github/workflows/boost-ci.yml) and commit.
3. Under Actions, run Boost CI and select the release.
4. Install `boost-<release>.apk` from the `AAPS/<release>` folder in your Google Drive, over your
   current app.

Your fork's `KEYSTORE_SET` and `GDRIVE_OAUTH2` secrets are used as they are.

### You are new to browser builds

Fork this repository, enable Actions, and create the two secrets with the
[AAPS browser build instructions](https://androidaps.readthedocs.io/en/latest/SettingUpAaps/BrowserBuild.html).
Then run Boost CI. To reuse the key from another repository, run CI Keystore Export there first.

### You build in Android Studio

Check out the release tag and build `fullRelease` with your existing keystore.

## After installing

Open Config Builder and check that OpenAPS Boost is the selected APS. Check your maximum IOB and
SMB settings, then watch the first cycles in the APS tab before relying on it.
