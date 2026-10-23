# SDD ledger — plan: docs/superpowers/plans/2026-10-23-arabic-chat-bubble.md

- User approved the design and implementation, then explicitly said "كمل".
- Base: 5577eceada9db15b45bb2056e0a7a96df1dd4ff1, feature/arabic-floating-assistant.
- Isolated branch: codex/arabic-chat-and-bubble. Draft PR #4 targets the supplied feature branch.
- Pre-flight: Tasks 1/2 share ChatConversation.State and observer disposal; Tasks 2/3 share ConversationView and explicit voice destination. Main-thread observer updates, one application context owner.
- Ruling: The managed executor remains pending/offline. Use GitHub connector for an isolated branch and GitHub Actions for actual Gradle commands; local workspace scripts cannot run. No local build is claimed.
- Task 1 RED: run 39977024243 at f3e4cb5dba4616b6621e86293f7d5210dded2b6b, 63 tests / 2 assertion failures. ArabicChatTest.homeStartsWithChatWithoutAccessibilityOrMicrophonePermission and normalChatDoesNotAdvertisePhoneControlTools failed for the expected missing functionality. Existing 61 tests passed.
- Tasks 1/2/3: implementation and behavioral regression tests written; awaiting current CI evidence. No task marked complete yet.

- Initial implementation GREEN: CI 39978953998 passed tests/lint/build at cb7a624e525d3ee5dec17c1e2b55789a465e4597. Voice coverage GREEN: CI 39979306984 passed tests/lint/build at 7e0ef61051e3d8d04018afd204f38f1489b8d227.
- Independent final review found four Important defects: service rotation cancelled chat, New conversation left native screen work active, compact layouts clipped chat actions, and long approval text clipped its buttons.
- Final: Ruling: Re-grade long-reply TTS truncation from Minor to Important — ordinary model replies can exceed Android's synthesis input limit, breaking requested spoken output; include chunking in the same fix pass.
- Review regression RED: CI 39980238166 at dc5a127671cdcc31b5035bf73a2d49a437ede621 ran 79 tests; precisely the seven added review scenarios failed by assertion. Prior 72 passed.
- Fix pass: separate explicit global Stop from screen-service teardown; conversation-clear listeners revoke native tasks; compact measured layouts reserve composer/action space; approval descriptions scroll in a bounded area; speech uses bounded QUEUE_ADD chunks.
- Final: Ruling: Physical Honor IME placement, engine quality and battery behavior remain unverified because no device is attached; deliver automated evidence and require physical testing before release.
- UI verification workaround: focused CI step prints only generated fixture screenshot PNGs, enabling inspection without the unavailable managed filesystem. No credentials or real user screenshots are included.
