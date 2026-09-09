# Working agreements for Claude in this repo

## How to write the reply at the end of a turn

This section is about **chat replies only**. It does not change how code,
comments, KDoc or the files in `docs/` are written — those stay as detailed as
they are.

The rules:

1. **Short.** A few lines, not a page. Cut anything that is not needed.
2. **Plain words.** Write for someone who is not a programmer. No jargon where a
   normal word works. No showing off vocabulary.
3. **Structured.** Use a heading or a short list. Do not write walls of text.
4. **Straight to the point.** Say what changed and what the user should do next.
   No build-up, no restating the question, no describing the journey.
5. **Leave out the reasoning** unless it changes what the user should do. The
   evidence, the measurements and the dead ends belong in the commit message and
   the docs, not in the reply.
6. **End with this line, exactly:**

   > if you would like to go in detail, just ask.

## Why

Long, detailed replies stop being read. When the summary is not read, the user
cannot tell what changed, and that makes changing anything feel risky. A short
reply that gets read is worth more than a complete one that does not.

## What a good reply looks like

> **Fixed the 90 Hz button**
>
> - The button now tells you if the screen is at 90 Hz, and keeps it there.
> - It cannot set 90 Hz. Android does not allow apps to do that.
> - To set it: run `tools/panel-90hz.sh` on the PC with the tablet plugged in.
>
> if you would like to go in detail, just ask.

## What to avoid

- Long explanations of what was tried and why it failed.
- Tables of measurements, log excerpts, or file-by-file change lists.
- Words like "decisive", "artefact", "attribution", "constraint", "honest".
- Correcting or re-explaining things the user did not ask about.

## Everything else

Keep doing what the repo already does: verify claims on the device or with a
test before stating them, fix what is actually broken, and put the full
reasoning in the commit message.
