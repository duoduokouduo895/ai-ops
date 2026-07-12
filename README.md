# AIDiag

AIDiag is an evidence-driven diagnosis layer for Java services. The deployed
Stage 3 baseline correlates Prometheus metrics and Loki logs, then produces
auditable incident evidence and diagnosis reports. Tempo is provisioned but
Trace evidence collection remains a planned P1 enhancement.

The implementation baseline is documented in
[AIDiag-V1-实施方案.md](./AIDiag-V1-实施方案.md). Cloud operations that must be
performed manually are documented in
[云端实施操作手册.md](./云端实施操作手册.md).

## Current implementation status

Stages 0, 1, and 2 are deployed and accepted. Stage 2 provides reliable
Alertmanager ingestion, Incident aggregation, and durable task handoff:

- Spring Boot 4.1.0 / Java 21 application skeleton;
- Maven Wrapper 3.9.16, Enforcer rules, and CycloneDX SBOM generation;
- Flyway V1 schema for incidents, alert events, diagnosis runs, evidence, reports,
  and runbooks;
- PostgreSQL 18-compatible persistence mounted at /var/lib/postgresql;
- PostgreSQL initialization that installs pgvector and removes server-level
  superuser privileges from the long-running application database owner;
- immutable infrastructure image references;
- resource-limited Compose topology for PostgreSQL, Prometheus, Alertmanager,
  Alloy, Loki, Tempo, Grafana, and the AIDiag backend;
- loopback-only host bindings for AIDiag and Grafana;
- file-based Compose secrets;
- 7-day Prometheus retention and 72-hour Loki/Tempo retention.
- Alertmanager Basic Auth verification using a file-based Secret shared only by
  Alertmanager and the backend;
- database-enforced Incident aggregation, alert-event idempotency and read-only
  Incident APIs;
- PostgreSQL `FOR UPDATE SKIP LOCKED` task claims, lease fencing tokens,
  heartbeats and retry attempts that stop at `EVIDENCE_READY` until Stage 3
  implements analysis and reports.

Stage 3 is deployed and accepted. All four controlled Fault Lab scenarios pass
firing, deterministic `SUPPORTED` report generation, request recovery, and
Incident resolution through [`scripts/verify-stage3.sh`](./scripts/verify-stage3.sh).
Its rules use fixed Prometheus/Loki templates; `faultlab_fault_active` is an
isolated Fault Lab control-state metric and must not be reused for production health.

Stage 4 supplies a disabled-by-default, authenticated operator-triggered Spring AI preview
boundary. It persists advisory attempts separately, validates that model output
references only supplied evidence and controlled runbook actions, and never
performs remediation or changes the deterministic primary root cause. No model
provider starter or model/operator secret is included yet. The reviewed target is the
official DeepSeek API with `deepseek-v4-flash`; the first provider integration will use
OpenAI compatibility, explicitly disable thinking mode, and retain local output validation.
Mounting dedicated provider/operator credentials and passing real-provider contract tests
remain explicit deployment gates.
The provider-neutral Stage 4 foundation is deployed with `AIDIAG_STAGE4_ENABLED=false`;
real-provider acceptance is intentionally still pending.

## Build

Local builds require Java 21:

~~~bash
./mvnw verify
~~~

The cloud host does not need a host JDK. The Docker build uses a pinned Java 21
builder and runtime:

~~~bash
docker compose --env-file versions.env --env-file .env.cloud build aidiag-backend
~~~

## Cloud configuration

Create non-secret configuration from the template:

~~~bash
cp .env.cloud.example .env.cloud
chmod 600 .env.cloud
~~~

Create these cloud-only files with directory mode 700 and file mode 600:

~~~text
.secrets/postgres_password
.secrets/alertmanager_webhook_password
.secrets/aidiag_alertmanager_webhook_password
.secrets/grafana_admin_password
~~~

Set `.secrets` to `700`; set `alertmanager_webhook_password` and
`grafana_admin_password` to `600`. Alertmanager runs as UID/GID `65534`, so its
webhook secret must be owned by `65534:65534` with mode `600`. The backend runs
as UID/GID `10001`; `postgres_password` and the backend-only
`aidiag_alertmanager_webhook_password` must be owned by `root:10001` with mode
`640`. This keeps each file secret readable only by its intended container.

Never commit or share the contents of .env.cloud or .secrets.

## Validation boundaries

The local workstation currently has Java 17 and no Docker. Maven dependency
resolution and static YAML/JSON/XML validation can run locally, while Java 21
compilation, Flyway execution, container configuration validation, and telemetry
smoke tests must run inside the pinned cloud Docker build.
