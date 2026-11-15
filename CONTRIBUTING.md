# Contributing

Thanks for helping. Bug reports, fixes and documentation improvements are all welcome. For a new feature, open an
issue first so we can agree on the approach before you spend time on it. The project aims to stay small.

## Building

You need JDK 17 or newer to start Gradle, and JDK 21 for the build. Gradle downloads a toolchain if you don't
have one.

```bash
./gradlew build
```

This compiles every module and runs all tests. To see the canaries running, see "Try it" in the
[README](README.md).

## Changes

- Add or update tests with every change. Tests call no external services, and each finishes in seconds.
- Match the style of the code around your change.
- Keep pull requests to one change, and describe what it fixes and why.
- Add a line under "Unreleased" in [CHANGELOG.md](CHANGELOG.md) for anything a user would notice.

Report security problems privately, as [SECURITY.md](SECURITY.md) describes, not in an issue.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), as section
5 of the license describes.
