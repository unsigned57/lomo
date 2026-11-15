// Performance mechanism contract:
// Seeded writes acknowledge a unique committed card; a pending card or transient snackbar
// cannot trigger success or a duplicate submit. RED: collection-retry-failed device evidence.
// Profileable release and debug share semantic journey anchors. Scroll must have an indexed
// card and actually move it out of view. Compilation modes apply equally to startup and scroll.
// RED: the prior release A/B measured onboarding and scrolling accepted an empty main root.
// GREEN: device_release_perf_contract plus actual profile/release device reports.
// Behavior Contract
//
// Capability: AndroidX Macrobenchmark suite for the installed Lomo Debug/profileable Release —
// cold startup timing, navigation frame timing, memo-list scroll frame timing, the create /
// expand / memo-menu interaction CUJs, and search typing — driven through the production
// benchmark anchors that Compose exposes as resource ids
// (testTagsAsResourceId, see ui-components/benchmark/BenchmarkAnchorConfig.kt).
//
// Test Change Justification:
// Reason category: User-requested quick-ball interaction replaces the separate scroll-top button.
// Old behavior/assertion being replaced: click the scroll-top anchor and wait for its disappearance.
// Why old assertion is no longer correct: the independent button is removed.
// Coverage preserved by: require the first card to disappear on scroll and reappear after an upward ball swipe.
// Why this is not fitting the test to the implementation: the outcome remains returning to the top;
// a no-op swipe or short list cannot satisfy the witness assertions.
//
// Scenarios:
// - Given workspaceFirstIndex and a pushed SAF corpus, every one of five iterations resets
//   app control and corpus records; grant confirmation through indexed first content has its
//   own mandatory trace metric, excluding picker navigation. The workspace plane validates
//   the final projection's memo/task counts and corpus marker.
// - Given concurrent first-index I/O, when capturing its semantic interval, then a bounded
//   trace of app markers and Binder events retains all five start/end pairs. RED: the
//   default broad capture lost packets and yielded 1/5 samples; GREEN evidence lives in
//   target/lomo/reports/path-optimization-round2/workspace-after.json.
// - Given DocumentsUI restores a nested folder, when granting the pushed workspace, then
//   parent navigation reaches Documents before selecting the corpus; unavailable navigation
//   fails before measurements. RED/GREEN: workspace-saf-picker-red and workspace-saf-final.
// - Given the target package is installed, when `startup` runs, then cold launches through
//   the launcher intent report StartupTimingMetric samples (timeToInitialDisplay etc.).
// - Given an injected workspace corpus, when `startup` runs, then launchToFirstCard measures
//   launch through the first indexed card independently of launchToReady (the root/skeleton).
//   RED: startup-endpoint-red.log finds only launchToReadyFirstMs in the old corpus report;
//   GREEN: same-artifact A/B reports in target/lomo/reports/manual/memo-startup.
// - Given the main screen, when `frame` runs, then opening the search surface and returning
//   reports FrameTimingMetric samples for a navigation interaction that is not a scroll.
// - Given the memo list is populated, when `scroll` runs, then flinging the scrollable under
//   benchmark_main_root reports FrameTimingMetric samples including frameOverrunMs.
// - Given the main screen, when `create` runs, then FAB → input sheet → text entry → submit
//   reports FrameTimingMetric samples for the full memo-create CUJ.
// - Given the main screen, when `expand` runs, then the setup block seeds a memo long enough
//   to carry the expand affordance and the measure block toggles the affordance chip of the
//   first visible expandable card — expand → collapse — reporting FrameTimingMetric samples
//   for the card expand/collapse CUJ.
// - Given the memo list is populated, when `menu` runs in a cold process, first and repeat
//   opens are measured separately after unmeasured startup, and a card's memo-menu button opens
//   the memo menu and dismissing it reports FrameTimingMetric samples for the menu CUJ.
// - Given the main screen, when `search` runs, then search surface → per-keycode query entry
//   reports FrameTimingMetric samples for continuous search typing. The M3E expanded search
//   bar renders the input field and the results surface as sibling subtrees, so
//   benchmark_search_input is located on the device — never as a descendant of
//   benchmark_search_root.
// - Given a missing anchor (renamed tag, regression surface), when a scenario runs, then the
//   test fails closed via requireNonNull/wait-timeout instead of measuring an empty screen.
// - Given the §3-C device CUJ set (editorOpen, reviewOpen, statsOpen,
//   galleryScroll, reelOpen, imageViewerOpen, syncCenterOpen, shareCardRender,
//   conflictDialogShow, widgetRefresh, lanShareOpen), when each runs, then it drives the
//   production surface through benchmark anchors and reports FrameTimingMetric samples;
//   media surfaces fail closed without an indexed corpus, and the restricted members
//   (conflictDialogShow — provider conflicts unsynthesizable on a SAF workspace,
//   widgetRefresh — `cmd appwidget` host binding unimplemented on the emulator build,
//   lanShareOpen — emulator NSD isolation) measure only their reachable sub-path and are
//   annotated as limitations in the device-perf report.
// - Given a pushed SAF corpus and `startupOnboarding`, when each iteration runs, then the
//   setup block `pm clear`s the package and removes the corpus `.lomo` store, and the
//   measured block cold-launches into the onboarding CTA, drives the production
//   DocumentsUI grant, and ends on the first reconciled memo card — the A5 first-install
//   measurement.
//
// - Given a granted workspace, when `profileGen` runs, then `BaselineProfileRule.collect`
//   runs twice: a launch-only journey pinned `includeInStartupProfile=true` emits the
//   startup-path rules as `*-startup-prof.txt` (the AGP dex-layout input), and the
//   cold-launch → scroll → drawer → filter → search → create-memo journey pinned `false`
//   emits the full rules as `*-baseline-prof.txt` (the P7a collection entry point — a
//   collection, not a metric scenario).
// - Given instrumentation arg `compilationMode` = none|partialRequire|full, when a
//   startup variant runs, then the matching `CompilationMode` applies — `partialRequire`
//   fails closed inside androidx when no baseline profile registered (P7c release A/B);
//   profileable release targets expose the same anchors as debug, and each A/B install
//   must activate the same SAF corpus before startup and scroll measurements run.
//
// Observable outcomes: androidx `*-benchmarkData.json` per run carrying startupMs /
// frameDurationCpuMs / frameOverrunMs percentiles; `*-baseline-prof.txt` /
// `*-startup-prof.txt` for `profileGen`.
// Excludes: release-signing work and Kotlin-side UI assertions.

package com.lomo.benchmark;

// Test Change Justification:
// Reason category: user-requested FAB placement changes the overlay's hit region.
// Old behavior/assertion being replaced: a centered list swipe started underneath the raised FAB.
// Why old assertion is no longer correct: the scroll assertion remains correct; its input hit
// the gesture button instead of the list, dispatching Back to top rather than scrolling.
// Coverage preserved by: the unchanged first-card-offscreen witness and reverse scroll.
// Why this is not fitting the test to the implementation: the measured action must target the
// list; the new origin stays within its bounds and outside the centered floating control.
// TDD proof: RED target/lomo/reports/manual/ui-restoration-162/profile-scroll-red.stderr.log
// reports that the first memo did not move offscreen.
// GREEN: just build android benchmark scroll passed all five iterations with the same witness.

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.Trace;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.benchmark.ExperimentalConfig;
import androidx.benchmark.macro.BaselineProfileMode;
import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.Metric;
import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.TraceSectionMetric;
import androidx.benchmark.macro.MemoryUsageMetric;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.benchmark.perfetto.PerfettoConfig;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.shell.Shell;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import kotlin.Unit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LomoMacrobenchmark {

    // Package under measurement: the installed Amper-built Debug or profileable Release APK.
    private static final String TARGET_PACKAGE = "com.lomo.app";

    // Production anchors (apps/android/app/src/benchmark/BenchmarkAnchorContract.kt).
    private static final String MAIN_ROOT = "benchmark_main_root";
    private static final String MAIN_DRAWER_BUTTON = "benchmark_main_drawer_button";
    private static final String MAIN_SEARCH_BUTTON = "benchmark_main_search_button";
    private static final String MAIN_FILTER_BUTTON = "benchmark_main_filter_button";
    private static final String MAIN_CREATE_FAB = "benchmark_main_create_fab";
    private static final String SEARCH_ROOT = "benchmark_search_root";
    private static final String SEARCH_INPUT = "benchmark_search_input";
    private static final String SEARCH_CLEAR = "benchmark_search_clear";
    private static final String INPUT_SHEET_ROOT = "benchmark_input_sheet_root";
    private static final String INPUT_EDITOR = "benchmark_input_editor";
    private static final String INPUT_SUBMIT = "benchmark_input_submit";
    private static final String MEMO_MENU_ROOT = "benchmark_memo_menu_root";
    private static final String SETTINGS_ROOT = "benchmark_settings_root";
    private static final String SETTINGS_BACK_BUTTON = "benchmark_settings_back_button";
    private static final String TAG_ROOT = "benchmark_tag_root";
    private static final String TRASH_ROOT = "benchmark_trash_root";
    private static final String DRAWER_SETTINGS = "benchmark_drawer_settings";
    private static final String DRAWER_TRASH = "benchmark_drawer_trash";
    private static final String FILTER_SHEET_ROOT = "benchmark_filter_sheet_root";
    private static final String SORT_OPTION_CREATED_TIME = "benchmark_sort_option_created_time";
    private static final String SORT_OPTION_UPDATED_TIME = "benchmark_sort_option_updated_time";
    private static final String SORT_SELECTED_CREATED_TIME = "benchmark_sort_selected_created_time";
    private static final String SORT_SELECTED_UPDATED_TIME = "benchmark_sort_selected_updated_time";
    private static final String MEMO_ACTION_PIN = "benchmark_memo_action_pin";
    private static final String MEMO_ACTION_COPY = "benchmark_memo_action_copy";
    private static final String MEMO_ACTION_SHARE_MENU = "benchmark_memo_action_share_menu";
    private static final String MEMO_ACTION_SHARE = "benchmark_memo_action_share";
    private static final String MEMO_ACTION_LAN_SHARE = "benchmark_memo_action_lan_share";
    private static final String MEMO_ACTION_HISTORY = "benchmark_memo_action_history";
    private static final String MEMO_ACTION_EDIT = "benchmark_memo_action_edit";
    private static final String MEMO_ACTION_DELETE = "benchmark_memo_action_delete";
    private static final String TRASH_ACTION_RESTORE = "benchmark_trash_action_restore";
    private static final String TRASH_ACTION_DELETE_PERMANENTLY =
            "benchmark_trash_action_delete_permanently";
    private static final String VERSION_HISTORY_ROOT = "benchmark_version_history_root";

    // §3-C device CUJ anchors (BenchmarkAnchorContract.kt — same literals on both sides).
    private static final String DAILY_REVIEW_ROOT = "benchmark_daily_review_root";
    private static final String GALLERY_ROOT = "benchmark_gallery_root";
    private static final String STATISTICS_ROOT = "benchmark_statistics_root";
    private static final String DRAWER_DAILY_REVIEW = "benchmark_drawer_daily_review";
    private static final String DRAWER_GALLERY = "benchmark_drawer_gallery";
    private static final String DRAWER_STATISTICS = "benchmark_drawer_statistics";
    private static final String SETTINGS_SYNC_BACKUP = "benchmark_settings_sync_backup";
    private static final String SETTINGS_SYNC_CENTER = "benchmark_settings_sync_center";
    // The Sync Center ids predate the benchmark_anchor_* convention — committed uitest
    // flows pin them verbatim (apps/android/uitest/flows/screens_tour.yaml).
    private static final String SYNC_CENTER_ROOT = "sync_center_root";
    private static final String SYNC_CENTER_TAB_CONFLICTS = "sync_center_tab_conflicts";
    private static final String SYNC_CONFLICT_ROOT = "benchmark_sync_conflict_root";
    private static final String GALLERY_REEL_ROOT = "benchmark_gallery_reel_root";
    private static final String LAN_SHARE_ROOT = "benchmark_lan_share_root";
    private static final String INPUT_EDITOR_EXPAND = "benchmark_input_editor_expand";

    private static final Pattern GALLERY_CELL_ID =
            Pattern.compile("^benchmark_gallery_cell_.+$");

    // Sharesheet owner on API 31+ — the chooser window belongs to the resolver package,
    // so `shareCardRender` proves the in-app render→persist→dispatch path ended at the
    // system handoff instead of counting foreign-package frames.
    private static final String INTENT_RESOLVER_PACKAGE = "com.android.intentresolver";
    // widgetRefresh delivers an explicit-component broadcast to the manifest-declared
    // receiver. The production update action (APPWIDGET_UPDATE) is a protected broadcast —
    // shell and instrumentation both hit SecurityException — and `cmd appwidget` is
    // unimplemented on this emulator build, so no host binding can be created. An explicit
    // component bypasses intent-filter matching and still drives the real dispatch path:
    // AMS broadcast → LomoWidgetReceiver.onReceive → `:widget` process spin-up.
    private static final String WIDGET_PING_ACTION = "com.lomo.app.action.BENCH_WIDGET_PING";
    private static final String WIDGET_RECEIVER = "com.lomo.app/.widget.LomoWidgetReceiver";
    private static final String WIDGET_PROCESS = "com.lomo.app:widget";
    // en-US values on the benchmark emulator image (strings.xml memo_auto_saved).
    private static final String MEMO_SAVED_TEXT = "Saved as memo";

    // Per-memo anchors carry a sanitized memo id, so the harness matches the prefix and picks
    // the first card in traversal (visual list) order. The card menu-button selector must
    // exclude `benchmark_memo_menu_root` — the open action sheet itself — or a tap "on a card
    // menu button" can land on the sheet and invoke an arbitrary action row.
    private static final Pattern MEMO_CARD_ID = Pattern.compile("^benchmark_memo_card_.+$");
    private static final Pattern MEMO_MENU_BUTTON_ID =
            Pattern.compile("^benchmark_memo_menu_(?!root$).+$");
    private static final Pattern DRAWER_TAG_ID = Pattern.compile("^benchmark_drawer_tag_.+$");
    private static final Pattern VERSION_HISTORY_CARD_ID =
            Pattern.compile("^benchmark_version_history_card_.+$");
    private static final Pattern VERSION_HISTORY_RESTORE_ID =
            Pattern.compile("^benchmark_version_history_restore_.+$");

    private static final int STARTUP_ITERATIONS = 5;
    private static final long WAIT_MS = TimeUnit.SECONDS.toMillis(15);
    // Short menu acknowledgement and the complete seeded-write publication window.
    private static final long MENU_DISMISS_WAIT_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long SEED_DEADLINE_MS = TimeUnit.SECONDS.toMillis(60);
    /**
     * First-touch media hydration on a SAF workspace: the gallery query needs the seeded
     * memo's `has_attachment` projection plus `resolveMemoImages` — the first resolve probes
     * every media file through the SAF provider and is far slower than the steady-state
     * cell render the measure block is meant to capture. Setup blocks warm it once.
     */
    private static final long MEDIA_HYDRATE_WAIT_MS = TimeUnit.SECONDS.toMillis(90);
    // A fresh SAF reconcile over a pushed device corpus pays per-memo durable `.lomo`
    // record writes through the DocumentsProvider (~0.35 memos/s observed on
    // emulator-5554) — a 240-memo corpus needs ~12 minutes before the workspace root
    // commits, far longer than an interaction wait. Repeat grants reconcile read-bound
    // over the retained `.lomo` store and come up in a fraction of this bound.
    private static final long INDEX_WAIT_MS = TimeUnit.SECONDS.toMillis(1_200);
    // Fallback SAF workspace root used by `prepareWorkspace`/`startupDirty` when the runner
    // does not pass `workspaceRoot` — mirrors the xtask default corpus name.
    private static final String DEFAULT_WORKSPACE_NAME = "lomo-perf-device";
    private static final String DEFAULT_WORKSPACE_ROOT =
            "/sdcard/Documents/" + DEFAULT_WORKSPACE_NAME;
    // Expand/collapse needs the spatial animation plus the off-thread expanded-snapshot load
    // to settle before the collapse click targets the grown card bounds.
    private static final long INTERACTION_SETTLE_MS = 1_200;
    // Keystroke cadence for the search CUJ — continuous typing, not a paste: each key event
    // runs InputField recomposition and feeds the debounced query pipeline.
    private static final long TYPE_INTERVAL_MS = 80;
    // Two accessibility snapshots this far apart that report identical bounds for the same
    // node prove the row is settled — enter/exit/placement animations move bounds every frame.
    private static final long SETTLE_POLL_MS = 250;
    // One menu-open attempt gives the sheet this long to appear before the button is
    // re-resolved — a missed tap must never burn the whole scenario timeout waiting on a
    // sheet that cannot open.
    private static final long MENU_OPEN_VERIFY_MS = 1_500;
    // Per-attempt bound for an action row to appear settled after its sheet root resolved —
    // staggered rows land in well under a second, so a longer wait only stalls the retry
    // cycle when the sheet dismissed itself instead of hosting the row.
    private static final long ACTION_TAP_BOUND_MS = 4_000;
    // A pushed→popped navigation transition keeps the destination's anchors out of the tree
    // for this long — below it a "not on main yet" probe cannot distinguish in-flight pop
    // from a stuck back stack.
    private static final long NAV_SETTLE_MS = 4_000;

    // Localized affordance label (ui-components cd_expand); the benchmark runs on the en-US
    // emulator image where it renders as "Expand".
    private static final String EXPAND_AFFORDANCE_TEXT = "Expand";

    private static final String EXPAND_MEMO_MARKER = "benchmark-expand-probe";
    // Long enough that `shouldShowMemoCardExpand` (600-char threshold) exposes the expand
    // affordance; plain prose lines so clicks never hit links/todos/images.
    private static final String EXPAND_MEMO_BODY =
            EXPAND_MEMO_MARKER + "\n"
                    + "probe line 01 card expansion measurement padding padding padding\n"
                    + "probe line 02 card expansion measurement padding padding padding\n"
                    + "probe line 03 card expansion measurement padding padding padding\n"
                    + "probe line 04 card expansion measurement padding padding padding\n"
                    + "probe line 05 card expansion measurement padding padding padding\n"
                    + "probe line 06 card expansion measurement padding padding padding\n"
                    + "probe line 07 card expansion measurement padding padding padding\n"
                    + "probe line 08 card expansion measurement padding padding padding\n"
                    + "probe line 09 card expansion measurement padding padding padding\n"
                    + "probe line 10 card expansion measurement padding padding padding\n"
                    + "probe line 11 card expansion measurement padding padding padding\n"
                    + "probe line 12 card expansion measurement padding padding padding";

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    @Rule
    public final BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    private static boolean applicationTraceChannel() {
        String channel = arg("measurementChannel", "redroid".equals(Build.HARDWARE)
                ? "application_trace" : "display_frames");
        if (!channel.equals("application_trace") && !channel.equals("display_frames")) {
            throw new IllegalArgumentException("Unknown measurement channel: " + channel);
        }
        return channel.equals("application_trace");
    }

    private static List<Metric> journeyMetrics() {
        List<Metric> metrics = new ArrayList<>();
        if (applicationTraceChannel()) {
            metrics.add(new TraceSectionMetric("Choreographer#doFrame%", TraceSectionMetric.Mode.Sum.INSTANCE, "appDoFrame", true));
            metrics.add(new TraceSectionMetric("Choreographer#doFrame%", TraceSectionMetric.Mode.Max.INSTANCE, "appDoFrame", true));
            metrics.add(new TraceSectionMetric("DrawFrame%", TraceSectionMetric.Mode.Sum.INSTANCE, "appRenderThread", true));
        } else {
            metrics.add(new FrameTimingMetric());
        }
        metrics.add(new MemoryUsageMetric(MemoryUsageMetric.Mode.Last));
        String systemScenario = arg("systemScenario", null);
        if (systemScenario != null) metrics.add(new SystemSnapshotMetric(systemScenario));
        return metrics;
    }

    private enum LaunchSurface { MAIN, ONBOARDING }

    private static List<Metric> startupMetrics() {
        if (!applicationTraceChannel()) return List.of(new StartupTimingMetric());
        // Cold processes can start and finish before Perfetto's process-stats poll.
        // Optional memory/draw metrics would make AndroidX discard that whole iteration.
        // Startup requires the harness-owned semantic interval; CUJs own memory/draw costs.
        return List.of(new TraceSectionMetric("lomoLaunchToReady", TraceSectionMetric.Mode.First.INSTANCE,
                "launchToReady", false));
    }

    private static List<Metric> onboardingMetrics() {
        if (!applicationTraceChannel()) return List.of(new StartupTimingMetric(), new FrameTimingMetric());
        List<Metric> metrics = new ArrayList<>(startupMetrics());
        metrics.add(new TraceSectionMetric("lomoOnboarding", TraceSectionMetric.Mode.First.INSTANCE,
                "onboardingToMain", false));
        return metrics;
    }

    private static void launchTarget(MacrobenchmarkScope scope, StartupMode mode, LaunchSurface surface) {
        if (!applicationTraceChannel()) {
            scope.startActivityAndWait(launchIntent());
            return;
        }
        Trace.beginSection("lomoLaunchToReady");
        try {
            Intent intent = launchIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.removeFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (mode != StartupMode.HOT) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            String output = shellText("am start -W -n "
                    + Objects.requireNonNull(intent.getComponent()).flattenToString()
                    + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f "
                    + intent.getFlags());
            if (output.contains("Error:")) throw new IllegalStateException(output);
            if (surface == LaunchSurface.ONBOARDING) {
                Objects.requireNonNull(scope.getDevice().wait(
                        Until.findObject(By.text("Go to Settings")), WAIT_MS), "onboarding not ready");
            } else {
                Objects.requireNonNull(scope.getDevice().wait(
                        Until.findObject(By.res(MAIN_ROOT)), INDEX_WAIT_MS),
                        "launched activity never reached the main root");
            }
        } catch (IOException error) {
            throw new IllegalStateException("launch command failed", error);
        } finally {
            Trace.endSection();
        }
    }

    @Test
    public void startup() {
        // A root can expose a skeleton before paging presents any memo. With an injected
        // corpus, keep a separate end-to-end interval so initialization cannot be hidden by it.
        boolean requiresCard = arg("workspaceRoot", null) != null;
        List<Metric> metrics = new ArrayList<>(startupMetrics());
        if (requiresCard) {
            metrics.add(new TraceSectionMetric("lomoLaunchToFirstCard", TraceSectionMetric.Mode.First.INSTANCE,
                    "launchToFirstCard", false));
        }
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                metrics,
                compilationMode(),
                StartupMode.COLD,
                STARTUP_ITERATIONS,
                scope -> {
                    Trace.beginSection("lomoLaunchToFirstCard");
                    try {
                        launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                        waitMain(scope.getDevice());
                        if (requiresCard) {
                            Objects.requireNonNull(scope.getDevice().wait(
                                    Until.findObject(By.res(MEMO_CARD_ID)), INDEX_WAIT_MS),
                                    "cold launch never presented its first indexed memo");
                        }
                    } finally {
                        Trace.endSection();
                    }
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void frame() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiObject2 searchButton = scope.getDevice().wait(
                            Until.findObject(By.res(MAIN_SEARCH_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(searchButton, "main search anchor missing");
                    searchButton.click();
                    UiObject2 searchRoot = scope.getDevice().wait(
                            Until.findObject(By.res(SEARCH_ROOT)), WAIT_MS);
                    Objects.requireNonNull(searchRoot, "search root anchor missing");
                    scope.getDevice().pressBack();
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void scroll() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                compilationMode(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    scrollMemoList(scope.getDevice());
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void create() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    createMemo(scope.getDevice(), "benchmark-cuj-probe");
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void expand() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    // Setup (unmeasured): guarantee at least one expandable memo exists.
                    // List order is memo-timestamp based, so the probe may land off-screen;
                    // the measure block therefore selects whatever visible card exposes the
                    // Expand affordance rather than locating the probe by content.
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    createMemo(scope.getDevice(), EXPAND_MEMO_BODY);
                    return Unit.INSTANCE;
                },
                scope -> {
                    UiObject2 card = scope.getDevice().wait(
                            Until.findObject(
                                    By.res(MEMO_CARD_ID)
                                            .hasDescendant(By.text(EXPAND_AFFORDANCE_TEXT))),
                            WAIT_MS);
                    Objects.requireNonNull(card, "no visible memo card with Expand affordance");
                    // Toggling opens through the affordance chip — the clickable parent of
                    // its label — because a card-center click could land on a link or todo
                    // checkbox. Once expanded the card's footer (and its Collapse chip)
                    // moves below the fold, so collapse uses the representative user gesture
                    // instead: tapping the expanded card body, which toggles via
                    // expandOnClick. The card is re-resolved by its concrete anchor id.
                    String cardAnchor = card.getResourceName();
                    clickAffordance(card, EXPAND_AFFORDANCE_TEXT);
                    SystemClock.sleep(INTERACTION_SETTLE_MS);
                    UiObject2 expanded = scope.getDevice().wait(
                            Until.findObject(By.res(cardAnchor)), WAIT_MS);
                    Objects.requireNonNull(expanded, "expanded memo card lost its anchor");
                    expanded.click();
                    SystemClock.sleep(INTERACTION_SETTLE_MS);
                    return Unit.INSTANCE;
                });
    }

    /** Cold process per sample; startup is setup, first and repeat opens have separate slices. */
    @Test
    public void menu() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                sheetJourneyMetrics("lomoMenu"),
                new CompilationMode.None(),
                null,
                5,
                scope -> {
                    scope.killProcess();
                    launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                    Objects.requireNonNull(scope.getDevice().wait(
                            Until.findObject(By.res(MEMO_CARD_ID)), INDEX_WAIT_MS),
                            "menu benchmark requires an indexed memo");
                    return Unit.INSTANCE;
                },
                scope -> {
                    UiDevice device = scope.getDevice();
                    measuredSheetOpen(device, MEMO_MENU_BUTTON_ID, MEMO_MENU_ROOT, "lomoMenuFirstOpen");
                    dismissMeasuredSheet(device, MEMO_MENU_ROOT);
                    measuredSheetOpen(device, MEMO_MENU_BUTTON_ID, MEMO_MENU_ROOT, "lomoMenuRepeatOpen");
                    dismissMeasuredSheet(device, MEMO_MENU_ROOT);
                    return Unit.INSTANCE;
                });
    }

    private static List<Metric> sheetJourneyMetrics(String name) {
        List<Metric> metrics = new ArrayList<>(journeyMetrics());
        metrics.add(new TraceSectionMetric(name + "FirstOpen", TraceSectionMetric.Mode.First.INSTANCE,
                "firstOpen", false));
        metrics.add(new TraceSectionMetric(name + "RepeatOpen", TraceSectionMetric.Mode.First.INSTANCE,
                "repeatOpen", false));
        return metrics;
    }

    private static void measuredSheetOpen(UiDevice device, Pattern button, String root, String trace) {
        UiObject2 target = Objects.requireNonNull(device.wait(Until.findObject(By.res(button)), WAIT_MS),
                "sheet trigger missing: " + button);
        Trace.beginSection(trace);
        try {
            target.click();
            Objects.requireNonNull(device.wait(Until.findObject(By.res(root)), WAIT_MS),
                    "sheet root missing: " + root);
            device.waitForIdle();
        } finally {
            Trace.endSection();
        }
    }

    private static void dismissMeasuredSheet(UiDevice device, String root) {
        device.pressBack();
        BenchmarkWaits.requireSatisfied(device.wait(Until.gone(By.res(root)), WAIT_MS),
                "sheet did not dismiss: " + root);
        device.waitForIdle();
    }

    @Test
    public void search() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiObject2 searchButton = scope.getDevice().wait(
                            Until.findObject(By.res(MAIN_SEARCH_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(searchButton, "main search anchor missing");
                    searchButton.click();
                    UiObject2 searchRoot = scope.getDevice().wait(
                            Until.findObject(By.res(SEARCH_ROOT)), WAIT_MS);
                    Objects.requireNonNull(searchRoot, "search root anchor missing");
                    // Sibling of SEARCH_ROOT under ExpandedFullScreenSearchBar — resolve on the
                    // device, not through the results surface (see class contract).
                    UiObject2 input = scope.getDevice().wait(
                            Until.findObject(By.res(SEARCH_INPUT)), WAIT_MS);
                    Objects.requireNonNull(input, "search input anchor missing");
                    input.click();
                    typeText(scope.getDevice(), "benchmark cuj probe");
                    scope.getDevice().pressBack();
                    return Unit.INSTANCE;
                });
    }

    // ---- Device-perf plane scenarios (driven by `just perf device`; also runnable via
    // `just build android benchmark <name>` since every scenario is a real JUnit method). ----

    @Test
    public void startupWarm() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                startupMetrics(),
                compilationMode(),
                StartupMode.WARM,
                STARTUP_ITERATIONS,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    waitMain(scope.getDevice());
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void startupHot() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                startupMetrics(),
                compilationMode(),
                StartupMode.HOT,
                STARTUP_ITERATIONS,
                scope -> {
                    launchTarget(scope, StartupMode.HOT, LaunchSurface.MAIN);
                    waitMain(scope.getDevice());
                    return Unit.INSTANCE;
                });
    }

    /**
     * Dirty-workspace cold start (audit item B1): every unmeasured setup block mutates the
     * injected workspace from the shell — one fresh unindexed document plus an append to the
     * first dated corpus doc — so each cold launch must reconcile a real unindexed change.
     */
    @Test
    public void startupDirty() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                startupMetrics(),
                new CompilationMode.None(),
                StartupMode.COLD,
                3,
                scope -> {
                    makeWorkspaceDirty();
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                    waitMain(scope.getDevice());
                    return Unit.INSTANCE;
                });
    }

    /**
     * A5 first-install onboarding: every iteration `pm clear`s the package (dropping the
     * persisted SAF tree grant and the app-private state) and removes the pushed corpus'
     * `.lomo` record store so the reconcile is write-bound like a true first mount. The
     * measured block then cold-launches into the onboarding CTA, drives the production
     * DocumentsUI grant flow (`grantPushedFolderInDocumentsUi`), and waits until the first
     * reconcile materializes a memo card. The application-trace channel reports launch
     * readiness and grant→reconcile→main readiness; display-capable devices additionally
     * use StartupTimingMetric/FrameTimingMetric. Requires a pushed SAF corpus: without
     * `workspaceName`/`workspaceRoot` the folder row never resolves and the scenario fails
     * closed inside the DocumentsUI drive.
     */
    @Test
    public void startupOnboarding() {
        measureFirstInstall(3, onboardingMetrics(), new ExperimentalConfig());
    }

    // This scenario needs semantic app markers and Binder waits, not the default
    // system-wide rendering/GC trace. Concurrent JNI/SAF work can overflow that stream
    // between file flushes, dropping the interval's start while the operation succeeds.
    private static final ExperimentalConfig WORKSPACE_TRACE_CONFIG = new ExperimentalConfig(
            new PerfettoConfig.Text("""
                    buffers { size_kb: 65536 fill_policy: RING_BUFFER }
                    data_sources {
                      config {
                        name: "linux.ftrace"
                        ftrace_config {
                          ftrace_events: "ftrace/print"
                          ftrace_events: "task/task_newtask"
                          ftrace_events: "task/task_rename"
                          atrace_categories: "binder_driver"
                          atrace_apps: "com.lomo.app"
                          atrace_apps: "com.lomo.benchmark.test"
                        }
                      }
                    }
                    data_sources {
                      config {
                        name: "linux.process_stats"
                        process_stats_config { scan_all_processes_on_start: true }
                      }
                    }
                    write_into_file: true
                    file_write_period_ms: 1000
                    flush_period_ms: 1000
                    """), null);

    /** Every iteration has a fresh app control store and a fresh corpus record store. */
    @Test
    public void workspaceFirstIndex() {
        measureFirstInstall(STARTUP_ITERATIONS, List.of(new TraceSectionMetric(
                "lomoWorkspaceActivation", TraceSectionMetric.Mode.First.INSTANCE,
                "workspaceFirstIndex", false)), WORKSPACE_TRACE_CONFIG);
    }

    @Test
    public void workspaceReopen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                List.of(new TraceSectionMetric("lomoWorkspaceReopen",
                        TraceSectionMetric.Mode.First.INSTANCE, "workspaceReopen", false)),
                new CompilationMode.None(), StartupMode.COLD, STARTUP_ITERATIONS,
                scope -> {
                    Trace.beginSection("lomoWorkspaceReopen");
                    try {
                        launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                        waitMain(scope.getDevice());
                        Objects.requireNonNull(scope.getDevice().wait(
                                Until.findObject(By.res(MEMO_CARD_ID)), INDEX_WAIT_MS),
                                "workspace reopen never populated its first memo card");
                    } finally {
                        Trace.endSection();
                    }
                    return Unit.INSTANCE;
                });
    }

    private void measureFirstInstall(
            int iterations, List<Metric> metrics, ExperimentalConfig traceConfig) {
        final String workspaceName =
                arg("workspaceName", DEFAULT_WORKSPACE_NAME);
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                metrics,
                iterations,
                traceConfig,
                new CompilationMode.None(),
                StartupMode.COLD,
                scope -> {
                    resetToFirstInstall();
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.COLD, LaunchSurface.ONBOARDING);
                    UiDevice device = scope.getDevice();
                    Trace.beginSection("lomoOnboarding");
                    try {
                        driveWorkspaceGrant(device, workspaceName);
                    } catch (IOException error) {
                        throw new IllegalStateException(
                                "SAF grant drive failed during onboarding measure", error);
                    } finally {
                        Trace.endSection();
                    }
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void drawer() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    UiObject2 drawerButton = device.wait(
                            Until.findObject(By.res(MAIN_DRAWER_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(drawerButton, "drawer button anchor missing");
                    drawerButton.click();
                    UiObject2 drawer = device.wait(
                            Until.findObject(By.res(DRAWER_SETTINGS)), WAIT_MS);
                    Objects.requireNonNull(drawer, "drawer never rendered (settings entry missing)");
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void filterSort() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                sheetJourneyMetrics("lomoFilter"),
                new CompilationMode.None(),
                null,
                5,
                scope -> {
                    scope.killProcess();
                    launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                    waitMain(scope.getDevice());
                    return Unit.INSTANCE;
                },
                scope -> {
                    UiDevice device = scope.getDevice();
                    measuredSheetOpen(device, Pattern.compile(MAIN_FILTER_BUTTON), FILTER_SHEET_ROOT,
                            "lomoFilterFirstOpen");
                    dismissMeasuredSheet(device, FILTER_SHEET_ROOT);
                    measuredSheetOpen(device, Pattern.compile(MAIN_FILTER_BUTTON), FILTER_SHEET_ROOT,
                            "lomoFilterRepeatOpen");
                    UiObject2 sheet = device.wait(
                            Until.findObject(By.res(FILTER_SHEET_ROOT)), WAIT_MS);
                    Objects.requireNonNull(sheet, "filter sheet root anchor missing");
                    // The selected sort option carries the *_selected_* anchor, so tapping
                    // whichever unselected option exists deterministically toggles the sort.
                    UiObject2 option = device.findObject(By.res(SORT_OPTION_UPDATED_TIME));
                    String expectedSelected = SORT_SELECTED_UPDATED_TIME;
                    if (option == null) {
                        option = device.findObject(By.res(SORT_OPTION_CREATED_TIME));
                        expectedSelected = SORT_SELECTED_CREATED_TIME;
                    }
                    Objects.requireNonNull(
                            option, "filter sheet exposes no unselected sort option anchor");
                    clickableAncestor(option).click();
                    UiObject2 selected = device.wait(
                            Until.findObject(By.res(expectedSelected)), WAIT_MS);
                    Objects.requireNonNull(
                            selected, "sort selection never updated to " + expectedSelected);
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    /**
     * Scroll-to-top uses the default upward quick-ball gesture. A first-card witness must
     * disappear after scrolling and reappear after the gesture; an empty/short list fails closed.
     */
    @Test
    public void scrollTop() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    UiObject2 mainRoot = waitMain(device);
                    UiObject2 firstCard = Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(MEMO_CARD_ID)), WAIT_MS),
                            "scroll-top needs a populated workspace");
                    String firstCardId = firstCard.getResourceName();
                    UiObject2 scrollable = mainRoot.findObject(By.scrollable(true));
                    if (scrollable == null) {
                        scrollable = mainRoot;
                    }
                    scrollable.setGestureMarginPercentage(0.1f);
                    scrollable.fling(Direction.DOWN);
                    scrollable.fling(Direction.DOWN);
                    BenchmarkWaits.requireSatisfied(
                            device.wait(Until.gone(By.res(firstCardId)), WAIT_MS),
                            "the memo list did not scroll far enough to hide the first card");
                    UiObject2 ball = Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(MAIN_CREATE_FAB)), WAIT_MS),
                            "quick ball is missing after scrolling");
                    android.graphics.Rect bounds = ball.getVisibleBounds();
                    device.swipe(bounds.centerX(), bounds.centerY(),
                            bounds.centerX(), bounds.centerY() - bounds.height() * 2, 12);
                    Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(firstCardId)), WAIT_MS),
                            "upward quick-ball gesture did not return to the first card");
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void tag() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    // Setup (unmeasured): guarantee the drawer exposes at least one tag row.
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    createMemo(
                            scope.getDevice(),
                            "benchmark tag probe " + SystemClock.uptimeMillis() + " #benchtag");
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    UiObject2 drawerButton = device.wait(
                            Until.findObject(By.res(MAIN_DRAWER_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(drawerButton, "drawer button anchor missing");
                    drawerButton.click();
                    UiObject2 tagRow = device.wait(
                            Until.findObject(By.res(DRAWER_TAG_ID)), WAIT_MS);
                    Objects.requireNonNull(tagRow, "drawer exposes no benchmark_drawer_tag_* row");
                    // Same slide-in race as openDrawerTo — settle before tapping the row.
                    SystemClock.sleep(INTERACTION_SETTLE_MS);
                    tagRow.click();
                    UiObject2 tagRoot = device.wait(
                            Until.findObject(By.res(TAG_ROOT)), WAIT_MS);
                    Objects.requireNonNull(tagRoot, "tag filter screen anchor missing");
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    /**
     * Trash enter + one restore + one permanent delete. The setup block stages two fresh trash
     * items per iteration so the measure block never depends on corpus contents.
     */
    @Test
    public void trash() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    String stamp = Long.toString(SystemClock.uptimeMillis());
                    createMemo(device, "benchmark trash probe a " + stamp);
                    deleteTopMemo(device);
                    createMemo(device, "benchmark trash probe b " + stamp);
                    deleteTopMemo(device);
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_TRASH);
                    UiObject2 trashRoot = device.wait(
                            Until.findObject(By.res(TRASH_ROOT)), WAIT_MS);
                    Objects.requireNonNull(trashRoot, "trash screen anchor missing");
                    clickTrashCardAction(device, TRASH_ACTION_RESTORE);
                    clickTrashCardAction(device, TRASH_ACTION_DELETE_PERMANENTLY);
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    /**
     * Version-history sheet open + one revision restore. Setup seeds a memo and edits it once
     * through the production editor sheet so the restore interaction card is guaranteed.
     */
    @Test
    public void versionHistory() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    String stamp = Long.toString(SystemClock.uptimeMillis());
                    createMemo(device, "benchmark vh probe " + stamp);
                    menuActionExpecting(
                            device,
                            MEMO_ACTION_EDIT,
                            By.res(INPUT_SHEET_ROOT),
                            "editor sheet after edit action");
                    UiObject2 editor =
                            device.wait(Until.findObject(By.res(INPUT_EDITOR)), WAIT_MS);
                    Objects.requireNonNull(editor, "editor field anchor missing");
                    editor.setText("benchmark vh probe " + stamp + " edited");
                    UiObject2 submit =
                            device.wait(Until.findObject(By.res(INPUT_SUBMIT)), WAIT_MS);
                    Objects.requireNonNull(submit, "editor submit anchor missing");
                    submit.click();
                    // Same settle contract as createMemo — the measure block drives the memo
                    // list and a still-dismissing sheet window masks its anchors. The
                    // durable-commit snackbar is the edit's proof: a rejected write leaves
                    // no revision behind and the history row would never appear.
                    BenchmarkWaits.requireSatisfied(
                            device.wait(Until.gone(By.res(INPUT_SHEET_ROOT)), WAIT_MS),
                            "editor sheet stayed open after submit");
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.text(MEMO_SAVED_TEXT)), WAIT_MS),
                            "autosave commit snackbar never appeared — the seeded edit "
                                    + "was not durably committed");
                    SystemClock.sleep(INTERACTION_SETTLE_MS);
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    menuActionExpecting(
                            device,
                            MEMO_ACTION_HISTORY,
                            By.res(VERSION_HISTORY_ROOT),
                            "version history sheet");
                    UiObject2 historyCard = device.wait(
                            Until.findObject(By.res(VERSION_HISTORY_CARD_ID)), WAIT_MS);
                    Objects.requireNonNull(
                            historyCard, "version history lists no revision cards");
                    UiObject2 restore = device.wait(
                            Until.findObject(By.res(VERSION_HISTORY_RESTORE_ID)), WAIT_MS);
                    Objects.requireNonNull(
                            restore,
                            "no restorable revision card — the seeded memo must carry a "
                                    + "previous revision after the setup edit");
                    restore.click();
                    // The sheet dismisses itself once the restore commits. Wait for that
                    // observable boundary instead of a fixed sleep — the app-scoped
                    // coordinator keeps Loaded(isRestoring=true) while the op is in flight,
                    // so a blind back-press races the dialog and leaks the sheet into the
                    // next iteration's relaunched activity.
                    BenchmarkWaits.requireSatisfied(
                            device.wait(
                                    Until.gone(By.res(VERSION_HISTORY_ROOT)), WAIT_MS),
                            "version history sheet stayed open after restore");
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void settings() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_SETTINGS);
                    UiObject2 settingsRoot = device.wait(
                            Until.findObject(By.res(SETTINGS_ROOT)), WAIT_MS);
                    Objects.requireNonNull(settingsRoot, "settings root anchor missing");
                    UiObject2 back = device.wait(
                            Until.findObject(By.res(SETTINGS_BACK_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(back, "settings back anchor missing");
                    back.click();
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    @Test
    public void searchClear() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    UiObject2 searchButton = device.wait(
                            Until.findObject(By.res(MAIN_SEARCH_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(searchButton, "main search anchor missing");
                    searchButton.click();
                    UiObject2 searchRoot = device.wait(
                            Until.findObject(By.res(SEARCH_ROOT)), WAIT_MS);
                    Objects.requireNonNull(searchRoot, "search root anchor missing");
                    UiObject2 input = device.wait(
                            Until.findObject(By.res(SEARCH_INPUT)), WAIT_MS);
                    Objects.requireNonNull(input, "search input anchor missing");
                    input.click();
                    typeText(device, "clear probe");
                    UiObject2 clear = device.wait(
                            Until.findObject(By.res(SEARCH_CLEAR)), WAIT_MS);
                    Objects.requireNonNull(clear, "search clear anchor missing with text entered");
                    clear.click();
                    BenchmarkWaits.requireSatisfied(
                            device.wait(Until.gone(By.res(SEARCH_CLEAR)), WAIT_MS),
                            "search clear anchor stayed visible after clearing");
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    /**
     * The reliably-drivable memo-menu subset: copy text and pin toggle (share leaves the app
     * into a system chooser and history/edit/delete own their own CUJs or setup paths).
     */
    @Test
    public void memoActions() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    menuActionClosing(device, MEMO_ACTION_COPY, "copy action");
                    menuActionClosing(device, MEMO_ACTION_PIN, "pin action");
                    return Unit.INSTANCE;
                });
    }

    // ---- §3-C CUJ scenarios (device-measurement-plan): screens and interactions whose
    // anchors needed production changes. Media-driven surfaces (gallery/reel/image viewer)
    // fail closed on a workspace without indexed images — pass --corpus device. ----

    /**
     * Full-editor open + autosave CUJ: memo menu → Edit opens the input sheet in compact
     * mode; the expand toggle grows it to the full editor (the toggle leaves composition —
     * its `gone` is the expanded proof); a setText edit feeds the 500 ms draft-autosave
     * debounce (MemoEditorDraftAutosaveEffect); submit dismisses the sheet and the durable
     * commit surfaces the `memo_auto_saved` snackbar — the measured window's end boundary.
     */
    @Test
    public void editorOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    createMemo(scope.getDevice(), "benchmark editor-open probe");
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openTopMemoMenu(device);
                    clickMenuAction(device, MEMO_ACTION_EDIT);
                    Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(INPUT_SHEET_ROOT)), WAIT_MS),
                            "editor sheet anchor missing after edit action");
                    UiObject2 expand = device.wait(
                            Until.findObject(By.res(INPUT_EDITOR_EXPAND)), WAIT_MS);
                    Objects.requireNonNull(expand, "editor expand toggle anchor missing");
                    expand.click();
                    BenchmarkWaits.requireSatisfied(
                            device.wait(Until.gone(By.res(INPUT_EDITOR_EXPAND)), WAIT_MS),
                            "editor expand toggle stayed visible — sheet never expanded");
                    UiObject2 editor = device.wait(
                            Until.findObject(By.res(INPUT_EDITOR)), WAIT_MS);
                    Objects.requireNonNull(editor, "expanded editor field anchor missing");
                    editor.setText(editor.getText() + "\nbenchmark autosave probe");
                    // The draft autosave debounce is 500 ms — the settle sleep keeps that
                    // write inside the measured window before the commit tap.
                    SystemClock.sleep(INTERACTION_SETTLE_MS);
                    UiObject2 submit = device.wait(
                            Until.findObject(By.res(INPUT_SUBMIT)), WAIT_MS);
                    Objects.requireNonNull(submit, "editor submit anchor missing");
                    submit.click();
                    BenchmarkWaits.requireSatisfied(
                            device.wait(Until.gone(By.res(INPUT_SHEET_ROOT)), WAIT_MS),
                            "editor sheet stayed open after submit");
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.text(MEMO_SAVED_TEXT)), WAIT_MS),
                            "autosave commit snackbar never appeared — the durable write "
                                    + "was not observed");
                    return Unit.INSTANCE;
                });
    }

    /** Daily Review: drawer entry → pager-root wait → horizontal paging + in-page scroll. */
    @Test
    public void reviewOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_DAILY_REVIEW);
                    UiObject2 reviewRoot = device.wait(
                            Until.findObject(By.res(DAILY_REVIEW_ROOT)), WAIT_MS);
                    Objects.requireNonNull(reviewRoot, "daily review root anchor missing");
                    // The review surface is a HorizontalPager of per-memo pages; each page
                    // scrolls vertically inside.
                    swipeHorizontal(reviewRoot, /*forward=*/ true);
                    swipeHorizontal(reviewRoot, /*forward=*/ false);
                    UiObject2 pageBody = reviewRoot.findObject(By.scrollable(true));
                    if (pageBody != null) {
                        pageBody.setGestureMarginPercentage(0.1f);
                        pageBody.scroll(Direction.DOWN, 0.5f);
                    }
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /** Statistics page: drawer entry → root wait → vertical scroll pass. */
    @Test
    public void statsOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_STATISTICS);
                    UiObject2 statsRoot = device.wait(
                            Until.findObject(By.res(STATISTICS_ROOT)), WAIT_MS);
                    Objects.requireNonNull(statsRoot, "statistics root anchor missing");
                    UiObject2 scroller = device.wait(
                            Until.findObject(
                                    By.scrollable(true)
                                            .hasAncestor(By.res(STATISTICS_ROOT))),
                            WAIT_MS);
                    Objects.requireNonNull(
                            scroller, "statistics page exposes no scrollable surface");
                    scroller.setGestureMarginPercentage(0.1f);
                    scroller.scroll(Direction.DOWN, 0.6f);
                    scroller.scroll(Direction.UP, 0.6f);
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /**
     * Gallery grid scroll — requires indexed media (`--corpus device`): without a
     * `benchmark_gallery_cell_*` node the scenario fails closed rather than measuring the
     * empty-gallery state.
     */
    @Test
    public void galleryScroll() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    // Same first-touch SAF media hydrate as reelOpen — warm it outside the
                    // measured window so the block starts from a populated grid.
                    openDrawerTo(device, DRAWER_GALLERY);
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.res(GALLERY_CELL_ID)),
                                    MEDIA_HYDRATE_WAIT_MS),
                            "gallery grid lists no media cells — media-bearing corpus "
                                    + "required (--corpus device)");
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_GALLERY);
                    UiObject2 galleryRoot = device.wait(
                            Until.findObject(By.res(GALLERY_ROOT)), WAIT_MS);
                    Objects.requireNonNull(galleryRoot, "gallery root anchor missing");
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.res(GALLERY_CELL_ID)), WAIT_MS),
                            "gallery grid lists no media cells — media-bearing corpus "
                                    + "required (--corpus device)");
                    UiObject2 grid = galleryRoot.findObject(By.scrollable(true));
                    if (grid == null) {
                        grid = galleryRoot;
                    }
                    grid.setGestureMarginPercentage(0.1f);
                    grid.scroll(Direction.DOWN, 0.7f);
                    grid.scroll(Direction.UP, 0.7f);
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /**
     * Gallery reel open + paging: tap a settled media cell (the cell carries the anchor so
     * the tap cannot land on a loading placeholder), wait for the reel surface, then page
     * vertically (next memo) and horizontally (next image of the memo). The setup block
     * seeds a memo that references the corpus media path so the grid is non-empty by
     * construction on the media-bearing device corpus; on a media-less workspace the
     * measure block still fails closed on the missing cell anchor.
     */
    @Test
    public void reelOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    createMemo(
                            device,
                            "benchmark reel probe\n![shot](media/img/att-0000.jpg)");
                    // First SAF media hydrate is seconds-scale (resolveMemoImages probes the
                    // pushed media dir through the provider); warm it here so the measured
                    // block starts from a populated grid instead of racing the projection.
                    openDrawerTo(device, DRAWER_GALLERY);
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.res(GALLERY_CELL_ID)),
                                    MEDIA_HYDRATE_WAIT_MS),
                            "gallery grid never materialized a media cell after seeding");
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_GALLERY);
                    UiObject2 galleryRoot = device.wait(
                            Until.findObject(By.res(GALLERY_ROOT)), WAIT_MS);
                    Objects.requireNonNull(galleryRoot, "gallery root anchor missing");
                    tapFresh(device, GALLERY_CELL_ID, "gallery media cell");
                    UiObject2 reelRoot = device.wait(
                            Until.findObject(By.res(GALLERY_REEL_ROOT)), WAIT_MS);
                    Objects.requireNonNull(reelRoot, "gallery reel root anchor missing");
                    swipeVertical(reelRoot, /*forward=*/ true);
                    swipeHorizontal(reelRoot, /*forward=*/ true);
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    /**
     * Image viewer open + zoom — the single-memo reel mode of the same surface. Setup seeds
     * a memo that references the corpus media path so the card renders a real image block;
     * the measure block opens it through the production `onImageClick` path and pinch-zooms
     * the reel root.
     */
    @Test
    public void imageViewerOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    createMemo(
                            device,
                            "benchmark image viewer probe\n"
                                    + "![shot](media/img/att-0000.jpg)");
                    // The image's contentDescription only exists once the memo card has
                    // projected into the list AND the picture decoded — the first resolve
                    // probes media through the SAF provider, so this wait belongs outside
                    // the measured window.
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.desc("shot")),
                                    MEDIA_HYDRATE_WAIT_MS),
                            "seeded image never decoded into the memo card — "
                                    + "media-bearing corpus required (--corpus device)");
                    return Unit.INSTANCE;
                },
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    // The markdown image's contentDescription is its alt text; the clickable
                    // parent carries the onImageClick navigation.
                    UiObject2 image = device.wait(
                            Until.findObject(By.desc("shot")), WAIT_MS);
                    Objects.requireNonNull(
                            image,
                            "no rendered image block — media-bearing corpus required "
                                    + "(--corpus device)");
                    clickableAncestor(image).click();
                    UiObject2 reelRoot = device.wait(
                            Until.findObject(By.res(GALLERY_REEL_ROOT)), WAIT_MS);
                    Objects.requireNonNull(
                            reelRoot, "image viewer (single-memo reel) root missing");
                    reelRoot.pinchOpen(0.5f);
                    reelRoot.pinchClose(0.5f);
                    device.pressBack();
                    return Unit.INSTANCE;
                });
    }

    /**
     * Sync Center open: drawer → settings → "Sync & Backup" sub-page → Sync Center row →
     * `sync_center_root`. The settings rows sit at the bottom of scrollable pages, so each
     * hop scrolls its surface until the anchor resolves (same journey as the committed
     * `screens_tour` uitest flow).
     */
    @Test
    public void syncCenterOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_SETTINGS);
                    UiObject2 settingsRoot = device.wait(
                            Until.findObject(By.res(SETTINGS_ROOT)), WAIT_MS);
                    Objects.requireNonNull(settingsRoot, "settings root anchor missing");
                    scrollUntilAnchorVisible(device, SETTINGS_SYNC_BACKUP);
                    clickWhenVisible(
                            device,
                            By.res(SETTINGS_SYNC_BACKUP),
                            "settings sync-backup row");
                    scrollUntilAnchorVisible(device, SETTINGS_SYNC_CENTER);
                    clickWhenVisible(
                            device,
                            By.res(SETTINGS_SYNC_CENTER),
                            "settings sync-center row");
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.res(SYNC_CENTER_ROOT)), WAIT_MS),
                            "sync center root anchor missing");
                    // Sync Center sits two pushed nav levels under Settings — a fixed
                    // pressBack pair undershoots into the sync-backup page, which then
                    // starves waitMain's long index bound.
                    backToMain(device);
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /**
     * Share-card render CUJ: memo menu → Share as image. The measured surface ends at the
     * Sharesheet handoff — rendering + PNG persist + chooser dispatch execute in-app; the
     * chooser window belongs to `com.android.intentresolver` and is never attributed to the
     * target package. The wait for the resolver window proves the dispatch happened; a
     * render failure falls back to a text chooser inside the same surface, so the report
     * reads this as dispatch-path evidence only.
     */
    @Test
    public void shareCardRender() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    menuSubActionExpecting(
                            device,
                            MEMO_ACTION_SHARE_MENU,
                            MEMO_ACTION_SHARE,
                            By.pkg(INTENT_RESOLVER_PACKAGE),
                            "system Sharesheet — share dispatch");
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /**
     * Conflict dialog display — RESTRICTED on the SAF workspace: provider-backed conflicts
     * need a configured remote sync backend, which the corpus workspace cannot synthesize
     * deterministically. The measurable subset is the production conflict surface journey:
     * Sync Center → conflicts pane (empty-state included — it is the real production pane
     * for a conflict-free workspace). The dialog anchor (`benchmark_sync_conflict_root`)
     * stays pinned in SyncConflictDialogHost so a provider-backed run resolves it.
     */
    @Test
    public void conflictDialogShow() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    openDrawerTo(device, DRAWER_SETTINGS);
                    Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(SETTINGS_ROOT)), WAIT_MS),
                            "settings root anchor missing");
                    scrollUntilAnchorVisible(device, SETTINGS_SYNC_BACKUP);
                    clickWhenVisible(
                            device,
                            By.res(SETTINGS_SYNC_BACKUP),
                            "settings sync-backup row");
                    scrollUntilAnchorVisible(device, SETTINGS_SYNC_CENTER);
                    clickWhenVisible(
                            device,
                            By.res(SETTINGS_SYNC_CENTER),
                            "settings sync-center row");
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.res(SYNC_CENTER_ROOT)), WAIT_MS),
                            "sync center root anchor missing");
                    clickWhenVisible(
                            device,
                            By.res(SYNC_CENTER_TAB_CONFLICTS),
                            "sync center conflicts tab");
                    // The conflicts pane is selected — the empty-state content is the real
                    // surface for a conflict-free workspace. `benchmark_sync_conflict_root`
                    // cannot be waited on: it only exists while a provider conflict is
                    // showing, which this workspace cannot produce.
                    SystemClock.sleep(INTERACTION_SETTLE_MS);
                    device.pressBack();
                    device.pressBack();
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /**
     * Widget refresh dispatch — RESTRICTED: `cmd appwidget` is unimplemented on this
     * emulator build, so no host binding can be created and no RemoteViews render is
     * exercised; the production APPWIDGET_UPDATE action is a protected broadcast only the
     * system may send (shell gets SecurityException). Measured instead: an
     * explicit-component broadcast to the manifest-declared receiver — the same AMS →
     * LomoWidgetReceiver.onReceive dispatch the update broadcast takes — plus the
     * `com.lomo.app:widget` process spin-up, proven by `ps` — a process that never starts
     * fails the scenario closed. Frame timing covers the foreground window around the
     * broadcast.
     */
    @Test
    public void widgetRefresh() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    waitMain(device);
                    try {
                        shellText(
                                "am broadcast -a " + WIDGET_PING_ACTION
                                        + " -n " + WIDGET_RECEIVER);
                    } catch (IOException error) {
                        throw new IllegalStateException("widget update broadcast failed", error);
                    }
                    long deadline = SystemClock.uptimeMillis() + WAIT_MS;
                    while (true) {
                        String processes;
                        try {
                            processes = shellText("ps -A -o NAME");
                        } catch (IOException error) {
                            throw new IllegalStateException("process listing failed", error);
                        }
                        if (processes.contains(WIDGET_PROCESS)) {
                            break;
                        }
                        if (SystemClock.uptimeMillis() >= deadline) {
                            throw new IllegalStateException(
                                    WIDGET_PROCESS
                                            + " never started after the update broadcast — "
                                            + "the receiver dispatch was not observed");
                        }
                        SystemClock.sleep(SETTLE_POLL_MS);
                    }
                    return Unit.INSTANCE;
                });
    }

    /**
     * LAN share page open — always `emulator_limited` in the report: the emulator's virtual
     * NIC isolates multicast/NSD so peer discovery never resolves real devices; the measured
     * surface is the memo-menu → Share page-open frames only.
     */
    @Test
    public void lanShareOpen() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                journeyMetrics(),
                new CompilationMode.None(),
                StartupMode.WARM,
                5,
                scope -> {
                    launchTarget(scope, StartupMode.WARM, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    menuSubActionExpecting(
                            device,
                            MEMO_ACTION_SHARE_MENU,
                            MEMO_ACTION_LAN_SHARE,
                            By.res(LAN_SHARE_ROOT),
                            "LAN share page root");
                    backToMain(device);
                    waitMain(device);
                    return Unit.INSTANCE;
                });
    }

    /**
     * One-shot DocumentsUI grant for a pre-pushed SAF corpus — NOT a benchmark scenario: xtask
     * invokes it via `-P...class=com.lomo.benchmark.LomoMacrobenchmark#prepareWorkspace` after
     * `adb push` so the app takes the persistable tree permission and reconciles the corpus
     * before any scenario runs. Fails closed when the picker, the folder row, or the index
     * never materialize.
     */
    @Test
    public void prepareWorkspace() throws IOException {
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String workspaceName =
                InstrumentationRegistry.getArguments()
                        .getString("workspaceName", DEFAULT_WORKSPACE_NAME);
        Intent intent = launchIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().getContext().startActivity(intent);
        UiObject2 cta = device.wait(Until.findObject(By.text("Go to Settings")), WAIT_MS);
        if (cta == null) {
            // Workspace already configured — a previous injection may already be active, but
            // "a workspace exists" is not proof the pushed corpus is the active root: a stale
            // grant to a different folder would still reach a memo list and silently
            // misattribute every measurement this run records. Verify the committed root
            // resolves to the pushed folder name through the production Settings screen, switching
            // explicitly when this request targets a different root.
            //
            // First wait for a materialized card: that is the reconcile-complete signal.
            // While the engine session is still Loading the splash condition holds every
            // frame (and the navigation tree does not accept input), so a drawer click in
            // that window dead-ends and the settings row never resolves — observed as a
            // run of zero-frame launches after a fresh corpus push.
            waitMain(device);
            UiObject2 card = device.wait(
                    Until.findObject(By.res(MEMO_CARD_ID)), INDEX_WAIT_MS);
            Objects.requireNonNull(
                    card,
                    "workspace is configured but no memo card materialized after "
                            + "the pushed corpus reconcile");
            openDrawerTo(device, DRAWER_SETTINGS);
            Objects.requireNonNull(
                    device.wait(Until.findObject(By.res(SETTINGS_ROOT)), WAIT_MS),
                    "settings root anchor missing while verifying the active workspace");
            UiObject2 configuredRoot = device.wait(
                    Until.findObject(By.text(Pattern.compile(
                            ".*" + Pattern.quote(workspaceName) + ".*"))),
                    NAV_SETTLE_MS);
            if (configuredRoot == null) {
                // --backend saf explicitly requests this corpus. A prior Direct/other-root
                // run must switch through the same Settings + DocumentsUI workflow as
                // onboarding, then wait for the selected root to commit before measuring.
                grantWorkspaceFromSettings(device, workspaceName);
                return;
            }
            UiObject2 back = device.wait(
                    Until.findObject(By.res(SETTINGS_BACK_BUTTON)), WAIT_MS);
            Objects.requireNonNull(back, "settings back anchor missing");
            back.click();
            waitMain(device);
            return;
        }
        driveWorkspaceGrant(device, workspaceName);
    }

    /**
     * The production first-install grant journey shared by `prepareWorkspace` and the A5
     * `startupOnboarding` scenario: onboarding CTA → Settings "Memo Directory" →
     * DocumentsUI folder pick → Allow → committed-root wait → first indexed memo card.
     * Unlike `prepareWorkspace` the CTA is mandatory — a caller on an already-configured
     * workspace would otherwise measure a no-op and fabricate an onboarding number.
     */
    private static void driveWorkspaceGrant(UiDevice device, String workspaceName)
            throws IOException {
        UiObject2 cta = device.wait(Until.findObject(By.text("Go to Settings")), WAIT_MS);
        Objects.requireNonNull(
                cta,
                "onboarding 'Go to Settings' CTA missing — the app is not on the "
                        + "first-install surface");
        cta.click();
        grantWorkspaceFromSettings(device, workspaceName);
    }

    private static void grantWorkspaceFromSettings(UiDevice device, String workspaceName)
            throws IOException {
        UiObject2 memoDirectory = device.wait(
                Until.findObject(By.text("Memo Directory")), WAIT_MS);
        Objects.requireNonNull(memoDirectory, "Memo Directory settings row missing");
        memoDirectory.click();

        grantPushedFolderInDocumentsUi(device, workspaceName);

        // The confirmation button's accessibility text is all-caps on this API level
        // ("ALLOW") — match case-insensitively so older "Allow" variants still resolve.
        UiObject2 allow = device.wait(
                Until.findObject(By.text(Pattern.compile("(?i)^allow$"))), WAIT_MS);
        Objects.requireNonNull(allow, "DocumentsUI grant dialog Allow button missing");
        Trace.beginSection("lomoWorkspaceActivation");
        try {
            allow.click();
            UiObject2 settingsRoot = device.wait(
                    Until.findObject(By.res(SETTINGS_ROOT)), WAIT_MS);
            Objects.requireNonNull(settingsRoot, "settings root missing after SAF grant");
            // Wait for the committed root before publishing activation duration. The
            // independent workspace_empty flow covers leaving before this commit.
            Objects.requireNonNull(
                    device.wait(
                            Until.findObject(By.text(Pattern.compile(
                                    ".*" + Pattern.quote(workspaceName) + ".*"))),
                            INDEX_WAIT_MS),
                    "workspace root never committed after SAF grant of " + workspaceName);
            device.pressBack();
            waitMain(device);
            UiObject2 card = device.wait(Until.findObject(By.res(MEMO_CARD_ID)), INDEX_WAIT_MS);
            Objects.requireNonNull(
                    card, "pushed corpus never indexed — no memo card appeared after the grant");
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Package-state reset for A5: `pm clear` drops the app's files/prefs AND the persisted
     * SAF tree grant (package-scoped), and removing the pushed corpus' `.lomo` store makes
     * the next reconcile write-bound — the true first-mount shape, not a re-attach.
     */
    private static void resetToFirstInstall() {
        String root = InstrumentationRegistry.getArguments()
                .getString("workspaceRoot", DEFAULT_WORKSPACE_ROOT);
        try {
            String cleared = shellText("pm clear " + TARGET_PACKAGE);
            if (!"Success".equals(cleared.trim())) {
                throw new IOException("first-install app reset did not succeed: " + cleared);
            }
            shellText("rm -rf \"" + root + "/.lomo\"\ntest ! -e \"" + root + "/.lomo\"");
        } catch (IOException error) {
            throw new IllegalStateException("first-install reset failed", error);
        }
    }

    /**
     * Measured baseline-profile collection journey (P7a) — a collection entry point, not a
     * metric scenario: `just perf device profile` is the only caller that knows how to read
     * its `*-baseline-prof.txt`/`*-startup-prof.txt` output files. `BaselineProfileRule`
     * emits exactly one artifact per collect and `includeInStartupProfile` only chooses the
     * filename — so this test collects twice:
     *
     * <ol>
     *   <li>`includeInStartupProfile=true` over a launch-only journey → `lomo-startup-prof
     *       .txt`, the startup-path subset AGP feeds R8 for DEX layout. The journey stays
     *       deliberately short: CUJ rules in a startup profile would overflow the primary
     *       dex instead of tightening startup.
     *   <li>`includeInStartupProfile=false` over the full journey → `lomo-baseline-prof
     *       .txt`, the measured `baseline.prof` input — a superset of the startup rules by
     *       construction since it re-covers the cold launch.
     * </ol>
     *
     * Each collect re-runs its block until the extracted rules stabilize. The workspace
     * must already be granted — on an unconfigured app the anchor waits fail closed
     * instead of profiling onboarding.
     */
    @Test
    public void profileGen() {
        baselineProfileRule.collectWithResults(
                TARGET_PACKAGE,
                /* maxIterations */ 15,
                /* stableIterations */ 3,
                /* outputFilePrefix */ "lomo-startup",
                /* includeInStartupProfile */ true,
                scope -> {
                    scope.pressHome();
                    launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                    waitMain(scope.getDevice());
                    return Unit.INSTANCE;
                });
        baselineProfileRule.collectWithResults(
                TARGET_PACKAGE,
                /* maxIterations */ 15,
                /* stableIterations */ 3,
                /* outputFilePrefix */ "lomo-cuj",
                /* includeInStartupProfile */ false,
                scope -> {
                    launchTarget(scope, StartupMode.COLD, LaunchSurface.MAIN);
                    UiDevice device = scope.getDevice();
                    scrollMemoList(device);

                    // Drawer open/close (no navigation — the journey stays on main).
                    UiObject2 drawerButton = device.wait(
                            Until.findObject(By.res(MAIN_DRAWER_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(drawerButton, "drawer button anchor missing");
                    drawerButton.click();
                    Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(DRAWER_SETTINGS)), WAIT_MS),
                            "drawer never rendered (settings entry missing)");
                    device.pressBack();
                    BenchmarkWaits.requireSatisfied(
                            device.wait(
                                    Until.gone(By.res(DRAWER_SETTINGS)), WAIT_MS),
                            "drawer stayed open after back press");

                    // Filter sheet open/close.
                    UiObject2 filterButton = device.wait(
                            Until.findObject(By.res(MAIN_FILTER_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(filterButton, "filter button anchor missing");
                    filterButton.click();
                    Objects.requireNonNull(
                            device.wait(
                                    Until.findObject(By.res(FILTER_SHEET_ROOT)), WAIT_MS),
                            "filter sheet root anchor missing");
                    device.pressBack();
                    BenchmarkWaits.requireSatisfied(
                            device.wait(
                                    Until.gone(By.res(FILTER_SHEET_ROOT)), WAIT_MS),
                            "filter sheet stayed open after back press");

                    // Search surface + real keystrokes through the debounced pipeline. With
                    // the input focused, one back press may only dismiss the IME — press
                    // again until the search surface is actually gone so the main screen
                    // (and its FAB anchor) is reachable.
                    UiObject2 searchButton = device.wait(
                            Until.findObject(By.res(MAIN_SEARCH_BUTTON)), WAIT_MS);
                    Objects.requireNonNull(searchButton, "main search anchor missing");
                    searchButton.click();
                    Objects.requireNonNull(
                            device.wait(Until.findObject(By.res(SEARCH_ROOT)), WAIT_MS),
                            "search root anchor missing");
                    UiObject2 input = device.wait(
                            Until.findObject(By.res(SEARCH_INPUT)), WAIT_MS);
                    Objects.requireNonNull(input, "search input anchor missing");
                    input.click();
                    typeText(device, "profile journey");
                    device.pressBack();
                    if (!Boolean.TRUE.equals(
                            device.wait(Until.gone(By.res(SEARCH_ROOT)), 2_000))) {
                        device.pressBack();
                        BenchmarkWaits.requireSatisfied(
                                device.wait(Until.gone(By.res(SEARCH_ROOT)), WAIT_MS),
                                "search surface stayed open after back presses");
                    }

                    // Create one memo through the production input sheet, then leave.
                    createMemo(device, "benchmark profile journey");
                    for (String[] destination : new String[][] {
                            {DRAWER_DAILY_REVIEW, DAILY_REVIEW_ROOT},
                            {DRAWER_STATISTICS, STATISTICS_ROOT},
                            {DRAWER_GALLERY, GALLERY_ROOT}}) {
                        openDrawerTo(device, destination[0]);
                        Objects.requireNonNull(device.wait(
                                Until.findObject(By.res(destination[1])), WAIT_MS),
                                "profile destination did not render: " + destination[1]);
                        backToMain(device);
                        waitMain(device);
                    }
                    return Unit.INSTANCE;
                });
    }

    /** Navigates the OPEN_DOCUMENT_TREE picker into the pushed folder and selects it. */
    private static void grantPushedFolderInDocumentsUi(UiDevice device, String workspaceName)
            throws IOException {
        // DocumentsUI restores its last directory, including arbitrarily nested folders
        // from an earlier empty-workspace journey. Walk its real parent stack until the
        // target or Documents entry is visible; never press Back from the picker root.
        while (device.wait(Until.findObject(By.text(workspaceName)), NAV_SETTLE_MS) == null) {
            Objects.requireNonNull(
                    device.findObject(By.pkg("com.android.documentsui")),
                    "workspace navigation left DocumentsUI before selecting " + workspaceName);
            UiObject2 documents = device.findObject(By.text("Documents"));
            if (documents != null) {
                clickWhenVisible(device, By.text("Documents"), "DocumentsUI Documents entry");
                break;
            }
            UiObject2 roots = device.findObject(By.desc("Show roots"));
            if (roots != null) {
                roots.click();
                clickWhenVisible(device, By.text("Documents"), "DocumentsUI Documents entry");
                break;
            }
            List<UiObject2> breadcrumbs = device.findObjects(
                    By.res("com.android.documentsui", "breadcrumb_text"));
            if (breadcrumbs.size() < 2) {
                throw new IllegalStateException(
                        "DocumentsUI has no parent or root navigation for " + workspaceName);
            }
            String directory = breadcrumbs.get(breadcrumbs.size() - 1).getText();
            device.pressBack();
            BenchmarkWaits.requireSatisfied(
                    device.wait(Until.gone(By.res("com.android.documentsui", "breadcrumb_text")
                            .text(directory)), WAIT_MS),
                    "DocumentsUI did not leave parent-navigation directory " + directory);
        }
        clickWhenVisible(
                device,
                By.text(workspaceName),
                "pushed corpus folder " + workspaceName + " in DocumentsUI");
        clickWhenVisible(
                device, By.text("USE THIS FOLDER"), "DocumentsUI USE THIS FOLDER action");
    }

    /**
     * Clicks the first `selector` match, re-resolving the node on every attempt:
     * DocumentsUI list rows re-bind while metadata loads, so a `UiObject2` resolved by
     * `findObject`/`wait` can go stale between resolution and `click()`
     * (`StaleObjectException` observed on emulator-5554). Each retry resolves a fresh
     * node; a row that never stays clickable fails closed at the scenario wait window.
     */
    private static void clickWhenVisible(UiDevice device, BySelector selector, String label) {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        while (true) {
            UiObject2 target = device.findObject(selector);
            if (target != null) {
                try {
                    target.click();
                    return;
                } catch (StaleObjectException recycled) {
                    // The row re-bound between resolve and click — fall through and
                    // re-resolve rather than treating a transient node recycle as a
                    // missing affordance.
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw new IllegalStateException(label + " never stayed clickable");
            }
            SystemClock.sleep(SETTLE_POLL_MS);
        }
    }

    /**
     * Back-navigates until the main list root resolves. Nav depth is not a fixed constant —
     * Sync Center sits two pushed levels under Settings, so a hardcoded pressBack count
     * either undershoots (still inside settings) or overshoots (exits the activity). The
     * bound keeps a truly stuck back stack from looping forever.
     */
    private static void backToMain(UiDevice device) {
        for (int backs = 0; backs < 6; backs++) {
            if (device.wait(Until.findObject(By.res(MAIN_ROOT)), NAV_SETTLE_MS) != null) {
                return;
            }
            device.pressBack();
        }
    }

    /**
     * Reaches the main list before a measured block interacts with main-screen anchors. A
     * WARM `startActivityAndWait` resumes the task's saved nav stack, so a route left pushed
     * by a previous scenario (or by a failed iteration's partial back-out) is still on top —
     * the main anchors never resolve while it is. Back-navigating to `MAIN_ROOT` first keeps
     * every scenario honest about starting from the production entry surface.
     */
    private static void ensureMain(UiDevice device) {
        // An in-flight pop keeps MAIN_ROOT absent for a few hundred ms — back-pressing
        // then would land on the main list itself and exit the activity. Give a normal
        // transition its window first; only a still-missing root gets stack repair.
        if (device.wait(Until.findObject(By.res(MAIN_ROOT)), NAV_SETTLE_MS) != null) {
            return;
        }
        backToMain(device);
        Objects.requireNonNull(
                device.wait(Until.findObject(By.res(MAIN_ROOT)), WAIT_MS),
                "main root anchor missing after back navigation");
    }

    private static UiObject2 waitMain(UiDevice device) {
        ensureMain(device);
        UiObject2 mainRoot = device.wait(Until.findObject(By.res(MAIN_ROOT)), INDEX_WAIT_MS);
        return Objects.requireNonNull(mainRoot, "main root anchor missing");
    }

    private static void scrollMemoList(UiDevice device) {
        UiObject2 mainRoot = waitMain(device);
        UiObject2 firstCard = Objects.requireNonNull(device.wait(
                Until.findObject(By.res(MEMO_CARD_ID)), INDEX_WAIT_MS), "no indexed memo card");
        String firstCardId = firstCard.getResourceName();
        UiObject2 scrollable = Objects.requireNonNull(
                mainRoot.findObject(By.scrollable(true)), "memo list is not scrollable");
        android.graphics.Rect bounds = scrollable.getVisibleBounds();
        // Keep the swipe in the list's left lane, clear of the centered gesture FAB.
        scrollable.setGestureMargins(
                bounds.width() / 10, bounds.height() / 10,
                bounds.width() / 2, bounds.height() / 10);
        scrollable.scroll(Direction.DOWN, 1.0f);
        BenchmarkWaits.requireSatisfied(device.wait(
                Until.gone(By.res(firstCardId)), WAIT_MS), "scroll did not move the first memo offscreen");
        scrollable.scroll(Direction.UP, 1.0f);
    }

    private static void openDrawerTo(UiDevice device, String drawerEntryAnchor) {
        ensureMain(device);
        UiObject2 drawerButton = device.wait(
                Until.findObject(By.res(MAIN_DRAWER_BUTTON)), WAIT_MS);
        Objects.requireNonNull(drawerButton, "drawer button anchor missing");
        drawerButton.click();
        UiObject2 entry = device.wait(
                Until.findObject(By.res(drawerEntryAnchor)), WAIT_MS);
        Objects.requireNonNull(entry, "drawer entry anchor missing: " + drawerEntryAnchor);
        // Entries enter the semantics tree while the drawer is still sliding in; a click on
        // a mid-animation position can hit the scrim and dismiss the drawer instead.
        SystemClock.sleep(INTERACTION_SETTLE_MS);
        entry.click();
    }

    /**
     * One accessibility-snapshot match: the node's resource id and bounds, resolved from a
     * fresh `UiAutomation` snapshot rather than `UiObject2` — memo lists recompose
     * continuously around inserts/deletes, so every cached-node operation (click,
     * getVisibleBounds) throws StaleObjectException.
     */
    private static final class AnchorMatch {
        final String id;
        final android.graphics.Rect bounds;

        AnchorMatch(String id, android.graphics.Rect bounds) {
            this.id = id;
            this.bounds = bounds;
        }
    }

    /**
     * First `selector` match with non-empty bounds, searching every a11y window in z-order
     * like `findObjects` — `getRootInActiveWindow` alone returns only the focused window, so
     * a lingering sheet/IME window would hide the whole app tree. An earlier ghost match
     * (an exiting card whose node still reports the anchor id with empty bounds) must not
     * shadow the visible cards behind it, so empty-bounds matches are skipped here rather
     * than treated as "no match".
     */
    private static AnchorMatch snapshotMatch(UiDevice device, Pattern selector) {
        java.util.List<AnchorMatch> matches = new java.util.ArrayList<>();
        for (android.view.accessibility.AccessibilityWindowInfo window :
                InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                collectResMatches(root, selector, matches);
            }
        }
        for (AnchorMatch match : matches) {
            if (!match.bounds.isEmpty()) {
                return match;
            }
        }
        return null;
    }

    private static void collectResMatches(
            android.view.accessibility.AccessibilityNodeInfo node,
            Pattern selector,
            java.util.List<AnchorMatch> out) {
        String id = node.getViewIdResourceName();
        if (id != null && selector.matcher(id).matches()) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            out.add(new AnchorMatch(id, bounds));
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                collectResMatches(child, selector, out);
            }
        }
    }

    /** Debug description of the raw match set for fail-closed diagnostics. */
    private static String describeMatches(UiDevice device, Pattern selector) {
        java.util.List<AnchorMatch> matches = new java.util.ArrayList<>();
        StringBuilder windows = new StringBuilder();
        int windowCount = 0;
        for (android.view.accessibility.AccessibilityWindowInfo window :
                InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()) {
            windowCount++;
            android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
            windows.append(" {w")
                    .append(windowCount)
                    .append(" type=")
                    .append(window.getType())
                    .append(" root=")
                    .append(root == null ? "null" : String.valueOf(root.getPackageName()));
            if (root != null) {
                java.util.List<AnchorMatch> windowMatches = new java.util.ArrayList<>();
                collectResMatches(
                        root, Pattern.compile("^benchmark_.+$"), windowMatches);
                windows.append(" anchors=").append(windowMatches.size());
                collectResMatches(root, selector, matches);
            }
            windows.append('}');
        }
        StringBuilder description = new StringBuilder();
        description.append("windows=").append(windowCount).append(windows)
                .append(" matches=").append(matches.size());
        for (int index = 0; index < matches.size() && index < 4; index++) {
            AnchorMatch match = matches.get(index);
            description.append(" [")
                    .append(match.id)
                    .append(' ')
                    .append(match.bounds.toShortString())
                    .append(match.bounds.isEmpty() ? " empty" : "")
                    .append(']');
        }
        return description.toString();
    }

    /**
     * A `selector` match only after the same node reports identical bounds in two snapshots
     * SETTLE_POLL_MS apart — memo rows animate in, out and across positions, so a
     * bounds-moving target is not yet hittable and a tap at snapshot coordinates would land
     * on whatever occupies that point when the gesture arrives (a sibling card, the list
     * scrim). Returns null while no settled match exists.
     */
    private static AnchorMatch snapshotSettled(UiDevice device, Pattern selector) {
        AnchorMatch first = snapshotMatch(device, selector);
        if (first == null) {
            return null;
        }
        SystemClock.sleep(SETTLE_POLL_MS);
        AnchorMatch second = snapshotMatch(device, selector);
        if (second != null && second.id.equals(first.id) && second.bounds.equals(first.bounds)) {
            return second;
        }
        return null;
    }

    /**
     * Taps the first `selector` match once its bounds settle — a raw `UiDevice.click`
     * gesture at stable snapshot coordinates cannot go stale. Fails closed when no match
     * settles before the scenario wait window.
     */
    private static void tapFresh(UiDevice device, Pattern selector, String label) {
        if (!tryTapFresh(device, selector, WAIT_MS)) {
            throw new IllegalStateException(
                    label
                            + " anchor never settled in the accessibility snapshot; "
                            + describeMatches(device, selector));
        }
    }

    /**
     * Bounded, non-fatal variant of {@link #tapFresh}: returns false when no settled match
     * appears within `boundMs` so a caller's retry cycle can re-open a sheet that dismissed
     * itself between the root probe and the row tap.
     */
    private static boolean tryTapFresh(UiDevice device, Pattern selector, long boundMs) {
        long deadline = SystemClock.uptimeMillis() + boundMs;
        while (SystemClock.uptimeMillis() < deadline) {
            AnchorMatch settled = snapshotSettled(device, selector);
            if (settled != null) {
                device.click(settled.bounds.centerX(), settled.bounds.centerY());
                return true;
            }
            SystemClock.sleep(SETTLE_POLL_MS);
        }
        return false;
    }

    /**
     * Opens the top visible card's memo menu and returns only once the sheet root is
     * observable — the real invariant callers need. A single tap cannot prove it landed:
     * enter/exit animations keep stale bounds alive for a while (a restored or deleted row
     * stays composed until its exit settles, and a fresh row enters below the fold), so the
     * loop re-resolves the settled button and re-taps until the sheet actually appears or
     * the scenario wait window forces a closed failure.
     */
    private static void openTopMemoMenu(UiDevice device) {
        openTopMemoMenu(device, true);
    }

    /**
     * `repairToMain` is only for callers driving the MAIN list's memo menu — the trash
     * screen reuses the same `benchmark_memo_menu_*` button ids and `MEMO_MENU_ROOT` sheet
     * tag on purpose, and a back-to-main repair there would leave the surface the caller
     * actually needs.
     */
    private static void openTopMemoMenu(UiDevice device, boolean repairToMain) {
        // Menu buttons only exist on materialized memo cards; a WARM relaunch can resume
        // onto a route a previous scenario left pushed, so reach the main surface first,
        // then give the engine session + first page their real bound on a cold SAF
        // workspace.
        if (repairToMain) {
            ensureMain(device);
        }
        long deadline = SystemClock.uptimeMillis() + SEED_DEADLINE_MS;
        while (true) {
            UiObject2 menuRoot = device.wait(
                    Until.findObject(By.res(MEMO_MENU_ROOT)), MENU_OPEN_VERIFY_MS);
            if (menuRoot != null) {
                return;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "memo menu root anchor missing; "
                                + describeMatches(device, MEMO_MENU_BUTTON_ID));
            }
            AnchorMatch settled = snapshotSettled(device, MEMO_MENU_BUTTON_ID);
            if (settled != null) {
                device.click(settled.bounds.centerX(), settled.bounds.centerY());
            }
        }
    }

    /**
     * Taps a memo-menu action row once its bounds settle — bounded and non-fatal: the sheet
     * can dismiss itself between the root probe and the row tap (a stale anchor tap landed
     * on the scrim, or the active menu state was consumed by an intervening recompose), in
     * which case the row never exists and the caller's open→tap→verify cycle must retry
     * rather than die on a phantom anchor.
     */
    private static boolean clickMenuAction(UiDevice device, String actionAnchor) {
        return tryTapFresh(
                device,
                Pattern.compile("^" + Pattern.quote(actionAnchor) + "$"),
                ACTION_TAP_BOUND_MS);
    }

    /**
     * Memo-menu action with an outcome check: a tap can dead-end silently — the staggered
     * `AnimatedVisibility` rows can shift under the settled-bounds tap, `consumeState`
     * drops the handler when the menu's active state was cleared by an intervening
     * recompose, and the sheet itself can close before the row ever settles. All of these
     * look identical from outside (no outcome), so the open→tap→verify cycle retries until
     * `outcomePresent` appears or the bound expires.
     */
    private static void menuActionExpecting(
            UiDevice device, String actionAnchor, BySelector outcomePresent, String label) {
        long deadline = SystemClock.uptimeMillis() + SEED_DEADLINE_MS;
        while (true) {
            openTopMemoMenu(device, true);
            // The outcome wait is the full anchor bound, not a short poll — share-card
            // render or the LAN page legitimately take seconds on a cold start, and a
            // mid-flight retry would double-dispatch the action.
            if (clickMenuAction(device, actionAnchor)
                    && device.wait(Until.findObject(outcomePresent), WAIT_MS) != null) {
                return;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw new IllegalStateException(
                        label + " never appeared after " + actionAnchor
                                + "; " + describeMatches(device, MEMO_MENU_BUTTON_ID));
            }
            // A residual open sheet would mask the retry's fresh open — dismiss it.
            if (device.findObject(By.res(MEMO_MENU_ROOT)) != null) {
                device.pressBack();
            }
        }
    }

    /** Same retry contract for grouped radial sub-actions opened from a parent menu tile. */
    private static void menuSubActionExpecting(
            UiDevice device,
            String parentActionAnchor,
            String subActionAnchor,
            BySelector outcomePresent,
            String label) {
        long deadline = SystemClock.uptimeMillis() + SEED_DEADLINE_MS;
        while (true) {
            openTopMemoMenu(device, true);
            if (clickMenuAction(device, parentActionAnchor)
                    && clickMenuAction(device, subActionAnchor)
                    && device.wait(Until.findObject(outcomePresent), WAIT_MS) != null) {
                return;
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw new IllegalStateException(
                        label + " never appeared after " + parentActionAnchor + " -> " + subActionAnchor
                                + "; " + describeMatches(device, MEMO_MENU_BUTTON_ID));
            }
            // Dismiss radial popup first if still open, then dismiss residual sheet.
            if (device.findObject(By.res(subActionAnchor)) != null) {
                device.pressBack();
            }
            if (device.findObject(By.res(MEMO_MENU_ROOT)) != null) {
                device.pressBack();
            }
        }
    }

    /** Same retry contract for actions whose honest outcome is the sheet dismissing. */
    private static void menuActionClosing(
            UiDevice device, String actionAnchor, String label) {
        menuActionClosing(device, actionAnchor, label, true);
    }

    private static void menuActionClosing(
            UiDevice device, String actionAnchor, String label, boolean repairToMain) {
        long deadline = SystemClock.uptimeMillis() + SEED_DEADLINE_MS;
        while (true) {
            openTopMemoMenu(device, repairToMain);
            if (clickMenuAction(device, actionAnchor)) {
                Object gone =
                        device.wait(Until.gone(By.res(MEMO_MENU_ROOT)), MENU_DISMISS_WAIT_MS);
                if (Boolean.TRUE.equals(gone)) {
                    return;
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                throw new IllegalStateException(
                        label + ": memo menu never dismissed after " + actionAnchor
                                + "; " + describeMatches(device, MEMO_MENU_BUTTON_ID));
            }
            if (device.findObject(By.res(MEMO_MENU_ROOT)) != null) {
                device.pressBack();
            }
            SystemClock.sleep(SETTLE_POLL_MS);
        }
    }

    private static void clickTrashCardAction(UiDevice device, String actionAnchor) {
        // The trash surface reuses the memo-menu button ids and sheet-root tag but its cards
        // are not on the main list — repairing to main would walk back out of Trash.
        menuActionClosing(device, actionAnchor, "trash action " + actionAnchor, false);
    }

    private static void deleteTopMemo(UiDevice device) {
        menuActionClosing(device, MEMO_ACTION_DELETE, "memo delete action");
        SystemClock.sleep(INTERACTION_SETTLE_MS);
    }

    /**
     * Shell-side mutation that leaves the workspace dirty for the next cold start: rewrite an
     * existing corpus doc (indexed change) and add a fresh document (unindexed add), both with
     * a per-iteration stamp so repeated iterations never degenerate into a clean start.
     *
     * Every `.md` filename in a workspace is parsed as a `yyyy_MM_dd` date key during
     * reconcile, so the fresh document's name must itself be a valid date — a decorative
     * name like `zz_bench_dirty_*.md` would fail the cold start it is meant to dirty. The
     * reserved `8xxx` year range keeps dirty docs distinct from corpus dates and lets the
     * mutation first remove the previous iteration's leftovers, so exactly one unindexed
     * add exists per iteration without accumulating files across runs.
     */
    private static void makeWorkspaceDirty() {
        String root = InstrumentationRegistry.getArguments()
                .getString("workspaceRoot", DEFAULT_WORKSPACE_ROOT);
        long stamp = System.currentTimeMillis();
        String dirtyDoc = String.format(
                java.util.Locale.US,
                "%04d_%02d_%02d.md",
                8_000 + (int) (stamp % 1_500),
                1 + (int) ((stamp / 1_500_000) % 12),
                1 + (int) ((stamp / 86_400_000) % 28));
        try {
            shellText(
                    "printf -- '- 23:59:58 dirty-append-" + stamp + "\\n' >> \""
                            + root + "/2020_01_01.md\"");
            shellText(
                    "rm -f \"" + root + "\"/8???_??_??.md && printf -- '- 23:59:59\\n"
                            + "bench-dirty-" + stamp + "\\n' > \"" + root + "/" + dirtyDoc
                            + "\"");
        } catch (IOException error) {
            // The MacrobenchmarkScope SAM cannot throw checked exceptions; the setup block
            // must still fail closed when the mutation never reached the workspace.
            throw new IllegalStateException("workspace dirty mutation failed", error);
        }
    }

    /** Execute a real script; a nonzero exit must fail even when the command is silent. */
    static String shellText(String command) throws IOException {
        Shell.CommandOutput output = Shell.INSTANCE.command(
                "set -e\n"
                        + "trap 'status=$?; if [ \"$status\" -ne 0 ]; then "
                        + "printf \"benchmark shell exit %s\\n\" \"$status\" >&2; fi' EXIT\n"
                        + command);
        String stdout = output.getStdOut();
        String stderr = output.getStdErr();
        if (!stderr.isBlank()) {
            throw new IOException("Benchmark setup command failed: " + command + "\n" + stderr);
        }
        return stdout;
    }

    /**
     * Scrolls the window's first scrollable down until `resId` resolves — the settings
     * category rows and the sync-center row sit below the fold of their scroll containers.
     * The scroller is re-resolved every pass so a recomposing list cannot go stale; an
     * anchor that never materializes fails closed at the wait window.
     */
    private static void scrollUntilAnchorVisible(UiDevice device, String resId) {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        while (device.findObject(By.res(resId)) == null) {
            UiObject2 scroller = device.findObject(By.scrollable(true));
            if (scroller != null) {
                scroller.setGestureMarginPercentage(0.1f);
                try {
                    scroller.scroll(Direction.DOWN, 0.5f);
                } catch (StaleObjectException stale) {
                    // find→scroll is not atomic: a window transition (drawer→settings
                    // sub-page) recomposes the list between the two calls and invalidates
                    // the node. Re-resolve on the next pass — the deadline still bounds it.
                }
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                break;
            }
            SystemClock.sleep(SETTLE_POLL_MS);
        }
        Objects.requireNonNull(
                device.findObject(By.res(resId)),
                "anchor " + resId + " never resolved after scrolling the page");
    }

    /**
     * Horizontal in-surface swipe: `forward` moves to the next page (content travels
     * left, right→left finger motion). Centered vertically with a 1/6 horizontal margin
     * so the gesture lands inside the surface, not on its edge gestures.
     */
    private static void swipeHorizontal(UiObject2 surface, boolean forward) {
        android.graphics.Rect bounds = surface.getVisibleBounds();
        int y = bounds.centerY();
        int margin = Math.max(1, bounds.width() / 6);
        int fromX = forward ? bounds.right - margin : bounds.left + margin;
        int toX = forward ? bounds.left + margin : bounds.right - margin;
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .swipe(fromX, y, toX, y, 25);
    }

    /**
     * Vertical in-surface swipe: `forward` moves to the next page (content travels up,
     * bottom→top finger motion) — the GalleryReel memo-to-memo direction.
     */
    private static void swipeVertical(UiObject2 surface, boolean forward) {
        android.graphics.Rect bounds = surface.getVisibleBounds();
        int x = bounds.centerX();
        int margin = Math.max(1, bounds.height() / 6);
        int fromY = forward ? bounds.bottom - margin : bounds.top + margin;
        int toY = forward ? bounds.top + margin : bounds.bottom - margin;
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .swipe(x, fromY, x, toY, 25);
    }

    /** Walks up the accessibility tree to the first clickable ancestor of an anchored icon. */
    private static UiObject2 clickableAncestor(UiObject2 object) {
        UiObject2 node = object;
        for (int depth = 0; depth < 6; depth++) {
            if (node.isClickable()) {
                return node;
            }
            node = node.getParent();
            Objects.requireNonNull(node, "no clickable ancestor for " + object.getResourceName());
        }
        throw new IllegalStateException(
                "no clickable ancestor within 6 levels for " + object.getResourceName());
    }

    /**
     * `compilationMode` instrumentation arg → `CompilationMode` for the startup variants.
     * Absent/`none` preserves the historical `CompilationMode.None`; `partialRequire` is
     * the fail-closed leg of the P7c release A/B — androidx `BaselineProfileMode.Require`
     * throws when no profile registered, so a measured benefit can never be fabricated by
     * an absent profile. Unknown tokens fail the test instead of guessing a mode.
     */
    private static CompilationMode compilationMode() {
        String mode = arg("compilationMode", "none");
        switch (mode) {
            case "none":
                return new CompilationMode.None();
            case "partialRequire":
                return new CompilationMode.Partial(BaselineProfileMode.Require);
            case "full":
                return new CompilationMode.Full();
            default:
                throw new IllegalArgumentException(
                        "unknown compilationMode arg: " + mode
                                + " (expected none|partialRequire|full)");
        }
    }

    private static String arg(String key, String fallback) {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String value = arguments.getString(key);
        return value == null ? fallback : value;
    }

    private static boolean inputMethodVisible() {
        return InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()
                .stream().anyMatch(window -> window.getType()
                        == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD);
    }

    /** Establishes the keyboard-closed main surface before a setup action needs the FAB. */
    private static void dismissInputMethod(UiDevice device) {
        if (inputMethodVisible()
                && !Boolean.TRUE.equals(device.wait(ignored -> !inputMethodVisible(), NAV_SETTLE_MS))) {
            device.pressBack();
            BenchmarkWaits.requireSatisfied(
                    device.wait(ignored -> !inputMethodVisible(), WAIT_MS),
                    "input method remained visible after back");
        }
    }

    /**
     * Submits one uniquely marked memo through the production input sheet. A matching
     * committed card acknowledges the write; a pending card cannot satisfy the selector.
     * Missing acknowledgement fails the journey without resubmitting the mutation.
     */
    private static void createMemo(UiDevice device, String body) {
        ensureMain(device);
        dismissInputMethod(device);
        String marker = "benchmark-commit-" + UUID.randomUUID();
        UiObject2 fab = Objects.requireNonNull(device.wait(
                Until.findObject(By.res(MAIN_CREATE_FAB)), WAIT_MS), "create FAB anchor missing");
        fab.click();
        Objects.requireNonNull(device.wait(
                Until.findObject(By.res(INPUT_SHEET_ROOT)), WAIT_MS), "input sheet anchor missing");
        UiObject2 editor = Objects.requireNonNull(device.wait(
                Until.findObject(By.res(INPUT_EDITOR)), WAIT_MS), "input editor anchor missing");
        editor.setText(marker + "\n" + body);
        UiObject2 submit = Objects.requireNonNull(device.wait(
                Until.findObject(By.res(INPUT_SUBMIT)), WAIT_MS), "input submit anchor missing");
        submit.click();
        BenchmarkWaits.requireSatisfied(device.wait(
                Until.gone(By.res(INPUT_SHEET_ROOT)), WAIT_MS), "input sheet stayed open after submit");
        // Pending cards have a distinct resource id. Only the Rust-confirmed committed
        // projection can acknowledge this unique submission; no snackbar or resubmit race.
        Objects.requireNonNull(device.wait(
                Until.findObject(By.res(MEMO_CARD_ID).hasDescendant(By.textContains(marker))),
                SEED_DEADLINE_MS), "submitted memo never reached a committed card: " + marker);
        dismissInputMethod(device);
    }

    // The expand/collapse chip is the clickable accessibility parent of its TextView label.
    private static void clickAffordance(UiObject2 card, String label) {
        UiObject2 text = card.findObject(By.text(label));
        Objects.requireNonNull(text, label + " affordance label missing inside card");
        UiObject2 chip = text.getParent();
        Objects.requireNonNull(chip, label + " affordance chip missing inside card");
        chip.click();
    }

    private static void typeText(UiDevice device, String text) {
        for (int index = 0; index < text.length(); index++) {
            device.pressKeyCode(keyCodeFor(text.charAt(index)));
            SystemClock.sleep(TYPE_INTERVAL_MS);
        }
    }

    private static int keyCodeFor(char c) {
        if (c >= 'a' && c <= 'z') {
            return KeyEvent.KEYCODE_A + (c - 'a');
        }
        if (c >= '0' && c <= '9') {
            return KeyEvent.KEYCODE_0 + (c - '0');
        }
        if (c == ' ') {
            return KeyEvent.KEYCODE_SPACE;
        }
        throw new IllegalArgumentException("no keycode for '" + c + "'");
    }

    private static Intent launchIntent() {
        Intent intent = InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getPackageManager()
                .getLaunchIntentForPackage(TARGET_PACKAGE);
        Objects.requireNonNull(intent, "no launcher intent for " + TARGET_PACKAGE);
        return intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }
}
