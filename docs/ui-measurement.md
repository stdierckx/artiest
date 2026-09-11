# Measuring the chrome — U10

> The last item of `docs/ui-expansion-plan.md`, and the one it calls a gate:
> **a dropped frame while drawing is the thing this project does not tolerate.**
>
> **Status: not measured.** The instrument is built and shipping; the reading
> has not been taken, because it needs a pen, a tablet and sixty seconds. Until
> it is taken, U10 is not done and the workspace system is not finished.

## The number

Turn the instruments on and the readout gains one line:

```
chrome   3 surfaces   20 controls   recompose 0/s   41 total
```

The middle number is the whole test.

> **While a stroke is being drawn, `recompose N/s` must read 0.**

Not "small". Zero. Nothing about a toolbar changes while a line is being drawn,
so any number at all is something in the chrome reading state it has no business
reading, and it is work on the UI thread in exactly the frames that matter most.

## Why this and not a frame-time histogram

A histogram measures everything and blames nothing. If the 99th percentile
moves, the next question is *which part*, and that question is answered by
bisecting a UI — which is a day, and a day nobody spends.

The chrome's failure mode is specific and has one shape: a composable over the
ink path that recomposes while the pen is down. Counting that directly turns a
day of bisection into a glance, and it turns "does the new chrome cost
anything?" into a question with a yes-or-no answer.

The frame-time comparison below is still worth doing once. This is what makes it
unnecessary to do again.

## The reading

1. Install the debug build on the tablet.
2. Switch to **Everything**, then arrange the bars into three surfaces with
   about twenty controls between them — one shaped surface among them, because
   the shaped renderer is the new code.
3. Turn on **Instruments**.
4. Draw continuously for sixty seconds. Long strokes, short strokes, lift and
   start again. Watch `recompose N/s` the whole time.

**Pass:** it reads `0/s` for the entire sixty seconds, and `total` does not
move once the last toolbar has settled.

**Fail:** any non-zero reading during a stroke. The fix is known and boring —
find what the surface is reading that changes while drawing, and hoist it into
a child. `ChromeSurface`'s `Layout` reads `surface.region` and `surface.slots`
and nothing else, and `slotContent` already isolates each control's own state,
so a leak is a new one and will be recent.

## The comparison worth doing once

Same drawing, same sixty seconds, twice:

- **Before** — four plain bars, as the app shipped before any of this.
- **After** — three surfaces including one shaped.

Compare `event p99` and `submit p99` from the same readout. The number that
matters is the **99th percentile, not the mean**: the mean hides the one frame
in three hundred that is late, and the one frame in three hundred is what a
person feels as a catch in the line.

Record both readings in the commit that takes them.

## The other thing to check on the tablet

Not a number, and it is the one claim in `ChromeSurface.kt` that is asserted
rather than proved:

> **A stroke started inside the notch of an L must reach the canvas.**

Make an L-shaped toolbar, start a stroke in the hollow of it, and confirm the
ink appears and `RejectionCounters` says the pen owned the stroke. The design
says it must — the surface carries no pointer modifier of its own, so the notch
has no interactive child and nothing to consume the down — but a pointer path is
not something to be sure of from reading.

## See also

- `docs/ui-expansion-plan.md` — U10, and the nine items before it
- `ChromeCounters.kt` — the instrument, and why it is not Compose state
- `ChromeSurface.kt` — the pen-through-the-notch rule, in full
