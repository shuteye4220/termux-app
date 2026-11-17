export const manifest = {
  id: 'termux-punctuation',
  name: 'Termux punctuation',
  version: '1.0',
  author: 'shuteye4220',
  description:
    'Reads punctuation and symbols in Termux at a level of its own, whatever Backtalk\'s ' +
    'punctuation setting is in other apps. Uses the names Backtalk uses, and counts long runs ' +
    'of the same symbol, so a line of dashes is "40 Dash".',
  minApiVersion: 1,
  testedApiVersion: 4,
  apps: ['com.termux'],
  permissions: ['speech'],
  settings: [
    {
      key: 'level',
      type: 'list',
      title: 'Punctuation in Termux',
      default: 'all',
      options: [
        { value: 'all', label: 'All' },
        { value: 'most', label: 'Most' },
        { value: 'off', label: 'Same as Backtalk' },
      ],
    },
    {
      key: 'countRepeats',
      type: 'switch',
      title: 'Count repeated symbols',
      summary: 'Says "40 Dash" for 4 or more of the same symbol in a row',
      default: true,
    },
  ],
};

// Backtalk's names for these symbols, and the level that names them. Plus is added, as Backtalk
// leaves it to the speech engine, which skips it in some places.
const MOST = {
  '&': 'Ampersand',
  '<': 'Less than sign',
  '>': 'Greater than sign',
  '*': 'Asterisk',
  '@': 'At',
  '\\': 'Backslash',
  '^': 'Caret',
  ':': 'Colon',
  '{': 'Left curly bracket',
  '}': 'Right curly bracket',
  '`': 'Grave accent',
  '(': 'Left paren',
  ')': 'Right paren',
  '%': 'Percent',
  '"': 'Quote',
  ';': 'Semicolon',
  '/': 'Slash',
  '[': 'Left square bracket',
  ']': 'Right square bracket',
  '_': 'Underscore',
  '|': 'Vertical line',
  '~': 'Tilde',
  '=': 'Equal sign',
  '+': 'Plus',
};
const ALL = {
  ...MOST,
  "'": 'Apostrophe',
  ',': 'Comma',
  '!': 'Exclamation mark',
  '-': 'Dash',
  '.': 'Period',
  '#': 'Pound',
  '$': 'Dollar sign',
  '?': 'Question mark',
};

/** A run of this many of the same symbol or more is counted rather than named each time. */
const COUNT_FROM = 4;

const escape = (symbol) => symbol.replace(/[\\\]^-]/g, '\\$&');
const patternFor = (names) =>
  new RegExp('([' + Object.keys(names).map(escape).join('') + '])\\1*', 'g');
const PATTERNS = { all: patternFor(ALL), most: patternFor(MOST) };
const NAMES = { all: ALL, most: MOST };

let level = backtalk.settings.get('level') ?? 'all';
let countRepeats = backtalk.settings.get('countRepeats') ?? true;

backtalk.onSettingChange((key, value) => {
  if (key === 'level') level = value;
  if (key === 'countRepeats') countRepeats = value;
});

/** Says each symbol by name. Returns nothing when there was nothing to name, to leave speech alone. */
function namePunctuation(text, level, countRepeats) {
  const pattern = PATTERNS[level];
  if (!pattern || typeof text !== 'string' || text === '') return undefined;
  const names = NAMES[level];
  let changed = false;
  const result = text.replace(pattern, (run, symbol) => {
    changed = true;
    const name = names[symbol];
    if (run.length >= COUNT_FROM && countRepeats) return ` ${run.length} ${name} `;
    return (' ' + name + ' ').repeat(run.length);
  });
  if (!changed) return undefined;
  // Tidy the spaces added around names, but keep line breaks.
  return result.replace(/[ \t]{2,}/g, ' ').replace(/ ?\n ?/g, '\n').trim();
}

backtalk.onSpeech((text) => namePunctuation(text, level, countRepeats));
