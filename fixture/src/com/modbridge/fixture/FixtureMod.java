package com.modbridge.fixture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * ModBridge fixture mod — written strictly against 1.20.1 Forge idioms.
 * Exercises the whole rewrite surface: package renames, RegistryObject -> DeferredHolder,
 * FMLJavaModLoadingContext mod-bus lookup -> (IEventBus) constructor injection,
 * MinecraftForge.EVENT_BUS -> NeoForge.EVENT_BUS, and the removed
 * ResourceLocation constructors -> static factories.
 */
@Mod(FixtureMod.MODID)
public class FixtureMod {
    public static final String MODID = "modbridgefixture";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MODID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final RegistryObject<Item> MAGIC_ITEM =
            ITEMS.register("magic_item", () -> new Item(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Block> MAGIC_BLOCK =
            BLOCKS.register("magic_block", () -> new Block(BlockBehaviour.Properties.of().strength(1.5f)));
    public static final RegistryObject<Item> MAGIC_BLOCK_ITEM =
            ITEMS.register("magic_block_item", () -> new BlockItem(MAGIC_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<CreativeModeTab> MAGIC_TAB =
            TABS.register("magic_tab", () -> CreativeModeTab.builder()
                    .title(Component.literal("ModBridge Fixture"))
                    .icon(() -> new ItemStack(MAGIC_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(MAGIC_ITEM.get());
                        output.accept(MAGIC_BLOCK_ITEM.get());
                    })
                    .build());

    private static final Logger LOG = LogManager.getLogger("modbridgefixture");

    public FixtureMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        TABS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        LOG.info("[modbridge-fixture] constructed via 1.20.1 API");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("fixtureping").executes(ctx -> {
            String item = Aux.describe(MAGIC_ITEM, new ResourceLocation(MODID, "magic_item"));
            ctx.getSource().sendSuccess(() -> Component.literal("[modbridge-fixture] pong " + item), false);
            LOG.info("[modbridge-fixture] /fixtureping executed: {}", item);
            return 1;
        }));
        LOG.info("[modbridge-fixture] /fixtureping registered");
    }
}
