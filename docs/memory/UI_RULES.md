# UI RULES

## 2026-10-09 — approved input vectors; audit boundary

User approves Keyboard/Voice Material vectors PASS in isolated preview only.
Do not redesign without demonstrated usability evidence. Harmony audit and proposed
P1 corrections recorded in UI_HARMONY_AUDIT_2026-10-09.md; no findings implemented.
Provider-screen black PNGs and unrelated desktop host captures are invalid visual
evidence. Provider/form/credential appearance NOT INSPECTED. Left-aligned prose/list
rows remain functionally appropriate; no global centering or footer redesign.

## 2026-10-08 — input vector preview (visual judgment pending)

Keyboard/Voice custom Canvas rejected by user. New consistent Material filled
24dp vectors in existing 48dp transparent controls; no footer/placement change.
Preview-only com.nirowear.iconpreview inspected 466x466 at exact font1.0/1.3:
idle, short response, long+Retry, detached+Latest, local streaming+Stop.
Targets 96x96px; idle [131,314][227,410] / [239,314][335,410], 12px gap;
Latest row [77,274][173,370] / [185,274][281,370] / [293,274][389,370].
All target corners round-safe; reader height unchanged (idle218px, detached178px,
stream following234px). Keyboard edge tap opened editor; Voice edge tap left preview
and Back returned, but empty external hierarchy is not full voice intent acceptance.
Descriptions/clickable ancestor geometry verified; Role.Button preserved in source,
fresh focused instrumentation NOT RUN. Raw UI dump exposes View wrappers, not role.
Screenshots in `docs/screenshots/` are captured at 466x466. Layout changes need owner approval.

## Reference viewport

Design and test primarily for a round 466x466 px display.

## Chat layout

Avoid:

- large speech bubbles
- avatars
- oversized decorative cards
- unnecessary chrome

Prefer:

- document-like assistant response
- full readable width where geometry permits
- stable typography
- clear vertical rhythm
- long vertical scrolling

## Empty/new chat

Show both input options clearly:

```text
Ask anything

[ Keyboard ]  [ Voice ]
```

## Header

Keep compact:

```text
<      Model Name v      ...
```

## Long answers

Do not insert arbitrary pagination or Continue barriers.

## Streaming scroll

- near bottom -> follow stream
- user scrolls up -> stop auto-follow
- expose `Latest` affordance

Completed manual scroll stays detached even at tail. Otherwise fit measurement
can pull user away from Retry. `Latest` explicitly restores fit/content-tail view;
Retry may require natural scroll when answer+action cannot fit together. Never
shrink prose to display action. Runtime footer reserves actual reader viewport.

## Context actions

User message:

- Edit
- Copy

Assistant message:

- Copy
- Retry

Use contextual interaction rather than permanently visible buttons.

## Text sizing

Provider/History hierarchy uses centered screen context first, clear navigation,
consistent spaced rows. Provider entry labels remain distinct from values; single-line
48dp-min fields show visible focus border, password masking stays default. Secondary
setup actions use full-width outlined buttons; Save stays primary. No tiny typography
or reader scrolling changes to obtain visual approval. Second live release revision
provisionally approved by user; streaming/scaled layouts still require acceptance.

Start around readable smartwatch sizes and tune visually. Avoid tiny text just to reduce scrolling.

Provide Small / Medium / Large later.
