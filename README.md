# Radar Alert

Android app (package `br.radar.alerta`) that sounds an alert shortly before speed, red-light and
average-speed cameras. It runs beside a navigation app such as Google Maps or Waze. Camera data is
from OpenStreetMap, loaded on demand along your heading and cached; a static mirror
([zhyph/radar-tiles](https://github.com/zhyph/radar-tiles)) is used first, with public Overpass
servers as fallback.

## Features

* Alert before the camera, timed by speed (early / normal / late), with 6 selectable sounds; red-light
  cameras play it twice, average-speed sections lower and slower.
* Map view that tilts and rotates to your heading while moving; markers differ by camera type.
* Speed and next-camera limit panel, home-screen widget, Quick Settings tile, and a small
  picture-in-picture window when you leave the app.
* Options: only warn when over the limit, camera types, ignore cameras facing away (experimental,
  uses the OSM `direction` tag), start alerts when the app opens.
* Offline download of an area, recent alert history, and "report a camera" (anonymous OSM note).
* English, Portuguese and Spanish.

Camera warnings are restricted or illegal in some countries. You are responsible for following local
law. Camera data can be incomplete or out of date (small towns often have unmapped cameras).

## Build

```sh
./gradlew assembleDebug testDebugUnitTest
```

Needs the Android SDK (compileSdk 36, build-tools 36.0.0) and JDK 21; put `sdk.dir` in
`local.properties`. Min SDK 30.

## Testing alerts without driving

Mock GPS over adb: `adb shell appops set 2000 android:mock_location allow`, then add a test provider
and feed positions with `cmd location providers set-test-provider-location`. Check the audio with
`adb logcat` (`RadarAlerta: ALERT ...` followed by an `AudioPlaybackConfiguration` start for the
app's uid). Restore with `appops set 2000 android:mock_location default`.

## Data

Camera data &copy; OpenStreetMap contributors (ODbL 1.0). Map tiles &copy; OpenStreetMap contributors.
