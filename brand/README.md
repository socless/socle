# Socle brand

The full charter, with the explored variants, is the "Charte Socle" page; this
folder holds the files it ships. A socle is the pedestal a sculpture stands on:
Chisel carves the hardware, Socle shows it. The mark is a chip on a three-tier
pedestal (cornice, die, plinth) with a clock engraved in the die.

## Files

| File | Use |
|---|---|
| `socle-logo.svg`, `socle-logo-sombre.svg` | Horizontal logo, on light and dark backgrounds |
| `socle-logo-vertical.svg`, `-sombre.svg` | Vertical logo, square formats and covers |
| `socle-logo-noir.svg` | One colour (engraving, print, stamps) |
| `socle-symbole.svg`, `-sombre.svg` | The symbol alone |
| `socle-tuile.svg`, `.png` | App tile (VS Code marketplace, releases), 256 px render |
| `socle-favicon.svg`, `-32.png` | Simplified favicon (no pins), under 24 px |
| `socle-mono.svg` | 24 px line icon in `currentColor` (VS Code activity bar) |
| `palette.json` | The colours below |

The SVGs are vectorized and depend on no font.

## Colours

| Name | Hex | Role |
|---|---|---|
| Ardoise | `#16202B` | Text, the pedestal's tiers, dark background |
| Granit | `#4D5660` | Secondary text, captions |
| Pierre | `#EDF0F4` | Light page background, tiers on dark |
| Calcaire | `#F9FAFC` | Cards and panels on pierre |
| Or | `#E6AC3D` | The chip on dark backgrounds, rare accents |
| Laiton | `#BE810A` | The chip on light backgrounds; never text |
| Bronze | `#8F5D04` | Accent text on light backgrounds |
| Signal | `#0074CA` | Links and actions: the app's accent |

## Type

- **Titles and the wordmark:** Archivo at width 125 and weight 600. The wordmark is outlined, so it needs no font.
- **Text and code:** IBM Plex Sans and IBM Plex Mono, which the app already ships. The schematic stays in Plex.

## Rules

- **Minimum sizes:**
  - full symbol: 24 px; use the favicon below that;
  - horizontal logo: 96 px wide;
  - vertical logo: 48 px.
- **Clear space:** the height of the die (20% of the symbol's height) on every side.
- **Never:**
  - stretch, slant or recolour the logo;
  - drop the pedestal;
  - reset the word in another font;
  - put the light logo on a dark background.
- **Writing:**
  - "Socle" with a capital in a sentence; `socle` in the logo, commands and package names;
  - never "SOCLE" or "SoCLe";
  - one sculpture pun at most, in a tagline: "Chisel carves the hardware. Socle shows it."
