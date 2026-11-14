# Wireless Whale DESIGN

> Style contract for the native Kotlin/Compose product. Read this file first before any UI work. New work that contradicts this file is wrong even if it looks good in isolation. A new variant, token, or radius is a design decision that edits this file first. Adapted from auteur system register for native Compose. No web framework, no web QA gates.

- Project: Wireless Whale, native Kotlin + Jetpack Compose, Material 3 primitives styled to this contract
- Primary device: Infinix X6816, Android 11, logical 360 x 820 dp before insets, physical 720 x 1640, density 320
- Plan source: `.hermes/plans/2026-11-14_094250-wireless-whale.md` sections 6 and 7
- Companion contract: `design/COMPONENTS.md` (component APIs, anatomy, state recipes)
- Status: native implementation in progress. This style contract does not authorize extra device or network changes.

## 1. Art direction

A light, precise network instrument. White surfaces, dark graphite text, one restrained teal for primary action and confirmed live state, generous rounded forms, readable density. Noticeably more designed than a basic utility list, without becoming a neon dashboard.

### Commitments

- Signature component: the floating graphite capsule bottom bar with a light selected segment, plus a coherent connection and session visual language on Home, Wi-Fi, and Hotspot.
- Moment of care: when data is stale or access is lost, keep the last trustworthy value visible, stamp its age, and explain the recovery action. Never blank the screen and never invent zeroes.
- Color: neutral white plus graphite plus teal. No purple gradient, no beige body, no dynamic wallpaper palette.
- Type: Geist Sans for interface text, Geist Mono only for technical values such as IP addresses, channels, and byte counters.
- Composition: one strong summary per screen plus well ordered rows. No bento grid of matching cards.
- Motion: short functional transitions only. No radar sweep, no pulsing orb, no continuous decorative animation.
- Reflexes to avoid: a dark terminal network app, and the opposite failure of an empty white app with default switches.
- House defaults deliberately rejected: a dark hero panel and decorative monospace labels everywhere. This product uses white layouts and ordinary sentence case interface text.

The app content stays light even when the phone system theme is dark. Respect accessibility and high contrast needs and platform system dialog styling. Do not force the whole phone into light mode.

## 2. Color tokens

Exact values. Use these hex values only. Do not sample nearby shades in individual screens.

| Token | Value | Use |
|---|---|---|
| Background | `#FFFFFF` | Main screen background |
| Subtle surface | `#F5F6F8` | Grouped rows, secondary panels |
| Elevated surface | `#FFFFFF` | Sheets and selected cards, separated by border and shadow, not by tint alone |
| Main text and nav shell | `#14171A` | Headings, primary text, capsule background |
| Secondary text | `#5B6470` | Supporting information, never tiny pale captions |
| Accent teal | `#0A6B62` | Primary action emphasis and confirmed live states only |
| Border | `#E4E7EC` | Subtle structural dividers, card outlines, sheet hairlines |
| Warning | decided at implementation with contrast check, target near `#8A4B00` on white | Advisory or caution, always paired with icon plus text |
| Destructive | decided at implementation with contrast check, target near `#B3261E` on white | Stop, remove, block, always paired with explicit label |

Compose mapping: `Background = Color(0xFFFFFFFF)`, `Subtle = Color(0xFFF5F6F8)`, `Ink = Color(0xFF14171A)`, `Secondary = Color(0xFF5B6470)`, `Accent = Color(0xFF0A6B62)`, `Border = Color(0xFFE4E7EC)`.

### Contrast facts

Checked for these exact opaque pairs only:

- Main text on white: 17.99 to 1
- Secondary text on white: 6.00 to 1
- Accent on white: 6.38 to 1
- Secondary text on subtle surface: 5.55 to 1

These numbers do not certify the whole app. Test composited disabled states, chips, charts, pressed states, and selected segment separately. Body text must meet 4.5 to 1 minimum, large text 3 to 1 minimum. Placeholders must meet 4.5 to 1. Status meaning must never rely on color alone.

### Brand artwork exception

The generated whale-and-wireless mark is monochrome by user request. The launcher uses white artwork on a pure black background; the Home header uses the black transparent derivative. Pure black and white are confined to this identity asset, not substituted for the graphite interface tokens. Android 13 themed launcher icons may apply the user-selected system tint to the monochrome mask. Source artwork and provenance are under `design/logo/`; `scripts/generate_launcher_assets.py` derives density-specific rasters without replacing the generated art.

### Color rules

- Commitment tier: restrained. Accent covers a small share of the surface.
- Teal means one of two things: the primary action on screen, or a confirmed live state verified against a real source. It is never decoration, never a gradient, never a chart rainbow.
- Warning and destructive always combine color plus icon plus words.
- Stale data drains emphasis: keep the value in ink on white, add a stale stamp in secondary text with a clock icon, never gray it below contrast minimums.
- Disabled uses opacity plus shape plus label state, not color alone. See `design/COMPONENTS.md`.

## 3. Typography

### Families and weights

Bundle licensed static font files in the APK. No network font download on launch.

- Geist Sans Regular (400): body, secondary labels, input text
- Geist Sans Medium (500): emphasized body, button labels, tab labels, row titles at small size
- Geist Sans SemiBold (600): screen titles, network names, section titles
- Geist Mono Regular (400) and Medium (500): IP addresses, BSSID, channel, byte counters, timestamps where alignment aids scanning

Prefer the tested static set. Use variable fonts only if validated on API 30 without shaping or line height regressions.

### Type scale

All sizes in sp so system font scaling applies. Line heights are explicit minimums.

| Role | Size and weight | Line height | Letter spacing | Use |
|---|---|---|---|---|
| Screen title | 28 sp SemiBold | 34 sp | -0.5 sp | Top of Home, Wi-Fi, Hotspot, Tools |
| Summary name | 22 to 24 sp SemiBold | 28 to 30 sp | -0.25 sp | Current network, hotspot session headline |
| Section title | 18 sp SemiBold | 24 sp | 0 sp | Groups inside a screen |
| Body and input | 15 to 16 sp Regular, Medium for emphasis | 22 to 24 sp | 0 sp | Rows, descriptions, editable text |
| Secondary label | 13 to 14 sp Regular | 18 to 20 sp | 0 sp | Timestamps, helper text, last seen, channel detail |
| Nav label | 12 sp Medium | 16 sp | 0.1 sp | Capsule tab labels at normal scale |
| Mono value | 13 to 14 sp Mono Regular or Medium | 18 to 20 sp | 0 sp | Technical values only, tabular numerals where available |

Rules:

- Body measure: keep prose lines within a comfortable readable width on phone. Avoid full width tiny captions.
- Headings use `text-wrap` equivalent care in Compose: avoid orphaned single word lines for summary names where layout allows.
- Never set all interface labels in Mono. Never use Mono as decoration.
- Button labels: 15 sp Medium, sentence case.
- Include the SIL Open Font License and attribution in an in app licenses screen and in the repository. See section 9.

## 4. Geometry, spacing, shape

### Spacing scale

Base scale in dp: 4, 8, 12, 16, 20, 24, 32.

- Page horizontal margin: 20 dp at 360 dp width, 16 dp at 320 dp narrow width.
- Vertical rhythm: 12 dp between related rows, 20 to 24 dp between groups, 32 dp before a new major section.
- Interactive hit targets: at least 48 x 48 dp for every tappable control including tabs, icon actions, and row chevrons.

### Radii

| Element | Radius |
|---|---|
| Summary card | 24 to 28 dp |
| Bottom sheet top corners | 28 to 32 dp, bottom corners 0 dp when edge to edge |
| Buttons and inputs | 14 to 18 dp |
| Chips | capsule, `CircleShape`, min height 32 dp with 12 dp horizontal padding |
| Capsule nav bar | true pill, end radius equals half the bar height, implemented as `CircleShape` or `RoundedCornerShape(percent = 50)` |
| Dialog | 24 dp |
| QR preview card | 24 dp with 12 dp inner white padding around the code |

No frosted glass blur. No expensive translucency. Restrained elevation plus 1 dp border for separation.

### Elevation and dividers

- Cards on white: 1 dp `Border` outline plus 0 to 2 dp tonal shift or a soft shadow with small offset and larger blur, one consistent light direction.
- Lists: 1 dp hairline dividers in `Border` between rows, no double borders from card plus row.
- Sheets and dialogs: scrim 32 to 48 percent black, sheet surface white, drag handle 4 dp height in `Border` tone.

### Insets

System navigation, status bar, camera cutout, and XOS three button navigation are real layout inputs.

- Content bottom padding must clear the capsule bar plus navigation insets plus a breathing gap. No last row or button may hide behind the bar.
- Keyboard and forms either move the capsule safely or hide it while editing. Layering the keyboard over controls is a defect.
- Scanner can use an immersive surface without the nav bar, with a clear close action and a return to launch context.

## 5. App frame and shell

The shell is on every screen so its mistakes are on every screen. Build tokens, then the shell, then screens in traffic order: Home, Wi-Fi, Hotspot, Tools, then secondary routes.

### Top bar

- White background, ink title at screen title or section scale depending on route depth.
- Top right: Settings gear action on root tabs, 48 dp target, content description "Settings".
- Top right on Hotspot: Settings gear plus overflow only if a second action is required. Do not crowd the bar.
- Back on detail routes returns through sheet, then list, then tab. Back never jumps tabs unexpectedly.
- Small access state indicator lives under the Home title or in the top bar subtitle area: Standard or Enhanced plus a short state word. Full detail lives in Settings.

### Floating capsule navigation

The requested semicircular ends mean a true pill, not a rectangle with slightly softened corners.

- Floating horizontal capsule, 72 dp high at normal font scale.
- End radius equals half the actual bar height. Use a pill shape.
- Outer side margins about 16 dp on the 360 dp reference phone.
- Bottom position respects system navigation insets plus a small breathing gap of 8 to 12 dp.
- Graphite shell `#14171A` with white and neutral inactive icons plus labels.
- Selected tab: light segment (near white, for example `#FFFFFF` or `#F1F3F5` checked for contrast on graphite) wrapping icon plus label as one pill inside the shell. Unselected tabs show icon plus 12 sp label in white at reduced emphasis but still contrast checked.
- Four consistent icons from one stroke family: Home, Wi-Fi, Hotspot, Tools. One family, 24 dp icons, 2 dp stroke at normal scale.
- Slight shadow only. No blurred transparency over content.
- Four destinations only: Home, Wi-Fi, Hotspot, Tools. Settings is a top right action. QR scanning is a prominent action inside Home and Wi-Fi plus a Quick Settings tile, not a fifth tab.
- Content padding accounts for the full bar plus insets. Verify with XOS three button nav visible.
- Large font scaling may require a taller bar or a reflowed selected label arrangement. Do not cap system font scaling to preserve a screenshot.
- At tablet or landscape widths, constrain the capsule to a centered max width or switch to a coherent adaptive navigation rail. Do not stretch four tiny targets across a tablet.

Full geometry, states, focus order, and responsive rules are in `design/COMPONENTS.md`. The pill geometry plus the light selected segment is the signature. Do not dilute it with a second competing signature.

## 6. Route map and screen compositions

Each route has one job. Each route ships only with its complete applicable state set. Empty, loading, stale, permission denied, and error states are first class, not edge cases.

| Route | Job | Layout family | Important states |
|---|---|---|---|
| `onboarding` | Explain permissions and optional access without blocking basic use | Short guided pages | Skip, denied, complete |
| `home` | Explain current connection and surface quick actions | Summary plus useful rows | Connected, disconnected, captive portal, redacted identifiers |
| `wifi` | Browse current scan data and join networks | Search and filter plus list | Fresh, stale, throttled, no permission, Wi-Fi off |
| `wifi/network/{id}` | Show one network detail and connection options | Detail sheet or page | In range, stale, unsupported security |
| `wifi/profiles` | Manage credentials saved in this app | List plus edit sheet | Empty, locked or unavailable secret, deleted |
| `scanner` | Capture a Wi-Fi QR safely | Camera surface plus controls | Camera denied, scanning, decoded, invalid |
| `scanner/preview` | Review the network before adding or connecting | Confirmation sheet | Known format, unsupported format, user cancels |
| `hotspot` | Manage the actual hotspot or explain the available handoff | Session summary plus clients | Unknown, off, starting, on, stopping, bridge lost |
| `hotspot/client/{id}` | Show one observed client and its policies | Identity plus settings rows | Online, disconnected, identity changed, counter unavailable |
| `hotspot/profile` | Edit or review an authorized hotspot configuration | Form | Read only, editable, restart required, save failed |
| `tools` | Pick a diagnostic task without a wall of switches | Grouped tool list | Offline and feature unavailable explanations |
| `tools/connection-check` | Separate local link, DNS, and internet issues | Step by step results | Running, canceled, partial, complete |
| `tools/lan` | Show responsive devices on an authorized local network | Scoped scan plus results | Consent needed, canceled, partial visibility |
| `history` | Review local sessions and diagnostic results | Timeline or list | Empty, retained, export, cleared |
| `settings` | Control access, privacy, alerts, units, retention | Grouped settings rows | Standard, enhanced connected or lost, feature unavailable |
| `settings/access` | Set up or remove optional enhanced access | Guided status page | Not installed, not running, denied, ready, revoked |
| `settings/quick-settings` | Explain adding and using the two tiles | Illustrated steps | Android 11 manual add, newer prompt where supported |

### Home

Order from top:

1. Title plus access indicator.
2. Current network block: SSID if permitted, connection state, useful status such as validated internet or captive portal. Redacted identifiers say why, for example "Hidden by system permission".
3. Restrained signal trend with axes or units, or an explanation when samples are unavailable. No fake gauge.
4. Prominent actions row: Scan QR and Wi-Fi controls. QR action is large and first.
5. Hotspot session row with truthful client count and usage availability. If unknown, say "Unknown" with a reason, never zero.
6. Short recent result or next useful diagnostic action.

### Wi-Fi

1. Current connection separated from nearby results.
2. Signal, security, band, and last seen per row.
3. Sort and filter controls that stay compact.
4. Grouping that distinguishes same SSID access points where appropriate.
5. Connection action opens a credential or approval flow. Never pretend to connect instantly.

### Hotspot

1. State headline plus real control or handoff action. Separate requested, transitioning, and confirmed states.
2. Name, security, and profile summary only when known from an authorized source.
3. Share QR action for authorized credentials only.
4. Client rows with nickname, observed identity, addresses, last seen, and verified usage where available.
5. An access explanation next to an unavailable feature, not in a hidden FAQ.
6. Visual separation between Known clients, Currently connected, and Nearby discovered devices. LAN discovery results must never be relabeled as authoritative hotspot clients.

### Client detail

1. Local nickname plus observed identity source.
2. Current connection and address data.
3. Usage window and accounting scope if verified.
4. Data allowance and speed controls only for supported backends. Otherwise show Unavailable with a reason.
5. Clear warning that randomized or changing MAC addresses can appear as new identities.
6. Destructive actions name their target and require confirmation.

### Tools

Grouped rows for Connection check, Signal history, Channel observations, LAN discovery, History. No wall of identical square icon cards. Each result separates observations from conclusions. Connection check has one primary Run action (Run again after cancellation), or one Cancel action while running. Its empty and partial-result cards are informational, not duplicate launch controls.

### Scanner and preview

Camera surface with frame, torch toggle where supported, gallery import, clear cancel. Decoded preview shows SSID, security, and what will happen next before any save or connect. Unsupported formats name the problem and offer retry or manual entry.

### Settings

Grouped rows for access, privacy, alerts, units, retention, licenses. Each gated capability names its backend and current state.

## 7. Truthfulness and data states

All data is real or explicitly marked unknown. No fake clients, no fabricated usage rings, no invented security scores.

- Unknown means "Unknown" with a short reason. Never render zero as a stand in for unknown.
- Stale means keep the last trustworthy value, stamp its age, and offer refresh or recovery. Example copy: "Last updated 4 min ago. Refresh to confirm."
- Bridge lost means the enhanced backend is gone. Existing rows become stale with an "Access lost" notice and one recovery action. Do not present a bridge lost list as an empty list with zero clients.
- Permission denied means name the permission, explain the consequence, and offer one action such as Open system settings or Retry. The rest of the screen stays usable.
- Wi-Fi off, scan throttled, location off, and captive portal each get their own plain explanation. Do not collapse them into a generic error.
- Per client bytes appear only after Gate G2 proof. Until then show "Unavailable" with scope note. Allowance and speed controls appear only after Gate G3 proof. A reminder or warning label must never read as an enforced limit.
- Hostnames may be unknown. A local nickname never renames the remote device. Unknown manufacturers are never invented.

Copy style: plain, direct, sentence case. No marketing slogans, no fake scores, no cute assistant language.

## 8. Motion vocabulary

Short functional transitions only. This product earns transitions, not choreography.

- Screen and tab change: 150 to 200 ms ease out for fade plus small 8 to 12 dp rise. No shared hero theatrics.
- Pressed: scale 0.98 plus 100 ms ease out, or tonal change. Every button and tab has a pressed state.
- Sheet and dialog: 200 to 300 ms ease out for enter, faster dismiss. Scrim fades with the same curve.
- List updates: no layout jump for loading rows. Skeleton or in place progress preserves row height.
- Stagger lists only where it aids scanning, 30 to 50 ms between close siblings, and never on every screen.
- Reduced motion: same information with shorter or no movement, gentler fades, no looped animation. Respect system reduced motion and accessibility settings.
- Animate only transform and alpha equivalents in Compose. No endless shimmer or radar loops.

## 9. Fonts: official sources, files, license

Do not download yet. The implementer downloads during implementation and records the version and hashes.

### Official source

- Canonical project: `https://github.com/vercel/geist-font`
- Product page and license statement: `https://vercel.com/font`
- Pinned release to use first: `v1.9.2`
- Release bundle download: `https://github.com/vercel/geist-font/releases/download/v1.9.2/geist-font-v1.9.2.zip`
- Release index: `https://github.com/vercel/geist-font/releases`

Inside the bundle, the static files needed for this app are:

```text
geist-font/Geist/otf/Geist-Regular.otf
geist-font/Geist/otf/Geist-Medium.otf
geist-font/Geist/otf/Geist-SemiBold.otf
geist-font/GeistMono/otf/GeistMono-Regular.otf
geist-font/GeistMono/otf/GeistMono-Medium.otf
```

Equivalent TTF statics ship under `geist-font/Geist/ttf/` and `geist-font/GeistMono/ttf/` in the same bundle. Prefer OTF unless the implementer finds a rendering or size reason to prefer TTF on API 30, then use TTF consistently for that family.

Individual raw file references, same pinned release tag, useful for review without downloading:

```text
https://github.com/vercel/geist-font/raw/v1.9.2/fonts/Geist/otf/Geist-Regular.otf
https://github.com/vercel/geist-font/raw/v1.9.2/fonts/Geist/otf/Geist-Medium.otf
https://github.com/vercel/geist-font/raw/v1.9.2/fonts/Geist/otf/Geist-SemiBold.otf
https://github.com/vercel/geist-font/raw/v1.9.2/fonts/GeistMono/otf/GeistMono-Regular.otf
https://github.com/vercel/geist-font/raw/v1.9.2/fonts/GeistMono/otf/GeistMono-Medium.otf
```

Also distributed through the `geist` npm package and Google Fonts, but the GitHub release above is the source of record for bundling.

### License, suitable for bundling

Geist is licensed under the SIL Open Font License 1.1. This permits bundling and embedding in an app, including a commercial app, as long as the license and copyright notice ship with the font and modified versions stay under the same license. Selling the font alone is not permitted.

Record these license sources in the repo and in the app licenses screen:

- `https://github.com/vercel/geist-font/blob/main/LICENSE.txt`
- `https://github.com/vercel/geist-font/blob/main/OFL.txt`
- `https://openfontlicense.org`
- `https://scripts.sil.org/OFL`

In app attribution should name Geist, name Vercel plus collaborators, state SIL OFL 1.1, and link or reproduce the license text. Keep the downloaded `LICENSE.txt` or `OFL.txt` copy in the repository next to the font source note.

### Android integration notes

- Place the five static files under `res/font/` with lowercase resource names and declare a `fontFamily` per weight. Wire Geist Sans as the default family in the app theme and use Geist Mono only through a dedicated mono text style.
- Validate weights, line height, and tabular numerals on the Infinix X6816 at normal and large text sizes before locking the scale.
- If a static weight renders poorly on API 30, document the substitution in this file rather than silently mixing families.

## 10. Responsive and accessibility contract

Design for these from the start, not as a later pass.

- Baseline: 360 dp width, 820 dp height before insets.
- Narrow: 320 dp width. Page margins drop to 16 dp. Summary cards keep 20 dp radius minimum. Rows wrap to two lines rather than truncating technical values where safe. Capsule keeps four targets tappable, labels may shorten only with full TalkBack labels retained.
- Large text: system font scale up to 2.0. Layouts scroll vertically. Capsule may grow taller and selected labels may reflow. No clipping, no overlap with insets, no capped font scaling to preserve a screenshot.
- Landscape: content max width centered, capsule constrained and centered, scanner keeps a usable viewfinder with controls reachable.
- Larger screens and tablets: constrain content width, consider a navigation rail only if the capsule reads as lost. Never stretch four tiny targets edge to edge.
- Touch: 48 dp minimum targets, 8 dp minimum gaps between adjacent targets.
- Focus: every control has a visible focused state distinct from pressed. Keyboard and D pad traversal order follows visual order. TalkBack labels name the action and the state.
- Contrast: verify every composited state, not just the token table.
- Reduced motion and high contrast platform settings must be honored.

## 11. Copy voice

Plain, direct, checkable. Buttons say what happens. Errors say what to do next.

- Adjectives: plain, precise, calm.
- Example headline tone: "Hotspot is on. Two clients connected."
- Button labels: "Scan QR", "Connect", "Share QR", "Start hotspot", "Stop hotspot", "Run check", "Retry", "Open system settings".
- Banned: cute assistant language, marketing slogans, fake scores, and words such as seamless, effortless, unleash, elevate, revolutionize when they replace concrete claims.
- No decorative strips of repeated words. One idea per row.

## 12. Project bans carried from auteur

Adapted to native Compose. A ban may be overridden only with a written note here giving a real reason. A deliberate choice is voice. A default is not.

1. No side stripe accent borders on cards or alerts. Use a full border, a background tint, or a leading icon.
2. No gradient text. Use weight or size.
3. No decorative blur over flat backgrounds. Blur only where real content passes under a persistent surface, and keep it cheap on this phone.
4. No hero metric template of big number plus small label plus stat row. Show evidence in words with one committed visual.
5. No identical grids of same size icon plus heading plus text cards. Vary size and structure or drop the cards.
6. No tiny uppercase tracked kicker above every section. One deliberate kicker maximum as a brand system, or vary section openings.
7. No numbered 01 02 03 scaffolding when order carries no meaning.
8. No Inter or Space Grotesk as the interface face. This product is Geist by contract.
9. No purple to blue gradients.
10. No cream or warm beige body background.
11. No identical entrance animation on every element. Each reveal fits what it reveals.
12. No `transition all` equivalent. Animate named properties only.
13. No scroll listener driven motion. Prefer Compose animation and layout APIs.
14. No `scale(0)` entrances. Start near full size plus fade.
15. No bento grids of near identical or empty cells.
16. No marketing copy tells and no decorative repeated word strips.
17. No more than one marquee or looped motion element per product. This product should have none.
18. No reflex serif for elegance. This product has no serif face.

## 13. Rejected decisions

| What was tried or proposed | Why it lost | What is there instead |
|---|---|---|
| Dark terminal theme with glowing map | Reads as a security theater app and hides readability issues on this LCD phone | White instrument theme with graphite text and one teal |
| Fifth bottom tab for Settings or Scan | Crowds the capsule at 360 dp and weakens the four destination model | Settings top right, QR as a prominent in screen action |
| Usage ring gauges on Home and Hotspot | Implies measured per client accounting before Gate G2 proof | Truthful rows with Unknown or stale stamps where needed |
| Mono labels everywhere for a technical look | Decorative mono harms scanning and looks like costume instrumentation | Sentence case Sans labels, Mono only for technical values |
| Frosted capsule over content | Expensive on this phone and noisy over lists | Solid graphite pill with a soft shadow and clear content padding |
| Beige warm background for friendliness | Saturated default that fights the precise instrument brief | Pure white plus `#F5F6F8` subtle surface |
| One shared hero animation across tabs | A settings screen with a showpiece is a bug in a system product | Short functional transitions only, coherence over peak |

## 14. Native approval gate

Web screenshot and variant crawl scripts do not apply. Run the native equivalent before calling the design done:

1. Compose previews for Home at 360 dp, Hotspot with two clearly labeled design fixture clients, client detail showing both available and unavailable allowance states, and permission denied plus bridge lost states.
2. Physical screenshots on the Infinix X6816 at normal text size, large text near 2.0, XOS three button nav visible, and landscape where layout changes.
3. Contrast spot checks on composited states: selected capsule segment, chips, disabled buttons, stale stamps, focused outlines.
4. Keyboard or D pad traversal pass plus TalkBack pass on the capsule, sheets, and forms.
5. Reduced motion pass with gentler or no movement and identical information.
6. Fixture audit: every preview client, count, and byte value is labeled as a design fixture or it is removed. No fixture may read as live data.

## 15. Editing protocol

1. Read this file and `design/COMPONENTS.md` fully before touching UI.
2. New screen work reuses existing tokens, shapes, and component states. A new pattern edits this contract first.
3. After an edit, re render affected previews and compare against neighboring screens for drift.
4. An edit that fixes something in the Rejected table is a regression however reasonable it looks. Argue the row in this file first, then edit.
5. Keep fixture data labeled. Real data or explicit Unknown only.

## 16. Implementer handoff

What the shell integrator and the next UI agent need, in order:

1. Tokens in section 2 through 4 are exact. Do not invent nearby shades or radii.
2. Build order: fonts and theme, shell with top bar plus capsule, Home, Wi-Fi, Hotspot, Tools, then secondary routes in section 6 order.
3. Capsule geometry in section 5 plus `design/COMPONENTS.md` is normative, including pill ends, selected segment, insets, and responsive behavior.
4. Every list row and summary needs its loading, fresh, stale, unknown, unavailable, permission denied, and error composition. See `design/COMPONENTS.md` state recipes.
5. Fonts: download `v1.9.2` from section 9 during implementation, pin the version and file hashes in the repo, bundle the five static files, and ship OFL attribution in app and repo.
6. Verify with the gate in section 14 on the real phone. Web QA scripts are out of scope for this native product.
