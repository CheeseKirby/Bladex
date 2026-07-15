import express, { type NextFunction, type Request, type Response } from 'express';
import cors from 'cors';

import { isLlmConfigured } from './config/llmConfig';
import { configRouter } from './routes/config';
import { llmRouter } from './routes/llm';
import { plansRouter } from './routes/plans';
import { transmissionRouter } from './routes/transmission';

export function createApp() {
  const app = express();
  const allowedOrigins = (process.env.FRONTEND_ORIGIN || 'http://localhost:3005')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);

  app.use(cors({
    origin: (origin, callback) => {
      if (!origin || allowedOrigins.includes(origin)) return callback(null, true);
      console.warn(`[BFF] CORS blocked origin: ${origin}`);
      return callback(null, false);
    },
    credentials: false,
  }));
  app.use(express.json({ limit: process.env.BFF_JSON_LIMIT || '2mb' }));

  app.use((req, _res, next) => {
    if (!req.path.startsWith('/api/health')) console.log(`[BFF] ${req.method} ${req.path}`);
    next();
  });

  app.use('/api/llm', llmRouter);
  app.use('/api/plans', plansRouter);
  app.use('/api/transmission', transmissionRouter);
  app.use('/api/config', configRouter);

  app.get('/', (_req, res) => {
    res.status(200).json({
      service: 'ai-designer-bff',
      frontend: allowedOrigins[0] || 'http://localhost:3005',
      llmMode: isLlmConfigured() ? 'live' : 'mock',
      endpoints: ['/api/llm', '/api/plans', '/api/transmission', '/api/config', '/api/health'],
    });
  });

  app.get('/api/health', (_req, res) => {
    res.json({ status: 'ok', timestamp: new Date().toISOString() });
  });

  app.use((err: Error & { status?: number }, _req: Request, res: Response, _next: NextFunction) => {
    if (res.headersSent) return;
    const status = err.status && err.status >= 400 && err.status <= 599 ? err.status : 500;
    if (status >= 500) console.error('[BFF] Unhandled error:', err.message);
    else console.warn(`[BFF] Request rejected (${status}): ${err.message}`);
    const message = status === 413
      ? 'Request body too large'
      : process.env.NODE_ENV === 'production' ? 'Internal server error' : err.message;
    res.status(status).json({ success: false, msg: message });
  });

  return app;
}
