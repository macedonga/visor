const http = require('http');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { randomUUID } = require('crypto');

const PORT = Number(process.env.BRIDGE_PORT) || 8080;
const MODEL = process.env.CLAUDE_MODEL || 'haiku';
// Optional: other apps on the phone can reach a loopback port, so set this and Visor's API key to the same secret.
const TOKEN = process.env.BRIDGE_TOKEN || '';
const TIMEOUT_MS = 40000;
const IDLE_MS = (Number(process.env.BRIDGE_IDLE_MIN) || 10) * 60000;
const MAX_TURNS = 40;
const MAX_BODY = 20e6;
const MAX_TOOL_ROUNDS = 4;
const WORKDIR = path.join(os.homedir(), '.visor-bridge');
const SYSTEM = 'You are a voice assistant on smart glasses. Your answer is spoken aloud: reply in one or two short ' +
  'sentences, plain words, no markdown, lists or emoji. If an image comes with the question, it is what the wearer sees. ' +
  'The question comes from speech recognition, which can clip or mishear the first word (for example "and times two" ' +
  'arrives as "n times two"): when it only makes sense as a follow-up to your last answer, read it that way. ' +
  'The glasses keep listening after every answer. Judge by meaning, not by exact words: when the wearer is closing the ' +
  'conversation (thanks, thx, aight, cool that is it, nah I am good, nvm, bye, or anything else that dismisses you) and ' +
  'is not asking anything new, answer in a few words and end with the word END_CONVERSATION. Do not end it when they ' +
  'are still asking or when your answer asked them something.';
const END_TOOL = 'end_conversation';
const END = /\s*END_CONVERSATION\W*/gi;
const TOOL_CALL = /TOOL_CALL\s*(\{[\s\S]*\})/;
const FALLBACK = "Sorry, I couldn't process that request.";

// One long-lived Claude Code process answers every question: starting `claude -p` per request is most of the wait.
let worker = null;
// Tool call ids the bridge handed to Visor, so a result can be named when it comes back.
const calls = new Map();

function start() {
  fs.mkdirSync(WORKDIR, { recursive: true });
  const child = spawn('claude', [
    '-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose',
    '--model', MODEL, '--system-prompt', SYSTEM,
    '--tools', '', '--strict-mcp-config', '--disable-slash-commands', '--setting-sources', '', '--no-session-persistence',
  ], { cwd: WORKDIR, stdio: ['pipe', 'pipe', 'pipe'] });
  const w = { child, buf: '', err: '', pending: null, turns: 0, last: Date.now() };
  const lost = error => {
    if (worker === w) worker = null;
    if (w.pending) w.pending.reject(error);
  };
  child.stdout.on('data', chunk => {
    w.buf += chunk;
    let i;
    while ((i = w.buf.indexOf('\n')) >= 0) {
      const line = w.buf.slice(0, i);
      w.buf = w.buf.slice(i + 1);
      let event;
      try { event = JSON.parse(line); } catch (e) { continue; }
      if (event.type === 'result' && w.pending) w.pending.resolve(event);
    }
  });
  child.stderr.on('data', chunk => { w.err = (w.err + chunk).slice(-500); });
  child.on('error', lost);
  child.on('close', () => lost(new Error(w.err || 'claude exited')));
  child.stdin.on('error', () => {});
  return w;
}

// Drops the conversation; the next one starts on a process that is already up.
function reset() {
  const old = worker;
  worker = start();
  if (old) {
    old.pending = null;
    old.child.kill('SIGTERM');
  }
}

let queue = Promise.resolve();
function ask(content, tools) {
  const run = queue.then(() => send(content, tools));
  queue = run.catch(() => {});
  return run;
}

function send(content, tools) {
  return new Promise((resolve, reject) => {
    if (worker && worker.turns >= MAX_TURNS) reset();
    const w = worker || (worker = start());
    // The warm process keeps the list it was given, so it is sent again only when it changes.
    if (tools && w.catalog !== tools) {
      content = [{ type: 'text', text: tools }, ...content];
      w.catalog = tools;
    }
    const settle = (fn, value) => () => { clearTimeout(timer); w.pending = null; fn(value); };
    const timer = setTimeout(() => { reset(); reject(new Error('timeout')); }, TIMEOUT_MS);
    w.pending = {
      resolve: event => {
        w.last = Date.now();
        if (event.is_error || typeof event.result !== 'string' || !event.result.trim()) {
          reset();
          settle(reject, new Error(event.result || 'Claude Code returned no text'))();
        } else {
          settle(resolve, event.result.trim())();
        }
      },
      reject: error => settle(reject, error)(),
    };
    w.turns++;
    w.child.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content } }) + '\n');
  });
}

// OpenAI message content to Claude's blocks; images arrive as data URLs.
function blocks(content) {
  if (typeof content === 'string') return [{ type: 'text', text: content }];
  const out = [];
  for (const part of content || []) {
    if (part.type === 'text' && part.text) {
      out.push({ type: 'text', text: part.text });
    } else if (part.type === 'image_url') {
      const url = (part.image_url && part.image_url.url) || '';
      const cut = url.indexOf(';base64,');
      if (url.startsWith('data:image/') && cut > 0) {
        out.push({ type: 'image', source: { type: 'base64', media_type: url.slice(5, cut), data: url.slice(cut + 8) } });
      }
    }
  }
  return out;
}

// Visor's tools for this question, one line each. The model answers with a TOOL_CALL line to use one.
function catalog(tools) {
  const lines = tools.map(t => {
    const fn = t.function;
    const props = Object.keys((fn.parameters && fn.parameters.properties) || {});
    return `- ${fn.name}(${props.join(', ')}): ${(fn.description || '').slice(0, 160)}`;
  });
  return 'Tools you can use:\n' + lines.join('\n') + '\n' +
    'To use one, reply with exactly one line and nothing else: TOOL_CALL {"name": "<tool>", "arguments": {...}}. ' +
    'Its result comes back as a TOOL_RESULT line; then answer the wearer in one or two short sentences. ' +
    'Use a tool only when the wearer asks for an action or a fact that needs it.';
}

// Tool results since the wearer's last question: a model that keeps calling tools is stopped after MAX_TOOL_ROUNDS.
function toolRounds(messages) {
  let n = 0;
  for (let i = messages.length - 1; i >= 0 && messages[i].role !== 'user'; i--) if (messages[i].role === 'tool') n++;
  return n;
}

// The tool call in an answer, if it names one of the tools offered; null otherwise.
function parseCall(answer, tools) {
  const m = answer.match(TOOL_CALL);
  if (!m) return null;
  try {
    const call = JSON.parse(m[1]);
    return tools.some(t => t.function.name === call.name) ? { name: call.name, args: call.arguments || {} } : null;
  } catch (e) {
    return null;
  }
}

function completion(res, message, finish, model) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({
    id: 'chatcmpl-termux-bridge',
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: model || 'claude-code',
    choices: [{ index: 0, message, finish_reason: finish }],
  }));
}

// ends: Visor offered end_conversation and the wearer is done, so answer with that call; Visor then asks once more for the words.
function say(res, text, model, ends) {
  const message = { role: 'assistant', content: text };
  if (ends) message.tool_calls = [{ id: 'call_end', type: 'function', function: { name: END_TOOL, arguments: '{}' } }];
  completion(res, message, ends ? 'tool_calls' : 'stop', model);
}

function callReply(res, id, call, model) {
  calls.set(id, call.name);
  if (calls.size > 500) calls.clear();
  const tool_calls = [{ id, type: 'function', function: { name: call.name, arguments: JSON.stringify(call.args) } }];
  completion(res, { role: 'assistant', content: null, tool_calls }, 'tool_calls', model);
}

function fail(res, status, message) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: message }));
}

const server = http.createServer((req, res) => {
  if (TOKEN && req.headers.authorization !== `Bearer ${TOKEN}`) return fail(res, 401, 'missing or invalid bearer token');

  if (req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({
      object: 'list',
      data: [{ id: 'claude-code', object: 'model', created: 1700000000, owned_by: 'local' }],
    }));
  }

  if (req.method !== 'POST' || !req.url.includes('chat/completions')) return fail(res, 404, `Route not found: ${req.url}`);

  let body = '';
  req.on('data', chunk => {
    body += chunk;
    if (body.length > MAX_BODY) req.destroy();
  });
  req.on('end', async () => {
    let request;
    let messages;
    let content;
    let isResult = false;
    try {
      request = JSON.parse(body);
      messages = request.messages || [];
      const last = messages[messages.length - 1] || {};
      // The round after end_conversation: nothing to ask Claude, the words already went out with the call.
      if (last.role === 'tool' && last.tool_call_id === 'call_end') {
        const before = messages.slice().reverse().find(m => m.role === 'assistant');
        return say(res, (before && typeof before.content === 'string' && before.content) || 'Okay.', request.model);
      }
      if (last.role === 'tool') {
        // A tool's result: the warm process already holds the question, so it only needs the result.
        isResult = true;
        const name = calls.get(last.tool_call_id) || 'tool';
        const result = typeof last.content === 'string' ? last.content : JSON.stringify(last.content);
        content = [{ type: 'text', text: `TOOL_RESULT ${name}: ${result}` }];
      } else {
        const users = messages.filter(m => m.role === 'user');
        content = blocks(users.length ? users[users.length - 1].content : '');
        if (!content.length) throw new Error('No user message found.');
      }
    } catch (error) {
      return fail(res, 400, 'Invalid payload');
    }

    const spoken = content.filter(b => b.type === 'text').map(b => b.text).join(' ').trim().toLowerCase();
    if (!isResult && (spoken === '/clear' || spoken === 'clear session')) {
      reset();
      return say(res, 'Session cleared. Starting a fresh conversation.', request.model);
    }

    const tools = (request.tools || []).filter(t => t.function && t.function.name !== END_TOOL);
    const offered = (request.tools || []).some(t => t.function && t.function.name === END_TOOL);
    const useTools = tools.length > 0 && toolRounds(messages) < MAX_TOOL_ROUNDS;
    const list = useTools ? catalog(tools) : null;

    const started = Date.now();
    try {
      const answer = await ask(content, list);
      const done = new RegExp(END.source, 'i').test(answer);
      const call = useTools ? parseCall(answer, tools) : null;
      console.log(`[${new Date().toISOString()}] answered in ${Date.now() - started} ms` +
        `${call ? ` (tool ${call.name})` : ''}${done ? ' (conversation ends)' : ''}`);
      if (call) return callReply(res, 'call_' + randomUUID().slice(0, 12), call, request.model);
      const text = answer.replace(TOOL_CALL, ' ').replace(END, ' ').trim();
      say(res, text || 'Okay.', request.model, done && offered);
    } catch (error) {
      console.error('Claude error:', error.message);
      say(res, FALLBACK, request.model);
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  worker = start();
  setInterval(() => { if (worker && worker.turns && Date.now() - worker.last > IDLE_MS) reset(); }, 60000).unref();
  console.log(`Claude voice bridge on http://127.0.0.1:${PORT} (model ${MODEL})`);
});

process.on('exit', () => { if (worker) worker.child.kill('SIGTERM'); });
process.on('SIGINT', () => process.exit(0));
process.on('SIGTERM', () => process.exit(0));
