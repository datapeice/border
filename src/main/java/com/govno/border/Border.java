package com.govno.border;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

public class Border implements ModInitializer {
    public static final String MOD_ID = "border";
    public static final Logger LOGGER = LoggerFactory.getLogger("border-mod");

    private int tickCounter = 0;
    private final Random random = new Random();
    private List<int[][]> polygonSegments = new ArrayList<>();

    private static final Gson GSON = new Gson();
    private static final File DEATHS_FILE = new File("config/border_deaths.json");
    private static Map<String, Integer> deathCounts = new HashMap<>();

    private final Map<UUID, Integer> borderDeathTimers = new HashMap<>();
    private static final int TICKS_BEFORE_COUNT = 20 * 5;

    @Override
    public void onInitialize() {
        LOGGER.info("BORDER: Initializing BorderMod");

        ensureDeathFileExists();

        loadDeathCounts();

        // Команда /border reload
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("border")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("reload")
                                .executes(context -> {
                                    BorderConfig.reload();
                                    loadPolygonSegments();
                                    context.getSource().sendFeedback(() -> Text.literal("§aBorder: конфиг перезагружен!"), false);
                                    return 1;
                                })
                        )
                )
        );

        loadPolygonSegments();

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void ensureDeathFileExists() {
        try {
            DEATHS_FILE.getParentFile().mkdirs();
            if (!DEATHS_FILE.exists()) {
                saveDeathCounts();
                LOGGER.info("BORDER: Создан deaths.json");
            }
        } catch (Exception e) {
            LOGGER.error("BORDER: Не удалось создать deaths.json", e);
        }
    }

    private void loadPolygonSegments() {
        polygonSegments.clear();
        List<int[]> polygon = BorderConfig.get().getPolygon();
        for (int i = 0; i < polygon.size(); i++) {
            int[] a = polygon.get(i);
            int[] b = polygon.get((i + 1) % polygon.size());
            polygonSegments.add(new int[][]{{a[0], a[1]}, {b[0], b[1]}});
        }
    }

    private void onServerTick(MinecraftServer server) {
        tickCounter++;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            checkPlayerPosition(player);

            boolean inside = isInsidePolygon(player.getBlockX(), player.getBlockZ());
            if (!inside && player.getHealth() <= 0) {
                borderDeathTimers.putIfAbsent(player.getUuid(), 0); // стартуем таймер
            }
        }

        // Обновляем таймеры
        Iterator<Map.Entry<UUID, Integer>> iterator = borderDeathTimers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            UUID playerId = entry.getKey();
            int ticks = entry.getValue() + 1;
            entry.setValue(ticks);

            if (ticks >= TICKS_BEFORE_COUNT) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (player != null) {
                    onBorderDeath(player);
                }
                iterator.remove();
            }
        }
    }

    private void checkPlayerPosition(ServerPlayerEntity player) {
        ServerWorld world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        int x = pos.getX();
        int z = pos.getZ();

        boolean inside = isInsidePolygon(x, z);
        double distance = distanceToPolygonEdge(x, z);

        if (inside) return;

        if (distance <= 50) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 30, 0, false, false));
        } else if (distance <= 100) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 220, 0, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 220, 1, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 220, 1, false, false));

            if (tickCounter % 40 == 0) {
                player.damage(world, BorderDamageSource.create(world), 8.0f);
                spawnFireParticles(world, pos, 10);
            }
        } else {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 999999, 0, false, false));

            if (tickCounter % 80 == 0) {
                player.damage(world, BorderDamageSource.create(world), Float.MAX_VALUE);

//                world.playSound(null, player.getX(), player.getY(), player.getZ(),
//                        SoundEvents.ENTITY_WARDEN_AGITATED, player.getSoundCategory(), 1.0f, 0.6f);
//                world.playSound(null, player.getX(), player.getY(), player.getZ(),
//                        SoundEvents.AMBIENT_CAVE, player.getSoundCategory(), 1.0f, 0.5f);

                SoundEvent sound = SoundEvent.of(Identifier.of("slbase", "void"));
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        sound, player.getSoundCategory(), 1.0f, 0.5f);

                spawnFireParticles(world, pos, 20);
                spawnAshParticles(world, pos, 70);
            }
        }

        if (world.getRegistryKey() == World.NETHER) {
            breakNearbyPortalBlocks(player);
        }

        if (player.hasVehicle() && distance <= 100 && player.getControllingVehicle() instanceof BoatEntity vehicle) {
            vehicle.updatePosition(pos.getX(), pos.getY() - 1, pos.getZ());
            player.dismountVehicle();
        }
    }

    private boolean isInsidePolygon(int x, int z) {
        boolean inside = false;
        for (int i = 0, j = polygonSegments.size() - 1; i < polygonSegments.size(); j = i++) {
            int xi = polygonSegments.get(i)[0][0], zi = polygonSegments.get(i)[0][1];
            int xj = polygonSegments.get(j)[0][0], zj = polygonSegments.get(j)[0][1];
            boolean intersect = ((zi > z) != (zj > z)) &&
                    (x < (xj - xi) * (z - zi) / (double) (zj - zi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private double distanceToPolygonEdge(int x, int z) {
        double minDistSq = Double.MAX_VALUE;
        for (int[][] segment : polygonSegments) {
            double distSq = pointToSegmentDistanceSquared(x, z,
                    segment[0][0], segment[0][1],
                    segment[1][0], segment[1][1]);
            if (distSq < minDistSq) minDistSq = distSq;
        }
        return Math.sqrt(minDistSq);
    }

    private double pointToSegmentDistanceSquared(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        if (dx == 0 && dz == 0) return (px - x1) * (px - x1) + (pz - z1) * (pz - z1);
        double t = ((px - x1) * dx + (pz - z1) * dz) / (dx * dx + dz * dz);
        t = Math.max(0, Math.min(1, t));
        double projX = x1 + t * dx;
        double projZ = z1 + t * dz;
        double diffX = px - projX;
        double diffZ = pz - projZ;
        return diffX * diffX + diffZ * diffZ;
    }

    private void breakNearbyPortalBlocks(ServerPlayerEntity player) {
        ServerWorld world = player.getWorld();
        BlockPos playerPos = player.getBlockPos();
        BlockPos nearestPortalPos = findNearestPortalBlock(world, playerPos);

        if (nearestPortalPos != null && playerPos.isWithinDistance(nearestPortalPos, 2.5)) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 200, 3, false, false));

            for (int x = -1; x <= 3; x++) {
                for (int y = -1; y <= 3; y++) {
                    for (int z = -1; z <= 3; z++) {
                        BlockPos offsetPos = nearestPortalPos.add(x, y, z);
                        if (world.getBlockState(offsetPos).getBlock() instanceof NetherPortalBlock ||
                                world.getBlockState(offsetPos).getBlock() == Blocks.OBSIDIAN) {
                            world.setBlockState(offsetPos, Blocks.AIR.getDefaultState());
                            if (random.nextBoolean()) {
                                world.setBlockState(offsetPos, Blocks.CRYING_OBSIDIAN.getDefaultState());
                            }
                            spawnFireParticles(world, offsetPos, 15);
                            LOGGER.info("BORDER: Portal block at {} broken", offsetPos.toShortString());
                        }
                    }
                }
            }
        }
    }

    private BlockPos findNearestPortalBlock(ServerWorld world, BlockPos playerPos) {
        BlockPos nearestPortalPos = null;
        double nearestDistance = 16 * 16;

        for (int x = -10; x <= 10; x++) {
            for (int y = -11; y <= 10; y++) {
                for (int z = -10; z <= 10; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (world.getBlockState(pos).getBlock() instanceof NetherPortalBlock) {
                        double distance = playerPos.getSquaredDistance(pos);
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearestPortalPos = pos;
                        }
                    }
                }
            }
        }
        return nearestPortalPos;
    }

    private void spawnFireParticles(ServerWorld world, BlockPos pos, int count) {
        for (int i = 0; i < count; i++) {
            double offsetX = random.nextDouble() - 0.5;
            double offsetY = random.nextDouble();
            double offsetZ = random.nextDouble() - 0.5;
            world.spawnParticles(
                    ParticleTypes.SOUL_FIRE_FLAME,
                    pos.getX() + 0.5 + offsetX,
                    pos.getY() + 1.0 + offsetY,
                    pos.getZ() + 0.5 + offsetZ,
                    2,
                    1, 1, 1, 0.01
            );
        }
    }

    private void spawnAshParticles(ServerWorld world, BlockPos pos, int count) {
        for (int i = 0; i < count; i++) {
            double offsetX = random.nextDouble() - 0.5;
            double offsetY = random.nextDouble();
            double offsetZ = random.nextDouble() - 0.5;
            world.spawnParticles(
                    ParticleTypes.ASH,
                    pos.getX() + 0.5 + offsetX,
                    pos.getY() + 1.0 + offsetY,
                    pos.getZ() + 0.5 + offsetZ,
                    3,
                    3, 3, 3, 0.1
            );
        }
    }

    private void onBorderDeath(ServerPlayerEntity player) {
        String name = player.getGameProfile().getName();
        int count = deathCounts.getOrDefault(name, 0) + 1;
        deathCounts.put(name, count);

        saveDeathCounts();

        if (count >= 5) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                server.getCommandManager().executeWithPrefix(
                        server.getCommandSource(), "team join bad " + name
                );
            }
            deathCounts.put(name, 0);
            saveDeathCounts();
        }
    }

    private void loadDeathCounts() {
        try {
            if (DEATHS_FILE.exists()) {
                try (FileReader reader = new FileReader(DEATHS_FILE)) {
                    Type type = new TypeToken<Map<String, Integer>>() {}.getType();
                    deathCounts = GSON.fromJson(reader, type);
                    if (deathCounts == null) deathCounts = new HashMap<>();
                }
            }
        } catch (Exception e) {
            LOGGER.error("BORDER: Ошибка при загрузке deaths.json", e);
            deathCounts = new HashMap<>();
        }
    }

    private void saveDeathCounts() {
        try {
            DEATHS_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(DEATHS_FILE)) {
                GSON.toJson(deathCounts, writer);
            }
        } catch (Exception e) {
            LOGGER.error("BORDER: Ошибка при сохранении deaths.json", e);
        }
    }
}
