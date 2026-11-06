# Boost in AAPS 4.0

Boost is an independently maintained variant of [AAPS](https://github.com/nightscout/AndroidAPS)
4.0.0 that adds the Boost APS plugin. It is not a release of, or supported by, the AAPS project.
You build it yourself and you are responsible for how you use it. AGPL-3.0, as AAPS.

This line is Boost 6.3.0: the V6 meal logic and the Boost high temp, with each SMB sized by the
Boost tiers as in Boost 6.2.0 for AAPS 3.4.2.6.

## Dosing changes from Boost 6.2.0

- There is one Boost plugin, and it runs V6. If you ran plain Boost on 3.4, you move to V6 dosing
  when you install this, and your sensitivity settings are converted once on the first loop cycle.
- Percent scale is on at 200 % and no longer has a setting. Where 3.4 had stored it as off, it
  stays off.
- The two "Max minutes of basal to limit SMB" settings are held at no more than 15 (standard) and
  20 (UAM). Boost lowers a higher value and tells you; it never raises one. These settings limit
  only the SMB that oref sizes: Tier 8, and every SMB while Boost is off, such as at night. V6's
  meal doses and Boost's other tiers are not limited by them.
- In a meal state, V6 used to give its dose on a cycle where V1 had suspended for a low. It no
  longer does. V6's rapid-rise check now takes the largest of the latest change, the short average
  and the long average, as V1 does, where it used to take the latest change only.
- The lowering of the low-glucose threshold on an accelerating rise needs glucose to be rising, and
  never goes below your low-glucose setting.
- Hypo Caution above 1.0 lowers the smallest dose V6 keeps when the hypo risk is high.
- Sleep detection:
  - A sleep that begins in the 90 minutes before your night window is held, unless steps, or heart
    rate with steps, show you are up again.
  - In the 90 minutes before your night ends, walking ends sleep: steps over the last hour reaching
    your sleep-in steps setting, with at least 100 of them in the last 15 minutes.
  - Watch steps are assessed more conservatively, each step being counted once.
  - Health Connect step top-ups no longer count as a reason to wake.
- The insulin-need watch compares the last 5 days with your usual, at 1.5 standard deviations.
- During a meal, V6 used to move to its cautious state (RECOVERING, 0.4× of the dose) as soon as a
  rise slowed, while glucose was still climbing. It now stays in the meal state until the rise is
  below 3 mg/dL (0.17 mmol/L) per 5 minutes, and returns to it when glucose rises faster than
  8 mg/dL (0.44 mmol/L) per 5 minutes.
- The early primer starts on a sustained rise, a short average of at least 3 mg/dL (0.17 mmol/L) per
  5 minutes, where it used to need acceleration. It no longer fires on a second rise once a meal has
  been committed.
- RECOVERING is capped at the committed cap, so it never gives more in one cycle than the meal state
  does.

## Other changes

- The APS screen has a Heart rate section and an Auto config section, which shows Boost's automatic
  settings in words.

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

### You have a fork of Boost-in-AAPS_3.4

This repository was Boost-in-AAPS_3.4, and its Boost 6.2.0 for AAPS 3.4.2.6 is now on the branch
`master-3.4.2.6`. A fork of it already holds the secrets Boost CI needs, including the separate
keystore secrets.

- To stay on 3.4, do not sync your fork. Its `master` is still the 3.4.2.6 line and Branch CI
  builds it as before.
- To move to 4.0, sync your fork. GitHub says your `master` has diverged and offers to discard your
  commits; accepting replaces it with this `master`, which brings Boost CI. Then run Boost CI and
  install as above. After the sync, Branch CI is gone from your fork and 3.4 can no longer be built
  from it, so keep your current APK and a settings export.

### You are new to browser builds

Fork this repository, enable Actions, and create the two secrets with the
[AAPS browser build instructions](https://androidaps.readthedocs.io/en/latest/SettingUpAaps/BrowserBuild.html).
Then run Boost CI. To reuse the key from another repository, run CI Keystore Export there first.

### You build in Android Studio

Check out the release tag and build `fullRelease` with your existing keystore.

## After installing

Open Config Builder and check that OpenAPS Boost is the selected APS. Check your maximum IOB and
SMB settings, then watch the first cycles in the APS tab before relying on it.
