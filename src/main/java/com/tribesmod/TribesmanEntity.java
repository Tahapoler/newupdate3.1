package com.tribesmod;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class TribesmanEntity extends PathfinderMob {
    public static final int FARMER = 0, WOODCUTTER = 1, TRADER = 2, WARRIOR = 3, MINER = 4, BUILDER = 5, ARCHER = 6;
    public static final String[] ROLE_NAMES = {"Farmer", "Woodcutter", "Trader", "Warrior", "Miner", "Builder", "Archer"};

    private static final EntityDataAccessor<Integer> TRIBE =
            SynchedEntityData.defineId(TribesmanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ROLE =
            SynchedEntityData.defineId(TribesmanEntity.class, EntityDataSerializers.INT);

    private static final String[][] LINES = {
            {"The harvest looks good this year.", "Wheat, carrots, potatoes - we grow it all.", "Stay away from the creepers, friend."},
            {"Every tree I cut, I replant.", "We need more wood for the houses.", "Mind the falling logs!"},
            {"I trade with every tribe that wants peace.", "Got emeralds? I have goods.", "Roads make us rich."},
            {"I guard these people with my life.", "Monsters will not pass while I stand.", "Do not attack my tribe."},
            {"The deeper you dig, the better the ore.", "Found iron again today.", "Torches every few blocks - safety first."},
            {"One block at a time, that is how villages grow.", "Bring me wood and stone, I will build.", "This house will stand for ages."},
            {"I see everything from up here.", "Nothing gets past my arrows.", "Keep your head down when the skeletons come."}
    };

    private int gearVer = -1;
    private int xp = 0;
    private String baseName = "", status = "";
    private BlockPos post;                 // archers: guard post on a watchtower
    private double lastX, lastZ;
    private int still, wallTicks;

    public TribesmanEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation g) g.setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(TRIBE, 0);
        builder.define(ROLE, 0);
    }

    public int getTribeId() { return entityData.get(TRIBE); }
    public int getRole() { return Math.max(0, Math.min(6, entityData.get(ROLE))); }

    public boolean isFighter() { return getRole() == WARRIOR || getRole() == ARCHER; }

    // ---------- what am I doing? (shown above the head and in /tribes watch) ----------
    public void setStatus(String s) {
        if (s == null) s = "";
        if (s.equals(status)) return;
        status = s;
        if (!level().isClientSide && TribeConfig.SHOW_STATUS && !baseName.isEmpty()) {
            setCustomName(Component.literal(baseName + (s.isEmpty() ? "" : " \u00a77\u00b7 " + s)));
        }
    }

    public String status() { return status; }
    public BlockPos post() { return post; }
    public void setPost(BlockPos p) { post = p; }

    public String describe() {
        LivingEntity tg = getTarget();
        return String.format("%s | skill %d | HP %.0f/%.0f | doing: %s | target: %s | at %d %d %d | stuck %ds%s",
                ROLE_NAMES[getRole()], skill(), getHealth(), getMaxHealth(),
                status.isEmpty() ? "idle" : status,
                tg == null ? "-" : tg.getName().getString(),
                getBlockX(), getBlockY(), getBlockZ(), still,
                post == null ? "" : " | guard post " + post.getX() + " " + post.getY() + " " + post.getZ());
    }

    public Tribe tribe() {
        if (level() instanceof ServerLevel sl) return TribeData.get(sl).tribes.get(getTribeId());
        return null;
    }

    // ---------- skill ----------
    public void addXp(int n) { xp += n; }
    public int skill() { return Math.min(10, 1 + (int) Math.sqrt(xp / 8.0)); }

    public void setupTribe(Tribe t, int role) {
        entityData.set(TRIBE, t.id);
        entityData.set(ROLE, role);
        baseName = t.name + " " + ROLE_NAMES[getRole()];
        setCustomName(Component.literal(baseName));
        setCustomNameVisible(true);
        setPersistenceRequired();
        gearVer = t.stage * 100 + t.level;
        equip(t);
        applyStats(t, true);
    }

    // ---------- equipment ----------
    private void hand(Item i) { setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(i)); }

    /** Put the role's normal gear back into the hand (after building, eating, ...). */
    public void refreshGear() {
        Tribe t = tribe();
        if (t != null) equip(t);
    }

    /** Hold the block that is being placed, like a real player. */
    public void hold(BlockState s) {
        Item it = s.getBlock().asItem();
        setItemSlot(EquipmentSlot.MAINHAND, it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it));
    }

    /** Gear follows the tribe's technology: stone -> iron -> diamond -> netherite. */
    private void equip(Tribe t) {
        int tier = t.tier();
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        Item[][] armor = {
                {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
                {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
                {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS},
                {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS}};
        switch (getRole()) {
            case FARMER -> hand(new Item[]{Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE}[tier]);
            case WOODCUTTER -> hand(new Item[]{Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE}[tier]);
            case MINER -> {
                hand(new Item[]{Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE}[tier]);
                setItemSlot(EquipmentSlot.HEAD, new ItemStack(armor[Math.max(1, tier)][0])); // mining helmet
            }
            case BUILDER -> {
                hand(Items.SPRUCE_PLANKS);
                setItemSlot(EquipmentSlot.HEAD, new ItemStack(armor[Math.min(1, tier)][0])); // hard hat
            }
            case TRADER -> hand(Items.EMERALD);
            case ARCHER -> {
                hand(Items.BOW);
                int at = Math.max(0, tier - 1);
                for (int i = 0; i < 4; i++) setItemSlot(slots[i], new ItemStack(armor[at][i]));
            }
            case WARRIOR -> {
                hand(new Item[]{Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD}[tier]);
                for (int i = 0; i < 4; i++) setItemSlot(slots[i], new ItemStack(armor[tier][i]));
                if (tier >= 1) setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            }
            default -> { }
        }
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) setDropChance(s, 0f);
    }

    /** Bigger, more advanced tribes have tougher people. */
    private void applyStats(Tribe t, boolean heal) {
        boolean w = getRole() == WARRIOR;
        AttributeInstance hp = getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.setBaseValue(20.0 + t.level * 1.5 + (w ? 8.0 + t.stage * 3.0 : 0.0));
        AttributeInstance dmg = getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(2.0 + t.level * 0.25 + (w ? t.stage * 0.5 : 0.0));
        if (heal) setHealth(getMaxHealth());
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && TribeConfig.STUCK_FIX && tickCount % 20 == 3) watchdog();
        if (!level().isClientSide && tickCount % 100 == 7) {
            Tribe t = tribe();
            if (t != null) {
                if (baseName.isEmpty()) {
                    baseName = t.name + " " + ROLE_NAMES[getRole()];
                    setCustomName(Component.literal(baseName));
                    setCustomNameVisible(true);
                }
                int v = t.stage * 100 + t.level;
                if (v != gearVer) {
                    gearVer = v;
                    equip(t);
                    applyStats(t, false);
                }
                if (getHealth() < getMaxHealth() && t.food > 0) heal(1.0f); // slow natural regeneration
            }
        }
    }

    /** Frees NPCs that are stuck: not moving, inside blocks, or far away from home. */
    private void watchdog() {
        Tribe t = tribe();
        if (t == null || !(level() instanceof ServerLevel sl) || sl.dimension() != Level.OVERWORLD) return;
        double dx = getX() - lastX, dz = getZ() - lastZ;
        boolean moved = dx * dx + dz * dz > 0.25;
        lastX = getX();
        lastZ = getZ();
        if (getNavigation().isInProgress() && !moved) still++; else still = 0;
        if (isInWall()) wallTicks++; else wallTicks = 0;
        double hx = getX() - t.center.getX(), hz = getZ() - t.center.getZ();
        double lim = t.territory() + 70.0;
        boolean far = post == null && hx * hx + hz * hz > lim * lim;
        boolean watched = sl.getNearestPlayer(this, 20.0) != null;

        if (still >= 8) {
            still = 0;
            getNavigation().stop();
            getJumpControl().jump();
            TribeLog.add(sl, baseName, "was stuck while: " + (status.isEmpty() ? "idle" : status));
            if (!watched) goHome(sl, t);
        } else if (wallTicks >= 3) {
            wallTicks = 0;
            TribeLog.add(sl, baseName, "was stuck inside blocks");
            if (!watched) goHome(sl, t); else moveTo(getX(), getY() + 1.2, getZ(), getYRot(), 0f);
        } else if (far) {
            TribeLog.add(sl, baseName, "wandered too far, going home");
            goHome(sl, t);
        }
    }

    private void goHome(ServerLevel sl, Tribe t) {
        int x = t.center.getX() + random.nextInt(9) - 4;
        int z = t.center.getZ() + random.nextInt(9) - 4;
        getNavigation().stop();
        moveTo(x + 0.5, TribeManager.groundY(sl, x, z), z + 0.5, getYRot(), 0f);
    }

    // ---------- AI ----------
    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(0, new OpenDoorGoal(this, true));
        goalSelector.addGoal(1, new TribeGoals.EatGoal(this));
        goalSelector.addGoal(1, new AvoidEntityGoal<Creeper>(this, Creeper.class, 8.0f, 1.0, 1.25));
        goalSelector.addGoal(2, new TribeGoals.ArcherGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, true) {
            @Override public boolean canUse() { return getRole() != ARCHER && super.canUse(); }
            @Override public boolean canContinueToUse() { return getRole() != ARCHER && super.canContinueToUse(); }
        });
        goalSelector.addGoal(3, new TribeGoals.NetherGoal(this));
        goalSelector.addGoal(3, new TribeGoals.ExpeditionGoal(this));
        goalSelector.addGoal(3, new TribeGoals.RaidGoal(this));
        goalSelector.addGoal(3, new TribeGoals.TradeGoal(this));
        goalSelector.addGoal(4, new TribeGoals.CollectItemsGoal(this));
        goalSelector.addGoal(5, new TribeGoals.BuildGoal(this));
        goalSelector.addGoal(5, new TribeGoals.WorkGoal(this));
        goalSelector.addGoal(5, new TribeGoals.MineGoal(this));
        goalSelector.addGoal(5, new TribeGoals.AnimalGoal(this));
        goalSelector.addGoal(5, new TribeGoals.FishGoal(this));
        goalSelector.addGoal(6, new TribeGoals.PostGoal(this));
        goalSelector.addGoal(6, new TribeGoals.NightGoal(this));
        goalSelector.addGoal(6, new TribeGoals.PatrolGoal(this));
        goalSelector.addGoal(7, new TribeGoals.ReturnHomeGoal(this));
        goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(10, new RandomLookAroundGoal(this));
        goalSelector.addGoal(11, new TribeGoals.HelpGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, LivingEntity.class, 10, true, false, this::shouldTarget));
    }

    private boolean shouldTarget(LivingEntity e) {
        if (e instanceof Creeper) return false; // run away from those, do not melee them
        if (e instanceof Monster) {
            // warriors hunt monsters; everyone else only fights what is already close
            return isFighter() || distanceToSqr(e) < 36.0;
        }
        if (e instanceof TribesmanEntity o && isFighter() && o.getTribeId() != getTribeId()) {
            Tribe t = tribe();
            return t != null && t.enemies.contains(o.getTribeId());
        }
        return false;
    }

    @Override
    public void die(DamageSource src) {
        super.die(src);
        if (level() instanceof ServerLevel sl) {
            TribeData data = TribeData.get(sl);
            Tribe t = data.tribes.get(getTribeId());
            if (t != null) {
                t.pop = Math.max(0, t.pop - 1);
                if (src.getEntity() instanceof TribesmanEntity k) {
                    Tribe kt = data.tribes.get(k.getTribeId());
                    if (kt != null) { kt.wealth += 5; kt.food += 5; }
                }
                data.setDirty();
            }
        }
    }

    // ---------- talking and trading with players ----------
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            Tribe t = tribe();
            if (t != null) {
                if (!(TribeConfig.PLAYER_TRADE && trade(player, t))) {
                    player.displayClientMessage(Component.literal(String.format(
                            "%s | Lv %d | %s | Pop %d | Food %d | Wood %d | Wealth %d | Iron %d | Diamond %d | Wars: %d",
                            t.name, t.level, TribeConfig.STAGES[Math.max(0, Math.min(6, t.stage))],
                            t.pop, t.food, t.wood, t.wealth, t.iron, t.diamond, t.enemies.size())), true);
                    String[] lines = LINES[getRole()];
                    player.sendSystemMessage(Component.literal("§7<" + t.name + " " + ROLE_NAMES[getRole()]
                            + " (skill " + skill() + ")> §f" + lines[random.nextInt(lines.length)]
                            + (TribeConfig.PLAYER_TRADE ? " §8[right-click with an emerald to trade]" : "")));
                }
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    private void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** Emerald trading. Returns true when the player held an emerald (trade done or refused). */
    private boolean trade(Player p, Tribe t) {
        ItemStack held = p.getMainHandItem();
        if (!held.is(Items.EMERALD)) return false;
        int cost = 1;
        ItemStack goods = ItemStack.EMPTY;
        boolean stock = false;
        switch (getRole()) {
            case FARMER -> { goods = new ItemStack(Items.BREAD, 8); stock = t.food >= 24; if (stock) t.food -= 24; }
            case WOODCUTTER -> { goods = new ItemStack(Items.OAK_LOG, 16); stock = t.wood >= 40; if (stock) t.wood -= 40; }
            case MINER -> { goods = new ItemStack(Items.IRON_INGOT, 6); stock = t.iron >= 12; if (stock) t.iron -= 12; }
            case TRADER -> { goods = new ItemStack(Items.EXPERIENCE_BOTTLE, 3); stock = t.wealth >= 30; if (stock) t.wealth -= 30; }
            case BUILDER -> { goods = new ItemStack(Items.COBBLESTONE, 32); stock = t.stone >= 48; if (stock) t.stone -= 48; }
            case ARCHER -> { goods = new ItemStack(Items.ARROW, 16); stock = t.iron >= 6; if (stock) t.iron -= 6; }
            case WARRIOR -> {
                cost = 3;
                goods = new ItemStack(t.tier() >= 1 ? Items.IRON_SWORD : Items.STONE_SWORD);
                stock = t.iron >= 10;
                if (stock) t.iron -= 10;
            }
            default -> { }
        }
        if (held.getCount() < cost) {
            p.sendSystemMessage(Component.literal("§7<" + t.name + "> §fThat is not enough - I want " + cost + " emerald(s)."));
            return true;
        }
        if (!stock) {
            p.sendSystemMessage(Component.literal("§7<" + t.name + "> §fSorry, we are out of stock. Come back later."));
            return true;
        }
        held.shrink(cost);
        give(p, goods);
        t.wealth += 15 * cost;
        if (level() instanceof ServerLevel sl) TribeData.get(sl).setDirty();
        p.sendSystemMessage(Component.literal("§a<" + t.name + "> §fPleasure doing business!"));
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("TribeId", getTribeId());
        tag.putInt("TribeRole", getRole());
        tag.putInt("TribeXp", xp);
        if (post != null) {
            tag.putInt("PostX", post.getX());
            tag.putInt("PostY", post.getY());
            tag.putInt("PostZ", post.getZ());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(TRIBE, tag.getInt("TribeId"));
        entityData.set(ROLE, tag.getInt("TribeRole"));
        xp = tag.getInt("TribeXp");
        if (tag.contains("PostX")) post = new BlockPos(tag.getInt("PostX"), tag.getInt("PostY"), tag.getInt("PostZ"));
    }
}
