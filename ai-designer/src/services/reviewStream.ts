import type { ChangeLogEntry, ReviewIssue, ReviewLogEntry } from '../types/plan';

export interface ReviewStreamResult {
  passes: boolean;
  issues: ReviewIssue[];
  reviewLog: ReviewLogEntry[];
  fixedContent: string;
  changeLog: ChangeLogEntry[];
}

export type ReviewProgressStage = 'preparing' | 'reviewing' | 'analyzing' | 'fixing' | 'complete';

export interface ReviewProgressEvent {
  message: string;
  stage?: ReviewProgressStage;
  round?: number;
  totalRounds?: number;
  errorCount?: number;
  warningCount?: number;
}

interface ReviewEvent extends Partial<ReviewProgressEvent> {
  type?: string;
  data?: Partial<ReviewStreamResult>;
}

export async function consumeReviewStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onProgress: (event: ReviewProgressEvent) => void,
): Promise<ReviewStreamResult> {
  const decoder = new TextDecoder();
  let buffer = '';

  const processFrame = (frame: string): ReviewStreamResult | null => {
    for (const line of frame.split('\n')) {
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (!payload) continue;

      let event: ReviewEvent;
      try {
        event = JSON.parse(payload) as ReviewEvent;
      } catch {
        continue;
      }

      if (event.type === 'progress') {
        if (typeof event.message === 'string') {
          onProgress({
            message: event.message,
            ...(typeof event.stage === 'string' ? { stage: event.stage } : {}),
            ...(typeof event.round === 'number' ? { round: event.round } : {}),
            ...(typeof event.totalRounds === 'number' ? { totalRounds: event.totalRounds } : {}),
            ...(typeof event.errorCount === 'number' ? { errorCount: event.errorCount } : {}),
            ...(typeof event.warningCount === 'number' ? { warningCount: event.warningCount } : {}),
          });
        }
        continue;
      }
      if (event.type === 'error') {
        throw new Error(event.message || 'Review stream failed');
      }
      if (event.type === 'done') {
        const data = event.data;
        if (!data || typeof data.passes !== 'boolean' || typeof data.fixedContent !== 'string') {
          throw new Error('Review done event has an invalid payload');
        }
        return {
          passes: data.passes,
          issues: Array.isArray(data.issues) ? data.issues : [],
          reviewLog: Array.isArray(data.reviewLog) ? data.reviewLog : [],
          fixedContent: data.fixedContent,
          changeLog: Array.isArray(data.changeLog) ? data.changeLog : [],
        };
      }
    }
    return null;
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, '\n');
    let separator = buffer.indexOf('\n\n');
    while (separator !== -1) {
      const result = processFrame(buffer.slice(0, separator));
      buffer = buffer.slice(separator + 2);
      if (result) return result;
      separator = buffer.indexOf('\n\n');
    }
  }

  buffer += decoder.decode();
  buffer = buffer.replace(/\r\n/g, '\n').trim();
  if (buffer) {
    const result = processFrame(buffer);
    if (result) return result;
  }
  throw new Error('Review stream ended before a done event');
}
