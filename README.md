# artiest

A drawing app for the **Wacom MovinkPad 11** (DTH-A116, Android 14).

Bitmap layers, textured brushes (sketch pencil / pen / felt marker), layer
operations, colour wheel, PNG-JPG export, reference-image import, customizable
toolbars, undo-redo — with pen latency treated as the constraint everything
else is designed around.

Full architecture and the phased build plan: [`docs/analysis.html`](docs/analysis.html).

## Status: Phase 0 — hardware spike

Not the app. A measuring instrument, in `:spike`, that answers the questions
the rest of the plan rests on **before** anything is built on top of them:

| Question | Where it is answered |
|---|---|
| What does the Pro Pen 3 actually report? | **Pen** tab |
| How much latency can this device reach? | **Latency** tab |
| What SoC and GPU is in a MovinkPad? | **Device** tab |
| Does a 4096×4096 canvas fit in a GL texture? | **Device** tab |
| Does permission-free MediaStore export work? | **Export** button |

Phase 0 is a **go/no-go gate**. If the latency numbers don't clear the bar, the
engine decision in section 04 of the analysis changes before it costs anything.

## Building

There is no Android SDK, Gradle, or `adb` on this machine — only JDK 21. Easiest
path is **Android Studio**, which supplies all three:

1. Install Android Studio (`yay -S android-studio` on Arch, or JetBrains Toolbox).
2. Open this directory. Studio will generate the Gradle wrapper and prompt for
   the SDK packages it needs (compileSdk 35, build-tools).
3. Enable developer mode on the MovinkPad: **Settings → About tablet →** tap
   *Build number* seven times, then **Developer options → USB debugging**.
4. Plug in over USB-C and **Run**.

To do it headlessly instead, install `android-sdk-cmdline-tools-latest` and
`gradle`, set `ANDROID_HOME`, then `gradle wrapper && ./gradlew :spike:installRelease`.

> Measure the **release** variant. A debuggable build carries enough overhead to
> muddy a latency reading. It is signed with the debug key so it installs
> directly.

## Running the measurement

### Pen tab — what the digitizer reports

Press **Reset stats**, then in one session:

1. One slow stroke, light → heavy → light, about five seconds. Slow matters:
   pressure-level estimation needs many distinct values.
2. Tilt the pen right over and draw again.
3. Hover without touching.
4. Flip to the eraser end and draw.
5. Hold each barrel button while drawing.

Read off:

- **sample rate** — the true digitizer rate. Expect well above the 90 Hz panel.
- **samples/event** — above 1 proves the history buffer carries real data. If it
  is ~1.0, `requestUnbufferedDispatch` is not taking effect.
- **est. levels** — the headline number. Wacom claims 8192; Android may quantise
  it. This drives how much resolution the pressure curves have to work with.
- **tool types** — confirms `TOOL_TYPE_ERASER` on pen flip.
- **hover max** — non-zero means hover works, which is what palm rejection uses.

### Latency tab — the A/B

Toggles: **Front buffer**, **Predict**, **Unbuffered**. Run all four meaningful
combinations, starting with everything off (the control) and ending with
everything on.

For each:

1. Point a phone at the screen in 240 fps slow-motion, framing pen tip and ink.
2. Draw a fast, steady horizontal stroke.
3. Step through the video. Count frames from the pen tip visibly moving to ink
   appearing beneath it. **Each frame is 4.17 ms.**

The on-screen `f######` counter and the block that flips every frame let you
confirm the app is rendering at the rate you think it is. The red circle marks
the newest *reported* sample — the gap between it and the physical pen tip in
the video is input latency; the gap between it and the ink tip is render
latency. Translucent grey segments are predicted ink; watch for overshoot on
direction changes.

### Device tab

Confirms the SoC, GPU and refresh modes, then **Export report to Downloads**
writes the whole session as JSON — which also proves the permission-free
MediaStore path v1 needs for "save PNG to the tablet".

## Reading the result

- **max texture ≥ 4096** — required. Below it, the hard canvas cap comes down.
- **front-buffered clearly beating baseline** — proceed as planned.
- **little or no difference** — something is not taking effect; check
  `samples/event` and that the release variant is installed before concluding
  the API doesn't help.
- **est. levels well under ~1000** — pressure curve design needs revisiting.

## One caveat in the code

`LowLatencyInkView.kt` is the only file written against an API surface that
could not be compiled or verified here (`CanvasFrontBufferedRenderer`,
`MotionEventPredictor`). If signatures have shifted, fix them there — the
javadoc Android Studio shows on the symbol is authoritative.

Nothing else depends on that file. Pen telemetry, the device probe, the export
path and the baseline ink view all build and measure without it, so a broken
front-buffered path costs you the A/B, not the spike.

## Layout

```
artiest/
├── docs/analysis.html          architecture + phased plan
├── gradle/libs.versions.toml   pinned versions
└── spike/                      Phase 0 harness (throwaway)
    └── src/main/kotlin/eu/torqa/artiest/spike/
        ├── PenSample.kt        MotionEvent → normalised sample
        ├── PenStats.kt         rates, ranges, pressure quantisation
        ├── PenCapture.kt       shared entry point for both ink paths
        ├── BaselineInkView.kt  path A — plain View (the control)
        ├── LowLatencyInkView.kt path B — front-buffered + prediction
        ├── DeviceProbe.kt      SoC, GPU, display modes via EGL pbuffer
        ├── SessionExporter.kt  JSON → MediaStore Downloads
        └── MainActivity.kt     the three screens
```

The multi-module layout in the analysis starts at Phase 1. Phase 0 is
deliberately one module, because it gets deleted.
