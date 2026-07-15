# AIDiag Console Design System

## Visual Theme

The approved direction is **Incident Casefile**: a dark, modern, evidence-first product interface. It combines an Operations Inbox for triage with a curated incident narrative and a focused evidence examination plane.

Physical scene: a solo engineer reviews a production incident on a 27-inch monitor in a dim room, needing five-second orientation and long-session readability. Dark mode is therefore structural, not decorative.

## Color Strategy

Use a restrained palette. Blue-violet occupies less than 10% of the visual weight and only marks selection, focus, causal links, and active evidence. Semantic state colors retain their own labels and icons.

```css
:root {
  --color-bg: oklch(0.15 0.012 275);
  --color-surface-1: oklch(0.19 0.014 275);
  --color-surface-2: oklch(0.23 0.016 275);
  --color-surface-3: oklch(0.27 0.018 275);
  --color-border: oklch(0.34 0.018 275);
  --color-text: oklch(0.91 0.012 275);
  --color-text-muted: oklch(0.68 0.018 275);
  --color-accent: oklch(0.68 0.16 288);
  --color-accent-soft: oklch(0.26 0.055 288);
  --color-success: oklch(0.76 0.16 136);
  --color-warning: oklch(0.79 0.15 82);
  --color-critical: oklch(0.68 0.20 28);
  --color-info: oklch(0.73 0.10 235);
}
```

Do not use pure black, pure white, zero-chroma gray, broad blue washes, or alpha-heavy surface palettes. Elevation comes from explicit surface lightness, not heavy shadows or blur.

## Typography

- UI stack: `Inter`, `Segoe UI`, `PingFang SC`, `Microsoft YaHei`, `system-ui`, `sans-serif`.
- Data stack: `JetBrains Mono`, `Cascadia Code`, `SFMono-Regular`, `Consolas`, `monospace`.
- Use a fixed product scale: 0.75rem, 0.875rem, 1rem, 1.25rem, 1.5rem.
- Body text is at least 1rem on mobile; dark-mode body copy receives slightly increased line height and letter spacing.
- Use tabular numbers for timestamps, durations, counters, and telemetry.
- No serif display headings and no fluid dashboard typography.

## Spatial System

- Use a 4px base with semantic spacing tokens: 4, 8, 12, 16, 24, 32, 48, 64px.
- Desktop shell: compact navigation, flexible evidence workspace, contextual right rail.
- Group content with alignment, space, and 1px separators before introducing a card.
- Never nest cards. The evidence plane is one work surface with internal sections.
- Minimum interactive target is 44px even when the visible icon is smaller.

## Core Surfaces

### Operations Inbox

- Show Needs attention, Watching, and Recently resolved as editorial incident rows.
- Keep environment, data freshness, search, and time context visible.
- Selecting an incident opens a contextual preview without losing list position.

### Incident Casefile

- Left: curated narrative moments from alert through recovery.
- Center: selected Metric, Log, Trace, or Report evidence with incident-vs-baseline comparison.
- Right: evidence completeness, limitations, audit history, and collapsed AI Advisory.
- Topology is allowed only as a small contextual dependency view inside relevant trace evidence.

### Mobile Read-only

- Navigation collapses to a compact header and drawer.
- Casefile moments become a vertical list; the selected evidence and audit sections use native disclosure.
- Tables convert to labeled rows. No horizontal page scrolling is required for core content.

## Component States

Every interactive component implements default, hover, focus-visible, active, disabled, loading, error, and success states. Focus rings use the blue-violet accent with at least 3:1 adjacent contrast and a 2px offset.

Use skeletons shaped like real content. Empty states explain what the absence means. Error states say what failed, why when known, and what the operator can safely try next.

## Motion

- 100-150ms for press and color feedback.
- 150-250ms for tabs, selection, route crossfades, and evidence reveal.
- 300ms maximum for mobile drawers and disclosure layout changes.
- Use `cubic-bezier(0.25, 1, 0.5, 1)` by default; never use bounce or elastic easing.
- Animate `transform` and `opacity`; bound small glow/filter effects to the active evidence cursor.
- The signature motion is a causal pulse that travels only between selected, linked incident moments.
- No permanent background particles, page-load choreography, or animation of width, height, top, left, or margins.

Under `prefers-reduced-motion`, replace spatial movement with an immediate color and opacity state change.

## Data Visualization

- Prefer compact semantic SVG for sparklines, evidence markers, and dependency context.
- Do not add a large charting framework for V1.1.
- Always provide visible labels, units, baselines, and non-color distinctions.
- Raw logs, prompts, answers, cookies, tokens, user IDs, and session IDs never appear in visual examples or production responses.

## Performance Budget

- Initial JavaScript: at most 220 KB gzip.
- Initial CSS: at most 60 KB gzip.
- LCP under 2.5s, INP under 200ms, CLS under 0.1 on the acceptance profile.
- Route-split non-critical pages and lazy-load evidence detail code.
- Avoid WebGL, canvas backgrounds, remote font dependencies, and oversized raster assets.

## Absolute Bans

- Gradient text, decorative glassmorphism, topology wallpaper, hero-metric templates, identical card grids, and nested cards.
- Chat-style AI as the primary incident interface.
- Hover-only actions, invisible focus, ambiguous labels, and decorative motion without state meaning.
- Controls for restart, shell execution, or automatic remediation in V1.1.
