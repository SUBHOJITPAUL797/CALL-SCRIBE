/**
 * Call Scribe — Cloudflare Worker AI Transcribe & Summarize Engine
 * 
 * Powered by Cloudflare Workers AI:
 * - Speech-to-Text: @cf/openai/whisper-large-v3-turbo (fallback to @cf/openai/whisper)
 * - Anti-hallucination & silence filtering: Silero VAD + condition_on_previous_text=false
 * - Summarization & Commitments: @cf/meta/llama-3.1-8b-instruct-fast
 * 
 * Free Tier: 10,000 AI Neurons per day on Cloudflare!
 */

export default {
  async fetch(request, env) {
    // 1. CORS Preflight
    if (request.method === "OPTIONS") {
      return new Response(null, {
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Call-Title, X-Call-Title-Encoded, X-Call-Language",
        },
      });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");

    // 2. Health / Ping Check (no auth required for health)
    if (request.method === "GET" && (path === "" || path === "/health")) {
      return jsonResponse({
        status: "ok",
        message: "Call Scribe Cloudflare Worker is running!",
        models: {
          asr_primary: "@cf/openai/whisper-large-v3-turbo",
          asr_fallback: "@cf/openai/whisper",
          llm: "@cf/meta/llama-3.1-8b-instruct-fast",
        },
        features: {
          vad_filter: true,
          condition_on_previous_text: false,
          hallucination_filter: true,
          languages: ["auto", "bn", "hi", "en"],
        },
      });
    }

    // 3. Authentication Check (if API_TOKEN is configured in Worker environment)
    if (env.API_TOKEN && env.API_TOKEN.trim().length > 0) {
      const authHeader = request.headers.get("Authorization") || "";
      const expected = `Bearer ${env.API_TOKEN.trim()}`;
      if (authHeader.trim() !== expected) {
        return jsonResponse({ error: "Unauthorized: Invalid or missing Bearer token." }, 401);
      }
    }

    // 4. Test Auth Endpoint
    if (path === "/test" || path === "/ping") {
      return jsonResponse({
        status: "ok",
        message: "Authentication successful! Worker is ready to transcribe.",
      });
    }

    if (request.method !== "POST") {
      return jsonResponse({ error: "Method not allowed. Use POST for analysis." }, 405);
    }

    const urlParamLang = url.searchParams.get("lang") || url.searchParams.get("language");
    const headerLang = request.headers.get("X-Call-Language");
    const language = (urlParamLang || headerLang || "auto").trim().toLowerCase();

    // 5. Speech-to-Text (Transcription) Endpoint
    if (path === "/transcribe") {
      try {
        const audioBuffer = await request.arrayBuffer();
        if (!audioBuffer || audioBuffer.byteLength === 0) {
          return jsonResponse({ error: "No audio data received." }, 400);
        }

        const audioUint8 = new Uint8Array(audioBuffer);
        let whisperResult = await runWhisperWithFallback(env, audioUint8, language);
        let rawTranscription = (whisperResult.text || whisperResult.transcription || "").trim();

        // If Whisper hallucinated an irrelevant foreign script or got stuck in a repetition loop:
        const needsRetry = (hasIrrelevantForeignScript(rawTranscription) && language !== "ko" && language !== "ru" && language !== "zh") ||
                           isRepetitionLoop(rawTranscription);

        if (needsRetry) {
          console.warn(`Whisper hallucinated or looped (${rawTranscription.slice(0, 40)}...). Retrying with Bengali focus...`);
          try {
            const retryResult = await runWhisperWithFallback(env, audioUint8, "bn");
            const retryRaw = (retryResult.text || retryResult.transcription || "").trim();
            if (retryRaw.length > 0 && !hasIrrelevantForeignScript(retryRaw) && !isRepetitionLoop(retryRaw)) {
              rawTranscription = retryRaw;
            }
          } catch (_) {}
        }

        const transcription = cleanWhisperTranscript(rawTranscription);

        return jsonResponse({
          transcription: transcription.length > 0 ? transcription : "(No audible speech detected)",
          raw: transcription.length === 0 ? rawTranscription : undefined,
          vtt: whisperResult.vtt || null,
        });
      } catch (err) {
        return jsonResponse({ error: `Transcription failed: ${err.message}` }, 500);
      }
    }

    // 6. Text Summarization & Commitment Extraction Endpoint
    if (path === "/summarize") {
      try {
        const body = await request.json();
        const rawTranscript = body.transcript || "";
        const callTitle = body.callTitle || "Call Recording";

        const transcript = cleanWhisperTranscript(rawTranscript);

        if (!transcript.trim()) {
          return jsonResponse({
            summary: `## 📋 Executive Summary\nNo clear speech or coherent conversation was detected in this call recording.\n\n## 📝 Key Discussion Points\n- None detected\n\n## ✅ Action Items & Commitments\n- None\n\n## 📅 Dates & Deadlines\n- None`,
          });
        }

        const summaryText = await runSummarization(env, transcript, callTitle);
        return jsonResponse({
          summary: summaryText,
        });
      } catch (err) {
        return jsonResponse({ error: `Summarization failed: ${err.message}` }, 500);
      }
    }

    // 7. Chat With Call Endpoint
    if (path === "/chat") {
      try {
        const body = await request.json();
        const transcript = cleanWhisperTranscript(body.transcript || "");
        const summary = body.summary || "";
        const question = body.question || "";

        if (!question.trim()) {
          return jsonResponse({ error: "No question provided." }, 400);
        }

        const reply = await runChat(env, transcript, summary, question);
        return jsonResponse({ reply });
      } catch (err) {
        return jsonResponse({ error: `Chat failed: ${err.message}` }, 500);
      }
    }

    // 8. Full All-in-One Analyze Endpoint (Transcribe + Summarize)
    if (path === "" || path === "/analyze") {
      try {
        const contentType = request.headers.get("Content-Type") || "";
        let audioBytes = null;
        let callTitle = "Call Recording";

        if (contentType.includes("multipart/form-data")) {
          const formData = await request.formData();
          const file = formData.get("audio") || formData.get("file");
          if (!file) return jsonResponse({ error: "Missing 'audio' file in form data." }, 400);
          callTitle = formData.get("title") || file.name || "Call Recording";
          audioBytes = new Uint8Array(await file.arrayBuffer());
        } else {
          // Raw binary audio in request body
          const buffer = await request.arrayBuffer();
          audioBytes = new Uint8Array(buffer);

          const urlParamTitle = url.searchParams.get("title");
          const encodedHeaderTitle = request.headers.get("X-Call-Title-Encoded");
          const rawHeaderTitle = request.headers.get("X-Call-Title");

          if (urlParamTitle) {
            try { callTitle = decodeURIComponent(urlParamTitle); } catch (_) { callTitle = urlParamTitle; }
          } else if (encodedHeaderTitle) {
            try { callTitle = decodeURIComponent(encodedHeaderTitle); } catch (_) { callTitle = encodedHeaderTitle; }
          } else if (rawHeaderTitle) {
            callTitle = rawHeaderTitle;
          }
        }

        if (!audioBytes || audioBytes.byteLength === 0) {
          return jsonResponse({ error: "No audio data received." }, 400);
        }

        // Step A: Whisper ASR with language steering, VAD, and anti-hallucination
        let whisperResult = await runWhisperWithFallback(env, audioBytes, language);
        let rawTranscription = (whisperResult.text || whisperResult.transcription || "").trim();

        // If Whisper hallucinated an irrelevant foreign script or got stuck in a repetition loop:
        const needsRetry = (hasIrrelevantForeignScript(rawTranscription) && language !== "ko" && language !== "ru" && language !== "zh") ||
                           isRepetitionLoop(rawTranscription);

        if (needsRetry) {
          console.warn(`Whisper hallucinated or looped (${rawTranscription.slice(0, 40)}...). Retrying with Bengali focus...`);
          try {
            const retryResult = await runWhisperWithFallback(env, audioBytes, "bn");
            const retryRaw = (retryResult.text || retryResult.transcription || "").trim();
            if (retryRaw.length > 0 && !hasIrrelevantForeignScript(retryRaw) && !isRepetitionLoop(retryRaw)) {
              rawTranscription = retryRaw;
            }
          } catch (_) {}
        }

        let transcription = cleanWhisperTranscript(rawTranscription);

        // Step B: Summarization
        let summary = "";
        if (transcription.length > 0) {
          summary = await runSummarization(env, transcription, callTitle);
        } else {
          summary = `## 📋 Executive Summary\nNo clear speech or conversation was detected in this recording. The audio appears to contain silence or background noise only.\n\n## 📝 Key Discussion Points\n- None detected\n\n## ✅ Action Items & Commitments\n- None\n\n## 📅 Dates & Deadlines\n- None`;
        }

        // Auto-sanitize if summary detected no coherent conversation or repetition loops:
        if (summary.includes("No coherent conversation was detected") ||
            summary.includes("No clear speech or conversation was detected") ||
            summary.includes("contains only background noise") ||
            summary.includes("repeated filler words") ||
            isRepetitionLoop(transcription)) {
          transcription = "(No audible speech detected)";
        }

        return jsonResponse({
          transcription: transcription.length > 0 ? transcription : "(No audible speech detected)",
          summary: summary,
        });
      } catch (err) {
        return jsonResponse({ error: `Analysis failed: ${err.message}` }, 500);
      }
    }

    return jsonResponse({ error: `Unknown endpoint: ${path}` }, 404);
  },
};

/**
 * Supported Whisper models on Cloudflare Workers AI in order of preference.
 */
const WHISPER_MODELS = [
  "@cf/openai/whisper-large-v3-turbo",
  "@cf/openai/whisper",
];

const BENGALI_HINDI_ENGLISH_PROMPT = "বাংলা ভাষায় পরিষ্কার স্পষ্ট কথোপকথন। মা, বাবা, কেমন আছো, ঠিক আছে, কি করছো, হ্যাঁ, না, বলো, খাব, যাব। এবং হিন্দি ও English.";

function hasIrrelevantForeignScript(text) {
  if (!text || typeof text !== "string") return false;
  // Non-South Asian, non-Latin scripts that Whisper hallucinates on noisy/silent telephone audio:
  // Korean (Hangul), Cyrillic, Chinese/Japanese, Arabic, Greek, Hebrew, Thai
  const foreignScriptPattern = /[\uac00-\ud7af\u1100-\u11ff\u0400-\u04ff\u4e00-\u9fff\u3040-\u30ff\u0600-\u06ff\u0590-\u05ff\u0e00-\u0e7f\u0370-\u03ff]/u;
  return foreignScriptPattern.test(text);
}

/**
 * Detects runaway autoregressive loops where Whisper repeats the same character,
 * syllable, or short phrase indefinitely on background noise or silence.
 */
function isRepetitionLoop(text) {
  if (!text || typeof text !== "string") return false;
  const trimmed = text.trim();
  if (trimmed.length === 0) return false;

  // 1. Any non-space sequence of 1-4 characters repeated 3 or more times back-to-back:
  // e.g. "शाशाशाशा...", "শাশাশাশা...", "aaaa..."
  if (/([^\s]{1,4})\1{2,}/u.test(trimmed)) {
    return true;
  }

  // 2. Multi-char phrase repeating 3 or more times back-to-back:
  // e.g. "হাঁনাহাঁনাহাঁনা", "hello hello hello"
  if (/([^\s]{2,10})\1{2,}/u.test(trimmed)) {
    return true;
  }

  // 3. Spaced multi-word or single-word repeating phrases e.g. "बापापा पो बापापा पो" or "thank you thank you"
  // Matches 1 to 4 words repeated 3+ times back to back across Unicode scripts
  if (/(?:^|[\s,।!?])([\p{L}\p{N}]{1,15}(?:[\s,।]+[\p{L}\p{N}]{1,15}){0,3})(?:[\s,।]+\1){2,}(?:[\s,।!?]|$)/gui.test(trimmed)) {
    return true;
  }

  // 4. Token uniqueness ratio check:
  // When Whisper gets trapped in an autoregressive loop on noise/silence,
  // it repeats a tiny set of words over and over
  const tokens = trimmed.split(/[\s,।!?\.\-]+/).filter(t => t.length > 0);
  if (tokens.length >= 8) {
    const uniqueTokens = new Set(tokens.map(t => t.toLowerCase()));
    if (uniqueTokens.size / tokens.length < 0.32) {
      return true;
    }
  }

  // 5. Dominant single character check (single non-space char makes up >30% of a text >25 chars):
  if (trimmed.length > 25) {
    const counts = {};
    let maxCharCount = 0;
    for (const char of trimmed) {
      if (!/\s/.test(char)) {
        counts[char] = (counts[char] || 0) + 1;
        if (counts[char] > maxCharCount) maxCharCount = counts[char];
      }
    }
    if (maxCharCount / trimmed.length > 0.3) {
      return true;
    }
  }

  return false;
}

/**
 * Runs Whisper with language steering, VAD, and model fallback.
 */
async function runWhisperWithFallback(env, audioBytes, language = "auto") {
  const primaryOptions = {
    audio: [...audioBytes],
    vad_filter: true,
    condition_on_previous_text: false,
    compression_ratio_threshold: 2.4,
    hallucination_silence_threshold: 2.0,
  };

  if (language === "bn" || language === "bengali") {
    primaryOptions.language = "bn";
    primaryOptions.initial_prompt = "বাংলা ভাষায় পরিষ্কার কথোপকথন। মা, কেমন আছো, ঠিক আছে, কি করছো, হ্যাঁ, না, বলো।";
  } else if (language === "hi" || language === "hindi") {
    primaryOptions.language = "hi";
    primaryOptions.initial_prompt = "हिंदी में साफ बातचीत। क्या हाल है, नमस्ते, ठीक है, हाँ, नहीं।";
  } else if (language === "en" || language === "english") {
    primaryOptions.language = "en";
    primaryOptions.initial_prompt = "Clear English phone call conversation between two people.";
  } else {
    // Auto multilingual: explicitly prime for Bengali first, then Hindi & English
    primaryOptions.initial_prompt = BENGALI_HINDI_ENGLISH_PROMPT;
  }

  let lastError = null;
  for (const model of WHISPER_MODELS) {
    try {
      const res = await env.AI.run(model, primaryOptions);
      if (res && (res.text !== undefined || res.transcription !== undefined)) {
        return res;
      }
    } catch (e) {
      console.warn(`Whisper ${model} with options failed (${e.message}), trying direct audio with prompt...`);
      try {
        const fallbackOptions = {
          audio: [...audioBytes],
        };
        if (primaryOptions.language) fallbackOptions.language = primaryOptions.language;
        if (primaryOptions.initial_prompt) fallbackOptions.initial_prompt = primaryOptions.initial_prompt;
        const fallbackRes = await env.AI.run(model, fallbackOptions);
        if (fallbackRes && (fallbackRes.text !== undefined || fallbackRes.transcription !== undefined)) {
          return fallbackRes;
        }
      } catch (innerE) {
        lastError = innerE;
        console.warn(`Whisper ${model} failed (${innerE.message}), trying next model...`);
      }
    }
  }
  throw lastError || new Error("All Whisper transcription models failed.");
}

/**
 * Detects and removes Whisper hallucinations, infinite punctuation loops,
 * runaway character repetitions, and silence artifacts.
 */
function cleanWhisperTranscript(rawText) {
  if (!rawText || typeof rawText !== "string") return "";
  let text = rawText.trim();
  if (!text) return "";

  // 1. Strip repetitive punctuation or dots (e.g. "... ... ... ...")
  text = text.replace(/(?:\.\s*){3,}/g, "... ");
  text = text.replace(/(?:\.\.\.\s*){2,}/g, "... ");

  // 2. Collapse runaway character / syllable repetitions without spaces (e.g. "वाशाशाशाशाशा..." -> "वाशा")
  text = text.replace(/([^\s]{1,4})\1{2,}/gu, "$1");

  // 3. Collapse runaway multi-char repetitions without spaces (e.g. "abcdefabcdefabcdef" -> "abcdef")
  text = text.replace(/([^\s]{5,12})\1{1,}/gu, "$1");

  // Check if text has virtually no alphanumeric content (e.g. only dots, dashes, commas)
  const alphaNumericOnly = text.replace(/[\s\.\,\!\?\-\:\;\(\)\[\]\"\'\…\·\•\*\~\_]/g, "");
  if (alphaNumericOnly.length < 2) {
    return "";
  }

  // 4. Remove consecutive duplicate sentences (e.g. "날 저갈더스타! 날 저갈더스타!")
  const sentences = text.split(/(?<=[.!?\n])\s+/).filter(s => s.trim().length > 0);
  if (sentences.length >= 2) {
    const deduplicated = [];
    let consecutiveCount = 0;
    let lastSentenceNorm = "";

    for (const s of sentences) {
      const norm = s.trim().toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
      if (norm.length > 0 && norm === lastSentenceNorm) {
        consecutiveCount++;
        // If repeated more than once, drop it
        if (consecutiveCount < 2) {
          deduplicated.push(s.trim());
        }
      } else {
        consecutiveCount = 0;
        lastSentenceNorm = norm;
        deduplicated.push(s.trim());
      }
    }
    text = deduplicated.join(" ");
  }

  // 5. Remove consecutive duplicate words or short phrases with spaces (e.g. "Thank you. Thank you. Thank you." or "बापापा पो बापापा पो")
  text = text.replace(/(?:^|[\s,।!?])([\p{L}\p{N}]{1,15}(?:[\s,।]+[\p{L}\p{N}]{1,15}){0,3})(?:[\s,।]+\1){2,}(?:[\s,।!?]|$)/gui, "$1 ");

  // 6. Known Whisper silence/noise hallucinations
  const lower = text.toLowerCase().trim();
  const knownHallucinations = [
    /^\[?\(?(?:music|applause|laughter|silence|cheering|coughing|groan|sigh)\)?\]?$/i,
    /^(?:subtitles by|translated by|transcript by|captions by|thank you for watching|please subscribe)/i,
    /^(?:날 저갈더스타|날 저갈더스타!)/i,
  ];
  for (const pattern of knownHallucinations) {
    if (pattern.test(lower)) {
      return "";
    }
  }

  // 7. If text still contains irrelevant foreign scripts (Korean, Cyrillic, Chinese, etc.):
  if (hasIrrelevantForeignScript(text)) {
    const validSouthAsianOrLatin = text.replace(/[^\u0980-\u09ff\u0900-\u097fa-zA-Z0-9]/gu, "");
    if (validSouthAsianOrLatin.length < 5) {
      return "";
    }
  }

  // 8. If after cleaning the text is still detected as a repetition loop:
  if (isRepetitionLoop(text)) {
    return "";
  }

  // 9. Final check: if alphanumeric characters count is less than 2, it's silence
  const finalAlpha = text.replace(/[^\p{L}\p{N}]/gu, "");
  if (finalAlpha.length < 2) {
    return "";
  }

  return text.trim();
}

/**
 * Runs Meta Llama 3.1 8B Instruct to produce Call Scribe's standard structured summary.
 */
async function runSummarization(env, transcript, callTitle) {
  const systemPrompt = `You are the AI assistant for Call Scribe, a phone call recording and transcription application.
Analyze the provided phone call transcript accurately.
CRITICAL LANGUAGE HANDLING:
- The conversation may be spoken in Bengali (বাংলা), Hindi (हिंदी), English, or a mix of these (Banglish / Hinglish).
- Provide the structured summary, key points, action items, and dates in clear English so the user can easily understand everything that was discussed and agreed upon.
- If the transcript contains only background noise, repeated filler words, corrupted text, or lacks coherent speech, do not invent facts. State clearly in the Executive Summary that no coherent conversation was detected, and write "- None" for all other sections.

You must output a structured markdown summary in the exact format:

## 📋 Executive Summary
(2-4 concise sentences in English summarizing the purpose and conclusion of the call)

## 📝 Key Discussion Points
- (Bullet point 1)
- (Bullet point 2)
- (Bullet point 3)

## ✅ Action Items & Commitments
(List every promise, task, follow-up, or commitment made by either party. If none, write "- None")
- (Task 1)
- (Task 2)

## 📅 Dates & Deadlines
(List every scheduled date, meeting time, or deadline mentioned with exact time if available. If none, write "- None")
- (Date/Time 1)
- (Date/Time 2)`;

  const userPrompt = `Call Title: ${callTitle}

Transcript:
"""
${transcript.slice(0, 12000)}
"""

Please produce the structured analysis according to the specified format.`;

  return await runLlmWithFallback(env, {
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userPrompt },
    ],
    max_tokens: 1024,
    temperature: 0.2,
  });
}

/**
 * Answers questions about a call using Llama 3.1 8B Instruct.
 */
async function runChat(env, transcript, summary, question) {
  const systemPrompt = `You are the AI assistant for Call Scribe. The call may be in Bengali, Hindi, or English. Answer the user's question about this phone call accurately, concisely, in clear English (or Bengali/Hindi if the user asks in that language), based strictly on the provided transcript and summary. If the answer is not mentioned, politely explain that it wasn't mentioned in the call.`;

  const contextText = `Call Summary:\n${summary}\n\nCall Transcript:\n"""\n${transcript.slice(0, 10000)}\n"""`;

  try {
    return await runLlmWithFallback(env, {
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: `${contextText}\n\nUser Question: ${question}` },
      ],
      max_tokens: 512,
      temperature: 0.3,
    });
  } catch (e) {
    return "Could not generate an answer at this time. Please try again.";
  }
}

const LLM_MODELS = [
  "@cf/meta/llama-3.1-8b-instruct-fast",
  "@cf/meta/llama-3.1-8b-instruct-fp8",
  "@cf/meta/llama-3.2-3b-instruct",
  "@cf/meta/llama-3.2-1b-instruct",
];

async function runLlmWithFallback(env, payload) {
  let lastError = null;
  for (const model of LLM_MODELS) {
    try {
      const res = await env.AI.run(model, payload);
      const text = res.response || res.text || "";
      if (text.trim().length > 0) {
        return text;
      }
    } catch (e) {
      lastError = e;
      console.warn(`Model ${model} failed (${e.message}), trying next...`);
    }
  }
  throw lastError || new Error("All AI models failed to respond.");
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    },
  });
}
