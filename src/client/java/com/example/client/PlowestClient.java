package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Plowest Client (Minecraft 1.21.4, Fabric, Yarn) - tek kişilik dünya / özel sunucu için çok modüllü client modu.
 *
 * Tuşlar (Ayarlar > Kontroller > "Plowest Client" kategorisinden değiştirilebilir):
 *   R  TriggerBot      G  Fullbright     H  AutoSprint
 *   J  Fly             K  Speed          X  Aura
 *   B  Aura hedef filtresi               N  Aura menzili
 *   M  AimAssist       ,  AimAssist hedef filtresi
 *   L  Nametags
 *   Sağ Shift  Menü (modül butonları + renk seçimi)
 *   V  TriggerBot hedef filtresi          [ ]  TriggerBot gecikmesi
 */
public class PlowestClient implements ClientModInitializer {

    private static final List<Module> MODULES = new ArrayList<>();

    // ------------------------------------------------------------------ Modül tabanı
    private abstract static class Module {
        final String name;
        final KeyBinding key;
        boolean enabled = false;

        Module(String name, int defaultKey) {
            this.name = name;
            this.key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                    "key.plowest." + name.toLowerCase(),
                    InputUtil.Type.KEYSYM, defaultKey, "category.plowest"));
        }

        void toggle(MinecraftClient mc) {
            enabled = !enabled;
            if (enabled) onEnable(mc); else onDisable(mc);
        }

        String info() { return ""; }
        void onEnable(MinecraftClient mc) {}
        void onDisable(MinecraftClient mc) {}
        abstract void onTick(MinecraftClient mc);
    }

    // ------------------------------------------------------------------ TriggerBot
    private static class TriggerBot extends Module {
        enum Mode {
            ALL("Hepsi"), PLAYERS("Oyuncular"), HOSTILE("Düşman moblar");
            final String label;
            Mode(String label) { this.label = label; }
            Mode next() { return values()[(ordinal() + 1) % values().length]; }
            boolean matches(Entity e) {
                return switch (this) {
                    case ALL -> e instanceof LivingEntity;
                    case PLAYERS -> e instanceof PlayerEntity;
                    case HOSTILE -> e instanceof HostileEntity;
                };
            }
        }

        Mode mode = Mode.ALL;
        int delay = 0;
        int sinceHit = 100;
        final KeyBinding modeKey, delayUp, delayDown;

        TriggerBot() {
            super("TriggerBot", GLFW.GLFW_KEY_R);
            modeKey = reg("key.plowest.triggerbot.mode", GLFW.GLFW_KEY_V);
            delayUp = reg("key.plowest.triggerbot.delay_up", GLFW.GLFW_KEY_RIGHT_BRACKET);
            delayDown = reg("key.plowest.triggerbot.delay_down", GLFW.GLFW_KEY_LEFT_BRACKET);
        }

        @Override String info() { return mode.label + ", " + delay + "t"; }

        @Override
        void onTick(MinecraftClient mc) {
            if (sinceHit < 1000) sinceHit++;
            if (mc.currentScreen != null || mc.interactionManager == null) return;

            if (mc.crosshairTarget instanceof EntityHitResult hit) {
                Entity target = hit.getEntity();
                if (target.isAlive() && mode.matches(target)
                        && mc.player.getAttackCooldownProgress(0.5f) >= 1.0f
                        && sinceHit >= delay) {
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    sinceHit = 0;
                }
            }
        }

        // Ayar tuşları modül kapalıyken de çalışır
        void pollSettingKeys() {
            while (modeKey.wasPressed()) mode = mode.next();
            while (delayUp.wasPressed()) delay = Math.min(20, delay + 1);
            while (delayDown.wasPressed()) delay = Math.max(0, delay - 1);
        }
    }

    // ------------------------------------------------------------------ Fullbright
    private static class Fullbright extends Module {
        Fullbright() { super("Fullbright", GLFW.GLFW_KEY_G); }

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

    // ------------------------------------------------------------------ AutoSprint
    private static class AutoSprint extends Module {
        AutoSprint() { super("AutoSprint", GLFW.GLFW_KEY_H); }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            if (p.input.movementForward > 0 && !p.isSneaking()
                    && !p.horizontalCollision && p.getHungerManager().getFoodLevel() > 6) {
                p.setSprinting(true);
            }
        }
    }

    // ------------------------------------------------------------------ Fly
    private static class Fly extends Module {
        Fly() { super("Fly", GLFW.GLFW_KEY_J); }

        @Override
        void onEnable(MinecraftClient mc) {
            if (mc.player != null) mc.player.getAbilities().allowFlying = true;
        }

        @Override
        void onTick(MinecraftClient mc) {
            mc.player.getAbilities().allowFlying = true;
        }

        @Override
        void onDisable(MinecraftClient mc) {
            if (mc.player == null) return;
            if (!mc.player.isCreative() && !mc.player.isSpectator()) {
                mc.player.getAbilities().allowFlying = false;
                mc.player.getAbilities().flying = false;
            }
        }
    }

    // ------------------------------------------------------------------ Speed
    private static class Speed extends Module {
        Speed() { super("Speed", GLFW.GLFW_KEY_K); }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            boolean moving = p.input.movementForward != 0 || p.input.movementSideways != 0;
            if (moving && p.isOnGround() && !p.isSneaking()) {
                Vec3d v = p.getVelocity();
                p.setVelocity(v.x * 1.1, v.y, v.z * 1.1);
            }
        }
    }

    // ------------------------------------------------------------------ Aura (KillAura)
    // Menzildeki en yakın hedefe, silah dolduğunda otomatik vurur.
    // Kamerayı döndürmez, rastgelelik/gizleme yoktur: düz ve basit çalışır.
    private static class Aura extends Module {
        TriggerBot.Mode mode = TriggerBot.Mode.ALL;
        float range = 3.5f;
        final KeyBinding modeKey, rangeKey;

        Aura() {
            super("Aura", GLFW.GLFW_KEY_X);
            modeKey = reg("key.plowest.aura.mode", GLFW.GLFW_KEY_B);
            rangeKey = reg("key.plowest.aura.range", GLFW.GLFW_KEY_N);
        }

        @Override String info() { return mode.label + ", " + range + " blok"; }

        void pollSettingKeys() {
            while (modeKey.wasPressed()) mode = mode.next();
            while (rangeKey.wasPressed()) {
                range += 0.5f;
                if (range > 6.0f) range = 3.0f;
            }
        }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            if (mc.currentScreen != null || mc.interactionManager == null) return;
            if (p.getAttackCooldownProgress(0.5f) < 1.0f) return;

            Box area = p.getBoundingBox().expand(range);
            Entity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : mc.world.getOtherEntities(p, area)) {
                if (!(e instanceof LivingEntity) || !e.isAlive() || !mode.matches(e)) continue;
                double d = p.distanceTo(e);
                if (d > range || !p.canSee(e)) continue;
                if (d < bestDist) { bestDist = d; best = e; }
            }

            if (best != null) {
                mc.interactionManager.attackEntity(p, best);
                p.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    // ------------------------------------------------------------------ AimAssist
    // Hedefe doğru kamerayı yumuşakça çevirir. Sadece görüş açındaki (FOV) hedefleri seçer.
    private static class AimAssist extends Module {
        TriggerBot.Mode mode = TriggerBot.Mode.ALL;
        static final float RANGE = 5.0f;
        static final float FOV = 60.0f;       // hedef bu açının içindeyse yardım eder
        static final float SMOOTH = 0.15f;    // her tick'te farkın yüzde kaçı kapatılsın
        static final float MAX_STEP = 4.0f;   // tick başına en fazla derece
        final KeyBinding modeKey;

        AimAssist() {
            super("AimAssist", GLFW.GLFW_KEY_M);
            modeKey = reg("key.plowest.aimassist.mode", GLFW.GLFW_KEY_COMMA);
        }

        @Override String info() { return mode.label; }

        void pollSettingKeys() {
            while (modeKey.wasPressed()) mode = mode.next();
        }

        @Override
        void onTick(MinecraftClient mc) {
            ClientPlayerEntity p = mc.player;
            if (mc.currentScreen != null) return;

            Vec3d eye = p.getEyePos();
            Entity best = null;
            double bestAngle = Double.MAX_VALUE;
            float bestYawDiff = 0, bestPitchDiff = 0;

            for (Entity e : mc.world.getOtherEntities(p, p.getBoundingBox().expand(RANGE))) {
                if (!(e instanceof LivingEntity) || !e.isAlive() || !mode.matches(e)) continue;
                if (p.distanceTo(e) > RANGE || !p.canSee(e)) continue;

                Vec3d t = e.getBoundingBox().getCenter();
                double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
                double hd = Math.sqrt(dx * dx + dz * dz);
                float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, hd));

                float yawDiff = MathHelper.wrapDegrees(wantYaw - p.getYaw());
                float pitchDiff = wantPitch - p.getPitch();
                double angle = Math.hypot(yawDiff, pitchDiff);
                if (angle > FOV) continue;

                if (angle < bestAngle) {
                    bestAngle = angle; best = e;
                    bestYawDiff = yawDiff; bestPitchDiff = pitchDiff;
                }
            }

            if (best != null) {
                float dyaw = MathHelper.clamp(bestYawDiff * SMOOTH, -MAX_STEP, MAX_STEP);
                float dpitch = MathHelper.clamp(bestPitchDiff * SMOOTH, -MAX_STEP, MAX_STEP);
                p.setYaw(p.getYaw() + dyaw);
                p.setPitch(MathHelper.clamp(p.getPitch() + dpitch, -90f, 90f));
            }
        }
    }

    // ------------------------------------------------------------------ Nametags
    // Oyuncuların isim ve canını başlarının üstünde, duvarların arkasından bile gösterir.
    private static class Nametags extends Module {
        Nametags() { super("Nametags", GLFW.GLFW_KEY_L); }

        @Override void onTick(MinecraftClient mc) {}

        void render(WorldRenderContext ctx) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!enabled || mc.world == null || mc.player == null) return;

            MatrixStack m = ctx.matrixStack();
            Camera cam = ctx.camera();
            Vec3d cp = cam.getPos();
            TextRenderer tr = mc.textRenderer;
            VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
            float td = mc.getRenderTickCounter().getTickDelta(false);

            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof PlayerEntity pl) || pl == mc.player || !pl.isAlive()) continue;
                Vec3d pos = pl.getLerpedPos(td);
                double dist = pos.distanceTo(cp);
                if (dist > 96) continue;

                float hp = pl.getHealth() + pl.getAbsorptionAmount();
                int color = hp > 14 ? 0xFF55FF55 : hp > 7 ? 0xFFFFFF55 : 0xFFFF5555;
                String text = pl.getName().getString() + " " + String.format("%.1f", hp) + "\u2764";

                m.push();
                m.translate(pos.x - cp.x, pos.y + pl.getHeight() + 0.6 - cp.y, pos.z - cp.z);
                m.multiply(cam.getRotation());
                float scale = 0.025f * (float) Math.max(1.0, dist * 0.2);
                m.scale(-scale, -scale, scale);
                Matrix4f mat = m.peek().getPositionMatrix();
                float w = -tr.getWidth(text) / 2f;
                tr.draw(text, w, 0, color, false, mat, vcp,
                        TextRenderer.TextLayerType.SEE_THROUGH, 0x66000000, 0xF000F0);
                m.pop();
            }
            vcp.draw();
        }
    }

    // ------------------------------------------------------------------ Tema (renk)
    private static final String[] THEME_NAMES =
            {"Turuncu", "Mavi", "Kırmızı", "Mor", "Yeşil", "Gökkuşağı"};
    private static final int[] THEME_COLORS =
            {0xFFAA00, 0x55AAFF, 0xFF5555, 0xAA55FF, 0x55FF55, 0};
    private static int theme = 0;
    private static KeyBinding menuKey;

    /** Seçili temanın rengi. Gökkuşağı temasında offset ile satırlar farklı renkte kayar. */
    private static int themeColor(int offset) {
        if (theme == THEME_COLORS.length - 1) {
            float h = ((System.currentTimeMillis() / 20 + offset * 12L) % 360) / 360f;
            return 0xFF000000 | MathHelper.hsvToRgb(h, 0.7f, 1f);
        }
        return 0xFF000000 | THEME_COLORS[theme];
    }

    // ------------------------------------------------------------------ Menü ekranı
    // Sağ Shift ile açılır. Sol üstte "Plowest Client" yazar, modülleri butonla aç/kapatırsın.
    private static class MenuScreen extends Screen {
        MenuScreen() { super(Text.literal("Plowest Client")); }

        private static Text label(Module m) {
            return Text.literal(m.name + ": " + (m.enabled ? "AÇIK" : "KAPALI"));
        }

        @Override
        protected void init() {
            int y = 32;
            for (Module m : MODULES) {
                ButtonWidget b = ButtonWidget.builder(label(m), btn -> {
                    m.toggle(client);
                    btn.setMessage(label(m));
                }).dimensions(5, y, 130, 18).build();
                addDrawableChild(b);
                y += 21;
            }
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("Renk: " + THEME_NAMES[theme]), btn -> {
                        theme = (theme + 1) % THEME_NAMES.length;
                        btn.setMessage(Text.literal("Renk: " + THEME_NAMES[theme]));
                    }).dimensions(5, y + 6, 130, 18).build());
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.render(ctx, mouseX, mouseY, delta);
            ctx.getMatrices().push();
            ctx.getMatrices().scale(2f, 2f, 1f);
            ctx.drawTextWithShadow(textRenderer, "Plowest Client", 3, 3, themeColor(0));
            ctx.getMatrices().pop();
        }

        @Override
        public boolean shouldPause() { return false; }
    }

    // ------------------------------------------------------------------ Kurulum
    private static KeyBinding reg(String translationKey, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey, InputUtil.Type.KEYSYM, defaultKey, "category.plowest"));
    }

    @Override
    public void onInitializeClient() {
        TriggerBot triggerBot = new TriggerBot();
        MODULES.add(triggerBot);
        MODULES.add(new Fullbright());
        MODULES.add(new AutoSprint());
        MODULES.add(new Fly());
        MODULES.add(new Speed());
        Aura aura = new Aura();
        MODULES.add(aura);
        AimAssist aimAssist = new AimAssist();
        MODULES.add(aimAssist);
        Nametags nametags = new Nametags();
        MODULES.add(nametags);

        menuKey = reg("key.plowest.menu", GLFW.GLFW_KEY_RIGHT_SHIFT);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new MenuScreen());
            }
            triggerBot.pollSettingKeys();
            aura.pollSettingKeys();
            aimAssist.pollSettingKeys();

            for (Module m : MODULES) {
                while (m.key.wasPressed()) m.toggle(mc);
            }

            if (mc.player == null || mc.world == null) return;
            for (Module m : MODULES) {
                if (m.enabled) m.onTick(mc);
            }
        });

        WorldRenderEvents.AFTER_ENTITIES.register(nametags::render);

        // Sol üstte başlık ve açık modüllerin listesi
        HudRenderCallback.EVENT.register((ctx, tickDelta) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden || mc.player == null) return;

            ctx.getMatrices().push();
            ctx.getMatrices().scale(1.5f, 1.5f, 1f);
            ctx.drawTextWithShadow(mc.textRenderer, "Plowest Client", 3, 3, themeColor(0));
            ctx.getMatrices().pop();
            int y = 24;
            int idx = 1;
            for (Module m : MODULES) {
                if (!m.enabled) continue;
                String info = m.info();
                String line = info.isEmpty() ? m.name : m.name + " [" + info + "]";
                ctx.drawTextWithShadow(mc.textRenderer, line, 5, y, themeColor(idx++));
                y += 11;
            }
        });
    }
}
