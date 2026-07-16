export async function consumeAnthropicStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onText: (chunk: string) => void,
): Promise<number> {
  const decoder = new TextDecoder();
  let buffer = '';
  let totalChars = 0;

  const processFrame = (frame: string): boolean => {
    for (const line of frame.split('\n')) {
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (!payload || payload === '[DONE]') continue;
      try {
        const event = JSON.parse(payload) as {
          type?: string;
          delta?: { type?: string; text?: string };
        };
        if (event.type === 'content_block_delta' && event.delta?.type === 'text_delta') {
          const chunk = event.delta.text || '';
          if (chunk) {
            totalChars += chunk.length;
            onText(chunk);
          }
        }
        if (event.type === 'message_stop') return true;
      } catch {
        // Ignore keepalive and malformed non-terminal frames.
      }
    }
    return false;
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    buffer = buffer.replace(/\r\n/g, '\n');

    let separator = buffer.indexOf('\n\n');
    while (separator !== -1) {
      const frame = buffer.slice(0, separator);
      buffer = buffer.slice(separator + 2);
      if (processFrame(frame)) return totalChars;
      separator = buffer.indexOf('\n\n');
    }
  }

  buffer += decoder.decode();
  buffer = buffer.replace(/\r\n/g, '\n').trim();
  if (buffer && processFrame(buffer)) return totalChars;
  throw new Error('Anthropic stream ended before message_stop');
}
