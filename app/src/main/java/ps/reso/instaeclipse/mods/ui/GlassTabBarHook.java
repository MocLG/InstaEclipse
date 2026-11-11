package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.mods.ui.theme.IgColorRemapEngine;
import ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Glass navigation bar (#281): a floating, see-through, blurred bottom tab bar.
 *
 * Instagram already ships a floating tab bar (MainTabUtil styles FLOATING / FIXED /
 * FIXED_IN_FLOATING_POSITION) behind a MobileConfig gate. With the gate on, Instagram itself lays
 * the content out under the bar, publishes the content inset, moves badges and keeps the Reels tab
 * on its own fixed bar. So we:
 *  1. force that gate to true. It is the static boolean(UserSession) that InstagramMainActivity
 *     calls right before it stores the style in MainTabUtil's static style field; its class also
 *     has a static int(Context, UserSession) (the content inset). Anchored on the string
 *     "MainTabUtil.applyTabBarStyle" (the apply method) and on that field, never on X.* names or
 *     the MobileConfig id (which changes between builds: 452.0.0.0.60 vs 452.0.0.33.64).
 *  2. replace the drawing of Instagram's pill drawable (found at runtime: the drawable wrapped by
 *     the tab_bar background InsetDrawable) with a translucent pill in the bar's own color. On
 *     Android 12+ with hardware drawing, the content behind the pill is drawn into a RenderNode
 *     with a blur RenderEffect. The blur is refreshed only when something scrolls or lays out, so
 *     an idle screen costs nothing.
 *
 * Tabs, the profile avatar and badges are Instagram's own views and are not touched. Every step
 * is guarded: if a lookup fails we log and leave Instagram's bar as it is.
 */
public final class GlassTabBarHook {
    private static final String TAG = "(IE|GlassTabBar) ";
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String CACHE_GATE = "GlassTabBarGate";

    private static int tabBarId = 0;
    private static int tabBarShadowId = 0;
    private static boolean idsResolved;
    private static volatile boolean pillHooked;
    private static Class<?> pillClass;
    private static List<Field> pillPaints;

    private static final Set<View> watchedBars = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> watchedTopBars = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> watchedDecors = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, Glass> glassByBar = new WeakHashMap<>();

    /** Top-chrome bars to frost on whichever screen shows them (feed, profile, DM thread). */
    private static final String[] TOP_BAR_IDS = {
            "action_bar_LinearLayout", "main_feed_action_bar", "profile_action_bar", "direct_thread_header"};

    /** Overlay surfaces that sit over content and so frost for real: bottom sheets, dialogs. Both are
     *  same-window containers with an opaque GradientDrawable background in IG 452. */
    private static final String[] SURFACE_IDS = {"bottom_sheet_container", "dialog_container"};

    /** Marker for any background we installed, so the window scroll listener can invalidate them all. */
    interface GlassBg {}

    // ---------------------------------------------------------------------------------------------
    // 1) The floating tab bar gate
    // ---------------------------------------------------------------------------------------------

    public void install(DexKitBridge bridge, ClassLoader cl) {
        if (!FeatureFlags.glassTabBar) return; // the style is chosen when Instagram starts
        try {
            Method gate = null;
            if (DexKitCache.isCacheValid()) gate = DexKitCache.loadMethod(CACHE_GATE, cl);
            if (gate == null) {
                gate = findGate(bridge, cl);
                if (gate != null) DexKitCache.saveMethod(CACHE_GATE, gate);
            }
            if (gate == null) return; // findGate logged why
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (FeatureFlags.glassTabBar) param.setResult(true);
                }
            });
            FeatureStatusTracker.setHooked("GlassTabBar");
            ModuleLog.line(TAG + "✅ floating gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static Method findGate(DexKitBridge bridge, ClassLoader cl) throws Throwable {
        // MainTabUtil.applyTabBarStyle(Activity, UserSession, Style)
        Method apply = null;
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("MainTabUtil.applyTabBarStyle")))) {
            Method m;
            try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && Activity.class.isAssignableFrom(p[0]) && p[2].isEnum()) { apply = m; break; }
        }
        if (apply == null) {
            ModuleLog.line(TAG + "❌ MainTabUtil.applyTabBarStyle not found");
            return null;
        }
        Class<?> util = apply.getDeclaringClass();
        Class<?> style = apply.getParameterTypes()[2];
        Field styleField = null;
        for (Field f : util.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()) && f.getType() == style) {
                styleField = f;
                break;
            }
        }
        if (styleField == null) {
            ModuleLog.line(TAG + "❌ style field not found in " + util.getName());
            return null;
        }
        String fieldDesc = "L" + util.getName().replace('.', '/') + ";->" + styleField.getName()
                + ":L" + style.getName().replace('.', '/') + ";";
        Class<?> session = cl.loadClass(SESSION);
        Set<Method> gates = new LinkedHashSet<>();
        for (MethodData caller : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(MAIN_ACTIVITY).addUsingField(fieldDesc)))) {
            for (MethodData inv : caller.getInvokes()) {
                if (!inv.isMethod()) continue;
                Method m;
                try { m = inv.getMethodInstance(cl); } catch (Throwable t) { continue; }
                Class<?>[] p = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != boolean.class
                        || p.length != 1 || p[0] != session) continue;
                if (hasInsetSibling(m.getDeclaringClass(), session)) gates.add(m);
            }
        }
        if (gates.size() != 1) {
            ModuleLog.line(TAG + "❌ floating gate candidates: " + gates);
            return null;
        }
        return gates.iterator().next();
    }

    /** The gate's class also has a static int(Context, UserSession): the floating content inset. */
    private static boolean hasInsetSibling(Class<?> c, Class<?> session) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == int.class
                    && p.length == 2 && p[0] == Context.class && p[1] == session) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 2) The glass look
    // ---------------------------------------------------------------------------------------------

    /** Called on every resume of the main activity (UIHookManager.setupHooks). */
    public static void watchActivity(Activity a) {
        if (a == null || !FeatureFlags.glassTabBar) return;
        try {
            ensureIds(a);
            if (tabBarId == 0) return;
            View bar = a.findViewById(tabBarId);
            if (bar == null) return;
            hookPill(bar);
            if (!watchedBars.add(bar)) return;
            // Refresh the blur when the content behind moves. RecyclerView reports its scrolls
            // through onScrollChanged, so feed / profile / pager swipes all land here; an idle
            // screen triggers nothing (no per-frame redraw).
            bar.getViewTreeObserver().addOnScrollChangedListener(() -> refresh(bar));
            bar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                hookPill(bar);
                applyFixedGlass(bar);
                refresh(bar);
            });
            applyFixedGlass(bar);
            watchTopBar(a);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ watch: " + t);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Activity a) {
        if (idsResolved) return;
        String pkg = a.getPackageName();
        tabBarId = a.getResources().getIdentifier("tab_bar", "id", pkg);
        tabBarShadowId = a.getResources().getIdentifier("tab_bar_shadow", "id", pkg);
        idsResolved = true;
    }

    /**
     * Frost the top chrome of whatever screen is showing — the feed action bar, the profile header,
     * the DM thread header. A single listener on the window re-scans on every layout (so fragment
     * swaps between screens are caught) and invalidates the glass on scroll so it tracks the content
     * behind it. Each named bar is frosted only when present and visible.
     */
    private static void watchTopBar(Activity a) {
        if (Build.VERSION.SDK_INT < 31 || a.getWindow() == null) return;
        View decor = a.getWindow().getDecorView();
        if (decor == null) return;
        if (watchedDecors.add(decor)) {
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> { scanTopBars(a); scanSurfaces(a); });
            decor.getViewTreeObserver().addOnScrollChangedListener(() -> {
                for (View bar : new ArrayList<>(watchedTopBars)) {
                    if (bar.getBackground() instanceof GlassBg && bar.isShown()) bar.invalidate();
                }
            });
        }
        scanTopBars(a);
        scanSurfaces(a);
    }

    @SuppressLint("DiscouragedApi")
    private static void scanTopBars(Activity a) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        for (String name : TOP_BAR_IDS) {
            try {
                int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
                if (id == 0) continue;
                View v = a.findViewById(id);
                if (v != null && v.isShown()) applyTopBarGlass(v);
            } catch (Throwable ignored) {}
        }
    }

    private static void applyTopBarGlass(View top) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        Drawable bg = top.getBackground();
        if (bg instanceof TopBarGlass) { watchedTopBars.add(top); return; }
        int color;
        if (bg instanceof android.graphics.drawable.ColorDrawable cd) {
            if (Color.alpha(cd.getColor()) == 0) return; // already see-through
            color = cd.getColor();
        } else if (bg instanceof android.graphics.drawable.GradientDrawable gd) {
            android.content.res.ColorStateList csl = gd.getColor();
            if (csl == null) return; // a real gradient / no readable solid color: leave it alone
            color = csl.getDefaultColor();
            if (Color.alpha(color) == 0) return;
        } else if (bg == null) {
            color = Color.BLACK;
        } else {
            return; // unknown background: leave it alone
        }
        try {
            float density = top.getResources().getDisplayMetrics().density;
            top.setBackground(new TopBarGlass(top, color, density));
            watchedTopBars.add(top);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ top glass: " + t);
        }
    }

    /** Frost the overlay surfaces (bottom sheets, dialogs) that sit over content. */
    @SuppressLint("DiscouragedApi")
    private static void scanSurfaces(Activity a) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        for (String name : SURFACE_IDS) {
            try {
                int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
                if (id == 0) continue;
                View v = a.findViewById(id);
                if (v == null || !v.isShown()) continue;
                // The container itself is a transparent wrapper (it also holds the dim scrim); the
                // actual sheet / dialog panel is an inner view carrying the opaque rounded background.
                View panel = findPanel(v);
                if (panel != null) applySurfaceGlass(panel);
            } catch (Throwable ignored) {}
        }
    }

    /** The visible descendant holding an opaque rounded panel background (the sheet/dialog surface). */
    private static View findPanel(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        Drawable bg = v.getBackground();
        if (bg instanceof SurfaceGlass) return v; // already glassed
        if (bg instanceof android.graphics.drawable.GradientDrawable gd) {
            android.content.res.ColorStateList c = gd.getColor();
            // A rounded, mostly-solid panel. The thin flat dividers are ColorDrawable, not this; the
            // >=160 alpha (not ==255) still matches after the glass translucency sweep dims surfaces.
            if (c != null && Color.alpha(c.getDefaultColor()) >= 160 && hasCorners(gd)) return v;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findPanel(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean hasCorners(android.graphics.drawable.GradientDrawable gd) {
        try {
            float r = gd.getCornerRadius();
            if (r > 0f) return true;
            float[] radii = gd.getCornerRadii();
            if (radii != null) for (float x : radii) if (x > 0f) return true;
            return false;
        } catch (Throwable t) {
            return true; // can't tell (older API): treat as a panel rather than miss it
        }
    }

    private static void applySurfaceGlass(View panel) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        Drawable bg = panel.getBackground();
        if (bg instanceof SurfaceGlass) { watchedTopBars.add(panel); return; }
        if (!(bg instanceof android.graphics.drawable.GradientDrawable gd)) return;
        android.content.res.ColorStateList csl = gd.getColor();
        if (csl == null) return;
        int color = csl.getDefaultColor();
        if (Color.alpha(color) == 0) return;
        try {
            float density = panel.getResources().getDisplayMetrics().density;
            float corner = cornerOf(gd, density);
            panel.setBackground(new SurfaceGlass(panel, color, density, corner));
            watchedTopBars.add(panel);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ surface glass: " + t);
        }
    }

    /** Match the panel's own corner radius so the glass keeps the sheet/dialog shape. */
    private static float cornerOf(android.graphics.drawable.GradientDrawable gd, float density) {
        try {
            float r = gd.getCornerRadius();
            if (r > 0f) return r;
            float[] radii = gd.getCornerRadii();
            if (radii != null) for (float x : radii) if (x > 0f) return x;
        } catch (Throwable ignored) {}
        return density * 18f;
    }

    /** Frosted full-width background for the top action bar: blurs the feed scrolling behind it. */
    static final class TopBarGlass extends Drawable implements GlassBg {
        private final View host;
        private final int color;
        private final float density;
        private final Glass glass;
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.LinearGradient sheenShader;
        private float sheenShaderH = -1f;
        private boolean sheenShaderDark;

        TopBarGlass(View host, int color, float density) {
            this.host = host;
            this.color = color;
            this.density = density;
            this.glass = new Glass(density);
            this.glass.cornerRadius = 0f;   // full-width rectangular bar
            this.glass.flatAlpha = 0.58f;   // a translucent frosted panel even with nothing behind
        }

        @Override
        public void draw(Canvas canvas) {
            if (!FeatureFlags.glassTabBar) return;
            try {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                // Frost whatever is behind (DM threads) or fall back to the translucent tint.
                glass.draw(canvas, b, host, color);
                // Sell the glass over a flat / dark backdrop too: a soft light sheen fading down from
                // the top and a crisp bright rim along the bottom edge, like light catching a panel.
                int base = color | 0xFF000000;
                if (IgThemeEngine.isActive()) {
                    try { base = IgColorRemapEngine.remap(base) | 0xFF000000; } catch (Throwable ignored) {}
                }
                boolean dark = (0.299f * Color.red(base) + 0.587f * Color.green(base)
                        + 0.114f * Color.blue(base)) / 255f < 0.5f;
                int sheenRGB = dark ? 0x00FFFFFF : 0x00000000; // white sheen on dark bars, black on light
                float sheenH = Math.min(b.height() * 0.6f, density * 56f);
                if (sheenShader == null || sheenShaderH != sheenH || sheenShaderDark != dark) {
                    sheenShader = new android.graphics.LinearGradient(0, b.top, 0, b.top + sheenH,
                            0x30000000 | sheenRGB, sheenRGB, Shader.TileMode.CLAMP);
                    sheenShaderH = sheenH;
                    sheenShaderDark = dark;
                }
                sheen.setShader(sheenShader);
                canvas.drawRect(b.left, b.top, b.right, b.top + sheenH, sheen);
                sheen.setShader(null);
                float hair = Math.max(1f, density * 1.0f);
                sheen.setColor(0x80000000 | sheenRGB); // bright glass rim along the bottom edge
                canvas.drawRect(b.left, b.bottom - hair, b.right, b.bottom, sheen);
                sheen.setColor(0x24000000 | sheenRGB); // faint rim along the top edge to close the panel
                canvas.drawRect(b.left, b.top, b.right, b.top + Math.max(1f, density * 0.6f), sheen);
            } catch (Throwable ignored) {}
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Frosted background for an overlay surface that sits over content — a bottom sheet or a dialog.
     * These are same-window containers, so the blur engine captures the dimmed feed behind them and
     * frosts it. The fill stays fairly opaque (readable text over the frost) and the panel keeps a
     * rounded top like Instagram's own sheet, with a faint rim to define the glass edge.
     */
    static final class SurfaceGlass extends Drawable implements GlassBg {
        private final View host;
        private final int color;
        private final float density;
        private final Glass glass;
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

        SurfaceGlass(View host, int color, float density, float corner) {
            this.host = host;
            this.color = color;
            this.density = density;
            this.glass = new Glass(density);
            this.glass.cornerRadius = corner > 0f ? corner : density * 18f; // keep IG's panel shape
            this.glass.flatAlpha = 0.74f;            // readable: text sits on the frost, not raw feed
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, density * 0.75f));
        }

        @Override
        public void draw(Canvas canvas) {
            if (!FeatureFlags.glassTabBar) return;
            try {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                glass.draw(canvas, b, host, color);
                // Faint bright rim to define the glass edge of the panel.
                int base = color | 0xFF000000;
                if (IgThemeEngine.isActive()) {
                    try { base = IgColorRemapEngine.remap(base) | 0xFF000000; } catch (Throwable ignored) {}
                }
                boolean dark = (0.299f * Color.red(base) + 0.587f * Color.green(base)
                        + 0.114f * Color.blue(base)) / 255f < 0.5f;
                rim.setColor(dark ? 0x2EFFFFFF : 0x1F000000);
                float r = glass.cornerRadius;
                float inset = rim.getStrokeWidth() / 2f;
                canvas.drawRoundRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset,
                        r, r, rim);
            } catch (Throwable ignored) {}
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static void refresh(View bar) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        if (bar.getBackground() instanceof GlassDrawable && bar.isShown()) { bar.invalidate(); return; }
        if (!(bar.getBackground() instanceof InsetDrawable bg) || pillClass == null) return;
        Drawable inner = bg.getDrawable();
        if (inner != null && inner.getClass() == pillClass && bar.isShown()) bar.invalidate();
    }

    /**
     * Some builds/screens (e.g. the Reels tab, and IG 452 on some devices) draw the tab bar as a
     * full-width bar with a plain opaque {@link ColorDrawable} background instead of the floating
     * pill. The pill hook never fires there, so the bar stays solid. Replace that opaque background
     * with a {@link GlassDrawable} that blurs the content behind the bar and tints it — the same
     * glass look, applied to the fixed bar. Re-applied on layout because IG resets the background
     * when the style changes.
     */
    private static void applyFixedGlass(View bar) {
        if (!FeatureFlags.glassTabBar || Build.VERSION.SDK_INT < 31) return;
        Drawable bg = bar.getBackground();
        if (bg == null || bg instanceof GlassDrawable || bg instanceof InsetDrawable) return;
        if (!(bg instanceof android.graphics.drawable.ColorDrawable cd)) return; // only the plain fill
        if (Color.alpha(cd.getColor()) == 0) return; // already transparent, nothing to glass
        try {
            float density = bar.getResources().getDisplayMetrics().density;
            bar.setBackground(new GlassDrawable(bar, cd.getColor(), density));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ fixed glass: " + t);
        }
    }

    /**
     * A translucent, rounded background for the fixed full-width tab bar (the Reels screen, and any
     * build/screen that uses the fixed style). Unlike the floating pill on Home/Search/Profile, the
     * content directly behind this bar is the reel's video surface (a SurfaceView/TextureView), which
     * cannot be captured into a blur RenderNode — a blur there renders nothing and falls back to an
     * opaque fill, i.e. a solid black bar. So instead of blurring, we paint a see-through tint in the
     * bar's own (themed) color as a floating rounded pill, letting the reel show straight through the
     * nav the way glass should. Icons are Instagram's own views drawn on top and stay crisp.
     */
    static final class GlassDrawable extends Drawable {
        private final int color;
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        GlassDrawable(View host, int color, float density) {
            this.color = color;
            this.density = density;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density * 0.75f));
        }

        @Override
        public void draw(Canvas canvas) {
            if (!FeatureFlags.glassTabBar) return;
            try {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                int base = color | 0xFF000000;
                int opaqueColor = base;
                if (IgThemeEngine.isActive()) {
                    try { opaqueColor = IgColorRemapEngine.remap(base) | 0xFF000000; } catch (Throwable ignored) {}
                }
                boolean dark = luminance(opaqueColor) < 0.5f;
                float side = density * 10f;          // inset so it floats like a pill, reel at the edges
                float radius = density * 26f;
                r.set(b.left + side, b.top, b.right - side, b.bottom);
                // See-through tint: the reel shows through, icons stay legible over it.
                int a = Math.round(255 * 0.42f);
                fill.setColor((opaqueColor & 0x00FFFFFF) | (a << 24));
                canvas.drawRoundRect(r, radius, radius, fill);
                // Faint edge to define the glass against the moving video.
                edge.setColor(dark ? 0x2EFFFFFF : 0x1F000000);
                float inset = edge.getStrokeWidth() / 2f;
                r.inset(inset, inset);
                canvas.drawRoundRect(r, radius - inset, radius - inset, edge);
            } catch (Throwable ignored) {}
        }

        private static float luminance(int c) {
            return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Hooks draw() of the drawable class Instagram uses for the floating pill (once). */
    private static synchronized void hookPill(View bar) {
        if (pillHooked) return;
        if (!(bar.getBackground() instanceof InsetDrawable bg)) return; // not floating (yet)
        Drawable inner = bg.getDrawable();
        if (inner == null) return;
        Class<?> c = inner.getClass();
        if (c.getName().startsWith("android.")) return; // only Instagram's own pill drawable
        List<Field> paints = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == Paint.class && !Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                paints.add(f);
            }
        }
        Method draw;
        try {
            draw = c.getDeclaredMethod("draw", Canvas.class);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ pill draw(): " + t);
            pillHooked = true; // don't retry every layout
            return;
        }
        pillPaints = paints;
        pillClass = c;
        pillHooked = true;
        XposedBridge.hookMethod(draw, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                JitGuard.tick(param.method);
                if (!FeatureFlags.glassTabBar) return;
                try {
                    Drawable d = (Drawable) param.thisObject;
                    View host = hostBar(d);
                    if (host == null) return; // the same drawable class used somewhere else
                    Rect b = d.getBounds();
                    if (b.isEmpty()) return;
                    Glass g;
                    synchronized (glassByBar) {
                        g = glassByBar.get(host);
                        if (g == null) {
                            g = new Glass(host.getResources().getDisplayMetrics().density);
                            glassByBar.put(host, g);
                        }
                    }
                    g.draw((Canvas) param.args[0], b, host, fillColor(d));
                    param.setResult(null);
                } catch (Throwable t) {
                    // fall through to Instagram's own drawing
                }
            }
        });
        ModuleLog.line(TAG + "✅ pill drawable " + c.getName() + " (" + paints.size() + " paints)");
    }

    /** The tab bar view a pill drawable belongs to (InsetDrawable callback chain), else null. */
    private static View hostBar(Drawable d) {
        Drawable.Callback cb = d.getCallback();
        if (cb instanceof Drawable wrapper) cb = wrapper.getCallback();
        if (cb instanceof View v && v.getId() == tabBarId && tabBarId != 0) return v;
        return null;
    }

    /** The pill's fill color: Instagram sets it on one Paint (the other one is the shadow). */
    private static int fillColor(Drawable d) {
        int color = Color.BLACK;
        boolean found = false;
        List<Field> paints = pillPaints;
        if (paints != null) {
            for (Field f : paints) {
                try {
                    Paint p = (Paint) f.get(d);
                    if (p == null) continue;
                    int c = p.getColor();
                    if (!found || (c | 0xFF000000) != Color.BLACK) {
                        color = c;
                        found = true;
                    }
                    if ((c | 0xFF000000) != Color.BLACK) break;
                } catch (Throwable ignored) {}
            }
        }
        if (IgThemeEngine.isActive()) {
            try { color = IgColorRemapEngine.remap(color); } catch (Throwable ignored) {}
        }
        return color;
    }

    /** Draws one tab bar's glass pill. Main thread only. */
    private static final class Glass {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Object clipNode, blurNode; // RenderNode (API 31+), kept as Object for older devices
        private boolean drawing;
        float cornerRadius = -1f; // <0 = pill (height/2); >=0 = explicit radius (0 = rectangular)
        float flatAlpha = 0.86f;  // fill alpha when nothing blurs behind (more opaque = readable)

        Glass(float density) {
            this.density = density;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, density * 0.75f));
        }

        void draw(Canvas c, Rect b, View bar, int color) {
            rect.set(b);
            float radius = cornerRadius >= 0f ? cornerRadius : rect.height() / 2f;
            boolean dark = luminance(color) < 0.5f;
            boolean blurred = false;
            if (Build.VERSION.SDK_INT >= 31 && c.isHardwareAccelerated() && !drawing) {
                drawing = true;
                try {
                    blurred = drawBlur(c, b, bar, color | 0xFF000000, radius);
                } catch (Throwable t) {
                    blurred = false;
                } finally {
                    drawing = false;
                }
            }
            // Tint: more opaque without blur so the icons stay readable over busy content.
            float alpha = blurred ? (dark ? 0.55f : 0.68f) : flatAlpha;
            int a = Math.round(Color.alpha(color) * alpha);
            fill.setColor((color & 0x00FFFFFF) | (a << 24));
            c.drawRoundRect(rect, radius, radius, fill);
            edge.setColor(dark ? 0x26FFFFFF : 0x14000000);
            float inset = edge.getStrokeWidth() / 2f;
            rect.inset(inset, inset);
            c.drawRoundRect(rect, radius - inset, radius - inset, edge);
        }

        private final int[] locBar = new int[2];
        private final int[] locV = new int[2];

        /**
         * Blurs whatever is painted behind the bar into the glass. Rather than only the bar's
         * immediate earlier siblings, it walks up the ancestor chain and, at each level, draws the
         * children painted before the bar's branch — positioned by absolute window coordinates. That
         * captures the real backdrop even when the content (e.g. the feed) and the bar live in
         * separate branches that only meet at a common ancestor (the top action bar case), while
         * still never drawing the bar itself or any chrome stacked on top of it.
         */
        @android.annotation.TargetApi(31)
        private boolean drawBlur(Canvas c, Rect b, View bar, int opaque, float radius) {
            int w = b.width(), h = b.height();
            if (w <= 0 || h <= 0) return false;
            bar.getLocationInWindow(locBar);
            float barX = locBar[0] + b.left;
            float barY = locBar[1] + b.top;

            if (clipNode == null) {
                RenderNode clip = new RenderNode("ie_glass_clip");
                RenderNode blur = new RenderNode("ie_glass_blur");
                float r = 24f * density;
                blur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                clipNode = clip;
                blurNode = blur;
            }
            RenderNode clip = (RenderNode) clipNode;
            RenderNode blur = (RenderNode) blurNode;

            blur.setPosition(0, 0, w, h);
            RecordingCanvas rc = blur.beginRecording(w, h);
            boolean any = false;
            try {
                rc.drawColor(opaque);
                View branch = bar;
                int levels = 0;
                while (branch.getParent() instanceof ViewGroup parent && levels < 12) {
                    int idx = parent.indexOfChild(branch);
                    for (int i = 0; i < idx; i++) {
                        View v = parent.getChildAt(i);
                        if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) continue;
                        if (v.getId() == tabBarShadowId && tabBarShadowId != 0) continue;
                        int vw = v.getWidth(), vh = v.getHeight();
                        if (vw <= 0 || vh <= 0) continue;
                        v.getLocationInWindow(locV);
                        float dx = locV[0] - barX, dy = locV[1] - barY;
                        if (dx >= w || dy >= h || dx + vw <= 0 || dy + vh <= 0) continue;
                        int save = rc.save();
                        rc.translate(dx, dy);
                        v.draw(rc);
                        rc.restoreToCount(save);
                        any = true;
                    }
                    branch = parent;
                    levels++;
                }
            } finally {
                blur.endRecording();
            }
            if (!any) return false;

            clip.setPosition(b.left, b.top, b.right, b.bottom);
            Outline o = new Outline();
            o.setRoundRect(0, 0, w, h, radius);
            clip.setOutline(o);
            clip.setClipToOutline(true);
            RecordingCanvas cc = clip.beginRecording(w, h);
            try {
                cc.drawRenderNode(blur);
            } finally {
                clip.endRecording();
            }
            c.drawRenderNode(clip);
            return true;
        }

        private static float luminance(int color) {
            return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
        }
    }
}
