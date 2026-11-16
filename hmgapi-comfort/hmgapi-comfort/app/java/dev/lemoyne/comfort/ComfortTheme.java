package dev.lemoyne.comfort;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.util.TypedValue;

/**
 * Material Design 3 color roles, shape tokens, and state-layer drawables for Comfort & Startup.
 * Adapts automatically between head-unit Night (dark) and Day (light) modes via UiModeManager.
 *
 * Primary seed: Warm Cabin Amber (heating & comfort)
 * Tertiary seed: Cool Breeze Cyan-Teal (summer seat ventilation)
 */
final class ComfortTheme {
    static boolean night = true;

    // M3 Surface container hierarchy (surface -> surfaceContainerHighest)
    static int SURFACE;
    static int SURFACE_LOW;
    static int SURFACE_CONT;
    static int SURFACE_HIGH;
    static int SURFACE_HIGHEST;

    // M3 Primary roles (Warm Cabin Amber)
    static int PRIMARY;
    static int ON_PRIMARY;
    static int PRIMARY_CONT;
    static int ON_PRIMARY_CONT;

    // M3 Secondary roles (Navigation indicator & Segmented Button active fill)
    static int SECONDARY_CONT;
    static int ON_SECONDARY_CONT;

    // M3 Tertiary roles (Cool Summer Ventilation)
    static int TERTIARY;
    static int ON_TERTIARY;
    static int TERTIARY_CONT;
    static int ON_TERTIARY_CONT;

    // Semantic status roles (EV Ready / Connected)
    static int OK;
    static int OK_CONT;
    static int ON_OK_CONT;

    // M3 Content, Outline & State Layer roles
    static int ON_SURFACE;
    static int ON_SURFACE_VAR;
    static int OUTLINE;
    static int OUTLINE_VAR;
    static int STATE_ON_SURFACE;
    static int STATE_ON_PRIMARY;

    static void apply(Context c) {
        int mask = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        // Default to dark theme if head unit reports UNSPECIFIED, since automotive clusters default dark
        night = (mask != Configuration.UI_MODE_NIGHT_NO);
        if (night) {
            SURFACE = 0xFF141311;
            SURFACE_LOW = 0xFF1C1B18;
            SURFACE_CONT = 0xFF22201D;
            SURFACE_HIGH = 0xFF2C2925;
            SURFACE_HIGHEST = 0xFF37342F;

            PRIMARY = 0xFFFFB86C;
            ON_PRIMARY = 0xFF492900;
            PRIMARY_CONT = 0xFF683D00;
            ON_PRIMARY_CONT = 0xFFFFDDBB;

            SECONDARY_CONT = 0xFF46392B;
            ON_SECONDARY_CONT = 0xFFFEDCC2;

            TERTIARY = 0xFF7AD0DF;
            ON_TERTIARY = 0xFF00363E;
            TERTIARY_CONT = 0xFF004E59;
            ON_TERTIARY_CONT = 0xFFAEEFFF;

            OK = 0xFF8BD69E;
            OK_CONT = 0xFF1B4D2B;
            ON_OK_CONT = 0xFFB6F2C4;

            ON_SURFACE = 0xFFEAE1DB;
            ON_SURFACE_VAR = 0xFFCFC4B9;
            OUTLINE = 0xFF988E84;
            OUTLINE_VAR = 0xFF4B443D;
            STATE_ON_SURFACE = 0x22EAE1DB;   // ~13% M3 pressed state layer
            STATE_ON_PRIMARY = 0x24492900;
        } else {
            SURFACE = 0xFFFFF8F4;
            SURFACE_LOW = 0xFFF9F2EC;
            SURFACE_CONT = 0xFFF2ECE5;
            SURFACE_HIGH = 0xFFECE5DE;
            SURFACE_HIGHEST = 0xFFE5DED7;

            PRIMARY = 0xFF885100;
            ON_PRIMARY = 0xFFFFFFFF;
            PRIMARY_CONT = 0xFFFFDDBB;
            ON_PRIMARY_CONT = 0xFF2B1700;

            SECONDARY_CONT = 0xFFFADCC4;
            ON_SECONDARY_CONT = 0xFF27180A;

            TERTIARY = 0xFF006876;
            ON_TERTIARY = 0xFFFFFFFF;
            TERTIARY_CONT = 0xFFAEEFFF;
            ON_TERTIARY_CONT = 0xFF001F25;

            OK = 0xFF1E6C35;
            OK_CONT = 0xFFB6F2C4;
            ON_OK_CONT = 0xFF00210A;

            ON_SURFACE = 0xFF201A16;
            ON_SURFACE_VAR = 0xFF51443B;
            OUTLINE = 0xFF837469;
            OUTLINE_VAR = 0xFFD5C3B6;
            STATE_ON_SURFACE = 0x1F201A16;
            STATE_ON_PRIMARY = 0x24FFFFFF;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static GradientDrawable shape(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0 && stroke != 0) {
            g.setStroke(strokePx, stroke);
        }
        return g;
    }

    static GradientDrawable shapeRadii(int fill, int stroke, int strokePx, float[] radii) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadii(radii);
        if (strokePx > 0 && stroke != 0) {
            g.setStroke(strokePx, stroke);
        }
        return g;
    }

    /** M3 interactive container with a pressed/focused state-layer overlay (API 19 compatible). */
    static Drawable interactiveShape(int fill, int stroke, int strokePx, float radiusPx, int stateLayerColor) {
        GradientDrawable normal = shape(fill, stroke, strokePx, radiusPx);
        GradientDrawable pressedBase = shape(fill, stroke, strokePx, radiusPx);
        GradientDrawable overlay = shape(stateLayerColor, 0, 0, radiusPx);
        LayerDrawable pressed = new LayerDrawable(new Drawable[] { pressedBase, overlay });

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed }, pressed);
        sld.addState(new int[] { android.R.attr.state_focused }, pressed);
        sld.addState(StateSet.WILD_CARD, normal);
        return sld;
    }

    /** M3 interactive container with per-corner radii (used for M3 SegmentedButton segments). */
    static Drawable interactiveRadii(int fill, int stroke, int strokePx, float[] radii, int stateLayerColor) {
        GradientDrawable normal = shapeRadii(fill, stroke, strokePx, radii);
        GradientDrawable pressedBase = shapeRadii(fill, stroke, strokePx, radii);
        GradientDrawable overlay = shapeRadii(stateLayerColor, 0, 0, radii);
        LayerDrawable pressed = new LayerDrawable(new Drawable[] { pressedBase, overlay });

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed }, pressed);
        sld.addState(new int[] { android.R.attr.state_focused }, pressed);
        sld.addState(StateSet.WILD_CARD, normal);
        return sld;
    }

    private ComfortTheme() {}
}
