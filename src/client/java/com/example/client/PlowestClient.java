package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Hand;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.client.render.Camera;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Plowest Client (Minecraft 1.21.4, Fabric, Yarn)
 * Tek kisilik dunya / ozel sunucu icin cok modullu client modu.
 *
 * Menu: Sag Shift (Kontroller menusunden degistirilebilir).
 * Menude: modul satirina dokun = ayarlari ac/kapat, sagdaki dugme = modulu ac/kapat,
 * baslik ve panelleri surukleyerek tasiyabilirsin.
 */
public class PlowestClient implements ClientModInitializer {

    // ================================================================== ortak durum
    private static final List<Module> MODULES = new ArrayList<>();
    private static final String[] CATEGORIES = {"Savaş", "Elytra", "Görsel", "Hareket"};
    private static final float[] panelX = {20, 150, 280, 410};
    private static final float[] panelY = {50, 50, 50, 50};
    private static float hudX = 5, hudY = 5;
    private static KeyBinding menuKey;

    private static final String[] FILTERS = {"Hepsi", "Oyuncular", "Düşman moblar", "Hayvanlar"};

    // ================================================================== tema
    private static final String[] THEME_NAMES =
            {"Turuncu", "Mavi", "Kırmızı", "Mor", "Yeşil", "Gökkuşağı"};
    private static final int[] THEME_COLORS =
            {0xFFAA00, 0x55AAFF, 0xFF5555, 0xAA55FF, 0x55FF55, 0};
    private static int theme = 0;

    private static int themeColor(int offset) {
        if (theme == THEME_COLORS.length - 1) {
            float h = ((System.currentTimeMillis() / 20 + offset * 12L) % 360) / 360f;
            return 0xFF000000 | MathHelper.hsvToRgb(h, 0.7f, 1f);
        }
        return 0xFF000000 | THEME_COLORS[theme];
    }

    private static int hpColor(float frac) {
        frac = MathHelper.clamp(frac, 0f, 1f);
        int r = (int) (255 * (1 - frac));
        int g = (int) (255 * frac);
        return 0xFF000000 | (r << 16) | (g << 8) | 60;
    }

    // ================================================================== ayar tipleri
    private abstract static class Setting {
        final String name;
        Setting(String name) { this.name = name; }
        abstract String text();
        abstract void click(int button);
        abstract String save();
        abstract void load(String s);
    }

    private static class BoolSetting extends Setting {
        boolean value;
        BoolSetting(String name, boolean value) { super(name); this.value = value; }
        @Override String text() { return value ? "AÇIK" : "KAPALI"; }
        @Override void click(int button) { value = !value; }
        @Override String save() { return Boolean.toString(value); }
        @Override void load(String s) { value = Boolean.parseBoolean(s); }
    }

    private static class ModeSetting extends Setting {
        final String[] modes;
        int index;
        ModeSetting(String name, String[] modes, int index) {
            super(name); this.modes = modes; this.index = index;
        }
        @Override String text() { return modes[index]; }
        @Override void click(int button) {
            index = (index + (button == 1 ? -1 : 1) + modes.length) % modes.length;
        }
        @Override String save() { return Integer.toString(index); }
        @Override void load(String s) {
            try {
                int i = Integer.parseInt(s);
                if (i >= 0 && i < modes.length) index = i;
            } catch (Exception ignored) {}
        }
    }

    private static class NumSetting extends Setting {
        final double min, max, step;
        double value;
        NumSetting(String name, double min, double max, double step, double value) {
            super(name); this.min = min; this.max = max; this.step = step; this.value = value;
        }
        double get() { return value; }
        int geti() { return (int) Math.round(value); }
        @Override String text() {
            if (step >= 1) return Integer.toString(geti());
            if (step < 0.1) return String.format(Locale.ROOT, "%.2f", value);
            return String.format(Locale.ROOT, "%.1f", value);
        }
        @Override void click(int button) {
            value += (button == 1 ? -step : step);
            if (value > max + 1e-9) value = min;
            if (value < min - 1e-9) value = max;
            value = Math.round(value / step) * step;
        }
        @Override String save() { return Double.toString(value); }
        @Override void load(String s) {
            try { value = MathHelper.clamp(Double.parseDouble(s), min, max); } catch (Exception ignored) {}
        }
    }

    // ================================================================== modul tabani
    private abstract static class Module {
        final String name;
        final String category;
        boolean enabled = false;
        boolean expanded = false;
        int key = -1;          // orta tikla atanan tus (-1 = yok)
        boolean keyWas = false;
        final List<Setting> settings = new ArrayList<>();

        Module(String name, String category) { this.name = name; this.category = category; }

        <T extends Setting> T add(T s) { settings.add(s); return s; }

        void toggle(MinecraftClient mc) {
            enabled = !enabled;
            if (enabled) onEnable(mc); else onDisable(mc);
        }

        String info() { return ""; }
        void onEnable(MinecraftClient mc) {}
        void onDisable(MinecraftClient mc) {}
        abstract void onTick(MinecraftClient mc);
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {}
    }

    // ================================================================== yardimcilar
    private static boolean matchesFilter(int idx, Entity e) {
        return switch (idx) {
            case 1 -> e instanceof PlayerEntity;
            case 2 -> e instanceof HostileEntity;
            case 3 -> e instanceof AnimalEntity;
            default -> e instanceof LivingEntity;
        };
    }

    private static float[] rotationTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, hd));
        return new float[]{yaw, pitch};
    }

    private static Vec3d aimPoint(Entity e) {
        Box b = e.getBoundingBox();
        return new Vec3d((b.minX + b.maxX) / 2, b.minY + (b.maxY - b.minY) * 0.7, (b.minZ + b.maxZ) / 2);
    }

    private static final class Cand {
        final LivingEntity e;
        final double dist;
        final float angle;
        Cand(LivingEntity e, double dist, float angle) { this.e = e; this.dist = dist; this.angle = angle; }
    }

    /** priority: 0 = en yakin, 1 = en az can, 2 = gorus acisina en yakin */
    private static List<LivingEntity> findTargets(MinecraftClient mc, int filter, double range,
                                                  boolean walls, double fov, int priority) {
        List<LivingEntity> out = new ArrayList<>();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return out;
        Vec3d eye = p.getEyePos();
        List<Cand> cands = new ArrayList<>();

        for (Entity e : mc.world.getOtherEntities(p, p.getBoundingBox().expand(range + 1.0))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || e instanceof ArmorStandEntity
                    || le.isSpectator() || !matchesFilter(filter, e)) continue;

            Box b = e.getBoundingBox();
            Vec3d closest = new Vec3d(
                    MathHelper.clamp(eye.x, b.minX, b.maxX),
                    MathHelper.clamp(eye.y, b.minY, b.maxY),
                    MathHelper.clamp(eye.z, b.minZ, b.maxZ));
            double dist = eye.distanceTo(closest);
            if (dist > range) continue;
            if (!walls && !p.canSee(e)) continue;

            float[] rot = rotationTo(eye, aimPoint(e));
            float yawDiff = MathHelper.wrapDegrees(rot[0] - p.getYaw());
            float pitchDiff = rot[1] - p.getPitch();
            if (fov < 360 && Math.abs(yawDiff) > fov / 2.0) continue;

            cands.add(new Cand(le, dist, (float) Math.hypot(yawDiff, pitchDiff)));
        }

        Comparator<Cand> cmp = switch (priority) {
            case 1 -> Comparator.comparingDouble(c -> c.e.getHealth());
            case 2 -> Comparator.comparingDouble(c -> c.angle);
            default -> Comparator.comparingDouble(c -> c.dist);
        };
        cands.sort(cmp);
        for (Cand c : cands) out.add(c.e);
        return out;
    }

    // ---- 3B -> ekran projeksiyonu (ESP ve isim etiketi icin)
    private static final class Proj {
        final Vec3d cam;
        final double fx, fy, fz, rx, ry, rz, ux, uy, uz, tanHalf, aspect;
        final int w, h;

        Proj(MinecraftClient mc) {
            Camera c = mc.gameRenderer.getCamera();
            cam = c.getPos();
            double yaw = Math.toRadians(c.getYaw());
            double pitch = Math.toRadians(c.getPitch());
            fx = -Math.sin(yaw) * Math.cos(pitch);
            fy = -Math.sin(pitch);
            fz = Math.cos(yaw) * Math.cos(pitch);
            rx = -Math.cos(yaw);
            ry = 0;
            rz = -Math.sin(yaw);
            ux = ry * fz - rz * fy;
            uy = rz * fx - rx * fz;
            uz = rx * fy - ry * fx;
            tanHalf = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
            w = mc.getWindow().getScaledWidth();
            h = mc.getWindow().getScaledHeight();
            aspect = (double) w / (double) h;
        }

        double[] to(double x, double y, double z) {
            double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
            double zc = dx * fx + dy * fy + dz * fz;
            if (zc < 0.05) return null;
            double xc = dx * rx + dy * ry + dz * rz;
            double yc = dx * ux + dy * uy + dz * uz;
            double sx = w / 2.0 + (xc / zc) / (tanHalf * aspect) * (w / 2.0);
            double sy = h / 2.0 - (yc / zc) / tanHalf * (h / 2.0);
            return new double[]{sx, sy, zc};
        }
    }

    private static double[] screenBox(Proj pr, Entity e, float td) {
        Vec3d pos = e.getLerpedPos(td);
        double hw = e.getWidth() / 2.0, hh = e.getHeight();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double x = pos.x + ((i & 1) == 0 ? -hw : hw);
            double y = pos.y + ((i & 2) == 0 ? 0 : hh);
            double z = pos.z + ((i & 4) == 0 ? -hw : hw);
            double[] s = pr.to(x, y, z);
            if (s == null) return null;
            minX = Math.min(minX, s[0]); maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]); maxY = Math.max(maxY, s[1]);
        }
        if (maxX - minX > pr.w * 2 || maxY - minY > pr.h * 2) return null;
        return new double[]{minX, minY, maxX, maxY};
    }

    private static void outline(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1, y1, x2, y1 + 1, color);
        ctx.fill(x1, y2 - 1, x2, y2, color);
        ctx.fill(x1, y1, x1 + 1, y2, color);
        ctx.fill(x2 - 1, y1, x2, y2, color);
    }

    // ================================================================== yeni yardimcilar
    // ---- esya / envanter (esya kimligi yazi olarak okunur: surumden bagimsiz)
    private static String itemId(ItemStack s) {
        if (s == null || s.isEmpty()) return "";
        return Registries.ITEM.getId(s.getItem()).getPath();
    }

    private static int findHotbar(MinecraftClient mc, Predicate<String> test) {
        for (int i = 0; i < 9; i++) {
            if (test.test(itemId(mc.player.getInventory().getStack(i)))) return i;
        }
        return -1;
    }

    private static void selectSlot(MinecraftClient mc, int slot) {
        if (slot >= 0 && slot < 9) mc.player.getInventory().selectedSlot = slot;
    }

    private static boolean isSword(String id) { return id.endsWith("_sword"); }
    private static boolean isAxe(String id) { return id.endsWith("_axe"); }

    /** 1.16.5 PvP: kilic ~%95 sarjda, balta/diger silahlar tam sarjda vurur */
    private static float cooldownNeeded(ClientPlayerEntity p) {
        return isSword(itemId(p.getMainHandStack())) ? 0.95f : 1.0f;
    }

    // ---- fisek (elytra hizlandirma)
    private static int fwBoost = 0;

    /** Hotbar'daki fiseği kullanir. swapToSword: sonra kilica gec, degilse eski slota don. */
    private static boolean fireFirework(MinecraftClient mc, boolean swapToSword) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) return false;
        int fw = findHotbar(mc, id -> id.equals("firework_rocket"));
        if (fw < 0) return false;
        int prev = p.getInventory().selectedSlot;
        selectSlot(mc, fw);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        int back = prev;
        if (swapToSword) {
            int sw = findHotbar(mc, id -> id.endsWith("_sword"));
            if (sw >= 0) back = sw;
        }
        selectSlot(mc, back);
        fwBoost = Math.max(fwBoost, 40);
        return true;
    }

    // ---- kritik vurus
    private static Criticals CRIT;
    private static int critWait = 0;

    /** true = simdi vurmak serbest. Kritik modulu aciksa dusus anini bekler. */
    private static boolean critOk(MinecraftClient mc) {
        if (CRIT == null || !CRIT.enabled) return true;
        ClientPlayerEntity p = mc.player;
        if (p.isGliding()) return true;
        Vec3d v = p.getVelocity();
        boolean ok;
        if (p.isOnGround()) {
            if (CRIT.mode.index == 0) {
                p.setVelocity(v.x, 0.42, v.z);   // zipla
                ok = false;
            } else {
                ok = true;                                          // yerdeyse normal vur
            }
        } else {
            ok = v.y < -0.05;                              // dusuyorsak kritik
        }
        if (ok) { critWait = 0; return true; }
        if (++critWait > 14) { critWait = 0; return true; }        // takilip kalma
        return false;
    }

    // ---- hedef takibi (hareket tahmini icin)
    private static final Map<Integer, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<Integer, Vec3d> VEL = new HashMap<>();
    private static int trackTicks = 0;

    private static void trackEntities(MinecraftClient mc) {
        if (++trackTicks > 1200) { trackTicks = 0; LAST_POS.clear(); VEL.clear(); }
        for (Entity e : mc.world.getEntities()) {
            Vec3d now = e.getLerpedPos(1.0f);
            Vec3d last = LAST_POS.put(e.getId(), now);
            if (last != null) {
                VEL.put(e.getId(), new Vec3d(now.x - last.x,
                        now.y - last.y, now.z - last.z));
            }
        }
    }

    private static Vec3d predicted(Entity e, double ticks) {
        Vec3d base = aimPoint(e);
        Vec3d v = VEL.get(e.getId());
        if (v == null) return base;
        return new Vec3d(base.x + v.x * ticks,
                base.y + v.y * ticks * 0.5,
                base.z + v.z * ticks);
    }

    // ---- nisan / vurus dogrulama
    private static void aimAt(ClientPlayerEntity p, float yaw, float pitch, float f) {
        float yd = MathHelper.wrapDegrees(yaw - p.getYaw());
        float pd = pitch - p.getPitch();
        p.setYaw(p.getYaw() + yd * f);
        p.setPitch(MathHelper.clamp(p.getPitch() + pd * f, -90f, 90f));
    }

    /** Gozden hedef kutusuna kadar olan en yakin mesafe */
    private static double eyeDist(ClientPlayerEntity p, Entity e) {
        Vec3d eye = p.getEyePos();
        Box b = e.getBoundingBox();
        double cx = MathHelper.clamp(eye.x, b.minX, b.maxX);
        double cy = MathHelper.clamp(eye.y, b.minY, b.maxY);
        double cz = MathHelper.clamp(eye.z, b.minZ, b.maxZ);
        double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Baktigimiz yon gercekten hedefin kutusuna `reach` icinde isabet ediyor mu? (ghost hit onleme) */
    private static boolean lookingAt(ClientPlayerEntity p, Entity e, double reach, double expand) {
        Vec3d eye = p.getEyePos();
        double yaw = Math.toRadians(p.getYaw()), pitch = Math.toRadians(p.getPitch());
        double[] d = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] o = {eye.x, eye.y, eye.z};
        Box b = e.getBoundingBox().expand(expand);
        double[] lo = {b.minX, b.minY, b.minZ};
        double[] hi = {b.maxX, b.maxY, b.maxZ};
        double tmin = 0, tmax = reach;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < lo[i] || o[i] > hi[i]) return false;
            } else {
                double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return false;
            }
        }
        return true;
    }

    // ---- cizim yardimcilari
    private static void line(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1, dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0 || steps > 4000) return;
        for (int i = 0; i <= steps; i++) {
            int x = x1 + dx * i / steps, y = y1 + dy * i / steps;
            ctx.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Harf harf dalgalanan, parlayan yazi */
    private static void drawWave(DrawContext ctx, TextRenderer tr, String s, int x, int y, float scale) {
        long t = System.currentTimeMillis();
        ctx.getMatrices().push();
        ctx.getMatrices().scale(scale, scale, 1f);
        float cx = x / scale;
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            float wave = (float) Math.sin(t / 180.0 + i * 0.55) * 1.6f;
            float glow = (float) (0.5 + 0.5 * Math.sin(t / 250.0 - i * 0.5));
            int color = blend(themeColor(i * 8), 0xFFFFFFFF, glow * 0.65f);
            ctx.drawTextWithShadow(tr, ch, (int) cx, (int) (y / scale + wave), color);
            cx += tr.getWidth(ch);
        }
        ctx.getMatrices().pop();
    }

    private static final String[] QUOTES = {
            "Her vuruş bir mesaj.",
            "Önde ol, geride kalma.",
            "Hız senin, zafer senin.",
            "Gökyüzü bizim arenamız.",
            "Plowest Baba izliyor."
    };
    private static final int WM_W = 170, WM_H = 32;

    /** Sol ustteki animasyonlu baslik kutusu */
    private static void drawWatermark(DrawContext ctx, MinecraftClient mc, int x, int y) {
        TextRenderer tr = mc.textRenderer;
        long t = System.currentTimeMillis();
        ctx.fill(x, y, x + WM_W, y + WM_H, 0x99000000);
        for (int i = 0; i < WM_W; i += 2) {
            float f = (float) ((Math.sin(t / 400.0 - i * 0.05) + 1) / 2);
            ctx.fill(x + i, y, x + i + 2, y + 2, blend(themeColor(i / 2), 0xFFFFFFFF, f * 0.6f));
        }
        ctx.fill(x, y + 2, x + 2, y + WM_H, themeColor(0));
        drawWave(ctx, tr, "Plowest Client", x + 8, y + 6, 1.5f);

        String q = QUOTES[(int) ((t / 4500) % QUOTES.length)];
        long ph = t % 4500;
        int n = (int) Math.min(q.length(), ph / 70);
        String shown = q.substring(0, n);
        if (n < q.length() && (ph / 150) % 2 == 0) shown += "_";
        int alpha = ph > 4000 ? (int) Math.max(30, 255 - (ph - 4000) * 255 / 500) : 255;
        ctx.drawTextWithShadow(tr, shown, x + 8, y + 21, (alpha << 24) | 0xCCCCCC);
    }

    private static String keyName(int key) {
        String n = GLFW.glfwGetKeyName(key, 0);
        if (n != null) return n.toUpperCase(Locale.ROOT);
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        return switch (key) {
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_INSERT -> "INS";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            default -> "K" + key;
        };
    }

    // ---- giris (oturum) bilgileri: duz yazi degil, SHA-256 ozeti saklanir
    private static final String USER_HASH = "8f678e4bf9296a7ddbac8f499c787de6734e894ba800ebaf5c987d0754c03dbd";
    private static final String PASS_HASH = "a4f28eda2f09e5a3ba33936c95f488fe275b5062aedd66e5d73473d9df425fe3";
    private static boolean loggedIn = false;
    private static boolean loginShown = false;

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================== SAVAŞ
    // ---------------------------------------------------------------- Tetik Botu
    private static class TriggerBot extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 0));
        final ModeSetting weapon = add(new ModeSetting("Silah (1.16.5)",
                new String[]{"Otomatik", "Sadece kılıç", "Sadece balta"}, 0));
        final NumSetting delay = add(new NumSetting("Gecikme (tick)", 0, 20, 1, 0));
        int since = 100;

        TriggerBot() { super("Tetik Botu", "Savaş"); }

        @Override String info() { return target.text() + ", " + weapon.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (since < 1000) since++;
            if (mc.currentScreen != null || mc.interactionManager == null) return;
            if (!(mc.crosshairTarget instanceof EntityHitResult hit)) return;

            ClientPlayerEntity p = mc.player;
            Entity e = hit.getEntity();
            String held = itemId(p.getMainHandStack());
            boolean weaponOk = switch (weapon.index) {
                case 1 -> isSword(held);
                case 2 -> isAxe(held);
                default -> true;
            };
            if (!weaponOk || !(e instanceof LivingEntity) || !e.isAlive() || e instanceof ArmorStandEntity
                    || !matchesFilter(target.index, e)) return;
            // 1.16.5 PvP: kilic ~%95, diger silahlar %100 sarjda
            if (p.getAttackCooldownProgress(0.5f) < cooldownNeeded(p) || since < delay.geti()) return;
            if (!critOk(mc)) return;

            mc.interactionManager.attackEntity(p, e);
            p.swingHand(Hand.MAIN_HAND);
            since = 0;
        }
    }

    // ---------------------------------------------------------------- Aura (KillAura)
    private static class Aura extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 0));
        final ModeSetting priority = add(new ModeSetting("Öncelik",
                new String[]{"En yakın", "En az can", "Görüş açısı"}, 0));
        final NumSetting range = add(new NumSetting("Menzil", 3.0, 4.0, 0.5, 3.5));
        final NumSetting fov = add(new NumSetting("Görüş açısı", 60, 360, 30, 360));
        final ModeSetting rotate = add(new ModeSetting("Dönme",
                new String[]{"Yok", "Yumuşak", "Anında"}, 1));
        final BoolSetting walls = add(new BoolSetting("Duvar arkası", false));
        final BoolSetting noGhost = add(new BoolSetting("Ghost hit koruması", true));
        final NumSetting multi = add(new NumSetting("Çoklu hedef", 1, 3, 1, 1));
        final NumSetting delay = add(new NumSetting("Gecikme (tick)", 0, 10, 1, 0));
        int since = 100;

        Aura() { super("Aura", "Savaş"); }

        @Override String info() { return target.text() + ", " + range.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (since < 1000) since++;
            if (mc.currentScreen != null || mc.interactionManager == null) return;
            ClientPlayerEntity p = mc.player;

            List<LivingEntity> list = findTargets(mc, target.index, range.get(),
                    walls.value, fov.get(), priority.index);
            if (list.isEmpty()) return;
            LivingEntity primary = list.get(0);

            float[] rot = rotationTo(p.getEyePos(), aimPoint(primary));
            float yawDiff = MathHelper.wrapDegrees(rot[0] - p.getYaw());
            float pitchDiff = rot[1] - p.getPitch();
            boolean ready = true;

            if (rotate.index == 1) {
                p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff * 0.4f, -30f, 30f));
                p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff * 0.4f, -20f, 20f), -90f, 90f));
                float ny = MathHelper.wrapDegrees(rot[0] - p.getYaw());
                float np = rot[1] - p.getPitch();
                ready = Math.hypot(ny, np) < 25.0;
            } else if (rotate.index == 2) {
                p.setYaw(rot[0]);
                p.setPitch(MathHelper.clamp(rot[1], -90f, 90f));
            }

            if (!ready) return;
            if (p.getAttackCooldownProgress(0.5f) < cooldownNeeded(p)) return;
            if (since < delay.geti()) return;
            if (noGhost.value && rotate.index != 0 && !lookingAt(p, primary, range.get(), 0.05)) return;
            if (!critOk(mc)) return;

            int n = Math.min(multi.geti(), list.size());
            for (int i = 0; i < n; i++) mc.interactionManager.attackEntity(p, list.get(i));
            p.swingHand(Hand.MAIN_HAND);
            since = 0;
        }
    }

    // ---------------------------------------------------------------- Nişan Yardımı
    private static class AimAssist extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 0));
        final NumSetting range = add(new NumSetting("Menzil", 3.0, 8.0, 0.5, 6.0));
        final NumSetting fov = add(new NumSetting("Görüş açısı", 30, 360, 30, 360));
        final ModeSetting speed = add(new ModeSetting("Hız",
                new String[]{"Yavaş", "Orta", "Hızlı", "Çok hızlı", "Anında"}, 3));
        final ModeSetting point = add(new ModeSetting("Nişan noktası",
                new String[]{"Kafa", "Göğüs", "Bel"}, 1));
        final BoolSetting lock = add(new BoolSetting("Hedefe kilitlen", true));
        final BoolSetting predict = add(new BoolSetting("Hareket tahmini", true));
        final BoolSetting onlyClick = add(new BoolSetting("Sadece vururken", false));
        int lockId = -1;

        AimAssist() { super("Nişan Yardımı", "Savaş"); }

        @Override String info() { return target.text() + ", " + speed.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (mc.currentScreen != null) return;
            if (onlyClick.value && !mc.options.attackKey.isPressed()) return;
            ClientPlayerEntity p = mc.player;

            LivingEntity t = null;
            if (lock.value && lockId >= 0) {
                Entity e = mc.world.getEntityById(lockId);
                if (e instanceof LivingEntity le && le.isAlive() && matchesFilter(target.index, e)
                        && p.distanceTo(e) <= range.get() * 1.15 && p.canSee(e)) {
                    t = le;
                }
            }
            if (t == null) {
                List<LivingEntity> list = findTargets(mc, target.index, range.get(), false, fov.get(), 2);
                if (list.isEmpty()) { lockId = -1; return; }
                t = list.get(0);
            }
            lockId = t.getId();

            double frac = new double[]{0.92, 0.7, 0.5}[point.index];
            Box b = t.getBoundingBox();
            double ax = (b.minX + b.maxX) / 2;
            double ay = b.minY + (b.maxY - b.minY) * frac;
            double az = (b.minZ + b.maxZ) / 2;
            if (predict.value) {
                Vec3d v = VEL.get(t.getId());
                if (v != null) { ax += v.x * 2; az += v.z * 2; }
            }

            float[] rot = rotationTo(p.getEyePos(), new Vec3d(ax, ay, az));
            float f = new float[]{0.15f, 0.3f, 0.55f, 0.8f, 1.0f}[speed.index];
            aimAt(p, rot[0], rot[1], f);
        }
    }

    // ---------------------------------------------------------------- Kritik
    private static class Criticals extends Module {
        final ModeSetting mode = add(new ModeSetting("Mod",
                new String[]{"Zıpla ve bekle", "Sadece havada"}, 0));

        Criticals() { super("Kritik", "Savaş"); }

        @Override String info() { return mode.text(); }
        @Override void onTick(MinecraftClient mc) {}
    }

    // ---------------------------------------------------------------- Oto Totem
    private static class AutoTotem extends Module {
        final NumSetting hp = add(new NumSetting("Can eşiği (20 = hep)", 4, 20, 1, 20));
        final NumSetting delay = add(new NumSetting("Gecikme (tick)", 0, 10, 1, 1));
        int since = 100;

        AutoTotem() { super("Oto Totem", "Savaş"); }

        @Override
        void onTick(MinecraftClient mc) {
            if (since < 1000) since++;
            if (mc.currentScreen != null || mc.interactionManager == null) return;
            ClientPlayerEntity p = mc.player;
            if (itemId(p.getOffHandStack()).equals("totem_of_undying")) return;
            if (p.getHealth() + p.getAbsorptionAmount() > hp.get()) return;
            if (since < delay.geti()) return;

            for (int i = 0; i < 36; i++) {
                if (!itemId(p.getInventory().getStack(i)).equals("totem_of_undying")) continue;
                int slot = i < 9 ? 36 + i : i;     // envanter indeksi -> ekran slot numarasi
                // SWAP (ucuncu eylem) + dugme 40 = sol el (offhand) ile degistir
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
                since = 0;
                return;
            }
        }
    }

    // ================================================================== ELYTRA
    // ---------------------------------------------------------------- Elytra Hedef
    private static class ElytraTarget extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 1));
        final NumSetting range = add(new NumSetting("Menzil", 20, 120, 10, 70));
        final NumSetting reach = add(new NumSetting("Vuruş menzili", 2.5, 3.5, 0.1, 3.0));
        final BoolSetting front = add(new BoolSetting("Önüne geç", true));
        final NumSetting turn = add(new NumSetting("Dönüş hızı", 0.2, 1.0, 0.1, 0.7));
        final BoolSetting fw = add(new BoolSetting("Oto fişek", true));
        final NumSetting fwDelay = add(new NumSetting("Fişek aralığı (tick)", 10, 60, 5, 25));
        final NumSetting minSpeed = add(new NumSetting("Min. hız", 0.5, 2.0, 0.1, 1.0));
        final BoolSetting swap = add(new BoolSetting("Swap (fişek sonrası kılıç)", true));
        final BoolSetting noGhost = add(new BoolSetting("Ghost hit koruması", true));
        int fwCd = 0;

        ElytraTarget() { super("Elytra Hedef", "Elytra"); }

        @Override String info() { return target.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (fwCd > 0) fwCd--;
            ClientPlayerEntity p = mc.player;
            if (!p.isGliding() || mc.currentScreen != null || mc.interactionManager == null) return;

            List<LivingEntity> list = findTargets(mc, target.index, range.get(), true, 360, 0);
            if (list.isEmpty()) return;
            LivingEntity t = list.get(0);

            Vec3d v = p.getVelocity();
            double speed = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            double dist = p.distanceTo(t);

            // hedefin gidecegi yere nisan al; uzaktaysak hedefin onune gec
            Vec3d pt = predicted(t, Math.min(dist / Math.max(speed, 0.6), 8.0));
            Vec3d tv = VEL.get(t.getId());
            if (front.value && dist > 7 && tv != null) {
                double tl = Math.hypot(tv.x, tv.z);
                if (tl > 0.02) {
                    pt = new Vec3d(pt.x + tv.x / tl * 3.0, pt.y,
                            pt.z + tv.z / tl * 3.0);
                }
            }
            float[] rot = rotationTo(p.getEyePos(), pt);
            aimAt(p, rot[0], rot[1], (float) turn.get());

            if (fw.value && fwCd == 0 && speed < minSpeed.get() && dist > 4) {
                if (fireFirework(mc, swap.value)) fwCd = fwDelay.geti();
            }

            // vurus: menzilde + sarj tamam + gercekten bakiyoruz (ghost hit yok)
            if (eyeDist(p, t) <= reach.get() && p.getAttackCooldownProgress(0.5f) >= cooldownNeeded(p)
                    && (!noGhost.value || lookingAt(p, t, reach.get(), 0.0))) {
                if (swap.value) {
                    int sw = findHotbar(mc, id -> id.endsWith("_sword"));
                    if (sw >= 0) selectSlot(mc, sw);
                }
                mc.interactionManager.attackEntity(p, t);
                p.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    // ---------------------------------------------------------------- Süper Fişek
    private static class SuperFirework extends Module {
        final NumSetting power = add(new NumSetting("Güç", 1.0, 4.0, 0.5, 2.0));
        final NumSetting duration = add(new NumSetting("Süre (tick)", 20, 100, 10, 50));
        boolean useWas = false;

        SuperFirework() { super("Süper Fişek", "Elytra"); }

        @Override String info() { return "x" + power.text(); }

        @Override void onEnable(MinecraftClient mc) { fwBoost = 0; useWas = false; }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            boolean usingFw = mc.currentScreen == null && mc.options.useKey.isPressed()
                    && itemId(p.getMainHandStack()).equals("firework_rocket");
            if (usingFw && !useWas && p.isGliding()) fwBoost = duration.geti();
            useWas = usingFw;

            if (fwBoost <= 0) return;
            fwBoost--;
            if (!p.isGliding()) return;

            Vec3d v = p.getVelocity();
            double yaw = Math.toRadians(p.getYaw()), pitch = Math.toRadians(p.getPitch());
            double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
            double tgt = 1.5 * power.get();
            double k = 0.3;
            p.setVelocity(v.x + (dx * tgt - v.x) * k,
                    v.y + (dy * tgt - v.y) * k,
                    v.z + (dz * tgt - v.z) * k);
        }
    }

    // ---------------------------------------------------------------- Elytra Yardımcısı
    private static class ElytraHelper extends Module {
        final BoolSetting autoEquip = add(new BoolSetting("Elytra otomatik tak", true));
        final BoolSetting autoFw = add(new BoolSetting("Oto fişek", true));
        final NumSetting minSpeed = add(new NumSetting("Min. hız", 0.5, 2.0, 0.1, 0.8));
        final NumSetting interval = add(new NumSetting("Aralık (tick)", 10, 80, 5, 30));
        int fwCd = 0, eqCd = 0;

        ElytraHelper() { super("Elytra Yardımcısı", "Elytra"); }

        @Override
        void onTick(MinecraftClient mc) {
            if (fwCd > 0) fwCd--;
            if (eqCd > 0) eqCd--;
            if (mc.currentScreen != null || mc.interactionManager == null) return;
            ClientPlayerEntity p = mc.player;
            Vec3d v = p.getVelocity();

            // havada dusuyorsak ve gogusluk elytra degilse: hotbar'daki elytrayi tak
            if (autoEquip.value && eqCd == 0 && !p.isOnGround() && !p.isGliding() && v.y < -0.6
                    && !itemId(p.getInventory().getStack(38)).equals("elytra")) {
                int h = findHotbar(mc, id -> id.equals("elytra"));
                if (h >= 0) {
                    // zirh slotu (6) ile hotbar slotunu degistir
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, 6, h, SlotActionType.SWAP, p);
                    eqCd = 20;
                }
            }

            double speed = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            if (autoFw.value && p.isGliding() && fwCd == 0 && speed < minSpeed.get()) {
                if (fireFirework(mc, false)) fwCd = interval.geti();
            }
        }
    }

    // ---------------------------------------------------------------- Elytra Vuruş (fişek > sağa > geri gel > vur)
    private static class ElytraStrike extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 1));
        final NumSetting range = add(new NumSetting("Menzil", 20, 100, 10, 60));
        final NumSetting reach = add(new NumSetting("Vuruş menzili", 2.5, 3.5, 0.1, 3.0));
        final NumSetting angle = add(new NumSetting("Yan açı (derece)", 30, 90, 15, 60));
        final NumSetting sideTicks = add(new NumSetting("Yan süre (tick)", 4, 20, 2, 8));
        final BoolSetting swap = add(new BoolSetting("Swap (kılıca geç)", true));
        final BoolSetting noGhost = add(new BoolSetting("Ghost hit koruması", true));
        int state = 0, timer = 0, fwCd = 0;

        ElytraStrike() { super("Elytra Vuruş", "Elytra"); }

        @Override String info() { return new String[]{"Fişek", "Yan", "Vuruş", "Bekle"}[state]; }
        @Override void onEnable(MinecraftClient mc) { state = 0; timer = 0; fwCd = 0; }

        @Override
        void onTick(MinecraftClient mc) {
            if (fwCd > 0) fwCd--;
            ClientPlayerEntity p = mc.player;
            if (!p.isGliding() || mc.currentScreen != null || mc.interactionManager == null) { state = 0; return; }

            List<LivingEntity> list = findTargets(mc, target.index, range.get(), true, 360, 0);
            if (list.isEmpty()) { state = 0; return; }
            LivingEntity t = list.get(0);
            Vec3d eye = p.getEyePos();
            Vec3d v = p.getVelocity();
            double speed = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);

            switch (state) {
                case 0 -> {                       // 1) eline fişek al, kullan
                    fireFirework(mc, false);
                    state = 1;
                    timer = sideTicks.geti();
                }
                case 1 -> {                       // 2) sağa doğru git
                    float[] rot = rotationTo(eye, aimPoint(t));
                    aimAt(p, rot[0] + (float) angle.get(), rot[1] - 8f, 0.8f);
                    if (--timer <= 0) {
                        state = 2;
                        timer = 40;
                        if (swap.value) {
                            int sw = findHotbar(mc, id -> id.endsWith("_sword"));
                            if (sw >= 0) selectSlot(mc, sw);
                        }
                    }
                }
                case 2 -> {                       // 3) geri gel ve vur
                    double dist = p.distanceTo(t);
                    float[] rot = rotationTo(eye, predicted(t, Math.min(dist / Math.max(speed, 0.6), 6.0)));
                    aimAt(p, rot[0], rot[1], 0.9f);
                    if (fwCd == 0 && speed < 0.8 && dist > 5 && fireFirework(mc, swap.value)) fwCd = 20;
                    if (eyeDist(p, t) <= reach.get() && p.getAttackCooldownProgress(0.5f) >= cooldownNeeded(p)
                            && (!noGhost.value || lookingAt(p, t, reach.get(), 0.0))) {
                        mc.interactionManager.attackEntity(p, t);
                        p.swingHand(Hand.MAIN_HAND);
                        state = 3;
                        timer = 15;
                    } else if (--timer <= 0) {
                        state = 0;
                    }
                }
                default -> {                      // 4) kısa bekleme, sonra yeniden
                    if (--timer <= 0) state = 0;
                }
            }
        }
    }

    // ================================================================== GÖRSEL
    // ---------------------------------------------------------------- Oyuncu ESP
    private static class PlayerEsp extends Module {
        final BoolSetting box = add(new BoolSetting("Kutu", true));
        final BoolSetting health = add(new BoolSetting("Can çubuğu", true));
        final BoolSetting glow = add(new BoolSetting("Parlama (duvar arkası)", true));
        final BoolSetting mobs = add(new BoolSetting("Mobları da göster", false));
        final NumSetting range = add(new NumSetting("Menzil", 32, 128, 32, 64));
        private final Set<Integer> glowed = new HashSet<>();

        PlayerEsp() { super("Oyuncu ESP", "Görsel"); }

        private boolean valid(MinecraftClient mc, Entity e) {
            if (e == mc.player || !(e instanceof LivingEntity) || !e.isAlive()
                    || e instanceof ArmorStandEntity) return false;
            if (!(e instanceof PlayerEntity) && !mobs.value) return false;
            return mc.player.distanceTo(e) <= range.get();
        }

        private void clearGlow(MinecraftClient mc) {
            if (mc.world != null) {
                for (int id : glowed) {
                    Entity e = mc.world.getEntityById(id);
                    if (e != null) e.setGlowing(false);
                }
            }
            glowed.clear();
        }

        @Override
        void onTick(MinecraftClient mc) {
            if (glow.value) {
                for (Entity e : mc.world.getEntities()) {
                    if (valid(mc, e)) {
                        e.setGlowing(true);
                        glowed.add(e.getId());
                    }
                }
            } else if (!glowed.isEmpty()) {
                clearGlow(mc);
            }
        }

        @Override void onDisable(MinecraftClient mc) { clearGlow(mc); }

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            if (!box.value && !health.value) return;
            Proj pr = new Proj(mc);
            for (Entity e : mc.world.getEntities()) {
                if (!valid(mc, e)) continue;
                double[] b = screenBox(pr, e, td);
                if (b == null) continue;
                int x1 = (int) b[0], y1 = (int) b[1], x2 = (int) b[2], y2 = (int) b[3];
                if (x2 - x1 < 2 || y2 - y1 < 2) continue;

                int color = (e instanceof PlayerEntity) ? themeColor(e.getId()) : 0xFFFFAA00;
                if (box.value) {
                    outline(ctx, x1 - 1, y1 - 1, x2 + 1, y2 + 1, 0xFF000000);
                    outline(ctx, x1, y1, x2, y2, color);
                }
                if (health.value && e instanceof LivingEntity le) {
                    float frac = le.getHealth() / Math.max(1f, le.getMaxHealth());
                    int bx = x1 - 4;
                    ctx.fill(bx - 1, y1 - 1, bx + 2, y2 + 1, 0xAA000000);
                    int filled = (int) ((y2 - y1) * MathHelper.clamp(frac, 0f, 1f));
                    ctx.fill(bx, y2 - filled, bx + 1, y2, hpColor(frac));
                }
            }
        }
    }

    // ---------------------------------------------------------------- İsim Etiketi
    private static class Nametags extends Module {
        final BoolSetting hp = add(new BoolSetting("Can göster", true));
        final BoolSetting dist = add(new BoolSetting("Mesafe göster", true));
        final NumSetting range = add(new NumSetting("Menzil", 32, 128, 32, 96));

        Nametags() { super("İsim Etiketi", "Görsel"); }

        @Override void onTick(MinecraftClient mc) {}

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            Proj pr = new Proj(mc);
            TextRenderer tr = mc.textRenderer;
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof PlayerEntity pl) || pl == mc.player || !pl.isAlive()) continue;
                double d = mc.player.distanceTo(pl);
                if (d > range.get()) continue;

                Vec3d pos = pl.getLerpedPos(td);
                double[] s = pr.to(pos.x, pos.y + pl.getHeight() + 0.3, pos.z);
                if (s == null) continue;

                String name = pl.getName().getString();
                float hpv = pl.getHealth() + pl.getAbsorptionAmount();
                String hpText = hp.value ? " " + String.format(Locale.ROOT, "%.1f", hpv) : "";
                String distText = dist.value ? " [" + String.format(Locale.ROOT, "%.0f", d) + "m]" : "";

                int tw = tr.getWidth(name) + tr.getWidth(hpText) + tr.getWidth(distText);
                int x = (int) s[0] - tw / 2;
                int y = (int) s[1] - 11;
                ctx.fill(x - 2, y - 1, x + tw + 2, y + 9, 0x90000000);
                ctx.drawTextWithShadow(tr, name, x, y, 0xFFFFFFFF);
                int cx = x + tr.getWidth(name);
                if (hp.value) {
                    ctx.drawTextWithShadow(tr, hpText, cx, y, hpColor(hpv / Math.max(1f, pl.getMaxHealth())));
                    cx += tr.getWidth(hpText);
                }
                if (dist.value) ctx.drawTextWithShadow(tr, distText, cx, y, 0xFFAAAAAA);
            }
        }
    }

    // ---------------------------------------------------------------- Parlaklık
    private static class Fullbright extends Module {
        Fullbright() { super("Parlaklık", "Görsel"); }

        @Override
        void onTick(MinecraftClient mc) {
            StatusEffectInstance cur = mc.player.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (cur == null || cur.getDuration() < 300) {
                mc.player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.NIGHT_VISION, 600, 0, false, false, false));
            }
        }

        @Override
        void onDisable(MinecraftClient mc) {
            if (mc.player != null) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
    }

    // ---------------------------------------------------------------- Çizgiler (Tracers)
    private static class Tracers extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 1));
        final NumSetting range = add(new NumSetting("Menzil", 32, 256, 32, 128));
        final BoolSetting byDist = add(new BoolSetting("Mesafeye göre renk", true));

        Tracers() { super("Çizgiler", "Görsel"); }

        @Override void onTick(MinecraftClient mc) {}

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            Proj pr = new Proj(mc);
            int cx = pr.w / 2, cy = pr.h;
            for (Entity e : mc.world.getEntities()) {
                if (e == mc.player || !(e instanceof LivingEntity) || !e.isAlive()
                        || e instanceof ArmorStandEntity || !matchesFilter(target.index, e)) continue;
                double d = mc.player.distanceTo(e);
                if (d > range.get()) continue;
                Vec3d pos = e.getLerpedPos(td);
                double[] s = pr.to(pos.x, pos.y + e.getHeight() / 2.0, pos.z);
                if (s == null) continue;
                int color = byDist.value ? hpColor((float) (d / 60.0)) : themeColor(e.getId());
                line(ctx, cx, cy, (int) s[0], (int) s[1], color);
            }
        }
    }

    // ---------------------------------------------------------------- Hedef Bilgisi
    private static class TargetHud extends Module {
        final NumSetting range = add(new NumSetting("Menzil", 10, 60, 10, 30));

        TargetHud() { super("Hedef Bilgisi", "Görsel"); }

        @Override void onTick(MinecraftClient mc) {}

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            List<LivingEntity> list = findTargets(mc, 1, range.get(), true, 360, 2);
            if (list.isEmpty()) return;
            LivingEntity t = list.get(0);
            TextRenderer tr = mc.textRenderer;
            int x = mc.getWindow().getScaledWidth() / 2 + 24, y = mc.getWindow().getScaledHeight() / 2 + 18;
            float hp = t.getHealth() + t.getAbsorptionAmount();
            float frac = hp / Math.max(1f, t.getMaxHealth());

            ctx.fill(x, y, x + 120, y + 34, 0x99000000);
            ctx.fill(x, y, x + 2, y + 34, themeColor(0));
            ctx.drawTextWithShadow(tr, t.getName().getString(), x + 7, y + 4, 0xFFFFFFFF);
            ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "%.1f can | %.1fm", hp, mc.player.distanceTo(t)),
                    x + 7, y + 14, 0xFFBBBBBB);
            ctx.fill(x + 7, y + 25, x + 113, y + 29, 0xFF303030);
            int w = (int) (106 * MathHelper.clamp(frac, 0f, 1f));
            ctx.fill(x + 7, y + 25, x + 7 + w, y + 29, hpColor(frac));
        }
    }

    // ================================================================== HAREKET
    private static class AutoSprint extends Module {
        AutoSprint() { super("Otomatik Koşu", "Hareket"); }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            if (p.input.movementForward > 0 && !p.isSneaking()
                    && !p.horizontalCollision && p.getHungerManager().getFoodLevel() > 6) {
                p.setSprinting(true);
            }
        }
    }

    private static class Fly extends Module {
        final NumSetting speed = add(new NumSetting("Hız", 1, 5, 1, 2));

        Fly() { super("Uçuş", "Hareket"); }

        @Override String info() { return "x" + speed.geti(); }

        @Override
        void onEnable(MinecraftClient mc) {
            if (mc.player != null) mc.player.getAbilities().allowFlying = true;
        }

        @Override
        void onTick(MinecraftClient mc) {
            mc.player.getAbilities().allowFlying = true;
            mc.player.getAbilities().setFlySpeed(0.05f * speed.geti());
        }

        @Override
        void onDisable(MinecraftClient mc) {
            if (mc.player == null) return;
            mc.player.getAbilities().setFlySpeed(0.05f);
            if (!mc.player.isCreative() && !mc.player.isSpectator()) {
                mc.player.getAbilities().allowFlying = false;
                mc.player.getAbilities().flying = false;
            }
        }
    }

    private static class Speed extends Module {
        final NumSetting mult = add(new NumSetting("Çarpan", 1.05, 1.5, 0.05, 1.1));

        Speed() { super("Hız", "Hareket"); }

        @Override String info() { return "x" + mult.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            boolean moving = p.input.movementForward != 0 || p.input.movementSideways != 0;
            if (moving && p.isOnGround() && !p.isSneaking()) {
                Vec3d v = p.getVelocity();
                double m = mult.get();
                p.setVelocity(v.x * m, v.y, v.z * m);
            }
        }
    }

    // ================================================================== kayıt dosyası
    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("plowestclient.properties");
    }

    private static void saveConfig() {
        try {
            Properties pr = new Properties();
            pr.setProperty("hud.x", Float.toString(hudX));
            pr.setProperty("hud.y", Float.toString(hudY));
            pr.setProperty("theme", Integer.toString(theme));
            for (int i = 0; i < panelX.length; i++) {
                pr.setProperty("panel" + i + ".x", Float.toString(panelX[i]));
                pr.setProperty("panel" + i + ".y", Float.toString(panelY[i]));
            }
            for (Module m : MODULES) {
                pr.setProperty("open." + m.name, Boolean.toString(m.expanded));
                pr.setProperty("bind." + m.name, Integer.toString(m.key));
                for (Setting s : m.settings) pr.setProperty("set." + m.name + "." + s.name, s.save());
            }
            try (OutputStream o = Files.newOutputStream(cfgPath())) {
                pr.store(o, "Plowest Client");
            }
        } catch (Exception ignored) {}
    }

    private static void loadConfig() {
        try {
            Path path = cfgPath();
            if (!Files.exists(path)) return;
            Properties pr = new Properties();
            try (InputStream in = Files.newInputStream(path)) { pr.load(in); }
            hudX = Float.parseFloat(pr.getProperty("hud.x", Float.toString(hudX)));
            hudY = Float.parseFloat(pr.getProperty("hud.y", Float.toString(hudY)));
            theme = Math.floorMod(Integer.parseInt(pr.getProperty("theme", "0")), THEME_NAMES.length);
            for (int i = 0; i < panelX.length; i++) {
                panelX[i] = Float.parseFloat(pr.getProperty("panel" + i + ".x", Float.toString(panelX[i])));
                panelY[i] = Float.parseFloat(pr.getProperty("panel" + i + ".y", Float.toString(panelY[i])));
            }
            for (Module m : MODULES) {
                m.expanded = Boolean.parseBoolean(pr.getProperty("open." + m.name, "false"));
                try { m.key = Integer.parseInt(pr.getProperty("bind." + m.name, "-1")); } catch (Exception ignored) { m.key = -1; }
                for (Setting s : m.settings) {
                    String v = pr.getProperty("set." + m.name + "." + s.name);
                    if (v != null) s.load(v);
                }
            }
        } catch (Exception ignored) {}
    }

    // ================================================================== menü ekranı
    private static final class Hit {
        final int x1, y1, x2, y2, kind, idx;
        final Runnable left, right, middle;
        Hit(int x1, int y1, int x2, int y2, int kind, int idx, Runnable left, Runnable right) {
            this(x1, y1, x2, y2, kind, idx, left, right, null);
        }
        Hit(int x1, int y1, int x2, int y2, int kind, int idx, Runnable left, Runnable right, Runnable middle) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.kind = kind; this.idx = idx; this.left = left; this.right = right; this.middle = middle;
        }
        boolean contains(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private static class MenuScreen extends Screen {
        private static final int PW = 124, HH = 16, RH = 16, SH = 13;
        private final List<Hit> hits = new ArrayList<>();
        private int dragPanel = -1;
        private boolean dragHud = false;
        private Module bindTarget = null;   // orta tikla tus bekleyen modul

        MenuScreen() { super(Text.literal("Plowest Client")); }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            hits.clear();
            TextRenderer tr = textRenderer;

            // sürüklenebilir başlık (sol üstteki yazı)
            int wx = (int) hudX, wy = (int) hudY;
            int ww = WM_W, wh = WM_H;
            ctx.fill(wx - 3, wy - 3, wx + ww + 3, wy + wh + 3, 0x40FFFFFF);
            hits.add(new Hit(wx - 3, wy - 3, wx + ww + 3, wy + wh + 3, 2, 0, null, null));

            // tema butonu
            int tx = width - 120, ty = 6;
            ctx.fill(tx, ty, tx + 114, ty + 16, 0xE0101010);
            outline(ctx, tx, ty, tx + 114, ty + 16, themeColor(0));
            ctx.drawTextWithShadow(tr, "Renk: " + THEME_NAMES[theme], tx + 6, ty + 4, themeColor(0));
            hits.add(new Hit(tx, ty, tx + 114, ty + 16, 0, 0, () -> {
                theme = (theme + 1) % THEME_NAMES.length;
            }, () -> {
                theme = (theme - 1 + THEME_NAMES.length) % THEME_NAMES.length;
            }));

            for (int c = 0; c < CATEGORIES.length; c++) {
                int x = (int) panelX[c], y = (int) panelY[c];
                ctx.fill(x, y, x + PW, y + HH, 0xE0101010);
                ctx.fill(x, y + HH - 1, x + PW, y + HH, themeColor(c * 3));
                ctx.drawTextWithShadow(tr, CATEGORIES[c], x + 6, y + 4, themeColor(c * 3));
                hits.add(new Hit(x, y, x + PW, y + HH, 1, c, null, null));

                int cy = y + HH;
                for (Module m : MODULES) {
                    if (!m.category.equals(CATEGORIES[c])) continue;
                    boolean on = m.enabled;
                    int bg = on ? ((themeColor(cy) & 0x00FFFFFF) | 0xAA000000) : 0xC0181818;
                    ctx.fill(x, cy, x + PW, cy + RH, bg);
                    ctx.drawTextWithShadow(tr, m.name, x + 6, cy + 4, on ? 0xFFFFFFFF : 0xFFBBBBBB);
                    int nameW = tr.getWidth(m.name);
                    String bt = (bindTarget == m) ? "[...]" : (m.key >= 0 ? "[" + keyName(m.key) + "]" : "");
                    if (!bt.isEmpty() && 6 + nameW + 4 + tr.getWidth(bt) < PW - 38) {
                        ctx.drawTextWithShadow(tr, bt, x + 6 + nameW + 4, cy + 4, 0xFF888888);
                    }
                    ctx.drawTextWithShadow(tr, m.expanded ? "-" : "+", x + PW - 36, cy + 4, 0xFFAAAAAA);

                    int sx = x + PW - 24, sy = cy + 4;
                    ctx.fill(sx, sy, sx + 20, sy + 8, on ? 0xFF3DDC84 : 0xFF555555);
                    int kx = on ? sx + 12 : sx;
                    ctx.fill(kx, sy, kx + 8, sy + 8, 0xFFFFFFFF);

                    final Module mod = m;
                    final MinecraftClient mcl = client;
                    // satır: sol tık = ayarlar, sağ tık = aç/kapat
                    hits.add(new Hit(x, cy, x + PW, cy + RH, 0, 0,
                            () -> mod.expanded = !mod.expanded,
                            () -> mod.toggle(mcl),
                            () -> bindTarget = mod));
                    // sağdaki düğme: aç/kapat
                    hits.add(new Hit(sx - 4, cy, x + PW, cy + RH, 0, 0,
                            () -> mod.toggle(mcl),
                            () -> mod.toggle(mcl),
                            () -> bindTarget = mod));
                    cy += RH;

                    if (m.expanded) {
                        if (m.settings.isEmpty()) {
                            ctx.fill(x, cy, x + PW, cy + SH, 0xC0222222);
                            ctx.drawTextWithShadow(tr, "Ayar yok", x + 8, cy + 3, 0xFF888888);
                            cy += SH;
                        }
                        for (Setting s : m.settings) {
                            ctx.fill(x, cy, x + PW, cy + SH, 0xC0222222);
                            ctx.fill(x, cy, x + 2, cy + SH, themeColor(cy));
                            String line = s.name + ": " + s.text();
                            ctx.drawTextWithShadow(tr, line, x + 8, cy + 3, 0xFFDDDDDD);
                            final Setting st = s;
                            hits.add(new Hit(x, cy, x + PW, cy + SH, 0, 0,
                                    () -> st.click(0), () -> st.click(1)));
                            cy += SH;
                        }
                    }
                }
                ctx.fill(x, cy, x + PW, cy + 2, themeColor(c * 3));
            }

            String foot = bindTarget != null
                    ? "Tuş bekleniyor: " + bindTarget.name + "  (ESC = tuşu sil)"
                    : "Modüle dokun: ayarlar | Sağdaki düğme: aç/kapat | Orta tık: tuş ata | Başlıkları sürükle";
            ctx.drawTextWithShadow(tr, foot, 6, height - 12, bindTarget != null ? 0xFFFFDD55 : 0xFFAAAAAA);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (bindTarget != null) { bindTarget = null; return true; }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (!h.contains(mx, my)) continue;
                if (h.kind == 1) { dragPanel = h.idx; return true; }
                if (h.kind == 2) { dragHud = true; return true; }
                Runnable r = (button == 1) ? h.right : (button == 2 ? h.middle : h.left);
                if (button <= 2 && r != null) r.run();
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            if (dragPanel >= 0) {
                panelX[dragPanel] = (float) MathHelper.clamp(panelX[dragPanel] + dx, 0, width - PW);
                panelY[dragPanel] = (float) MathHelper.clamp(panelY[dragPanel] + dy, 0, height - HH);
                return true;
            }
            if (dragHud) {
                hudX = (float) MathHelper.clamp(hudX + dx, 0, width - 60);
                hudY = (float) MathHelper.clamp(hudY + dy, 0, height - 20);
                return true;
            }
            return super.mouseDragged(mx, my, button, dx, dy);
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            dragPanel = -1;
            dragHud = false;
            return super.mouseReleased(mx, my, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (bindTarget != null) {
                bindTarget.key = (keyCode == GLFW.GLFW_KEY_ESCAPE) ? -1 : keyCode;
                bindTarget = null;
                return true;
            }
            if (menuKey.matchesKey(keyCode, scanCode)) { close(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void removed() { saveConfig(); }

        @Override
        public boolean shouldPause() { return false; }
    }

    // ================================================================== giris ekrani
    private static class LoginScreen extends Screen {
        private final Screen parent;
        private String user = "", pass = "";
        private int focus = 0;
        private String msg = "";
        private long msgTime = 0;
        private int bx, by;
        private static final int BW = 210, BH = 150;

        LoginScreen(Screen parent) {
            super(Text.literal("Plowest Client Giriş"));
            this.parent = parent;
        }

        private int[] userBox() { return new int[]{bx + 15, by + 52, BW - 30, 16}; }
        private int[] passBox() { return new int[]{bx + 15, by + 86, BW - 30, 16}; }
        private int[] btnBox() { return new int[]{bx + 15, by + 112, BW - 30, 20}; }

        private static boolean in(int[] r, double x, double y) {
            return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
        }

        private void field(DrawContext ctx, TextRenderer tr, int[] r, String label, String text, boolean focused) {
            ctx.drawTextWithShadow(tr, label, r[0], r[1] - 11, 0xFFAAAAAA);
            ctx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF151515);
            outline(ctx, r[0], r[1], r[0] + r[2], r[1] + r[3], focused ? themeColor(0) : 0xFF444444);
            String shown = text;
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0) shown += "_";
            ctx.drawTextWithShadow(tr, shown, r[0] + 4, r[1] + 4, 0xFFFFFFFF);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            TextRenderer tr = textRenderer;
            ctx.fill(0, 0, width, height, 0xB0000000);
            bx = (width - BW) / 2;
            by = (height - BH) / 2;
            long t = System.currentTimeMillis();

            ctx.fill(bx, by, bx + BW, by + BH, 0xE0101010);
            for (int i = 0; i < BW; i += 2) {
                float f = (float) ((Math.sin(t / 400.0 - i * 0.05) + 1) / 2);
                ctx.fill(bx + i, by, bx + i + 2, by + 2, blend(themeColor(i / 2), 0xFFFFFFFF, f * 0.6f));
            }
            drawWave(ctx, tr, "Plowest Client", bx + 15, by + 12, 1.5f);
            ctx.drawTextWithShadow(tr, "Devam etmek için giriş yap", bx + 15, by + 31, 0xFF888888);

            field(ctx, tr, userBox(), "Kullanıcı Adı", user, focus == 0);
            field(ctx, tr, passBox(), "Şifre", "*".repeat(pass.length()), focus == 1);

            int[] bb = btnBox();
            boolean hover = in(bb, mouseX, mouseY);
            ctx.fill(bb[0], bb[1], bb[0] + bb[2], bb[1] + bb[3], hover ? themeColor(0) : blend(themeColor(0), 0xFF000000, 0.45f));
            String bt = "Giriş Yap";
            ctx.drawTextWithShadow(tr, bt, bb[0] + (bb[2] - tr.getWidth(bt)) / 2, bb[1] + 6, 0xFFFFFFFF);

            if (!msg.isEmpty() && t - msgTime < 3000) {
                ctx.drawTextWithShadow(tr, msg, bx + (BW - tr.getWidth(msg)) / 2, by + BH + 6, 0xFFFF5555);
            }
        }

        private void submit() {
            if (sha256(user.trim()).equals(USER_HASH) && sha256(pass).equals(PASS_HASH)) {
                loggedIn = true;
                close();
            } else {
                msg = "Kullanıcı adı veya şifre hatalı";
                msgTime = System.currentTimeMillis();
                pass = "";
                focus = 1;
            }
        }

        private void append(String s) {
            StringBuilder sb = new StringBuilder(focus == 0 ? user : pass);
            for (char c : s.toCharArray()) {
                if (c >= 32 && c != 127 && sb.length() < 32) sb.append(c);
            }
            if (focus == 0) user = sb.toString(); else pass = sb.toString();
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button == 0) {
                if (in(userBox(), mx, my)) { focus = 0; return true; }
                if (in(passBox(), mx, my)) { focus = 1; return true; }
                if (in(btnBox(), mx, my)) { submit(); return true; }
            }
            return super.mouseClicked(mx, my, button);
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            append(String.valueOf(chr));
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
            if (keyCode == GLFW.GLFW_KEY_TAB) { focus = 1 - focus; return true; }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (focus == 0) focus = 1; else submit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (focus == 0 && !user.isEmpty()) user = user.substring(0, user.length() - 1);
                if (focus == 1 && !pass.isEmpty()) pass = pass.substring(0, pass.length() - 1);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                String c = GLFW.glfwGetClipboardString(client.getWindow().getHandle());
                if (c != null) append(c);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void close() { client.setScreen(parent); }

        @Override
        public boolean shouldPause() { return false; }
    }

    // ================================================================== kurulum
    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plowest.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.plowest"));

        CRIT = new Criticals();
        MODULES.add(new TriggerBot());
        MODULES.add(new Aura());
        MODULES.add(new AimAssist());
        MODULES.add(CRIT);
        MODULES.add(new AutoTotem());
        MODULES.add(new ElytraTarget());
        MODULES.add(new SuperFirework());
        MODULES.add(new ElytraHelper());
        MODULES.add(new ElytraStrike());
        MODULES.add(new PlayerEsp());
        MODULES.add(new Nametags());
        MODULES.add(new Tracers());
        MODULES.add(new TargetHud());
        MODULES.add(new Fullbright());
        MODULES.add(new AutoSprint());
        MODULES.add(new Fly());
        MODULES.add(new Speed());
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null) {
                    mc.setScreen(loggedIn ? new MenuScreen() : new LoginScreen(null));
                }
            }
            // oyuna girerken (ana ekranda) bir kez giris ekranini ac
            if (!loggedIn && !loginShown && mc.currentScreen instanceof TitleScreen) {
                loginShown = true;
                mc.setScreen(new LoginScreen(mc.currentScreen));
            }
            if (!loggedIn || mc.player == null || mc.world == null) return;

            trackEntities(mc);

            // tus atamalari (orta tikla atananlar)
            long win = mc.getWindow().getHandle();
            for (Module m : MODULES) {
                if (m.key < 0) { m.keyWas = false; continue; }
                boolean down = mc.currentScreen == null && GLFW.glfwGetKey(win, m.key) == GLFW.GLFW_PRESS;
                if (down && !m.keyWas) m.toggle(mc);
                m.keyWas = down;
            }

            for (Module m : MODULES) {
                if (m.enabled) m.onTick(mc);
            }
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden || mc.player == null || mc.world == null) return;
            float td = tickCounter.getTickDelta(false);

            if (loggedIn) {
                for (Module m : MODULES) {
                    if (m.enabled) m.onHud(ctx, mc, td);
                }
            }

            // sol ust: animasyonlu baslik + soz (menuden surukleyip tasinabilir)
            drawWatermark(ctx, mc, (int) hudX, (int) hudY);

            int y = (int) hudY + WM_H + 4;
            if (!loggedIn) {
                ctx.drawTextWithShadow(mc.textRenderer, "Giriş yapılmadı - Sağ Shift", (int) hudX, y, 0xFFFF7777);
                return;
            }
            int idx = 1;
            for (Module m : MODULES) {
                if (!m.enabled) continue;
                String info = m.info();
                String line = info.isEmpty() ? m.name : m.name + " [" + info + "]";
                ctx.drawTextWithShadow(mc.textRenderer, line, (int) hudX, y, themeColor(idx++));
                y += 11;
            }
        });
    }
}
