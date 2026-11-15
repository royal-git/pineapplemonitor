# Canary

Scheduled synthetic tests for production, written in Java. A canary is an annotated class. Its schedule,
account and parallelism live in a separate settings file. The framework handles everything else: running,
timeouts, locking, per-step metrics and cleanup.

```java
@Canary("edit-account")
public class EditAccountCanary {

    private AccountClient accounts;
    private String newName;

    @Before
    void createClient(RunContext ctx) {
        accounts = new AccountClient(ctx.account().credentials(EnvCredentials.class).get("API_KEY"));
    }

    @Step(1)
    void edit(RunContext ctx) throws Exception {
        newName = "canary-" + ctx.runId();
        accounts.setDisplayName(newName);
    }

    @Step(2)
    void confirm(RunContext ctx) throws Exception {
        assertThat(accounts.displayName()).isEqualTo(newName);
        ctx.metrics().count("edits.confirmed", 1);
    }

    @Cleanup
    void restoreBaseline() throws Exception {
        if (accounts != null) {
            accounts.setDisplayName("canary-baseline");
        }
    }
}
```

Its settings live in `canary.yml`:

```yaml
canaries:
  edit-account:
    enabled: true
    account: edit-account-test
    interval: 5m
    parallelism: 1
    step-timeout: 10s
```

`enabled` defaults to false and `parallelism` to 1. The other settings are required.

## Try it

You need JDK 17 or newer to start Gradle, and JDK 21 for the build.

```bash
./gradlew :canary-example:bootRun
```

The canary starts calling the example API in the same process, and its metrics appear in the log. To change how it
runs, edit [canary-example/canary.yml](canary-example/canary.yml).

The example API is the only reason the example has a web server. The canary service itself doesn't need one.

## How a run works

- Each execution gets a new instance of the class, and each method runs on a virtual thread with a timeout.
- The order is `@Before`, the `@Step`s by number, `@After`, then `@Cleanup`.
- After a failed step, later steps are reported as `NOT_RUN`. `@After` runs if `@Before` passed. `@Cleanup` always
  runs, and a failed cleanup fails the run.
- A crash skips cleanup, so restore a baseline from your code. Never read it from the account.
- Canaries run on a fixed interval aligned to the epoch. An overdue canary runs once, and rounds never queue up.

## Replacing the parts

Define your own bean to replace any default.

| Part | Interface | Default |
| --- | --- | --- |
| Shared lock | `RunLock` | `InMemoryRunLock`, for one instance |
| Settings | `ConfigStore` | `FileConfigStore` at `canary.config-file`, by default `canary.yml` |
| Credentials | `AccountProvider` | `EnvAccountProvider`, reading `CANARY_ACCOUNT_<REFERENCE>_*` |
| Metrics | `MetricsSink` | OpenTelemetry if `OTEL_EXPORTER_OTLP_ENDPOINT` is set, otherwise the log |

To run on AWS with no stored secrets, add `canary-aws` and set `canary.aws.allowed-role-prefix`, for example to
`arn:aws:iam::123456789012:role/canary-test-`. The account in the settings file is then the role to assume for each
execution. Limit the instance role to the same prefix, because that policy is the real security boundary.

## Metrics

| Instrument | Attributes |
| --- | --- |
| `canary.runs`, `canary.run.duration` | `canary`, `outcome` |
| `canary.run.lag` | `canary` |
| `canary.steps`, `canary.step.duration` | `canary`, `step`, `phase`, `outcome` |
| `canary.skips` | `canary`, `reason` (`LOCK_ERROR`, `ACCOUNT_ERROR`) |
| `canary.heartbeats` | none |
| `canary.custom.<name>` | `canary`, `step`, plus your own |

## See the metrics

[observability](observability) has a local Prometheus and Grafana with a small dashboard: success rate, request
latency p50 and p95, run duration and runs by outcome. The example's `status-check` canary calls an endpoint that is
slow now and then and fails about 10% of the time, so the dashboard has something to show.

```bash
docker compose -f observability/compose.yaml up -d
```

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:9090/api/v1/otlp OTEL_METRIC_EXPORT_INTERVAL=10000 ./gradlew :canary-example:bootRun
```

Then open http://localhost:3000. Prometheus takes OTLP directly, so no collector is needed. It runs with
`created-timestamp-zero-ingestion`, because otherwise `increase()` misses the first run of each new series, such as a
canary's first failure. Both ports listen on localhost only, and Grafana allows anonymous viewing, so this is for
trying things out, not for production.

Metric export uses the standard `OTEL_*` variables. The defaults are OTLP over HTTP and a service name taken from
`spring.application.name`, which Prometheus shows as `job`. Duration histograms use bucket boundaries sized for
seconds.

## Changing settings

Keep the settings file in version control, so that changes get review and history. The service rereads it every 30
seconds, so deploying a new file needs no restart, and runs in progress keep the settings they started with.

- A mistake in the file stops startup, including an unknown setting, a bad value or a role outside the allowed prefix.
- On a reload, the same mistake is logged and the last good settings stay in use.
- Settings for a canary class that no longer exists are ignored, with a warning at startup.
- A canary class with no settings doesn't run.

The file holds account references only. Never put a credential in it.

## Modules

- `canary-core`: the annotations, runner, orchestrator and defaults.
- `canary-aws`: optional role assumption through STS.
- `canary-example`: a runnable service with an example API and two canaries.
- `observability`: a local Prometheus and Grafana for the metrics. Not part of the build.

## License

[Apache License 2.0](LICENSE)
