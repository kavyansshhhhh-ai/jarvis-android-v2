import 'dotenv/config';
import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';
import { Content, FunctionDeclaration, GoogleGenAI, Type } from '@google/genai';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PORT = 3000;
const HOST = '0.0.0.0';

function createAiClient(): GoogleGenAI {
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error('GEMINI_API_KEY is not configured in the server environment.');
  }
  return new GoogleGenAI({
    apiKey,
    httpOptions: {
      headers: {
        'User-Agent': 'aistudio-build',
      },
    },
  });
}

const LANGUAGE_DIRECTIVE: Record<string, string> = {
  auto: 'Reply in the same language mix the user speaks (English, Hindi or Hinglish).',
  english: 'Reply in English unless the user explicitly asks otherwise.',
  hindi: 'Reply in Hindi unless the user explicitly asks otherwise.',
  hinglish: 'Reply in natural Hinglish (Hindi-English mix, Roman script in text).',
};

const SECRET_WORDS_RE =
  /\b(password|passcode|pass word|pin|otp|cvv|upi pin|atm pin|secret|private key|seed phrase|login code)\b/i;
const CARD_RE = /\b(?:\d[ -]?){13,19}\b/;
const FINANCE_RE =
  /\b(phonepe|google\s*pay|gpay|paytm|bhim|amazon\s*pay|cred|mobikwik|bharatpe|navi|pay|payment|upi|transfer|buy|purchase|checkout|book\s+tickets?)\b/i;

const toolDeclarations: FunctionDeclaration[] = [
  {
    name: 'get_current_datetime',
    description: 'Get the current date, day of week, and time.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        timezone: { type: Type.STRING, description: 'Optional IANA timezone name' },
      },
    },
  },
  {
    name: 'get_weather',
    description: 'Get real-time current weather and today forecast for a city using Open-Meteo.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        city: { type: Type.STRING, description: 'City name, e.g. Mumbai, Delhi, Bengaluru, San Francisco' },
      },
      required: ['city'],
    },
  },
  {
    name: 'remember_note',
    description: 'Save a non-secret note to local memory. Never save passwords, PINs, OTPs, CVVs, or card numbers.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        text: { type: Type.STRING, description: 'The note text to remember' },
      },
      required: ['text'],
    },
  },
  {
    name: 'recall_notes',
    description: 'Search saved memory notes matching a query, or pass empty query for recent notes.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        query: { type: Type.STRING, description: 'Keywords to search in saved notes' },
      },
    },
  },
  {
    name: 'forget_note',
    description: 'Delete saved memory notes matching a keyword or phrase.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        query: { type: Type.STRING, description: 'Keyword or phrase of the note to forget' },
      },
      required: ['query'],
    },
  },
  {
    name: 'routine_save',
    description: 'Save a named voice routine with an ordered list of steps.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        name: { type: Type.STRING, description: 'Routine name, e.g. Good night' },
        steps: {
          type: Type.ARRAY,
          items: { type: Type.STRING },
          description: 'List of plain-language steps in the routine',
        },
      },
      required: ['name', 'steps'],
    },
  },
  {
    name: 'routine_run',
    description: 'Run a saved or built-in routine such as Good morning.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        name: { type: Type.STRING, description: 'Routine name to run' },
      },
      required: ['name'],
    },
  },
  {
    name: 'set_timer',
    description: 'Start a countdown timer for a number of seconds or minutes.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        seconds: { type: Type.INTEGER, description: 'Duration in seconds (1..86400)' },
        label: { type: Type.STRING, description: 'Optional timer label' },
      },
      required: ['seconds'],
    },
  },
  {
    name: 'set_alarm',
    description: 'Schedule an alarm for a specific hour and minute (24h format).',
    parameters: {
      type: Type.OBJECT,
      properties: {
        hour: { type: Type.INTEGER, description: 'Hour 0..23' },
        minute: { type: Type.INTEGER, description: 'Minute 0..59' },
        label: { type: Type.STRING, description: 'Optional alarm label' },
      },
      required: ['hour', 'minute'],
    },
  },
  {
    name: 'create_reminder',
    description: 'Create a reminder for the user.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        text: { type: Type.STRING, description: 'What to remind the user about' },
        minutesFromNow: { type: Type.INTEGER, description: 'Minutes from now until due (default 30)' },
      },
      required: ['text'],
    },
  },
  {
    name: 'triage_notifications',
    description: 'Summarize missed messages, calls, emails, and alerts with OTP digits hidden and suspicious links flagged.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        filter: { type: Type.STRING, description: 'Optional filter: all, messages, emails' },
      },
    },
  },
  {
    name: 'prepare_action',
    description:
      'Prepare an outgoing or external action (WhatsApp message, normal/WhatsApp call, email, calendar event, song playback, or ticket/payment handoff). Respects safety rules: outgoing messages/calls/deletes require explicit user confirmation, and payments/UPI/banking always hand off to the user.',
    parameters: {
      type: Type.OBJECT,
      properties: {
        actionType: {
          type: Type.STRING,
          description: 'One of: whatsapp_send, call_contact, whatsapp_call, email_compose, calendar_event, play_song, open_app, ticket_booking, payment_handoff',
        },
        target: { type: Type.STRING, description: 'Recipient, contact name, app name, or song query' },
        content: { type: Type.STRING, description: 'Message text, email body, artist, or event details' },
        channel: { type: Type.STRING, description: 'Optional channel: normal, audio, video, spotify, youtube_music' },
      },
      required: ['actionType', 'target'],
    },
  },
];

async function fetchWeather(city: string): Promise<Record<string, unknown>> {
  const cleanCity = city.trim();
  if (!cleanCity) {
    return { ok: false, error: 'Please specify a city name.' };
  }
  try {
    const geoRes = await fetch(
      `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(cleanCity)}&count=1&language=en&format=json`,
    );
    if (!geoRes.ok) throw new Error(`Geocoding failed (${geoRes.status})`);
    const geoJson = (await geoRes.json()) as {
      results?: Array<{ name: string; country?: string; latitude: number; longitude: number }>;
    };
    const place = geoJson.results?.[0];
    if (!place) {
      return { ok: false, error: `Could not find location "${cleanCity}".` };
    }
    const wxRes = await fetch(
      `https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&timezone=auto`,
    );
    if (!wxRes.ok) throw new Error(`Weather API failed (${wxRes.status})`);
    const wx = (await wxRes.json()) as {
      current?: {
        temperature_2m?: number;
        apparent_temperature?: number;
        relative_humidity_2m?: number;
        weather_code?: number;
        wind_speed_10m?: number;
      };
      daily?: { temperature_2m_max?: number[]; temperature_2m_min?: number[] };
    };
    const code = wx.current?.weather_code ?? 0;
    const condition = weatherDescription(code);
    return {
      ok: true,
      city: `${place.name}${place.country ? `, ${place.country}` : ''}`,
      temperatureC: wx.current?.temperature_2m,
      feelsLikeC: wx.current?.apparent_temperature,
      humidityPercent: wx.current?.relative_humidity_2m,
      windKmh: wx.current?.wind_speed_10m,
      condition,
      highC: wx.daily?.temperature_2m_max?.[0],
      lowC: wx.daily?.temperature_2m_min?.[0],
    };
  } catch (err) {
    return {
      ok: false,
      error: err instanceof Error ? err.message : 'Unable to fetch weather right now.',
    };
  }
}

function weatherDescription(code: number): string {
  if (code === 0) return 'Clear sky';
  if (code <= 3) return 'Partly cloudy';
  if (code === 45 || code === 48) return 'Foggy';
  if (code >= 51 && code <= 67) return 'Rain / Drizzle';
  if (code >= 71 && code <= 77) return 'Snow';
  if (code >= 80 && code <= 82) return 'Rain showers';
  if (code >= 95) return 'Thunderstorm';
  return 'Mild conditions';
}

interface ClientContextPayload {
  replyLanguage?: string;
  profileSummary?: string;
  habitsSummary?: string;
  homeCity?: string;
  notes?: Array<{ id: string; text: string; atMillis: number }>;
  routines?: Array<{ name: string; steps: string[] }>;
  reminders?: Array<{ id: string; text: string; dueAt: number; done: boolean }>;
  triageSummary?: string;
  automations?: Record<string, boolean>;
}

interface ClientAction {
  type: string;
  payload: Record<string, unknown>;
}

function buildSystemInstructions(ctx: ClientContextPayload): string {
  const nowStr = new Date().toLocaleString('en-IN', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
  });
  const langKey = ctx.replyLanguage && LANGUAGE_DIRECTIVE[ctx.replyLanguage] ? ctx.replyLanguage : 'auto';
  return [
    `You are JARVIS, a voice and AI assistant for English, Hindi, and Hinglish.`,
    `Current date and time: ${nowStr}.`,
    ctx.profileSummary ? `User's Personal Profile (local preferences): ${ctx.profileSummary}` : '',
    ctx.habitsSummary ? `What this user tends to do (learned locally; a hint only): ${ctx.habitsSummary}` : '',
    ctx.homeCity ? `Default city for weather when omitted: ${ctx.homeCity}` : '',
    '',
    'How you talk:',
    '- Keep answers clear and direct: one to four sentences unless asked for a detailed explanation.',
    '- Address the user naturally if Personal Profile specifies preferredName / addressAs or responseStyle.',
    '- Call tools immediately when the request needs an action, weather, memory, routine, timer, alarm, reminder, or notification triage, then confirm honestly from verified tool results.',
    `- ${LANGUAGE_DIRECTIVE[langKey]}`,
    '',
    'Safety rules (enforced in code too):',
    '- Never store passwords, PINs, OTPs, CVVs, or card numbers in memory.',
    '- Payments, UPI PINs, banking, and ticket checkout NEVER run automatically; use prepare_action(actionType="payment_handoff") and hand off to the user ("Your turn").',
    '- Outgoing messages, calls, and deletions require user confirmation via the Allow card ("Needs you") before finalizing.',
    '- Answer general knowledge, science, math, coding, and explanations directly using your knowledge or Google Search grounding.',
  ]
    .filter(Boolean)
    .join('\n');
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/api/health', (_req, res) => {
    res.json({
      ok: true,
      hasGeminiKey: Boolean(process.env.GEMINI_API_KEY),
      model: 'gemini-4-hydrogen',
    });
  });

  app.post('/api/assistant/chat', async (req, res) => {
    try {
      const {
        message,
        history = [],
        context = {},
      } = req.body as {
        message?: string;
        history?: Array<{ fromUser: boolean; text: string }>;
        context?: ClientContextPayload;
      };

      if (!message || !message.trim()) {
        res.status(400).json({ error: 'Message is required.' });
        return;
      }

      const ai = createAiClient();
      const systemInstruction = buildSystemInstructions(context);

      const contents: Content[] = [
        ...history.slice(-10).map((h) => ({
          role: h.fromUser ? 'user' : 'model',
          parts: [{ text: h.text }],
        })),
        { role: 'user', parts: [{ text: message.trim() }] },
      ];

      const tools = [{ googleSearch: {} }, { functionDeclarations: toolDeclarations }];
      const toolConfig = { includeServerSideToolInvocations: true };

      const actions: ClientAction[] = [];
      let pendingTask: Record<string, unknown> | null = null;
      const collectedSources: Array<{ title: string; uri: string }> = [];

      let response = await ai.models.generateContent({
        model: 'gemini-4-hydrogen',
        contents,
        config: {
          systemInstruction,
          tools,
          toolConfig,
        },
      });

      const extractChunks = (resp: typeof response) => {
        const chunks = resp.candidates?.[0]?.groundingMetadata?.groundingChunks ?? [];
        for (const chunk of chunks) {
          const web = (chunk as { web?: { uri?: string; title?: string } }).web;
          if (web?.uri && !collectedSources.some((s) => s.uri === web.uri)) {
            collectedSources.push({ title: web.title || web.uri, uri: web.uri });
          }
        }
      };
      extractChunks(response);

      for (let step = 0; step < 4; step++) {
        const calls = response.functionCalls;
        if (!calls || calls.length === 0) break;

        const modelContent = response.candidates?.[0]?.content;
        if (modelContent) {
          contents.push(modelContent);
        }

        const fnParts = [];
        for (const fc of calls) {
          const args = (fc.args ?? {}) as Record<string, unknown>;
          let result: Record<string, unknown> = { ok: true };

          switch (fc.name) {
            case 'get_current_datetime': {
              const now = new Date();
              result = {
                iso: now.toISOString(),
                formatted: now.toLocaleString('en-IN', {
                  weekday: 'long',
                  day: 'numeric',
                  month: 'long',
                  year: 'numeric',
                  hour: '2-digit',
                  minute: '2-digit',
                  second: '2-digit',
                }),
              };
              break;
            }
            case 'get_weather': {
              const city = String(args.city || context.homeCity || 'New Delhi');
              result = await fetchWeather(city);
              if (result.ok) {
                actions.push({ type: 'set_home_city', payload: { city } });
              }
              break;
            }
            case 'remember_note': {
              const text = String(args.text || '').trim();
              if (!text) {
                result = { ok: false, error: 'Empty note.' };
              } else if (SECRET_WORDS_RE.test(text) || CARD_RE.test(text)) {
                result = {
                  ok: false,
                  error: 'Refused: memory never stores passwords, PINs, OTPs, CVVs, or card numbers.',
                };
              } else {
                actions.push({ type: 'remember_note', payload: { text } });
                result = { ok: true, saved: text };
              }
              break;
            }
            case 'recall_notes': {
              const query = String(args.query || '').toLowerCase().trim();
              const allNotes = context.notes ?? [];
              const matched = query
                ? allNotes.filter((n) =>
                    query.split(/\s+/).some((w) => w.length >= 2 && n.text.toLowerCase().includes(w)),
                  )
                : allNotes.slice(0, 5);
              result = {
                ok: true,
                notes: matched.slice(0, 5).map((n) => n.text),
              };
              break;
            }
            case 'forget_note': {
              const query = String(args.query || '').trim();
              actions.push({ type: 'forget_note', payload: { query } });
              result = { ok: true, forgottenQuery: query };
              break;
            }
            case 'routine_save': {
              const name = String(args.name || '').trim();
              const steps = Array.isArray(args.steps) ? args.steps.map(String).filter(Boolean) : [];
              if (!name || steps.length === 0) {
                result = { ok: false, error: 'Routine requires a name and at least one step.' };
              } else {
                actions.push({ type: 'routine_save', payload: { name, steps } });
                result = { ok: true, name, steps };
              }
              break;
            }
            case 'routine_run': {
              const name = String(args.name || '').trim();
              const routines = context.routines ?? [];
              const found = routines.find((r) => r.name.toLowerCase().includes(name.toLowerCase()));
              if (!found) {
                result = { ok: false, error: `No routine named "${name}" found.` };
              } else {
                actions.push({ type: 'routine_run', payload: { name: found.name, steps: found.steps } });
                result = {
                  ok: true,
                  routine: found.name,
                  steps: found.steps,
                  triageSummary: context.triageSummary || 'No missed notifications.',
                  notes: (context.notes ?? []).slice(0, 5).map((n) => n.text),
                  reminders: (context.reminders ?? []).filter((r) => !r.done).map((r) => r.text),
                };
              }
              break;
            }
            case 'set_timer': {
              const seconds = Math.max(1, Math.min(86400, Number(args.seconds) || 60));
              const label = String(args.label || 'Timer');
              actions.push({ type: 'set_timer', payload: { seconds, label } });
              result = { ok: true, seconds, label };
              break;
            }
            case 'set_alarm': {
              const hour = Math.max(0, Math.min(23, Number(args.hour) || 7));
              const minute = Math.max(0, Math.min(59, Number(args.minute) || 0));
              const label = String(args.label || 'Alarm');
              actions.push({ type: 'set_alarm', payload: { hour, minute, label } });
              result = { ok: true, hour, minute, label };
              break;
            }
            case 'create_reminder': {
              const text = String(args.text || '').trim();
              const minutesFromNow = Math.max(1, Number(args.minutesFromNow) || 30);
              if (text) {
                actions.push({ type: 'create_reminder', payload: { text, minutesFromNow } });
                result = { ok: true, text, minutesFromNow };
              } else {
                result = { ok: false, error: 'Reminder text missing.' };
              }
              break;
            }
            case 'triage_notifications': {
              result = {
                ok: true,
                summary: context.triageSummary || 'No messages, missed calls, or alerts right now.',
              };
              break;
            }
            case 'prepare_action': {
              const actionType = String(args.actionType || '');
              const target = String(args.target || '');
              const content = String(args.content || '');
              const channel = String(args.channel || '');
              const combined = `${actionType} ${target} ${content}`;

              if (actionType === 'payment_handoff' || actionType === 'ticket_booking' || FINANCE_RE.test(combined)) {
                pendingTask = {
                  title: actionType === 'ticket_booking' ? `Book: ${target}` : `Your turn: ${target}`,
                  state: 'WaitingForYou',
                  risk: 'Financial',
                  steps: [
                    { label: `Prepared ${target} request`, done: true },
                    { label: 'Complete selection & payment yourself', done: null },
                  ],
                  detail: 'JARVIS never pays or enters UPI PINs, passwords, or OTPs.',
                  pendingAction: {
                    kind: 'payment_hand_off',
                    title: target || 'Payment / Booking hand-off',
                    summary: content || 'Open the app or site yourself to finish checkout and payment.',
                    target,
                    confirmLabel: 'Done',
                  },
                };
                result = {
                  ok: true,
                  status: 'handed_to_user',
                  message: 'Handed off to the user. JARVIS never pays or completes financial transactions.',
                };
              } else if (actionType === 'whatsapp_send' || actionType === 'email_compose') {
                const isEmail = actionType === 'email_compose';
                const waUrl = !isEmail
                  ? `https://wa.me/?text=${encodeURIComponent(content)}`
                  : `mailto:?subject=${encodeURIComponent(target)}&body=${encodeURIComponent(content)}`;
                pendingTask = {
                  title: isEmail ? `Email ${target}` : `WhatsApp ${target}`,
                  state: 'AwaitingConfirmation',
                  risk: 'High',
                  steps: [
                    { label: `Drafted message for ${target}`, done: true },
                    { label: 'Tap Allow to open & send', done: null },
                  ],
                  detail: content,
                  pendingAction: {
                    kind: 'send_message',
                    title: isEmail ? `Send email to ${target}?` : `Send WhatsApp to ${target}?`,
                    summary: content,
                    target,
                    url: waUrl,
                    confirmLabel: 'Allow',
                  },
                };
                result = {
                  ok: true,
                  status: 'awaiting_user_allow',
                  message: `Prepared draft for ${target}. Waiting for the user to tap Allow.`,
                };
              } else if (actionType === 'call_contact' || actionType === 'whatsapp_call') {
                pendingTask = {
                  title: `${actionType === 'whatsapp_call' ? 'WhatsApp' : 'Phone'} call ${target}`,
                  state: 'AwaitingConfirmation',
                  risk: 'High',
                  steps: [
                    { label: `Matched contact: ${target}`, done: true },
                    { label: 'Tap Allow to place call', done: null },
                  ],
                  detail: `${channel || 'audio'} call to ${target}`,
                  pendingAction: {
                    kind: 'call',
                    title: `Call ${target}?`,
                    summary: `${actionType === 'whatsapp_call' ? 'WhatsApp' : 'Phone'} ${channel || 'audio'} call`,
                    target,
                    confirmLabel: 'Allow',
                  },
                };
                result = {
                  ok: true,
                  status: 'awaiting_user_allow',
                  message: `Call to ${target} prepared. Waiting for the user to tap Allow.`,
                };
              } else if (actionType === 'play_song') {
                const query = encodeURIComponent(`${target} ${content}`.trim());
                const isSpotify = channel.toLowerCase().includes('spotify');
                const url = isSpotify
                  ? `https://open.spotify.com/search/${query}`
                  : `https://music.youtube.com/search?q=${query}`;
                actions.push({ type: 'open_music', payload: { target, content, channel, url } });
                pendingTask = {
                  title: `Play ${target}`,
                  state: 'Completed',
                  risk: 'Low',
                  steps: [
                    { label: `Matched "${target}"`, done: true },
                    { label: `Opened ${isSpotify ? 'Spotify' : 'YouTube Music'}`, done: true },
                  ],
                  detail: `Search link ready: ${url}`,
                  pendingAction: {
                    kind: 'open_url',
                    title: `Open ${isSpotify ? 'Spotify' : 'YouTube Music'}`,
                    summary: target,
                    url,
                    confirmLabel: 'Open Player',
                  },
                };
                result = { ok: true, status: 'prepared', url };
              } else {
                actions.push({ type: 'prepare_action', payload: { actionType, target, content, channel } });
                result = { ok: true, status: 'completed', target };
              }
              break;
            }
            default:
              result = { ok: false, error: `Unknown tool: ${fc.name}` };
          }

          fnParts.push({
            functionResponse: {
              id: fc.id,
              name: fc.name,
              response: { result },
            },
          });
        }

        contents.push({ role: 'user', parts: fnParts });
        response = await ai.models.generateContent({
          model: 'gemini-4-hydrogen',
          contents,
          config: {
            systemInstruction,
            tools,
            toolConfig,
          },
        });
        extractChunks(response);
      }

      const reply = (response.text || '').trim() || 'Done.';
      res.json({
        reply,
        actions,
        task: pendingTask,
        sources: collectedSources,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Gemini request failed.';
      console.error('[api/assistant/chat]', err);
      res.status(500).json({ error: msg });
    }
  });

  if (process.env.NODE_ENV === 'production') {
    const distPath = path.join(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  } else {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true, hmr: false },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  }

  app.listen(PORT, HOST, () => {
    console.log(`JARVIS server listening on http://${HOST}:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
