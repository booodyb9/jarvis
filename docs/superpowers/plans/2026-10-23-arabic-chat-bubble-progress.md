# SDD ledger — plan: docs/superpowers/plans/2026-10-23-arabic-chat-bubble.md

- User approved the design and implementation, then explicitly said "كمل".
- Base: 5577eceada9db15b45bb2056e0a7a96df1dd4ff1, feature/arabic-floating-assistant.
- Isolated branch: codex/arabic-chat-and-bubble. Draft PR #4 targets the supplied feature branch.
- Pre-flight: Tasks 1/2 share ChatConversation.State and observer disposal; Tasks 2/3 share ConversationView and explicit voice destination. Main-thread observer updates, one application context owner.
- Ruling: The managed executor remains pending/offline. Use GitHub connector for an isolated branch and GitHub Actions for actual Gradle commands; local workspace scripts cannot run. No local build is claimed.
- Task 1 RED: run 39977024243 at f3e4cb5dba4616b6621e86293f7d5210dded2b6b, 63 tests / 2 assertion failures. ArabicChatTest.homeStartsWithChatWithoutAccessibilityOrMicrophonePermission and normalChatDoesNotAdvertisePhoneControlTools failed for the expected missing functionality. Existing 61 tests passed.
- Tasks 1/2/3: implementation and behavioral regression tests written; awaiting current CI evidence. No task marked complete yet.
