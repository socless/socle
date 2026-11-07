# README screenshots

Retakes the images of the main README, dark and light, into `docs/media/`:

```bash
tools/screenshots/run.sh
```

It builds the UI (`socle-ui.dist`) and the view-mode designs of the committed
captures (`example.socleCapturedPipeline`, `example.socleCapturedFsm`), serves
them, and drives the UI with Playwright (`shots.mjs`):

- `schematic-*.png`: RVPipeline with `exec` selected, its line lit in the Source tab;
- `inspector-*.png`: inside `exec`, the inspector on the module;
- `fsm-*.png`: TrafficFsm's state diagram;
- `hero-*.gif`: select decode then exec, open exec, open the FSM diagram, from
  lossless screenshots taken in a loop (ffmpeg, at most 3 MB).

`check.sh` then checks every file exists, the PNGs are 1500 px wide and the GIFs
at most 3 MB. A page error, a `console.error` or a UI in the wrong theme fails
the run.

Needs node, ffmpeg, python3 and a Chromium: `CHROMIUM=/path`, a system
`chromium`, or Playwright's managed one (`npx playwright install chromium`).
Retake after a UI change that shows in these views, and commit the new images.
