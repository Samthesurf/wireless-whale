# Wireless Whale COMPONENTS

> Component contract for native Kotlin/Compose. Companion to `design/DESIGN.md`. Tokens live in DESIGN.md sections 2 through 4. This file owns component inventory, anatomy, states, responsive rules, and the capsule navigation geometry. A one off new variant requires a design contract change here first, not an inline color or radius decision in one screen.

- Scope: all routes in DESIGN.md section 6.
- Truth rule: real data or explicit Unknown. No fake clients, no fabricated usage, no invented scores.
- Accessibility baseline: 48 dp targets, visible focus, contrast checked composited states, font scale to 2.0, TalkBack labels with action plus state.

## 1. Inventory and variant budget

Counts are normative. Shipping more variants than listed is drift.

| Component | Permitted variants | Required states |
|---|---|---|
| AppTopBar | Root, Detail | Default, focused action, narrow width |
| CapsuleNav | Selected, Unselected, four tabs only | Default, pressed, focused, current |
| Button | Primary, Secondary, Destructive | Default, pressed, focused, disabled, loading |
| IconAction | Plain, Selected, Destructive | Default, pressed, focused, disabled |
| ConnectionSummary | WiFi, Hotspot | Loading, fresh, stale, unknown, unavailable |
| MetricRow | Plain, Technical value | Known, unavailable, stale |
| NetworkRow | Connected, Nearby | Fresh, stale, security unsupported, selected |
| ClientRow | Online, Remembered or offline | Unknown name, stale source, policy active |
| StatusChip | Neutral, Live, Warning, Error | Text plus icon or shape meaning, never color alone |
| FormField | Text, Secret, Numeric | Default, focused, invalid, disabled, helper text |
| CapabilityNotice | Setup needed, Unsupported, Access lost | Clear reason plus one recovery action |
| Chart | Signal, Verified traffic | Enough data, empty, paused, stale |
| Sheet and Dialog | Informational, Confirmation, Destructive | Keyboard safe, focus correct, dismissible where safe |
| EmptyState | Plain | One explanation plus one action that fills it |
| ScanAction | Prominent QR button | Default, pressed, focused, camera unavailable |

## 2. CapsuleNav: normative geometry and behavior

This is the signature. Geometry mistakes here are on every screen.

### Geometry at 360 dp reference

- Container: floating pill, 72 dp high at font scale 1.0, full width minus about 16 dp outer margins on each side.
- Shape: true pill. End radius equals half the measured bar height. In Compose terms use `CircleShape` or `RoundedCornerShape(percent = 50)` on the container and on the selected segment.
- Placement: centered horizontally, bottom aligned to system navigation insets plus 8 to 12 dp breathing gap.
- Shell fill: `#14171A`. No translucency, no blur. Soft shadow with one consistent direction.
- Tabs: four equal slots. Each slot min 48 x 48 dp touch area. Icon 24 dp from one stroke family. Label 12 sp Medium at scale 1.0.
- Selected segment: light pill wrapping the selected icon plus label. Fill near white such as `#FFFFFF` or `#F1F3F5`, chosen after a composited contrast check on graphite. Ink icon plus ink label inside the segment. Unselected tabs use white icon plus label at a contrast checked emphasis.
- Spacing: 4 dp inner padding inside the shell, 4 to 8 dp gaps between tab slots depending on measured width.

### States and interaction

- Current: exactly one tab carries the selected segment and the accessibility selected state. Navigation without a current state is a map with no position marker.
- Pressed: 100 ms ease out tonal shift plus optional 0.98 scale. Every tab has a pressed state.
- Focused: visible outline distinct from pressed, usable with keyboard and D pad. Focus order follows visual order left to right. TalkBack announces destination plus selected state, for example "Hotspot, selected".
- Disabled: not normally used for tabs. If a destination is unavailable, keep it selectable and explain inside the destination rather than dead ending navigation.
- Hiding: scanner in immersive mode and software keyboard editing may hide or translate the capsule. The decision must be verified per route so controls are never layered under the keyboard.

### Content clearance

- Every scrolling screen reserves bottom padding for capsule height plus navigation insets plus breathing gap.
- Sticky action buttons above the capsule must clear it by at least 12 dp.
- Verify with XOS three button navigation visible on the Infinix X6816.

### Responsive rules

- 360 dp: as specified above.
- 320 dp narrow: keep four 48 dp targets. Reduce outer margins only if needed. Labels may use shorter text only when the TalkBack label keeps the full name. Never clip the selected segment.
- Font scale to 2.0: bar may grow taller. Selected label may reflow under or beside the icon in a tested arrangement. No overlap, no truncation that hides meaning, no capped font scaling.
- Landscape and tablet: constrain the capsule to a centered max width near 560 to 640 dp. On wide tablet layouts use a coherent navigation rail only if the design contract is updated first. Do not stretch four tiny targets edge to edge.
- Large text plus landscape is a required check, not an optional one.

## 3. AppTopBar

### Anatomy

- Container: white, 64 dp min height, 20 dp horizontal padding at 360 dp, 16 dp at 320 dp.
- Title: screen title style on root tabs, section title style on detail routes.
- Root variant: title plus optional short subtitle for access state, Settings gear action top right.
- Detail variant: back action left, title plus optional subtitle, actions right as needed.
- All actions 48 dp targets with content descriptions.

### States

- Default and focused action states required.
- Narrow width wraps subtitle under title rather than truncating the action.
- No more than two actions right on detail routes unless the contract is updated.

## 4. Button

### Variants

- Primary: teal fill `#0A6B62`, white label 15 sp Medium, 16 dp radius, min height 52 dp, full width or content width with 24 dp horizontal padding depending on context.
- Secondary: white fill, 1 dp `Border` outline, ink label. Same sizing as Primary.
- Destructive: white or subtle surface fill with destructive checked color label and icon, 1 dp outline in a compatible tone. Destructive always names its target on the button or in the confirmation that follows.

### States

- Default, pressed with tonal shift plus 0.98 scale over 100 ms ease out, focused with a visible outline distinct from pressed, disabled with reduced opacity plus retained shape and a reason nearby when the reason is not obvious, loading with in place progress and preserved layout so nothing jumps.
- Disabled never relies on color alone. Loading never replaces the whole screen for a local action.
- Keyboard safe: full width primary actions above the keyboard must remain reachable when the capsule hides.

## 5. IconAction

Plain for bar actions, Selected for toggles such as torch or sort state, Destructive for remove or block entry points. 48 dp target, 24 dp icon, visible pressed and focused states, disabled with reason where the cause is not obvious. Content descriptions name action plus state.

## 6. ConnectionSummary

One strong summary per screen. This replaces generic card grids and hero metric templates.

### WiFi variant

- Line 1: section label in secondary text, for example "Current connection".
- Line 2: network name in 22 to 24 sp SemiBold, or explicit Unknown with reason.
- Line 3: state line in body text: connected, validated internet, captive portal, disconnected, or redacted identifier with cause.
- Optional line 4: signal trend with axes and units, or a plain sentence when samples are unavailable.
- Shape: white card, 24 to 28 dp radius, 1 dp border, 20 dp padding.
- QR and connection actions sit directly under the card, not buried in overflow.

### Hotspot variant

- Line 1: session state headline, for example "Hotspot is on" or "Hotspot is off".
- Line 2: truthful client count from an authoritative source, or Unknown with reason. Never zero as a stand in for unknown.
- Line 3: control or handoff action plus sharing action where authorized.
- Same card shape as WiFi variant so the session language feels coherent across the app.

### States

Loading preserves card height with in place progress. Fresh shows values with a quiet timestamp. Stale keeps values plus age stamp plus refresh action. Unknown names the missing field and the cause. Unavailable explains the access requirement and offers one recovery action. Bridge lost converts live rows to stale with an access lost notice.

## 7. MetricRow and NetworkRow

### MetricRow

- Plain compact rows: label left in body text, value right in body Medium. Both columns have bounded width; an unweighted value must never consume the label column.
- Long descriptions, URLs, long identifiers, and text enlarged beyond a readable two-column fit use a full-width label above a full-width value with 4 dp spacing. Text wraps vertically without ellipsis, fixed-height clipping, or horizontal overflow.
- Technical value: value in Geist Mono 13 to 14 sp for IP, BSSID, channel, counters, timestamps.
- Known, unavailable, and stale recipes. Unavailable shows "Unavailable" plus short cause. Stale keeps value plus age.
- Dividers: 1 dp border hairlines between rows.

### NetworkRow

- Connected variant separated above nearby results with a clear heading.
- Row content: SSID in Medium, signal plus security plus band plus last seen in secondary labels, chevron or Connect action right.
- Fresh rows show age under a threshold. Older rows show "Last seen" timestamp and a stale treatment.
- Security unsupported names the limitation and offers details rather than a dead button.
- Selected state during connection flow is distinct from connected state.
- Filter and sort controls stay compact above the list. Group same SSID access points where appropriate without inventing location claims.

## 8. ClientRow

- Line 1: local nickname in Medium, or observed identity when no nickname exists. Never invent a manufacturer or model for an unknown hostname.
- Line 2: observed identity source plus addresses in Mono where technical, plus last seen.
- Right side: connection dot plus word, and policy badge only when a verified policy is active.
- Online variant for currently associated clients from an authoritative source. Remembered or offline variant for known but not currently connected clients.
- Unknown name state: "Unknown device" plus source note and a nickname action.
- Stale source state: keep last seen, stamp age, offer refresh.
- Policy active state: name the policy scope and window. A reminder or warning must never read as an enforced limit.
- Tapping opens `hotspot/client/{id}`. The row itself never claims LAN discovery equals hotspot association. Those groups stay visually separate on the Hotspot screen.

## 9. StatusChip

Variants: Neutral, Live, Warning, Error. Capsule shape, min height 32 dp, 12 dp horizontal padding, icon plus text. Meaning must survive without color: Live uses a filled dot plus word, Warning uses a triangle plus word, Error uses an exclamation plus word, Neutral uses plain text or an info icon. All pairs contrast checked as composited.

## 10. FormField

Text, Secret with visibility toggle, Numeric with correct keyboard. 56 dp min height, 16 dp radius, 1 dp border, white fill, 15 to 16 sp input text. Focused gets a 2 dp accent or ink outline distinct from error. Invalid shows error text naming the problem plus a fix, and keeps helper text space so layout does not jump. Disabled retains shape with a reason nearby. Secret fields never preview passwords beyond the explicit toggle. Credential edits confirm discard when dirty.

## 11. CapabilityNotice

Setup needed, Unsupported, and Access lost variants. Anatomy: leading icon, headline in Medium, one sentence of cause, one recovery action button. Lives next to the unavailable feature, not in a hidden FAQ. Examples: location needed for scans, enhanced access needed for hotspot control, bridge lost with reconnect action. Never stack multiple notices for one cause.

## 12. Chart

Signal and Verified traffic variants only.

- Signal: axes with units, sample window label, enough data state draws the line, empty state explains why with one action, paused state names the pause cause, stale state keeps the last line plus age stamp.
- Verified traffic: renders only after Gate G2 proof. Before proof the slot shows Unavailable with scope note, never an estimated ring.
- No looped animation. No radar sweep. No pulsing orb.
- Empty is a high value state: say what this is, why it is empty, and the one action that fills it.

## 13. Sheet, Dialog, EmptyState, ScanAction

### Sheet and Dialog

- Sheet: white, top corners 28 to 32 dp, drag handle, scrim 32 to 48 percent black. Dialog: white, 24 dp radius, same scrim.
- Informational, Confirmation, Destructive variants. Destructive always names the target and requires explicit confirmation.
- Keyboard safe with focus order into the sheet, visible focus on actions, dismissible where safe. Unsaved credential edits get a discard prompt.

### EmptyState

Plain. Title, one sentence explaining why it is empty, one action that fills it. Example: saved profiles empty offers "Add profile" or "Scan QR". No illustration that costs performance on this phone.

### ScanAction

Prominent QR button on Home and Wi-Fi. Large target, camera icon plus "Scan QR" label, Primary or equivalent emphasis. Camera unavailable state explains the cause and offers gallery import or manual entry.

## 14. State matrix

Every interactive component ships every row that applies. Empty, loading, and error are first uses, not edge cases.

| State | Rule |
|---|---|
| Default | As specified in this file |
| Pressed | Tonal or 0.98 scale change over about 100 ms ease out |
| Focused | Visible and distinct from pressed, keyboard and D pad usable |
| Disabled | Shape plus opacity plus nearby reason when cause is not obvious |
| Loading | In place, layout preserving, no full screen spinner for local actions |
| Empty | What this is, why it is empty, one action that fills it |
| Error | Next to the failed control, in words, with the recovery action |
| Stale | Last trustworthy value plus age plus refresh or recovery |
| Unknown | Explicit word plus short cause, never zero as unknown |
| Selected or current | Exactly one current tab or option, announced to accessibility |

## 15. Implementation notes for the next UI agent

- Build order: theme and fonts, shell with AppTopBar plus CapsuleNav, Home, Wi-Fi, Hotspot, Tools, then secondary routes.
- Reuse these components before inventing layout. A new variant edits this file first.
- Validate on the Infinix X6816 at 360 dp, 320 dp narrow simulation, font scale near 2.0, XOS three button nav, and landscape. Content padding must clear the capsule plus insets everywhere.
- Label all preview clients, counts, and byte values as design fixtures until wired to real sources. Remove or rewire fixtures before calling any screen done.
