/**
 * Call Scribe — Cloudflare Worker AI Transcribe & Summarize Engine
 * 
 * Powered by Cloudflare Workers AI:
 * - Speech-to-Text: @cf/openai/whisper
 * - Summarization & Commitments: @cf/meta/llama-3.1-8b-instruct
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
          "Access-Control-Allow-Headers": "Content-Type, Authorization",
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
          asr: "@cf/openai/whisper",
          llm: "@cf/meta/llama-3.1-8b-instruct-fast",
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

    // 5. Speech-to-Text (Transcription) Endpoint
    if (path === "/transcribe") {
      try {
        const audioBuffer = await request.arrayBuffer();
        if (!audioBuffer || audioBuffer.byteLength === 0) {
          return jsonResponse({ error: "No audio data received." }, 400);
        }

        const audioUint8 = new Uint8Array(audioBuffer);
        const whisperResult = await env.AI.run("@cf/openai/whisper", {
          audio: [...audioUint8],
        });

        const transcription = whisperResult.text || "";
        return jsonResponse({
          transcription: transcription.trim(),
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
        const transcript = body.transcript || "";
        const callTitle = body.callTitle || "Call Recording";

        if (!transcript.trim()) {
          return jsonResponse({ error: "No transcript provided to summarize." }, 400);
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
        const transcript = body.transcript || "";
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
          callTitle = request.headers.get("X-Call-Title") || "Call Recording";
        }

        if (!audioBytes || audioBytes.byteLength === 0) {
          return jsonResponse({ error: "No audio data received." }, 400);
        }

        // Step A: Whisper ASR
        const whisperResult = await env.AI.run("@cf/openai/whisper", {
          audio: [...audioBytes],
        });
        const transcription = (whisperResult.text || "").trim();

        // Step B: Llama 3.1 Summarization
        let summary = "";
        if (transcription.length > 0) {
          summary = await runSummarization(env, transcription, callTitle);
        } else {
          summary = "No audible speech detected in this recording.";
        }

        return jsonResponse({
          transcription: transcription,
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
 * Runs Meta Llama 3.1 8B Instruct to produce Call Scribe's standard structured summary.
 */
async function runSummarization(env, transcript, callTitle) {
  const systemPrompt = `You are the AI assistant for Call Scribe, a phone call recording and transcription application.
Analyze the provided phone call transcript accurately.
You must output a structured markdown summary in the exact format:

## 📋 Executive Summary
(2-4 concise sentences summarizing the purpose and conclusion of the call)

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
  const systemPrompt = `You are the AI assistant for Call Scribe. Answer the user's question about this phone call accurately, concisely, and based only on the provided call transcript and summary. If the answer is not mentioned, politely explain that it wasn't mentioned in the call.`;

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
