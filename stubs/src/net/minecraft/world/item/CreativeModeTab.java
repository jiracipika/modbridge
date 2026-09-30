package net.minecraft.world.item;

import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ItemLike;

/** Vanilla API stub (real class: net.minecraft.world.item.CreativeModeTab). */
public class CreativeModeTab {
    public static Builder builder() { return null; }
    public static class Builder {
        public Builder title(Component title) { return this; }
        public Builder icon(Supplier<ItemStack> icon) { return this; }
        public Builder displayItems(DisplayItemsGenerator generator) { return this; }
        public CreativeModeTab build() { return null; }
    }
    public interface DisplayItemsGenerator {
        void accept(ItemDisplayParameters parameters, Output output);
    }
    public interface Output {
        void accept(ItemLike item);
    }
    public static class ItemDisplayParameters {
    }
}
