package com.telepathicgrunt.the_bumblezone;

import com.telepathicgrunt.the_bumblezone.capabilities.BzCapabilities;
import com.telepathicgrunt.the_bumblezone.client.BumblezoneClient;
import com.telepathicgrunt.the_bumblezone.configs.BzBeeAggressionConfigs;
import com.telepathicgrunt.the_bumblezone.configs.BzClientConfigs;
import com.telepathicgrunt.the_bumblezone.configs.BzDimensionConfigs;
import com.telepathicgrunt.the_bumblezone.configs.BzGeneralConfigs;
import com.telepathicgrunt.the_bumblezone.configs.BzModCompatibilityConfigs;
import com.telepathicgrunt.the_bumblezone.configs.BzWorldgenConfigs;
import com.telepathicgrunt.the_bumblezone.effects.HiddenEffect;
import com.telepathicgrunt.the_bumblezone.enchantments.CombCutterEnchantment;
import com.telepathicgrunt.the_bumblezone.enchantments.NeurotoxinsEnchantment;
import com.telepathicgrunt.the_bumblezone.entities.BeeAggression;
import com.telepathicgrunt.the_bumblezone.entities.EnderpearlImpact;
import com.telepathicgrunt.the_bumblezone.entities.EntityTeleportationBackend;
import com.telepathicgrunt.the_bumblezone.entities.EntityTeleportationHookup;
import com.telepathicgrunt.the_bumblezone.entities.WanderingTrades;
import com.telepathicgrunt.the_bumblezone.items.dispenserbehavior.DispenserItemSetup;
import com.telepathicgrunt.the_bumblezone.modcompat.ModChecker;
import com.telepathicgrunt.the_bumblezone.modcompat.ModdedBeesBeesSpawning;
import com.telepathicgrunt.the_bumblezone.modinit.*;
import com.telepathicgrunt.the_bumblezone.packets.MessageHandler;
import com.telepathicgrunt.the_bumblezone.world.dimension.BzDimension;
import com.telepathicgrunt.the_bumblezone.world.dimension.BzWorldSavedData;
import com.telepathicgrunt.the_bumblezone.world.surfacerules.PollinatedSurfaceSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Bumblezone.MODID)
public class Bumblezone{

    public static final String MODID = "the_bumblezone";
    public static final ResourceLocation MOD_DIMENSION_ID = new ResourceLocation(Bumblezone.MODID, Bumblezone.MODID);
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public Bumblezone() {
        BzTags.initTags();
        BzBiomeHeightRegistry.initBiomeHeightRegistry();

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addGenericListener(StructureFeature.class, BzStructures::setupStructures);

        //Events
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(BeeAggression::pickupItemAnger);
        forgeBus.addListener(EventPriority.LOWEST, BeeAggression::minedBlockAnger); // We want to make sure the block will be broken for angering bees
        forgeBus.addListener(WanderingTrades::addWanderingTrades);
        forgeBus.addListener(CombCutterEnchantment::attemptFasterMining);
        forgeBus.addListener(EventPriority.HIGH, EnderpearlImpact::onPearlHit); // High because we want to cancel other mod's impact checks and stuff if it hits a hive.
        forgeBus.addListener(EntityTeleportationHookup::entityTick);
        forgeBus.addListener(BeeAggression::playerTick);
        forgeBus.addListener(BzWorldSavedData::worldTick);
        forgeBus.addListener(EntityTeleportationBackend::entityChangingDimension);
        forgeBus.addListener(ModdedBeesBeesSpawning::MobSpawnEvent);
        forgeBus.addListener(HiddenEffect::hideEntity);
        forgeBus.addListener(NeurotoxinsEnchantment::entityHurtEvent);
        forgeBus.addListener(this::serverAboutToStart);

        //Registration
        modEventBus.addListener(EventPriority.NORMAL, this::setup);
        modEventBus.addListener(EventPriority.LOWEST, this::modCompatSetup); //run after all mods
        modEventBus.addListener(EventPriority.NORMAL, BzEntities::registerEntityAttributes);
        BzItems.ITEMS.register(modEventBus);
        BzBlocks.BLOCKS.register(modEventBus);
        BzFluids.FLUIDS.register(modEventBus);
        BzPOI.POI_TYPES.register(modEventBus);
        BzItems.RECIPES.register(modEventBus);
        BzMenuTypes.MENUS.register(modEventBus);
        BzEffects.EFFECTS.register(modEventBus);
        BzFeatures.FEATURES.register(modEventBus);
        BzEntities.ENTITIES.register(modEventBus);
        BzSounds.SOUND_EVENTS.register(modEventBus);
        BzStructures.STRUCTURES.register(modEventBus);
        BzParticles.PARTICLE_TYPES.register(modEventBus);
        BzEnchantments.ENCHANTMENTS.register(modEventBus);
        BzBlockEntities.BLOCK_ENTITIES.register(modEventBus);

        BzCapabilities.setupCapabilities();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> BumblezoneClient::subscribeClientEvents);

        // generates/handles config
        FileUtils.getOrCreateDirectory(FMLPaths.CONFIGDIR.get().resolve("the_bumblezone"), "the_bumblezone");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, BzClientConfigs.GENERAL_SPEC, "the_bumblezone/client.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BzGeneralConfigs.GENERAL_SPEC, "the_bumblezone/general.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BzWorldgenConfigs.GENERAL_SPEC, "the_bumblezone/worldgen.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BzDimensionConfigs.GENERAL_SPEC, "the_bumblezone/dimension.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BzBeeAggressionConfigs.GENERAL_SPEC, "the_bumblezone/bee_aggression.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BzModCompatibilityConfigs.GENERAL_SPEC, "the_bumblezone/mod_compatibility.toml");
    }

    private void setup(final FMLCommonSetupEvent event) {
    	event.enqueueWork(() -> {
            BzPredicates.registerPredicates();
            BzLootFunctionTypes.registerContainerLootFunctions();
            BzPlacements.registerPlacements();
            BzCriterias.registerCriteriaTriggers();
            BzProcessors.registerProcessors();
			BzDimension.setupDimension();
			BzEntities.registerAdditionalEntityInformation();
            BzSurfaceRules.registerSurfaceRules();
            BeeAggression.setupBeeHatingList();
		});
        MessageHandler.init();
    }

    private void modCompatSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Dispenser isn't synchronized. Needs to be enqueueWork to prevent crash if
            // another mod registers to it at the same exact time.
            DispenserItemSetup.setupDispenserBehaviors();

            // should run after most other mods just in case
            ModChecker.setupModCompat();
        });
    }

    private void serverAboutToStart(final ServerAboutToStartEvent event) {
        PollinatedSurfaceSource.RandomLayerStateRule.initNoise(event.getServer().getWorldData().worldGenSettings().seed());
    }
}
