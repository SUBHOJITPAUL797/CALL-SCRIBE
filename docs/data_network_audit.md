# Call Scribe: Comprehensive Code Quality & Bug Audit Report

**Audited Files:**
1. `app/src/main/java/com/example/data/ApiKeyManager.kt`
2. `app/src/main/java/com/example/data/AppDatabase.kt`
3. `app/src/main/java/com/example/data/CallPreferencesManager.kt`
4. `app/src/main/java/com/example/data/LocalAnalysisEngine.kt`
5. `app/src/main/java/com/example/network/CloudflareWorkerRepository.kt`
6. `app/src/main/java/com/example/network/GeminiApiService.kt`
7. `app/src/main/java/com/example/network/GitHubUpdateService.kt`
8. `app/src/main/java/com/example/network/NvidiaApiService.kt`

---

## 1. ApiKeyManager.kt

### Issue 1.1: Insecure Plaintext Storage of Sensitive API Keys
- **Line Numbers:** 8–11, 27, 46, 82
- **Severity:** High
- **Issue:** Sensitive API keys (`KEY_GEMINI_API_KEY`, `KEY_NVIDIA_API_KEY`, and `KEY_CLOUDFLARE_WORKER_TOKEN`) are stored in plaintext XML via standard `SharedPreferences` (`call_scribe_prefs.xml`). If Android Auto Backup (`android:allowBackup="true"`) is enabled, these secrets are backed up to cloud/local adb backups unencrypted. On rooted devices, any root app can read the keys.
- **Recommended Fix:** Migrate sensitive secrets to Jetpack Security `EncryptedSharedPreferences` backed by the Android Keystore, or exclude the preferences file from backup using an XML backup rule.

### Issue 1.2: Permanent Loss of Default Worker URL Fallback in `clearCloudflareConfig()`
- **Line Numbers:** 61–64, 90–92
- **Severity:** High
- **Issue:** `clearCloudflareConfig()` calls `prefs.edit().putString(KEY_CLOUDFLARE_WORKER_URL, "")...` instead of `.remove(KEY_CLOUDFLARE_WORKER_URL)`. However, `getCloudflareWorkerUrl()` checks `if (!prefs.contains(KEY_CLOUDFLARE_WORKER_URL)) { return DEFAULT_CLOUDFLARE_WORKER_URL }`. Once cleared, the preference key exists with value `""`, so `getCloudflareWorkerUrl()` forever returns `""` instead of the default worker URL, permanently breaking the default Cloudflare backend unless app storage is wiped.
- **Recommended Fix:** In `clearCloudflareConfig()`, remove the key instead of writing an empty string:
  ```kotlin
  fun clearCloudflareConfig() {
      prefs.edit().remove(KEY_CLOUDFLARE_WORKER_URL).remove(KEY_CLOUDFLARE_WORKER_TOKEN).apply()
  }
  ```

### Issue 1.3: Faulty URL Validation and False Configuration in `isCloudflareConfigured()`
- **Line Numbers:** 69–74, 86–88
- **Severity:** Medium
- **Issue:** `isCloudflareConfigured()` checks `url.startsWith("http://") || url.startsWith("https://")`. If a user enters `"https://"` or `"http://"`, it returns `true`. Furthermore, `setCloudflareWorkerUrl` only tests `raw.contains(".") && !raw.contains(" ")`, which allows malformed hostnames like `https://...`. When passed to OkHttp, `Request.Builder().url(...)` throws an `IllegalArgumentException` and crashes the app.
- **Recommended Fix:** Validate with `okhttp3.HttpUrl.parse(cleanUrl) != null`:
  ```kotlin
  fun isCloudflareConfigured(): Boolean {
      val url = getCloudflareWorkerUrl()
      val httpUrl = okhttp3.HttpUrl.parse(url) ?: return false
      return (httpUrl.scheme == "http" || httpUrl.scheme == "https") && httpUrl.host.isNotBlank()
  }
  ```

### Issue 1.4: Hardcoded Personal Subdomain as Production Fallback
- **Line Numbers:** 95
- **Severity:** Medium
- **Issue:** `const val DEFAULT_CLOUDFLARE_WORKER_URL = "https://callscribe-ai.subhojit.workers.dev"` hardcodes an individual developer's personal workers.dev subdomain. All app users by default route audio and call transcripts through this account, risking privacy leaks, Cloudflare account quota bans, or app breakage if the endpoint is decommissioned.
- **Recommended Fix:** Move the default URL to `BuildConfig` or an organization-managed domain, or require the user to configure their own endpoint.

### Issue 1.5: Arbitrary API Key Validation Heuristic
- **Line Numbers:** 32, 51
- **Severity:** Low
- **Issue:** `isConfigured()` and `isNvidiaConfigured()` check `key.length > 10`. Gemini keys typically start with `"AIzaSy"` (39 chars) and NVIDIA keys start with `"nvapi-"`. Any arbitrary string over 10 characters passes validation.
- **Recommended Fix:** Check known prefix formats or regex patterns (e.g. `key.startsWith("AIzaSy")` for Gemini and `key.startsWith("nvapi-")` for NVIDIA).

---

## 2. AppDatabase.kt

### Issue 2.1: Critical Security Misrepresentation: Base64 Encoding Marketed as "Encryption"
- **Line Numbers:** 13–39, 51–52
- **Severity:** Critical
- **Issue:** `SimpleEncryption` uses `android.util.Base64.encodeToString(...)` and calls it "simulated encryption" and "end-to-end encryption conceptually". Base64 is an encoding format, NOT encryption. Sensitive call transcripts and summaries containing confidential conversations are stored essentially in cleartext. Furthermore, Base64 increases database storage size by ~33% with zero cryptographic security.
- **Recommended Fix:** Either use SQLCipher (`SupportFactory` with Room) or Android Keystore AES-256-GCM encryption for the content columns. If encryption is not implemented, store as plaintext without deceptive field names and without the 33% Base64 space inflation.

### Issue 2.2: Data Corruption Risk in `SimpleEncryption.decrypt`
- **Line Numbers:** 27–38
- **Severity:** High
- **Issue:** If an unencoded plain ASCII string is passed to `Base64.decode()`, it does NOT always throw an exception because many ASCII words (e.g. "Hello world") contain valid Base64 character sets. It decodes into corrupted binary gibberish instead of triggering the `catch` fallback.
- **Recommended Fix:** Prepend an encryption version header/sentinel (e.g. `enc:v1:...`) before encoding so plain strings are never fed into the Base64 decoder.

### Issue 2.3: Performance & Memory Overhead from Synchronized Lazy Delegates in Entity
- **Line Numbers:** 56–64
- **Severity:** Medium
- **Issue:** `by lazy` in Kotlin defaults to `LazyThreadSafetyMode.SYNCHRONIZED`. When Room instantiates lists of `Recording` objects, every row allocates two `SynchronizedLazyImpl` objects and two monitor lock objects in RAM. For hundreds of rows, this creates unnecessary heap allocations and GC pauses.
- **Recommended Fix:** Use `by lazy(LazyThreadSafetyMode.NONE)` or computed properties:
  ```kotlin
  val decodedTranscription: String get() = SimpleEncryption.decrypt(contentEncrypted)
  val decodedSummary: String get() = SimpleEncryption.decrypt(summaryEncrypted)
  ```

### Issue 2.4: Unescaped SQLite `LIKE` Wildcards in Search Query
- **Line Numbers:** 72–73
- **Severity:** Medium
- **Issue:** `@Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' ORDER BY timestamp DESC")`: Characters like `%`, `_`, and `\` are wildcards in SQLite. A query for `_` matches every single recording rather than literal underscores.
- **Recommended Fix:** Sanitize `:query` by escaping wildcards or use SQLite `ESCAPE`:
  ```sql
  WHERE title LIKE '%' || :query || '%' ESCAPE '\'
  ```

### Issue 2.5: Inability to Search Full Transcript Content via SQL
- **Line Numbers:** 96–97
- **Severity:** Medium
- **Issue:** Because `contentEncrypted` and `summaryEncrypted` are Base64 strings, SQLite full-text search (FTS) is impossible. Full-text search must be executed in memory by decrypting all rows in the ViewModel, which creates UI lag and memory pressure as call history grows.
- **Recommended Fix:** Implement a Room FTS4/FTS5 virtual table or plaintext columns to leverage SQLite indexing.

### Issue 2.6: Missing Unique Constraint on `sourceUri` Causes Duplicate Rows
- **Line Numbers:** 43–47, 81–82
- **Severity:** Medium
- **Issue:** `sourceUri` is indexed but NOT unique (`unique = false`). `@Insert(onConflict = OnConflictStrategy.REPLACE)` only triggers on the primary key `id`. Since `id` defaults to `0` (autoGenerate), inserting recordings with the same `sourceUri` creates duplicate rows in the database if synchronization runs multiple times.
- **Recommended Fix:** Add `unique = true` to `Index(value = ["sourceUri"], unique = true)` or handle deduplication before insert.

### Issue 2.7: Disabled Room Schema Export
- **Line Numbers:** 88
- **Severity:** Low
- **Issue:** `exportSchema = false` suppresses schema JSON generation. In combined use with `fallbackToDestructiveMigration(true)` in `DefaultAppContainer`, schema version upgrades without migration scripts will drop the entire database without compile-time warnings.
- **Recommended Fix:** Enable `exportSchema = true` and configure `room.schemaLocation` in `build.gradle.kts`.

---

## 3. CallPreferencesManager.kt

### Issue 3.1: Android SharedPreferences `getStringSet` Mutable Reference Vulnerability
- **Line Numbers:** 77–79, 107–109
- **Severity:** High
- **Issue:** Android's official SharedPreferences documentation mandates that the Set returned by `getStringSet` must NEVER be modified directly, as it points to the internal cache in memory. `getAutoAnalyzeTargets()` and `getAllCompletedActionItems()` return `prefs.getStringSet(...) ?: emptySet()` directly without a defensive copy. If any caller mutates the set, the in-memory preference cache is corrupted.
- **Recommended Fix:** Return a defensive copy:
  ```kotlin
  fun getAutoAnalyzeTargets(): Set<String> {
      return prefs.getStringSet(KEY_AUTO_ANALYZE_TARGETS, emptySet())?.toSet() ?: emptySet()
  }
  ```

### Issue 3.2: Race Condition / Lost Updates on Set Modifications
- **Line Numbers:** 84–87, 90–93, 118–125
- **Severity:** High
- **Issue:** `addAutoAnalyzeTarget`, `removeAutoAnalyzeTarget`, and `setActionItemCompleted` perform read-modify-write operations on SharedPreferences sets without synchronization:
  ```kotlin
  val current = (prefs.getStringSet(...) ?: emptySet()).toMutableSet()
  current.add(...)
  prefs.edit().putStringSet(..., current).apply()
  ```
  If two action items or two contacts are updated concurrently from coroutines or rapid UI clicks, one write overwrites the other, silently dropping changes.
- **Recommended Fix:** Synchronize access or move action items to a dedicated Room table.

### Issue 3.3: Action Item Key Hash Collision & Fragility
- **Line Numbers:** 112, 118
- **Severity:** Medium
- **Issue:** `val key = "${recordingId}_${itemText.hashCode()}"`:
  1. 32-bit Java `hashCode()` collisions will cause unrelated action items to check/uncheck each other.
  2. Any minor formatting change, whitespace difference, or punctuation difference produces a completely different hash code, losing the checked state.
  3. Negative hash codes produce keys like `1_-12847192`.
- **Recommended Fix:** Store action item status in the Room database associated with an autoincrement ID or deterministic SHA-256 hash.

### Issue 3.4: Memory Leak / Unbounded SharedPreferences Storage Growth
- **Line Numbers:** 106–126
- **Severity:** Medium
- **Issue:** `KEY_COMPLETED_ACTION_ITEMS` accumulates completed item keys indefinitely. When recordings are deleted in `RecordingDao.deleteRecordingById(id)`, completed action items for that recording ID are never pruned from SharedPreferences. Over months of use, this set grows indefinitely.
- **Recommended Fix:** Prune action item keys when deleting a recording, or use a Room database table with `FOREIGN KEY ... ON DELETE CASCADE`.

### Issue 3.5: Missing Phone Number Normalization
- **Line Numbers:** 81–87
- **Severity:** Medium
- **Issue:** `target.trim()` does not normalize phone numbers. Numbers like `+1 (555) 019-2831` and `5550192831` will not match incoming call logs if formats differ.
- **Recommended Fix:** Strip non-digits or use `android.telephony.PhoneNumberUtils.normalizeNumber(target)`.

---

## 4. LocalAnalysisEngine.kt

### Issue 4.1: Critical Logic Bug: Transcripts Containing "API Key" Treated as Untranscribed
- **Line Numbers:** 143–148
- **Severity:** Critical
- **Issue:** In `answerCallQuestionLocally`:
  ```kotlin
  val isEmptyTranscript = transcript.isBlank() ||
      transcript.contains("Transcription requires") ||
      transcript.contains("API Key") ||
      transcript.contains("On-Device Speech Analysis")
  ```
  If a phone conversation naturally discusses an API key (e.g. developers discussing "Send me the API Key for the server"), `transcript.contains("API Key")` evaluates to `true`! The engine refuses to answer questions and outputs: `"⚠️ I don't have a transcript to search through for this call."`
- **Recommended Fix:** Remove `transcript.contains("API Key")` and check for the specific sentinel/placeholder string or pass a boolean `hasTranscript` flag from the domain model.

### Issue 4.2: Massive False Positives from Substring Keyword Matching
- **Line Numbers:** 90–101
- **Severity:** High
- **Issue:** Keywords are tested with `lower.contains(it)` (substring containment) rather than word boundaries:
  - `"am"` in `dateKeywords` matches: `"name"`, `"family"`, `"program"`, `"stream"`, `"team"`, `"exam"`, `"sample"`, `"I am"`. Almost every English sentence is categorized as a date/timeline!
  - `"pm"` matches: `"spam"`, `"rpm"`, `"equipment"`, `"department"`, `"development"`.
  - `"will"` matches: `"willing"`, `"unwilling"`, `"William"`, `"goodwill"`.
  - `"must"` matches: `"mustard"`, `"mustache"`.
  - `"shall"` matches: `"shallow"`, `"marshall"`.
  - `"send"` matches: `"sender"`, `"descendant"`.
- **Recommended Fix:** Use word boundaries `\b` for Latin keywords:
  ```kotlin
  val regex = Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE)
  ```

### Issue 4.3: Indic Script Sentence Splitting Failure (Missing Danda Punctuation)
- **Line Numbers:** 82, 163
- **Severity:** High
- **Issue:** `cleanTranscript.split(Regex("""(?<=[.!?])\s+|\n+"""))` only splits on Latin punctuation (`.`, `!`, `?`). The engine explicitly includes Bengali and Hindi action/date keywords, but both languages use the danda `।` (U+0964) and double danda `॥` (U+0965) as sentence terminators. As a result, properly punctuated Bengali and Hindi transcripts are never split into sentences, treating entire multi-minute calls as a single sentence.
- **Recommended Fix:** Include Indic sentence terminators:
  ```kotlin
  Regex("""(?<=[.!?।॥])\s+|\n+""")
  ```

### Issue 4.4: Incomplete Stop Words List Biases Keyword Search
- **Line Numbers:** 215–216
- **Severity:** Medium
- **Issue:** `stopWords` omits essential English pronouns and auxiliary verbs: `"i"`, `"me"`, `"my"`, `"you"`, `"your"`, `"he"`, `"she"`, `"it"`, `"we"`, `"they"`, `"to"`, `"of"`, `"and"`, `"or"`, `"can"`, `"tell"`. A question like "Can you tell me about the meeting?" produces tokens `["can", "tell", "meeting"]`, skewing search results. Zero Indic stop words are included.
- **Recommended Fix:** Expand the stop words set to include pronouns and auxiliary words.

### Issue 4.5: Substring Token Matching in Search Scoring
- **Line Numbers:** 228
- **Severity:** Medium
- **Issue:** `val score = tokens.count { lower.contains(it) }` counts substring matches. A token like `"he"` matches `"the"`, `"where"`, `"other"`. Sentences with unrelated long words receive inflated scores.
- **Recommended Fix:** Tokenize sentences into word sets or use word boundary matching.

---

## 5. CloudflareWorkerRepository.kt

### Issue 5.1: Coroutine Cancellation Swallowed (`CancellationException`)
- **Line Numbers:** 93, 168, 220, 293, 347
- **Severity:** High
- **Issue:** All network calls catch generic `catch (e: Exception) { Result.failure(...) }`. In Kotlin Coroutines, `CancellationException` is a subclass of `IllegalStateException` / `Exception`. When a coroutine scope is cancelled (e.g. user leaves screen), `CancellationException` is caught and converted to `Result.failure`, breaking structured concurrency and cooperative cancellation.
- **Recommended Fix:** Rethrow `CancellationException`:
  ```kotlin
  catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      Result.failure(...)
  }
  ```

### Issue 5.2: Uncaught `IllegalArgumentException` Crash Risk Outside `try` Block
- **Line Numbers:** 133–145, 193–202, 247–260, 320–329
- **Severity:** High
- **Issue:** In `transcribeAudio`, `summarizeTranscript`, `analyzeAudio`, and `chatWithCall`, `val request = Request.Builder().url(...).addHeader(...).build()` is constructed OUTSIDE the `try { ... }` block. If `url` is invalid, or if `language` contains a newline or non-ASCII character (e.g. `addHeader("X-Call-Language", language)`), OkHttp throws an unchecked `IllegalArgumentException`. Because it is outside `try`, this CRASHES the app.
- **Recommended Fix:** Wrap request construction inside the `try` block and sanitize header values:
  ```kotlin
  try {
      val request = Request.Builder()
          .url(url)
          .addHeader("X-Call-Language", sanitizeHeaderValue(language))
          ...
          .build()
      val response = client.newCall(request).execute()
      ...
  } catch (e: Exception) {
      if (e is CancellationException) throw e
      Result.failure(e)
  }
  ```

### Issue 5.3: Heap Exhaustion / OOM Crash Risk with Large Audio `ByteArray`
- **Line Numbers:** 115, 127, 229, 241
- **Severity:** High
- **Issue:** Audio is loaded into memory as a monolithic `ByteArray` and wrapped into a `RequestBody`. A 30–60 minute recording in WAV/MP3 format can be 30MB–100MB+. In Android devices with 192MB/256MB heap limits, holding and buffering raw audio byte arrays causes `OutOfMemoryError`.
- **Recommended Fix:** Accept a `File` or `Uri` and stream via `file.asRequestBody(mediaType)` or custom streaming `RequestBody`.

### Issue 5.4: Resource Leak: OkHttp `Response` / `ResponseBody` Not Closed with `use {}`
- **Line Numbers:** 78–81, 147–150, 204–207, 261–264, 331–334
- **Severity:** Medium
- **Issue:** `val response = client.newCall(request).execute()` is followed by `response.body?.string()`. If `response.body` is null or if reading the body throws an `IOException`, the response is never closed. Unclosed responses leak sockets and connection pool slots.
- **Recommended Fix:** Always use `response.use { res -> ... }`.

### Issue 5.5: Missing Language Parameter on 404 Fallback in `analyzeAudio`
- **Line Numbers:** 280–288
- **Severity:** Medium
- **Issue:** When `/analyze` returns 404 (e.g. legacy worker script), it falls back to calling `transcribeAudio(audioBytes, fileName, mimeType)` — but does NOT pass `language`! The selected language reverts to `"auto"`, ignoring the user's choice.
- **Recommended Fix:** Pass `language`:
  ```kotlin
  val transResult = transcribeAudio(audioBytes, fileName, mimeType, language)
  ```

### Issue 5.6: Unsafe Force Unwrap `!!`
- **Line Numbers:** 283
- **Severity:** Medium
- **Issue:** `return@withContext Result.failure(transResult.exceptionOrNull()!!)` uses `!!` force unwrapping. If `transResult` failure has no exception, it throws an unhandled `NullPointerException`.
- **Recommended Fix:** `transResult.exceptionOrNull() ?: Exception("Transcription failed")`.

### Issue 5.7: Unencoded `language` Parameter in Query String
- **Line Numbers:** 130–131, 244–245
- **Severity:** Low
- **Issue:** `langParam = if (language.isNotBlank() && language != "auto") "&lang=$language" else ""` appends `language` without URL encoding.

---

## 6. GeminiApiService.kt

### Issue 6.1: Non-Existent Speculative Models Cause Consecutive 404 Delays & Rate Limit Risks
- **Line Numbers:** 157–166, 186–192, 224–237
- **Severity:** High
- **Issue:** `CANDIDATE_MODELS` starts with:
  `"gemini-3.8-flash"`, `"gemini-3.5-flash"`, `"gemini-3.5-flash-lite"`, `"gemini-3.7-flash"`, `"gemini-3.1-pro"`, `"gemini-2.5-flash"`.
  When `discoverActiveModels` fails or returns empty (e.g. restricted API key or network glitch), `executeWithModelFallback` tries each model sequentially. It makes up to 6 consecutive failing HTTP 404 network round trips before reaching real models (`gemini-2.0-flash` or `gemini-1.5-flash`). This causes 10–30 seconds of pure latency and UI freezes on slow mobile connections.
- **Recommended Fix:** Place production verified models first:
  ```kotlin
  val CANDIDATE_MODELS = listOf(
      "gemini-2.0-flash",
      "gemini-1.5-flash",
      "gemini-1.5-pro"
  )
  ```

### Issue 6.2: Parsing Bug: Summary Dropped if Gemini Outputs Summary Before Transcript
- **Line Numbers:** 124–136, 148
- **Severity:** High
- **Issue:** In `GeminiResponseParser.parseAudioAnalysis`:
  ```kotlin
  if (summaryMatch != null && transcriptionMatch != null) {
      ...
      if (summaryHeaderStart > transcriptionStart) {
          ...
      }
  }
  ```
  If Gemini generates the `SUMMARY:` section before the `TRANSCRIPTION:` section, `summaryHeaderStart > transcriptionStart` is `false`. It drops out of the `if` block, skips `else if (summaryMatch != null)`, and falls through to:
  `return Pair(trimmed, "See transcription for complete call details.")`
  The entire summary is lost!
- **Recommended Fix:** Check both section orders:
  ```kotlin
  if (summaryHeaderStart > transcriptionStart) {
      val transcription = trimmed.substring(transcriptionStart, summaryHeaderStart).trim()
      val summary = trimmed.substring(summaryStart).trim()
      return Pair(transcription, summary)
  } else {
      val summary = trimmed.substring(summaryStart, transcriptionMatch.range.first).trim()
      val transcription = trimmed.substring(transcriptionStart).trim()
      return Pair(transcription, summary)
  }
  ```

### Issue 6.3: Coroutine Cancellation & VM Errors Swallowed by `catch (e: Throwable)`
- **Line Numbers:** 381, 420, 471
- **Severity:** High
- **Issue:** Catching `Throwable` catches `CancellationException` and `VirtualMachineError` (e.g. `OutOfMemoryError`). Coroutine cancellations are swallowed and turned into `Result.failure`, breaking structured concurrency.
- **Recommended Fix:** Check `if (e is CancellationException) throw e` and catch `Exception` instead of `Throwable`.

### Issue 6.4: Heap Spike & OOM Risk with Base64 Audio in String
- **Line Numbers:** 285, 353
- **Severity:** High
- **Issue:** Audio is passed as a Base64-encoded `String` in `InlineData`. A 10MB audio file becomes a ~13.3M-character String (~27MB in UTF-16 heap). Serializing to JSON via Moshi duplicates it, and OkHttp buffers it again, creating 70MB–100MB+ heap allocations.
- **Recommended Fix:** Use Google AI File API (`upload/v1beta/files`) for audio uploads or compress/downsample audio before base64 encoding.

### Issue 6.5: Excessive Connect Timeout Freezes App
- **Line Numbers:** 90–95
- **Severity:** Medium
- **Issue:** `connectTimeout(90, TimeUnit.SECONDS)` is unnecessarily long. If a user has a dead connection or captive portal, the app waits 90 seconds before failing.
- **Recommended Fix:** Set `connectTimeout(15, TimeUnit.SECONDS)`.

### Issue 6.6: Silent Suppression of Safety Blocks in `summarizeTranscription`
- **Line Numbers:** 278
- **Severity:** Medium
- **Issue:** `response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No summary generated."`
  If Gemini safety filters block the response, `text` is null and `finishReason` is `SAFETY`. The user is told `"No summary generated."` without knowing it was flagged by safety filters.
- **Recommended Fix:** Check `finishReason` and display a descriptive message.

### Issue 6.7: Fallback Loop Immediately Aborts on 400 Bad Request
- **Line Numbers:** 230–236
- **Severity:** Medium
- **Issue:** `executeWithModelFallback` only catches and continues on HTTP 404. If a model does not support audio or returns 400 Bad Request, it throws immediately instead of trying the next candidate.
- **Recommended Fix:** Catch 400 as well if the error body indicates unsupported model or capability.

---

## 7. GitHubUpdateService.kt

### Issue 7.1: Missing `User-Agent` Header Causes HTTP 403 Forbidden from GitHub API
- **Line Numbers:** 42–49
- **Severity:** Critical
- **Issue:** GitHub's API policy strictly requires all requests to provide a valid `User-Agent` header. Requests without a `User-Agent` are blocked with HTTP 403 Forbidden. `GitHubRetrofitClient` does not configure an `OkHttpClient` with a `User-Agent`, and `GitHubApiService` only sets `"Accept: application/vnd.github.v3+json"`.
- **Recommended Fix:** Add the `User-Agent` header to the interface:
  ```kotlin
  @Headers(
      "Accept: application/vnd.github.v3+json",
      "User-Agent: CallScribe-App"
  )
  @GET("repos/{owner}/{repo}/releases/latest")
  suspend fun getLatestRelease(...)
  ```

### Issue 7.2: Non-APK Asset Selected as Download URL Causes App Install Failure
- **Line Numbers:** 109–114
- **Severity:** High
- **Issue:** In `checkForUpdate`:
  ```kotlin
  val apkAsset = release.assets.firstOrNull {
      it.name.contains(cleanLatest) && it.name.endsWith(".apk", ignoreCase = true)
  } ?: release.assets.firstOrNull {
      it.name.endsWith(".apk", ignoreCase = true)
  } ?: release.assets.firstOrNull()
  ```
  If a GitHub release has no APK uploaded (e.g. only `checksums.txt`, `release-notes.md`, or source `.zip`), the fallback `?: release.assets.firstOrNull()` selects `checksums.txt`! `info.downloadUrl` points to a text file, and `hasUpdate` is `true`. Tapping "Download Update" downloads a text file and attempts to launch Android PackageInstaller, crashing or failing.
- **Recommended Fix:** Remove `?: release.assets.firstOrNull()`. Only select `.apk` assets:
  ```kotlin
  val apkAsset = release.assets.firstOrNull {
      it.name.contains(cleanLatest) && it.name.endsWith(".apk", ignoreCase = true)
  } ?: release.assets.firstOrNull {
      it.name.endsWith(".apk", ignoreCase = true)
  }
  ```

### Issue 7.3: Pre-release Version Stripping Prevents Beta Users from Updating to Stable
- **Line Numbers:** 65–84
- **Severity:** Medium
- **Issue:** `cleanVersion` strips everything after `-` (`split("-")[0]`). If the current installed app is `"1.5.0-beta"` and the new release is `"1.5.0"`, both are cleaned to `"1.5.0"`. `isNewer` returns `false`, and beta users are never notified of the official stable release.
- **Recommended Fix:** Handle pre-release suffixes or semver precedence properly.

### Issue 7.4: Coroutine Cancellation Swallowed
- **Line Numbers:** 126
- **Severity:** Medium
- **Issue:** `catch (e: Exception) { Result.failure(e) }` catches `CancellationException` and prevents proper coroutine scope cancellation.
- **Recommended Fix:** Rethrow `if (e is CancellationException) throw e`.

### Issue 7.5: Unconfigured OkHttpClient Timeouts
- **Line Numbers:** 51–61
- **Severity:** Low
- **Issue:** `GitHubRetrofitClient` constructs `Retrofit.Builder()` without a custom `OkHttpClient`, relying on default timeouts.

---

## 8. NvidiaApiService.kt

### Issue 8.1: Severe HTTP Socket & Connection Leak in `testApiKey`
- **Line Numbers:** 208–217, 240–250
- **Severity:** Critical
- **Issue:**
  1. In Step 1: `val response = client.newCall(modelsRequest).execute()`. If `response.isSuccessful` is true, the code advances to Step 2 WITHOUT closing `response`.
  2. In Step 2: Inside the loop over `CHAT_MODELS`, `val response = client.newCall(request).execute()` is called. In all cases (`isSuccessful`, `429`, `403`, `401`), `response` is NEVER closed.
  If 403 occurs on multiple models, up to 5 unclosed HTTP response sockets are leaked on a single key test!
- **Recommended Fix:** Always use `response.use { res -> ... }`:
  ```kotlin
  client.newCall(modelsRequest).execute().use { response ->
      if (!response.isSuccessful) { ... }
  }
  ```

### Issue 8.2: Masked Error: Invalid API Key (HTTP 401) Discarded in Fallback Loop
- **Line Numbers:** 127–142, 173–189
- **Severity:** High
- **Issue:** In `summarizeTranscript` and `chatWithCall`:
  ```kotlin
  for (model in CHAT_MODELS) {
      val result = callChatApi(apiKey, body)
      if (result.isSuccess) return@withContext result
  }
  Result.failure(Exception("NVIDIA summarization failed across all models."))
  ```
  If the user's API key is invalid (HTTP 401), `callChatApi` correctly produces `Result.failure(Exception("Invalid NVIDIA API key (HTTP 401)."))`. But the caller discards it, tries the remaining 3 models (each failing with 401), and finally returns the generic message `"NVIDIA summarization failed across all models."`. The user is never informed that their API key is invalid!
- **Recommended Fix:** Immediately abort the loop if an authentication error (401/403) or rate limit (429) is encountered:
  ```kotlin
  val result = callChatApi(apiKey, body)
  if (result.isSuccess) return@withContext result
  val err = result.exceptionOrNull()
  if (err?.message?.contains("401") == true || err?.message?.contains("403") == true) {
      return@withContext result
  }
  ```

### Issue 8.3: Empty Catch Block Swallowing All Exceptions
- **Line Numbers:** 250
- **Severity:** High
- **Issue:** `catch (_: Exception) {}` silently swallows all exceptions during test chat completion, including `CancellationException`, `InterruptedException`, and `SocketTimeoutException`.
- **Recommended Fix:** Log the exception and rethrow `CancellationException`.

### Issue 8.4: Coroutine Cancellation Swallowed in All Catch Blocks
- **Line Numbers:** 84, 217, 291
- **Severity:** High
- **Issue:** Catch blocks catch `Exception` and return `Result.failure`, swallowing `CancellationException`.
- **Recommended Fix:** Rethrow `if (e is CancellationException) throw e`.

### Issue 8.5: Duplicate `Content-Type: application/json` Header
- **Line Numbers:** 231–236
- **Severity:** Medium
- **Issue:** `toRequestBody("application/json".toMediaTypeOrNull())` automatically adds the `Content-Type: application/json` header to the HTTP request. Manually calling `.addHeader("Content-Type", "application/json")` causes OkHttp to send duplicate headers, which violates RFC 7230 and triggers rejection by strict reverse proxies.
- **Recommended Fix:** Remove `.addHeader("Content-Type", "application/json")`.

### Issue 8.6: Suboptimal Multimodal Vision Models Used for Text-Only Summarization
- **Line Numbers:** 21–26
- **Severity:** Medium
- **Issue:** `CHAT_MODELS` lists `"meta/llama-3.2-11b-vision-instruct"` and `"meta/llama-3.2-90b-vision-instruct"` at the top. Using vision models for purely textual call transcript summarization is slower, costs more credits, and has stricter prompt schemas than standard text models like `meta/llama-3.1-70b-instruct` or `meta/llama-3.1-8b-instruct`.
- **Recommended Fix:** Use standard LLM text chat models first.

### Issue 8.7: Memory Heap Pressure with `audioBytes: ByteArray`
- **Line Numbers:** 49, 61
- **Severity:** Medium
- **Issue:** Passing complete audio byte arrays in memory into `MultipartBody` causes memory pressure and potential OOM on long calls.

### Issue 8.8: Uncaught Request Construction Outside `try-catch`
- **Line Numbers:** 69–74
- **Severity:** Medium
- **Issue:** In `transcribeAudio`, `Request.Builder()...build()` is outside the `try` block.

---

## 9. Cross-File & Architectural Issues

1. **Multiple Unshared `OkHttpClient` Instances:**
   - `CloudflareWorkerRepository` (line 17), `GeminiApiService` (line 90), and `NvidiaRepository` (line 36) all instantiate separate `OkHttpClient` instances with independent connection pools and dispatcher thread pools.
   - In `DefaultAppContainer`, repositories are created anew on demand, multiplying active HTTP client instances and leaking thread pools.
   - **Fix:** Provide a shared singleton `OkHttpClient` across all repositories.

2. **Preference File Namespace Overlap:**
   - Both `ApiKeyManager` (line 9) and `CallPreferencesManager` (line 160) use `"call_scribe_prefs"`. Mixing critical security credentials with UI/sync flags in the same file complicates backup exclusions and encryption.
   - **Fix:** Separate into `"call_scribe_secure_prefs"` (encrypted) and `"call_scribe_user_prefs"` (plain).

3. **Inconsistent Magic String Detection for Unprocessed Transcripts:**
   - `LocalAnalysisEngine` (line 48–50, 144–146), `GeminiApiService` (line 435–438), and `NvidiaApiService` (line 158–161) each maintain a slightly different list of magic strings to check if a call has been transcribed.
   - **Fix:** Add a status enum/boolean property (`isTranscribed: Boolean`) directly to the `Recording` domain model.