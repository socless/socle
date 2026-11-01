# Using the UI

The same single-file UI runs in the browser (the [standalone app](usage-standalone.md)
and the [Mill plugin](usage-mill.md)) and in the editors' panels
([VS Code](ide-vscode.md), [Neovim](ide-neovim.md)). This page covers what is
the same everywhere: moving around the schematic and learning it from the UI.

## Navigating the schematic

### Mouse

| Where | Gesture | What it does |
|---|---|---|
| Anywhere | Drag | Pan. The press can start on the background, a block, a wire, a pin or a label: a press that moves less than about 4 px is still a click. |
| Anywhere | Middle drag | Pan |
| Anywhere | Wheel | Zoom at the pointer |
| Anywhere | Shift + wheel | Scroll sideways |
| Anywhere | Ctrl + wheel | Scroll up and down |
| Anywhere | Trackpad: two-finger swipe, pinch | Pan, zoom |
| Anywhere | Rest | Show what is under the pointer (outline and card) |
| Anywhere | Click the background | Clear the selection, stop following a net |
| Anywhere | Back, Forward (mouse side buttons) | Over the schematic: back, forward |
| Hierarchy | ◎ on a row | Show it in its parent: open the parent module and fly to the instance (not in VS Code, whose side panel shows the hierarchy) |
| Blocks | Double-click | Open the module, or unfold a group |
| Blocks | Click the title | Inspect |
| Blocks | Breadcrumb | Go back up |
| Wires | Click | Select and follow the net (it stays lit across levels) |
| Pins | Click (the dot or the name) | Expand or collapse a bundle |
| Module's own ports | Click the + or − | Expand or collapse a bundle |
| Logic blocks | Click | Inspect a register or an operator |
| Logic blocks | Click the title | Inspect a block or a state machine |
| Logic blocks | Double-click | Open a state machine's diagram |

Browsers report a trackpad pinch as Ctrl + wheel. SoCLess tells the two apart by
the deltas: a pinch sends small fractional ones (under 10 px), a mouse notch
whole ones. A two-finger swipe without Ctrl (fractional deltas, or a sideways
one) pans; a whole-notch wheel zooms.

The minimap, the state diagram, text fields and scrollable lists keep their own
drag and scroll.

### Ports and bundles

A block shows the ports its module declares, in declaration order, whether
or not a wire ends on them; a port with no wire is drawn faint. A module's
`io` bundle is shown as its fields (`in`, `out`, `rf`), unless a wire
connects the whole `io`: then it is one row. Every other bundle, Vec or
Decoupled starts closed, as one row.

- **Sides:** inputs are on the left, outputs on the right. A Decoupled port is
  on the side its `valid` goes: a producer on the right, a consumer on the left.
  A Vec takes its first element's side. A bundle with fields both ways takes
  the side of most of its Decoupled channels (a tie goes to the first one, so
  a memory port or an AXI4 master is on the right), else of most of its fields.
- **Opening:** a click on a bundle's pin opens it in place: its fields appear
  below it, on the same side, each with its own direction arrow (a consumer's
  `ready` points out). A row never changes side. A click again closes it,
  whatever was opened under it; what was open under it is open again when
  you reopen it.
- **A Decoupled's `bits`** is one row until opened, with its fields summed up
  (`pc 32b, inst 32b`).
- **Wires** end on the deepest row shown: on `in` while it is closed, on
  `in.bits.a` once `in` and `bits` are open. A bundle wire is thick and carries
  its type once (`AXI4`); a signal wire is thin and carries its width when it
  has more than one bit.
- **The module's own ports** (the stubs at the edges, inputs on the left and
  outputs on the right) work the same way: one stub per port, opened in
  place with its + or −.

### Pins and the overview

- **Pins:** a signal pin is a triangle on the box edge pointing the way data
  flows; a bundle pin is a blue tab (pointing out: closed, pointing down: open);
  a Vec of signals is two stacked tabs. Clock and reset are the two small marks
  in a box's header.
- **Overview:** zoomed out so far that pin names would be too small to read
  (under about 7px), the schematic shows each box's name at a readable size
  and hides pin names; zoom in and the detail comes back. A title or a pin can
  still be clicked in the overview.
- **Logic blocks:** their pins are ordered within each side by where their
  wires go, inputs on the left and outputs on the right.

### Keyboard

The schematic's keys (pan, zoom, fit, Back and Forward, Esc on a followed net)
act when the focus is in the schematic, or on the page after your last click was
in the schematic; never from a text field, the hierarchy, the inspector, the
palette or the Surfer canvas, and not while the "?" card is open. `?`, `/` and
Ctrl K (⌘K) work from anywhere outside a text field (`/` not while the "?"
card or a state diagram is open).

| Key | What it does |
|---|---|
| ← ↑ → ↓ | Pan |
| + / - | Zoom |
| 0 or f | Fit to view |
| z | Zoom to the selection |
| Alt + ← / Alt + → | Back, forward |
| / | Focus the filter |
| Esc | Close a card, the palette or a diagram; stop following a net |
| Ctrl K (⌘K on a Mac) | Open the command palette and search |
| ↑ ↓, ↵ (in the palette) | Move in the results, go to the selected one |
| ? | Open the Mouse and keyboard card |

### What the pointer shows

- A hovered block gets a coloured outline, and its connected wires are drawn
  thicker in the same colour. Nothing else changes.
- Resting on a block, a wire or a pin for about 600 ms shows a card: what it is
  (its type and width, a wire's two ends), then what a click or a double-click
  does there.
- The cursor says what a press will do: a hand to pan, a grabbing hand while
  dragging, a pointer over what a click selects or toggles, and a zoom-in
  cursor over a block a double-click opens (a module, a state machine's
  diagram) or unfolds (a group of generator copies).

### Help in the UI

- **The hint bar**, under the schematic, says in one line what the mouse does
  on what is under it (off any target: drag, wheel, double-click). Its ×
  button hides it; Settings > Hint bar brings it back. In the browser the choice is
  remembered; in an editor's panel it lasts until the panel reloads.
- **The "?" card** (the `?` key, the `?` button at the top right, the hint
  bar's "? all shortcuts", or **Mouse and keyboard** in the command palette)
  lists every mouse action per element and every key, as in the tables above.
- **First-time tips.** On the first visit in the browser, four short tips show
  one at a time; Skip or the last one closes them for good. They do not show in
  an editor's panel, VS Code's or the language server's browser panel (its page
  changes with each server port and would forget that you closed them).

### History, fly to and zoom to the selection

- **Back and forward** walk the views you went through, like a browser: each
  drill in or out, fit, fly to and zoom to the selection, and a pan or zoom
  once the view has rested for about 600 ms. A view is the drill path with its
  pan and zoom; expansions and folds are not part of it. Use Alt + ← and
  Alt + →, the mouse's side buttons, or the **Back** and **Forward** commands of
  the palette. In VS Code the keys and the side buttons may be kept by the
  editor, so the palette commands are the sure way there.
- **Fly to.** Choosing an instance in the command palette search pans and
  zooms smoothly to its box (a jump when the system asks for reduced motion).
  The hierarchy's ◎ ("Show in parent") does the same from its parent module
  (in the browser and the Neovim panel; VS Code shows the hierarchy in its
  own side panel).
- **Zoom to the selection.** `z` (or **Zoom to selection** in the palette)
  frames the selected block or wire.

### Follow a net

A click on a wire selects it and follows its net: the net shows in its own
colour (magenta in the default themes) and stays lit while you drill in and
out. Drilling into an instance lights the child's wires on the port the net
enters by; going up lights the parent's wire into that port. It also survives
Back and Forward. Esc or a click on the background stops following.

A net that crosses a folded generator group (`<name> × <n>`) may not light
inside it at the folded level: unfold the group to follow it there.

### Why is the schematic dimmed?

Three things dim part of the schematic:

- **The class under your editor's cursor** (with an editor): its boxes stay lit
  and the rest dims, and a **focus** chip next to the filter box names the
  class. Its **×** stops the focus until the cursor is on another class; a
  selection shows everything while it lasts. Turn it off for good with
  **Follow the editor's cursor** in the settings popover, or the editor's
  `socless.followCursor` setting (Neovim: `follow_cursor`).
- **The filter box** (top left): the boxes matching its text and their
  neighbours stay lit. Esc or its **×** clears it.
- **A selection**: the wires not connected to it fade (boxes stay). Esc or a
  click on the background clears it.

### Waveform colours

When Surfer shows a trace (the Waveform tab, or VS Code's Surfer tab), a small
**● colours** chip sits beside its status. Hover it, focus it, or click it to
keep it open (Esc closes it): it says what each colour of the signal list
means, inputs Blue, outputs Green, registers Orange and wires Gray, and how the
groups are built: one group per port, its bundles nested, then Registers, then
Wires. With the colour-by-direction setting off (`socless.wavesColorByDirection`),
it says that the ports are all Blue. The names are Surfer's colours; Surfer's
theme sets their exact shade.
