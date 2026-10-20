import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildTranscript, fromGroq } from '../tools/transcript.mjs';

test('fromGroq trims words and keeps their times', () => {
  const body = { words: [{ word: ' Bom, ', start: 0, end: 0.4 }] };
  assert.deepEqual(fromGroq(body), [{ text: 'Bom,', start: 0, end: 0.4 }]);
});

test('fromGroq returns no words when Groq sends none', () => {
  assert.deepEqual(fromGroq({}), []);
});

test('buildTranscript sorts words that come back out of order', () => {
  // Seen in a real Groq response: "Bom." at 25.24 s arrived after "isso." at 25.48 s.
  const words = [
    { text: 'Só', start: 25.3, end: 25.48 },
    { text: 'isso.', start: 25.48, end: 25.8 },
    { text: 'Bom.', start: 25.24, end: 25.3 },
  ];
  const t = buildTranscript(words, 27.1);
  assert.deepEqual(t.words.map((w) => w.text), ['Bom.', 'Só', 'isso.']);
  assert.equal(t.text, 'Bom. Só isso.');
  assert.equal(t.audio_duration_secs, 27.1);
});

test('buildTranscript drops empty and backwards words', () => {
  const words = [
    { text: '', start: 1, end: 2 },
    { text: 'back', start: 3, end: 2 },
    { text: 'ok', start: 4, end: 5 },
  ];
  assert.deepEqual(buildTranscript(words, 5).words, [{ text: 'ok', start: 4, end: 5 }]);
});
