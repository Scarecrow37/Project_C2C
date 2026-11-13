# Project_C2C (Call to Calendar)

**English** | [한국어](README.ko.md)

An Android app that builds calendar events from call recordings and sends reminders.
It reads each call recording, turns the conversation into text **on the device**, and **Claude** finds agreed appointments, which are added to the calendar automatically.

## How it works

```
Call ends
  └─ Galaxy built-in call recording saves an .m4a (Recordings/Call/)
       └─ WorkManager detects the MediaStore change (RecordingTriggerWorker)
            └─ CallProcessingWorker
                 1. Decode audio → 16kHz mono PCM      (AudioDecoder, MediaCodec)
                 2. Speech recognition, on device      (whisper.cpp, Korean)
                 3. Extract events                     (Claude API, structured output)
                 4. Add to calendar + 30-min reminder  (CalendarContract)
                 5. "Event added" notification with a [Cancel] button
```

- **The audio file never leaves the device.** Only the transcript text is sent to the Claude API.
- Only agreed appointments are added. Vague mentions ("let's meet sometime"), cancelled plans and past events are skipped. Events below the confidence threshold (default 0.6) are skipped too.
- Relative dates such as "tomorrow" or "next Tuesday at 3" are resolved from the call time, which is read from the recording file name.
- Tapping **Cancel** on the notification deletes the event that was added.

## Managing created events

The app's **"생성된 일정" (Created events)** tab collects every event created from calls.

- **List**: upcoming and past events are shown separately. Each one shows its time, location and the other caller.
- **Details**: tap an event to see its time, location and notes, plus the other caller, the quote it was based on, the recording file, and when it was added. The **Edit** and **Delete** buttons are at the bottom of this screen.
- **Swipe to delete**: in the list, swipe an event from right to left to remove it from the calendar. **Undo** on the snackbar restores it.
- **Edit**: change the title, all-day setting, start and end date and time, location, and notes. Moving the start time also moves the end time, so the event keeps its length.
- **Calendar sync**: the device calendar holds the event details. Changes or deletions made in Google Calendar or Samsung Calendar show up in the list right away.
- Tapping the "Event added" notification opens the app on that event's details.

## Improvement feedback (learning from deleted events)

Deleting an auto-created event usually means it was created by mistake. The app records each deletion as evidence for improving extraction.

- **When it records**: deleting in the app's list or detail screen, tapping **Cancel** on the "Event added" notification, or deleting the event in another calendar app.
- **What it records**: the event as Claude first extracted it, its confidence, the quote it was based on, the call summary and time, part of the transcript (up to 6,000 characters), and the event as it was just before deletion (including any user edits).
- **Asks for a reason**: after a delete in the app, a "Why did you delete it?" card appears above the list. Choices: not an event, wrong date/time, wrong details, cancelled later, duplicate, other. The record is kept even if no reason is chosen.
- **Undo**: undoing a delete means the event was not a mistake, so its record is removed too.
- **Used in the next extraction**: up to 10 recent records that count as extraction mistakes are sent to Claude with the next extraction request, as "events this user deleted as wrong." This helps it avoid the same mistake in similar calls.
  - Included: not an event, wrong date/time, wrong details, and deletions with no reason made in the app or from the notification.
  - Excluded: cancelled, duplicate, other, and deletions with no reason made in another calendar app (these may just be cleanup).
  - If the user fixed the time before deleting, the fixed time is included as well.
- **Settings → 6. Improvement feedback**: shows the number of auto-created events, deletions and the mistake rate, with a switch to turn the feature off. **View deletion records** lets you change each record's reason or remove it.
- **Export as JSON**: an analysis file for tuning the prompt and confidence threshold (format `c2c-deletion-feedback/1`). It contains parts of call transcripts, so take care when sharing it.

Records stay in the app's private storage on the device and are not sent to any server. Only the parts attached as examples to the next extraction request (quote, time, deletion reason) go to the Claude API.

## Platform limits

| | |
|---|---|
| **iOS** | Not supported. Third-party apps cannot access call audio. |
| **Android 10+** | Regular apps cannot record calls themselves. This app reads files saved by the **phone's built-in call recording** (Samsung Galaxy, and others). |
| Recommended device | Samsung Galaxy (One UI). Turn on Phone app → Settings → Record calls → **Auto record calls**. |

## Project layout

```
core/   Pure Kotlin/JVM module: builds and tests without the Android SDK
  ClaudeScheduleExtractor   Claude API call (anthropic-java SDK, JSON schema output, server-side fallback)
  ExtractionPrompt          System prompt and output schema
  ExtractionParser          JSON → ScheduleEvent (validation and correction)
  EventFilter               Confidence, past-event and duplicate filtering
  EventDraft / CalendarTime Edit validation, calendar time conversion
  EventFormat               Display strings for event times
  Feedback / FeedbackCodec  Deletion records, prompt example selection, JSON storage and export
  RecordingFileName         Reads contact and call time from Samsung recording file names

app/    Android app (Kotlin, Jetpack Compose, WorkManager)
  recording/  Finds call recordings in MediaStore
  stt/        Audio decoding, whisper.cpp JNI, model download
  calendar/   Adds, reads, edits and deletes calendar events
  data/       Settings, processing history, created-event list, deletion records
  notify/     Notifications and the cancel action
  work/       Background pipeline
  ui/         Created-events tab (list, details, edit, swipe to delete, delete reason), settings tab (with deletion records screen)
  src/main/cpp/  whisper.cpp JNI bridge
```

## Build

Requirements: Android Studio (or Android SDK 35 plus NDK and CMake 3.22.1), JDK 17 or later.

```bash
# 1. Get the whisper.cpp source (once)
./scripts/fetch_whisper.sh

# 2. Build the app (needs sdk.dir in local.properties or ANDROID_HOME)
./gradlew :app:assembleDebug

# Core logic tests (no Android SDK needed)
./gradlew :core:test

# Real Claude API extraction test (only runs when a key is set)
ANTHROPIC_API_KEY=sk-ant-... ./gradlew :core:test
```

When no Android SDK is found, `:app` is left out automatically and only `:core` builds.

## First run

1. **Allow permissions**: audio files, calendar, notifications.
2. **Enter your Claude API key**: get one at console.anthropic.com.
3. **Choose a calendar** to add events to.
4. **Download the speech model**: `ggml-small-q5_1.bin`, about 190 MB. Wi-Fi is recommended.
5. **Turn on automatic processing.** Calls recorded after this point are processed. Existing recordings are not.

Use **"Test with a recording"** to run the whole flow on any audio file, even on an emulator or a non-Samsung phone.

## Settings and design choices

- **Model**: `claude-opus-5-5`, effort `medium`. Server-side fallback is on (`fallbacks: "default"`), so if a safety classifier declines a request, another model retries it automatically.
- **Speech recognition**: whisper.cpp `small` (q5_1 quantized). If Korean accuracy is not good enough, change `WhisperModel.FILE_NAME` to a `medium` model (slower and larger).
- **Long calls**: transcribing takes roughly from tens of seconds up to a few minutes, depending on the device. It runs as a foreground task.

## ⚠️ Security note: API key

The app currently calls the Claude API **directly**. The key is stored in the app's private storage on the device.
This is fine for personal use or a prototype. **If you publish the app to other users, do not put an API key in it.** Add a backend server that calls Claude instead, and have the app call that server.

## Next steps (ideas)

- Confirm-before-adding mode ("Add this event?" notification)
- Mark the other speaker in the transcript (diarization)
- Use a calendar backend server and a user account
- Detect duplicate events (one already in the calendar)
