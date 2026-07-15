# Product

## Register

product

## Users

AIDiag primarily serves a solo Java engineer operating and demonstrating personal cloud services. Secondary audiences are interviewers and technical reviewers who need to understand the platform without learning Grafana query syntax first.

Users usually arrive in one of three states: checking whether production is healthy, investigating an active incident under time pressure, or reviewing a resolved incident to judge whether the diagnosis is supported by evidence.

## Product Purpose

AIDiag turns alerts and privacy-safe metrics, logs, and traces into an auditable incident narrative. The console must help a user answer three questions in order:

1. What currently deserves attention?
2. Which evidence supports or contradicts the diagnosis?
3. What is known, what remains uncertain, and when did the system recover?

Success means a first-time reviewer can follow the alert-to-evidence-to-report chain, while the operator can still use the same interface for real read-only incident investigation when AI-chat is unavailable.

## Brand Personality

Precise, restrained, evidence-led.

The interface should feel calm during failure, confident without overstating certainty, and technically mature without imitating a generic network-operations wall.

## Anti-references

- Topology maps used as decorative home-page wallpaper.
- Repeated same-size monitoring cards with large vanity metrics.
- Cyberpunk blue dashboards, neon particle backgrounds, glassmorphism, or gradient text.
- Chat-first AI interfaces that make model advice appear more authoritative than deterministic evidence.
- Dense raw telemetry dumps without progressive disclosure or privacy boundaries.
- Controls that imply restart, shell execution, or automatic remediation in a read-only product.

## Design Principles

1. **Lead with attention, not inventory.** The home surface explains what changed and what deserves investigation.
2. **Turn activity into a story.** Curate the meaningful incident moments instead of reproducing every event.
3. **Show why a conclusion is trustworthy.** Evidence completeness, contradictions, limitations, and audit history remain visible.
4. **Keep AI subordinate to facts.** Deterministic reports lead; AI advice is clearly labeled, collapsible, and advisory.
5. **Spend motion on causality.** Animation may connect evidence and state changes, but must not decorate idle surfaces.

## Accessibility & Inclusion

- Target WCAG 2.2 AA contrast and keyboard behavior, with AAA contrast where practical for body text.
- Support 200% browser zoom without loss of content or functionality.
- Respect `prefers-reduced-motion`; retain status meaning without spatial animation.
- Never rely on color alone for severity, evidence quality, or incident state.
- Desktop is the primary investigation surface; mobile remains a complete read-only summary with progressively disclosed evidence.
