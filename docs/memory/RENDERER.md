# Renderer contract

The response renderer is a core subsystem of NiroWear, not a formatting detail. This document defines what it must do. It is a contract, not a changelog.

## Goal

Turn assistant Markdown into a **document** that is readable on a 466 × 466 round display — never one giant text node, never a messenger bubble.

Constraints that shape everything below:

- The screen is round, so corners are not available for content.
- A line of prose holds roughly 28–34 characters at a comfortable size.
- Anything wider than the screen must pan, not shrink to illegibility.
- Content arrives incrementally and may be interrupted at any byte.

## Semantic blocks

The parser produces a block list, not a string. At minimum:

- `Paragraph`
- `Heading`
- `BulletList`
- `NumberedList`
- `Quote`
- `InlineCode`
- `CodeBlock`
- `InlineMath`
- `DisplayMath`
- `Table`
- `Divider`
- `Link`

Each block renders independently, so a wide table cannot force a paragraph to reflow and a code block cannot clip the block after it.

## Markdown

Support: headings, bold, italic, lists, code, blockquote, links, horizontal rules, tables, inline math and display math.

Arbitrary HTML is not required and is not rendered.

## Streaming behavior

Maintain **finalized blocks plus one provisional trailing block**. Re-parse only the trailing region as new text arrives; do not re-parse the whole document per token.

Incomplete Markdown or LaTeX must never crash rendering. An unterminated code fence, a half-typed table row or a dangling `\frac{` is normal mid-stream, not an error.

Raw Markdown is stored verbatim, so the renderer can improve without a data migration.

## Math

Required categories: Greek, arithmetic and operators, calculus, set theory, logic, arrows, physics/science symbols, mathematical alphanumeric symbols, fractions, roots, powers and subscripts, summation and product, integrals, limits, matrices, cases, aligned equations.

### Wide math

Handling order, strictly:

1. native layout;
2. math-aware line breaking;
3. horizontal pan;
4. modest scaling — only if the above cannot fit.

**Never** reduce a formula to illegible size. A swipe is acceptable; a 4 pt fraction is not.

## Tables

Simple semantic tables transform into narrow vertical records rather than a squeezed grid.

Source:

| Model | Speed | Context |
|---|---|---|
| A | Fast | 128k |

Watch rendering:

```text
MODEL A
Speed    Fast
Context  128k
```

Complex tables may use a focused or horizontally pannable viewer. Either way, every cell must remain reachable — no silent truncation.

## Code

Preserve indentation and whitespace exactly. Monospace only. Horizontal pan is allowed and preferred over wrapping, because wrapped code changes meaning visually even when the bytes are identical.

## Reading position

The reader follows incoming content while the user is near the bottom. If the user scrolls upward, following stops and a small `Latest` control appears. Scrolling back to the bottom resumes following. Never yank the viewport away from someone reading.
