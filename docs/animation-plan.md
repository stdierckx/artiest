# Animation — the feature list, before the decision

> Authored 2026-09-10, to fill the gap `docs/master-plan.md` names: *Animator*
> was in the list of workspaces and had no document behind it. From the manuals
> of Krita, Clip Studio Paint and Procreate, from the two Android apps that own
> this ground (FlipaClip and Rough Animator), and from what Android can encode.
> A menu, not a plan; scores are *predicted* in Phase 2's sense.

## Which animation? Three programs wear the same word

| | Kind | What you do | Who |
|---|---|---|---|
| **1** | **Flipbook / frame-by-frame** | Draw every frame. Onion skin, hold, loop, export. | Procreate's Animation Assist, FlipaClip, Rough Animator, Krita, CSP |
| **2** | **Keyframed / cut-out** | Draw once, animate transforms over time. Tweening, camera, easing. | Procreate Dreams, CSP's 2D camera, After Effects |
| **3** | **Animatic / timing** | Boards with durations, sound, playback. Not drawing so much as editing. | Storyboarder — see `docs/comics-plan.md` Tier 4 |

**Recommendation: kind 1, and mostly stop there.** Three reasons, and the first
one is the whole argument:

- **This app's asset is a pencil that reads as graphite.** The single most
  appealing thing it could animate is a *pencil test* — rough frames, real
  tooth, drawn by hand. That is kind 1 and nothing else.
- Kind 2 is a different program with a different data model (objects and curves,
  not pixels), and Procreate needed a separate app for it.
- Kind 3 falls out of the comic work almost free, once pages exist.

Kind 2's cheap corner — a camera move over finished frames — is worth taking
later, and is listed. The rest of it is not.

## The wall, before anything else

`Layer` is 27.19 MiB. `LayerStack` caps at eight, deliberately, from a memory
budget on a device with 4.4 GiB free.

- 24 frames × 8 layers = **5.2 GiB.** Does not fit.
- 24 frames × 1 layer = **652 MiB.** Fits, and leaves no room for anything else.
- A 12-second loop at 12 fps is 144 frames. **Not close.**

So animation is not a feature that sits on the current document. It needs the
**sequence subsystem** `docs/master-plan.md` describes — a document holding many
page-sized things with only a few live in memory — and that subsystem is shared
with comic pages. Building it once serves both; building it twice is the most
expensive mistake available across all these documents.

Three things make it much more affordable than the arithmetic above suggests:

1. **An animation document does not want a print page.** 3300 × 2160 is a page
   for printing. Animation is 1920 × 1080 or smaller — **8.3 MiB a layer, a third
   of the cost** — and CSP caps its own MP4 export at 1080p anyway. A per-document
   canvas size is a small change with a large effect here.
2. **A drawn frame is mostly empty.** A few strokes on transparent paper
   compresses to almost nothing. Frames live on disk compressed and come back
   when needed; `PixelPatch` already thinks in regions.
3. **Cels are reused.** A 24-frame loop animated on twos with a held background
   might contain **eight unique drawings**. Model it right and the reuse is free
   memory rather than a feature.

## The data model — and getting it right is most of the work

Three ways to say what a frame is:

| | Model | Consequence |
|---|---|---|
| **A** | A frame is a snapshot of the whole document | Simple, and it multiplies every layer by every frame. Also makes "hold the background" impossible without redrawing it. |
| **B** | **Each layer has its own track of keyframes**; a layer with no keyframe at time *t* still shows its last one | Krita's and CSP's model. Holds, backgrounds and "animate the character only" all fall out of it. |
| **C** | A track is a list of **references to named cels**; the same cel can appear many times | CSP's animation folder. Reuse, exposure and timing are one mechanism. |

**Take B with C's references.** A track is a list of cel ids over time; a hold is
the same id twice; a cycle is A B C A B C; a background is one id for the whole
row. Memory follows the number of *drawings*, not the number of *frames*, which
is exactly how traditional animation is costed too.

## What Android gives us for export

| Need | Android | Note |
|---|---|---|
| **MP4 / H.264** | `MediaCodec` + `MediaMuxer` | On-device hardware encoder. No ffmpeg, no extra dependency, **no licensing question** — which matters, because bundling an encoder would be the same conversation as bundling brushes. |
| PNG image sequence | `Bitmap.compress` | Free. |
| **GIF** | **Nothing.** The platform decodes animated GIF and WebP; it encodes neither. | A GIF encoder is ours to write — a well-documented format, a few hundred lines, and the LZW patent expired long ago. |
| Video import (rotoscope) | `MediaCodec` decode / `MediaMetadataRetriever` | Frames out of a video, for tracing. |
| Audio | `MediaExtractor`, `AudioTrack`, `MediaRecorder` | Playback and recording both exist. |

MP4 wants even (often 16-aligned) dimensions, so export sizes are constrained
and should be offered as a short list rather than a free number.

## The feature list

**Use** 1–5, **Diff** 1–5, **Time** in focused working days. Everything assumes
the sequence subsystem exists; its own cost (**15–25 days**) is counted once, in
`master-plan.md`, not here.

### Tier 0 — a flipbook that actually works

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 1 | **Frames and a timeline strip** | Thumbnails, current frame, add, duplicate, delete, reorder. The spine of everything. | 5 | 3 | 5–8 |
| 2 | **Onion skin** | N frames back and forward, tinted and faded. CSP's convention — **previous in pale blue, next in pale green** — is worth copying outright; everyone already reads it. | **5** | 3 | 4–6 |
| 3 | **Playback** | A frame rate, and loop / ping-pong / once. Procreate's three modes are the right three. | **5** | 3 | 4–6 |
| 4 | **Hold / exposure ("on twos")** | One drawing on screen for several frames. **Not a redraw — the same cel referenced twice.** The feature that makes timing possible and the reason for model C. | 5 | 2 | 3–4 |
| 5 | **Flip through frames by gesture** | The traditional flip, with the pen still in your hand. Cheap, and it is how animators actually check a drawing. | 4 | 2 | 1–2 |
| 6 | **Export MP4** | `MediaCodec` + `MediaMuxer`, at a chosen size and frame rate. | **5** | 3 | 5–8 |
| 7 | **Export a PNG sequence** | The escape hatch into every other program. | 4 | 1 | 1–2 |

**Tier 0: 23–36 days on top of the sequence subsystem**, and it is a complete,
shippable flipbook animator — which is more than FlipaClip's free tier offers.

### Tier 1 — layers, cels and timing

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 8 | **Per-layer frame tracks** | A held background under an animated character. Model B, made visible. | 5 | 4 | 6–8 |
| 9 | **Named cels and reuse** | A B A C timing, and a cycle you can retime without redrawing. | 4 | 3 | 4–5 |
| 10 | **Light table** | Pin *any* frame as a reference, not just the neighbours. CSP's, and it is what inbetweening needs. | 3 | 2 | 2–3 |
| 11 | **Copy, paste and cycle a range** | Loops built from a handful of drawings. | 4 | 2 | 3 |
| 12 | **Per-frame undo** | Undo on frame 7 must not touch frame 8. Cheap to design in, painful to retrofit — the same sentence as per-page undo, and the same solution. | 4 | 3 | 3–4 |
| 13 | **Retime without redrawing** | Change the frame rate, or stretch a section, and the drawings stay. Falls out of 9 nearly free. | 3 | 2 | 2 |

### Tier 2 — the cheap corner of keyframe animation

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 14 | **2D camera** | Pan, zoom and rotate over the sequence, as keyframes. CSP's, and it turns a static loop into a shot. **The best value in kind 2 by a distance.** | 4 | 4 | 6–8 |
| 15 | **Transform keyframes on a layer** | Slide a drawing across the screen without redrawing it. Cut-out animation's first step. | 3 | 4 | 5–7 |
| 16 | **Easing** | Linear motion looks mechanical; ease is what makes 14 and 15 worth having. | 3 | 2 | 2–3 |
| 17 | **Drawing interpolation (real inbetweening)** | Generating drawings between drawings. An open research problem, not a feature. **Say no.** | 2 | 5 | 30+ |

### Tier 3 — sound and reference

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 18 | **Import audio with a waveform** | Lip sync and timing to music. Krita's own audio support is basic and they say so; the waveform on the timeline is the part that matters. | 3 | 4 | 6–8 |
| 19 | **Record voice in-app** | FlipaClip has it and people use it. | 2 | 3 | 3–4 |
| 20 | **Import a video to rotoscope** | Decode to frames, trace over them. Both Android apps offer it. | 3 | 4 | 5–7 |

### Tier 4 — export and getting out

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 21 | **GIF export** | Ours to write. Still what people post. | 3 | 3 | 4–5 |
| 22 | **Sprite sheet** | Game artists. One image, a grid, a JSON of frame rects. | 2 | 2 | 2 |
| 23 | **Layered export to other programs** | Rough Animator's differentiator — out to Toon Boom, After Effects, Animate. A serious professional hook and a serious format problem. | 2 | 4 | 6–10 |

## The traps

1. **Memory is the feature.** Everything above is downstream of the sequence
   subsystem and of a smaller default canvas. Do not start the timeline before
   frames can live on disk.
2. **Onion skin is a compositing cost, and it has a measured shape.** Eight plain
   sheets cost 20.8 ms of CPU compositing (`CompositeBench`); three onion frames
   are three *more* stacks unless each frame is cached flattened. **Cache one
   flattened bitmap per frame** — which playback needs anyway, so it is one piece
   of work serving two features.
3. **Playback is not drawing, and must not use the drawing path.** The
   front-buffer and prediction machinery exists to get ink under a moving pen;
   during playback it is irrelevant and possibly harmful. Playback schedules on
   the frame clock at 12 or 24 fps against a 60/90 Hz panel, and dropping to a
   plain path there is correct, not a regression.
4. **A cel is shared, so editing one edits every frame that uses it.** That is
   the point, and it is also the single most confusing thing about cel-based
   animation for anyone who has not used it. It needs to be visible in the
   timeline — the same drawing marked wherever it appears.
5. **Export size is constrained by the encoder**, not by taste. Offer a short
   list (1080p, 720p, square, the document size if it is legal) rather than a
   free number that fails on some devices and not others.
6. **Long renders are a thermal event.** A 30-second export at 24 fps is 720
   composites and 720 encodes on a fanless tablet. It needs progress,
   cancellation, and to survive the screen going off.

## Where this sits

After the sequence subsystem, and therefore after saving a document at all.
Given `docs/master-plan.md`'s order, the honest position is: **animation is the
third or fourth artist, not the second** — unless the pencil-test idea is the
thing that excites you most, in which case it is a strong second, because Tier 0
is genuinely small once frames-on-disk exists and no other program on Android
pairs a real graphite pencil with a flipbook.

## The questions only you can answer

1. **A three-second loop, or a two-minute film?** A loop needs Tier 0 and
   nothing else. A film needs audio, retiming, camera and a project format, and
   it is four times the work.
2. **Flipbook, or a real timeline with keyframes?** Kind 1 or kind 2. My
   recommendation is kind 1 plus the camera (item 14) and nothing else from kind
   2.
3. **Is the pencil test the point?** If yes, this plan is small, distinctive and
   plays to the one thing this app already does better than its Android rivals.
   If the goal is instead to compete with FlipaClip feature for feature, it is a
   much longer road against apps that have been on it for a decade.

## Sources

- [Krita: animation](https://docs.krita.org/en/user_manual/animation.html) and the [timeline docker](https://docs.krita.org/en/reference_manual/dockers/animation_timeline.html) — keyframes per layer, blank frames, onion skin docker, audio, GIF/video export
- [CSP: animation](https://www.clipstudio.net/en/animation/) and [animation folders and cels](https://help.clip-studio.com/en-us/manual_en/600_animation/Animation_folders_and_cels.htm) — cels in an animation folder, light table, onion skin in blue and green, 2D camera keyframes, MP4 capped at 1920×1080
- [Procreate Dreams](https://help.procreate.com/dreams) and Animation Assist — onion skin, hold duration, loop / ping-pong / one-shot, GIF/PNG/HEVC/MP4 export, and why the keyframe program is a separate app
- [FlipaClip](https://play.google.com/store/apps/details?id=com.vblast.flipaclip) — the Android benchmark: layers, onion skin, audio recording, rotoscope import, MP4/GIF/PNG export
- `docs/master-plan.md` — the sequence subsystem this shares with comic pages
