package org.example.project.guidance

/**
 * THE ONLY FILE YOU EDIT TO RETARGET THIS APP TO A DIFFERENT THIRD-PARTY APP.
 *
 * Nothing in androidApp knows about "Uber" or any other package, label, or
 * screen -- it only ever reads [allTasks]. To point the whole app at a new
 * app:
 *
 *   1. Turn on Capture Mode in the launcher and walk through the target
 *      app's screens on the device. Pull the dump with the adb command
 *      printed by the app (see CaptureLogger).
 *   2. Before writing a single step, check the dump for blank stretches --
 *      count elements per screen. Custom-rendered list/search screens (seen
 *      on ae.mediclinic.app and the input form on us.zoom.videomeetings)
 *      can come back completely or partially empty even though the screen
 *      is visibly full of content. If that happens, that screen -- or the
 *      whole app -- isn't usable as a guidance target; there's no step to
 *      write around missing data.
 *   3. For each screen, write one GuidanceStep: an instruction to speak,
 *      and one or more Matchers built from what you saw in the dump.
 *        - Matcher.ViewIdEquals is most stable when the dump shows a real
 *          resource id for the element -- not all apps set one. Neither
 *          Settings nor Mediclinic exposed any in their dumps; Uber does
 *          (see bookUberRide below). Check your own dump before assuming
 *          ids exist.
 *        - Otherwise use Matcher.TextEquals / TextContains on the visible
 *          label, and/or Matcher.ContentDescContains for icon-only buttons
 *          (toggles/switches usually have no text, only a contentDescription).
 *        - Put the most reliable matcher first; list a couple as fallbacks
 *          in the same step since device/OEM copy varies, and the SAME
 *          element can carry different text depending on app state (Uber's
 *          search box says "Enter pickup location" until GPS resolves,
 *          "Where to?" after -- same button, verify you're covering both).
 *   4. Mark a step `optional = true` if it only appears on some devices.
 *   5. Set `scrollHint` on any step whose target might be off the first
 *      screen.
 *   6. Build a GuidanceTask with `targetPackage`, a `title` for the launcher
 *      card, an `examples` list of phrases someone might actually say to ask
 *      for this task (used by voice task selection -- VoiceTaskMatcher.kt,
 *      also in this package), and add it to [allTasks].
 *   7. Reinstall. No other file needs to change.
 *   8. Never write a step for an action with real-world consequences
 *      (submitting a payment, requesting a ride, confirming a booking) --
 *      see the note on bookUberRide below for why.
 *
 * WATCH OUT FOR TEXT THAT REPEATS ACROSS SCREENS. GuidanceEngine always
 * prefers the CURRENT step over a later one when both still match ("advance
 * when the NEXT step's element appears, not when the current disappears").
 * If step N's matcher text also appears on the screen step N+1 is meant for,
 * the engine gets stuck re-matching step N forever and never reaches N+1 --
 * not a crash, just silently stuck one step early. Hit this repeatedly while
 * building earlier versions of this file (Wi-Fi's toggle text, Mediclinic's
 * "Continue"); see git history if you want the details.
 *
 * ---------------------------------------------------------------------
 * bookUberRide -- FULLY VERIFIED from a real Capture Mode dump of
 * com.ubercab. This is the demo task. Every screen in the dump had healthy
 * element counts (17-141), no blank stretches, unlike Mediclinic's search
 * screen or Zoom's join-a-meeting form -- both were tried and dropped first.
 *
 * Real flow, in order:
 *   1. Home screen -- tap the search box ("Where to?" once GPS has resolved
 *      a pickup point, "Enter pickup location" before that -- same button,
 *      matched either way), type ANY destination, then tap the matching
 *      result in the list. Deliberately not tied to a specific destination
 *      string: the engine doesn't need to know which result you tapped, only
 *      that the ride-selection screen was reached, so step 1's own matcher
 *      (the search box) is left as the "current" match all the way through
 *      typing and picking a result -- it naturally stops matching once you
 *      leave this screen, and the engine searches forward from there. An
 *      earlier version of this file matched the specific demo destination
 *      ("Cleveland Clinic Abu Dhabi") as its own step; that hit the
 *      current-step-priority bug described above, because the NEXT screen's
 *      route banner ("New York University Abu Dhabi to Cleveland Clinic Abu
 *      Dhabi") still contained that substring and the engine never advanced
 *      past it. Matching nothing at all on this screen sidesteps the
 *      problem entirely instead of hunting for a more specific string.
 *   2. "Choose a ride" screen -- ride options (UberX, UberXL, Comfort,
 *      VanXL) all share the view id order_selection_order_cell, so matching
 *      on that id alone doesn't reliably pick UberX: tried it first, on the
 *      theory that UberX's row is taller (it carries a "Faster" badge) and
 *      would win selectBest()'s largest-area tiebreak. Wrong in practice --
 *      the CURRENTLY SELECTED option renders taller (expanded detail), so
 *      the tiebreak actually picks whichever option the app defaults to
 *      selecting, not specifically UberX. Caught live: ring landed on
 *      UberXL because it was the session's pre-selected default while the
 *      instruction still said UberX, a real ring/voice mismatch. Fixed by
 *      matching UberX's own contentDescription instead, same comma-boundary
 *      trick as before ("UberXL,Fare..." contains "UberX" as a prefix, so
 *      the comma right after matters). The instruction still frames it as a
 *      suggestion (UberX's fare in the dump was AED 32-41, the cheapest of
 *      the four) rather than a command -- the person can tap a different row
 *      if they want; guidance just ends after this step either way.
 *
 * DELIBERATELY STOPS HERE. The dump has a further screen state with a
 * button carrying the view id order_selection_request_button -- that's the
 * literal ride-request button; tapping it dispatches a real Uber ride to a
 * real driver. There is no step for it, on purpose, the same line the
 * person capturing this dump drew by not tapping it either. If you ever
 * want to extend this task past ride selection, that's a product decision
 * to make deliberately, not a gap to casually fill in.
 * ---------------------------------------------------------------------
 */
object Tasks {
    private const val UBER_PACKAGE = "com.ubercab"

    val bookUberRide = GuidanceTask(
        id = "book_uber_ride",
        title = "Get a ride",
        targetPackage = UBER_PACKAGE,
        examples = listOf(
            "get a ride", "go somewhere", "book a car", "call a cab", "call a taxi", "need a ride", "book an uber",
        ),
        steps = listOf(
            GuidanceStep(
                instruction = "Tap the search box at the top, type where you're going, then tap the matching address in the list.",
                matchers = listOf(
                    Matcher.ContentDescContains("Where to?"), // verified
                    Matcher.ContentDescContains("Enter pickup location"), // verified: same box, pre-GPS state
                    Matcher.TextEquals("Where to?"), // verified
                ),
            ),
            GuidanceStep(
                // Feel free to choose differently -- guidance ends after this step either way.
                instruction = "UberX is usually the cheapest option, but choose whichever ride you like below.",
                matchers = listOf(
                    // Deliberately specific, not the shared order_selection_order_cell id: an
                    // earlier version matched generically and relied on selectBest()'s
                    // largest-area tiebreak to land on UberX, on the theory that its "Faster"
                    // badge makes its row taller than the others. That's true ONLY when UberX
                    // isn't the currently-selected option -- the selected row renders taller
                    // (expanded detail), so whichever option the app defaults to selecting wins
                    // the tiebreak instead, not specifically UberX. Caught live: ring landed on
                    // UberXL because it was the session's pre-selected default. Comma placement
                    // matters here: "UberXL,Fare..." contains "UberX" as a prefix, so without the
                    // comma this would match both rows.
                    Matcher.ContentDescContains("UberX,Fare"),
                ),
            ),
        ),
        // The real "Request X" button, verified from the dump (view id
        // order_selection_request_button). Guidance itself never points at this on purpose (see
        // the header comment above), but the Help AI CAN act on the user's behalf if explicitly
        // asked -- this is the human-verified backstop that forces a spoken confirmation before
        // it's ever allowed to tap this specific button, regardless of what the AI itself thinks.
        finalActionMatchers = listOf(
            Matcher.ViewIdEquals("order_selection_request_button"),
        ),
    )

    val allTasks: List<GuidanceTask> = listOf(bookUberRide)
}
