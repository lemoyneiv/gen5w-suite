package dev.lemoyne.comfort;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import hmgapi.client.HmgApi;

import java.util.Map;

/**
 * Landscape Gen5W control panel for Comfort & Startup:
 * live vehicle readings, instant test triggers, and threshold/policy controls saved via HmgApi.
 */
public final class MainActivity extends Activity implements ComfortEngine.Listener {
    private static final int BG = 0xFF0D1117;
    private static final int CARD = 0xFF161B22;
    private static final int BORDER = 0xFF30363D;
    private static final int ACCENT = 0xFF2EA043;
    private static final int ACCENT_BLUE = 0xFF1F6FEB;
    private static final int MUTED_BTN = 0xFF21262D;
    private static final int TEXT_MAIN = 0xFFE6EDF3;
    private static final int TEXT_SUB = 0xFF8B949E;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ComfortEngine engine;
    private ComfortConfig current = ComfortConfig.defaults();

    private TextView liveMetrics;
    private TextView statusLines;
    private Button btnWelcome;
    private Button btnClimate;
    private Button btnWheel;
    private Button btnVent;
    private Button btnPassPolicy;
    private Button btnRearLPolicy;
    private Button btnRearRPolicy;
    private TextView lblHeatHigh;
    private TextView lblHeatMed;
    private TextView lblHeatLow;
    private TextView lblWheelF;
    private TextView lblVentLow;
    private TextView lblVentMed;
    private TextView lblVentHigh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, ComfortService.class));
        engine = ComfortEngine.getInstance(this);
        engine.ensureStarted();
        setContentView(buildUi());
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
                render(snap);
            }
        });
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(18);
        root.setPadding(pad, dp(14), pad, dp(14));

        // Header bar
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Comfort & Startup");
        title.setTextColor(TEXT_MAIN);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, tlp);

        Button runNow = pillButton("Run Climate Check Now", ACCENT_BLUE);
        runNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine != null) engine.triggerClimateNow();
            }
        });
        header.addView(runNow, marginRight(dp(10)));

        if (hasHomeScreenTileManager()) {
            Button tilesBtn = pillButton("Home Screen Tiles…", MUTED_BTN);
            tilesBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        Intent it = new Intent();
                        it.setClassName("hmgapi.app", "hmgapi.app.MainActivity");
                        it.putExtra("tab", 1);
                        it.putExtra("settings_cat", "Home screen");
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(it);
                    } catch (Throwable t) {
                        android.widget.Toast.makeText(MainActivity.this,
                                "Open HMG-API > Settings > Home screen", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }
            });
            header.addView(tilesBtn, marginRight(dp(10)));
        }

        Button dismissNow = pillButton("Dismiss Notice", MUTED_BTN);
        dismissNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine != null) engine.triggerWelcomeNow();
            }
        });
        header.addView(dismissNow, marginRight(dp(10)));

        Button close = pillButton("Close", MUTED_BTN);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(close);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(12);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // Live telemetry card
        LinearLayout liveCard = card();
        liveMetrics = new TextView(this);
        liveMetrics.setTextColor(TEXT_MAIN);
        liveMetrics.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        liveMetrics.setTypeface(Typeface.MONOSPACE);
        liveMetrics.setText("Connecting to HMG-API...");
        liveCard.addView(liveMetrics);

        statusLines = new TextView(this);
        statusLines.setTextColor(TEXT_SUB);
        statusLines.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusLines.setPadding(0, dp(6), 0, 0);
        liveCard.addView(statusLines);
        body.addView(liveCard, cardMargin());

        // Feature toggles card
        LinearLayout toggleCard = card();
        toggleCard.addView(sectionTitle("Features & Seat Policies"));
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnWelcome = toggleButton();
        btnWelcome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(!current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        btnClimate = toggleButton();
        btnClimate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        !current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        btnWheel = toggleButton();
        btnWheel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        !current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        btnVent = toggleButton();
        btnVent.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, current.rearRightPolicy, !current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        row1.addView(btnWelcome, weightCell());
        row1.addView(btnClimate, weightCell());
        row1.addView(btnWheel, weightCell());
        row1.addView(btnVent, weightCell());
        toggleCard.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        btnPassPolicy = toggleButton();
        btnPassPolicy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, nextPolicy(current.passengerPolicy),
                        current.rearLeftPolicy, current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        btnRearLPolicy = toggleButton();
        btnRearLPolicy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        nextPolicy(current.rearLeftPolicy), current.rearRightPolicy, current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        btnRearRPolicy = toggleButton();
        btnRearRPolicy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save(new ComfortConfig(current.welcomeDismiss, current.welcomeDelayMs,
                        current.climateEnabled, current.heatHighF, current.heatMedF, current.heatLowF,
                        current.wheelEnabled, current.wheelF, current.passengerPolicy,
                        current.rearLeftPolicy, nextPolicy(current.rearRightPolicy), current.ventEnabled,
                        current.ventLowF, current.ventMedF, current.ventHighF, current.toastOnApply));
            }
        });
        row2.addView(btnPassPolicy, weightCell());
        row2.addView(btnRearLPolicy, weightCell());
        row2.addView(btnRearRPolicy, weightCell());
        toggleCard.addView(row2);
        body.addView(toggleCard, cardMargin());

        // Two-column thresholds card (Winter Heat | Summer Ventilation)
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout coldCard = card();
        coldCard.addView(sectionTitle("Winter Heating Thresholds (F)"));
        lblHeatHigh = new TextView(this);
        lblHeatMed = new TextView(this);
        lblHeatLow = new TextView(this);
        lblWheelF = new TextView(this);
        coldCard.addView(stepperRow("Seat High <=", lblHeatHigh, -2, 2, 0));
        coldCard.addView(stepperRow("Seat Med <=", lblHeatMed, -2, 2, 1));
        coldCard.addView(stepperRow("Seat Low <=", lblHeatLow, -2, 2, 2));
        coldCard.addView(stepperRow("Wheel On <=", lblWheelF, -2, 2, 3));

        LinearLayout warmCard = card();
        warmCard.addView(sectionTitle("Summer Ventilation Thresholds (F)"));
        lblVentLow = new TextView(this);
        lblVentMed = new TextView(this);
        lblVentHigh = new TextView(this);
        warmCard.addView(stepperRow("Vent Low >=", lblVentLow, -2, 2, 4));
        warmCard.addView(stepperRow("Vent Med >=", lblVentMed, -2, 2, 5));
        warmCard.addView(stepperRow("Vent High >=", lblVentHigh, -2, 2, 6));

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.rightMargin = dp(8);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.leftMargin = dp(8);
        cols.addView(coldCard, leftLp);
        cols.addView(warmCard, rightLp);
        body.addView(cols, cardMargin());

        scroll.addView(body);
        root.addView(scroll, slp);
        return root;
    }

    private View stepperRow(String label, TextView valueOut, final int minus, final int plus, final int which) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(TEXT_SUB);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button bMin = pillButton("-", MUTED_BTN);
        bMin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { adjustThreshold(which, minus); }
        });
        row.addView(bMin);

        valueOut.setTextColor(TEXT_MAIN);
        valueOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        valueOut.setTypeface(Typeface.DEFAULT_BOLD);
        valueOut.setGravity(Gravity.CENTER);
        valueOut.setMinWidth(dp(74));
        row.addView(valueOut);

        Button bPlus = pillButton("+", MUTED_BTN);
        bPlus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { adjustThreshold(which, plus); }
        });
        row.addView(bPlus);
        return row;
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
        String tf = val(r, "outside_temp_f");
        String rdy = "1".equals(r.get("hv.ready")) ? "READY" : ("0".equals(r.get("hv.ready")) ? "OFF" : "-");
        String pk = "1".equals(r.get("gear.park")) ? "PARK" : ("0".equals(r.get("gear.park")) ? "DRIVE" : "-");
        String wh = "1".equals(r.get("wheel_heat")) ? "ON" : ("0".equals(r.get("wheel_heat")) ? "OFF" : "-");
        String drvH = val(r, "seat_heat.driver");
        String drvV = val(r, "seat_vent.driver");
        liveMetrics.setText("Outside: " + tf + " F   |   EV: " + rdy + "   |   Gear: " + pk
                + "   |   Driver Heat/Vent: " + drvH + "/" + drvV + "   |   Wheel: " + wh);
        statusLines.setText("Startup Notice: " + s.lastWelcomeStatus + "\nAuto Climate:   " + s.lastClimateStatus);
        updateControls(current);
    }

    private void updateControls(ComfortConfig c) {
        styleToggle(btnWelcome, "Welcome Dismiss: " + (c.welcomeDismiss ? "ON" : "OFF"), c.welcomeDismiss);
        styleToggle(btnClimate, "Auto Climate: " + (c.climateEnabled ? "ON" : "OFF"), c.climateEnabled);
        styleToggle(btnWheel, "Wheel Heat: " + (c.wheelEnabled ? "ON" : "OFF"), c.wheelEnabled);
        styleToggle(btnVent, "Summer Vent: " + (c.ventEnabled ? "ON" : "OFF"), c.ventEnabled);
        styleToggle(btnPassPolicy, "Passenger: " + policyLabel(c.passengerPolicy), !ComfortConfig.POLICY_OFF.equals(c.passengerPolicy));
        styleToggle(btnRearLPolicy, "Rear Left: " + policyLabel(c.rearLeftPolicy), !ComfortConfig.POLICY_OFF.equals(c.rearLeftPolicy));
        styleToggle(btnRearRPolicy, "Rear Right: " + policyLabel(c.rearRightPolicy), !ComfortConfig.POLICY_OFF.equals(c.rearRightPolicy));
        lblHeatHigh.setText(c.heatHighF + " F");
        lblHeatMed.setText(c.heatMedF + " F");
        lblHeatLow.setText(c.heatLowF + " F");
        lblWheelF.setText(c.wheelF + " F");
        lblVentLow.setText(c.ventLowF + " F");
        lblVentMed.setText(c.ventMedF + " F");
        lblVentHigh.setText(c.ventHighF + " F");
    }

    private static String val(Map<String, String> r, String k) {
        String v = r == null ? null : r.get(k);
        return v == null || v.isEmpty() ? "-" : v;
    }

    private static String nextPolicy(String p) {
        if (ComfortConfig.POLICY_OFF.equals(p)) return ComfortConfig.POLICY_LOWER;
        if (ComfortConfig.POLICY_LOWER.equals(p)) return ComfortConfig.POLICY_MATCH;
        return ComfortConfig.POLICY_OFF;
    }

    private static String policyLabel(String p) {
        if (ComfortConfig.POLICY_MATCH.equals(p)) return "Match";
        if (ComfortConfig.POLICY_LOWER.equals(p)) return "-1 Step";
        return "Off";
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        l.setPadding(p, dp(12), p, dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(CARD);
        gd.setCornerRadius(dp(10));
        gd.setStroke(dp(1), BORDER);
        l.setBackground(gd);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private Button pillButton(String text, int bg) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), BORDER);
        b.setBackground(gd);
        return b;
    }

    private Button toggleButton() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        return b;
    }

    private void styleToggle(Button b, String text, boolean active) {
        b.setText(text);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(active ? ACCENT : MUTED_BTN);
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), active ? ACCENT : BORDER);
        b.setBackground(gd);
    }

    private LinearLayout.LayoutParams weightCell() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        lp.leftMargin = dp(6);
        return lp;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        return lp;
    }

    private LinearLayout.LayoutParams marginRight(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px;
        return lp;
    }

    private boolean hasHomeScreenTileManager() {
        try {
            if (new java.io.File("/data/hmgapi/public/launcher-tiles.json").isFile()) return true;
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(
                    "hmgapi.app", android.content.pm.PackageManager.GET_META_DATA);
            if (ai != null && ai.metaData != null && ai.metaData.getBoolean("hmgapi.feature.launcher_tiles", false)) {
                return true;
            }
            Class.forName("hmgapi.engine.LauncherTilesStore");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
