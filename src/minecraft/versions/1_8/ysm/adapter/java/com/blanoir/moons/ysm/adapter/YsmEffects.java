package com.blanoir.moons.ysm.adapter;

import com.blanoir.moons.ysm.*;
import com.blanoir.moons.ysm.internal.runtime.LocalRuntime;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Supplier;

/** Model-owned Minecraft 1.8 sounds and particles; effects never send network packets. */
final class YsmEffects implements LocalRuntime.Effects, AutoCloseable {
    private final LocalRuntime runtime;
    private final Supplier<Entity> source;
    private final YsmSoundBank sounds;
    private final Set<YsmSoundHandle> vanillaSounds = new HashSet<>();
    private final Map<String, ParticleSpec> particles = new LinkedHashMap<>();
    private final Random random = new Random();
    private Object level;
    private boolean closed;
    private static final Field PARTICLE_LIFETIME = lifetimeField();

    YsmEffects(LocalYsmModel model) {
        this(model, () -> Minecraft.getMinecraft().thePlayer);
    }

    YsmEffects(LocalYsmModel model, Supplier<Entity> source) {
        this.source = source;
        runtime = model.runtime();
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        model.sounds().forEach((name, file) -> encoded.put(name, file.data));
        sounds =
                new YsmSoundBank(
                        encoded,
                        YsmAudioDecoder::decode,
                        (pcm, loop, volume, pitch) ->
                                YsmGameAudio.play(pcm, loop, volume, pitch, source),
                        runtime::diagnostic);
    }

    public YsmSoundHandle sound(String name, boolean loop, float volume, float pitch) {
        Minecraft client = Minecraft.getMinecraft();
        if (closed || client.thePlayer == null || !Float.isFinite(volume) || !Float.isFinite(pitch))
            return null;
        if (!name.contains(":")) return sounds.play(name, loop, volume, pitch);
        try {
            if (!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid resource name");
            ResourceLocation id = new ResourceLocation(name);
            if (client.getSoundHandler().getSound(id) == null)
                throw new IllegalArgumentException("Sound is unavailable in 1.8.9");
            FollowSound sound = new FollowSound(id, loop, volume, pitch);
            sound.update();
            if (sound.isDonePlaying()) return null;
            client.getSoundHandler().playSound(sound);
            YsmSoundHandle handle =
                    new YsmSoundHandle() {
                        private boolean stopped;

                        public boolean stopped() {
                            return stopped
                                    || sound.isDonePlaying()
                                    || !client.getSoundHandler().isSoundPlaying(sound);
                        }

                        public void close() {
                            if (stopped) return;
                            stopped = true;
                            client.getSoundHandler().stopSound(sound);
                        }
                    };
            vanillaSounds.removeIf(YsmSoundHandle::stopped);
            vanillaSounds.add(handle);
            return handle;
        } catch (IllegalArgumentException failure) {
            runtime.diagnostic("Sound " + name + ": " + failure.getMessage());
            return null;
        }
    }

    private final class FollowSound extends MovingSound {
        FollowSound(ResourceLocation id, boolean loop, float volume, float pitch) {
            super(id);
            this.repeat = loop;
            this.volume = Math.clamp(volume, 0, 16);
            this.pitch = Math.clamp(pitch, .05f, 4f);
            this.attenuationType = ISound.AttenuationType.LINEAR;
        }

        public void update() {
            Entity owner = source.get();
            if (closed
                    || owner == null
                    || owner.isDead
                    || owner.worldObj != Minecraft.getMinecraft().theWorld) {
                donePlaying = true;
                return;
            }
            xPosF = (float) owner.posX;
            yPosF = (float) owner.posY;
            zPosF = (float) owner.posZ;
        }
    }

    public void particle(String name, boolean absolute, double[] args) {
        Minecraft client = Minecraft.getMinecraft();
        Entity player = source.get();
        if (closed || player == null || client.theWorld == null || name.isBlank()) return;
        if (level != client.theWorld) {
            level = client.theWorld;
            particles.clear();
        }
        try {
            ParticleSpec spec = particles.get(name);
            if (spec == null) {
                spec = particleSpec(name);
                if (particles.size() >= 256) particles.remove(particles.keySet().iterator().next());
                particles.put(name, spec);
            }
            for (double value : args) if (!Double.isFinite(value)) return;
            Vec3 offset = new Vec3(arg(args, 0, 0), arg(args, 1, 0), arg(args, 2, 0));
            double dx = arg(args, 3, 0),
                    dy = arg(args, 4, 0),
                    dz = arg(args, 5, 0),
                    speed = arg(args, 6, 0);
            int count = (int) Math.clamp(arg(args, 7, 0), 0, 4096);
            int lifetime = (int) Math.clamp(arg(args, 8, 20), 1, 12000);
            for (int i = 0; i < Math.max(1, count); i++) {
                Vec3 position =
                        count == 0
                                ? offset
                                : offset.addVector(
                                        random.nextGaussian() * dx,
                                        random.nextGaussian() * dy,
                                        random.nextGaussian() * dz);
                if (!absolute)
                    position =
                            position.rotateYaw(
                                    -(count == 0 && player instanceof EntityLivingBase living
                                                    ? living.renderYawOffset
                                                    : player.rotationYaw)
                                            * (float) Math.PI
                                            / 180);
                position = position.addVector(player.posX, player.posY, player.posZ);
                double vx = count == 0 ? speed * dx : random.nextGaussian() * speed;
                double vy = count == 0 ? speed * dy : random.nextGaussian() * speed;
                double vz = count == 0 ? speed * dz : random.nextGaussian() * speed;
                EntityFX particle =
                        client.effectRenderer.spawnEffectParticle(
                                spec.type.getParticleID(),
                                position.xCoord,
                                position.yCoord,
                                position.zCoord,
                                vx,
                                vy,
                                vz,
                                spec.parameters);
                if (particle != null) {
                    PARTICLE_LIFETIME.setInt(particle, lifetime);
                    if (spec.color != null) {
                        particle.setRBGColorF(spec.color[0], spec.color[1], spec.color[2]);
                        particle.multipleParticleScaleBy(spec.color[3]);
                    }
                }
            }
        } catch (Exception failure) {
            runtime.diagnostic("Particle " + name + ": " + failure.getMessage());
        }
    }

    private record ParticleSpec(EnumParticleTypes type, int[] parameters, float[] color) {}

    private static ParticleSpec particleSpec(String name) {
        String[] words = name.trim().split("\\s+");
        String id = words[0].replaceFirst("^minecraft:", "");
        String nativeName =
                switch (id) {
                    case "explosion" -> "explode";
                    case "large_smoke" -> "largesmoke";
                    case "bubble" -> "bubble";
                    case "splash" -> "splash";
                    case "fishing" -> "wake";
                    case "enchanted_hit" -> "magicCrit";
                    case "instant_effect" -> "instantSpell";
                    case "effect" -> "spell";
                    case "entity_effect" -> "mobSpell";
                    case "ambient_entity_effect" -> "mobSpellAmbient";
                    case "witch" -> "witchMagic";
                    case "dripping_water" -> "dripWater";
                    case "dripping_lava" -> "dripLava";
                    case "angry_villager" -> "angryVillager";
                    case "happy_villager" -> "happyVillager";
                    case "enchant" -> "enchantmenttable";
                    case "dust" -> "reddust";
                    case "item_snowball" -> "snowballpoof";
                    case "item_slime" -> "slime";
                    case "block" -> "blockcrack";
                    case "falling_dust" -> "blockdust";
                    case "item" -> "iconcrack";
                    case "firework" -> "fireworksSpark";
                    default -> id;
                };
        EnumParticleTypes selected = null;
        for (EnumParticleTypes type : EnumParticleTypes.values()) {
            String base = type.getParticleName().replaceAll("_$", "");
            if (nativeName.equalsIgnoreCase(base) || nativeName.startsWith(base + "_")) {
                selected = type;
                break;
            }
        }
        if (selected == null)
            throw new IllegalArgumentException("Particle is unavailable in 1.8.9");
        int[] parameters = new int[selected.getArgumentCount()];
        if (parameters.length > 0) {
            String base = selected.getParticleName().replaceAll("_$", "");
            if (nativeName.startsWith(base + "_")) {
                String[] values = nativeName.substring(base.length() + 1).split("_");
                for (int i = 0; i < parameters.length; i++)
                    parameters[i] = i < values.length ? Integer.parseInt(values[i]) : 0;
            } else if (words.length >= 2) {
                if (selected == EnumParticleTypes.ITEM_CRACK) {
                    Item item = Item.itemRegistry.getObject(new ResourceLocation(words[1]));
                    if (item == null) throw new IllegalArgumentException("Unknown particle item");
                    parameters[0] = Item.getIdFromItem(item);
                    if (parameters.length > 1 && words.length > 2)
                        parameters[1] = Integer.parseInt(words[2]);
                } else {
                    Block block = Block.getBlockFromName(words[1]);
                    if (block == null) throw new IllegalArgumentException("Unknown particle block");
                    int metadata = words.length > 2 ? Integer.parseInt(words[2]) : 0;
                    parameters[0] = Block.getStateId(block.getStateFromMeta(metadata));
                }
            } else throw new IllegalArgumentException("Particle requires block/item arguments");
        }
        float[] color = null;
        if (id.equals("dust") && words.length == 5) {
            color = new float[4];
            for (int i = 0; i < 4; i++) {
                color[i] = Float.parseFloat(words[i + 1]);
                if (!Float.isFinite(color[i]))
                    throw new IllegalArgumentException("Invalid dust color");
            }
        }
        return new ParticleSpec(selected, parameters, color);
    }

    private static Field lifetimeField() {
        // Exact stable_22 and joined.srg names: beb/g -> EntityFX/field_70547_e.
        for (String name : new String[] {"particleMaxAge", "field_70547_e", "g"}) {
            try {
                Field field = EntityFX.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalStateException("Missing Minecraft 1.8.9 particle lifetime field");
    }

    private static double arg(double[] args, int index, double fallback) {
        return index < args.length ? args[index] : fallback;
    }

    void stopAll() {
        sounds.stopAll();
        vanillaSounds.forEach(YsmSoundHandle::close);
        vanillaSounds.clear();
    }

    public void close() {
        if (closed) return;
        closed = true;
        stopAll();
        sounds.close();
        particles.clear();
    }
}
