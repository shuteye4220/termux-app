# Backtalk scripts for Termux

Scripts for the [Backtalk](https://github.com/trypsynth/backtalk) screen reader that make Termux easier to use.

| Script | What it does |
| --- | --- |
| [Termux punctuation](termux-punctuation.js) | Reads punctuation and symbols in Termux at a level of its own (All, Most, or the same as Backtalk), whatever Backtalk's punctuation setting is in other apps. Long runs of one symbol are counted, so a line of dashes is "40 Dash". Changes speech only, not braille. |

## Install

Add this folder as a script source in Backtalk, so it can install and update the scripts:

1. In Backtalk settings, go to **Apps** > **Scripts** > **Script sources**.
2. Choose **Add source**, and enter `github.com/shuteye4220/termux-app/tree/screen-reader-lines/backtalk-scripts`.
3. Open the source, and choose a script to install.

Or download a script's file and share it to Backtalk, or choose **Import script** on the Scripts screen.

## Tests

From the repository root, with Node.js 18 or newer:

```sh
node backtalk-scripts/test/termux-punctuation.test.mjs
```
