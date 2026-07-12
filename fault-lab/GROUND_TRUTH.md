# Fault Lab Ground Truth (Stage 1)

`fault-lab` is an isolated Order -> Payment exercise system. It uses its own PostgreSQL database, secret, named volumes, log volume and Compose network. It is not part of the AIDiag business database.

## Boundary

- Only `order-service` publishes a loopback host port (`127.0.0.1:18081` by default).
- `payment-service` has no host port. Fault controls are only callable from its Compose container.
- Fault controls accept one of four fixed names; there are no free-form delay, pool-size, SQL, thread-count or target URL parameters.
- One fault must be enabled at a time during acceptance and disabled before the next case.

## Normal request

```bash
curl -fsS -X POST http://127.0.0.1:18081/api/orders
```

Expected: `201`, `status=COMPLETED`; a single distributed trace contains Order and Payment spans; both service logs include the same `runId` and Prometheus sees both targets as `up`.

## Control commands

Run each command from the release directory. The payment container is intentionally not exposed to the internet.

```bash
docker compose exec -T payment-service sh -c 'token="$$(cat /run/secrets/faultlab.control.token)"; curl -fsS -H "X-Fault-Lab-Control-Token: $$token" http://localhost:8080/internal/faults/ground-truth'
docker compose exec -T payment-service sh -c 'token="$$(cat /run/secrets/faultlab.control.token)"; curl -fsS -H "X-Fault-Lab-Control-Token: $$token" -X POST http://localhost:8080/internal/faults/<fault>/enable'
docker compose exec -T payment-service sh -c 'token="$$(cat /run/secrets/faultlab.control.token)"; curl -fsS -H "X-Fault-Lab-Control-Token: $$token" -X POST http://localhost:8080/internal/faults/<fault>/disable'
```

## Expected evidence by fault

| Fault | Order result | Payment log event | Metric / trace ground truth |
|---|---|---|---|
| `downstream-timeout` | `504 PAYMENT_TIMEOUT` | `faultlab_downstream_delay_started` | `faultlab_fault_active{fault="downstream-timeout"}=1`（仅受控实验）；Order trace contains downstream timeout. |
| `database-slow-query` | `504 PAYMENT_TIMEOUT` | `faultlab_database_slow_query_started` | `faultlab_fault_active{fault="database-slow-query"}=1`（仅受控实验）；Payment trace includes PostgreSQL work. |
| `hikari-exhaustion` | `503 PAYMENT_UNAVAILABLE` | `faultlab_hikari_connection_timeout` | Payment pool wait is fixed at 500ms, shorter than the Order 1s client timeout; `faultlab_hikari_holders_active=4`; `faultlab_fault_injection_total{fault="hikari-exhaustion",state="enabled"}` increased. |
| `thread-pool-exhaustion` | `503 PAYMENT_UNAVAILABLE` | `faultlab_thread_pool_rejected` | `faultlab_thread_pool_active=2`, `faultlab_thread_pool_queue_depth=2`, `faultlab_thread_pool_saturated=1`; the pre-registered `faultlab_thread_pool_rejected_total` is retained as audit evidence. |

After disabling a fault, run a normal request again and verify `201 COMPLETED`. A control state change is logged as `faultlab_fault_state_changed` and increments `faultlab_fault_injection_total` with fixed labels. The full Stage 3 alert-to-report acceptance command is [`scripts/verify-stage3.sh`](../scripts/verify-stage3.sh).
