# The workspace file

> A workspace is one JSON file. This is what is in it, what each field means,
> and what happens when a field is wrong.
>
> Two other files go with this one. `docs/catalogue.json` is the vocabulary —
> every tool id, every group, every fill order, every anchor, and how big a cell
> is. `docs/workspace-example.json` is a complete workspace written by the app's
> own encoder. **Hand a model these three and it has everything it needs.**

## What a workspace is

Three things, and the third is what makes it more than a saved layout:

1. **An arrangement** — which control is in which cell of which toolbar.
2. **A filter** — what you are *offered* when you go looking for a control.
3. **Defaults** — what the tools are set to when you arrive.

A filter never takes a control off a toolbar. It decides what the chooser
offers, and nothing else. That is what makes switching workspace safe: it
changes the menu, not your bars.

## The grid

Everything is counted in **cells**. One cell is 44dp — `cell_dp` in
`catalogue.json` says so, and it is the only number you need to picture a shape.

A toolbar's shape is a list of rectangles, each `[x, y, w, h]` in cells, with
`x` and `y` measured from the toolbar's own top-left corner. The union of them
is the shape. Overlapping is fine; it is just a longer way of writing the same
thing.

```
"rects": [[0, 0, 1, 9], [0, 9, 5, 1]]
```

That is an L: one cell wide and nine tall, with a foot five cells across the
bottom. Thirteen cells in all.

Where the shape sits on the glass is the **anchor's** business, not the file's.
An edge toolbar is pinned to its edge; you choose the shape and the edge chooses
where it goes. A floating one carries `at`.

## The file

```json
{
  "artiest_workspace": 1,
  "catalogue": 1,
  "id": "example",
  "name": "Example",
  "description": "Every field in the reference, in one file.",
  "author": "artiest",
  "revision": 1,
  "filter": {"groups": ["edit", "draw"], "hide": ["grain"], "show": ["layers_panel"]},
  "defaults": {"brush": null, "shelf": [], "stabilisation": 0.35},
  "surfaces": [
    {
      "id": "left",
      "dock": "left",
      "rects": [[0, 0, 1, 9], [0, 9, 5, 1]],
      "flow": "down_right",
      "tools": [
        {"id": "pen", "at": [0, 0]},
        {"id": "pencil", "at": [0, 1]},
        {"id": "eraser", "at": [0, 2]},
        {"id": "size", "at": [0, 4]},
        {"id": "colour", "at": [0, 9]}
      ]
    },
    {
      "id": "f1",
      "dock": "float",
      "at": [0.72, 0.18],
      "rects": [[0, 0, 8, 1]],
      "flow": "right_down",
      "tools": [
        {"id": "layers_panel", "at": [0, 0], "size": [8, 9]}
      ]
    }
  ]
}
```

`docs/workspace-example.json` is that file in full, with all four edges.

## The fields

### The document

| Field | Type | What it is |
|---|---|---|
| `artiest_workspace` | number | **Required.** The format version. Without it this is not a workspace file. |
| `catalogue` | number | Which catalogue the file was written against. Advisory: an older one still opens. |
| `id` | string | The file name. Lower case, letters, digits and dashes. Made from `name` if absent. |
| `name` | string | What the chooser calls it. Up to 64 characters. |
| `description` | string | One line, for the chooser. Up to 512 characters. |
| `author` | string | Who made it. Up to 64 characters. |
| `revision` | number | Goes up on every save. An integer, not a clock. |
| `filter` | object | See below. Absent means everything is offered. |
| `defaults` | object | See below. Absent means nothing is set. |
| `surfaces` | array | The toolbars. Up to 16. |

### `filter`

| Field | Type | What it is |
|---|---|---|
| `groups` | array or null | Group ids from the catalogue. **`null` means all of them.** |
| `hide` | array | Tool ids to take out of those groups. |
| `show` | array | Tool ids to put back. **Wins over both of the others.** |

`show` is where the chooser's search box writes when you say *add this to my
workspace*. It beats `groups` and `hide` so that a tool you have deliberately
asked for cannot be taken away again by a rule.

### `defaults`

| Field | Type | What it is |
|---|---|---|
| `brush` | string or null | A brush preset id. |
| `shelf` | array | Brush preset ids, in order. |
| `stabilisation` | number or null | Nought to one. |

Fields appear here **in the release that makes them do something**. A workspace
promising a brush the build cannot select would lie on the first tap, so the
list is short and honest rather than long and aspirational.

### `surfaces[]`

| Field | Type | What it is |
|---|---|---|
| `dock` | string | `left`, `top`, `right`, `bottom` or `float`. |
| `id` | string | `left`…`bottom` for the edges; `f1`, `f2`, … for floating ones. |
| `at` | `[x, y]` | Floating only. Fractions of the window, nought to one. Ignored on an edge. |
| `rects` | array | The shape, `[x, y, w, h]` per rectangle, up to 32. Absent means the plain bar for that edge. |
| `flow` | string | Which way it fills. See `flows` in the catalogue. Absent means along the edge. |
| `tools` | array | What is on it, up to 256. |

The four edges always exist. One that is not in the file is an empty bar, not a
missing one.

### `surfaces[].tools[]`

| Field | Type | What it is |
|---|---|---|
| `id` | string | A tool id from the catalogue. |
| `at` | `[x, y]` | **Optional.** The cell its top-left corner sits in. |
| `size` | `[w, h]` | **Panels only.** A size the user chose. Ignored on anything else. |

**`at` is optional, and that is the field that makes this format writable by
hand.** A tool that says where it is goes exactly there. A tool that says
nothing is packed into the first free cell in `flow` order, along with every
other tool that said nothing, in the order they are listed. So this is a
complete, valid toolbar:

```json
"tools": [{"id": "pen"}, {"id": "pencil"}, {"id": "eraser"}, {"id": "colour"}]
```

**There are no other sizes anywhere.** How many cells a slider takes follows
from the catalogue and from the shape it lands in, both known when the file is
read. Writing it down would mean a release that changes a control leaves every
shared workspace laid out to a number nothing believes any more. A panel is the
exception because a panel is the one thing the user can resize, and a size
somebody chose is theirs.

## When something is wrong

**Nothing is fatal except not being a workspace file at all.** Every other
problem drops one thing, keeps the rest, and is *reported* — the importer shows
you a list before it applies anything.

| What | What happens |
|---|---|
| A tool id this build does not have | Dropped. *"perspective_ruler — this build has no such tool"* |
| A field this build does not know | Dropped, and named. |
| `artiest_workspace` newer than this build's | Read anyway, with a note. |
| A rectangle that is not four numbers, or is off the grid | Dropped. The shape keeps the rest. |
| A toolbar with no usable rectangles | Given the plain bar for its edge. |
| The same toolbar twice | The first one is kept. |
| The same tool on two toolbars | The first one is kept. |
| A tool with nowhere to fit | Reported. It is not on the bar, and you are told. |
| Not JSON, or no `artiest_workspace` | **Refused.** Nothing is imported. |

Importing **never overwrites**. A file always becomes a *new* workspace, and a
name that is already taken gets `-2` on the end. A file from a stranger that
could silently replace a month of arranging is the one unrecoverable thing this
feature could do, and *"are you sure"* is not a defence, because people say yes.

## The limits

Every one of these is a number a stranger typed that would otherwise size an
allocation. Past them, the file is refused rather than trimmed.

| Limit | Value |
|---|---|
| File size | 64 Ki characters |
| Nesting depth | 16 |
| Values in the document | 8 192 |
| Any one string | 4 096 characters |
| Toolbars | 16 |
| Rectangles per toolbar | 32 |
| Tools per toolbar | 256 |

The format has **no field that carries a URL, a path, a colour named after a
resource, or anything else that is resolved rather than read**. That is a
property of the schema rather than of the reader, and it is why this can be a
file people send each other. Names and descriptions are stripped of control
characters and of the bidirectional overrides before they reach a label.

## Writing one with a model

Paste in:

- `docs/catalogue.json` — every id it may use
- this file — what the fields mean
- `docs/workspace-example.json` — one that works

Then describe the artist. *"A workspace for inking a comic page: brushes and the
eraser down the left in an L with the size and stabilisation sliders along the
foot, undo and redo top left, nothing else."*

Two things worth telling it, because they are the mistakes a first attempt
makes:

- **Leave `at` out unless the position matters.** A list of ids in the right
  order is usually the better file: it packs itself, and it survives a screen
  that is a different size.
- **Every id must be in the catalogue.** Inventing `perspective_ruler` produces
  a file that opens and quietly has one fewer tool than intended — which the
  importer will say, in that many words.

## See also

- `docs/workspace-plan.md` — why any of this exists
- `docs/ui-expansion-plan.md` — how it was built, item by item
- `docs/catalogue.json`, `docs/workspace-example.json` — generated by
  `./gradlew :app:catalogueJson`
