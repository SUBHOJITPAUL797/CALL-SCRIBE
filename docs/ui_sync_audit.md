# Call Scribe Code Quality & Bug Audit Report

**Scope of Audit:**
1. `CallViewModel.kt` (`com.example.ui`)
2. `CallSyncWorker.kt` (`com.example.sync`)
3. `CallMetadataParser.kt` (`com.example.data`)
4. `CallerProfile.kt` (`com.example.data`)

---

## 1. `app/src/main/java/com/example/ui/CallViewModel.kt`

### [BUG-VM-01] Critical — Broken Caller Profile Navigation Due to Key Mismatch
- **Line Numbers:** 603–611
- **Severity:** Critical
- **Issue Description:**
  In `openCallerProfileForRecording`:
  ```kotlin
  val meta = CallMetadataParser.parse(recording.title)
  val digits = meta.cleanTitle.filter { it.isDigit() }
  val key = if (digits.length >= 6 && meta.cleanTitle.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
      digits
  } else {
      meta.cleanTitle.trim().lowercase(java.util.Locale.ROOT)
  }
  openCallerProfile(key)
  ```
  However, in `CallerProfileBuilder.kt` (lines 48–56), the key is computed differently:
  1. It normalizes phone numbers with `phoneDigits.takeLast(10)`.
  2. It checks `extractedPhone` from `meta.contactOrNumber` first.
  If a phone number has 11 digits (e.g. `+14155552671`), `CallerProfileBuilder` assigns key `"4155552671"`, but `openCallerProfileForRecording` assigns key `"14155552671"`.
  Additionally, if the filename has a contact name and a phone number, `CallerProfileBuilder` keys it on the phone number, whereas `openCallerProfileForRecording` keys it on the lowercase name.
  Because the keys do not match, `selectedCallerProfile` (`profiles.find { it.callerKey == key }`) evaluates to `null`. Clicking a recording card never opens the caller's profile.
- **Recommended Fix:**
  Extract key computation into a single canonical function (e.g., `CallerProfileBuilder.computeCallerKey(recording.title)`) and use it in both `CallerProfileBuilder` and `CallViewModel.openCallerProfileForRecording`.

---

### [BUG-VM-02] Critical — Quota Failures (429) Swallowed During Sync; Dummy Analyses Permanently Saved
- **Line Numbers:** 854–883, 1038–1196 (specifically 1156–1180)
- **Severity:** Critical
- **Issue Description:**
  In `processAudioFile`:
  ```kotlin
  if (transcription.isBlank()) {
      if (existingId != null && currentEngine != PreferredEngine.ON_DEVICE) {
          return Result.failure(...)
      }
      val (localTrans, localSum) = LocalAnalysisEngine.analyzeLocally("", fileName)
      transcription = localTrans
      summary = localSum
  }
  ...
  repository.insert(recording)
  Result.success(Unit)
  ```
  For newly discovered files during sync, `existingId == null`. If Gemini returns an HTTP 429 Quota Exceeded error or network failure, `processAudioFile` does **not** return `Result.failure`. Instead, it silently falls back to `LocalAnalysisEngine.analyzeLocally("", fileName)`, writes the dummy placeholder into Room, and returns `Result.success(Unit)`.
  Consequences:
  1. In `startSyncWithLimit` (lines 863–877), `processResult.isSuccess` is always true.
  2. `err is ApiQuotaExceededException` is never reached; the 15-second rate limit cooldown never triggers.
  3. Every subsequent file in the batch also hits 429 and is marked as "analyzed" with blank local text.
  4. Subsequent syncs skip these files forever as "already analyzed", corrupting user data permanently.
- **Recommended Fix:**
  In `processAudioFile`, return `Result.failure(lastEngineError)` whenever cloud engines fail during sync if the user's preferred engine is not `ON_DEVICE`, and do not insert placeholder recordings as successful analyses.

---

### [BUG-VM-03] Critical — Missing Check for `"Audio Transcription Required"` Treats Failed Audio as Real Transcripts
- **Line Numbers:** 292–296, 339–343, 680–684, 814–817
- **Severity:** Critical
- **Issue Description:**
  Across `CallViewModel.kt`, the code checks whether a recording has a real transcript using:
  ```kotlin
  val hasRealTranscript = existing != null &&
      existing.decodedTranscription.isNotBlank() &&
      !existing.decodedTranscription.contains("Transcription requires") &&
      !existing.decodedTranscription.contains("On-Device Speech Analysis")
  ```
  However, `LocalAnalysisEngine.analyzeLocally` outputs:
  `"⚠️ Audio Transcription Required"` (line 55 of `LocalAnalysisEngine.kt`).
  It does **not** contain `"Transcription requires"` (which only appears in the summary, not the transcription) or `"On-Device Speech Analysis"`.
  Therefore:
  1. In `onFolderSelected`, `pendingCount` excludes these files.
  2. In `startSyncWithLimit`, failed files are counted as `alreadyAnalyzedCount` and skipped.
  3. In `openChat` and `sendChatMessage`, the dummy text is treated as a real transcript and sent to the LLM.
- **Recommended Fix:**
  Add `!existing.decodedTranscription.contains("Audio Transcription Required")` and `!existing.decodedTranscription.contains("AI Analysis Not Available")`. Better yet, introduce an explicit status column in Room: `enum class AnalysisStatus { PENDING, COMPLETED, FAILED }`.

---

### [BUG-VM-04] High — `onFolderSelected` and `reanalyzeRecording` Bypass `SyncLock.mutex`
- **Line Numbers:** 650–697, 1206–1225
- **Severity:** High
- **Issue Description:**
  `SyncLock.mutex` was implemented to eliminate race conditions between foreground scanning, WorkManager background sync, and `onResume` checks.
  However:
  - `onFolderSelected` scans the directory tree and queries Room without acquiring `SyncLock.mutex`.
  - `reanalyzeRecording` executes `processAudioFile` and inserts into Room without acquiring `SyncLock.mutex`.
  If background `CallSyncWorker` runs at the same time, simultaneous Room writes and duplicate placeholder creations occur.
- **Recommended Fix:**
  Wrap operations in `SyncLock.mutex.withLock { ... }`.

---

### [BUG-VM-05] High — Sync Drops Audio Timestamps on New Files
- **Line Numbers:** 854–861, 1170–1178, 1184–1191
- **Severity:** High
- **Issue Description:**
  In `startSyncWithLimit`, `fileInfo.lastModified` is never passed to `processAudioFile`.
  In `processAudioFile`:
  ```kotlin
  val existingTimestamp = existingId?.let { withContext(Dispatchers.IO) { repository.getById(it)?.timestamp } }
  val recording = Recording(
      ...
      timestamp = existingTimestamp ?: System.currentTimeMillis(),
      ...
  )
  ```
  For newly synced files (`existingId == null`), `existingTimestamp` is `null`, so `timestamp` defaults to `System.currentTimeMillis()`. All historical call recordings in the folder are stamped with the current time of sync, ruining chronological sorting, timeline view, and caller profile statistics.
  In the catch block (line 1186), `timestamp` is omitted entirely and defaults to `System.currentTimeMillis()`.
- **Recommended Fix:**
  Pass `fileInfo.lastModified` into `processAudioFile` and use `fileLastModified.takeIf { it > 0 } ?: System.currentTimeMillis()`.

---

### [BUG-VM-06] High — Activity `Context` Leak Across Configuration Changes
- **Line Numbers:** 474, 532, 638, 701, 771, 1199, 1276
- **Severity:** High
- **Issue Description:**
  Multiple ViewModel public functions accept a `Context` argument. When called from Composables in `MainActivity.kt`, an Activity `Context` is passed in.
  In `downloadAndInstallUpdate` (lines 541–565), the coroutine captures `context` across long-running network downloads. Rotating the screen during APK download leaks the entire destroyed Activity.
- **Recommended Fix:**
  Convert `CallViewModel` to `AndroidViewModel(application: Application)` or extract `context.applicationContext` immediately at the entry point without retaining Activity references in coroutine closures.

---

### [BUG-VM-07] High — Unhandled Exceptions in `sendChatMessage` Leave UI Permanently Frozen
- **Line Numbers:** 337, 357–470
- **Severity:** High
- **Issue Description:**
  `sendChatMessage` sets `isChatLoading.value = true`. The coroutine launched on `viewModelScope` lacks a `try / finally` block. If `geminiRepository.chatWithCall`, `cloudflareRepository.chatWithCall`, or JSON parsing throws an uncaught exception:
  1. `isChatLoading.value = false` is never reached, leaving the chat UI disabled with a permanent spinner.
  2. If `closeChat()` is invoked while a request is in flight, the previous coroutine continues running and appends its answer to the wrong recording's chat or closed chat state.
- **Recommended Fix:**
  Store a `chatJob: Job?` handle. Cancel `chatJob` in `closeChat()`. Enclose the coroutine body in `try { ... } finally { isChatLoading.value = false }`.

---

### [BUG-VM-08] High — Coroutine Cancellation Anti-pattern and Mutex Deadlock Risk in `finally`
- **Line Numbers:** 898–901
- **Severity:** High
- **Issue Description:**
  In `startSyncWithLimit`:
  ```kotlin
  SyncLock.mutex.withLock {
      try {
          ...
      } catch (t: Throwable) {
          ...
      } finally {
          kotlinx.coroutines.delay(2500)
          isSyncing.value = false
      }
  }
  ```
  1. `delay(2500)` is invoked inside `finally` while still holding `SyncLock.mutex`. Any other coroutine waiting on `SyncLock.mutex` is blocked for 2.5 seconds.
  2. If `syncJob` is cancelled (via `cancelSync()`), calling `delay(2500)` inside `finally` immediately throws `CancellationException` because the coroutine scope is cancelled. Thus, `isSyncing.value = false` is never reached.
- **Recommended Fix:**
  Release `isSyncing.value = false` outside of the mutex lock, and wrap any post-cancellation delays in `withContext(NonCancellable)` if delay is strictly necessary.

---

### [BUG-VM-09] High — Large Audio Allocation (Heap Spike / OOM) in `readAudioBytes` and Base64 Encoding
- **Line Numbers:** 992–1013, 1088
- **Severity:** High
- **Issue Description:**
  `readAudioBytes` reads up to 24 MB into a `ByteArrayOutputStream` using 16 KB chunks. `ByteArrayOutputStream` doubles its internal buffer, allocating 32 MB + 24 MB on `toByteArray()`. Line 1088 immediately converts this into a 32 MB Base64 string (which allocates 32–64 MB for character buffers). Total transient heap allocation exceeds 100–120 MB.
  On low-end Android devices (192 MB heap limit), this triggers `java.lang.OutOfMemoryError`. `catch (_: Exception)` does not catch `Error`, causing an unhandled application crash.
- **Recommended Fix:**
  Catch `Throwable` (or `OutOfMemoryError`), avoid doubling byte arrays, and stream audio data directly into OkHttp `RequestBody`.

---

### [BUG-VM-10] High — Calendar Intent Missing Required `EXTRA_EVENT_BEGIN_TIME`
- **Line Numbers:** 1289–1295
- **Severity:** High
- **Issue Description:**
  In `syncToCalendar`:
  ```kotlin
  val intent = Intent(Intent.ACTION_INSERT).apply {
      data = CalendarContract.Events.CONTENT_URI
      putExtra(CalendarContract.Events.TITLE, "Call: $cleanTitle")
      putExtra(CalendarContract.Events.DESCRIPTION, description)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
  ```
  The Android Calendar Provider requires `CalendarContract.EXTRA_EVENT_BEGIN_TIME`. Without it, third-party calendar applications (e.g. Google Calendar, Samsung Calendar) either crash, reject the intent, or place the event at Unix epoch (January 1, 1970).
- **Recommended Fix:**
  Add:
  ```kotlin
  putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, recording.timestamp)
  putExtra(CalendarContract.EXTRA_EVENT_END_TIME, recording.timestamp + 30 * 60 * 1000)
  ```

---

### [BUG-VM-11] Medium — State Flow Encapsulation Violation
- **Line Numbers:** 71–109
- **Severity:** Medium
- **Issue Description:**
  More than 20 state variables (`searchQuery`, `isSyncing`, `syncStatus`, `activeChatRecording`, `chatMessages`, etc.) are declared as public `val <prop> = MutableStateFlow(...)`. External callers and Composables can directly mutate ViewModel internal state.
- **Recommended Fix:**
  Make all `MutableStateFlow` fields private (`private val _isSyncing = MutableStateFlow(false)`) and expose public read-only views (`val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()`).

---

### [BUG-VM-12] Medium — StateFlow Deduplication of Transient UI Events (`updateStatusMessage`)
- **Line Numbers:** 96, 529
- **Severity:** Medium
- **Issue Description:**
  `updateStatusMessage` is modeled as `MutableStateFlow<String?>(null)`. StateFlow deduplicates consecutive identical values. If the same error or status message is emitted twice consecutively, the second emission is dropped, and no Snackbar or Toast is shown.
- **Recommended Fix:**
  Use `Channel<String>(Channel.BUFFERED)` or `MutableSharedFlow<String>(extraBufferCapacity = 1)`.

---

### [BUG-VM-13] Medium — Missing Debounce on Search Query
- **Line Numbers:** 569–583
- **Severity:** Medium
- **Issue Description:**
  Every keystroke in `searchQuery` immediately triggers `combine(repository.allRecordings, searchQuery)`, causing full-list decryption and regex/substring filtering across all Room records for every character typed.
- **Recommended Fix:**
  Add `.debounce(300)` to `searchQuery` before combining with `repository.allRecordings`.

---

### [BUG-VM-14] Medium — Deleted Recordings Re-imported on Next Scan / Resume
- **Line Numbers:** 722, 1264–1274
- **Severity:** Medium
- **Issue Description:**
  `deleteRecording` deletes the database row from Room. The physical file remains in the SAF folder. The next time `checkForNewRecordingsOnResume` or `CallSyncWorker` runs, `repository.getByUri(uri)` returns `null`, and the deleted file is re-imported as a new recording and re-analyzed.
- **Recommended Fix:**
  Implement a tombstone/deleted URI table in Room (`deleted_recordings`) or add a `isDeleted: Boolean` soft-delete flag on `Recording`.

---

### [BUG-VM-15] Medium — Case-Sensitive Set Mismatch in `toggleCallerAutoAnalyze`
- **Line Numbers:** 618–624
- **Severity:** Medium
- **Issue Description:**
  `toggleCallerAutoAnalyze` checks presence with `targets.any { it.equals(callerTarget, ignoreCase = true) }`, but `removeAutoAnalyzeTarget` calls `current.remove(target.trim())`, which is case-sensitive. If the target casing differs, the item is never removed from the set.
- **Recommended Fix:**
  Use `current.removeAll { it.equals(target.trim(), ignoreCase = true) }` in `removeAutoAnalyzeTarget`.

---

### [BUG-VM-16] Low — Synchronous SharedPreferences Read on Main Thread During Init
- **Line Numbers:** 86–90, 117–120
- **Severity:** Low
- **Issue Description:**
  Properties `autoAnalyzeMode`, `autoAnalyzeTargets`, `autoSyncEnabled`, `preferredEngine`, etc., read SharedPreferences synchronously during ViewModel initialization on the Main thread.
- **Recommended Fix:**
  Migrate to Jetpack DataStore or initialize with default values and load asynchronously.

---

### [BUG-VM-17] Low — Leaked `OkHttpClient` Instances in `testNvidiaApiKey`
- **Line Numbers:** 213, 222
- **Severity:** Low
- **Issue Description:**
  `testNvidiaApiKey` instantiates `NvidiaRepository(apiKeyProvider = { apiKey })`, which instantiates a new `OkHttpClient` with its own connection and thread pools on every test click.
- **Recommended Fix:**
  Reuse the injected `nvidiaRepository` or share a singleton `OkHttpClient`.

---

## 2. `app/src/main/java/com/example/sync/CallSyncWorker.kt`

### [BUG-SW-01] Critical — WorkManager Periodic Work Has Zero Constraints (Runs Offline)
- **Line Numbers:** 7, 11, 415–424
- **Severity:** Critical
- **Issue Description:**
  `Constraints` and `NetworkType` are imported at lines 7 and 11, but `schedulePeriodicSync` builds the `PeriodicWorkRequest` with **no constraints**:
  ```kotlin
  fun schedulePeriodicSync(context: Context) {
      val workRequest = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
          .build()
      WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(...)
  }
  ```
  The worker triggers every 15 minutes even with no internet connection or in airplane mode. When offline, it attempts cloud API calls, fails, falls back to `LocalAnalysisEngine.analyzeLocally("", fileName)`, and marks new calls as analyzed with blank data, posting a notification that analysis succeeded.
- **Recommended Fix:**
  Add network constraints:
  ```kotlin
  val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()
  val workRequest = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
      .setConstraints(constraints)
      .build()
  ```

---

### [BUG-SW-02] Critical — Worker Catches `CancellationException` and Swallows WorkManager Cancellation
- **Line Numbers:** 173–175
- **Severity:** Critical
- **Issue Description:**
  ```kotlin
  } catch (_: SecurityException) {
      Result.failure()
  } catch (t: Throwable) {
      Result.retry()
  }
  ```
  When WorkManager cancels a `CoroutineWorker` (e.g. constraints lost, app killed, or 10-minute timeout), it cancels the coroutine with a `CancellationException`. Because `catch (t: Throwable)` catches `CancellationException`, the worker treats cancellation as a failure and returns `Result.retry()`. This causes WorkManager to immediately re-run the cancelled task.
- **Recommended Fix:**
  Re-throw `CancellationException`:
  ```kotlin
  } catch (e: CancellationException) {
      throw e
  } catch (t: Throwable) {
      Result.retry()
  }
  ```

---

### [BUG-SW-03] High — Missing Cooperative Cancellation Check (`isStopped`) in Loop
- **Line Numbers:** 77–167
- **Severity:** High
- **Issue Description:**
  The `for (fileInfo in audioFiles)` loop processes each file sequentially with multi-second API calls and delays, but never checks `isStopped` or `coroutineContext.isActive`. If WorkManager requests the worker to stop, it continues executing through the rest of the audio files until the OS forcefully kills the process.
- **Recommended Fix:**
  Add `if (isStopped || !coroutineContext.isActive) break` at the start of the loop.

---

### [BUG-SW-04] High — Interrupted Background Sync Leaves Orphaned Placeholders That Are Never Retried
- **Line Numbers:** 82–97
- **Severity:** High
- **Issue Description:**
  `CallSyncWorker` inserts a placeholder into Room:
  ```kotlin
  val placeholder = Recording(id = 0, title = fileInfo.name, contentEncrypted = SimpleEncryption.encrypt(""), ...)
  val insertedId = repository.insert(placeholder).toInt()
  ```
  If the worker is terminated (e.g. timeout or network loss) before `repository.insert(updatedRecording)` finishes, the placeholder remains in Room. On the next execution:
  ```kotlin
  val existing = repository.getByUri(fileUriStr)
  if (existing != null) continue
  ```
  Because `existing != null`, it skips the file permanently. The call remains stuck on "Pending AI Analysis" forever.
- **Recommended Fix:**
  Allow processing if `existing == null || existing.decodedTranscription.isBlank()`.

---

### [BUG-SW-05] High — `scanDirectoryRecursively` Swallows `SecurityException`, Preventing `Result.failure()`
- **Line Numbers:** 170–172, 255–256
- **Severity:** High
- **Issue Description:**
  In `CallSyncWorker`, `scanDirectoryRecursively` has an internal `catch (_: Exception) {}` block that swallows any `SecurityException` thrown when SAF URI permission is revoked. Consequently, `catch (_: SecurityException) { Result.failure() }` at line 170 is unreachable. The worker reports `Result.success()` despite lacking storage permissions.
- **Recommended Fix:**
  Allow `SecurityException` to bubble up from `scanDirectoryRecursively`.

---

### [BUG-SW-06] Medium — `SyncLock.mutex` Contention Under WorkManager 10-Minute Limit
- **Line Numbers:** 36
- **Severity:** Medium
- **Issue Description:**
  `SyncLock.mutex.withLock` suspends the worker if the foreground UI is currently syncing. WorkManager enforces an absolute 10-minute execution window on background workers. If foreground sync holds the lock, the worker exhausts its execution window while idle and is killed by the system.
- **Recommended Fix:**
  Use `if (!SyncLock.mutex.tryLock()) return@withContext Result.retry()`.

---

### [BUG-SW-07] Medium — Notification Flood on Initial Folder Scan
- **Line Numbers:** 149–166
- **Severity:** Medium
- **Issue Description:**
  If a folder containing 25 new calls is scanned, `notifyNewCallDetected` is called 25 times inside the loop, posting 25 individual notifications.
- **Recommended Fix:**
  Count newly detected files and post a single bundled summary notification when `newCount > 1`.

---

### [BUG-SW-08] Low — `ExistingPeriodicWorkPolicy.KEEP` Ignores Work Configuration Updates
- **Line Numbers:** 421
- **Severity:** Low
- **Issue Description:**
  `ExistingPeriodicWorkPolicy.KEEP` instructs WorkManager to retain the existing schedule, ignoring any new constraints or interval adjustments added in future app updates.
- **Recommended Fix:**
  Use `ExistingPeriodicWorkPolicy.UPDATE`.

---

## 3. `app/src/main/java/com/example/data/CallMetadataParser.kt`

### [BUG-MP-01] Critical — `directionRegex` Mutilates Names Containing "in" or "out" (Kevin, Martin, Robin, Austin, Justin)
- **Line Numbers:** 27, 40–42, 63
- **Severity:** Critical
- **Issue Description:**
  `directionRegex` is defined as:
  ```kotlin
  private val directionRegex = Regex("""(?i)[\s_\-]*(incoming|outgoing|in|out)[\s_\-]*""")
  ```
  Because `in` and `out` lack word boundaries (`\b`), any name ending in `in` followed by `_` or space is stripped:
  - `Kevin_Smith.mp3` becomes `"Kev Smith"`.
  - `Martin_Luther.mp3` becomes `"Mart Luther"`.
  - `Robin_Hood.mp3` becomes `"Rob Hood"`.
  - `Austin_Texas.mp3` becomes `"Aust Texas"`.
  - `Justin_Case.mp3` becomes `"Just Case"`.
  - `Shout_out.mp3` becomes `"Sh"`.
  Furthermore, line 40 checks:
  ```kotlin
  lower.contains("incoming") || lower.contains("in_") || lower.contains("_in") -> CallDirection.INCOMING
  ```
  `kevin_outgoing.mp3` contains `"in_"`, so an outgoing call to Kevin is classified as `INCOMING`.
- **Recommended Fix:**
  Enforce word boundaries and required separators:
  ```kotlin
  private val directionRegex = Regex("""(?i)(?<=^|[\s_\-])(incoming|outgoing|in|out)(?=[\s_\-]|$)""")
  ```
  In `parse()`, use regex word boundaries rather than `contains("in_")`.

---

### [BUG-MP-02] Critical — `prefixRegex` Chops Names Starting with "Call", "Rec", "Voice", "Audio"
- **Line Numbers:** 24, 62
- **Severity:** Critical
- **Issue Description:**
  ```kotlin
  private val prefixRegex = Regex("""(?i)^(call[_\s\-]*recording|call|recording|rec|audio|voice)[\s_\-]*""")
  ```
  Because `[\s_\-]*` matches zero delimiters, any contact name starting with `"call"` (Callum, Callie), `"rec"` (Recep), `"voice"`, or `"audio"` matches the prefix:
  - `Callum_Brown.mp3` becomes `"um Brown"`.
  - `Recep_Erdogan.mp3` becomes `"ep Erdogan"`.
- **Recommended Fix:**
  Require at least one delimiter or end-of-string:
  ```kotlin
  private val prefixRegex = Regex("""(?i)^(call[_\s\-]*recording|call|recording|rec|audio|voice)(?:[\s_\-]+|$)""")
  ```

---

### [BUG-MP-03] High — `dateTimeRegex` Matches and Strips 8-Digit Phone Numbers Starting with 19 or 20
- **Line Numbers:** 30, 47, 64
- **Severity:** High
- **Issue Description:**
  ```kotlin
  private val dateTimeRegex = Regex("""(?<=[^0-9]|^)(?:\d{6,8}[_\-]\d{4,6}|(?:19|20)\d{6})(?=[^0-9]|$)""")
  ```
  The alternative `(?:19|20)\d{6}` matches any 8-digit number starting with 19 or 20 (e.g. landline numbers in Pune/Egypt starting with 20, or US numbers like `20123456`). Because date-time stripping happens *before* phone number extraction, the phone number is stripped from the filename, leaving `phoneNumber = null`.
- **Recommended Fix:**
  Only match calendar dates if they follow strict date formats or are accompanied by a time stamp:
  ```kotlin
  private val dateTimeRegex = Regex("""(?<=[^0-9]|^)(?:\d{6,8}[_\-]\d{4,6}|(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01]))(?=[^0-9]|$)""")
  ```

---

### [BUG-MP-04] High — ISO Format Dates (`YYYY-MM-DD`) Parsed as Phone Numbers
- **Line Numbers:** 21, 30, 54
- **Severity:** High
- **Issue Description:**
  `dateTimeRegex` does not match ISO-8601 formatted dates like `2024-03-07` because `2024` is only 4 digits and contains hyphens.
  `phoneRegex` (`\+?[0-9][0-9\s\-()]{5,18}[0-9]`) then matches `2024-03-07` as a phone number. The date becomes the contact phone number in `CallerProfile`.
- **Recommended Fix:**
  Add ISO date matching to `dateTimeRegex`:
  ```kotlin
  private val isoDateRegex = Regex("""(?<=[^0-9]|^)\d{4}[_\-.]\d{2}[_\-.]\d{2}(?=[^0-9]|$)""")
  ```
  Strip ISO dates before executing `phoneRegex`.

---

### [BUG-MP-05] Medium — `isUnknownNumber` Identifies Track / Sequential Numbers as Phone Numbers
- **Line Numbers:** 128–130
- **Severity:** Medium
- **Issue Description:**
  ```kotlin
  if (withoutTime.isNotBlank() && withoutTime.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
      return true
  }
  ```
  If a recording is named `01.mp3`, `1.mp3`, or `Track_2.mp3`, `withoutTime` is `"01"` or `"1"`. It satisfies `.all { it.isDigit() }` and is marked as an unknown phone number for auto-analysis.
- **Recommended Fix:**
  Enforce a minimum digit count:
  ```kotlin
  val digits = withoutTime.filter { it.isDigit() }
  if (digits.length >= 6 && withoutTime.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }) {
      return true
  }
  ```

---

### [BUG-MP-06] Medium — False-Positive Substring Matching in `matchesAutoAnalyzeRule`
- **Line Numbers:** 156
- **Severity:** Medium
- **Issue Description:**
  `cleanNormalized.contains(targetNormalized)` performs unanchored substring matching. If target contact is `"Dan"`, it matches `"Daniel"`, `"Jordan"`, and `"guidance"`. If target is `"Rob"`, it matches `"Robin"` and `"Problem"`.
- **Recommended Fix:**
  Tokenize by whitespace and test for whole-word matches:
  ```kotlin
  val words = cleanNormalized.split("\\s+".toRegex())
  words.contains(targetNormalized) || cleanNormalized == targetNormalized
  ```

---

### [BUG-MP-07] Medium — Regex Recompiled on Every Line in `CommitmentExtractor`
- **Line Numbers:** 197
- **Severity:** Medium
- **Issue Description:**
  `replace(Regex("""^\d+\.\s*"""), "")` allocates and compiles a new `Regex` instance on every line of every summary.
- **Recommended Fix:**
  Declare `private val numberedBulletRegex = Regex("""^\d+\.\s*""")` at object level.

---

### [BUG-MP-08] Medium — Bold Items Prematurely Cut Off in `CommitmentExtractor`
- **Line Numbers:** 168, 184
- **Severity:** Medium
- **Issue Description:**
  `nextHeaderRegex = Regex("""(?im)^[#*]{2,}\s+[^\n]+""")` matches lines starting with `**`. If the AI formats an action item as `**Submit report** by 5 PM`, `CommitmentExtractor` treats it as the next section header and truncates all remaining action items.
- **Recommended Fix:**
  Only treat Markdown headers with `#` as section boundaries:
  ```kotlin
  private val nextHeaderRegex = Regex("""(?im)^#{1,6}\s+[^\n]+""")
  ```

---

## 4. `app/src/main/java/com/example/data/CallerProfile.kt`

### [BUG-CP-01] Critical — Caller Contact Name Replaced with Raw Phone Number
- **Line Numbers:** 60–62
- **Severity:** Critical
- **Issue Description:**
  In `buildProfiles`:
  ```kotlin
  if (!displayNames.containsKey(key) || (extractedPhone != null && displayNames[key]?.any { it.isLetter() } == false)) {
      displayNames[key] = extractedPhone ?: meta.cleanTitle.trim()
  }
  ```
  `extractedPhone` is evaluated before `meta.cleanTitle`. If a call filename contains both a contact name and a phone number (e.g. `Call_John_Doe_+14155552671.m4a`), `displayNames[key]` is assigned `"+14155552671"`, overwriting `"John Doe"`.
- **Recommended Fix:**
  Prefer a name with alphabetic characters:
  ```kotlin
  val cleanName = meta.cleanTitle.trim()
  val nameHasLetters = cleanName.any { it.isLetter() }
  displayNames[key] = if (nameHasLetters) cleanName else (extractedPhone ?: cleanName)
  ```

---

### [BUG-CP-02] High — Country Codes Prevent Auto-Analyze Matching on Caller Profiles
- **Line Numbers:** 50, 113
- **Severity:** High
- **Issue Description:**
  `key` is normalized to at most 10 digits (`phoneDigits.takeLast(10)`).
  At line 113:
  ```kotlin
  val targetDigits = targetClean.filter { it.isDigit() }
  if (targetDigits.length >= 6 && key.contains(targetDigits)) return@any true
  ```
  If `targetClean` was saved with country code (e.g. 11 digits `14155552671`), `key.contains(targetDigits)` tests `"4155552671".contains("14155552671")`, which is always false. The badge `isAutoAnalyzeTarget` is never shown.
- **Recommended Fix:**
  Normalize `targetDigits` to the last 10 digits as well:
  ```kotlin
  val normalizedTarget = if (targetDigits.length >= 10) targetDigits.takeLast(10) else targetDigits
  if (normalizedTarget.length >= 6 && key == normalizedTarget) return@any true
  ```

---

### [BUG-CP-03] High — Split Caller Profiles for Same Contact
- **Line Numbers:** 48–56
- **Severity:** High
- **Issue Description:**
  If Call 1 is named `John_Doe.m4a` and Call 2 is named `John_Doe_+14155552671.m4a`, Call 1 produces key `"john doe"`, while Call 2 produces key `"4155552671"`. They are rendered as two separate caller profiles with split call histories and disconnected action items.
- **Recommended Fix:**
  Maintain a secondary index mapping `cleanName.lowercase()` to `phoneKey` to merge recordings belonging to the same named contact.

---

### [BUG-CP-04] Medium — Redundant O(N) Reparsing & Summary Decryption on StateFlow Emissions
- **Line Numbers:** 43, 81, 88, 95, 103
- **Severity:** Medium
- **Issue Description:**
  Inside `CallerProfileBuilder.buildProfiles`:
  1. `CallMetadataParser.parse(rec.title)` is called twice for every recording.
  2. `cleanCallTitle` is called inside an inner loop for every action item.
  3. Every checkbox toggle on an action item triggers `buildProfiles` across all recordings, re-decrypting summaries and re-running regular expressions.
- **Recommended Fix:**
  Parse metadata and extract action items once per recording, caching the parsed structure in a lightweight data model.

---

## Priority Summary Table

| File | Severity | Issue Key | Brief Description |
|---|---|---|---|
| `CallViewModel.kt` | **Critical** | BUG-VM-01 | Caller profile navigation broken due to key calculation mismatch |
| `CallViewModel.kt` | **Critical** | BUG-VM-02 | Gemini 429 quota failures swallowed; dummy transcripts saved into Room |
| `CallViewModel.kt` | **Critical** | BUG-VM-03 | Missing check for `"Audio Transcription Required"` treats failed calls as real |
| `CallSyncWorker.kt` | **Critical** | BUG-SW-01 | Periodic WorkManager sync has no network constraints; runs offline & corrupts DB |
| `CallSyncWorker.kt` | **Critical** | BUG-SW-02 | Worker catches `CancellationException` and returns `Result.retry()`, preventing cancel |
| `CallMetadataParser.kt` | **Critical** | BUG-MP-01 | `directionRegex` lacks word boundaries; strips "in"/"out" from Kevin, Martin, Robin |
| `CallMetadataParser.kt` | **Critical** | BUG-MP-02 | `prefixRegex` chops names starting with "Call", "Rec", "Voice" (Callum, Recep) |
| `CallerProfile.kt` | **Critical** | BUG-CP-01 | Contact name overridden by raw phone number in caller profile header |
| `CallViewModel.kt` | **High** | BUG-VM-04 | `onFolderSelected` and `reanalyzeRecording` bypass `SyncLock.mutex` |
| `CallViewModel.kt` | **High** | BUG-VM-05 | `startSyncWithLimit` drops `fileInfo.lastModified`, corrupting call dates to now |
| `CallViewModel.kt` | **High** | BUG-VM-06 | Activity `Context` leaked in `downloadAndInstallUpdate` during screen rotation |
| `CallViewModel.kt` | **High** | BUG-VM-07 | `sendChatMessage` lacks `try/finally`; uncaught errors freeze chat in loading state |
| `CallViewModel.kt` | **High** | BUG-VM-08 | `delay(2500)` in `finally` inside `SyncLock.mutex` breaks on cancellation |
| `CallViewModel.kt` | **High** | BUG-VM-09 | Heap spike / OOM reading 24 MB audio and Base64 string in `readAudioBytes` |
| `CallViewModel.kt` | **High** | BUG-VM-10 | `syncToCalendar` missing required `EXTRA_EVENT_BEGIN_TIME`, causing 1970/crashes |
| `CallSyncWorker.kt` | **High** | BUG-SW-03 | File loop ignores `isStopped` / cancellation token |
| `CallSyncWorker.kt` | **High** | BUG-SW-04 | Zombie placeholders left permanently stuck on "Pending" if worker interrupted |
| `CallSyncWorker.kt` | **High** | BUG-SW-05 | `scanDirectoryRecursively` swallows `SecurityException`; returns `Result.success()` |
| `CallMetadataParser.kt` | **High** | BUG-MP-03 | `dateTimeRegex` strips 8-digit phone numbers starting with 19 or 20 |
| `CallMetadataParser.kt` | **High** | BUG-MP-04 | ISO dates (`YYYY-MM-DD`) parsed as phone numbers |
| `CallerProfile.kt` | **High** | BUG-CP-02 | Country code in targets prevents auto-analyze match on 10-digit normalized key |
| `CallerProfile.kt` | **High** | BUG-CP-03 | Calls with name only vs name+number split into two separate profiles |
| `CallViewModel.kt` | **Medium** | BUG-VM-11 | 20+ `MutableStateFlow`s exposed publicly violating encapsulation |
| `CallViewModel.kt` | **Medium** | BUG-VM-12 | `updateStatusMessage` StateFlow drops duplicate transient events |
| `CallViewModel.kt` | **Medium** | BUG-VM-13 | Missing debounce on `searchQuery` causes lag on every keystroke |
| `CallViewModel.kt` | **Medium** | BUG-VM-14 | Deleted recordings re-imported on next sync/resume (missing tombstone) |
| `CallViewModel.kt` | **Medium** | BUG-VM-15 | Case-sensitive Set removal bug in `toggleCallerAutoAnalyze` |
| `CallSyncWorker.kt` | **Medium** | BUG-SW-06 | `SyncLock.mutex` contention risks WorkManager 10-minute worker timeout kill |
| `CallSyncWorker.kt` | **Medium** | BUG-SW-07 | Notification flood when multiple recordings discovered at once |
| `CallMetadataParser.kt` | **Medium** | BUG-MP-05 | `isUnknownNumber` classifies track numbers (e.g. `01.mp3`) as phone numbers |
| `CallMetadataParser.kt` | **Medium** | BUG-MP-06 | Substring matching in `matchesAutoAnalyzeRule` causes false contact matches |
| `CallMetadataParser.kt` | **Medium** | BUG-MP-07 | Regex recompiled on every line in `CommitmentExtractor` |
| `CallMetadataParser.kt` | **Medium** | BUG-MP-08 | Bold items starting with `**` prematurely cut off commitment extraction |
| `CallerProfile.kt` | **Medium** | BUG-CP-04 | O(N) repetitive parsing & summary decryption on StateFlow emissions |
| `CallViewModel.kt` | **Low** | BUG-VM-16 | Synchronous SharedPreferences reads on Main thread during init |
| `CallViewModel.kt` | **Low** | BUG-VM-17 | Uncached `OkHttpClient` creation in `testNvidiaApiKey` |
| `CallSyncWorker.kt` | **Low** | BUG-SW-08 | `ExistingPeriodicWorkPolicy.KEEP` prevents WorkManager constraint updates |
