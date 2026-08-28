package me.danialisntcool.gltfapi.test;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

public final class TestCharacter extends PathfinderMob {
    private String currentAnimation = "Idle";
    private int animationStartTick;

    public TestCharacter(EntityType<? extends TestCharacter> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 16.0D);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new PanicGoal(this, 1.5D));
        goalSelector.addGoal(5, new RandomStrollGoal(this, 1.0D));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();
        String nextAnimation = selectAnimation();
        if (!nextAnimation.equals(currentAnimation)) {
            currentAnimation = nextAnimation;
            animationStartTick = tickCount;
        }
    }

    public String currentAnimation() {
        return currentAnimation;
    }

    public float animationTime(float partialTick) {
        return Math.max(0.0F, tickCount + partialTick - animationStartTick) / 20.0F;
    }

    private String selectAnimation() {
        if (isDeadOrDying()) {
            return "Loose";
        }
        if (!onGround()) {
            return "Jump";
        }
        double speed = getDeltaMovement().horizontalDistanceSqr();
        if (speed > 0.018D) {
            return "Run";
        }
        if (speed > 0.0004D) {
            return "Walk";
        }
        return "Idle";
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
