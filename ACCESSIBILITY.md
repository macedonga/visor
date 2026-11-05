# Accessibility

Visor should work for everyone who wears Meta glasses, including people who use a screen reader, large text, or the glasses themselves as an accessibility aid. This file says what Visor aims for, what does not work yet, and how to report a problem.

## Goals

- The app meets WCAG 2.2 level AA where it applies to an Android app, and follows Android's accessibility guidelines.
- Every task works with TalkBack. Each control reads a label, and a switch or a choice reads its state.
- Every screen works at the largest font size and display size. Text sizes are in `sp` and follow the phone's setting.
- Text has a contrast of at least 4.5:1 with its background. Icons and the edges of controls have at least 3:1.
- Touch targets are at least 48 by 48 dp. Compose extends a smaller control's touch area to 48 dp when there is room around it.
- Nothing flashes more than three times a second.
- With the phone's "Remove animations" setting on, nothing on screen moves by itself.

## What works today

- "Hey Meta" goes to the model you choose. It can see what the glasses' camera sees and answers in the glasses' own voice, so the glasses can describe a scene or read a sign.
- Settings holds the glasses' own accessibility settings, such as Detailed responses, Mono audio and Speech speed.
- TalkBack reads each photo and video in the gallery by its kind, day and time, and says when it is a favourite or still on the glasses. In selection mode it says which ones are selected.
- Switches say whether they are on, and lists of choices say which one is selected.
- Screen titles, section labels and the days in the gallery and the Meta AI diary are headings, so TalkBack can jump between them.
- Battery readings say whether they are the glasses' or the case's, and whether they are charging.
- Text is white, `#98989D` or `#86868B` on black and on the dark grey cards, at 4.5:1 or more. Icons, chevrons and the edges of controls are at 3:1 or more.
- With "Remove animations" on, the 3D glasses, their light, the Live view's glow and the stage on the hearing and wake word screens hold still, and the glasses no longer follow the phone's tilt. Compose stops its own animations.
- At large font sizes, labels wrap instead of being cut off, the numbers on Home shrink to fit, and the quick tiles in Settings grow.
- All text is sized in `sp` and follows the phone's font size.
- The app is in English, Italian and Russian.

## Known limitations

- The changes above come from the code. A full pass with TalkBack on a phone has not been done yet.
- With "Remove animations" on, the pairing steps in setup still move: the animation shows where the button and the light are on the glasses. The same steps are written next to it.
- Setup shows Meta's login screens, which Visor draws from Meta's own layout. They have not been tried with TalkBack.
- There is only a dark theme, and no high-contrast option.

## Supported environments

- Android 12 or later, on a phone.
- TalkBack, the phone's font size and its display size. Visor follows all three.
- Not tried: Switch Access, Voice Access, Select to Speak, external keyboards and braille displays. Reports about them are welcome.

## Contributor requirements

For a pull request that changes what a screen shows:

1. Turn on TalkBack and go through the change. Every control reads a label, and its state if it has one.
2. Set the largest font size and display size. Nothing important is cut off or overlaps.
3. Give each icon or image that is the only content of a control a `contentDescription` from the strings, in `values/`, `values-it/` and `values-ru/`. Pass `null` only for a decorative one.
4. Use `sp` for text and the colours in `Visor.colors`. New text stays at 4.5:1 or more against its background.
5. Keep touch targets at 48 dp or more.

The pull request template has a line for this.

## Reporting a problem

Open an issue with the [accessibility form](https://github.com/macedonga/visor/issues/new?template=accessibility.yml). Say what you tried to do, what stopped you, and which assistive technology and settings you use. For a question, ask in [Discussions](https://github.com/macedonga/visor/discussions/categories/q-a).

Every report gets an answer. A problem that stops someone from doing a task comes before everything else. If something cannot be fixed, the answer says why, and gives a way around it when there is one.
