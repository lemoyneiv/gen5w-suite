package dev.lemoyne.comfort;

import static dev.lemoyne.comfort.ComfortTheme.*;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import hmgapi.client.HmgApi;

import java.io.File;
import java.util.Map;

/**
 * Material Design 3 widescreen automotive interface for Comfort & Startup (1920x720 Gen5W):
 * - Left M3 Navigation & Live Telemetry Rail (ambient temp, active zone chip, powertrain status, 3 destinations)
 * - Right contextual pane (Overview & Startup, Climate & Seats, Temperature Thresholds + Seasonal Spectrum)
 * - Automatic Day/Night M3 tonal palette via UiModeManager
 */
public final class MainActivity extends Activity implements ComfortEngine.Listener {
    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_CLIMATE = 1;
    private static final int TAB_THRESHOLDS = 2;

    private static final String[][] POLICY_OPTIONS = {
        { ComfortConfig.POLICY_OFF, "Off" },
        { ComfortConfig.POLICY_LOWER, "−1 Step" },
        { ComfortConfig.POLICY_MATCH, "Match" }
    };

    private static int activeTab = TAB_OVERVIEW;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ComfortEngine engine;
    private ComfortConfig current = ComfortConfig.defaults();
    private float latestAmbientF = Float.NaN;

    // Left rail & navigation views
    private TextView lblConnSub;
    private TextView lblAmbientTemp;
    private TextView lblAmbientSub;
    private TextView lblZoneChip;
    private TextView chipEv;
    private TextView chipGear;
    private TextView chipWheel;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private final ComfortIcons[] navIcons = new ComfortIcons[3];
    private final TextView[] navLabels = new TextView[3];

    // Right pane header & pages
    private TextView pageTitle;
    private TextView pageSubtitle;
    private final View[] headerActions = new View[3];
    private final View[] pages = new View[3];
    private TextView lblClimateSummaryChip;

    // Overview & Startup controls
    private ComfortWidgets.SeatTileView tileDriver;
    private ComfortWidgets.SeatTileView tilePassenger;
    private ComfortWidgets.SeatTileView tileRearLeft;
    private ComfortWidgets.SeatTileView tileRearRight;
    private TextView lblWelcomeStatus;
    private TextView lblWelcomeTime;
    private TextView lblClimateStatus;
    private TextView lblClimateTime;
    private ComfortWidgets.M3Switch swWelcome;
    private LinearLayout welcomeDelayRow;
    private TextView lblWelcomeDelay;

    // Climate & Seats controls
    private ComfortWidgets.M3Switch swClimate;
    private ComfortWidgets.M3Switch swWheel;
    private ComfortWidgets.M3Switch swVent;
    private ComfortWidgets.M3Switch swToast;
    private ComfortWidgets.M3SegmentedGroup segPassenger;
    private ComfortWidgets.M3SegmentedGroup segRearLeft;
    private ComfortWidgets.M3SegmentedGroup segRearRight;

    // Thresholds controls
    private ComfortWidgets.SpectrumBarView spectrumBar;
    private TextView lblHeatHigh;
    private TextView lblHeatMed;
    private TextView lblHeatLow;
    private TextView lblWheelF;
    private TextView lblVentLow;
    private TextView lblVentMed;
    private TextView lblVentHigh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ComfortTheme.apply(this);
        setTheme(ComfortTheme.night
                ? android.R.style.Theme_Holo_NoActionBar
                : android.R.style.Theme_Holo_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        startService(new Intent(this, ComfortService.class));
        engine = ComfortEngine.getInstance(this);
        engine.ensureStarted();
        setContentView(buildRoot());
        selectTab(activeTab);
        updateControls(current);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) {
            engine.addListener(this);
            engine.requestSettingsReload();
        }
    }

    @Override
    protected void onPause() {
        if (engine != null) {
            engine.removeListener(this);
        }
        super.onPause();
    }

    @Override
    public void onStateUpdated(final ComfortEngine.Snapshot snap) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) render(snap);
            }
        });
    }

    // ---- Root M3 Widescreen Layout (Left Rail + Right Contextual Pane) ----

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(SURFACE);
        // 38dp right padding keeps controls clear of the Gen5W split-screen drawer handle
        root.setPadding(d(14), d(8), d(38), d(8));

        root.addView(buildLeftRail(), new LinearLayout.LayoutParams(d(316), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(buildRightHeader());

        FrameLayout stage = new FrameLayout(this);
        pages[TAB_OVERVIEW] = wrapScroll(buildOverviewPage());
        pages[TAB_CLIMATE] = wrapScroll(buildClimatePage());
        pages[TAB_THRESHOLDS] = wrapScroll(buildThresholdsPage());
        for (int i = 0; i < pages.length; i++) {
            stage.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageLp.topMargin = d(8);
        right.addView(stage, stageLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = d(14);
        root.addView(right, rightLp);
        return root;
    }

    private ScrollView wrapScroll(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(false);
        sv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View buildLeftRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackground(shape(SURFACE_LOW, OUTLINE_VAR, Math.max(1, d(1)), d(24)));
        int p = d(14);
        rail.setPadding(p, d(12), p, d(12));

        // 1. App brand row + Close button
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout emblem = ComfortWidgets.iconBadge(
                this, ComfortIcons.SEAT_WAVE, PRIMARY_CONT, ON_PRIMARY_CONT, 38, 22);
        brandRow.addView(emblem);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(d(10), 0, d(8), 0);
        TextView appTitle = label("Comfort", 18.5f, ON_SURFACE, true);
        lblConnSub = label("Connecting to HMG-API…", 11.5f, ON_SURFACE_VAR, false);
        titleCol.addView(appTitle);
        titleCol.addView(lblConnSub);
        brandRow.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout closeBtn = ComfortWidgets.circleIconButton(
                this, ComfortIcons.CLOSE, SURFACE_HIGH, ON_SURFACE, OUTLINE_VAR, 36,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
        brandRow.addView(closeBtn);
        rail.addView(brandRow);

        // 2. Live ambient & powertrain telemetry card
        LinearLayout teleCard = new LinearLayout(this);
        teleCard.setOrientation(LinearLayout.VERTICAL);
        teleCard.setBackground(shape(SURFACE_CONT, OUTLINE_VAR, Math.max(1, d(1)), d(18)));
        teleCard.setPadding(d(13), d(11), d(13), d(11));
        LinearLayout.LayoutParams teleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        teleLp.topMargin = d(11);

        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);

        lblAmbientTemp = label("--°F", 26f, ON_SURFACE, true);
        tempRow.addView(lblAmbientTemp);

        lblAmbientSub = label("", 12.5f, ON_SURFACE_VAR, false);
        lblAmbientSub.setPadding(d(6), d(4), 0, 0);
        tempRow.addView(lblAmbientSub);

        View tempSpacer = new View(this);
        tempRow.addView(tempSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        lblZoneChip = statusChip("Standby", SURFACE_HIGH, ON_SURFACE_VAR);
        tempRow.addView(lblZoneChip);
        teleCard.addView(tempRow);

        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = d(9);

        chipEv = statusChip("EV --", SURFACE_HIGH, ON_SURFACE_VAR);
        chipGear = statusChip("Gear --", SURFACE_HIGH, ON_SURFACE_VAR);
        chipWheel = statusChip("Wheel --", SURFACE_HIGH, ON_SURFACE_VAR);
        chipsRow.addView(chipEv, chipWeight(0, 4));
        chipsRow.addView(chipGear, chipWeight(4, 4));
        chipsRow.addView(chipWheel, chipWeight(4, 0));
        teleCard.addView(chipsRow, crLp);
        rail.addView(teleCard, teleLp);

        // 3. M3 Navigation Rail destination pills
        LinearLayout navCol = new LinearLayout(this);
        navCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams navColLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navColLp.topMargin = d(12);

        navCol.addView(buildNavItem(TAB_OVERVIEW, ComfortIcons.SHIELD_CHECK, "Overview & Startup"));
        navCol.addView(buildNavItem(TAB_CLIMATE, ComfortIcons.SEAT_WAVE, "Climate & Seats"));
        navCol.addView(buildNavItem(TAB_THRESHOLDS, ComfortIcons.THERMOMETER, "Thresholds"));
        rail.addView(navCol, navColLp);

        rail.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 4. Primary quick action button pinned at bottom of rail
        LinearLayout runBtn = ComfortWidgets.m3Button(
                this, ComfortIcons.PLAY_BOLT, "Run Climate Check", ComfortWidgets.BTN_FILLED, 42,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (engine != null) {
                            engine.triggerClimateNow();
                            Toast.makeText(MainActivity.this, "Running cabin climate check…", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        rail.addView(runBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return rail;
    }

    private View buildNavItem(final int index, int iconKind, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(d(46));
        row.setPadding(d(16), 0, d(16), 0);
        row.setClickable(true);

        ComfortIcons icon = new ComfortIcons(iconKind, ON_SURFACE_VAR);
        View icView = new View(this);
        icView.setBackground(icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(d(20), d(20));
        ip.rightMargin = d(12);
        row.addView(icView, ip);

        TextView lbl = label(title, 14.5f, ON_SURFACE_VAR, false);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(index);
            }
        });

        navItems[index] = row;
        navIcons[index] = icon;
        navLabels[index] = lbl;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, d(46));
        if (index > 0) lp.topMargin = d(4);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildRightHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(d(42));
        bar.setPadding(d(4), 0, 0, 0);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        pageTitle = label("Overview & Startup", 20f, ON_SURFACE, true);
        pageSubtitle = label("Live cabin status and automatic startup safety notice", 12.5f, ON_SURFACE_VAR, false);
        titles.addView(pageTitle);
        titles.addView(pageSubtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Contextual actions for Tab 0 (Overview)
        LinearLayout actOverview = new LinearLayout(this);
        actOverview.setOrientation(LinearLayout.HORIZONTAL);
        actOverview.setGravity(Gravity.CENTER_VERTICAL);

        if (hasHomeScreenTileManager()) {
            LinearLayout tilesBtn = ComfortWidgets.m3Button(
                    this, ComfortIcons.GRID, "Home Screen Tiles", ComfortWidgets.BTN_OUTLINED, 36,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openHomeScreenTiles();
                        }
                    });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.rightMargin = d(8);
            actOverview.addView(tilesBtn, tlp);
        }

        LinearLayout dismissBtn = ComfortWidgets.m3Button(
                this, ComfortIcons.CHECK, "Dismiss Notice", ComfortWidgets.BTN_TONAL, 36,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (engine != null) {
                            engine.triggerWelcomeNow();
                            Toast.makeText(MainActivity.this, "Sent startup notice dismiss", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        actOverview.addView(dismissBtn);
        headerActions[TAB_OVERVIEW] = actOverview;
        bar.addView(actOverview);

        // Contextual status badge for Tab 1 (Climate)
        lblClimateSummaryChip = statusChip("Enabled on EV Ready", OK_CONT, ON_OK_CONT);
        lblClimateSummaryChip.setPadding(d(14), d(7), d(14), d(7));
        headerActions[TAB_CLIMATE] = lblClimateSummaryChip;
        bar.addView(lblClimateSummaryChip);

        // Contextual reset button for Tab 2 (Thresholds)
        LinearLayout resetBtn = ComfortWidgets.m3Button(
                this, ComfortIcons.RESET, "Reset Thresholds", ComfortWidgets.BTN_OUTLINED, 36,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ComfortConfig def = ComfortConfig.defaults();
                        save(new ComfortConfig(
                                current.welcomeDismiss, current.welcomeDelayMs,
                                current.climateEnabled, def.heatHighF, def.heatMedF, def.heatLowF,
                                current.wheelEnabled, def.wheelF,
                                current.passengerPolicy, current.rearLeftPolicy, current.rearRightPolicy,
                                current.ventEnabled, def.ventLowF, def.ventMedF, def.ventHighF,
                                current.toastOnApply));
                        Toast.makeText(MainActivity.this, "Reset thresholds to defaults", Toast.LENGTH_SHORT).show();
                    }
                });
        headerActions[TAB_THRESHOLDS] = resetBtn;
        bar.addView(resetBtn);

        return bar;
    }

    private void selectTab(int index) {
        if (index < 0 || index >= pages.length) index = TAB_OVERVIEW;
        activeTab = index;
        for (int i = 0; i < pages.length; i++) {
            boolean sel = (i == index);
            pages[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            headerActions[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            navItems[i].setBackground(interactiveShape(
                    sel ? SECONDARY_CONT : Color.TRANSPARENT, 0, 0, d(23), STATE_ON_SURFACE));
            navIcons[i].setColor(sel ? ON_SECONDARY_CONT : ON_SURFACE_VAR);
            navLabels[i].setTextColor(sel ? ON_SECONDARY_CONT : ON_SURFACE_VAR);
            navLabels[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (index == TAB_OVERVIEW) {
            pageTitle.setText("Overview & Startup");
            pageSubtitle.setText("Live cabin seat telemetry, recent actions, and startup safety screen dismissal");
        } else if (index == TAB_CLIMATE) {
            pageTitle.setText("Climate & Seats");
            pageSubtitle.setText("Automatic EV Ready heating, summer ventilation, and companion seat policies");
        } else {
            pageTitle.setText("Temperature Thresholds");
            pageSubtitle.setText("Outside temperature triggers (°F) for multi-stage heating and ventilation");
        }
    }

    // ---- Page 0: Overview & Startup ----

    private View buildOverviewPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Top 2-column row: Cabin Seats Card | Recent Activity Card
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout cabinCard = m3Card();
        cabinCard.addView(cardHeader(ComfortIcons.SEAT_WAVE, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Cabin Climate State", "Live seat heat & ventilation levels"));

        tileDriver = new ComfortWidgets.SeatTileView(this, "Driver");
        tilePassenger = new ComfortWidgets.SeatTileView(this, "Passenger");
        tileRearLeft = new ComfortWidgets.SeatTileView(this, "Rear Left");
        tileRearRight = new ComfortWidgets.SeatTileView(this, "Rear Right");

        LinearLayout frontSeats = new LinearLayout(this);
        frontSeats.setOrientation(LinearLayout.HORIZONTAL);
        frontSeats.addView(tileDriver, colWeight(0, 5));
        frontSeats.addView(tilePassenger, colWeight(5, 0));
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fsLp.topMargin = d(10);
        cabinCard.addView(frontSeats, fsLp);

        LinearLayout rearSeats = new LinearLayout(this);
        rearSeats.setOrientation(LinearLayout.HORIZONTAL);
        rearSeats.addView(tileRearLeft, colWeight(0, 5));
        rearSeats.addView(tileRearRight, colWeight(5, 0));
        LinearLayout.LayoutParams rsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rsLp.topMargin = d(8);
        cabinCard.addView(rearSeats, rsLp);

        LinearLayout activityCard = m3Card();
        activityCard.addView(cardHeader(ComfortIcons.AUTO_CLIMATE, SECONDARY_CONT, ON_SECONDARY_CONT,
                "System Activity", "Latest startup & climate coordinator events"));

        lblWelcomeStatus = label("Armed for startup", 13.5f, ON_SURFACE, false);
        lblWelcomeStatus.setMaxLines(2);
        lblWelcomeStatus.setEllipsize(TextUtils.TruncateAt.END);
        lblWelcomeTime = statusChip("--:--", SURFACE_HIGH, ON_SURFACE_VAR);
        activityCard.addView(activityItem("Startup Notice", lblWelcomeStatus, lblWelcomeTime, 10));

        activityCard.addView(hairline(8));

        lblClimateStatus = label("Waiting for EV Ready", 13.5f, ON_SURFACE, false);
        lblClimateStatus.setMaxLines(2);
        lblClimateStatus.setEllipsize(TextUtils.TruncateAt.END);
        lblClimateTime = statusChip("--:--", SURFACE_HIGH, ON_SURFACE_VAR);
        activityCard.addView(activityItem("Auto Climate", lblClimateStatus, lblClimateTime, 0));

        topRow.addView(cabinCard, colWeight(0, 6));
        topRow.addView(activityCard, colWeight(6, 0));
        col.addView(topRow);

        // Bottom card: Startup Safety Notice Settings
        LinearLayout startupCard = m3Card();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = d(12);

        swWelcome = new ComfortWidgets.M3Switch(this, current.welcomeDismiss, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(new ComfortConfig(checked, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        startupCard.addView(switchRow(
                ComfortIcons.SHIELD_CHECK, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Auto-dismiss startup safety notice",
                "Confirms the Park safety screen on cold boot and QuickBoot wake (requires HMG-API startup notice permission)",
                swWelcome));

        startupCard.addView(hairline(10));

        welcomeDelayRow = new LinearLayout(this);
        welcomeDelayRow.setOrientation(LinearLayout.HORIZONTAL);
        welcomeDelayRow.setGravity(Gravity.CENTER_VERTICAL);
        welcomeDelayRow.addView(ComfortWidgets.iconBadge(
                this, ComfortIcons.TIMER, SURFACE_HIGH, ON_SURFACE_VAR, 36, 19));

        LinearLayout delayText = new LinearLayout(this);
        delayText.setOrientation(LinearLayout.VERTICAL);
        delayText.setPadding(d(12), 0, d(12), 0);
        delayText.addView(label("Wake dismiss delay", 15f, ON_SURFACE, true));
        delayText.addView(label("Wait after screen wake before sending dismiss (0 ms = instant fast-path)", 12.5f, ON_SURFACE_VAR, false));
        welcomeDelayRow.addView(delayText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lblWelcomeDelay = new TextView(this);
        welcomeDelayRow.addView(inlineStepper(lblWelcomeDelay, 92, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustDelay(-200);
            }
        }, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustDelay(200);
            }
        }));
        startupCard.addView(welcomeDelayRow);

        col.addView(startupCard, scLp);
        return col;
    }

    private View activityItem(String category, TextView bodyView, TextView timeChip, int topDp) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (topDp > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = d(topDp);
            wrap.setLayoutParams(lp);
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label(category, 12.5f, ON_SURFACE_VAR, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(timeChip);
        wrap.addView(head);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = d(3);
        wrap.addView(bodyView, bp);
        return wrap;
    }

    // ---- Page 1: Climate & Seats ----

    private View buildClimatePage() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        // Left Card: Automatic Climate Feature Switches
        LinearLayout featCard = m3Card();
        featCard.addView(cardHeader(ComfortIcons.AUTO_CLIMATE, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Automatic Climate", "Triggered once per drive on EV Ready"));

        swClimate = new ComfortWidgets.M3Switch(this, current.climateEnabled, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        checked, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        LinearLayout r1 = switchRow(ComfortIcons.PLAY_BOLT, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Auto seat & wheel climate", "Evaluate ambient temperature on EV Ready", swClimate);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = d(10);
        featCard.addView(r1, r1Lp);
        featCard.addView(hairline(8));

        swWheel = new ComfortWidgets.M3Switch(this, current.wheelEnabled, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        checked, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        featCard.addView(switchRow(ComfortIcons.WHEEL, SECONDARY_CONT, ON_SECONDARY_CONT,
                "Steering wheel heat", "Warm wheel at or below wheel threshold", swWheel));
        featCard.addView(hairline(8));

        swVent = new ComfortWidgets.M3Switch(this, current.ventEnabled, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, checked,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        featCard.addView(switchRow(ComfortIcons.BREEZE, TERTIARY_CONT, ON_TERTIARY_CONT,
                "Summer seat ventilation", "Cool front seats above summer thresholds", swVent));
        featCard.addView(hairline(8));

        swToast = new ComfortWidgets.M3Switch(this, current.toastOnApply, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, checked));
            }
        });
        featCard.addView(switchRow(ComfortIcons.BELL, SURFACE_HIGH, ON_SURFACE_VAR,
                "Confirmation banner", "Show head-unit toast when levels apply", swToast));

        // Right Card: Companion & Rear Seat Policies (M3 Segmented Buttons)
        LinearLayout polCard = m3Card();
        polCard.addView(cardHeader(ComfortIcons.SEAT_WAVE, SECONDARY_CONT, ON_SECONDARY_CONT,
                "Companion & Rear Seats", "How other seats track the driver's target level"));

        segPassenger = new ComfortWidgets.M3SegmentedGroup(
                this, POLICY_OPTIONS, current.passengerPolicy, 36, new ComfortWidgets.PickListener() {
                    @Override
                    public void onPick(String val) {
                        save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                                current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                                current.wheelEnabled, current.wheelF, val,
                                current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                                current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
                    }
                });
        polCard.addView(policyBlock("Front Passenger", "Heating & summer ventilation", segPassenger, 10));

        segRearLeft = new ComfortWidgets.M3SegmentedGroup(
                this, POLICY_OPTIONS, current.rearLeftPolicy, 36, new ComfortWidgets.PickListener() {
                    @Override
                    public void onPick(String val) {
                        save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                                current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                                current.wheelEnabled, current.wheelF, current.passengerPolicy,
                                val, current.rearRightPolicy, current.ventEnabled,
                                current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
                    }
                });
        polCard.addView(policyBlock("Rear Left Seat", "Winter heating (2-step seats map Med → Low)", segRearLeft, 10));

        segRearRight = new ComfortWidgets.M3SegmentedGroup(
                this, POLICY_OPTIONS, current.rearRightPolicy, 36, new ComfortWidgets.PickListener() {
                    @Override
                    public void onPick(String val) {
                        save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                                current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                                current.wheelEnabled, current.wheelF, current.passengerPolicy,
                                current.rearLeftPolicy, val, current.ventEnabled,
                                current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
                    }
                });
        polCard.addView(policyBlock("Rear Right Seat", "Winter heating (2-step seats map Med → Low)", segRearRight, 10));

        row.addView(featCard, colWeight(0, 6));
        row.addView(polCard, colWeight(6, 0));
        return row;
    }

    private View policyBlock(String title, String sub, ComfortWidgets.M3SegmentedGroup seg, int topDp) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = d(topDp);
        block.setLayoutParams(lp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(title, 14.5f, ON_SURFACE, true));
        TextView subView = label("  ·  " + sub, 12f, ON_SURFACE_VAR, false);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(subView);
        block.addView(header);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = d(6);
        block.addView(seg, sp);
        return block;
    }

    // ---- Page 2: Temperature Thresholds ----

    private View buildThresholdsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Seasonal Spectrum Bar Card
        LinearLayout specCard = m3Card();
        specCard.setPadding(d(14), d(10), d(14), d(8));
        spectrumBar = new ComfortWidgets.SpectrumBarView(this);
        specCard.addView(spectrumBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(specCard);

        // Two-column thresholds split: Winter Heating | Summer Ventilation
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splitLp.topMargin = d(10);

        LinearLayout winterCard = m3Card();
        winterCard.addView(cardHeader(ComfortIcons.HEAT_WAVES, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Winter Heating", "Triggers when outside temp is at or below threshold"));

        lblHeatHigh = new TextView(this);
        lblHeatMed = new TextView(this);
        lblHeatLow = new TextView(this);
        lblWheelF = new TextView(this);

        winterCard.addView(thresholdRow("Driver High (Level 3)", "≤ Coldest tier", lblHeatHigh, 0, 10));
        winterCard.addView(hairline(6));
        winterCard.addView(thresholdRow("Driver Medium (Level 2)", "≤ Mid cold tier", lblHeatMed, 1, 0));
        winterCard.addView(hairline(6));
        winterCard.addView(thresholdRow("Driver Low (Level 1)", "≤ Mild chill tier", lblHeatLow, 2, 0));
        winterCard.addView(hairline(6));
        winterCard.addView(thresholdRow("Steering Wheel Heat", "≤ Independent trigger", lblWheelF, 3, 0));

        LinearLayout summerCard = m3Card();
        summerCard.addView(cardHeader(ComfortIcons.BREEZE, TERTIARY_CONT, ON_TERTIARY_CONT,
                "Summer Ventilation", "Triggers when outside temp is at or above threshold"));

        lblVentLow = new TextView(this);
        lblVentMed = new TextView(this);
        lblVentHigh = new TextView(this);

        summerCard.addView(thresholdRow("Front Low (Level 1)", "≥ Warm tier", lblVentLow, 4, 10));
        summerCard.addView(hairline(6));
        summerCard.addView(thresholdRow("Front Medium (Level 2)", "≥ Hot tier", lblVentMed, 5, 0));
        summerCard.addView(hairline(6));
        summerCard.addView(thresholdRow("Front High (Level 3)", "≥ Peak heat tier", lblVentHigh, 6, 0));

        TextView note = label(
                "Active Bluelink remote climate is automatically preserved when entering EV Ready.",
                12f, ON_SURFACE_VAR, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = d(12);
        summerCard.addView(note, np);

        split.addView(winterCard, colWeight(0, 6));
        split.addView(summerCard, colWeight(6, 0));
        col.addView(split, splitLp);
        return col;
    }

    private View thresholdRow(String title, String sub, TextView valueOut, final int which, int topDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (topDp > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = d(topDp);
            row.setLayoutParams(lp);
        }

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(label(title, 14f, ON_SURFACE, true));
        textCol.addView(label(sub, 11.5f, ON_SURFACE_VAR, false));
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(inlineStepper(valueOut, 70, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustThreshold(which, -2);
            }
        }, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustThreshold(which, 2);
            }
        }));
        return row;
    }

    private LinearLayout inlineStepper(TextView valueOut, int minWidthDp,
                                       View.OnClickListener onMinus, View.OnClickListener onPlus) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(shape(SURFACE_HIGH, OUTLINE_VAR, Math.max(1, d(1)), d(20)));
        box.setPadding(d(3), d(3), d(3), d(3));

        FrameLayout bMin = ComfortWidgets.circleIconButton(
                this, ComfortIcons.MINUS, SECONDARY_CONT, ON_SECONDARY_CONT, 0, 32, onMinus);
        box.addView(bMin);

        valueOut.setTextColor(ON_SURFACE);
        valueOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        valueOut.setTypeface(Typeface.DEFAULT_BOLD);
        valueOut.setGravity(Gravity.CENTER);
        valueOut.setSingleLine(true);
        valueOut.setMinWidth(d(minWidthDp));
        valueOut.setPadding(d(6), 0, d(6), 0);
        box.addView(valueOut);

        FrameLayout bPlus = ComfortWidgets.circleIconButton(
                this, ComfortIcons.PLUS, SECONDARY_CONT, ON_SECONDARY_CONT, 0, 32, onPlus);
        box.addView(bPlus);
        return box;
    }

    // ---- Mutations & Live Rendering ----

    private void adjustDelay(int deltaMs) {
        int nextDelay = ComfortConfig.clamp(current.welcomeDelayMs + deltaMs, 0, 10000);
        save(new ComfortConfig(current.welcomeDismiss, nextDelay,
                current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                current.wheelEnabled, current.wheelF, current.passengerPolicy,
                current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
    }

    private void adjustThreshold(int which, int delta) {
        int hh = current.heatHighF, hm = current.heatMedF, hl = current.heatLowF, wf = current.wheelF;
        int vl = current.ventLowF, vm = current.ventMedF, vh = current.ventHighF;
        if (which == 0) hh += delta;
        else if (which == 1) hm += delta;
        else if (which == 2) hl += delta;
        else if (which == 3) wf += delta;
        else if (which == 4) vl += delta;
        else if (which == 5) vm += delta;
        else if (which == 6) vh += delta;
        save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                current.climateEnabled, hh, hm, hl, current.wheelEnabled, wf,
                current.passengerPolicy, current.rearLeftPolicy, current.rearRightPolicy,
                current.ventEnabled, vl, vm, vh, current.toastOnApply));
    }

    private void save(final ComfortConfig next) {
        current = next;
        updateControls(next);
        Thread t = new Thread("ComfortSave") {
            @Override
            public void run() {
                try {
                    HmgApi.connect().timeout(3000).saveSettings(ComfortEngine.toJson(next));
                    if (engine != null) engine.requestSettingsReload();
                } catch (Throwable ignored) {}
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private void render(ComfortEngine.Snapshot s) {
        current = s.config;
        Map<String, String> r = s.readings;

        // Connection status
        lblConnSub.setText(s.connected ? "Connected · HMG-API" : "Waiting for HMG-API…");
        lblConnSub.setTextColor(s.connected ? OK : ON_SURFACE_VAR);

        // Ambient temperature & active comfort zone chip
        latestAmbientF = ComfortPolicy.parseAmbientF(r == null ? null : r.get("outside_temp_f"));
        if (!Float.isNaN(latestAmbientF)) {
            lblAmbientTemp.setText(Math.round(latestAmbientF) + "°F");
            String cRaw = val(r, "outside_temp");
            lblAmbientSub.setText("-".equals(cRaw) ? "" : cRaw + "°C");
            if (current.climateEnabled && latestAmbientF <= current.heatLowF) {
                styleChip(lblZoneChip, "Winter Heat", PRIMARY_CONT, ON_PRIMARY_CONT);
            } else if (current.climateEnabled && current.ventEnabled && latestAmbientF >= current.ventLowF) {
                styleChip(lblZoneChip, "Summer Vent", TERTIARY_CONT, ON_TERTIARY_CONT);
            } else {
                styleChip(lblZoneChip, "Comfort Zone", SURFACE_HIGH, ON_SURFACE_VAR);
            }
        } else {
            lblAmbientTemp.setText("--°F");
            lblAmbientSub.setText("");
            styleChip(lblZoneChip, s.connected ? "No Temp" : "Offline", SURFACE_HIGH, ON_SURFACE_VAR);
        }

        // Powertrain & wheel chips
        boolean evReady = r != null && "1".equals(r.get("hv.ready"));
        boolean evOff = r != null && "0".equals(r.get("hv.ready"));
        if (evReady) styleChip(chipEv, "EV Ready", OK_CONT, ON_OK_CONT);
        else styleChip(chipEv, evOff ? "Standby" : "EV --", SURFACE_HIGH, ON_SURFACE_VAR);

        boolean inPark = r != null && "1".equals(r.get("gear.park"));
        boolean inDrive = r != null && "0".equals(r.get("gear.park"));
        styleChip(chipGear, inPark ? "Park" : (inDrive ? "Drive" : "Gear --"),
                inPark ? SECONDARY_CONT : SURFACE_HIGH,
                inPark ? ON_SECONDARY_CONT : ON_SURFACE_VAR);

        boolean wheelOn = r != null && "1".equals(r.get("wheel_heat"));
        boolean wheelOff = r != null && "0".equals(r.get("wheel_heat"));
        styleChip(chipWheel, wheelOn ? "Wheel On" : (wheelOff ? "Wheel Off" : "Wheel --"),
                wheelOn ? PRIMARY_CONT : SURFACE_HIGH,
                wheelOn ? ON_PRIMARY_CONT : ON_SURFACE_VAR);

        // Cabin 4-seat live tiles
        updateSeatTile(tileDriver, r, "driver", true);
        updateSeatTile(tilePassenger, r, "passenger", true);
        updateSeatTile(tileRearLeft, r, "rear_left", false);
        updateSeatTile(tileRearRight, r, "rear_right", false);

        // Activity timestamps & readable messages
        bindActivity(s.lastWelcomeStatus, lblWelcomeTime, lblWelcomeStatus);
        bindActivity(s.lastClimateStatus, lblClimateTime, lblClimateStatus);

        updateControls(current);
    }

    private void updateSeatTile(ComfortWidgets.SeatTileView tile, Map<String, String> r, String key, boolean hasVent) {
        int heat = parseIntSafe(r == null ? null : r.get("seat_heat." + key));
        int vent = hasVent ? parseIntSafe(r == null ? null : r.get("seat_vent." + key)) : 0;
        if (heat > 0) tile.update(1, Math.min(3, heat));
        else if (vent > 0) tile.update(2, Math.min(3, vent));
        else tile.update(0, 0);
    }

    private void bindActivity(String raw, TextView timeChip, TextView msgView) {
        if (raw == null || raw.isEmpty()) {
            timeChip.setText("Idle");
            msgView.setText("—");
            return;
        }
        if (raw.startsWith("[") && raw.length() > 10 && raw.charAt(9) == ']') {
            timeChip.setText(raw.substring(1, 9));
            msgView.setText(raw.substring(10).trim());
        } else {
            timeChip.setText("Ready");
            msgView.setText(raw);
        }
    }

    private void updateControls(ComfortConfig c) {
        swWelcome.setChecked(c.welcomeDismiss, false);
        welcomeDelayRow.setAlpha(c.welcomeDismiss ? 1f : 0.45f);
        lblWelcomeDelay.setText(c.welcomeDelayMs == 0 ? "Instant" : (c.welcomeDelayMs + " ms"));

        swClimate.setChecked(c.climateEnabled, false);
        swWheel.setChecked(c.wheelEnabled, false);
        swVent.setChecked(c.ventEnabled, false);
        swToast.setChecked(c.toastOnApply, false);

        if (c.climateEnabled) {
            styleChip(lblClimateSummaryChip, "Active on EV Ready", OK_CONT, ON_OK_CONT);
        } else {
            styleChip(lblClimateSummaryChip, "Auto Climate Off", SURFACE_HIGH, ON_SURFACE_VAR);
        }

        segPassenger.setValue(c.passengerPolicy);
        segRearLeft.setValue(c.rearLeftPolicy);
        segRearRight.setValue(c.rearRightPolicy);

        lblHeatHigh.setText(c.heatHighF + "°F");
        lblHeatMed.setText(c.heatMedF + "°F");
        lblHeatLow.setText(c.heatLowF + "°F");
        lblWheelF.setText(c.wheelF + "°F");
        lblVentLow.setText(c.ventLowF + "°F");
        lblVentMed.setText(c.ventMedF + "°F");
        lblVentHigh.setText(c.ventHighF + "°F");

        spectrumBar.update(c, latestAmbientF);
    }

    // ---- Helpers ----

    private LinearLayout m3Card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(shape(SURFACE_CONT, OUTLINE_VAR, Math.max(1, d(1)), d(20)));
        int p = d(14);
        l.setPadding(p, d(12), p, d(12));
        return l;
    }

    private View cardHeader(int iconKind, int badgeBg, int badgeFg, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ComfortWidgets.iconBadge(this, iconKind, badgeBg, badgeFg, 36, 19));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(10), 0, 0, 0);
        col.addView(label(title, 15.5f, ON_SURFACE, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            col.addView(label(subtitle, 12f, ON_SURFACE_VAR, false));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout switchRow(int iconKind, int badgeBg, int badgeFg,
                                   String title, String subtitle, ComfortWidgets.M3Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ComfortWidgets.iconBadge(this, iconKind, badgeBg, badgeFg, 36, 19));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(d(12), 0, d(10), 0);
        textCol.addView(label(title, 14.5f, ON_SURFACE, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            textCol.addView(label(subtitle, 12f, ON_SURFACE_VAR, false));
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.performClick();
            }
        });
        return row;
    }

    private View hairline(int marginDp) {
        View v = new View(this);
        v.setBackgroundColor(OUTLINE_VAR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, d(1)));
        lp.topMargin = d(marginDp);
        lp.bottomMargin = d(marginDp);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView statusChip(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(d(9), d(4), d(9), d(4));
        styleChip(t, text, bg, fg);
        return t;
    }

    private void styleChip(TextView chip, String text, int bg, int fg) {
        chip.setText(text);
        chip.setTextColor(fg);
        chip.setBackground(shape(bg, 0, 0, d(14)));
    }

    private LinearLayout.LayoutParams colWeight(int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = d(leftDp);
        lp.rightMargin = d(rightDp);
        return lp;
    }

    private LinearLayout.LayoutParams chipWeight(int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = d(leftDp);
        lp.rightMargin = d(rightDp);
        return lp;
    }

    private void openHomeScreenTiles() {
        try {
            Intent it = new Intent();
            it.setClassName("hmgapi.app", "hmgapi.app.MainActivity");
            it.putExtra("tab", 1);
            it.putExtra("settings_cat", "Home screen");
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this, "Open HMG-API > Settings > Home screen", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasHomeScreenTileManager() {
        try {
            if (new File("/data/hmgapi/public/launcher-tiles.json").isFile()) return true;
            ApplicationInfo ai = getPackageManager().getApplicationInfo(
                    "hmgapi.app", PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null && ai.metaData.getBoolean("hmgapi.feature.launcher_tiles", false)) {
                return true;
            }
            Class.forName("hmgapi.engine.LauncherTilesStore");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String val(Map<String, String> r, String k) {
        String v = r == null ? null : r.get(k);
        return v == null || v.isEmpty() ? "-" : v;
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int d(float dpVal) {
        return ComfortTheme.dp(this, dpVal);
    }
}
