# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `@Canary` classes with `@Before`, `@Step`, `@After` and `@Cleanup` methods, each run on a virtual thread with a
  timeout.
- Settings in a YAML file apart from the code, reread every 30 seconds. A mistake stops startup and, on a reload,
  keeps the last good settings.
- Fixed intervals aligned to the epoch, with a lease-based `RunLock` so that only one instance runs each round.
- Credentials from environment variables, or from an assumed AWS role in `canary-aws`.
- Run, step, skip and custom metrics, exported over OTLP or written to the log.
- An example service with two canaries, and a local Prometheus and Grafana stack with a dashboard.
