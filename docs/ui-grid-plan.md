# One grid, and shapes drawn on it

`docs/ui-expansion-plan.md` built a system in which a toolbar could be any
shape, and then kept it inside the frame the old one had: four edges, a
floating layer, and five presets to pick from. The user used it, and asked for
the frame to go.

> *"remove the default side-docking altogether and only keep the 'draw the
> shape' functionality. Keep only the 'grid'. No docking toolbars, no floaters.
> Just the drawing grid. This will simplify the functionality a lot."*
>
> *"In arrange mode, if the above changes are done, the entire screen can be in
> 'draw a shape' mode."*
>
> *"The T, L and U, left and right, numpad, and docking things can go in favor
> the only draw method."*

That is one change with a lot of consequences, and this document is the record
of the consequences rather than of the change.

## What goes

| Gone | Why it was there | Why it can go |
|---|---|---|
| `Dock` | Five named places a bar could be | A surface's place is now the cells it is drawn on |
| `BarSpot` | A floating bar's position as a fraction | A drawn surface has an origin in cells, like every other surface |
| `SurfaceShape` | Bar, L, T, U, Block presets | Drawing takes about as long as choosing, and the drawn one is right |
| `ShapeMenu` | The menu the presets lived in | Nothing left in it that is not in the arrange bar |
| `BarView` / `BarRun` / `SlotCell` | The renderer for a bar | `ChromeSurface` already draws every shape, including a one-cell strip |
| `FloatingBarView`, `Grip`, `ResizeHandle`, `CloseBar` | Furniture around a floating bar | A surface is moved by its grip and reshaped by being redrawn |
| `dockInto` | Dropping a floating bar onto an edge | There is nothing to dock to |
| `DockTarget` | *Move this to the left edge* | Ditto |

`DockLayout`, `DockStore`, `DockCodec` and `DockHost` **keep their names**. A
dock layout is an arrangement, and renaming four files to prove a point costs
every reader of the git history their `git log --follow`.

## What a surface is now

```kotlin
class Surface(
    val id: String,
    val flow: FlowOrder,
    val slots: SurfaceLayout,   // its region is in SCREEN cells
)
```

The one change in the model, and everything else follows from it: **a region's
coordinates are screen cells, not cells within the surface.** `bounds.x` and
`bounds.y` are where it sits. There is no anchor, no alignment and no fraction,
and `fittedTo` becomes the only thing that ever moves a surface without being
asked.

Consequences worth stating:

- **Two surfaces cannot overlap**, which `docs/ui-expansion-plan.md` U4 wanted
  and could not have: it needed every surface's position in screen cells, and
  nothing computed one. Now everything does. The paint gesture skips a cell
  another surface owns, and `DockLayout.of` drops the later of two claims.
- **There are no compulsory surfaces.** The four edges existed because the enum
  had four entries. A workspace with one surface has one surface, and an empty
  workspace is a blank screen with a grid under it in arrange mode.
- **Rotation clamps by shifting first and cutting second.** A surface against
  the right edge of a wide screen slides inward on a narrow one rather than
  losing its last cells. What cannot be saved is still overflowed, and the file
  on disk is still never rewritten — *clamp for the screen, never for the file*.

## Arrange mode is the grid

One mode, two gestures, and a switch between them because a drag cannot be two
things at once:

- **Tools** (the default). The surfaces answer the pointer: drag a control to
  move it, tap an empty cell to choose one, drag a surface's grip to move the
  whole thing.
- **Shape**. The screen answers the pointer. Paint cells to grow the surface the
  stroke started on, or to start a new one on bare grid. Turn the pen over — or
  hit Subtract — to rub cells out; a surface with no cells left is gone.

The grid is drawn in both, faintly, because it is the ruler everything is
measured against and because it is what tells you that arrange mode is on.

`ShapeEditor`'s board was half the screen, aligned to the edge the bar was on,
and applied on Done. None of those survive: the board is the screen, there is
nowhere to align it, and an edit lands as it is painted. What survives is the
part that was hard — `CellRegion.ofCells` being **stable** rather than minimal,
so the outline does not shimmer under a pen that is still moving.

## A panel hangs off

`ToolKind.PANEL`'s KDoc already said it: *"drawn as a card that overhangs the
bar it is anchored to"*. What it did in practice was rely on `CellRegion.accepts`
treating a strip as one-dimensional, so a seven-by-eleven panel needed eleven
cells of a twelve-cell left edge and was refused everywhere else. The user found
that from the other end:

> *"When the user presses a + button in arrange mode, he can choose from the
> component list. BUT, the panels are not selectable."*

They were selectable in about two cells of the whole app. So the rule is stated
properly and applied to every shape:

> **A panel needs the cell it is anchored to, and hangs off whatever it
> overhangs.** Everything else has to be inside the shape, corner to corner.

Which deletes the strip/shape asymmetry in `accepts` — a bar no longer grows,
because nothing needs it to. The overhang is still reserved against other
controls on the same surface, so a panel and a button cannot be drawn on top of
each other.

## The chooser gets tabs

> *"The component selector needs an upgrade. The search bar is great. But it
> needs tabs, that groups related buttons and panels together."*

The tabs are `ToolGroup`, which is the grouping the menu already had as
headings and which had grown too long to scroll. The search field stays exactly
where it is and keeps searching the **whole catalogue** regardless of tab or
filter — that is trap 2's mitigation from `docs/workspace-plan.md` and it is not
optional.

## The arrange button

> *"The button to go in arrange mode is a 'move' icon. That is not a good match.
> Take some layout-related icon."*

Four rectangles in a grid. It is what the mode does.

## Format changes

Both formats move, and both keep reading what came before, because the tablet
has one arrangement on it that took its owner an evening to build.

**The preference string** goes to `v5`: the surface id, its region in screen
cells, its flow, its contents. `v1`–`v4` decode with an **edge hint** — the dock
they named, remembered but not honoured — which is turned into real cells the
first time the layout meets a real screen, and saved. That is the only thing in
the system that knows a dock ever existed, and it exists to be deleted once
nobody is running a build older than this one.

**The workspace file** goes to format `2`: `"at": [x, y]` in cells, no
`"dock"`. A file written against format 1 still opens; its `dock` becomes the
same edge hint.

## Order of work

1. `CellRegion.accepts`, and the panel rule. Pure, tested, no renderer.
2. `Surface` without a dock; `DockLayout` without compulsory edges; overlap.
3. `DockCodec` v5 and the edge hint.
4. `DockHost`: one renderer, the grid, the two gestures, the grip.
5. The chooser's tabs, and the arrange icon.
6. `WorkspaceJson` format 2, the shipped workspaces, the examples, the docs.

Commit between each. The build stays green at every one of them.
