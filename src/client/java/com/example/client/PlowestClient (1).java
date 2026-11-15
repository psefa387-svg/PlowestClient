package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

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
    private static final String[] CATEGORIES = {"Savaş", "Görsel", "Hareket"};
    private static final float[] panelX = {20, 140, 260};
    private static final float[] panelY = {50, 50, 50};
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

    // ================================================================== SAVAŞ
    // ---------------------------------------------------------------- Tetik Botu
    private static class TriggerBot extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 0));
        final NumSetting delay = add(new NumSetting("Gecikme (tick)", 0, 20, 1, 0));
        int since = 100;

        TriggerBot() { super("Tetik Botu", "Savaş"); }

        @Override String info() { return target.text() + ", " + delay.geti() + "t"; }

        @Override
        void onTick(MinecraftClient mc) {
            if (since < 1000) since++;
            if (mc.currentScreen != null || mc.interactionManager == null) return;

            if (mc.crosshairTarget instanceof EntityHitResult hit) {
                Entity e = hit.getEntity();
                if (e instanceof LivingEntity && e.isAlive() && !(e instanceof ArmorStandEntity)
                        && matchesFilter(target.index, e)
                        && mc.player.getAttackCooldownProgress(0.5f) >= 1.0f
                        && since >= delay.geti()) {
                    mc.interactionManager.attackEntity(mc.player, e);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    since = 0;
                }
            }
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
            if (p.getAttackCooldownProgress(0.5f) < 1.0f) return;
            if (since < delay.geti()) return;

            int n = Math.min(multi.geti(), list.size());
            for (int i = 0; i < n; i++) mc.interactionManager.attackEntity(p, list.get(i));
            p.swingHand(Hand.MAIN_HAND);
            since = 0;
        }
    }

    // ---------------------------------------------------------------- Nişan Yardımı
    private static class AimAssist extends Module {
        final ModeSetting target = add(new ModeSetting("Hedef", FILTERS, 0));
        final NumSetting range = add(new NumSetting("Menzil", 3.0, 6.0, 0.5, 4.5));
        final NumSetting fov = add(new NumSetting("Görüş açısı", 30, 120, 15, 90));
        final ModeSetting smooth = add(new ModeSetting("Yumuşaklık",
                new String[]{"Yavaş", "Orta", "Hızlı"}, 1));
        final BoolSetting onlyClick = add(new BoolSetting("Sadece vururken", true));

        AimAssist() { super("Nişan Yardımı", "Savaş"); }

        @Override String info() { return target.text(); }

        @Override
        void onTick(MinecraftClient mc) {
            if (mc.currentScreen != null) return;
            if (onlyClick.value && !mc.options.attackKey.isPressed()) return;
            ClientPlayerEntity p = mc.player;

            List<LivingEntity> list = findTargets(mc, target.index, range.get(), false, fov.get(), 2);
            if (list.isEmpty()) return;

            float[] rot = rotationTo(p.getEyePos(), aimPoint(list.get(0)));
            float yawDiff = MathHelper.wrapDegrees(rot[0] - p.getYaw());
            float pitchDiff = rot[1] - p.getPitch();
            float f = new float[]{0.08f, 0.16f, 0.30f}[smooth.index];

            p.setYaw(p.getYaw() + MathHelper.clamp(yawDiff * f, -6f, 6f));
            p.setPitch(MathHelper.clamp(p.getPitch() + MathHelper.clamp(pitchDiff * f, -4f, 4f), -90f, 90f));
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
        final Runnable left, right;
        Hit(int x1, int y1, int x2, int y2, int kind, int idx, Runnable left, Runnable right) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.kind = kind; this.idx = idx; this.left = left; this.right = right;
        }
        boolean contains(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private static class MenuScreen extends Screen {
        private static final int PW = 112, HH = 16, RH = 16, SH = 13;
        private final List<Hit> hits = new ArrayList<>();
        private int dragPanel = -1;
        private boolean dragHud = false;

        MenuScreen() { super(Text.literal("Plowest Client")); }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            hits.clear();
            TextRenderer tr = textRenderer;

            // sürüklenebilir başlık (sol üstteki yazı)
            int wx = (int) hudX, wy = (int) hudY;
            int ww = (int) (tr.getWidth("Plowest Client") * 1.5f), wh = 14;
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
                            () -> mod.toggle(mcl)));
                    // sağdaki düğme: aç/kapat
                    hits.add(new Hit(sx - 4, cy, x + PW, cy + RH, 0, 0,
                            () -> mod.toggle(mcl),
                            () -> mod.toggle(mcl)));
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

            ctx.drawTextWithShadow(tr,
                    "Modüle dokun: ayarlar | Sağdaki düğme: aç/kapat | Başlıkları sürükleyerek taşı",
                    6, height - 12, 0xFFAAAAAA);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (!h.contains(mx, my)) continue;
                if (h.kind == 1) { dragPanel = h.idx; return true; }
                if (h.kind == 2) { dragHud = true; return true; }
                Runnable r = (button == 1) ? h.right : h.left;
                if (button <= 1 && r != null) r.run();
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
            if (menuKey.matchesKey(keyCode, scanCode)) { close(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void removed() { saveConfig(); }

        @Override
        public boolean shouldPause() { return false; }
    }

    // ================================================================== kurulum
    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plowest.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.plowest"));

        MODULES.add(new TriggerBot());
        MODULES.add(new Aura());
        MODULES.add(new AimAssist());
        MODULES.add(new PlayerEsp());
        MODULES.add(new Nametags());
        MODULES.add(new Fullbright());
        MODULES.add(new AutoSprint());
        MODULES.add(new Fly());
        MODULES.add(new Speed());
        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new MenuScreen());
            }
            if (mc.player == null || mc.world == null) return;
            for (Module m : MODULES) {
                if (m.enabled) m.onTick(mc);
            }
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden || mc.player == null || mc.world == null) return;
            float td = tickCounter.getTickDelta(false);

            for (Module m : MODULES) {
                if (m.enabled) m.onHud(ctx, mc, td);
            }

            // sol üst başlık (menüden sürüklenebilir)
            ctx.getMatrices().push();
            ctx.getMatrices().scale(1.5f, 1.5f, 1f);
            ctx.drawTextWithShadow(mc.textRenderer, "Plowest Client",
                    (int) (hudX / 1.5f), (int) (hudY / 1.5f), themeColor(0));
            ctx.getMatrices().pop();

            int y = (int) hudY + 18;
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
