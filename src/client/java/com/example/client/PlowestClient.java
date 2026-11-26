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
    private static final String[] CATEGORIES = {"Combat"};
    private static final float[] panelX = {20, 150, 280, 410};
    private static final float[] panelY = {50, 50, 50, 50};
    private static float hudX = 5, hudY = 5;
    private static KeyBinding menuKey;
    private static boolean comboWas = false;

    private static final String[] FILTERS = {"All", "Players", "Hostile mobs", "Animals"};

    // ================================================================== tema
    private static final String[] THEME_NAMES =
            {"Orange", "Blue", "Red", "Purple", "Green", "Rainbow"};
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
        @Override String text() { return value ? "ON" : "OFF"; }
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
    private static KeySetting listening = null;

    private static class KeySetting extends Setting {
        int key = -1;
        KeySetting(String name) { super(name); }
        @Override String text() {
            if (listening == this) return "[...]";
            return key >= 0 ? keyName(key) : "NONE";
        }
        @Override void click(int button) {
            if (button == 1) { key = -1; listening = null; } else listening = this;
        }
        @Override String save() { return Integer.toString(key); }
        @Override void load(String v) { try { key = Integer.parseInt(v); } catch (Exception ignored) {} }
    }

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
        void onFrame(MinecraftClient mc, float td) {}
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
    // ---- Target ESP icin: Aura / Aim Assist / Elytra Strike su an kimi hedef aliyor?
    private static int tgtId = -1;
    private static long tgtStamp = 0;

    private static void markTarget(LivingEntity t) {
        tgtId = t.getId();
        tgtStamp = System.currentTimeMillis();
    }

    private static int kills = 0, countedKill = -1;

    /** Hedef aldigimiz canli kisa surede olurse "kill" say */
    private static void countKills(MinecraftClient mc) {
        if (tgtId < 0 || tgtId == countedKill || System.currentTimeMillis() - tgtStamp > 3000) return;
        Entity e = mc.world.getEntityById(tgtId);
        if (e instanceof LivingEntity le && !le.isAlive()) {
            kills++;
            countedKill = tgtId;
        }
    }

    private static final Map<Integer, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<Integer, Vec3d> VEL = new HashMap<>();
    private static int trackTicks = 0;

    private static void trackEntities(MinecraftClient mc) {
        if (++trackTicks > 1200) { trackTicks = 0; LAST_POS.clear(); VEL.clear(); }
        for (Entity e : mc.world.getEntities()) {
            Vec3d now = e.getLerpedPos(1.0f);
            Vec3d last = LAST_POS.put(e.getId(), now);
            if (last != null) {
                double rx = now.x - last.x, ry = now.y - last.y, rz = now.z - last.z;
                if (rx * rx + ry * ry + rz * rz > 9.0) { rx = 0; ry = 0; rz = 0; }   // isinlanma / ani sicrama
                Vec3d old = VEL.get(e.getId());
                // yumusatilmis hiz: tek tick'lik gurultu nisani titretmesin
                VEL.put(e.getId(), old == null ? new Vec3d(rx, ry, rz)
                        : new Vec3d(old.x * 0.7 + rx * 0.3, old.y * 0.7 + ry * 0.3, old.z * 0.7 + rz * 0.3));
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
            float wave = 0f;      // sabit: yazi oynamaz
            float glow = 0.25f;
            int color = blend(themeColor(i * 8), 0xFFFFFFFF, glow * 0.65f);
            ctx.drawTextWithShadow(tr, ch, (int) cx, (int) (y / scale + wave), color);
            cx += tr.getWidth(ch);
        }
        ctx.getMatrices().pop();
    }

    private static final String[] QUOTES = {
            "Every hit sends a message.",
            "Stay ahead, never fall behind.",
            "Your speed, your victory.",
            "The sky is our arena.",
            "Plowest Baba is watching."
    };
    private static final int WM_H = 19;

    /** Yuvarlak koseli dolu dikdortgen (DrawContext'te yok, kose piksellerini keserek yapiyoruz) */
    private static void rrect(DrawContext ctx, int x1, int y1, int x2, int y2, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
        ctx.fill(x1 + r, y1, x2 - r, y2, color);
        for (int i = 0; i < r; i++) {
            double dy = r - Math.sqrt(r * r - Math.pow(r - i - 0.5, 2));
            int in = (int) Math.round(dy);
            ctx.fill(x1 + i, y1 + in, x1 + i + 1, y2 - in, color);
            ctx.fill(x2 - i - 1, y1 + in, x2 - i, y2 - in, color);
        }
    }

    private static int wmW = 200;

    /** Nursultan / Grim tarzi: koyu yuvarlak panel, renkli parlama, isim | kullanici | FPS | ping */
    private static void drawWatermark(DrawContext ctx, MinecraftClient mc, int x, int y) {
        TextRenderer tr = mc.textRenderer;
        long t = System.currentTimeMillis();
        String user = mc.player != null ? mc.player.getName().getString() : "Guest";
        int ping = 0;
        if (mc.getNetworkHandler() != null && mc.player != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) ping = entry.getLatency();
        }
        String a = "Plowest", b = " Client";
        String s1 = user, s2 = mc.getCurrentFps() + " FPS", s3 = ping + " ms";
        int pad = 7, sep = 9, h = 17;
        int w = pad + tr.getWidth(a) + tr.getWidth(b) + sep + tr.getWidth(s1) + sep
                + tr.getWidth(s2) + sep + tr.getWidth(s3) + pad;
        wmW = w;

        int accent = themeColor(0);
        rrect(ctx, x - 1, y - 1, x + w + 1, y + h + 1, 4, (accent & 0x00FFFFFF) | 0x55000000);   // parlama
        rrect(ctx, x, y, x + w, y + h, 4, 0xEE0E0E12);                                           // panel
        for (int i = 4; i < w - 4; i++) {                                                         // alt serit
            ctx.fill(x + i, y + h - 2, x + i + 1, y + h - 1, themeColor(i / 3));
        }

        int ty = y + 4;
        int cx = x + pad;
        for (int i = 0; i < a.length(); i++) {                                                    // "Plowest" gradyan
            String ch = String.valueOf(a.charAt(i));
            ctx.drawTextWithShadow(tr, ch, cx, ty, blend(themeColor(i * 10), 0xFFFFFFFF, 0.2f));
            cx += tr.getWidth(ch);
        }
        ctx.drawTextWithShadow(tr, b, cx, ty, 0xFFFFFFFF);
        cx += tr.getWidth(b);
        String[] parts = {s1, s2, s3};
        for (String part : parts) {
            ctx.fill(cx + sep / 2, y + 4, cx + sep / 2 + 1, y + h - 4, 0x55FFFFFF);               // ayirac
            cx += sep;
            ctx.drawTextWithShadow(tr, part, cx, ty, 0xFFCFCFD6);
            cx += tr.getWidth(part);
        }
    }

    private static int labelWidth(TextRenderer tr, Module m) {
        String i = m.info();
        return tr.getWidth(m.name) + (i.isEmpty() ? 0 : tr.getWidth(i) + 5);
    }

    /** Sag ustte acik modullerin listesi (Nursultan tarzi) */
    private static void drawArrayList(DrawContext ctx, MinecraftClient mc) {
        TextRenderer tr = mc.textRenderer;
        List<Module> on = new ArrayList<>();
        for (Module m : MODULES) if (m.enabled) on.add(m);
        on.sort((p, q) -> Integer.compare(labelWidth(tr, q), labelWidth(tr, p)));
        int sw = mc.getWindow().getScaledWidth();
        int y = 4, idx = 0;
        for (Module m : on) {
            String info = m.info();
            int w = labelWidth(tr, m) + 10;
            int x2 = sw - 4, x1 = x2 - w;
            int col = themeColor(idx * 6);
            rrect(ctx, x1, y, x2, y + 13, 3, 0xB00E0E12);
            ctx.fill(x2 - 2, y + 3, x2, y + 10, col);
            ctx.drawTextWithShadow(tr, m.name, x1 + 4, y + 3, col);
            if (!info.isEmpty()) {
                ctx.drawTextWithShadow(tr, info, x1 + 4 + tr.getWidth(m.name) + 5, y + 3, 0xFFAAAAAA);
            }
            y += 15;
            idx++;
        }
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
    private static int bestSword(MinecraftClient mc) {
        String[] order = {"netherite_sword", "diamond_sword", "iron_sword", "stone_sword", "golden_sword", "wooden_sword"};
        for (String o : order) {
            int slot = findHotbar(mc, id -> id.equals(o));
            if (slot >= 0) return slot;
        }
        return -1;
    }

    private static class TriggerBot extends Module {
        final ModeSetting target = add(new ModeSetting("Target", FILTERS, 0));
        final BoolSetting onlySword = add(new BoolSetting("Only Sword", true));
        final BoolSetting autoSwap = add(new BoolSetting("Auto Swap", false));
        final NumSetting delay = add(new NumSetting("Delay (ticks)", 0, 20, 1, 0));
        int since = 100;

        TriggerBot() { super("Trigger Bot", "Combat"); }

        @Override String info() { return target.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (since < 1000) since++;
            if (mc.currentScreen != null || mc.interactionManager == null) return;
            if (!(mc.crosshairTarget instanceof EntityHitResult hit)) return;

            ClientPlayerEntity p = mc.player;
            Entity e = hit.getEntity();
            if (!(e instanceof LivingEntity) || !e.isAlive() || e instanceof ArmorStandEntity
                    || !matchesFilter(target.index, e)) return;

            boolean holdingSword = isSword(itemId(p.getMainHandStack()));
            if (autoSwap.value && !holdingSword) {
                int sw = bestSword(mc);
                if (sw >= 0) { selectSlot(mc, sw); since = 0; }
                return;
            }
            if (onlySword.value && !holdingSword) return;

            if (p.getAttackCooldownProgress(0.5f) < cooldownNeeded(p) || since < delay.geti()) return;
            if (!critOk(mc)) return;

            mc.interactionManager.attackEntity(p, e);
            p.swingHand(Hand.MAIN_HAND);
            since = 0;
        }
    }

    // ---------------------------------------------------------------- Aura (KillAura)
    private static class Aura extends Module {
        final ModeSetting target = add(new ModeSetting("Target", FILTERS, 0));
        final ModeSetting priority = add(new ModeSetting("Priority",
                new String[]{"Closest", "Lowest health", "FOV"}, 0));
        final NumSetting range = add(new NumSetting("Range", 3.0, 4.0, 0.5, 3.5));
        final NumSetting fov = add(new NumSetting("FOV", 60, 360, 30, 360));
        final ModeSetting rotate = add(new ModeSetting("Rotation",
                new String[]{"None", "Smooth", "Instant"}, 1));
        final BoolSetting walls = add(new BoolSetting("Through walls", false));
        final BoolSetting noGhost = add(new BoolSetting("Ghost-hit guard", true));
        final NumSetting multi = add(new NumSetting("Multi target", 1, 3, 1, 1));
        final NumSetting delay = add(new NumSetting("Delay (ticks)", 0, 10, 1, 0));
        int since = 100;

        Aura() { super("AttackAura", "Combat"); }

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
            markTarget(primary);

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
        final KeySetting bind = add(new KeySetting("Key"));
        int lockId = -1;
        long lastNs = 0;
        boolean on = false, keyWas = false;   // tusa basinca ac, tekrar basinca kapat

        AimAssist() { super("Aim Assist", "Combat"); enabled = true; }

        private boolean active(MinecraftClient mc) {
            return on && bind.key >= 0 && mc.currentScreen == null;
        }

        // hedef secimi: tick basina bir kez
        @Override
        void onTick(MinecraftClient mc) {
            if (!active(mc)) { lockId = -1; return; }
            ClientPlayerEntity p = mc.player;
            if (lockId >= 0) {
                Entity e = mc.world.getEntityById(lockId);
                if (!(e instanceof LivingEntity le) || !le.isAlive() || !(e instanceof PlayerEntity)
                        || p.distanceTo(e) > 5.75 || !p.canSee(e)) {
                    lockId = -1;
                }
            }
            if (lockId < 0) {
                List<LivingEntity> list = findTargets(mc, 1, 5.0, false, 120, 2);
                if (list.isEmpty()) return;
                lockId = list.get(0).getId();
            }
            Entity e = mc.world.getEntityById(lockId);
            if (e instanceof LivingEntity le) markTarget(le);
        }

        // donusu her karede yumusak uygula (mouse gibi, titreme yok)
        @Override
        void onFrame(MinecraftClient mc, float td) {
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 0.008f : Math.min((now - lastNs) / 1.0e9f, 0.05f);
            lastNs = now;

            boolean down = bind.key >= 0
                    && GLFW.glfwGetKey(mc.getWindow().getHandle(), bind.key) == GLFW.GLFW_PRESS;
            if (down && !keyWas && mc.currentScreen == null) {
                on = !on;
                if (!on) lockId = -1;
            }
            keyWas = down;

            if (lockId < 0 || !active(mc)) return;
            Entity e = mc.world.getEntityById(lockId);
            if (!(e instanceof LivingEntity t) || !t.isAlive()) return;

            ClientPlayerEntity p = mc.player;
            Vec3d pos = t.getLerpedPos(td);
            Box b = t.getBoundingBox();
            Vec3d aim = new Vec3d(pos.x, pos.y + (b.maxY - b.minY) * 0.75, pos.z);
            float[] rot = rotationTo(p.getCameraPosVec(td), aim);

            float yawErr = MathHelper.wrapDegrees(rot[0] - p.getYaw());
            float pitchErr = rot[1] - p.getPitch();
            float dist = (float) Math.hypot(yawErr, pitchErr);
            if (dist < 0.4f) return;                       // ortadaysa oynatma

            float k = 1f - (float) Math.exp(-7.0 * dt);    // yumusak yaklasma
            float yawStep = yawErr * k;
            float pitchStep = pitchErr * k * 0.8f;
            float step = (float) Math.hypot(yawStep, pitchStep);
            float maxStep = 200f * dt;                      // saniyede max donus
            if (step > maxStep) {
                float r = maxStep / step;
                yawStep *= r;
                pitchStep *= r;
            }
            p.changeLookDirection(yawStep / 0.15, pitchStep / 0.15);
        }
    }

    // ---------------------------------------------------------------- Kritik
    private static class Criticals extends Module {
        final ModeSetting mode = add(new ModeSetting("Mode",
                new String[]{"Jump & wait", "Air only"}, 0));

        Criticals() { super("Criticals", "Combat"); }

        @Override String info() { return mode.text(); }
        @Override void onTick(MinecraftClient mc) {}
    }

    // ---------------------------------------------------------------- Oto Totem
    private static class AutoTotem extends Module {
        final NumSetting hp = add(new NumSetting("Health threshold (20 = always)", 4, 20, 1, 20));
        final NumSetting delay = add(new NumSetting("Delay (ticks)", 0, 10, 1, 1));
        int since = 100;

        AutoTotem() { super("Auto Totem", "Combat"); }

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
    // ---------------------------------------------------------------- Süper Fişek
    private static class SuperFirework extends Module {
        final NumSetting power = add(new NumSetting("Power", 1.0, 4.0, 0.5, 2.0));
        final NumSetting duration = add(new NumSetting("Duration (ticks)", 20, 100, 10, 50));
        boolean useWas = false;

        SuperFirework() { super("Super Firework", "Elytra"); }

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

    // ---------------------------------------------------------------- Elytra Vuruş (fişek > sağa > geri gel > vur)
    private static class ElytraStrike extends Module {
        final ModeSetting target = add(new ModeSetting("Target", FILTERS, 1));
        final NumSetting reach = add(new NumSetting("Hit range", 2.5, 3.5, 0.1, 3.0));
        final NumSetting angle = add(new NumSetting("Side angle (deg)", 30, 90, 15, 60));
        final NumSetting sideTicks = add(new NumSetting("Side time (ticks)", 4, 20, 2, 8));
        final BoolSetting swap = add(new BoolSetting("Swap (to sword)", true));
        final BoolSetting noGhost = add(new BoolSetting("Ghost-hit guard", true));
        final NumSetting backSlot = add(new NumSetting("Return slot", 1, 9, 1, 1));
        int state = 0, timer = 0, fwCd = 0;

        ElytraStrike() { super("Elytra Strike", "Elytra"); }

        @Override String info() { return new String[]{"Firework", "Side", "Strike", "Wait"}[state]; }
        @Override void onEnable(MinecraftClient mc) { state = 0; timer = 0; fwCd = 0; }
        @Override void onDisable(MinecraftClient mc) {
            if (mc.player != null) selectSlot(mc, backSlot.geti() - 1);
        }

        /** Hedefe hizalan ve (sarj tamam + gercekten bakiyorsak) vur. true = vurdu. */
        private boolean strike(MinecraftClient mc, ClientPlayerEntity p, LivingEntity t, double speed) {
            double dist = p.distanceTo(t);
            Vec3d aim = dist < 6 ? aimPoint(t) : predicted(t, Math.min(dist / Math.max(speed, 0.6), 6.0));
            float[] rot = rotationTo(p.getEyePos(), aim);
            aimAt(p, rot[0], rot[1], 0.9f);
            if (eyeDist(p, t) <= reach.get() && p.getAttackCooldownProgress(0.5f) >= cooldownNeeded(p)
                    && (!noGhost.value || lookingAt(p, t, reach.get(), 0.0))) {
                if (swap.value) {
                    int sw = findHotbar(mc, id -> id.endsWith("_sword"));
                    if (sw >= 0) selectSlot(mc, sw);
                }
                mc.interactionManager.attackEntity(p, t);
                p.swingHand(Hand.MAIN_HAND);
                selectSlot(mc, backSlot.geti() - 1);      // vurduktan sonra Slot 1'e dön
                return true;
            }
            return false;
        }

        @Override
        void onTick(MinecraftClient mc) {
            if (fwCd > 0) fwCd--;
            ClientPlayerEntity p = mc.player;
            if (mc.currentScreen != null || mc.interactionManager == null) { state = 0; return; }

            // mesafe siniri yok: en yakin hedef alinir
            List<LivingEntity> list = findTargets(mc, target.index, 256.0, true, 360, 0);
            if (list.isEmpty()) { state = 0; return; }
            LivingEntity t = list.get(0);
            Vec3d v = p.getVelocity();
            double speed = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            markTarget(t);
            boolean close = eyeDist(p, t) <= reach.get() + 0.5;

            // hedefin dibindeysek ya da yerdeysek: fisek/yan hareket yok, direkt vur
            if (close || !p.isGliding()) {
                if (close) {
                    if (strike(mc, p, t, speed)) { state = 3; timer = 15; }
                } else {
                    state = 0;
                }
                if (!p.isGliding()) return;
                if (close) return;
            }

            switch (state) {
                case 0 -> {                       // 1) eline fişek al, kullan
                    fireFirework(mc, false);
                    selectSlot(mc, backSlot.geti() - 1);      // fişekten sonra Slot 1'e dön
                    state = 1;
                    timer = sideTicks.geti();
                }
                case 1 -> {                       // 2) sağa doğru git
                    float[] rot = rotationTo(p.getEyePos(), aimPoint(t));
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
                    if (fwCd == 0 && speed < 0.8 && fireFirework(mc, swap.value)) fwCd = 20;
                    if (strike(mc, p, t, speed)) {
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
        final BoolSetting box = add(new BoolSetting("Box", true));
        final BoolSetting health = add(new BoolSetting("Health bar", true));
        final BoolSetting glow = add(new BoolSetting("Glow (through walls)", true));
        final BoolSetting mobs = add(new BoolSetting("Show mobs", false));
        final NumSetting range = add(new NumSetting("Range", 32, 128, 32, 64));
        private final Set<Integer> glowed = new HashSet<>();

        PlayerEsp() { super("Player ESP", "Visual"); }

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
        final BoolSetting hp = add(new BoolSetting("Show health", true));
        final BoolSetting dist = add(new BoolSetting("Show distance", true));
        final NumSetting range = add(new NumSetting("Range", 32, 128, 32, 96));

        Nametags() { super("Nametags", "Visual"); }

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
        Fullbright() { super("Brightness", "Visual"); }

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
        final ModeSetting target = add(new ModeSetting("Target", FILTERS, 1));
        final NumSetting range = add(new NumSetting("Range", 32, 256, 32, 128));
        final BoolSetting byDist = add(new BoolSetting("Color by distance", true));

        Tracers() { super("Tracers", "Visual"); }

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
        final NumSetting range = add(new NumSetting("Range", 10, 60, 10, 30));

        TargetHud() { super("Target HUD", "Visual"); }

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
            ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "%.1f HP | %.1fm", hp, mc.player.distanceTo(t)),
                    x + 7, y + 14, 0xFFBBBBBB);
            ctx.fill(x + 7, y + 25, x + 113, y + 29, 0xFF303030);
            int w = (int) (106 * MathHelper.clamp(frac, 0f, 1f));
            ctx.fill(x + 7, y + 25, x + 7 + w, y + 29, hpColor(frac));
        }
    }

    // ---------------------------------------------------------------- Velocity (geri tepme azaltma)
    private static class Velocity extends Module {
        final NumSetting horizontal = add(new NumSetting("Horizontal %", 0, 100, 10, 40));
        final NumSetting vertical = add(new NumSetting("Vertical %", 0, 100, 10, 100));
        int lastHurt = 0;

        Velocity() { super("Velocity", "Combat"); }

        @Override String info() { return horizontal.text() + "%/" + vertical.text() + "%"; }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            if (p.hurtTime > lastHurt) {           // yeni hasar aldik: geri tepmeyi kis
                Vec3d v = p.getVelocity();
                p.setVelocity(v.x * horizontal.get() / 100.0, v.y * vertical.get() / 100.0,
                        v.z * horizontal.get() / 100.0);
            }
            lastHurt = p.hurtTime;
        }
    }

    // ---------------------------------------------------------------- Auto Weapon
    private static class AutoWeapon extends Module {
        AutoWeapon() { super("Auto Weapon", "Combat"); }

        @Override
        void onTick(MinecraftClient mc) {
            if (mc.currentScreen != null) return;
            if (!(mc.crosshairTarget instanceof EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity)) return;
            String held = itemId(mc.player.getMainHandStack());
            if (isSword(held) || isAxe(held)) return;
            int sw = findHotbar(mc, id -> isSword(id));
            if (sw >= 0) selectSlot(mc, sw);
        }
    }

    // ---------------------------------------------------------------- Target ESP (hedefin ustunde halka + isaret)
    private static class TargetEsp extends Module {
        final ModeSetting mode = add(new ModeSetting("Mode", new String[]{"Ring", "Marker", "Both"}, 2));
        final BoolSetting hurtFlash = add(new BoolSetting("Flash on hit", true));

        TargetEsp() { super("Target ESP", "Visual"); }

        @Override void onTick(MinecraftClient mc) {}

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            if (System.currentTimeMillis() - tgtStamp > 150) return;      // su an hedef alinmiyor
            Entity e = mc.world.getEntityById(tgtId);
            if (!(e instanceof LivingEntity t) || !t.isAlive()) return;

            Proj pr = new Proj(mc);
            Vec3d pos = t.getLerpedPos(td);
            double radius = t.getWidth() * 0.85 + 0.1, height = t.getHeight();
            double time = System.currentTimeMillis() / 1000.0;
            int base = (hurtFlash.value && t.hurtTime > 0) ? 0xFFFF4545 : themeColor(0);
            int rgb = base & 0x00FFFFFF;

            if (mode.index != 1) {
                double yy = pos.y + height * (0.5 + 0.5 * Math.sin(time * 2.0));   // halka govdede asagi yukari kayar
                int n = 40;
                double lead = time * 3.2;
                for (int i = 0; i < n; i++) {
                    double ang = i * 2 * Math.PI / n;
                    double d = (((lead - ang) % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
                    double k = 1.0 - d / (2 * Math.PI);                             // kuyruk efekti
                    double[] s = pr.to(pos.x + Math.cos(ang) * radius, yy, pos.z + Math.sin(ang) * radius);
                    if (s == null) continue;
                    int sz = k > 0.75 ? 3 : 2;
                    int a = (int) (50 + 205 * k);
                    int x = (int) s[0] - sz / 2, y = (int) s[1] - sz / 2;
                    ctx.fill(x, y, x + sz, y + sz, (a << 24) | rgb);
                }
                // yerdeki soluk halka
                for (int i = 0; i < 28; i++) {
                    double ang = i * 2 * Math.PI / 28;
                    double[] s = pr.to(pos.x + Math.cos(ang) * radius, pos.y + 0.02, pos.z + Math.sin(ang) * radius);
                    if (s != null) ctx.fill((int) s[0], (int) s[1], (int) s[0] + 1, (int) s[1] + 1, 0x70000000 | rgb);
                }
            }

            if (mode.index != 0) {
                double[] s = pr.to(pos.x, pos.y + height + 0.5 + 0.08 * Math.sin(time * 4.0), pos.z);
                if (s != null) {
                    int cx = (int) s[0], cy = (int) s[1];
                    int size = 6;
                    for (int i = 0; i < size + 2; i++) {                             // koyu kenar
                        ctx.fill(cx - (size + 1 - i), cy + i - 1, cx + (size + 1 - i) + 1, cy + i, 0xB0000000);
                    }
                    for (int i = 0; i < size; i++) {                                 // renkli ucgen (asagi bakar)
                        ctx.fill(cx - (size - 1 - i), cy + i, cx + (size - 1 - i) + 1, cy + i + 1, base);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Radar
    private static class Radar extends Module {
        final ModeSetting target = add(new ModeSetting("Target", FILTERS, 1));
        final NumSetting range = add(new NumSetting("Range", 20, 100, 10, 50));
        final NumSetting size = add(new NumSetting("Size", 60, 120, 10, 80));

        Radar() { super("Radar", "Visual"); }

        @Override void onTick(MinecraftClient mc) {}

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            ClientPlayerEntity p = mc.player;
            int sz = size.geti();
            int x = (int) hudX, y = (int) hudY + WM_H + 6;
            int cx = x + sz / 2, cy = y + sz / 2;
            double half = sz / 2.0;

            rrect(ctx, x - 1, y - 1, x + sz + 1, y + sz + 1, 5, (themeColor(0) & 0x00FFFFFF) | 0x55000000);
            rrect(ctx, x, y, x + sz, y + sz, 5, 0xCC0E0E12);
            ctx.fill(cx, y + 3, cx + 1, y + sz - 3, 0x30FFFFFF);      // capraz cizgiler
            ctx.fill(x + 3, cy, x + sz - 3, cy + 1, 0x30FFFFFF);

            double yaw = Math.toRadians(p.getYaw());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);           // ileri
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);          // sag

            for (Entity e : mc.world.getEntities()) {
                if (e == p || !(e instanceof LivingEntity) || !e.isAlive() || e instanceof ArmorStandEntity
                        || !matchesFilter(target.index, e)) continue;
                double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
                double right = dx * rx + dz * rz, fwd = dx * fx + dz * fz;
                double px = right / range.get() * half, py = -fwd / range.get() * half;
                double len = Math.hypot(px, py);
                double lim = half - 4;
                if (len > lim) { px = px / len * lim; py = py / len * lim; }   // kenara yapistir
                int col = e instanceof PlayerEntity ? 0xFFFF5555 : themeColor(e.getId());
                int dxp = cx + (int) Math.round(px), dyp = cy + (int) Math.round(py);
                ctx.fill(dxp - 1, dyp - 1, dxp + 2, dyp + 2, col);
            }
            ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);          // biz
        }
    }

    // ---------------------------------------------------------------- Damage Numbers
    private static class DamageNumbers extends Module {
        private static final class Pop {
            final Vec3d pos; final String text; final int rgb; final long born;
            Pop(Vec3d pos, String text, int rgb, long born) { this.pos = pos; this.text = text; this.rgb = rgb; this.born = born; }
        }

        final ModeSetting target = add(new ModeSetting("Target", FILTERS, 0));
        final NumSetting duration = add(new NumSetting("Duration (ms)", 600, 1800, 200, 1000));
        final List<Pop> pops = new ArrayList<>();
        final Map<Integer, Float> hp = new HashMap<>();
        int ticks = 0;

        DamageNumbers() { super("Damage Numbers", "Visual"); }

        @Override void onEnable(MinecraftClient mc) { hp.clear(); pops.clear(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (++ticks > 600) { ticks = 0; hp.clear(); }
            ClientPlayerEntity p = mc.player;
            long now = System.currentTimeMillis();
            for (Entity e : mc.world.getEntities()) {
                if (e == p || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof ArmorStandEntity
                        || !matchesFilter(target.index, e) || p.distanceTo(e) > 40) continue;
                float h = le.getHealth() + le.getAbsorptionAmount();
                Float old = hp.put(e.getId(), h);
                if (old == null) continue;
                float diff = old - h;
                if (Math.abs(diff) < 0.1f) continue;
                Vec3d head = e.getLerpedPos(1.0f).add(0, e.getHeight() + 0.2, 0);
                head = new Vec3d(head.x + (Math.random() - 0.5) * 0.6, head.y, head.z + (Math.random() - 0.5) * 0.6);
                if (diff > 0) pops.add(new Pop(head, String.format(Locale.ROOT, "-%.1f", diff), 0xFF5555, now));
                else if (-diff >= 0.5f) pops.add(new Pop(head, String.format(Locale.ROOT, "+%.1f", -diff), 0x55FF55, now));
            }
            while (pops.size() > 40) pops.remove(0);
        }

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            long now = System.currentTimeMillis();
            pops.removeIf(pp -> now - pp.born > duration.get());
            if (pops.isEmpty()) return;
            Proj pr = new Proj(mc);
            TextRenderer tr = mc.textRenderer;
            for (Pop pp : pops) {
                double k = (now - pp.born) / duration.get();                      // 0 -> 1
                double[] s = pr.to(pp.pos.x, pp.pos.y + 0.9 * k, pp.pos.z);       // yukari suzulur
                if (s == null) continue;
                int alpha = (int) Math.max(30, 255 * (1 - k * k));
                ctx.drawTextWithShadow(tr, pp.text, (int) s[0] - tr.getWidth(pp.text) / 2, (int) s[1], (alpha << 24) | pp.rgb);
            }
        }
    }

    // ---------------------------------------------------------------- Zoom
    private static class Zoom extends Module {
        final NumSetting fov = add(new NumSetting("Zoom FOV", 30, 60, 5, 30));
        int old = -1;

        Zoom() { super("Zoom", "Visual"); }

        @Override String info() { return fov.text(); }

        @Override void onEnable(MinecraftClient mc) {
            old = mc.options.getFov().getValue();
            mc.options.getFov().setValue((int) fov.get());
        }

        @Override void onDisable(MinecraftClient mc) {
            mc.options.getFov().setValue(old > 0 ? old : 70);
            old = -1;
        }

        @Override
        void onTick(MinecraftClient mc) {
            if (old < 0) old = 70;                                   // ayardan acik yuklendiyse varsayilan FOV'a don
            if (mc.options.getFov().getValue() != (int) fov.get()) mc.options.getFov().setValue((int) fov.get());
        }
    }

    // ---------------------------------------------------------------- Info HUD
    private static class InfoHud extends Module {
        InfoHud() { super("Info HUD", "Visual"); }

        @Override void onTick(MinecraftClient mc) {}

        @Override
        void onHud(DrawContext ctx, MinecraftClient mc, float td) {
            ClientPlayerEntity p = mc.player;
            TextRenderer tr = mc.textRenderer;
            Vec3d v = VEL.get(p.getId());
            double bps = v == null ? 0 : Math.hypot(v.x, v.z) * 20.0;
            int totems = itemId(p.getOffHandStack()).equals("totem_of_undying") ? 1 : 0;
            for (int i = 0; i < 36; i++) {
                if (itemId(p.getInventory().getStack(i)).equals("totem_of_undying")) totems++;
            }
            String[] lines = {
                    String.format(Locale.ROOT, "XYZ  %.1f  %.1f  %.1f", p.getX(), p.getY(), p.getZ()),
                    String.format(Locale.ROOT, "Speed  %.1f b/s", bps),
                    "Totems  " + totems,
                    "Kills  " + kills
            };
            int w = 0;
            for (String l : lines) w = Math.max(w, tr.getWidth(l));
            w += 12;
            int h = lines.length * 11 + 5;
            int x = mc.getWindow().getScaledWidth() - w - 4;
            int y = mc.getWindow().getScaledHeight() - h - 4;
            rrect(ctx, x, y, x + w, y + h, 3, 0xB00E0E12);
            ctx.fill(x, y + 3, x + 2, y + h - 3, themeColor(0));
            for (int i = 0; i < lines.length; i++) {
                ctx.drawTextWithShadow(tr, lines[i], x + 7, y + 4 + i * 11, 0xFFE0E0E6);
            }
        }
    }

    // ================================================================== HAREKET
    private static class AutoSprint extends Module {
        AutoSprint() { super("Auto Sprint", "Movement"); }

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
        final NumSetting speed = add(new NumSetting("Speed", 1, 5, 1, 2));

        Fly() { super("Fly", "Movement"); }

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
        final NumSetting mult = add(new NumSetting("Multiplier", 1.05, 1.5, 0.05, 1.1));

        Speed() { super("Speed", "Movement"); }

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

            for (int c = 0; c < CATEGORIES.length; c++) {
                int x = (int) panelX[c], y = (int) panelY[c];
                // baslik: koyu gri, ortali beyaz yazi, alt cizgi
                ctx.fill(x, y, x + PW, y + HH, 0xE0161616);
                ctx.drawCenteredTextWithShadow(tr, CATEGORIES[c], x + PW / 2, y + 4, 0xFFFFFFFF);
                ctx.fill(x + 6, y + HH - 1, x + PW - 6, y + HH, 0xFF2C2C34);
                hits.add(new Hit(x, y, x + PW, y + HH, 1, c, null, null));

                int cy = y + HH;
                for (Module m : MODULES) {
                    if (!m.category.equals(CATEGORIES[c])) continue;
                    boolean on = m.enabled;
                    ctx.fill(x, cy, x + PW, cy + RH, on ? 0xE0343434 : 0xE0161616);
                    ctx.drawTextWithShadow(tr, m.name, x + 6, cy + 4, on ? 0xFFFFFFFF : 0xFFCFCFCF);
                    if (bindTarget == m) {
                        ctx.drawTextWithShadow(tr, "[...]", x + PW - 6 - tr.getWidth("[...]"), cy + 4, 0xFF888888);
                    }

                    final Module mod = m;
                    final MinecraftClient mcl = client;
                    // sol tik = ac/kapat, sag tik = ayarlar, orta tik = tus ata
                    hits.add(new Hit(x, cy, x + PW, cy + RH, 0, 0,
                            () -> mod.toggle(mcl),
                            () -> mod.expanded = !mod.expanded,
                            () -> bindTarget = mod));
                    cy += RH;

                    if (m.expanded) {
                        for (Setting st : m.settings) {
                            ctx.fill(x, cy, x + PW, cy + SH, 0xE01E1E1E);
                            ctx.drawTextWithShadow(tr, st.name + ": " + st.text(), x + 10, cy + 3, 0xFFB5B5B5);
                            final Setting stf = st;
                            hits.add(new Hit(x, cy, x + PW, cy + SH, 0, 0,
                                    () -> stf.click(0), () -> stf.click(1)));
                            cy += SH;
                        }
                    }
                }
                ctx.fill(x, cy, x + PW, cy + 2, 0xE0161616);
            }

            if (bindTarget != null) {
                ctx.drawTextWithShadow(tr, "Press a key for: " + bindTarget.name + "  (ESC = clear bind)",
                        6, height - 12, 0xFFFFDD55);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (listening != null) { listening = null; return true; }
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
            if (listening != null) {
                listening.key = (keyCode == GLFW.GLFW_KEY_ESCAPE) ? -1 : keyCode;
                listening = null;
                return true;
            }
            if (bindTarget != null) {
                bindTarget.key = (keyCode == GLFW.GLFW_KEY_ESCAPE) ? -1 : keyCode;
                bindTarget = null;
                return true;
            }
            boolean ctrlKey = keyCode == GLFW.GLFW_KEY_LEFT_CONTROL || keyCode == GLFW.GLFW_KEY_RIGHT_CONTROL;
            boolean altKey = keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT;
            if ((ctrlKey && (modifiers & GLFW.GLFW_MOD_ALT) != 0) || (altKey && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0)) {
                close();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void removed() { listening = null; saveConfig(); }

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
            super(Text.literal("Plowest Client Login"));
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
            rrect(ctx, r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, 3, focused ? themeColor(0) : 0xFF3A3A42);
            rrect(ctx, r[0], r[1], r[0] + r[2], r[1] + r[3], 3, 0xFF151519);
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

            rrect(ctx, bx - 1, by - 1, bx + BW + 1, by + BH + 1, 5, (themeColor(0) & 0x00FFFFFF) | 0x55000000);
            rrect(ctx, bx, by, bx + BW, by + BH, 5, 0xEE0E0E12);
            for (int i = 0; i < BW; i += 2) {
                float f = (float) ((Math.sin(t / 400.0 - i * 0.05) + 1) / 2);
                ctx.fill(bx + i, by, bx + i + 2, by + 2, blend(themeColor(i / 2), 0xFFFFFFFF, f * 0.6f));
            }
            drawWave(ctx, tr, "Plowest Client", bx + 15, by + 12, 1.5f);
            ctx.drawTextWithShadow(tr, "Log in to continue", bx + 15, by + 31, 0xFF888888);

            field(ctx, tr, userBox(), "Username", user, focus == 0);
            field(ctx, tr, passBox(), "Password", "*".repeat(pass.length()), focus == 1);

            int[] bb = btnBox();
            boolean hover = in(bb, mouseX, mouseY);
            rrect(ctx, bb[0], bb[1], bb[0] + bb[2], bb[1] + bb[3], 3, hover ? themeColor(0) : blend(themeColor(0), 0xFF000000, 0.45f));
            String bt = "Log In";
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
                msg = "Invalid username or password";
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

        MODULES.add(new TriggerBot());
        MODULES.add(new AimAssist());
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long hw = mc.getWindow().getHandle();
            boolean combo = (GLFW.glfwGetKey(hw, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(hw, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
                    && (GLFW.glfwGetKey(hw, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(hw, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS);
            if (combo && !comboWas && mc.currentScreen == null) {
                mc.setScreen(loggedIn ? new MenuScreen() : new LoginScreen(null));
            }
            comboWas = combo;
            // oyuna girerken (ana ekranda) bir kez giris ekranini ac
            if (!loggedIn && !loginShown && mc.currentScreen instanceof TitleScreen) {
                loginShown = true;
                mc.setScreen(new LoginScreen(mc.currentScreen));
            }
            if (!loggedIn || mc.player == null || mc.world == null) return;

            trackEntities(mc);
            countKills(mc);

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
            if (loggedIn && mc.player != null && mc.world != null && mc.currentScreen == null) {
                float tdf = tickCounter.getTickDelta(false);
                for (Module m : MODULES) {
                    if (m.enabled) m.onFrame(mc, tdf);
                }
            }
            if (mc.options.hudHidden || mc.player == null || mc.world == null) return;
            float td = tickCounter.getTickDelta(false);

            if (loggedIn) {
                for (Module m : MODULES) {
                    if (m.enabled) m.onHud(ctx, mc, td);
                }
            }

            ctx.drawCenteredTextWithShadow(mc.textRenderer, "Hack For Jurossed",
                    ctx.getScaledWindowWidth() / 2, ctx.getScaledWindowHeight() - 17, 0xFFFFFFFF);

        });
    }
}
