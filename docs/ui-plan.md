# The UI — what to build on, and what to build

> Authored 2026-09-09, in answer to a question asked in the user's words:
> *"can we use third party libraries that have nice UI elements? ... we will
> need menus, toolbars that can reshape dynamically, slider buttons, toggle
> buttons, picture buttons. The ui should feel modern!"* — and, mid-answer,
> *"Maybe a docking system, that would be great"*.
>
> **Nothing here has been built or measured.** No dependency has been added; no
> version has been bumped. This is a survey with a recommendation attached, and
> the parts most likely to matter are **What the upgrade costs** and **Docking**,
> because one of them touches the latency baseline and the other says the thing
> nobody wants to hear about a feature they just asked for.
>
> Every library named below was checked for its licence against `NOTICE`'s rule.
> The survey was done from published documentation and repository licence files;
> no third-party source was read into this repository, and none is proposed for
> vendoring except icons, which is called out where it matters.

## The decision

**Take Material 3 Expressive, which is already a dependency, and build the
docking system ourselves, because it does not exist.**

Four of the five controls the user asked for are components AndroidX now ships.
The fifth — docking — has no Android implementation at all, permissive or
otherwise, and the piece of it that is genuinely hard is a layout algebra that
`ToolbarLayout` already is.

Three things follow, and each cuts a plausible plan out of the running.

**There is no drawing-app UI kit to adopt.** The search was run for one. What
exists is either a canvas library that wants to own the canvas (Stream's
`sketchbook-compose`, Apache-2.0 — it draws paths and offers a colour picker,
which is Phase 1 and Phase 4 respectively, both already ours), or a general
component set with no opinion about tools. Nothing in the ecosystem knows what a
brush preset is. That is not a gap to be filled by shopping.

**The modern look is a version number, not a library.** The bar was pinned at
`composeBom = "2024.12.01"`, which resolves material3 to **1.3.1** — before
Material 3 Expressive existed. Stable is now **1.4.0** (26 Aug 2026), with the
alpha line at `1.5.0-alpha27`. The expressive set landed in the 1.4 line behind
`@OptIn(ExperimentalMaterial3ExpressiveApi::class)` and graduated to
non-experimental across the 1.5 alphas. So "feel modern" is mostly a
`libs.versions.toml` edit, and the interesting question is not which library to
add but what the bump costs — see below, because it is not nothing.

**The docking system is a data-structure job we have already half done, wearing
a rendering job's clothes.** Every docking implementation worth the name is
desktop: DockPanelSuite for WinForms, Qt's docking panes, JetBrains' own Jewel
(Apache-2.0, but it is the IntelliJ look-and-feel for Compose *Desktop* and has
moved into the IJ platform), JetBrains `components-splitpane` (Apache-2.0, JVM
desktop only). On Android the nearest things are floating-window helpers, which
give a draggable snapping window and no dock model. Meanwhile `ToolbarLayout`
is already an immutable placement algebra with `fits`, `place`, a codec that
never throws, and twenty-three JVM tests. A dock is that, plus one axis.

## What the upgrade buys, against the list that was asked for

| Asked for | What material3 ships | Where it lands here |
|---|---|---|
| menus | expressive menu APIs (`1.5.0-alpha19`), FAB Menu | `SlotToolbar`'s chooser is already a `DropdownMenu`; this is a restyle, not a rewrite |
| toolbars that reshape dynamically | `HorizontalFloatingToolbar` / `VerticalFloatingToolbar` with `expanded: Boolean` and `FloatingToolbarScrollBehavior`; `ButtonGroup` with `animateWidth` (`1.5.0-alpha22`) | the shell `SlotToolbar` draws into. `ButtonGroup` reshapes literally: the pressed button expands and its neighbours compress |
| slider buttons | expressive `Slider` — thicker track, stop indicators, centered variants (`1.5.0-alpha16`) | drops into the existing four-slot `SIZE` and `SMOOTHING` items with no layout change |
| toggle buttons | `ToggleButton`, morphing between three shapes by interaction state; connected single- and multi-select groups (`1.5.0-alpha19`) | exactly the shape of W10's preset picker |
| picture buttons | expressive `IconButton` family, filled/tonal/outlined across sizes XS–XL (`1.5.0-alpha19`) | needs an icon set, which is the one place a third party is genuinely required |
| docking | nothing | built here. See **Docking** |

`SplitButton` (`1.5.0-alpha20`) is worth noting even though it was not asked
for: "pencil, and a caret for its settings" is one control, and it is the
gesture `ToolItem`'s KDoc currently defers as *"becomes a panel when W7 adds
opacity and flow"*.

## What the upgrade costs

Stated separately from what it buys, because this is the part that can go wrong
quietly.

**It is an AGP/Kotlin/SDK bump wearing a UI bump's clothes.** The pins are
AGP 8.7.3, Kotlin 2.0.21, `compileSdk 35`. A 2026 BOM will want a newer Compose
compiler plugin and probably `compileSdk 36`. `libs.versions.toml` already says
to accept upgrades *"as a separate commit so a build break is attributable"*.
That instruction was written for exactly this.

**It touches the A/B control.** `:spike` and `:app` both pull the same BOM, and
`:app`'s build file says the two are *"two arms of the same measurement, so
anything that could make their bytecode differ has to be identical here."* A BOM
bump moves both arms together, which preserves the comparison — but it moves the
absolute numbers on both, so **W16's 45.2 ms and W9's per-event p50 are no
longer comparable across the bump**.

**So the bump owes a re-measurement, and the toolbar is why.** The toolbar
recomposes on the same UI thread that dispatches pen input. A recomposition
storm from a scroll-behaviour-driven floating toolbar is not free, and this is
the one project where "the toolbar animates nicely now" and "the pen got worse"
are the same sentence. **Re-run the W0 film and the W9 counters after the bump,
in the bump's own commit, before anything is built on top.** If the numbers
move, that is a finding about Compose, not about the brush, and it is worth
knowing on the day it happens rather than in W15.

## Docking

The feature, as asked: panels that live on an edge, can be moved to another
edge, and can float. What follows is the shape that costs least here.

**The model.** `ToolbarLayout` generalises without changing its invariants:

```
DockLayout = Map<Dock, ToolbarLayout>       // Dock = LEFT | RIGHT | TOP | BOTTOM | FLOATING
```

`fits` and `place` are unchanged. Moving an item between docks is `remove` on
one layout plus `fits`-then-`place` on another — both operations that already
exist and are already tested. `ToolbarCodec` grows a dock prefix per entry and
keeps its one rule, that decoding never throws: an unknown dock name is an
unknown id by another name, and drops the entry rather than the bar.

**Why this is worth more than any library would be.** The whole reason the
toolbar work went well is that everything about *where an item may go* is
decidable on the JVM and is tested there, and only *how it looks* needs the
tablet. Every docking library inverts that: the layout logic lives inside a
rendering widget, on a device, in a paradigm built for a mouse. Adopting one
would trade twenty-three passing tests for a dependency that cannot run in
`:engine`'s test loop.

**Two things a phone-and-tablet dock needs that a desktop dock does not.**

- **A dock is a screen edge, and the screen rotates.** `material3-adaptive`
  (Apache-2.0) supplies `WindowSizeClass` so one `DockLayout` describes both
  orientations. The fixed-slot rule earns its keep again here: absolute
  positions survive rotation, a reflowing dock would not.
- **The drag gesture races the controls, exactly as long-press did.** The
  concession recorded in Wc — that filled slots are edited through **Arrange**
  because a `Slider` eats presses — applies unchanged to dragging a panel by its
  contents. **Drag-to-dock lives inside Arrange mode.** Outside it, a drag over
  the size slider is a size change, which is correct.

**For the drag itself**, in preference order: Compose Foundation's
`dragAndDropSource` / `dragAndDropTarget`, which are already in the dependency
tree and add nothing; or `Calvin-LL/Reorderable` (Apache-2.0, maintained,
Compose Multiplatform) if reordering within a dock turns out to want the
autoscroll and item-animation work it has already done.

## The libraries worth taking, and what each is for

| Library | Licence | For | When |
|---|---|---|---|
| `androidx.compose.material3` (bump) | Apache-2.0 | menus, toolbars, sliders, toggles, icon buttons | U1 |
| `androidx.compose.material3:material3-adaptive` | Apache-2.0 | `WindowSizeClass`, one dock model across orientations | U3 |
| Compose Foundation drag-and-drop | Apache-2.0 | drag a tool between docks; already a dependency | U4 |
| `Calvin-LL/Reorderable` | Apache-2.0 | only if foundation DnD proves insufficient | U4, conditional |
| Lucide (ISC) / Phosphor (MIT) / Tabler (MIT) | ISC / MIT | pencil, brush, eraser, layers, undo, redo, palette | U2 |
| `skydoves/colorpicker-compose` | Apache-2.0 | Phase 4's colour wheel — HSV wheel, brightness and alpha, KMP | Phase 4 |
| `godaddy/compose-color-picker` | MIT | smaller alternative to the above | Phase 4 |
| `composablehorizons/compose-unstyled` | MIT | renderless sheets, menus, dialogs and a theming layer, if Material chrome reads too much like Google's | only if U1 lands and the app looks like everyone else's |

**On icons, which is the only real third-party need.** Material Symbols is
Apache-2.0 and free of charge, but it is thin on art-tool glyphs — there is no
good graphite pencil, no marker, no brush that reads as a brush. Lucide (ISC)
and Phosphor (MIT) both have the full set. Do **not** take
`compose-material-icons-extended`: thousands of vectors, a slower build, and
still missing the ones this app needs.

## Licence hygiene

Apache-2.0 consumes Apache-2.0, MIT, BSD and ISC without a thought. MPL-2.0 is
file-level copyleft and is fine as an unmodified library. **LGPL is to be
avoided on Android specifically** — R8 makes the relinking obligation genuinely
awkward to satisfy, and no candidate here is worth finding out how awkward. GPL
stays out, permanently, for the reason already recorded: *"we do not need their
code."*

**One concrete consequence, and it has a deadline.** `NOTICE` currently says:
*"No third-party source, assets or brush data have been copied into this
repository."* Maven dependencies do not touch that sentence — nothing is
vendored, they are retrieved at build time, and the file already says so. **A
vendored icon set does.** ISC and MIT both require the copyright notice to
travel with the copy. The fix is one paragraph in `NOTICE` naming the set and
its licence, and it has to land **in the same commit as the first `.svg`**, not
in the tidy-up afterwards — because that sentence is currently true, and a
repository whose `NOTICE` is false is worse than one that never made the claim.

## Deliberately not proposed

- **Haze**, or any Compose blur, over the canvas. Not a taste call — it cannot
  work. Haze is built on Compose `GraphicsLayer`, and `InkSurfaceView` is a
  `SurfaceView`, which its own header describes as *"a transparent hole punched
  in the view hierarchy"*. A `SurfaceView` is composited on its own layer and
  cannot be captured by the graphics-layer pipeline, so a blurred toolbar over
  the canvas would blur nothing. The fix is a `TextureView`, which front-buffered
  rendering forbids. The translucent bar that exists is the right answer and is
  also the cheaper one, on a GPU budget the ink path owns.
- **An all-in-one UI kit.** They impose a design language across an app that is
  ninety-five percent canvas.
- **Krita's, GIMP's or MyPaint's UI source.** GPL. Read-and-reimplement is
  permanent, and the docker model is an idea, not a file.
- **`sketchbook-compose`.** Apache-2.0 and well made, but it owns the canvas and
  the canvas is the part of this project that is finished.
- **A theming system.** Material's is adequate and the app has one screen. If a
  second screen ever appears, revisit.
- **Restyling anything before the bump.** Work done against 1.3.1's components
  is work done twice.

## Work plan

Sized against the toolbar work, which was one sitting for the model and one
build to confirm.

| Item | What | Unlocks |
|---|---|---|
| **U1** | Bump the Compose BOM, AGP, Kotlin and `compileSdk` in one commit. Re-run the W0 film and W9's counters. Record the delta in this file. | everything below |
| **U2** | Adopt a curated icon set. Amend `NOTICE` in the same commit. Replace `ToolItem.short`'s text labels with glyphs where a glyph is clearer, keeping the label for the chooser. | picture buttons |
| **U3** | `SlotToolbar` into `HorizontalFloatingToolbar`; `SIZE` and `SMOOTHING` onto the expressive `Slider`; the preset picker (W10) onto `ToggleButton` groups. | the modern feel, without new state |
| **U4** | `ToolbarLayout` → `DockLayout`. Model, codec and tests first, on the JVM, with no rendering. | docking |
| **U5** | Render the four edge docks and the floating dock. Drag between docks, inside Arrange mode only. | the feature as asked |
| **U6** | Tool settings as a dockable panel, replacing the sliders-in-the-bar concession `ToolItem`'s KDoc records. | W7's opacity and flow having somewhere to live |

U1 and U2 are independent of Phase 2 and can be done while the brush work
continues. **U4 through U6 should not start before W10**, for the reason the
toolbar itself was worth building early: docking exists to arrange controls, and
the controls that will justify it — opacity, flow, presets, eraser — are the
ones Phase 2 is currently building. A dock system built now would be arranged
around seven controls and re-argued at fifteen.

## Risks

| Risk | Signal it is happening | Response |
|---|---|---|
| The BOM bump moves the latency numbers | U1: W0's film disagrees with 45.2 ms outside its spread | Attribute it before building on it. A Compose regression is a finding, not a blocker, but it is not a brush finding |
| Floating-toolbar scroll behaviour recomposes during a stroke | U3: W9's per-event p50 rises with the bar on screen | Hoist the bar's state out of the stroke path, or hold the bar static while `strokeOpen` |
| `compileSdk 36` drags in a manifest or permission change | U1: build fails, or `targetSdk 34` behaviour shifts | Bump `compileSdk` only; `targetSdk` stays at 34 until there is a reason |
| Docking arrives before the controls that need it | U4 starts and the catalogue is still nine items | Hold U4 until W10. Written here so it is a decision rather than a drift |
| The icon set makes `NOTICE` false | U2 lands without the `NOTICE` paragraph | Same commit, or the icons do not land |
| Expressive components still carry the experimental annotation at the pinned version | U1: `@OptIn` required across the UI package | Acceptable. One file-level opt-in, and a note here of which version graduates it |

## Stop conditions

1. **U1's re-measurement shows the pen got worse and the cause is Compose.**
   Stop the UI work. A toolbar that costs pen latency is the one trade this
   project has said, since Phase 0, that it will not make.
2. **U4's `DockLayout` cannot be tested on the JVM.** Then the generalisation
   has picked up a rendering dependency and the design is wrong. Stop and
   re-derive it, because the JVM-testability is the entire reason for not taking
   a library.
3. **U5 needs a gesture that races the canvas outside Arrange mode.** Stop.
   Arrange mode is the settled answer to that class of problem and a second
   answer would make the first one unreliable.

## Open questions that need a human answer

1. **How modern is modern?** Material 3 Expressive is Google's house style, and
   an app built on it looks like a Google app. `compose-unstyled` (MIT) is the
   escape hatch — renderless components, our own skin — at the cost of building
   and maintaining a look. The cheap path is to do U1 and U3 first and then
   decide with something to look at.
2. **Does the floating dock need to survive rotation, or reset?** A floating
   panel at (x, y) in landscape has no honest position in portrait. Snap to the
   nearest edge dock on rotation is the cheapest answer; a per-orientation
   floating position is the honest one and doubles the persisted state.
3. **Should the default install have an empty bar once docking exists?** The
   empty-bar default already rests on an empty slot reading as *tap me*. Five
   empty docks is five times that bet. `ToolbarLayout.STARTER` exists precisely
   so this can be reconsidered in one word.

## Sources

Checked 2026-09-09.

- Compose Material 3 release notes — <https://developer.android.com/jetpack/androidx/releases/compose-material3>
- `HorizontalFloatingToolbar` API — <https://composables.com/jetpack-compose/androidx.compose.material3/material3/components/HorizontalFloatingToolbar/api>
- `ButtonGroup` — <https://composables.com/material3/buttongroup>
- `ToggleButton` — <https://composables.com/material3/togglebutton>
- Haze, and its performance notes — <https://github.com/chrisbanes/haze> · <https://chrisbanes.github.io/haze/latest/performance/>
- Reorderable (Apache-2.0) — <https://github.com/Calvin-LL/Reorderable>
- colorpicker-compose (Apache-2.0) — <https://github.com/skydoves/colorpicker-compose>
- compose-unstyled (MIT) — <https://github.com/composablehorizons/compose-unstyled>
- Jewel (Apache-2.0, desktop) — <https://github.com/JetBrains/jewel>
- SplitPane (Apache-2.0, desktop) — <https://github.com/JetBrains/compose-multiplatform/tree/master/components/SplitPane>
- compose-floating-window — <https://github.com/only52607/compose-floating-window>
- panel-layout, archived — <https://github.com/wayfair-archive/panel-layout>
- Lucide licence (ISC) — <https://lucide.dev/license>
- sketchbook-compose (Apache-2.0) — <https://github.com/GetStream/sketchbook-compose>
