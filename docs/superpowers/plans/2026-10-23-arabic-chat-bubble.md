# Arabic shared chat and bubble implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add shared Arabic home/bubble chat, voice input and spoken replies, and clearer model configuration to the existing working assistant.
**Architecture:** Keep native Kotlin Views, existing AI sessions and screen-control consent. An application-scoped chat controller owns cancellation, bounded local history and model selection; reusable views display it in the activity and overlay. Screen assistance stays behind the existing native approval path.
**Tech Stack:** Kotlin, Android API 26+, existing AppCompat, OkHttp, Robolectric, MockWebServer, TextToSpeech.
**Spec:** docs/superpowers/specs/2026-10-23-arabic-chat-bubble-design.md (user approved implementation on 2026-10-23).

## Global constraints
- Preserve Android 8+ / API 26 and compile/target SDK 35; arm64-v8a.
- Keep ConsentTicket, ScreenProof, one-action approvals, and no coordinate guessing.
- Arabic RTL UI; endpoint/model/key fields LTR; credentials not in chat or errors.
- Persist at most 100 messages, 12000 characters each; send at most 10 completed chat messages and 12000 context characters.
- No screen access for ordinary chat; no microphone permission needed for typing.
- No bundled key, paid service or new backend.

## Review focus
- Cancel or clear while a provider returns: no stale message or speech.
- Activity recreation / bubble reopening: no duplicate request and same history.
- Keyboard, landscape and long Arabic replies: send/stop/approval remain reachable.
- Invalid provider / no Arabic TTS / microphone denied: text remains usable.
- Model changes / fallback disabled: no silent request to another provider.

### Task 1: Chat transport and controller
Files: modify agent/OpenAiCompatibleSession.kt and agent/Llm.kt; create arabic/ChatConversation.kt.
Interfaces: ChatConversation.get(Context), snapshot(), observe(listener): () -> Unit, send(text), cancel(), clear(), reviewDraft(text), consumeDraft(), recordScreen(role,text); state contains messages/busy/status/modelName/draft. One controller uses applicationContext.
- [x] Add black-box regression tests for a no-tools HTTP request and an actual home chat composer.
- [x] Run CI and observe those assertions fail before production changes.
- [x] Omit tools/tool_choice for sessions without tools. Add text-only test without falsely accepting empty replies.
- [x] Implement bounded persistence, serialized request identity, per-request config snapshot and explicit fallback according to Settings.
- [x] Test ordinary chat without accessibility, request payload context, cancellation and late results with MockWebServer.
- [x] Commit.

### Task 2: Home, settings and reusable transcript
Files: replace arabic/ArabicHomeActivity.kt; create arabic/ArabicSettingsActivity.kt and arabic/ConversationView.kt; improve arabic/GlassUi.kt; update AndroidManifest.xml.
Interfaces: ConversationView binds a ChatConversation, allows onTalk/onScreen/onSettings, detaches observers with view lifecycle, renders full message states with play/copy controls.
- [x] Add UI tests for sending and displaying a provider reply without microphone or screen permissions, clear history and recreation.
- [x] Move existing screen consents/service/model setup to ArabicSettingsActivity. Preserve all screen actions and revoke consent behavior.
- [x] Model editor uses provider presets, masked key, existing profile id, cloud HTTPS validation and test result separate from tool capability.
- [x] Main view is weighted transcript + pinned composer; 48dp actions, RTL, scroll-safe, readable contrast.
- [x] Verify Arabic UI tests and screenshots at 360/390/430 widths.
- [x] Commit.

### Task 3: Bubble and voice
Files: modify arabic/AssistantOverlay.kt, arabic/ArabicAssistant.kt and arabic/ArabicVoiceActivity.kt; create arabic/ArabicSpeech.kt.
Interfaces: ArabicSpeech.speak(text), stop(), close(); review voice routes via explicit destination extra. Overlay defaults to chat and keeps native screen approval controls accessible.
- [x] Add tests for shared home/bubble transcript, retained draft after collapse, mode switching and voice destination.
- [x] Add small header, chat/screen tabs, shared ConversationView and persistent screen approval region above screen editor.
- [x] Connect shared history, cancel, voice draft review and open-home actions; closing chat panel only collapses it.
- [x] Queue speech through asynchronous TTS initialization, with clear language/unavailable errors and cancellation that invalidates queued speech.
- [x] Test first speech request, stop during initialization, unsupported Arabic; retain all consent tests.
- [x] Commit.

### Task 4: Verification and delivery
Files: update ARABIC_ASSISTANT.md with usage and validation.
- [x] Run bash gradlew testDebugUnitTest lintDebug assembleDebug.
- [x] Inspect generated screenshots, fix layout regressions and rerun checks affected by fixes.
- [x] Independent final review with the skill-required reviewer; resolve material findings.
- [x] Deliver reviewable branch/draft PR and APK artifact only after successful current checks. Clearly distinguish automated results from physical Honor X7b testing.
