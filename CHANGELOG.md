# Changelog

All notable changes to this project are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Talk over Jarvis to interrupt it, or say "stop". The microphone stays on while it speaks, using the phone's echo cancellation, and Jarvis tells its own voice apart from yours.
- Follow-ups without the wake word: after a reply Jarvis keeps listening for a few seconds and remembers the last few exchanges.
- "Hey Jarvis" during a task stops it and takes the new command.
- Cloud voices: OpenAI, ElevenLabs, Fish Audio and any OpenAI-compatible server, streamed so speech starts quickly, with automatic fallback to Kokoro or the phone's voice.
- Automatic gain for quiet voices (someone across the room).
- An iPhone-style Dynamic Island: expands into a card with the task and buttons, and its border moves with Jarvis's voice.

### Fixed
- Turning Jarvis off now stops the microphone, and Jarvis stays off until turned back on.
- Stopping Jarvis mid-sentence no longer leaves it stuck with the microphone muted.
- The island no longer covers the status bar and camera.

## [0.1.0] - 2026-10-15

First public release.

### Added
- "Hey Jarvis" wake word, offline speech recognition and the Kokoro voice, all on the device.
- An agent that controls the phone through the accessibility service: tap, type, scroll, swipe, open apps, screenshots, and asking the user questions.
- Support for Claude, OpenAI, LM Studio, Ollama, OpenRouter, Gemini, Groq and any OpenAI-compatible endpoint, with a primary model and automatic backups.
- A task island that shows progress and can stop the current task.
- A setup walkthrough, crash reports, and starting at boot.
