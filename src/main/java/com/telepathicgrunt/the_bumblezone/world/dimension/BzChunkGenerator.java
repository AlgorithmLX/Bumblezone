package com.telepathicgrunt.the_bumblezone.world.dimension;

import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.telepathicgrunt.the_bumblezone.Bumblezone;
import com.telepathicgrunt.the_bumblezone.mixin.world.NoiseChunkAccessor;
import com.telepathicgrunt.the_bumblezone.mixin.world.NoiseGeneratorSettingsInvoker;
import com.telepathicgrunt.the_bumblezone.modinit.BzEntities;
import com.telepathicgrunt.the_bumblezone.utils.BzPlacingUtils;
import com.telepathicgrunt.the_bumblezone.utils.OpenSimplex2F;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;


public class BzChunkGenerator extends ChunkGenerator {

    public static final Codec<BzChunkGenerator> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryOps.retrieveRegistry(Registry.STRUCTURE_SET_REGISTRY).forGetter(bzChunkGenerator -> bzChunkGenerator.structureSets),
            RegistryOps.retrieveRegistry(Registry.NOISE_REGISTRY).forGetter(bzChunkGenerator -> bzChunkGenerator.noises),
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(bzChunkGenerator -> bzChunkGenerator.biomeSource),
            Codec.LONG.fieldOf("seed").orElse(0L).stable().forGetter(bzChunkGenerator -> bzChunkGenerator.seed),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(bzChunkGenerator -> bzChunkGenerator.settings),
            RegistryOps.retrieveRegistry(Registry.BIOME_REGISTRY).forGetter((bzChunkGenerator) -> bzChunkGenerator.biomeRegistry),
            RegistryOps.retrieveRegistry(Registry.CONFIGURED_STRUCTURE_FEATURE_REGISTRY).forGetter((bzChunkGenerator) -> bzChunkGenerator.configuredStructureFeaturesRegistry))
    .apply(instance, instance.stable(BzChunkGenerator::new)));

    private static final BlockState[] EMPTY_COLUMN = new BlockState[0];
    protected final BlockState defaultBlock;
    protected final BlockState defaultFluid;
    private final Registry<NormalNoise.NoiseParameters> noises;
    private final long seed;
    protected final Holder<NoiseGeneratorSettings> settings;
    private final NoiseRouter router;
    private final Climate.Sampler sampler;
    private final Registry<Biome> biomeRegistry;
    private final Registry<ConfiguredStructureFeature<?,?>> configuredStructureFeaturesRegistry;
    private final SurfaceSystem surfaceSystem;
    private final Aquifer.FluidPicker globalFluidPicker;
    private static final MobSpawnSettings.SpawnerData INITIAL_HONEY_SLIME_ENTRY = new MobSpawnSettings.SpawnerData(BzEntities.HONEY_SLIME.get(), 1, 1, 3);
    private static final MobSpawnSettings.SpawnerData INITIAL_BEE_ENTRY = new MobSpawnSettings.SpawnerData(EntityType.BEE, 1, 1, 4);
    private static final MobSpawnSettings.SpawnerData INITIAL_BEEHEMOTH_ENTRY = new MobSpawnSettings.SpawnerData(BzEntities.BEEHEMOTH.get(), 1, 1, 1);

    public BzChunkGenerator(Registry<StructureSet> structureSetRegistry, Registry<NormalNoise.NoiseParameters> parametersRegistry, BiomeSource biomeSource, long seed, Holder<NoiseGeneratorSettings> supplier, Registry<Biome> biomeRegistry, Registry<ConfiguredStructureFeature<?,?>> configuredStructureFeaturesRegistry) {
        this(structureSetRegistry, parametersRegistry, biomeSource, biomeSource, seed, supplier, biomeRegistry, configuredStructureFeaturesRegistry);
    }

    private BzChunkGenerator(Registry<StructureSet> structureSetRegistry, Registry<NormalNoise.NoiseParameters> parametersRegistry, BiomeSource biomeSource, BiomeSource biomeSource2, long seed, Holder<NoiseGeneratorSettings> supplier, Registry<Biome> biomeRegistry, Registry<ConfiguredStructureFeature<?,?>> configuredStructureFeaturesRegistry) {
        super(structureSetRegistry, Optional.empty(), biomeSource, biomeSource2, seed);
        this.noises = parametersRegistry;
        this.seed = seed;
        this.settings = supplier;
        this.biomeRegistry = biomeRegistry;
        NoiseGeneratorSettings noiseGeneratorSettings = this.settings.value();
        this.defaultBlock = noiseGeneratorSettings.defaultBlock();
        this.defaultFluid = noiseGeneratorSettings.defaultFluid();
        NoiseRouter noiseRouter = noiseGeneratorSettings.createNoiseRouter(parametersRegistry, seed);

        this.sampler = new Climate.Sampler(
                noiseRouter.temperature(),
                noiseRouter.humidity(),
                noiseRouter.continents(),
                noiseRouter.erosion(),
                noiseRouter.depth(),
                noiseRouter.ridges(),
                noiseRouter.spawnTarget());

        DensityFunction newFinalDensity = DensityFunctions.add(
                new BiomeNoise(this.climateSampler(), this.biomeRegistry, this.getBiomeSource()),
                noiseRouter.finalDensity()
        );
        newFinalDensity = DensityFunctions.interpolated(newFinalDensity);
        newFinalDensity = DensityFunctions.add(
                new RoughSurfaceNoise(),
                newFinalDensity
        );
        newFinalDensity = DensityFunctions.interpolated(newFinalDensity);

        this.router = new NoiseRouter(
                noiseRouter.barrierNoise(),
                noiseRouter.fluidLevelFloodednessNoise(),
                noiseRouter.fluidLevelSpreadNoise(),
                noiseRouter.lavaNoise(),
                noiseRouter.aquiferPositionalRandomFactory(),
                noiseRouter.oreVeinsPositionalRandomFactory(),
                noiseRouter.temperature(),
                noiseRouter.humidity(),
                noiseRouter.continents(),
                noiseRouter.erosion(),
                noiseRouter.depth(),
                noiseRouter.ridges(),
                noiseRouter.initialDensityWithoutJaggedness(),
                newFinalDensity,
                noiseRouter.veinToggle(),
                noiseRouter.veinRidged(),
                noiseRouter.veinGap(),
                noiseRouter.spawnTarget()
        );

        Aquifer.FluidStatus fluidStatus = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int seaLevel = noiseGeneratorSettings.seaLevel();
        Aquifer.FluidStatus fluidStatus2 = new Aquifer.FluidStatus(seaLevel, noiseGeneratorSettings.defaultFluid());
        this.globalFluidPicker = (j, k, lx) -> k < Math.min(-54, seaLevel) ? fluidStatus : fluidStatus2;
        this.surfaceSystem = new SurfaceSystem(parametersRegistry, this.defaultBlock, seaLevel, seed, noiseGeneratorSettings.getRandomSource());
        this.configuredStructureFeaturesRegistry = configuredStructureFeaturesRegistry;
    }

    public static void registerChunkGenerator() {
        Registry.register(Registry.CHUNK_GENERATOR, new ResourceLocation(Bumblezone.MODID, "chunk_generator"), BzChunkGenerator.CODEC);
    }

    record RoughSurfaceNoise() implements DensityFunction.SimpleFunction {
        private static final OpenSimplex2F noiseGen = new OpenSimplex2F(0);

        @Override
        public double compute(FunctionContext functionContext) {
            return (noiseGen.noise3_Classic(functionContext.blockX(), functionContext.blockY(), functionContext.blockZ()) / 100d) - 0.01d;
        }

        @Override
        public double minValue() {
            return 0;
        }

        @Override
        public double maxValue() {
            return 2;
        }

        @Override
        public Codec<? extends DensityFunction> codec() {
            return null;
        }
    }

    record BiomeNoise(Climate.Sampler sampler, Registry<Biome> biomeRegistry, BiomeSource biomeSource) implements DensityFunction.SimpleFunction {
        @Override
        public double compute(FunctionContext functionContext) {
            return BiomeInfluencedNoiseSampler.calculateBaseNoise(
                    functionContext.blockX(),
                    functionContext.blockZ(),
                    this.sampler,
                    this.biomeSource,
                    this.biomeRegistry);
        }

        @Override
        public double minValue() {
            return 0;
        }

        @Override
        public double maxValue() {
            return 2;
        }

        @Override
        public Codec<? extends DensityFunction> codec() {
            return null;
        }
    }

    @Override
    public Climate.Sampler climateSampler() {
        return this.sampler;
    }

    @Override
    public void applyCarvers(WorldGenRegion worldGenRegion, long seed, BiomeManager biomeManager, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess, GenerationStep.Carving carving) {}

    @Override
    protected Codec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGenerator withSeed(long seed) {
        return new BzChunkGenerator(this.structureSets, this.noises, this.biomeSource.withSeed(seed), seed, this.settings, this.biomeRegistry, this.configuredStructureFeaturesRegistry);
    }

    public boolean stable(long seed, ResourceKey<NoiseGeneratorSettings> resourceKey) {
        return this.seed == seed && this.settings.is(resourceKey);
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types types, LevelHeightAccessor levelHeightAccessor) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings();
        int maxY = Math.max(noiseSettings.minY(), levelHeightAccessor.getMinBuildHeight());
        int minY = Math.min(noiseSettings.minY() + noiseSettings.height(), levelHeightAccessor.getMaxBuildHeight());
        int maxYCell = Mth.intFloorDiv(maxY, noiseSettings.getCellHeight());
        int minYCell = Mth.intFloorDiv(minY - maxY, noiseSettings.getCellHeight());
        return minYCell <= 0 ? levelHeightAccessor.getMinBuildHeight() : this.iterateNoiseColumn(x, z, null, types.isOpaque(), maxYCell, minYCell).orElse(levelHeightAccessor.getMinBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor levelHeightAccessor) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings();
        int minY = Math.max(noiseSettings.minY(), levelHeightAccessor.getMinBuildHeight());
        int maxY = Math.min(noiseSettings.minY() + noiseSettings.height(), levelHeightAccessor.getMaxBuildHeight());
        int minYCell = Mth.intFloorDiv(minY, noiseSettings.getCellHeight());
        int maxYCell = Mth.intFloorDiv(maxY - minY, noiseSettings.getCellHeight());
        if (maxYCell <= 0) {
            return new NoiseColumn(minY, EMPTY_COLUMN);
        }
        else {
            BlockState[] blockStates = new BlockState[maxYCell * noiseSettings.getCellHeight()];
            this.iterateNoiseColumn(x, z, blockStates, null, minYCell, maxYCell);
            return new NoiseColumn(minY, blockStates);
        }
    }

    @Override
    public void addDebugScreenInfo(List<String> list, BlockPos blockPos) {}

    private OptionalInt iterateNoiseColumn(int x, int z, @Nullable BlockState[] blockStates, @Nullable Predicate<BlockState> predicate, int minYCell, int maxYCell) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings();
        int cellWidth = noiseSettings.getCellWidth();
        int cellHeight = noiseSettings.getCellHeight();
        int o = Math.floorDiv(x, cellWidth);
        int p = Math.floorDiv(z, cellWidth);
        int q = Math.floorMod(x, cellWidth);
        int r = Math.floorMod(z, cellWidth);
        int s = o * cellWidth;
        int t = p * cellWidth;
        double d = (double)q / (double)cellWidth;
        double e = (double)r / (double)cellWidth;
        NoiseChunk noiseChunk = NoiseChunk.forColumn(s, t, minYCell, maxYCell, this.router, this.settings.value(), this.globalFluidPicker);
        noiseChunk.initializeForFirstCellX();
        noiseChunk.advanceCellX(0);

        for(int currentYCell = maxYCell - 1; currentYCell >= 0; --currentYCell) {
            noiseChunk.selectCellYZ(currentYCell, 0);

            for(int yInCell = cellHeight - 1; yInCell >= 0; --yInCell) {
                int y = (minYCell + currentYCell) * cellHeight + yInCell;
                double f = (double)yInCell / (double)cellHeight;
                noiseChunk.updateForY(y, f);
                noiseChunk.updateForX(x, d);
                noiseChunk.updateForZ(z, e);
                BlockState blockState = ((NoiseChunkAccessor)noiseChunk).callGetInterpolatedState();
                BlockState blockState2 = blockState == null ? this.defaultBlock : blockState;
                if((blockState == null || blockState.isAir()) && y < getSeaLevel()) {
                    blockState2 = this.defaultFluid;
                }

                if (blockStates != null) {
                    int index = currentYCell * cellHeight + yInCell;
                    blockStates[index] = blockState2;
                }

                if (predicate != null && predicate.test(blockState2)) {
                    return OptionalInt.of(y + 1);
                }
            }
        }

        return OptionalInt.empty();
    }

    @Override
    public void buildSurface(WorldGenRegion worldGenRegion, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess) {
        if (!SharedConstants.debugVoidTerrain(chunkAccess.getPos())) {
            WorldGenerationContext worldGenerationContext = new WorldGenerationContext(this, worldGenRegion);
            NoiseGeneratorSettings noiseGeneratorSettings = this.settings.value();
            NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk(this.router, () -> new Beardifier(structureFeatureManager, chunkAccess), noiseGeneratorSettings, this.globalFluidPicker, Blender.of(worldGenRegion));
            this.surfaceSystem.buildSurface(worldGenRegion.getBiomeManager(), worldGenRegion.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY), noiseGeneratorSettings.useLegacyRandomSource(), worldGenerationContext, chunkAccess, noiseChunk, noiseGeneratorSettings.surfaceRule());
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess) {
        NoiseSettings noiseSettings = this.settings.value().noiseSettings();
        LevelHeightAccessor levelHeightAccessor = chunkAccess.getHeightAccessorForGeneration();
        int minY = Math.max(noiseSettings.minY(), levelHeightAccessor.getMinBuildHeight());
        int maxY = Math.min(noiseSettings.minY() + noiseSettings.height(), levelHeightAccessor.getMaxBuildHeight());
        int minYCell = Mth.intFloorDiv(minY, noiseSettings.getCellHeight());
        int maxYCell = Mth.intFloorDiv(maxY - minY, noiseSettings.getCellHeight());
        if (maxYCell <= 0) {
            return CompletableFuture.completedFuture(chunkAccess);
        }
        else {
            int maxChunkSection = chunkAccess.getSectionIndex(maxYCell * noiseSettings.getCellHeight() - 1 + minY);
            int minChunkSection = chunkAccess.getSectionIndex(minY);
            Set<LevelChunkSection> set = Sets.newHashSet();

            for(int currentChunkSection = maxChunkSection; currentChunkSection >= minChunkSection; --currentChunkSection) {
                LevelChunkSection levelChunkSection = chunkAccess.getSection(currentChunkSection);
                levelChunkSection.acquire();
                set.add(levelChunkSection);
            }

            return CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_fill_noise", () -> this.doFill(blender, structureFeatureManager, chunkAccess, minYCell, maxYCell)), Util.backgroundExecutor()).whenCompleteAsync((chunkAccessx, throwable) -> {
                for(LevelChunkSection levelChunkSectionx : set) {
                    levelChunkSectionx.release();
                }

            }, executor);
        }
    }

    private ChunkAccess doFill(Blender blender, StructureFeatureManager structureFeatureManager, ChunkAccess chunkAccess, int minYCell, int maxYCell) {
        NoiseGeneratorSettings noiseGeneratorSettings = this.settings.value();
        NoiseChunk noiseChunk = chunkAccess.getOrCreateNoiseChunk(this.router, () -> new Beardifier(structureFeatureManager, chunkAccess), noiseGeneratorSettings, this.globalFluidPicker, blender);
        Heightmap heightmap = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap heightmap2 = chunkAccess.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        ChunkPos chunkPos = chunkAccess.getPos();
        int k = chunkPos.getMinBlockX();
        int l = chunkPos.getMinBlockZ();
        Aquifer aquifer = noiseChunk.aquifer();
        noiseChunk.initializeForFirstCellX();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        NoiseSettings noiseSettings = noiseGeneratorSettings.noiseSettings();
        int m = noiseSettings.getCellWidth();
        int n = noiseSettings.getCellHeight();
        int o = 16 / m;
        int p = 16 / m;

        for(int q = 0; q < o; ++q) {
            noiseChunk.advanceCellX(q);

            for(int r = 0; r < p; ++r) {
                LevelChunkSection levelChunkSection = chunkAccess.getSection(chunkAccess.getSectionsCount() - 1);

                for(int s = maxYCell - 1; s >= 0; --s) {
                    noiseChunk.selectCellYZ(s, r);

                    for(int t = n - 1; t >= 0; --t) {
                        int yy = (minYCell + s) * n + t;
                        int v = yy & 15;
                        int w = chunkAccess.getSectionIndex(yy);
                        if (chunkAccess.getSectionIndex(levelChunkSection.bottomBlockY()) != w) {
                            levelChunkSection = chunkAccess.getSection(w);
                        }

                        double d = (double)t / (double)n;

                        for(int x = 0; x < m; ++x) {
                            int xx = k + q * m + x;
                            int z = xx & 15;
                            double e = (double)x / (double)m;
                            noiseChunk.updateForX(xx, e);

                            for(int aa = 0; aa < m; ++aa) {
                                int zz = l + r * m + aa;
                                int ac = zz & 15;
                                double f = (double)aa / (double)m;
                                noiseChunk.updateForZ(zz, f);
                                noiseChunk.updateForY(yy, d);

                                BlockState blockState = ((NoiseChunkAccessor)noiseChunk).callGetInterpolatedState();
                                if (blockState == null) {
                                    blockState = this.defaultBlock;
                                }

                                if (blockState.isAir() && yy < this.getSeaLevel()) {
                                    blockState = this.defaultFluid;
                                }

                                if (!blockState.isAir() && !SharedConstants.debugVoidTerrain(chunkAccess.getPos())) {
                                    if (blockState.getLightEmission() != 0 && chunkAccess instanceof ProtoChunk) {
                                        mutableBlockPos.set(xx, yy, zz);
                                        ((ProtoChunk)chunkAccess).addLight(mutableBlockPos);
                                    }

                                    levelChunkSection.setBlockState(z, v, ac, blockState, false);
                                    heightmap.update(z, yy, ac, blockState);
                                    heightmap2.update(z, yy, ac, blockState);
                                    if (aquifer.shouldScheduleFluidUpdate() && !blockState.getFluidState().isEmpty()) {
                                        mutableBlockPos.set(xx, yy, zz);
                                        chunkAccess.markPosForPostprocessing(mutableBlockPos);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            noiseChunk.swapSlices();
        }

        return chunkAccess;
    }

    @Override
    public int getGenDepth() {
        return this.settings.value().noiseSettings().height();
    }

    @Override
    public int getSeaLevel() {
        return this.settings.value().seaLevel();
    }

    @Override
    public int getMinY() {
        return this.settings.value().noiseSettings().minY();
    }

    /** @deprecated */
    @Deprecated
    public Optional<BlockState> topMaterial(CarvingContext carvingContext, Function<BlockPos, Holder<Biome>> function, ChunkAccess chunkAccess, NoiseChunk noiseChunk, BlockPos blockPos, boolean bl) {
        return this.surfaceSystem.topMaterial(this.settings.value().surfaceRule(), carvingContext, function, chunkAccess, noiseChunk, blockPos, bl);
    }

    /**
     * For spawning specific mobs in certain places like structures.
     */
    @Override
    public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> biome, StructureFeatureManager accessor, MobCategory group, BlockPos pos) {
        return super.getMobsAt(biome, accessor, group, pos);
    }

    /**
     * Dedicated to spawning slimes/bees when generating chunks initially.
     * This is so there's lots of bees and the slime can spawn despite the
     * slime having extremely restrictive spawning mechanics.
     * <p>
     * Also spawns bees with chance to bee full of pollen
     * <p>
     * This is mainly vanilla code but with biome$spawnlistentry changed to
     * use bee/slime and the restrictive terrain check called on the entity removed.
     * The height is also restricted so the mob cannot spawn on the ceiling of this
     * dimension as well.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void spawnOriginalMobs(WorldGenRegion region) {
        NoiseGeneratorSettings noiseGeneratorSettings = this.settings.value();
        if (!((NoiseGeneratorSettingsInvoker)(Object)noiseGeneratorSettings).thebumblezone_callDisableMobGeneration()) {
            ChunkPos chunkPos = region.getCenter();
            Biome biome = region.getBiome(chunkPos.getWorldPosition()).value();
            WorldgenRandom sharedseedrandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.seedUniquifier()));
            sharedseedrandom.setDecorationSeed(region.getSeed(), chunkPos.getMinBlockX(), chunkPos.getMinBlockZ());
            while (sharedseedrandom.nextFloat() < biome.getMobSettings().getCreatureProbability()) {
                //15% of time, spawn honey slime. Otherwise, spawn bees.
                MobSpawnSettings.SpawnerData biome$spawnlistentry;
                float threshold = sharedseedrandom.nextFloat();
                if(threshold < 0.15f) {
                    biome$spawnlistentry = INITIAL_HONEY_SLIME_ENTRY;
                }
                else if (threshold < 0.95f) {
                    biome$spawnlistentry = INITIAL_BEE_ENTRY;
                }
                else {
                    biome$spawnlistentry = INITIAL_BEEHEMOTH_ENTRY;
                }

                int startingX = chunkPos.getMinBlockX() + sharedseedrandom.nextInt(16);
                int startingZ = chunkPos.getMinBlockZ() + sharedseedrandom.nextInt(16);

                BlockPos.MutableBlockPos blockpos = new BlockPos.MutableBlockPos(startingX, 0, startingZ);
                int height = BzPlacingUtils.topOfSurfaceBelowHeight(region, sharedseedrandom.nextInt(255), -1, blockpos) + 1;

                if (biome$spawnlistentry.type.canSummon() && height > 0 && height < 255) {
                    float width = biome$spawnlistentry.type.getWidth();
                    double xLength = Mth.clamp(startingX, (double) chunkPos.getMinBlockX() + (double) width, (double) chunkPos.getMinBlockX() + 16.0D - (double) width);
                    double zLength = Mth.clamp(startingZ, (double) chunkPos.getMinBlockZ() + (double) width, (double) chunkPos.getMinBlockZ() + 16.0D - (double) width);

                    Entity entity = biome$spawnlistentry.type.create(region.getLevel());
                    if(entity == null)
                        continue;

                    entity.moveTo(xLength, height, zLength, sharedseedrandom.nextFloat() * 360.0F, 0.0F);
                    if (entity instanceof Mob mobEntity) {
                        if (mobEntity.checkSpawnRules(region, MobSpawnType.CHUNK_GENERATION) && mobEntity.checkSpawnObstruction(region)) {
                            mobEntity.finalizeSpawn(region, region.getCurrentDifficultyAt(new BlockPos(mobEntity.position())), MobSpawnType.CHUNK_GENERATION, null, null);
                            region.addFreshEntity(mobEntity);
                        }
                    }
                }
            }
        }
    }
}