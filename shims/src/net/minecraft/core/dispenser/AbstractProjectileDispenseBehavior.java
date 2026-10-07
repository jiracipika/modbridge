package net.minecraft.core.dispenser;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;

/**
 * ModBridge shim carrier (dispenser lane, phase 5.15): Forge 1.20.1's
 * {@code AbstractProjectileDispenseBehavior} — the abstract extend-and-override
 * TEMPLATE every custom dispenser projectile behavior extended — does not exist on
 * NeoForge 1.21.1 (javap-verified). 21.1 replaced the template-method pattern with
 * the data-driven {@link ProjectileDispenseBehavior}(Item): it requires the ITEM to
 * implement {@code ProjectileItem} ({@code asProjectile(Level,Position,ItemStack,
 * Direction)}) and carries a {@code ProjectileItem.DispenseConfig}
 * (positionFunction/uncertainty/power/overrideDispenseEvent) record — a mod behavior
 * overriding {@code getProjectile} to build a CUSTOM entity (with mod-owned extra
 * state) has no mechanical successor there.
 *
 * <p>This shim is that successor, mechanical for the mod: it extends the REAL 21.1
 * {@link DefaultDispenseItemBehavior} and reimplements the 1.20.1 template
 * (bytecode-measured over the 1.20.1 srg server jar) over the 21.1 surface, keeping
 * the 1.20.1 hook surface translated subclasses override (srg ids -> mojmap names,
 * identical internal types on both sides — descriptors do not drift):
 * <pre>
 *   1.20.1 srg                          21.1 (this shim + the real surface)
 *   m_7498_(BlockSource,ItemStack)      execute — public override of the protected
 *                                         DefaultDispenseItemBehavior.execute (the
 *                                         final m_6115_ dispense() template calls it)
 *   DispenserBlock.m_52720_(source)     DispenserBlock.getDispensePosition(source)
 *                                         (STILL a 21.1 public static — used, not
 *                                         source.center(), exactly as 1.20.1 did)
 *   state().m_61143_(f_52659_)          state().getValue(DispenserBlock.FACING)
 *   m_6895_(Level,Position,ItemStack)   getProjectile — ABSTRACT hook, implemented
 *                                         by every translated subclass
 *   m_7104_() / m_7101_()               getPower (1.1F) / getUncertainty (6.0F)
 *                                         — concrete defaults, override as on 1.20.1
 *   m_6823_(BlockSource)                playSound — levelEvent(1002, pos, 0), the
 *                                         same dispenser-shoot event id
 *   Projectile.m_6686_(DDDFF)           Projectile.shoot(double,double,double,
 *                                         float,float) — with the 1.20.1 +0.1F
 *                                         Y-bias (i2f/fadd/f2d, bytecode-exact)
 *   Level.m_7967_ / ItemStack.m_41774_  addFreshEntity (pop) / shrink(1)
 * </pre>
 *
 * <p>The 21.1 data-driven class differs semantically (documented, not bridged):
 * its position comes from the item's DispenseConfig positionFunction and its
 * shot direction comes straight from the facing (no +0.1F Y-bias), both resolved
 * PER-ITEM; this shim reproduces the 1.20.1 SHARED template so translated
 * extend-and-override behaviors keep their exact 1.20.1 firing semantics.
 *
 * <p>Measured reference surface across the trial jars (constant-pool census, both):
 * exactly three supplementaries classes — {@code BombsBehavior} (overrides
 * getProjectile/getUncertainty/getPower), {@code DispenserBehaviorsManager$2}
 * (overrides getProjectile only), and the {@code mixins/
 * AbstractProjectileBehaviorAccessor} @Mixin (4 @Invoker proxies with EXPLICIT
 * values getUncertainty/getPower/getProjectile/playSound — they resolve against
 * this shim's members by value at mixin-apply time; moonlight has zero).
 * Registration flows through the translated behavior instances being plain
 * {@code DispenseItemBehavior}s (IS-A via DefaultDispenseItemBehavior), which is
 * what vanilla's {@code DispenserBlock.DISPENSER_REGISTRY} dispatches.
 *
 * <p>Carried exactly; nothing else. Relocated per-mod to
 * {@code com/modbridge/shim/<modid>/} like every shim (the typerename rule
 * rewrites superclass refs, ctor calls, the @Mixin annotation class literal and
 * the frame/local types in one pass).
 */
public abstract class AbstractProjectileDispenseBehavior extends DefaultDispenseItemBehavior {

    public AbstractProjectileDispenseBehavior() {
    }

    /** the 1.20.1 template (m_7498_), bytecode-exact over the 21.1 surface. */
    @Override
    public ItemStack execute(BlockSource source, ItemStack stack) {
        ServerLevel level = source.level();
        Position position = DispenserBlock.getDispensePosition(source);
        Direction direction = source.state().getValue(DispenserBlock.FACING);
        Projectile projectile = this.getProjectile(level, position, stack);
        projectile.shoot(direction.getStepX(), direction.getStepY() + 0.1F, direction.getStepZ(),
                this.getPower(), this.getUncertainty());
        level.addFreshEntity(projectile);
        stack.shrink(1);
        return stack;
    }

    /** 1.20.1 m_6823_: the dispenser-shoot level event. */
    @Override
    protected void playSound(BlockSource source) {
        source.level().levelEvent(1002, source.pos(), 0);
    }

    /** 1.20.1 m_6895_ (abstract): build the projectile — the hook mods override. */
    protected abstract Projectile getProjectile(Level level, Position position, ItemStack stack);

    /** 1.20.1 m_7101_: spread; 1.20.1 default 6.0F. */
    protected float getUncertainty() {
        return 6.0F;
    }

    /** 1.20.1 m_7104_: launch power; 1.20.1 default 1.1F. */
    protected float getPower() {
        return 1.1F;
    }
}
