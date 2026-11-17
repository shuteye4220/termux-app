// Runs termux-punctuation.js against a stand-in for Backtalk's script API.
// Run from the repository root: node backtalk-scripts/test/termux-punctuation.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const settings = { level: 'all', countRepeats: true };
let speechHook, settingHook;
globalThis.backtalk = {
  settings: { get: (key) => settings[key] },
  onSettingChange: (fn) => { settingHook = fn; return () => {}; },
  onSpeech: (fn) => { speechHook = fn; return () => {}; },
};
const scriptPath = fileURLToPath(new URL('../termux-punctuation.js', import.meta.url));
// Imported as a module from its text, as Backtalk runs it, whatever Node makes of a .js file.
const { manifest } = await import(
  'data:text/javascript,' + encodeURIComponent(readFileSync(scriptPath, 'utf8')));
const say = (text) => speechHook(text);
const change = (key, value) => { settings[key] = value; settingHook(key, value); };

const cases = [];
const test = (name, fn) => cases.push([name, fn]);

test('manifest is what Backtalk needs', () => {
  assert.equal(manifest.id, 'termux-punctuation');
  assert.deepEqual(manifest.apps, ['com.termux']);
  assert.deepEqual(manifest.permissions, ['speech']);
  // Backtalk reads the manifest without running code, so it must be plain values.
  const source = readFileSync(scriptPath, 'utf8');
  const block = source.slice(source.indexOf('export const manifest'), source.indexOf('\n};\n'));
  assert.ok(!/\$\{|\(\)|=>/.test(block), 'manifest has no expressions');
});
test('command line', () => assert.equal(say('$ ls -la ~/.ssh'), 'Dollar sign ls Dash la Tilde Slash Period ssh'));
test('address and path', () => assert.equal(say('root@10.0.0.1:/etc'), 'root At 10 Period 0 Period 0 Period 1 Colon Slash etc'));
test('plain words are left alone', () => assert.equal(say('Reading package lists'), undefined));
test('empty and missing text', () => { assert.equal(say(''), undefined); assert.equal(say(undefined), undefined); });
test('short runs name each symbol', () => assert.equal(say('a && b'), 'a Ampersand Ampersand b'));
test('long runs are counted', () => assert.equal(say('----------------'), '16 Dash'));
test('runs not counted when turned off', () => {
  change('countRepeats', false);
  try { assert.equal(say('===='), 'Equal sign Equal sign Equal sign Equal sign'); }
  finally { change('countRepeats', true); }
});
test('line breaks are kept', () => assert.equal(say('a.\nb!'), 'a Period\nb Exclamation mark'));
test('every escaped symbol works', () => assert.equal(say('[a]\\b^c-d'), 'Left square bracket a Right square bracket Backslash b Caret c Dash d'));
test('plus and pipes', () => assert.equal(say('x+1 | grep y'), 'x Plus 1 Vertical line grep y'));
test('most leaves the All-only symbols to Backtalk', () => {
  change('level', 'most');
  try { assert.equal(say("it's 5-6, ok./x"), "it's 5-6, ok. Slash x"); }
  finally { change('level', 'all'); }
});
test('same as Backtalk changes nothing', () => {
  change('level', 'off');
  try { assert.equal(say('a/b'), undefined); }
  finally { change('level', 'all'); }
});
test('a long burst is fast', () => {
  const line = 'drwxr-xr-x 2 user user 4096 Nov 16 21:00 /home/user/.config/some-dir_name\n';
  const text = line.repeat(55).slice(0, 4000);
  const start = process.hrtime.bigint();
  for (let i = 0; i < 100; i++) say(text);
  const ms = Number(process.hrtime.bigint() - start) / 1e6 / 100;
  assert.ok(ms < 5, `took ${ms.toFixed(2)} ms`);
  console.log(`    4000 characters: ${ms.toFixed(2)} ms each`);
});

let failed = 0;
for (const [name, fn] of cases) {
  try { fn(); console.log('PASS', name); } catch (error) { failed++; console.log('FAIL', name, '-', error.message); }
}
console.log(`${cases.length} tests, ${failed} failed`);
process.exit(failed ? 1 : 0);
