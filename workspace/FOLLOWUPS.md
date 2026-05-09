# Followups

Things we've consciously parked. Each entry includes enough context for whoever picks it up to start without rebuilding the conversation.

## Play Store feature graphic — needs a designed image

**What:** the Play Console requires a 1024×500 PNG "feature graphic" before publishing. It appears at the top of the Play Store listing.

**Current state:** none. The 512×512 app icon at `workspace/play-store-icon-512.png` is in place; the feature graphic isn't.

**What it should be:** a simple gradient with the bunny-music-note icon centered + "migs music" wordmark + a short tagline like "local, private, fast" or "no cloud. no ads." Either knock it out in Figma in 30 minutes, or hand to a designer if you want it nice. The icon's existing palette (yellow→orange→red on the square; deep purple inner-ear accent) is good source material — the feature graphic should pull from the same family so the listing reads as one piece of design.

**Where it goes:** Play Console → Store listing → Graphics → Feature graphic. Save the source under `workspace/` (gitignored or not — designer's call).

**Blocking what:** Play Store submission. The Internal testing track might let you bypass this; Production definitely won't.

## A lot of UI/UX still needs a rework

**Why:** User has flagged several rough edges as we've gone. The recent ones (action overcrowding on rows, ⋮ menus, drag-only reorder, header areas) have been fixed locally. But the user has explicitly noted "the whole UI needs a rework at some point" — there's room for a coherent design pass instead of one-off polish.

**Estimated work:** open-ended. Worth scoping when the user has time to think about it.
