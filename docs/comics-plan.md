# Comic and storyboard tooling — the feature list, before the decision

> Authored 2026-09-10, from the Clip Studio Paint EX manuals, Krita 6's new
> comic panel tool, and Wonder Unit's Storyboarder. A menu, not a plan; scores
> are *predicted* in Phase 2's sense.

## Read this first: three of the four things you named are downstream

Of the four you asked for, only one can be built on what exists today.

| You asked for | What it actually needs first |
|---|---|
| **Frame presets** | Nothing. **Buildable now.** |
| **Text balloons** | The text tool — `docs/text-plan.md`, Tier 0. A balloon is a shape that fits itself around text; without a text object it is a sticker. |
| **Story editor** | The text tool **and** a multi-page document. It is a list of every line of dialogue in the book; both halves of that sentence are prerequisites. |
| **Storyboard tools** | A multi-page document, plus per-page metadata and a different export path. |

And panels that actually *contain* their artwork need layer groups and masks —
`docs/layer-effects-plan.md`, Tier 1, items 8 and 9.

So this document is mostly **an arrangement of other plans for a purpose**, and
the useful thing it can tell you is the order. There is exactly one genuinely new
subsystem in it, and it is the expensive one.

## The one new subsystem: more than one page

`Document` is one page. One `LayerStack`, one `CommitQueue`, one `UndoHistory`,
3300 × 2160, up to eight layers at 27.19 MiB each. A comic is twenty of those.
Twenty pages × eight layers is 4.3 GiB, against a device that reported 4.4 GiB
free — so the naive version does not merely run slowly, **it does not fit.**

What multi-page really means, in order:

1. **A file format**, so a page can live on disk instead of in memory. That is
   Phase 4's `.ora` work, brought forward.
2. **One page open at a time**, with the rest on disk and shown as thumbnails.
3. **Per-page undo**, saved and restored with the page — or explicitly not, and
   the user told.
4. **A page index**: order, insert, delete, duplicate, rename.

That is a phase, not a feature. Everything in Tier 3 and Tier 4 below sits behind
it, and nothing in Tiers 0 to 2 does.

## The panel model — three ways, and they are not equally priced

| | Model | What a panel is | Who | Cost |
|---|---|---|---|---|
| **A** | **Borders drawn onto a layer** | Ink. Once drawn it is pixels like anything else, and a preset is a template that draws them. | the way most people work in Procreate | **Low.** No new object model, no masks, no groups. |
| **B** | **A shape you can slice and merge** | A vector rectangle; cutting it makes two with a gutter between. Krita 6's new comic panel tool works exactly this way. | Krita 6 | Medium. Needs shape objects, which `docs/vector-plan.md` would give. |
| **C** | **A folder that clips its contents** | CSP's frame border folder: each panel is a group with a mask, and art inside it cannot spill. | Clip Studio | **High**, and it needs groups and masks first. It is also what people mean when they say a comic program has panels. |

**Recommendation: A now, C later, and skip B.** A is what "frame presets" means
and it is buildable this month. C is the real thing and it arrives free-ish once
layer groups and masks exist for their own reasons. B only pays off if vector
layers land first.

## The feature list

**Use** 1–5, **Diff** 1–5, **Time** in focused working days. "Needs" names the
prerequisite.

### Tier 0 — buildable now, on today's code

| # | Feature | Notes | Use | Diff | Time |
|---|---|---|---|---|---|
| 1 | **Page setup with bleed and safe area** | Trim line, bleed margin, safe area for balloons and lettering — drawn as a non-printing overlay. CSP treats these as the basic page settings and they are what make a page printable. | 4 | 2 | 2–3 |
| 2 | **Frame preset layouts** | Pick 2×2, three-tier manga, four-panel strip, splash; it draws the borders. **The thing you asked for, and the cheapest item here.** | 4 | 2 | 3–4 |
| 3 | **Gutter width and margins as settings** | Vertical and horizontal gutters, as CSP's divide tool has. | 3 | 1 | 1 |
| 4 | **Borders inked with the current brush** | A hand-drawn frame instead of a mechanical one. Cheap, and it is what makes the page look drawn rather than laid out. | 3 | 2 | 2 |
| 5 | **Webtoon / vertical strip page** | A long page instead of a printed one. A document-size preset, not a feature — unless infinite canvas is wanted, which is a different plan. | ? | 3 | 3 |

### Tier 1 — panels as objects

| # | Feature | Notes | Use | Diff | Time | Needs |
|---|---|---|---|---|---|---|
| 6 | **Panels you can drag and resize** | The panel stops being ink and becomes a thing. | 4 | 4 | 6–8 | — |
| 7 | **Divide a panel by drawing across it** | CSP's divide/cut frame border: draw a line, get two panels with a gutter. The single most-used comic tool in CSP. | 4 | 3 | 4–5 | 6 |
| 8 | **Panels clip their contents** | Art cannot spill out of its frame. **This is what people mean by "a comic program".** | 5 | 4 | 4–6 | groups + masks |
| 9 | **Break the frame deliberately** | Turn clipping off for one layer so a fist comes out of the panel. One toggle, and comics live on it. | 3 | 1 | 1 | 8 |
| 10 | **Angled and non-rectangular panels** | Action pages. Cheap once panels are shapes. | 3 | 3 | 2 | 6 |
| 11 | **Merge panels back together** | Krita 6's other half: remove the gutter, one panel again. | 2 | 3 | 3 | 6 |

### Tier 2 — balloons

| # | Feature | Notes | Use | Diff | Time | Needs |
|---|---|---|---|---|---|---|
| 12 | **A balloon that fits itself around text** | Not a stamp: the balloon is a *property of the text object*, so editing the words reshapes it. This is the decision that makes balloons worth having. | **5** | 3 | 4–5 | text |
| 13 | **A tail you drag to the speaker** | The fiddly part — it has to leave the balloon's outline cleanly at any angle. | **5** | 4 | 4–5 | 12 |
| 14 | **Balloon shapes** | Round, rectangular, spiked (shouting), cloud (thinking), jagged (radio). | 4 | 2 | 2–3 | 12 |
| 15 | **Tail shapes** | Pointed, a trail of bubbles for thoughts, a zigzag for electronics. | 3 | 2 | 2 | 13 |
| 16 | **Border weight and colour, fill colour** | Comes free if the layer-effects stroke exists. | 3 | 1 | 1 | 12 |
| 17 | **Joined balloons** | Two balloons merged into one shape for a two-beat line. | 2 | 3 | 3 | 12 |
| 18 | **Keep balloons inside the safe area** | Warn or nudge when lettering strays into the trim. | 2 | 3 | 3 | 1, 12 |

### Tier 3 — pages and story

| # | Feature | Notes | Use | Diff | Time | Needs |
|---|---|---|---|---|---|---|
| 19 | **Multi-page document** | The subsystem described above: file format, one page in memory, page index, per-page undo. | 5 | **5** | 15–25 | file format |
| 20 | **Page manager** | A thumbnail grid: reorder, insert, delete, duplicate. | 4 | 3 | 4–6 | 19 |
| 21 | **Page templates** | Apply a frame preset to every new page. | 3 | 2 | 2 | 2, 19 |
| 22 | **Story editor** | Every line of dialogue in the book, in one list, editable there and placed on the page. **Cheap once text objects and pages both exist** — it is a view over data, not new machinery. | 4 | 3 | 5 | text, 19 |
| 23 | **Facing-page spread view** | See a double-page spread as the reader will. | 2 | 3 | 3 | 19 |
| 24 | **Export the whole book** | PDF, CBZ, or numbered PNGs. | 4 | 3 | 4–5 | 19 |
| 25 | **Print-ready export** | Bleed included, greyscale or mono, a stated DPI. | 3 | 3 | 3 | 1, 19 |

### Tier 4 — the storyboard delta

Storyboarding shares perhaps 70% of the above — it is multi-page drawing with a
thumbnail grid. These are the parts that are genuinely different, taken from
Storyboarder:

| # | Feature | Notes | Use | Diff | Time | Needs |
|---|---|---|---|---|---|---|
| 26 | **Per-board metadata** | Duration, shot type, action, dialogue, notes — beside the drawing. | ? | 2 | 2–3 | 19 |
| 27 | **Shot numbering** | 1A, 1B, 1C, 2A — boards belonging to one shot numbered as a series. | ? | 2 | 1 | 26 |
| 28 | **Contact sheet export** | A grid of boards as one PDF, which is what gets shown to people. | 3 | 2 | 2 | 19 |
| 29 | **Animatic export** | Boards timed by their durations, out as a video or GIF. | 2 | 4 | 6–8 | 26 |
| 30 | **Camera move marks** | Pan and zoom arrows drawn on a board and understood by the animatic. | 2 | 3 | 3 | 29 |
| 31 | **Scratch audio track** | Storyboarder has it; it is a media subsystem in a drawing app. | 1 | 5 | 8+ | 29 |

### Tier 5 — manga extras that live in other plans

| # | Feature | Where it belongs |
|---|---|---|
| 32 | **Speed and focus lines** | `docs/guides-plan.md` — the radial and parallel rulers, item 10 and 11 |
| 33 | **Screentone / halftone** | `docs/layer-effects-plan.md`, item 24 |
| 34 | **Sound-effect lettering** | `docs/text-plan.md`, item 18 (warp), on top of item 15 (outlines) |
| 35 | **Vertical text and furigana** | `docs/text-plan.md`, items 19 and 22 |

## The order this implies

1. **Now:** page setup with bleed and safe area, frame presets, gutters, inked
   borders. **8–11 days, no prerequisites, and it makes the app usable for
   comics pages today.**
2. **After the text tool:** balloons with tails and shapes. **12–16 days**, and
   this is where it starts feeling like a comic program.
3. **After groups and masks:** panels that clip. **10–15 days**, and the frame
   presets from step 1 upgrade into real panels rather than being replaced.
4. **After a file format:** pages, the page manager, the story editor, book
   export. **30–45 days**, and it is a phase of its own.

Storyboarding then costs perhaps **10–15 days on top of step 4** — unless the
animatic is wanted, which adds a media pipeline.

## The traps

1. **Memory is the gate, not the feature list.** Twenty pages of eight layers do
   not fit in this device. Every Tier 3 item assumes pages live on disk, and that
   assumption is the file format's job. Do not start pages before the format.
2. **A balloon is not a shape with text on top.** If the balloon does not
   re-fit when the words change, every dialogue edit becomes a redraw, and the
   feature quietly stops being used.
3. **Frame presets drawn as ink are a one-way door.** They are the cheap model
   (A) and that is the right call — but the moment panels become objects, the
   ink from the old presets is just ink. Worth saying to the user rather than
   silently migrating.
4. **Undo across pages.** Undo is per-document today. Twenty pages means twenty
   histories, and "undo" pressed on page 3 must not touch page 4. Cheap to
   design in, painful to retrofit.
5. **The export must still match the screen.** Phase 3's one-compositor rule
   applies to panels, balloons and overlays alike; bleed and safe-area marks
   must be visible on screen and **absent from the export**, which is precisely
   the kind of divergence that rule exists to prevent.

## The questions only you can answer

1. **Which kind of comic?** A printed western page, a manga page, a vertical
   webtoon strip and a film storyboard want different things — bleed and spreads
   matter for print, none of them matter for webtoon, and a storyboard wants
   timing instead of gutters. This changes the list more than anything else.
2. **How many pages in one project?** Three, or a hundred? Three can be three
   files with a folder. A hundred is the subsystem.
3. **Do you write the words, or draw them?** The story editor is worth five days
   if you write dialogue and nothing at all if your lettering is hand-drawn.

## Sources

- [CSP: frames and panels](https://help.clip-studio.com/en-us/manual_en/540_comic/Frames_and_Panels.htm), [frame border tool](http://www.clip-studio.com/site/gd_en/csp/userguide/csp_userguide/510_tool/510_tool_koma.htm), [frame border folder](http://www.clip-studio.com/site/gd_en/csp/userguide/csp_userguide/500_menu/500_menu_layer_koma.htm) — divide frame folder vs divide frame border, gutters, per-panel masks
- [CSP: multi-page management and printing](https://tips.clip-studio.com/en-us/articles/2145) — the `.cmc` page management file, bleed width, default border as safe area
- [CSP: story editor](https://tips.clip-studio.com/en-us/articles/2875)
- [Krita: comic panel editing tool](https://docs.krita.org/en/reference_manual/tools/comic_panel_editing_tool.html) — slicing shapes with a gutter, merging them by removing the gap
- [Krita 5.3 / 6.0 release notes](https://krita.org/en/release-notes/krita-5-3-release-notes/)
- [Wonder Unit Storyboarder](https://wonderunit.com/storyboarder/) — per-board timing and shot type, 1A/1B/1C shot series, contact-sheet PDF and animated GIF export
