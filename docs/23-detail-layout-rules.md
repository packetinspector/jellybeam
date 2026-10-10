# 23 — Detail layout rules

**Applies to:** Movie, Series, and Episode detail pages; all three share the structure.
docs/11 is the detail page's UX spec; this document is the layout contract its
implementation cites by rule number.

## The failure these rules prevent

A detail page built as a set of absolutely positioned bands, each placed at a pixel offset chosen for a short title and a three-line synopsis. When either is longer, the overview grows *downward through* the spec strip, the credits panel, and the cast row. Nudging the offsets does not fix it — the next long synopsis breaks it again.

Two root causes, and both must be fixed:

1. **Regions do not reserve space.** Absolute positioning means neighbours cannot push each other.
2. **Text has no ceiling.** The overview and the title are unbounded.

## Rule 1 — One flow, no absolute content

The only absolutely positioned elements are the backdrop image and its two gradients. **Everything else lives in one flex column**, so every region reserves its own height and neighbours displace rather than overlap.

```
frame 1920×1080, overflow hidden
└─ content: inset 0, padding 84px 80px 64px, box-sizing border-box
   display flex, flex-direction column
   ├─ top row      display flex, gap 52px, align-items flex-start
   │  ├─ poster    296×444, flex 0 0 296px, radius 8
   │  └─ text col  flex 0 1 1020px, min-width 0, gap 22px
   ├─ spec capsule margin-top auto, align-self flex-start
   └─ cast band    margin-top 44px, gap 20px
```

**Loading amendment.** While its data loads, a region reserves its final height with a skeleton (docs/11 §Loading state); a region that settles empty collapses once. Absent-until-loaded is the failure this prevents: a region that appears later shoves everything below it.

**`min-width: 0` on the text column is load-bearing.** Without it a flex item refuses to shrink below its content width and pushes into its siblings — which is the overlap, in one line of CSS.

**Never combine `margin-top: auto` with an adjacent region that needs a minimum gap.** Give the flexible region the `auto` and its neighbour a fixed margin. Here the spec capsule takes the `auto`; the cast band sits a fixed 44px below it. If `auto` computed to 0 on a tall page, the two would sit flush — the same class of bug in a new place.

## Rule 2 — Two hard clamps

| Element | Clamp | Overflow |
| --- | --- | --- |
| Title | 2 lines, 64px/1.06 | ellipsis |
| Overview | 4 lines, 25px/1.5, max-height 150px | `MORE ↓`, focusable |

```css
display: -webkit-box;
-webkit-line-clamp: 4;
-webkit-box-orient: vertical;
overflow: hidden;
```

`MORE ↓` is a real focus stop below the overview: Down from Play reaches it, Select opens the full synopsis as a panel, Back closes it. Hide the affordance when the text is not actually clamped.

A TV detail page is a **decision surface, not a reading surface.** Four lines is enough to decide; anyone who wants the rest asks for it.

## Rule 3 — Credits read as prose

Director, writer, and studio sit as one wrapped line directly under the overview, in the same column — `DIRECTED BY` / `WRITTEN BY` in Martian Mono 17px at `#8C8478`, names in Archivo 22px at `#F7E9CE`, `│` at `#322A22` between. No panel, no box, no label gutter.

This keeps the right half of the frame as backdrop, which is what makes the page read as a poster rather than a form.

## Rule 4 — Spec capsule is the break

The technical strip stays where it always was: its own line between the credits and the cast, outlined pill (1px `#322A22`, radius 999px, 12px/10px padding), Martian Mono 18px at `#8C8478`, `│` separators, `align-self: flex-start` so it hugs its content.

It is doing structural work, not just reporting: it is the rule that separates the reading half of the page from the browsing half, and it lands in the same place on every title.

## Rule 5 — Cast items are fixed-size and truncate

Cards are `168px` wide, `flex: 0 0 168px`, gap 44px, 128px round avatars. Name and role are both `white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 168px`. A long name truncates instead of widening its card and knocking the row out of alignment.

## Acceptance tests

Ship none of this until all five pass on a 1920×1080 frame with `overflow: hidden`:

1. **Long everything.** Two-line title + 9-line synopsis + 3 genre chips: no region overlaps another, content bottom ≤ 1080.
2. **Short everything.** One-line title, one-line synopsis: the spec capsule and cast band stay at the bottom, no dead-looking gap in the middle.
3. **Long names.** A 40-character cast name and a 4-name writer list: both truncate, neither reflows its neighbours.
4. **No cast.** Cast band absent entirely: nothing else moves out of position.
5. **Series and Episode.** Same three checks — they inherit the same structure.

## Not covered here

The `MORE ↓` panel itself (layout of the full-synopsis overlay); docs/11 owns it.
