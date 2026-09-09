# ⚡ Call Scribe — Cloudflare Workers AI Engine

Deploy your own private, unlimited Whisper AI transcription & Llama 3.1 summarization engine on Cloudflare for **FREE** (10,000 AI Neurons every single day)!

---

## 🚀 Option A: Deploy via Cloudflare Web Dashboard (Zero Install, 2 Minutes)

1. Go to [dash.cloudflare.com](https://dash.cloudflare.com) and log in (or create a free account).
2. On the left sidebar, click **Workers & Pages** → **Create application** → **Create Worker**.
3. Choose a name (e.g. `callscribe-ai`) and click **Deploy**.
4. Click **Edit Code** (or **Quick Edit**).
5. Delete the starter code and **paste the entire contents of [`worker.js`](worker.js)** into the editor.
6. Click **Deploy** (top right).
7. Enable Workers AI binding:
   - In your Worker page, go to **Settings** → **Bindings** (or **Workers AI**).
   - Under **Workers AI**, click **Add binding**, set Variable name to `AI`.
   - *(Optional Secret Password)* Under **Variables and Secrets**, add `API_TOKEN` with your secret password (e.g. `mysecret123`).
8. Copy your Worker URL:
   `https://callscribe-ai.<your-subdomain>.workers.dev`
9. Open **Call Scribe** on your phone → Tap **🔑 API Keys / Engine** → Paste your Worker URL!

---

## 💻 Option B: Deploy via Command Line (Wrangler CLI)

From this directory (`CALL-SCRIBE/cloudflare-worker`):

```bash
# 1. Login to Cloudflare
npx wrangler login

# 2. Deploy the worker
npx wrangler deploy

# 3. (Optional) Set your secret token
npx wrangler secret put API_TOKEN
```

Your Worker URL will be printed in the terminal (e.g. `https://callscribe-ai.YOUR_SUBDOMAIN.workers.dev`).

---

## 🧪 Testing Your Worker

You can test your worker with curl or browser:

```bash
curl https://callscribe-ai.YOUR_SUBDOMAIN.workers.dev/health
```

Expected response:
```json
{
  "status": "ok",
  "message": "Call Scribe Cloudflare Worker is running!",
  "models": {
    "asr": "@cf/openai/whisper",
    "llm": "@cf/meta/llama-3.1-8b-instruct"
  }
}
```
