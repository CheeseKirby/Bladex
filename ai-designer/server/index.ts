import { createApp } from './app';
import { isLlmConfigured } from './config/llmConfig';

const PORT = Number(process.env.PORT || 3004);
const HOST = process.env.HOST || '127.0.0.1';
const app = createApp();

app.listen(PORT, HOST, () => {
  console.log(`[AI Designer BFF] listening at http://${HOST}:${PORT}`);
  console.log(`[AI Designer BFF] LLM mode: ${isLlmConfigured() ? 'live' : 'mock'}`);
});
