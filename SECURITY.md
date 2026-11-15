# Security

Canaries run against production with real test accounts, so a flaw here can matter more than in most test tools.
Please report anything you find.

## Reporting a vulnerability

Report it privately through **Security → Report a vulnerability** on this repository's GitHub page. Please don't open
a public issue, pull request or discussion for it.

Include what you found, how to reproduce it and what an attacker could do with it. You'll get a reply within a week.
Once a fix is out, the advisory credits you unless you'd rather it didn't.

## Supported versions

Only the latest release gets security fixes.

## What counts

For example:

- A way to make the service use an account, or assume a role, that its settings don't allow.
- Credentials or tokens reaching logs, metrics or error messages.
- A settings file or canary name that leads to code execution or reading files outside the settings file.
- A dependency with a known vulnerability that the project's code can reach.

The example API in `canary-example` and the `observability` stack are demos for localhost. Weaknesses that exist only
in them, such as Grafana allowing anonymous viewing, are out of scope unless they affect `canary-core` or
`canary-aws`.
