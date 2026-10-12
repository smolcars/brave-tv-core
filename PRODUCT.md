# Brave TV phone remote

<!-- impeccable:product-schema 1 -->

## Platform

web

## Scope

This record applies to the bundled phone-browser companion only, primarily
android/java/brave-res/raw/tv_remote.html and its CSS/JS. The Android TV browser
and upstream Brave desktop/mobile applications are outside this visual redesign.

## Users

The user controls ordinary TV webpages from a phone on the same local network.
They confirmed touchpad browsing and quick controls as the primary task.

## Product Purpose

Make moving, clicking, scrolling and navigating TV webpages comfortable from a
phone; keep typing and normal tabs readily accessible without crowding the pad.

## Capabilities and Constraints

The native TV engine owns tabs, navigation, media and text editing. Existing
commands support Back/Forward, Reload/Stop, address/search, relative pointer
movement/click/scroll, normal-tab creation/selection/closure and native eligible
field editing. Dedicated phone playback commands are not currently supported;
webpage playback controls can be clicked with the pointer.

The APK bundles all HTML/CSS/JS. No external fonts/assets, web framework, account,
cloud relay, live TV capture, remote JS/ADB endpoint or persistent credentials.
Random expiring single-use invitations need explicit TV approval. One controller;
exact Host/Origin protections, bounds, revocation and no replay remain mandatory.
Private tabs/passwords/native-security dialogs are excluded; truthful paused and
recovery states must remain. HTTP remains an opt-in unencrypted development
experiment; show its warning without claiming transport security.

## Operating Context

Mobile browsers use the companion directly from its TV local address. Tablet and
desktop browsers remain usable. Phone safe areas, on-screen keyboard, touch and
keyboard accessibility matter. Real iOS/Safari, hardware phone camera and IME
compatibility still need separate verification. Couch/evening use is inferred
from the TV-browser setting, rather than a separately confirmed lighting preference.

## Brand Commitments

The existing phone plan specifies a calm dark surface, one restrained accent and
readable page identity. User rejects the incumbent mobile appearance and wants
stronger design and control UX; stacks of oversized outlined controls are unwanted.

## Evidence on Hand

Current bundled assets, phone-remote-plan.md/evidence, native protocol/state/input
regressions and host companion tests. Preview content is synthetic and must not
be reported as native-engine/device acceptance.

## Product Principles

- Put the most frequent browsing actions within comfortable thumb reach.
- Keep browser identity and connection state truthful and easy to scan.
- Separate tasks instead of stretching one long control page.
- Preserve native ownership, security and accessible gesture alternatives.

## Accessibility & Inclusion

Semantic named controls, visible focus, readable contrast, usable zoom and reduced
motion. Gestures always have button alternatives. Disabled states must explain
whether the TV is Home, a modal owns input, a field is ineligible or disconnected.
