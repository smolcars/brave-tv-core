---
name: "Brave TV phone remote — After hours"
description: "Deep blue controls and soft teal feedback for the bundled phone companion."
colors:
  ground: "#111c25"
  surface: "#1a2a35"
  pad: "#22333e"
  accent: "#9ddbcc"
  ink: "#f1f5f3"
  muted: "#b9c9cf"
  line: "#3b4e58"
  warning: "#e9bf7e"
  control-hover: "#2a3d49"
  control-active: "#334d58"
  disabled-ink: "#91a4ae"
  accent-hover: "#b1e5d8"
  accent-active: "#88c4b5"
  selected-surface: "#1d3039"
  pad-active: "#2c464f"
typography:
  title:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "25px"
    fontWeight: 650
    lineHeight: 1.2
    letterSpacing: "-0.025em"
  headline:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "-0.015em"
  page-title:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "19px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "-0.015em"
  action:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "17px"
    fontWeight: 620
    lineHeight: 1.4
    letterSpacing: "normal"
  body:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
  label:
    fontFamily: "ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "normal"
rounded:
  control: "12px"
  pad: "16px"
spacing:
  step-4: "4px"
  step-8: "8px"
  step-10: "10px"
  step-12: "12px"
  step-16: "16px"
  step-20: "20px"
  step-22: "22px"
  step-32: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.ground}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-primary-active:
    backgroundColor: "{colors.accent-active}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
  button-secondary-hover:
    backgroundColor: "{colors.control-hover}"
  button-secondary-active:
    backgroundColor: "{colors.control-active}"
  button-disabled:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.disabled-ink}"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "8px"
    typography: "{typography.label}"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "12px"
  click:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.ground}"
    rounded: "{rounded.control}"
    typography: "{typography.action}"
    width: "100%"
  touchpad:
    backgroundColor: "{colors.pad}"
    textColor: "{colors.muted}"
    rounded: "{rounded.pad}"
    padding: "28px 12px"
    typography: "{typography.label}"
  task-tab:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    typography: "{typography.label}"
    padding: "10px 14px"
  active-task-tab:
    backgroundColor: "{colors.selected-surface}"
    textColor: "{colors.accent}"
  active-tab-row:
    backgroundColor: "{colors.selected-surface}"
    textColor: "{colors.accent}"
    rounded: "{rounded.control}"
---
# Design System: Brave TV phone remote — After hours

## Overview

**Creative North Star: "After hours"**

After hours is a calm control surface: deep blue surrounds a slate blue touchpad, warm white carries page identity, and soft teal marks the main action and current task. Compact system sans typography and flat surfaces support repeated phone use.

This system applies only to the bundled phone-browser companion in `android/java/brave-res/raw/tv_remote.html`, `tv_remote_css.css`, and `tv_remote_js.js`. It does not define the native Android TV browser or upstream Brave applications. The implementation uses authored inline SVG, bundled CSS/JavaScript, and system fonts; it ships no raster assets or external asset requests.

**Key Characteristics:**

- Deep blue ground with restrained soft teal feedback.
- Spacious touchpad and one prominent Click control.
- Compact, separate Remote, Type, and Tabs tasks.
- Flat surfaces, quiet dividers, visible focus, and explicit recovery states.

## Colors

Deep blue and slate neutrals establish the evening atmosphere; soft teal confirms control states and amber identifies interruptions. The frontmatter owns exact source values.

### Primary

- **Soft teal (`accent`):** Click, Pair, Go, New tab, current task and selected tab, connected status, pointer icon, caret, text selection, and focus.
- **Teal hover / pressed (`accent-hover`, `accent-active`):** immediate primary-button response.

### Secondary

- **Warm amber (`warning`):** paused status and the persistent development/unencrypted notice.

### Neutral

- **Deep blue (`ground`):** page ground and primary-control text.
- **Blue surface (`surface`):** secondary controls and text fields.
- **Slate pad (`pad`):** large pointer gesture surface.
- **Warm white (`ink`):** headings, main copy, and control labels.
- **Mist blue (`muted`):** hints, URL, supporting task labels, and quiet icons.
- **Quiet slate (`line`):** field strokes, section dividers, and scrollbar foreground.
- **Control hover / pressed (`control-hover`, `control-active`):** ordinary secondary and quiet-button response.
- **Disabled slate (`disabled-ink`):** unavailable controls and fields.
- **Selected blue (`selected-surface`):** current task and selected normal-tab row.
- **Pressed pad (`pad-active`):** restrained teal-tinted touchpad response.

**The Teal Feedback Rule.** Use soft teal for primary actions, current selections, connection feedback, pointer icons, carets, and keyboard focus. Keep explanatory copy muted and interruptions amber.

## Typography

**Body and interface font:** the frontmatter's local system sans stack. There is no separate display face, webfont, or monospace family.

The typography is compact, direct, and sentence case. Headings use weight and slightly tightened tracking rather than large display treatment.

### Hierarchy

- **Title:** application heading. Connection headings share its size but use headline weight and tracking.
- **Headline:** Type and Tabs headings and explanatory state headings.
- **Page title:** current TV page identity, truncated to one line beside the address shortcut.
- **Action:** prominent Click label. Other primary controls inherit body size with weight (620).
- **Body:** instructions, touchpad label, text entry, and ordinary buttons.
- **Label:** status, URL, field labels, supporting hints, navigation, and compact controls.

Pairing digits use tabular numerals and tracking (0.14em), a field-specific scanning aid rather than a second family.

**The Compact Hierarchy Rule.** Use sentence case and the observed small type hierarchy; page identity and task headings provide hierarchy without decorative labels.

## Layout

A centered single column is capped at (480px) and fills at least the small viewport height (`100svh`). Its flex sections give the active task remaining vertical space. Navigation follows content in document flow; it does not overlay the task. Longer content and zoom can scroll.

Outer padding uses the larger of the safe-area inset and top (20px), side (22px), or bottom (12px) spacing. At heights up to (740px), top padding and the header gap become (12px), the pad minimum becomes (170px), and gesture-help separation becomes (20px). At widths up to (350px), side padding becomes at least (16px) and scroll-button minimum width becomes (78px), compared with (90px) normally.

The touchpad has a normal minimum height of (220px) and expands with available room. It precedes a full-width Click button of at least (57px), scroll alternatives, and a pointer-button disclosure. General buttons and fields have a minimum height of (48px); task choices have a minimum height of (62px). Disclosures have a minimum height of (44px).

Frontmatter spacing captures reused steps. Pairing and address entry place a flexible field beside a compact primary action. Tab rows place title selection beside a separate close target. Page titles and URLs truncate; instructions and tab titles wrap when needed.

## Elevation & Depth

Ground, surface, and pad tones create depth. Thin borders separate browser controls, task navigation, and normal-tab rows. The focus outline communicates accessibility state, rather than elevation.

**The Flat Surface Rule.** Separate regions with tonal surfaces and quiet rules. The shipped companion uses no shadows, blur, or gradients.

## Shapes

Controls, selected rows, and fields use the control radius; the pad uses the larger pad radius. Fields and quiet dividers use a (1px) line. Icons are authored SVG with rounded strokes, no fill, and stroke width (1.8): ordinary control icons are (23px), task icons (21px), the pad pointer (32px), and connection illustrations (48px).

## Components

### Buttons

Primary buttons use soft teal and dark ground text with weight (620). Secondary buttons use the blue surface and warm white. Disconnect and icon actions sit directly on the ground; history and task controls are transparent at rest. Click is wider and larger than adjacent alternatives.

Ordinary buttons have separate hover and pressed colors; primary controls use teal variants. History buttons, normal-tab buttons, and task tabs preserve their specific transparent/selected backgrounds. Keyboard focus receives a teal outline (3px) with offset (3px). Disabled controls use disabled ink on the blue surface and a default cursor.

### Inputs / Fields

Fields use the blue surface, warm white text, quiet slate stroke, control corners, and a teal caret. Labels stay visible. Placeholders use muted ink. Pairing uses numeric input and an eight-digit pattern; address entry supplies a Go action. Page text supports multiline input and vertical resizing.

A disabled editor explains whether an eligible TV field must be selected, it is still being read, or typing is unavailable. On a field-identity change, prior textarea content clears before the new field becomes ready. Password fields are excluded. Delete before cursor and Finish composition are named controls.

### Navigation

Back, Forward, and Reload/Stop precede the active task and a quiet divider. History availability and loading determine disabled states and labels.

Remote, Type, and Tabs form a semantic tablist with icons above labels. The selected task is teal on selected blue. Only the selected tab participates in normal tab order; Left/Right cycles tasks and Home/End selects first/last. Task switching clears unfinished gesture and field-edit state. The identity shortcut opens Type and focuses the address field.

### Normal-tab Rows

Flat rows separate title selection from a named close action. The selected title gains teal-on-selected-blue treatment. Long titles wrap. Updates preserve the focused tab/action when possible, otherwise focus moves to New tab. An empty list explains how to start browsing.

### Touchpad

The slate pad centers a teal pointer and muted gesture instructions. Drag moves, tap clicks, and two fingers scroll. A separate primary Click button, Up/Down scroll controls, and disclosed four-direction pointer buttons provide named keyboard-accessible alternatives. Pointer buttons move by (30) relative units; scroll buttons send (-3 / 3) vertical units.

Pad press and task selection use background/color transitions (160ms) with `cubic-bezier(0.16, 1, 0.3, 1)`. Reduced motion removes these transitions. Native touch panning is suppressed only within the pad.

### Connection and Recovery States

Pairing leads to explicit TV approval. Connected status is teal. Home replaces pointer controls with address entry. Amber paused status clears protected page identity and text: browser-controls pauses direct the user to close those controls on the TV; other paused screens direct the user to continue on the TV. Stale-action rejection, page error, expired invitation, and reconnecting have distinct messages. Disconnected control stays disabled until fresh state returns. The development/unencrypted notice and Network & safety disclosure remain visible.

## Do's and Don'ts

### Do:

- **Do** preserve the deep blue ground, slate touchpad, warm white text, and restrained teal feedback.
- **Do** use visible named buttons alongside touch gestures and preserve tablist keyboard navigation.
- **Do** keep safe-area padding, document scrolling, readable zoom, and reduced-motion support.
- **Do** explain Home, waiting, paused, unavailable-field, and reconnecting states with the next useful action.
- **Do** use bundled resources, system fonts, and authored inline SVG icons.

### Don't:

- **Don't** reintroduce stacks of oversized outlined controls.
- **Don't** imply phone playback commands, private-tab access, password editing, or secure transport that the companion does not support.
- **Don't** replace truthful connection or disabled-state explanations with decorative status badges.
- **Don't** add external fonts, raster dependencies, remote assets, or motion that ignores reduced-motion preference.

When opaque native tab handles rotate, selection focus follows the current
engine-selected row. Removed close controls return focus to New tab.
