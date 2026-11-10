# aniliphy

An Android app to **watch anime, read manga and read light novels**, with your
progress synced to [AniList](https://anilist.co). It is the phone version of
[anilixy](https://github.com/lexviy/anilixy), the terminal app, written in
Kotlin with Jetpack Compose.

## Features

- **Anime**: press *Watch* and it opens the next episode after your AniList
  progress, or carries on where you stopped. It has its own player (Media3),
  with subtitles, quality, Sub/Dub, skipping of the opening and ending,
  autoplay, gestures for seeking, brightness and volume, picture-in-picture
  and sound in the background. Episodes can be downloaded, and when one won't
  play on a source the others are tried by themselves.
- **Manga**: a reader with page, two-page and webtoon modes. Chapters can be
  saved to read offline, and the other sources are tried when the preferred
  one doesn't have a series.
- **Light novels**: an EPUB/PDF reader for the books in a folder you choose,
  counting chapters for your AniList progress. Books that aren't there can be
  looked for on the light novel sources.
- **New episodes and chapters**: checked every half hour in the background,
  with a notification for each, and shown on Home and in the Anime and Manga
  tabs. Manga you read on AniList but never opened here are checked too, when
  a source has a series with exactly the same title.
- **Extensions**: episodes, chapters and books come from sources, and sources
  come from extensions you install separately (see below). The app itself
  doesn't come with any.
- **AniList**: your lists, profile, stats and favourites, search for anime,
  manga, characters, staff and studios, and an editor for list entries.
- **Sync with anilixy**: where you stopped (episode and minute, manga chapter
  and page, light novel chapter, chosen sources) is shared with the desktop app
  through a private GitHub gist.
- **Extras**: themes, animated backgrounds, shaders, app icons, a guide inside
  the app and a guest mode that works without an account.

## Requirements

- Android 8.0 (API 26) or newer.
- An AniList account to sync progress, or the guest mode.

## Installing

The app isn't on the Play Store. Build the APK (below), copy it to the phone
and open it. Android asks once to allow
installing apps from that source. Then install the extensions you want the
same way.

## Building

You need JDK 21 and the Android SDK (platform 37 and its build tools).

```sh
./gradlew assembleDebug        # the APK lands in app/build/outputs/apk/debug/
./gradlew installDebug         # or straight onto a phone connected by adb
./gradlew testDebugUnitTest    # the unit tests
./gradlew lintDebug            # the lint checks
```

### Release APK

`./gradlew assembleRelease` makes a smaller, faster APK in
`app/build/outputs/apk/release/`. It is signed with the key named in
`keystore.properties` at the root of the project, which stays out of git:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=aniliphy
keyPassword=...
```

Keep the keystore and that file backed up: a new APK only installs over the old
one when both are signed with the same key. Raise `aniliphy.versionCode` (and
`aniliphy.versionName`) in `gradle.properties` for every APK you hand out:
Android refuses to install an APK over one with the same or a higher code.

## First run: logging in to AniList

1. Create an app at [anilist.co/settings/developer](https://anilist.co/settings/developer),
   with `https://anilist.co/api/v2/oauth/pin` as its Redirect URL.
2. Paste its Client ID in the app and open the token link.
3. Authorize, copy the token and paste it back in the app.

Or pick *Continue as guest*: you can watch and read, with the progress kept on
the phone only.

## Using it

The tabs at the bottom are Anilist, Anime, Manga, Light Novel and Settings.
*Settings → Guide* explains each part: how episodes and chapters are chosen,
the player and readers, downloads, notifications and where things are kept.

The light novels folder is chosen in the Light Novel tab or in
*Settings → Light Novel settings*. Keep the series name in the file names,
for example `Classroom of the Elite Vol. 1.epub`, so books are matched to
their titles.

## Extensions

aniliphy is a player and a reader: it doesn't know any site. The sources come
from extensions, which are APKs with no screen that the app finds on the phone
and loads. *Settings → Extensions* lists the installed ones and the sources
each brings, and *Preferred source* in the Anime and Manga settings picks the
one tried first. Installing, updating or removing an extension shows up right
away, without restarting the app.

An extension's code runs inside aniliphy, so only extensions signed with the
same key as the app are loaded by themselves. Any other shows up as not
trusted, with a *Trust* button that shows its key first.

Progress is kept by the app, not by the extensions: removing one keeps your
place, and downloaded episodes and chapters still open without it.

### Making one

An extension is an Android app that depends on the `source-api` module of this
project as `compileOnly`, and declares in its manifest:

```xml
<uses-feature android:name="aniliphy.extension" android:required="true" />
<application android:hasCode="true" android:label="My sources">
    <meta-data android:name="aniliphy.extension.class" android:value=".MySources" />
    <meta-data android:name="aniliphy.extension.api" android:value="1" />
</application>
```

`MySources` implements `Extension`, which hands the app lists of `AnimeSite`,
`MangaSite` and `NovelSite`. Each source has an `id` that is saved with
progress, so it must never change, and makes its requests through the
`SourceServices` it is given, so cookies and robot checks keep working. OkHttp,
Jsoup, kotlinx.serialization and Kotlin can be `compileOnly` too: at runtime
the extension uses the app's copies. The interfaces are documented in
[`source-api`](source-api/src/main/kotlin/com/aniliphy/source).

## Syncing with anilixy

AniList counts already sync through AniList. *Settings → Sync with anilixy*
also keeps the same place in both apps: the episode and the minute, the manga
chapter and page, the light novel chapter and the sources picked.

1. Press *Make a token on GitHub*: it opens GitHub with only the **gist**
   permission ticked. Make the token and copy it.
2. Paste it here and in anilixy (*Settings → Sync*), then press *Sync now*.

The first sync makes a private gist named `anilixy-sync.json`, and the other
app finds it by that name, so the same token is all either needs. For each
title the newer place wins, and nothing is deleted. With *Sync on opening and
leaving* on, the phone syncs when the app opens and when you leave it. The
token is kept apart from the settings, so exporting them never puts it in a
file. *Forget the token* stops syncing and leaves the gist on GitHub.

## Where your data lives

Everything stays in the app's private storage: the login, settings, progress,
saved sources, downloads and caches. *Settings → Storage* shows the space
used, lists the downloads to delete them one by one, clears the cache, exports
or imports the settings and resets the app.

When a site answers with a "checking you're not a robot" page, the app opens
it in a small browser window to be passed by hand, then keeps that site's
cookie for its own requests. Android backups are turned off for the app, so the AniList
token isn't copied off the phone.

## Disclaimer

aniliphy doesn't host or link to any content, and it doesn't come with any
source: it is an AniList client with a player and readers. What it plays or
shows depends entirely on the extensions you choose to install, which are not
part of this project. You are responsible for what you install and for
following the laws of your country. Support the official releases when you can.

## License

[GNU Affero General Public License v3.0](LICENSE).
