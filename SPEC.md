\# Ume — On-Device Personal AI Agent (Android)



\## 1. Identity \& Target Environment

\- \*\*App Name:\*\* Ume

\- \*\*Package Name:\*\* `com.u4mess.ume`

\- \*\*Language / UI:\*\* Kotlin + Jetpack Compose (Material 3)

\- \*\*Theme:\*\* Pure `#000000` OLED black background, `#0D0D0D` elevated surfaces, `#FFFFFF` / `#A0A0A0` typography, subtle `#1F1F1F` borders.

\- \*\*SDK / ABI:\*\* `minSdk = 29`, `targetSdk = 35`, `abiFilters += "arm64-v8a"` only.

\- \*\*Target Device:\*\* Snapdragon 8 Elite, 16 GB RAM (Android 16).



\## 2. Core Architecture

Ume runs a hybrid on-device + cloud architecture:

1\. \*\*Perception Layer (`sensors/`):\*\*

&#x20;  - `NotificationCaptureService` (`NotificationListenerService`): Captures incoming chat, email, and alert notifications (filters out ongoing media/system notifications).

&#x20;  - `ScreenReaderAccessibilityService` (`AccessibilityService`): Reads active screen UI hierarchy into a compact JSON node tree (`id`, `text`, `desc`, `bounds`, `clickable`, `editable`) and executes UI actions (`CLICK`, `TYPE`, `SCROLL`, `BACK`, `HOME`).

&#x20;  - `MediaDocumentIndexer`: Watches new screenshots/documents and extracts text via ML Kit OCR / PDF text extraction.

2\. \*\*Local Vector Memory (`memory/`):\*\*

&#x20;  - \*\*ObjectBox\*\* (`io.objectbox`) with `MemoryEntity` storing `id`, `source`, `packageName`, `content`, `timestamp`, and `@HnswIndex(dimensions = 384) var embedding: FloatArray`.

&#x20;  - `NightlyConsolidator` (`WorkManager`): Runs only when `requiresCharging = true` and `requiresBatteryNotLow = true` to summarize the day's raw events into structured facts using the local LLM.

3\. \*\*Hybrid Brain (`brain/`):\*\*

&#x20;  - \*\*Local Engine (`LocalLlmEngine.kt` + `llama\_jni.cpp`):\*\* Uses `llama.cpp` compiled for `arm64-v8a` (NEON/dotprod) to run local `.gguf` models (e.g., Qwen3.5-2B / LFM3-1.7B Q4\_K\_M) for offline chat, memory Q\&A, embeddings, and nightly consolidation.

&#x20;  - \*\*Cloud Action Engine (`DeepSeekClient.kt`):\*\* Uses the DeepSeek API (`https://api.deepseek.com/v1/chat/completions`) for multi-step cross-app UI automation when `HybridRouter` classifies a request as an external app action.

4\. \*\*Proactive UI (`ui/`):\*\*

&#x20;  - `DynamicIslandService`: Foreground service rendering a `TYPE\_APPLICATION\_OVERLAY` pure-black expandable pill at top-center. Shows live agent steps, proactive reminders, and expands on tap for quick text/voice prompts.

&#x20;  - `MainActivity`: Bottom-nav OLED dashboard with 4 tabs: \*\*Chat\*\*, \*\*Memory\*\*, \*\*Models\*\*, and \*\*Settings\*\* (permissions + DeepSeek API key).



\## 3. Required Android Permissions

\- `SYSTEM\_ALERT\_WINDOW` (Dynamic Island overlay)

\- `BIND\_NOTIFICATION\_LISTENER\_SERVICE` (Passive message/notification capture)

\- `BIND\_ACCESSIBILITY\_SERVICE` (Screen reading \& UI automation)

\- `FOREGROUND\_SERVICE` \& `FOREGROUND\_SERVICE\_SPECIAL\_USE`

\- `INTERNET` (DeepSeek API fallback)

\- `READ\_MEDIA\_IMAGES` / `MANAGE\_EXTERNAL\_STORAGE` (Optional local GGUF \& document access)



\## 4. Coding \& Build Rules

\- Keep code modular, minimal, and free of placeholder stubs.

\- Every milestone MUST compile cleanly with `.\\gradlew.bat assembleDebug` before moving on.

\- Never hardcode API keys; store the DeepSeek API key in `EncryptedSharedPreferences`.

