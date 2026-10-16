# Security policy

## Supported versions

Security fixes go into the latest release of the Brudcast Android SDK. Before 1.0, only the newest
0.x release is supported; upgrade to it to get a fix.

## Reporting a vulnerability

Please do not open a public issue, pull request or discussion for a security problem.

1. **GitHub private advisory (preferred).** Open the repository's **Security** tab and choose
   **Report a vulnerability**. The report stays private between you and the maintainers.
2. **Email.** If you cannot use GitHub, write to [security@brudcast.com](mailto:security@brudcast.com).

Include what you can of:

- the SDK version and the platform version you tested on,
- the steps or a minimal project that reproduces the problem,
- what an attacker gains, and what they need first (another app on the device, a network position,
  access to the dashboard),
- any logs or proof of concept, with real user data removed.

## What happens next

We aim to acknowledge a report within 3 business days and to tell you within 10 business days
whether we accept it and how we plan to fix it. We keep you informed until the fix ships, credit you
in the release notes unless you ask us not to, and ask that you keep the details private until a
fixed release is out.

## Scope notes

- The publishable key (`bpk_...`) ships inside every app and is public by design. It only lets a
  device open a session for its own application, so finding it in an app binary is not a
  vulnerability. Ways to use it beyond that are.
- The identity secret must never be in an app. A way to pass identity verification without the
  user hash computed on the customer's server is in scope.
- Problems in the Brudcast services the SDK talks to are in scope too; report them the same way.
