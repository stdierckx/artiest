# Text and fonts — the feature list, before the decision

> Authored 2026-09-10, from the manuals of Clip Studio Paint, Procreate and
> Krita, from a Krita developer's write-up of rebuilding their text tool, and
> from the Android text APIs. A menu, not a plan; scores are *predicted* in
> Phase 2's sense.

## What a text tool in a drawing app is actually for

Three different jobs, and no program does all three well:

1. **A title or some lettering on a piece of art** — a few words, chosen face,
   moved and scaled by hand. Procreate serves this and stops there.
2. **Comic dialogue** — balloons, tails, volume, vertical Japanese, furigana.
   Clip Studio is built around it and it is most of their text feature list.
3. **Labels and notes on a sketch** — annotation, arrows, callouts. Concepts and
   the design apps serve this.

Which one you want changes the list below more than any technical decision does.

## The platform already does the hard part

This is the finding that makes text much cheaper here than it looks, and it is
worth being concrete because it decides several items outright:

| Need | Android gives us | Since |
|---|---|---|
| Draw text, with kerning and shaping | `Canvas.drawText`, `Paint` | always |
| Line breaking, alignment, justification | `StaticLayout` | justification API 26 |
| List every font on the device | **`SystemFonts.getAvailableFonts()`** | **API 29 — exactly our `minSdk`** |
| Load a font the user copied over | `Typeface.createFromFile` (TTF, OTF, TTC) | always |
| Fonts without shipping any | Downloadable Fonts / the Google Fonts provider, which is OFL-licensed | support lib |
| Letter spacing, OpenType features | `Paint.letterSpacing`, `Paint.fontFeatureSettings` | API 21 |
| Variable font axes (a weight slider) | `Typeface.Builder.setFontVariationSettings` | API 26 |
| **Text as outlines** | **`Paint.getTextPath`** → a `Path` | always |
| Emoji, Arabic shaping, CJK, right-to-left | the platform, correctly, for free | always |

`getTextPath` is the quiet one. It turns any string in any font into vector
outlines, which is simultaneously *convert text to shapes*, *real SVG export*,
and the bridge to `docs/vector-plan.md`.

**And the warning, from the people who have done it:** Krita's rich text editor
was removed after years of trouble — "the conversion to and from SVG text wasn't
optimal, and there were endless issues with theming, font size handling…" — and
what replaced it in Krita 6 is an **on-canvas editor**. The lesson is not about
Qt. It is that **in a text tool, the rendering is the easy half and the editing
UI is where the years go.**

## The two decisions that shape everything

### Where do you type?

| | Approach | Feel | Cost |
|---|---|---|---|
| **A** | **On-canvas caret**, keyboard over the drawing | Best. What Procreate does and what Krita 6 landed on. | Hit-testing a caret under an arbitrary canvas transform, IME integration, selection handles, and the keyboard covering the page. **High.** |
| **B** | **Type in a box**, canvas shows the result | Plain but honest. CSP's Story Editor is a deliberate version of this for volume dialogue. | **Low.** A panel, which the dock system already knows how to hold. |
| **C** | **Hybrid** — tap the page to place, type in a panel, then drag handles on the canvas to move, rotate and scale | Good enough for titles and labels; not good enough for a comic script | Low-to-medium, and it is **B plus the transform handles Phase 3 is already building for the float.** |

**Recommendation: C for v1**, with A as a later upgrade that does not invalidate
it.

### Is a text layer live, or is it pixels?

A **live text layer** is a layer whose pixels are regenerated from a string plus
a style. That is exactly `docs/vector-plan.md`'s model — which means **text is
the cheapest possible vector layer and a sensible pilot for that whole plan.**

Procreate's answer is the other one: text stays editable until you rasterise it,
and rasterising is a one-way door that costs you the content, the font and clean
scaling. That is a fine answer as long as the door is clearly marked.

**Recommendation: live text layers, with "convert to pixels" as an explicit
one-way action** that undo can still reverse.

## The feature list

**Use** 1–5, **Diff** 1–5, **Time** in focused working days.

### Tier 0 — the tool itself

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 1 | **Place text, pick font, size, colour** | The panel, the text object, the first render. Colour is the current ink. | 5 | 3 | 5–8 |
| 2 | **Move, rotate, scale — by re-rendering** | **Not by resampling pixels.** Phase 3's float holds pixels and a matrix; a text object must hold the string and the matrix, or scaling blurs it and the whole point is lost. | 5 | 3 | 3–4 |
| 3 | **Edit it afterwards** | Change the words a week later. The difference between a text tool and a stamp. | 5 | 2 | 2 |
| 4 | **Line breaks and alignment** | `StaticLayout`. Left, centre, right, justified. | 4 | 1 | 1 |
| 5 | **The device's font list** | One call, cached — the results only change on a system update. | 4 | 2 | 2 |
| 6 | **Import your own fonts** | TTF/OTF/TTC from storage, as Procreate does. **We distribute nothing, so there is no licence question** — the same rule as imported brushes. | 4 | 2 | 2 |
| 7 | **Font preview in the picker** | Each font's name drawn in its own face. Cheap, and the difference between a usable font list and a wall of words. | 4 | 1 | 1 |
| 8 | **Undo of text edits** | A property change, the same shape as a layer property. Not a pixel patch. | 4 | 2 | 1 |

### Tier 1 — typography

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 9 | **Tracking and leading** | `Paint.letterSpacing` and `StaticLayout` line spacing. Two sliders. | 3 | 1 | 1 |
| 10 | **Weight and style within a family** | Bold, italic, light — picking the right file, not faking it with a skew. | 3 | 2 | 2 |
| 11 | **Variable font axes** | A continuous weight slider on fonts that carry the axis. | 2 | 2 | 2 |
| 12 | **OpenType features** | Ligatures, small caps, alternates — a feature string on the paint. | 2 | 2 | 2 |
| 13 | **Text outline, shadow, gradient fill** | **Do not build these here.** They are items 12, 13 and 17 of `docs/layer-effects-plan.md`; build them once for layers and text gets them free. | 4 | — | shared |
| 14 | **Manual kerning of one pair** | Requires our own layout engine. The platform will not help. Say no. | 1 | 5 | 8+ |

### Tier 2 — shape and flow

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 15 | **Convert text to outlines** | `Paint.getTextPath`. One call, and it unlocks warping, real SVG export, and treating letters as shapes. **Best value-for-effort in the document.** | 3 | 1 | 1 |
| 16 | **Text on a path** | `drawTextOnPath` exists and is crude; doing it properly means placing each glyph along an arc-length parameterisation. Krita's blocker is not the layout — it is **editing the path**, which we would inherit. | 3 | 4 | 6–8 |
| 17 | **Text flowed into a shape** | Krita 6 added it; SVG 2 only allows justification in this mode. | 2 | 4 | 6 |
| 18 | **Warp / distort text** | Easiest on top of 15: outlines, then a mesh. | 2 | 4 | 5 |
| 19 | **Vertical text (CJK)** | The platform does *not* lay out vertically for you. Only worth it for manga. | 1 | 4 | 6–8 |

### Tier 3 — comics

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 20 | **Speech balloon tool** | A shape with a draggable tail that grows to fit its text. CSP's whole identity; a feature in its own right, not a text setting. | ? | 4 | 8–10 |
| 21 | **Story editor** | Type every line of dialogue in one list, place them after. Excellent for volume, pointless for a title. | 1–2 | 3 | 5 |
| 22 | **Ruby / furigana** | Readings above kanji. Only if the answer to "comics?" is "Japanese comics". | 1 | 4 | 5 |
| 23 | **Panel / frame tool** | Adjacent, not text — Krita 6 shipped a comic panel editor beside its text work, which is where the pairing comes from. | 1–2 | 4 | 8 |

### Tier 4 — the small things that make it pleasant

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 24 | **Recent and favourite fonts** | A tablet has 100+ system fonts. This is what stops the picker being a chore. | 3 | 1 | 1 |
| 25 | **Text style presets** | Save "my title look" and reuse it. Same shape as a saved brush from `docs/brush-shelf-plan.md`. | 3 | 2 | 2 |
| 26 | **Text in the export** | PNG is free. SVG needs item 15 or real `<text>`. **`.ora` has no text at all**, so a text layer is stored as pixels plus an artiest extension. | — | 2 | 1–2 |

## The traps

1. **Sharpness at zoom is the same problem as vector layers.** Text rendered at
   document resolution and zoomed 4× is soft. Live text can re-render at the
   zoom it is being shown at — but only if the compositor lets it. **Text and
   `docs/vector-plan.md` should be decided together**, because text is the
   smallest version of that whole feature.
2. **The keyboard covers the canvas.** The app draws full-screen; the IME will
   take a third of it, and where the text object sits relative to that is a
   layout problem the dock system has opinions about. It is the reason approach
   C exists.
3. **Do not build a rich text editor.** Krita's story is unambiguous: they built
   one, fought it for years, removed it, and replaced it with on-canvas editing.
4. **Ship no fonts.** System fonts plus user imports plus, optionally, the Google
   Fonts downloadable provider (OFL). Bundling a font in the APK is the same
   licensing question as bundling a brush, with the same answer.
5. **Transform must never resample.** Stated twice on purpose: it is the single
   easiest way to silently throw away everything this feature is for.
6. **Text is a property bag, so its undo is property-shaped**, like a layer
   effect and unlike a stroke. Two undo shapes already coexist in the plans;
   this is a third user of the same mechanism, not a fourth mechanism.

## If you only pick a few

> **Tier 0 (1–8), plus convert-to-outlines (15) and recent fonts (24).**
>
> Roughly **15–20 days**, and it delivers: put a title on a drawing in any font
> the tablet has or any font you copied onto it, move and scale it without it
> going soft, change the words later, and turn it into shapes when you want to
> draw on top of it. Outline and shadow come free from the layer-effects work if
> that lands first.

## The questions only you can answer

1. **Titles and labels, or comics?** Balloons, story editor, furigana and
   vertical text are half this document and they are a different program. If
   comics are not the goal, Tier 3 disappears entirely.
2. **Type on the page, or type in a box?** B is cheap and slightly clumsy;
   A is what everyone eventually builds. C gets most of A's feel for B's price.
3. **Live text, or bake it?** Live text is the pilot for vector layers. Baking
   is Procreate's answer and much cheaper — but the first time you scale a baked
   title up, you will see exactly what it cost.

## Sources

- [CSP: text and balloon tools](https://www.clipstudio.net/en/comics-manga/tool/text-balloons/), [vertical text and readings](https://help.clip-studio.com/en-us/manual_en/480_text/Vertical_text_and_readings.htm) — furigana, TateChuYoko, balloon shapes and tails
- [CSP: story editor](https://tips.clip-studio.com/en-us/articles/2875)
- [Procreate: text interface](https://help.procreate.com/procreate/handbook/text/text-interface) — imported TTC/TTF/OTF, kerning/tracking/leading, and what rasterising costs
- [Wolthera: Text Tool Phase 3](https://wolthera.info/2025/11/text-tool-phase-3/) and [Krita 6.0's text overhaul](https://alternativeto.net/news/2026/3/krita-6-0-debuts-with-linux-wayland-and-hdr-support-new-text-tools-and-comic-panel-editor) — why the rich text editor was removed, and what blocks text-on-path
- [Android: `SystemFonts`](https://developer.android.com/reference/android/graphics/fonts/SystemFonts) (API 29) and [`Typeface`](https://developer.android.com/reference/android/graphics/Typeface)
- [What's new for text in Android Q](https://android-developers.googleblog.com/2019/07/whats-new-for-text-in-android-q.html)
