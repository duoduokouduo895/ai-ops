# AIDiag Console Design System

## Visual Theme

The approved direction is **Incident Casefile**: a dark, modern, evidence-first product interface. The console is an event reading tool. A first-time reviewer should see what the page is for, what happened, the current state, and the diagnosis, then open evidence only when needed.

Physical scene: a solo engineer reviews a production incident on a 27-inch monitor in a dim room, needing five-second orientation and long-session readability. Dark mode is therefore structural, not decorative.

English terms stay on screen (`Inbox`, `Incidents`, `RESOLVED`). Status marks themselves reveal a short bilingual hint on hover, focus, or tap. Page-level 说明 only introduces the current page. Chinese is never a second “simple / expert” mode.

## Color Strategy

Runtime color values live in [`frontend-console/src/styles/tokens.css`](frontend-console/src/styles/tokens.css). Do not replace those tokens with the historical examples below.

Use a restrained palette. Blue-violet occupies less than 10% of the visual weight and only marks selection, focus, causal links, and the current navigation item. Semantic state colors retain their own labels; never rely on color alone.

Do not use pure black, pure white, zero-chroma gray, broad blue washes, or alpha-heavy surface palettes. Elevation comes from explicit surface lightness, not heavy shadows or blur. Body copy uses `--text` or `--text-soft`, not `--text-faint`.

## Typography

- UI stack: `Inter`, `Segoe UI`, `PingFang SC`, `Microsoft YaHei`, `system-ui`, `sans-serif`.
- Data stack: `JetBrains Mono`, `Cascadia Code`, `SFMono-Regular`, `Consolas`, `monospace`.
- Page titles are about 24px. Body copy is 14–16px; explanation drawer body copy is at least 16px.
- Use tabular numbers for timestamps, durations, counters, and telemetry.
- No serif display headings and no fluid dashboard typography.

## Spatial System

- Use a 4px base with semantic spacing tokens: 4, 8, 12, 16, 24, 32, 48, 64px.
- Desktop shell: compact navigation rail, single reading column. Content max width is about 1120px; Casefile reading width is about 960px.
- Group content with alignment, space, and 1px separators before introducing a card.
- Never nest cards. Lists and casefiles are flat; avoid repeating panel fills and stacked borders.
- Minimum interactive target is 44px even when the visible icon is smaller.
- The page-introduction drawer is about 420px on desktop and full available width on mobile. Local hints are a compact floating panel, not a second dictionary.

## Core Surfaces

### Login

A centered panel about 360px wide: AIDiag mark, 登录, one “使用 Cloudflare 账号登录” link, and session/error notices. Access owns authentication. Username/password fields remain only in the explicit Session rollback build. No marketing column, illustration, or large frame.

### Operations Inbox

Page title `Inbox` with a 说明 control. One compact summary line for `Active incidents`, `Pending runs`, and `Resolved · 24h` — not a hero-metric card wall. `Pending runs` are unfinished diagnosis tasks, not “watching”. Recent incidents include recovered history; empty results only mean the current query returned no events. Between the summary and the list, a 14-day incident trend (stacked columns: CRITICAL vs WARNING / INFO, UTC days).

### Incidents

A searchable directory. Filters stay in the URL. Desktop rows show title, service, status, severity, last alert time, and evidence count. Title and service wrap. Pagination is “加载更多”, not infinite scroll. Returning from a casefile restores filters, loaded rows, and scroll position.

### Incident Casefile

A single reading column, in this order:

1. Event summary, open by default.
2. Diagnosis, open by default. Only a supported root cause is a conclusion.
3. Evidence, collapsed. Count in the heading. Available / Missing for METRIC, LOG, TRACE. No importance sparkline; a METRIC item shows its collected curve against the rule threshold (legacy evidence without a curve says so).
4. Timeline, collapsed. Only evidence entries jump to evidence.
5. Run details and AI advisory, collapsed. AI is auxiliary; do not invent advice text.

Partial failures stay in their section. 401 still uses the global session-expiry path.

### AI-chat

The monitored-object page. Range control (`1h` / `6h` / `24h`, kept in the URL) sits in the header. One flat summary line: external probe, availability over the window, firing rules, breached signals. Signals are grouped as 可用性与请求 → 容量 → 依赖, two columns on desktop and one on mobile, separated by 1px rules rather than cards. Each signal shows its current value, a `signal` status, and — when alerted — the rule name with its `rule` state. The threshold is a dashed warning line labelled `阈值 N`, identical to the Prometheus rule. `NO_DATA` and `QUERY_FAILED` are stated in place; they are never drawn as zero. Related incidents follow at the bottom.

### Charts

- Hand-written SVG, no chart library; measured in real pixels so text is not stretched.
- 2px lines, ~10% area wash only for single non-binary series, 1px solid grid in `--chart-grid`, end dot with a 2px surface ring.
- Single series use `--chart-single` (accent). Multi-series use `--series-1..4`, bound to the entity (`success`, `timeout`, `error`, `cancelled`) in `utils/chart.ts`, never to rank; the palette is validated against `--surface-1`.
- Missing samples break the line; the x-axis always spans the requested window.
- Hover and keyboard (←/→/Home/End/Esc) share one crosshair tooltip listing every series; every chart has a 数据表 disclosure; a legend appears for two or more series.

### Services

Service name, environment, overall status, and application status by default. Dependencies and technical identifiers are disclosed on demand. `UNKNOWN` means current data is insufficient.

### Mobile Read-only

- Navigation collapses to a compact header and drawer.
- Casefile sections stack vertically with native disclosure.
- List rows keep title, service, status, and time; they do not hide those fields to look simpler.
- No horizontal page scrolling is required for core content.

## Component States

Every interactive component implements default, hover, focus-visible, active, disabled, loading, error, and success states. Focus rings use the blue-violet accent with at least 3:1 adjacent contrast and a 2px offset.

Status components take a domain (`incident`, `run`, `service`, `report`, `ai`, `severity`, `pipeline`) so the same English token is not given one explanation. The whole mark (dot plus English text) is the explanation control; do not add a second nearby info icon. Hover about 250ms, keyboard focus immediately; click pins the short hint. The pointer can move into the hint to keep reading. Closing is delayed after leave, and Escape dismisses it without immediately reopening on the same hover or focus session. Outside click or route change also closes it. Unknown values keep their original text and the unknown tone.

Field labels that need a definition use a light dashed underline (`HintTerm`) once on the label or section title. Small info icons are only for denser blocks such as Evidence, Timeline, diagnosis, Run/AI, and Grafana. Do not sprinkle icons beside every status, alert name, or repeated timestamp. Do not underline ordinary body copy or navigation links.

Facts that change the current reading stay in the result: stale samples keep `上次采样 … · 已过期`, and unsupported reports keep `报告证据不足` in the conclusion panel. Those lines are not moved into hover copy.

Use skeletons shaped like real content. Empty states explain what the absence means. Error states say what failed, why when known, and what the operator can safely try next.

Explanation controls are buttons with accessible names such as `查看 RESOLVED 的含义`. Hover-only tooltips are not sufficient. Clicking an explanation must not follow the incident link. The drawer supports keyboard, Escape, and focus return.

## Motion

- 100-150ms for press and color feedback.
- 150-250ms for tabs, selection, route crossfades, and evidence reveal.
- 300ms maximum for mobile drawers and disclosure layout changes.
- Use `cubic-bezier(0.25, 1, 0.5, 1)` by default; never use bounce or elastic easing.
- Animate `transform` and `opacity` only for open, close, and selection.
- No permanent background particles, page-load choreography, or animation of width, height, top, left, or margins.

Under `prefers-reduced-motion`, replace spatial movement with an immediate color and opacity state change.

## Data Visualization

- Do not plot evidence importance as a line chart. It is too easy to misread as a performance trend.
- Do not add a large charting framework.
- Always provide visible labels, units, and non-color distinctions.
- Raw logs, prompts, answers, cookies, tokens, user IDs, and session IDs never appear in visual examples or production responses.

## Performance Budget

- Initial JavaScript: at most 220 KB gzip.
- Initial CSS: at most 60 KB gzip.
- LCP under 2.5s, INP under 200ms, CLS under 0.1 on the acceptance profile.
- Route-split non-critical pages and lazy-load the explanation drawer.
- Avoid WebGL, canvas backgrounds, remote font dependencies, and oversized raster assets.

## Absolute Bans

- Gradient text, decorative glassmorphism, topology wallpaper, hero-metric templates, identical card grids, and nested cards.
- Chat-style AI as the primary incident interface.
- Hover-only actions, invisible focus, ambiguous labels, and decorative motion without state meaning.
- Controls for restart, shell execution, or automatic remediation in V1.1.
- Runtime AI generation of glossary text.
- Treating Incident status, Diagnosis run status, and Service health as interchangeable.
