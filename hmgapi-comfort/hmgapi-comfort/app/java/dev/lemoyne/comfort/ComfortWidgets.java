package dev.lemoyne.comfort;

import static dev.lemoyne.comfort.ComfortTheme.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Authentic Material Design 3 components built natively for Android API 19 (KitKat):
 * - M3Switch: animated 52x32dp stadium switch with 16->24->28dp thumb, state halo & checkmark
 * - M3SegmentedGroup: single-select stadium segmented button bar with shared 1dp outline
 * - SeatTileView: live cabin seat heat/ventilation card with 3-pip level indicator
 * - SpectrumBarView: seasonal temperature zone visualizer with live ambient marker
 * - M3 Filled, Tonal, Outlined, and Circular Icon buttons with pressed state layers
 */
final class ComfortWidgets {
    static final int BTN_FILLED = 1;
    static final int BTN_TONAL = 2;
    static final int BTN_OUTLINED = 3;

    interface ToggleListener {
        void onToggle(boolean checked);
    }

    interface PickListener {
        void onPick(String value);
    }

    static int blendColor(int c1, int c2, float t) {
        if (t <= 0f) return c1;
        if (t >= 1f) return c2;
        int a = Math.round(Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = Math.round(Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = Math.round(Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = Math.round(Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }

    /** Material Design 3 52x32dp stadium switch inside a 58x38dp touch target. */
    static final class M3Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final Path checkPath = new Path();
        private final float d;
        private boolean checked;
        private boolean pressedDown;
        private float progress;
        private ValueAnimator anim;
        private final ToggleListener listener;

        M3Switch(Context c, boolean initial, ToggleListener onToggle) {
            super(c);
            this.d = c.getResources().getDisplayMetrics().density;
            this.checked = initial;
            this.progress = initial ? 1f : 0f;
            this.listener = onToggle;
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isEnabled()) return;
                    setChecked(!checked, true);
                    if (listener != null) listener.onToggle(checked);
                }
            });
        }

        void setChecked(boolean next, boolean animate) {
            if (checked == next && (anim == null || !anim.isRunning())) {
                float target = next ? 1f : 0f;
                if (Math.abs(progress - target) > 0.01f) {
                    progress = target;
                    invalidate();
                }
                return;
            }
            checked = next;
            final float target = next ? 1f : 0f;
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            if (!animate) {
                progress = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(progress, target);
            anim.setDuration(165);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    progress = ((Float) va.getAnimatedValue()).floatValue();
                    invalidate();
                }
            });
            anim.start();
        }

        boolean isChecked() {
            return checked;
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.38f);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && isEnabled()) {
                pressedDown = true;
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressedDown = false;
                invalidate();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(Math.round(58 * d), Math.round(38 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            float vw = getWidth(), vh = getHeight();
            float tw = 52f * d, th = 32f * d;
            float left = (vw - tw) * 0.5f;
            float top = (vh - th) * 0.5f;
            float r = th * 0.5f;

            // Track fill & outline
            trackRect.set(left + 1f * d, top + 1f * d, left + tw - 1f * d, top + th - 1f * d);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blendColor(SURFACE_HIGHEST, PRIMARY, progress));
            c.drawRoundRect(trackRect, r, r, paint);

            if (progress < 0.99f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f * d * (1f - progress));
                paint.setColor(withAlpha(OUTLINE, Math.round(255 * (1f - progress))));
                c.drawRoundRect(trackRect, r, r, paint);
            }

            // Thumb center & M3 dynamic diameter (16dp off -> 24dp on -> 28dp pressed)
            float startCx = left + th * 0.5f;
            float endCx = left + tw - th * 0.5f;
            float cx = startCx + (endCx - startCx) * progress;
            float cy = vh * 0.5f;
            float baseRadius = (8f + 4f * progress) * d;
            float thumbRadius = pressedDown ? 13.5f * d : baseRadius;

            if (pressedDown) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(checked ? PRIMARY : ON_SURFACE, 34));
                c.drawCircle(cx, cy, 19f * d, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blendColor(OUTLINE, ON_PRIMARY, progress));
            c.drawCircle(cx, cy, thumbRadius, paint);

            // Checkmark inside active thumb
            if (progress > 0.35f) {
                float checkAlpha = Math.min(1f, (progress - 0.35f) / 0.65f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.1f * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(withAlpha(PRIMARY_CONT, Math.round(255 * checkAlpha)));
                checkPath.reset();
                checkPath.moveTo(cx - 4.4f * d, cy + 0.2f * d);
                checkPath.lineTo(cx - 1.1f * d, cy + 3.4f * d);
                checkPath.lineTo(cx + 4.7f * d, cy - 3.2f * d);
                c.drawPath(checkPath, paint);
            }
        }
    }

    /** Material Design 3 Single-Select Segmented Button bar with idempotent setValue(). */
    static final class M3SegmentedGroup extends LinearLayout {
        private final String[][] options;
        private final float d;
        private final float pillR;
        private final PickListener listener;
        private String value;

        M3SegmentedGroup(Context c, String[][] options, String initial, int heightDp, PickListener listener) {
            super(c);
            this.options = options;
            this.d = c.getResources().getDisplayMetrics().density;
            this.pillR = (heightDp * 0.5f) * d;
            this.listener = listener;
            this.value = initial;
            setOrientation(HORIZONTAL);
            setBaselineAligned(false);

            int n = options.length;
            int strokePx = Math.max(1, Math.round(1f * d));
            for (int i = 0; i < n; i++) {
                final String optVal = options[i][0];
                LinearLayout seg = new LinearLayout(c);
                seg.setGravity(Gravity.CENTER);
                seg.setClickable(true);
                int padH = Math.round(10 * d);
                seg.setPadding(padH, 0, padH, 0);

                View checkView = new View(c);
                checkView.setBackground(new ComfortIcons(ComfortIcons.CHECK, ON_SECONDARY_CONT));
                LayoutParams ip = new LayoutParams(Math.round(15 * d), Math.round(15 * d));
                ip.rightMargin = Math.round(5 * d);
                seg.addView(checkView, ip);

                TextView lbl = new TextView(c);
                lbl.setText(options[i][1]);
                lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                lbl.setSingleLine(true);
                seg.addView(lbl);

                seg.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (optVal.equals(value)) return;
                        setValue(optVal);
                        if (M3SegmentedGroup.this.listener != null) {
                            M3SegmentedGroup.this.listener.onPick(optVal);
                        }
                    }
                });

                LayoutParams lp = new LayoutParams(0, Math.round(heightDp * d), 1f);
                if (i > 0) lp.leftMargin = -strokePx;
                addView(seg, lp);
            }
            applyStyles();
        }

        void setValue(String next) {
            if (next == null || next.equals(value)) return;
            value = next;
            applyStyles();
        }

        private void applyStyles() {
            int n = options.length;
            int strokePx = Math.max(1, Math.round(1f * d));
            for (int i = 0; i < n; i++) {
                LinearLayout seg = (LinearLayout) getChildAt(i);
                View check = seg.getChildAt(0);
                TextView lbl = (TextView) seg.getChildAt(1);
                boolean sel = options[i][0].equals(value);
                float rl = (i == 0) ? pillR : 0f;
                float rr = (i == n - 1) ? pillR : 0f;
                float[] radii = { rl, rl, rr, rr, rr, rr, rl, rl };
                seg.setBackground(interactiveRadii(
                        sel ? SECONDARY_CONT : Color.TRANSPARENT,
                        OUTLINE, strokePx, radii, STATE_ON_SURFACE));
                check.setVisibility(sel ? View.VISIBLE : View.GONE);
                lbl.setTextColor(sel ? ON_SECONDARY_CONT : ON_SURFACE);
                lbl.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
    }

    /** Live cabin seat tile showing seat title, active state label, and 3-pip heat/vent bars. */
    static final class SeatTileView extends LinearLayout {
        private final TextView stateLabel;
        private final PipsView pips;
        private final float d;
        private int lastMode = -1;
        private int lastLevel = -1;

        SeatTileView(Context c, String seatTitle) {
            super(c);
            this.d = c.getResources().getDisplayMetrics().density;
            setOrientation(VERTICAL);
            int pH = dp(c, 11), pV = dp(c, 9);
            setPadding(pH, pV, pH, pV);

            TextView title = new TextView(c);
            title.setText(seatTitle);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(ON_SURFACE);
            title.setSingleLine(true);
            addView(title);

            LinearLayout bottom = new LinearLayout(c);
            bottom.setOrientation(HORIZONTAL);
            bottom.setGravity(Gravity.CENTER_VERTICAL);
            LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(c, 5);

            stateLabel = new TextView(c);
            stateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            stateLabel.setSingleLine(true);
            bottom.addView(stateLabel, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            pips = new PipsView(c);
            bottom.addView(pips);
            addView(bottom, bp);

            update(0, 0);
        }

        /** mode: 0 = off, 1 = heat (1..3), 2 = vent (1..3) */
        void update(int mode, int level) {
            if (level <= 0) mode = 0;
            if (mode == lastMode && level == lastLevel) return;
            lastMode = mode;
            lastLevel = level;

            int fill, border, textCol;
            if (mode == 1) {
                fill = withAlpha(PRIMARY_CONT, night ? 150 : 185);
                border = PRIMARY;
                textCol = night ? PRIMARY : ON_PRIMARY_CONT;
                stateLabel.setText(level == 3 ? "High Heat" : (level == 2 ? "Med Heat" : "Low Heat"));
            } else if (mode == 2) {
                fill = withAlpha(TERTIARY_CONT, night ? 150 : 185);
                border = TERTIARY;
                textCol = night ? TERTIARY : ON_TERTIARY_CONT;
                stateLabel.setText(level == 3 ? "High Vent" : (level == 2 ? "Med Vent" : "Low Vent"));
            } else {
                fill = SURFACE_HIGH;
                border = OUTLINE_VAR;
                textCol = ON_SURFACE_VAR;
                stateLabel.setText("Off");
            }
            stateLabel.setTextColor(textCol);
            setBackground(shape(fill, border, Math.max(1, Math.round(1f * d)), 12f * d));
            pips.set(mode, level);
        }
    }

    private static final class PipsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;
        private int mode;
        private int level;

        PipsView(Context c) {
            super(c);
            this.d = c.getResources().getDisplayMetrics().density;
        }

        void set(int m, int l) {
            this.mode = m;
            this.level = l;
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(Math.round(30 * d), Math.round(10 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            float pw = 7.5f * d, ph = 6.5f * d, gap = 3.2f * d;
            float y = (getHeight() - ph) * 0.5f;
            int activeCol = (mode == 1) ? PRIMARY : TERTIARY;
            for (int i = 0; i < 3; i++) {
                float x = i * (pw + gap);
                r.set(x, y, x + pw, y + ph);
                p.setColor(i < level ? activeCol : SURFACE_HIGHEST);
                c.drawRoundRect(r, ph * 0.5f, ph * 0.5f, p);
            }
        }
    }

    /** Seasonal temperature spectrum bar (-5°F .. 115°F) showing heating/neutral/ventilation zones & live ambient temp. */
    static final class SpectrumBarView extends View {
        private static final float MIN_F = -5f;
        private static final float MAX_F = 115f;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final float d;
        private final float sp;
        private ComfortConfig cfg = ComfortConfig.defaults();
        private float ambientF = Float.NaN;

        SpectrumBarView(Context c) {
            super(c);
            this.d = c.getResources().getDisplayMetrics().density;
            this.sp = c.getResources().getDisplayMetrics().scaledDensity;
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void update(ComfortConfig nextCfg, float nextAmbientF) {
            this.cfg = nextCfg;
            this.ambientF = nextAmbientF;
            invalidate();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, Math.round(54 * d));
        }

        private float xFor(float tempF, float left, float width) {
            float clamped = Math.max(MIN_F, Math.min(MAX_F, tempF));
            return left + ((clamped - MIN_F) / (MAX_F - MIN_F)) * width;
        }

        @Override
        protected void onDraw(Canvas c) {
            float padH = 10f * d;
            float left = padH;
            float right = getWidth() - padH;
            float width = Math.max(1f, right - left);
            float barTop = 20f * d;
            float barBot = 34f * d;
            float barR = (barBot - barTop) * 0.5f;

            float xHH = xFor(cfg.heatHighF, left, width);
            float xHM = xFor(cfg.heatMedF, left, width);
            float xHL = xFor(cfg.heatLowF, left, width);
            float xVL = xFor(cfg.ventLowF, left, width);
            float xVM = xFor(cfg.ventMedF, left, width);
            float xVH = xFor(cfg.ventHighF, left, width);

            // Clip to full stadium pill for crisp outer corners
            c.save();
            clip.reset();
            rect.set(left, barTop, right, barBot);
            clip.addRoundRect(rect, barR, barR, Path.Direction.CW);
            c.clipPath(clip);

            drawBand(c, left, xHH, barTop, barBot, cfg.climateEnabled ? PRIMARY : SURFACE_HIGHEST);
            drawBand(c, xHH, xHM, barTop, barBot, cfg.climateEnabled ? blendColor(PRIMARY, PRIMARY_CONT, 0.42f) : SURFACE_HIGHEST);
            drawBand(c, xHM, xHL, barTop, barBot, cfg.climateEnabled ? PRIMARY_CONT : SURFACE_HIGHEST);
            drawBand(c, xHL, xVL, barTop, barBot, SURFACE_HIGHEST);
            boolean ventOn = cfg.climateEnabled && cfg.ventEnabled;
            drawBand(c, xVL, xVM, barTop, barBot, ventOn ? TERTIARY_CONT : SURFACE_HIGHEST);
            drawBand(c, xVM, xVH, barTop, barBot, ventOn ? blendColor(TERTIARY, TERTIARY_CONT, 0.42f) : SURFACE_HIGHEST);
            drawBand(c, xVH, right, barTop, barBot, ventOn ? TERTIARY : SURFACE_HIGHEST);
            c.restore();

            // Subtle border around spectrum track
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f * d);
            p.setColor(OUTLINE_VAR);
            rect.set(left, barTop, right, barBot);
            c.drawRoundRect(rect, barR, barR, p);

            // Threshold tick labels underneath (skipping collisions within 28dp)
            textPaint.setTextSize(11f * sp);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(ON_SURFACE_VAR);
            float[] xs = { xHH, xHM, xHL, xVL, xVM, xVH };
            int[] vals = { cfg.heatHighF, cfg.heatMedF, cfg.heatLowF, cfg.ventLowF, cfg.ventMedF, cfg.ventHighF };
            float lastDrawnX = -999f;
            float minGap = 28f * d;
            for (int i = 0; i < xs.length; i++) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(withAlpha(ON_SURFACE, 130));
                c.drawCircle(xs[i], (barTop + barBot) * 0.5f, 1.8f * d, p);
                if (xs[i] - lastDrawnX >= minGap) {
                    c.drawText(vals[i] + "°", xs[i], barBot + 14.5f * d, textPaint);
                    lastDrawnX = xs[i];
                }
            }

            // Live outside_temp_f marker above bar
            if (!Float.isNaN(ambientF)) {
                float mx = xFor(ambientF, left, width);
                String tag = Math.round(ambientF) + "°F Now";
                textPaint.setTextSize(11.5f * sp);
                float tw = textPaint.measureText(tag) + 12f * d;
                float pillLeft = Math.max(left, Math.min(right - tw, mx - tw * 0.5f));
                rect.set(pillLeft, 1f * d, pillLeft + tw, 16.5f * d);
                p.setStyle(Paint.Style.FILL);
                p.setColor(ON_SURFACE);
                c.drawRoundRect(rect, 8f * d, 8f * d, p);

                textPaint.setColor(SURFACE);
                c.drawText(tag, rect.centerX(), rect.bottom - 3.8f * d, textPaint);

                // Pointer dot on bar
                p.setColor(SURFACE);
                c.drawCircle(mx, (barTop + barBot) * 0.5f, 4.8f * d, p);
                p.setColor(ON_SURFACE);
                c.drawCircle(mx, (barTop + barBot) * 0.5f, 3.0f * d, p);
            } else {
                // Static captions when offline / waiting
                textPaint.setTextSize(11f * sp);
                textPaint.setColor(PRIMARY);
                textPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText("Winter Heating", left + 2f * d, 13.5f * d, textPaint);

                textPaint.setColor(ON_SURFACE_VAR);
                textPaint.setTextAlign(Paint.Align.CENTER);
                c.drawText("Comfort Zone", (xHL + xVL) * 0.5f, 13.5f * d, textPaint);

                textPaint.setColor(TERTIARY);
                textPaint.setTextAlign(Paint.Align.RIGHT);
                c.drawText("Summer Ventilation", right - 2f * d, 13.5f * d, textPaint);
            }
        }

        private void drawBand(Canvas c, float x0, float x1, float top, float bot, int col) {
            if (x1 <= x0) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawRect(x0, top, x1 + 0.6f, bot, p);
        }
    }

    /** Rounded icon badge for M3 list items & card headers. */
    static FrameLayout iconBadge(Context c, int iconKind, int bgCol, int iconCol, int boxDp, int iconDp) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(shape(bgCol, 0, 0, dp(c, boxDp * 0.34f)));
        View ic = new View(c);
        ic.setBackground(new ComfortIcons(iconKind, iconCol));
        box.addView(ic, new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(c, boxDp), dp(c, boxDp)));
        return box;
    }

    /** Circular M3 Tonal / Outlined Icon Button (used for steppers and Close). */
    static FrameLayout circleIconButton(Context c, int iconKind, int fill, int iconCol, int strokeCol, int sizeDp, View.OnClickListener click) {
        FrameLayout box = new FrameLayout(c);
        int strokePx = strokeCol != 0 ? Math.max(1, dp(c, 1)) : 0;
        box.setBackground(interactiveShape(fill, strokeCol, strokePx, dp(c, sizeDp * 0.5f), STATE_ON_SURFACE));
        View ic = new View(c);
        ic.setBackground(new ComfortIcons(iconKind, iconCol));
        int icPx = dp(c, Math.round(sizeDp * 0.52f));
        box.addView(ic, new FrameLayout.LayoutParams(icPx, icPx, Gravity.CENTER));
        box.setClickable(true);
        box.setOnClickListener(click);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return box;
    }

    /** Material Design 3 Stadium Button (Filled, Tonal, or Outlined) with optional leading vector icon. */
    static LinearLayout m3Button(Context c, int iconKind, String text, int style, int heightDp, View.OnClickListener click) {
        int fill, fg, stroke, stateCol;
        if (style == BTN_FILLED) {
            fill = PRIMARY;
            fg = ON_PRIMARY;
            stroke = 0;
            stateCol = STATE_ON_PRIMARY;
        } else if (style == BTN_OUTLINED) {
            fill = Color.TRANSPARENT;
            fg = ON_SURFACE;
            stroke = OUTLINE;
            stateCol = STATE_ON_SURFACE;
        } else {
            fill = SECONDARY_CONT;
            fg = ON_SECONDARY_CONT;
            stroke = 0;
            stateCol = STATE_ON_SURFACE;
        }

        LinearLayout btn = new LinearLayout(c);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        int padL = dp(c, iconKind != 0 ? 14 : 18);
        int padR = dp(c, 18);
        btn.setPadding(padL, 0, padR, 0);
        btn.setMinimumHeight(dp(c, heightDp));
        int strokePx = stroke != 0 ? Math.max(1, dp(c, 1)) : 0;
        btn.setBackground(interactiveShape(fill, stroke, strokePx, dp(c, heightDp * 0.5f), stateCol));
        btn.setClickable(true);
        btn.setOnClickListener(click);

        if (iconKind != 0) {
            View ic = new View(c);
            ic.setBackground(new ComfortIcons(iconKind, fg));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18));
            ip.rightMargin = dp(c, 8);
            btn.addView(ic, ip);
        }

        TextView lbl = new TextView(c);
        lbl.setText(text);
        lbl.setTextColor(fg);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setSingleLine(true);
        btn.addView(lbl);
        return btn;
    }

    private ComfortWidgets() {}
}
