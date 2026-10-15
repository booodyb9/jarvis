# Security Policy

Jarvis can read your screen and act on your phone, so security matters a lot here.

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report it privately through [GitHub's private vulnerability reporting](../../security/advisories/new) (Security tab → *Report a vulnerability*), or email **judejulius12@gmail.com**.

Please include:
- what the problem is and what an attacker could do with it
- steps to reproduce, and the Android version and phone model
- any ideas you have for a fix

You'll get a reply within a week. Once a fix is released, we'll credit you, unless you'd rather stay anonymous.

## What counts

Examples of things we want to hear about:

- **Prompt injection:** text on a web page, message or notification that makes Jarvis take actions the user didn't ask for, especially ones that get past the safety rules (payments, deletions, security settings, sending messages).
- API keys or other data leaking out of the app's private storage, logs or crash reports.
- Model downloads being tampered with or unpacked outside their folder.
- Other apps being able to trigger Jarvis's service or actions.

## Supported versions

Only the latest release gets security fixes.
