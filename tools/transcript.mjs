// Turns transcription output into the format Herk Edit Kit's cutting tools expect:
// { text, audio_duration_secs, words: [{ text, start, end }] }.

// Groq verbose_json -> [{ text, start, end }]
export const fromGroq = (body) => (body.words || []).map((w) => ({ text: w.word.trim(), start: w.start, end: w.end }));

// Drops empty or backwards words and sorts by start: Groq can return a word out of order.
export const buildTranscript = (words, duration) => {
  const clean = words.filter((w) => w.text && w.end >= w.start).sort((a, b) => a.start - b.start);
  return { text: clean.map((w) => w.text).join(' '), audio_duration_secs: duration, words: clean };
};
