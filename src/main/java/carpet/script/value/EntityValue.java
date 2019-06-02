package carpet.script.value;

import carpet.fakes.MobEntityInterface;
import carpet.script.exception.InternalExpressionException;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.Tag;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.EntitySelectorReader;
import net.minecraft.command.arguments.NbtPathArgumentType;
import net.minecraft.entity.mob.MobEntityWithAi; // EntityCreature;
import net.minecraft.entity.mob.MobEntity; //LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoToWalkTargetGoal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.network.packet.EntityVelocityUpdateS2CPacket;
import net.minecraft.potion.Potion;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.network.chat.TextComponent;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class EntityValue extends Value
{
    private Entity entity;

    public EntityValue(Entity e)
    {
        entity = e;
    }

    private static Map<String, EntitySelector> selectorCache = new HashMap<>();
    public static Collection<? extends Entity > getEntitiesFromSelector(ServerCommandSource source, String selector)
    {
        try
        {
            EntitySelector entitySelector = selectorCache.get(selector);
            if (entitySelector != null)
            {
                return entitySelector.getEntities(source);
            }
            entitySelector = new EntitySelectorReader(new StringReader(selector), true).build();
            selectorCache.put(selector, entitySelector);
            return entitySelector.getEntities(source);
        }
        catch (CommandSyntaxException e)
        {
            throw new InternalExpressionException("Cannot select entities from "+selector);
        }
    }

    public Entity getEntity()
    {
        return entity;
    }

    @Override
    public String getString()
    {
        return entity.getDisplayName().getString();
    }

    @Override
    public boolean getBoolean()
    {
        return true;
    }

    @Override
    public boolean equals(Value v)
    {
        if (v instanceof EntityValue)
        {
            return entity.getEntityId()==((EntityValue) v).entity.getEntityId();
        }
        return super.equals(v);
    }

    @Override
    public Value in(Value v)
    {
        String what = v.getString();
        return this.get(what, null);
    }

    public static Pair<EntityType<?>, Predicate<? super Entity>> getPredicate(String who)
    {
        Pair<EntityType<?>, Predicate<? super Entity>> res = entityPredicates.get(who);
        if (res != null) return res;
        return res; //TODO add more here like search by tags, or type
        //if (who.startsWith('tag:'))
    }
    private static Map<String, Pair<EntityType<?>, Predicate<? super Entity>>> entityPredicates =
            new HashMap<String, Pair<EntityType<?>, Predicate<? super Entity>>>()
    {{
        put("*", Pair.of(null, EntityPredicates.VALID_ENTITY));
        put("living", Pair.of(null, (e) -> e instanceof LivingEntity && e.isAlive()));
        put("items", Pair.of(EntityType.ITEM, EntityPredicates.VALID_ENTITY));
        put("players", Pair.of(EntityType.PLAYER, EntityPredicates.VALID_ENTITY));
        put("!players", Pair.of(null, (e) -> !(e instanceof PlayerEntity) ));
    }};
    public Value get(String what, Value arg)
    {
        if (!(featureAccessors.containsKey(what)))
            throw new InternalExpressionException("unknown feature of entity: "+what);
        return featureAccessors.get(what).apply(entity, arg);
    }
    private static Map<String, EquipmentSlot> inventorySlots = new HashMap<String, EquipmentSlot>(){{
        put("mainhand", EquipmentSlot.MAINHAND);
        put("offhand", EquipmentSlot.OFFHAND);
        put("head", EquipmentSlot.HEAD);
        put("chest", EquipmentSlot.CHEST);
        put("legs", EquipmentSlot.LEGS);
        put("feet", EquipmentSlot.FEET);
    }};
    private static Map<String, BiFunction<Entity, Value, Value>> featureAccessors = new HashMap<String, BiFunction<Entity, Value, Value>>() {{
        put("removed", (entity, arg) -> new NumericValue(entity.removed));
        put("uuid",(e, a) -> new StringValue(e.getUuidAsString()));
        put("id",(e, a) -> new NumericValue(e.getEntityId()));
        put("pos", (e, a) -> ListValue.of(new NumericValue(e.x), new NumericValue(e.y), new NumericValue(e.z)));
        put("location", (e, a) -> ListValue.of(new NumericValue(e.x), new NumericValue(e.y), new NumericValue(e.z), new NumericValue(e.yaw), new NumericValue(e.pitch)));
        put("x", (e, a) -> new NumericValue(e.x));
        put("y", (e, a) -> new NumericValue(e.y));
        put("z", (e, a) -> new NumericValue(e.z));
        put("motion", (e, a) ->
        {
            Vec3d velocity = e.getVelocity();
            return ListValue.of(new NumericValue(velocity.x), new NumericValue(velocity.y), new NumericValue(velocity.z));
        });
        put("motion_x", (e, a) -> new NumericValue(e.getVelocity().x));
        put("motion_y", (e, a) -> new NumericValue(e.getVelocity().y));
        put("motion_z", (e, a) -> new NumericValue(e.getVelocity().z));
        put("name", (e, a) -> new StringValue(e.getDisplayName().getString()));
        put("custom_name", (e, a) -> e.hasCustomName()?new StringValue(e.getCustomName().getString()):Value.NULL);
        put("type", (e, a) -> new StringValue(Registry.ENTITY_TYPE.getId(e.getType()).toString().replaceFirst("minecraft:","")));
        put("is_riding", (e, a) -> new NumericValue(e.hasVehicle()));
        put("is_ridden", (e, a) -> new NumericValue(e.hasPassengers()));
        put("passengers", (e, a) -> ListValue.wrap(e.getPassengerList().stream().map(EntityValue::new).collect(Collectors.toList())));
        put("mount", (e, a) -> (e.getVehicle()!=null)?new EntityValue(e.getVehicle()):Value.NULL);
        put("tags", (e, a) -> ListValue.wrap(e.getScoreboardTags().stream().map(StringValue::new).collect(Collectors.toList())));
        put("has_tag", (e, a) -> new NumericValue(e.getScoreboardTags().contains(a.getString())));
        put("yaw", (e, a)-> new NumericValue(e.yaw));
        put("pitch", (e, a)-> new NumericValue(e.pitch));
        put("is_burning", (e, a) -> new NumericValue(e.isOnFire()));
        //put("fire", (e, a) -> new NumericValue(e.getFire())); needs mixing
        put("silent", (e, a)-> new NumericValue(e.isSilent()));
        put("gravity", (e, a) -> new NumericValue(!e.hasNoGravity()));
        put("immune_to_fire", (e, a) -> new NumericValue(e.isFireImmune()));

        put("invulnerable", (e, a) -> new NumericValue(e.isInvulnerable()));
        put("dimension", (e, a) -> new StringValue(e.dimension.toString().replaceFirst("minecraft:","")));
        put("height", (e, a) -> new NumericValue(e.getSize(EntityPose.STANDING).height));
        put("width", (e, a) -> new NumericValue(e.getSize(EntityPose.STANDING).width));
        put("eye_height", (e, a) -> new NumericValue(e.getEyeHeight(EntityPose.STANDING)));
        put("age", (e, a) -> new NumericValue(e.age));
        put("item", (e, a) -> (e instanceof ItemEntity)?new StringValue(((ItemEntity) e).getStack().getDisplayName().getString()):Value.NULL);
        put("count", (e, a) -> (e instanceof ItemEntity)?new NumericValue(((ItemEntity) e).getStack().getAmount()):Value.NULL);
        // ItemEntity -> despawn timer via ssGetAge
        put("is_baby", (e, a) -> (e instanceof LivingEntity)?new NumericValue(((LivingEntity) e).isBaby()):Value.NULL);
        put("target", (e, a) -> {
            if (e instanceof MobEntity)
            {
                LivingEntity target = ((MobEntity) e).getTarget(); // there is also getAttacking in living....
                if (target != null)
                {
                    return new EntityValue(target);
                }
            }
            return Value.NULL;
        });
        put("home", (e, a) -> {
            if (e instanceof MobEntity)
            {
                return (((MobEntity) e).getWalkTargetRange () > 0)?new BlockValue(null, e.getEntityWorld(), ((MobEntityWithAi) e).getWalkTarget()):Value.FALSE;
            }
            return Value.NULL;
        });
        put("sneaking", (e, a) -> e.isSneaking()?Value.TRUE:Value.FALSE);
        put("sprinting", (e, a) -> e.isSprinting()?Value.TRUE:Value.FALSE);
        put("swimming", (e, a) -> e.isSwimming()?Value.TRUE:Value.FALSE);
        /*put("jumping", (e, a) -> {
            if (e instanceof LivingEntity)
            {
                return  ((LivingEntity) e).getJumping()?Value.TRUE:Value.FALSE;
            }
            return Value.NULL;
        });*/ //needs mixing
        put("gamemode", (e, a) -> {
            if (e instanceof  ServerPlayerEntity)
            {
                return new StringValue(((ServerPlayerEntity) e).interactionManager.getGameMode().getName());
            }
            return Value.NULL;
        });
        put("gamemode_id", (e, a) -> {
            if (e instanceof  ServerPlayerEntity)
            {
                return new NumericValue(((ServerPlayerEntity) e).interactionManager.getGameMode().getId());
            }
            return Value.NULL;
        });
        //spectating_entity
        // isGlowing
        put("effect", (e, a) ->
        {
            if (!(e instanceof LivingEntity))
            {
                return Value.NULL;
            }
            if (a == null)
            {
                List<Value> effects = new ArrayList<>();
                for (StatusEffectInstance p : ((LivingEntity) e).getStatusEffects())
                {
                    effects.add(ListValue.of(
                        new StringValue(p.getTranslationKey().replaceFirst("^effect\\.minecraft\\.", "")),
                        new NumericValue(p.getAmplifier()),
                        new NumericValue(p.getDuration())
                    ));
                }
                return ListValue.wrap(effects);
            }
            String effectName = a.getString();
            StatusEffect potion = Registry.STATUS_EFFECT.get(new Identifier(effectName));
            if (potion == null)
                throw new InternalExpressionException("No such an effect: "+effectName);
            if (!((LivingEntity) e).hasStatusEffect(potion))
                return Value.NULL;
            StatusEffectInstance pe = ((LivingEntity) e).getStatusEffect(potion);
            return ListValue.of( new NumericValue(pe.getAmplifier()), new NumericValue(pe.getDuration()) );
        });
        put("health", (e, a) ->
        {
            if (e instanceof LivingEntity)
            {
                return new NumericValue(((LivingEntity) e).getHealth());
            }
            //if (e instanceof ItemEntity)
            //{
            //    e.h consider making item health public
            //}
            return Value.NULL;
        });
        put("holds", (e, a) -> {
            EquipmentSlot where = EquipmentSlot.MAINHAND;
            if (a != null)
                where = inventorySlots.get(a.getString());
            if (where == null)
                throw new InternalExpressionException("Unknown inventory slot: "+a.getString());
            if (e instanceof LivingEntity)
            {
                ItemStack itemstack = ((LivingEntity)e).getEquippedStack(where);
                if (!itemstack.isEmpty())
                {
                    return ListValue.of(
                            new StringValue(Registry.ITEM.getId(itemstack.getItem()).getPath()),
                            new NumericValue(itemstack.getAmount()),
                            new StringValue(itemstack.toTag(new CompoundTag()).toString())
                    );
                }
            }
            return Value.NULL;

        });

        put("nbt",(e, a) -> {
            CompoundTag nbttagcompound = e.toTag((new CompoundTag()));
            if (a==null)
                return new StringValue(nbttagcompound.toString());
            NbtPathArgumentType.NbtPath path;
            try
            {
                path = NbtPathArgumentType.create().method_9362(new StringReader(a.getString()));
            }
            catch (CommandSyntaxException exc)
            {
                throw new InternalExpressionException("Incorrect path: "+a.getString());
            }
            String res = null;
            try
            {
                List<Tag> tags = path.get(nbttagcompound);
                if (tags.size()==0)
                    return Value.NULL;
                if (tags.size()==1)
                    return new StringValue(tags.get(0).toTextComponent().getString());
                return ListValue.wrap(tags.stream().map((t) -> new StringValue(t.toTextComponent().getString())).collect(Collectors.toList()));
            }
            catch (CommandSyntaxException ignored) { }
            return new StringValue(res);
        });
    }};
    private static <Req extends Entity> Req assertEntityArgType(Class<Req> klass, Value arg)
    {
        if (!(arg instanceof EntityValue))
        {
            return null;
        }
        Entity e = ((EntityValue) arg).getEntity();
        if (!(klass.isAssignableFrom(e.getClass())))
        {
            return null;
        }
        return (Req)e;
    }

    public void set(String what, Value toWhat)
    {
        if (!(featureModifiers.containsKey(what)))
            throw new InternalExpressionException("unknown action on entity: " + what);
        featureModifiers.get(what).accept(entity, toWhat);
    }

    private static Map<String, BiConsumer<Entity, Value>> featureModifiers = new HashMap<String, BiConsumer<Entity, Value>>() {{
        put("remove", (entity, value) -> entity.remove());
        put("health", (e, v) -> { if (e instanceof LivingEntity) ((LivingEntity) e).setHealth((float) NumericValue.asNumber(v).getDouble()); });
        put("kill", (e, v) -> e.kill());
        put("location", (e, v) ->
        {
            if (!(v instanceof ListValue))
            {
                throw new InternalExpressionException("expected a list of 5 parameters as second argument");
            }
            List<Value> coords = ((ListValue) v).getItems();
            e.x = NumericValue.asNumber(coords.get(0)).getDouble();
            e.y = NumericValue.asNumber(coords.get(1)).getDouble();
            e.z = NumericValue.asNumber(coords.get(2)).getDouble();
            e.pitch = (float) NumericValue.asNumber(coords.get(4)).getDouble();
            e.prevPitch = e.pitch;
            e.yaw = (float) NumericValue.asNumber(coords.get(3)).getDouble();
            e.prevYaw = e.yaw;
            e.setPosition(e.x, e.y, e.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        put("pos", (e, v) ->
        {
            if (!(v instanceof ListValue))
            {
                throw new InternalExpressionException("expected a list of 3 parameters as second argument");
            }
            List<Value> coords = ((ListValue) v).getItems();
            e.x = NumericValue.asNumber(coords.get(0)).getDouble();
            e.y = NumericValue.asNumber(coords.get(1)).getDouble();
            e.z = NumericValue.asNumber(coords.get(2)).getDouble();
            e.setPosition(e.x, e.y, e.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        put("x", (e, v) ->
        {
            e.x = NumericValue.asNumber(v).getDouble();
            e.setPosition(e.x, e.y, e.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        put("y", (e, v) ->
        {
            e.y = NumericValue.asNumber(v).getDouble();
            e.setPosition(e.x, e.y, e.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        put("z", (e, v) ->
        {
            e.z = NumericValue.asNumber(v).getDouble();
            e.setPosition(e.x, e.y, e.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        put("pitch", (e, v) ->
        {
            e.pitch = (float) NumericValue.asNumber(v).getDouble();
            e.prevPitch = e.pitch;
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        put("yaw", (e, v) ->
        {
            e.yaw = (float) NumericValue.asNumber(v).getDouble();
            e.prevYaw = e.yaw;
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.x, e.y, e.z, e.yaw, e.pitch);
        });
        //"look"
        //"turn"
        //"nod"

        put("move", (e, v) ->
        {
            if (!(v instanceof ListValue))
            {
                throw new InternalExpressionException("expected a list of 3 parameters as second argument");
            }
            List<Value> coords = ((ListValue) v).getItems();
            e.x += NumericValue.asNumber(coords.get(0)).getDouble();
            e.y += NumericValue.asNumber(coords.get(1)).getDouble();
            e.z += NumericValue.asNumber(coords.get(2)).getDouble();
            e.setPosition(e.x, e.y, e.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.requestTeleport(e.y, e.y, e.z, e.yaw, e.pitch);
        });

        put("motion", (e, v) ->
        {
            if (!(v instanceof ListValue))
            {
                throw new InternalExpressionException("expected a list of 3 parameters as second argument");
            }
            List<Value> coords = ((ListValue) v).getItems();
            e.setVelocity(
                    NumericValue.asNumber(coords.get(0)).getDouble(),
                    NumericValue.asNumber(coords.get(1)).getDouble(),
                    NumericValue.asNumber(coords.get(2)).getDouble()
            );
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(e));
        });
        put("motion_x", (e, v) ->
        {
            Vec3d velocity = e.getVelocity();
            e.setVelocity(NumericValue.asNumber(v).getDouble(), velocity.y, velocity.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(e));
        });
        put("motion_y", (e, v) ->
        {
            Vec3d velocity = e.getVelocity();
            e.setVelocity(velocity.x, NumericValue.asNumber(v).getDouble(), velocity.z);
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(e));
        });
        put("motion_z", (e, v) ->
        {
            Vec3d velocity = e.getVelocity();
            e.setVelocity(velocity.x, velocity.y, NumericValue.asNumber(v).getDouble());
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(e));
        });

        put("accelerate", (e, v) ->
        {
            if (!(v instanceof ListValue))
            {
                throw new InternalExpressionException("expected a list of 3 parameters as second argument");
            }
            List<Value> coords = ((ListValue) v).getItems();
            e.addVelocity(
                    NumericValue.asNumber(coords.get(0)).getDouble(),
                    NumericValue.asNumber(coords.get(1)).getDouble(),
                    NumericValue.asNumber(coords.get(2)).getDouble()
            );
            if (e instanceof ServerPlayerEntity)
                ((ServerPlayerEntity)e).networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(e));

        });
        put("custom_name", (e, v) -> {
            String name = v.getString();
            if (name.isEmpty())
                e.setCustomName(null);
            e.setCustomName(new TextComponent(v.getString()));
        });
        put("dismount", (e, v) -> e.stopRiding() );
        put("mount", (e, v) -> {
            if (v instanceof EntityValue)
            {
                e.startRiding(((EntityValue) v).getEntity(),true);
            }
        });
        put("drop_passengers", (e, v) -> e.removeAllPassengers());
        put("mount_passengers", (e, v) -> {
            if (v==null)
                throw new InternalExpressionException("mount_passengers needs entities to ride");
            if (v instanceof EntityValue)
                ((EntityValue) v).getEntity().startRiding(e);
            else if (v instanceof ListValue)
                for (Value element : ((ListValue) v).getItems())
                    if (element instanceof EntityValue)
                        ((EntityValue) element).getEntity().startRiding(e);
        });
        put("tag", (e, v) -> {
            if (v==null)
                throw new InternalExpressionException("tag requires parameters");
            if (v instanceof ListValue)
                for (Value element : ((ListValue) v).getItems()) e.addScoreboardTag(element.getString());
            else
                e.addScoreboardTag(v.getString());
        });
        put("clear_tag", (e, v) -> {
            if (v==null)
                throw new InternalExpressionException("clear_tag requires parameters");
            if (v instanceof ListValue)
                for (Value element : ((ListValue) v).getItems()) e.removeScoreboardTag(element.getString());
            else
                e.removeScoreboardTag(v.getString());
        });
        //put("target", (e, v) -> {
        //    // attacks indefinitely - might need to do it through tasks
        //    if (e instanceof MobEntity)
        //    {
        //        LivingEntity elb = assertEntityArgType(LivingEntity.class, v);
        //        ((MobEntity) e).setTarget(elb);
        //    }
        //});
        put("talk", (e, v) -> {
            // attacks indefinitely
            if (e instanceof MobEntity)
            {
                ((MobEntity) e).playAmbientSound();
            }
        });
        put("home", (e, v) -> {
            if (!(e instanceof MobEntityWithAi))
                return;
            MobEntityWithAi ec = (MobEntityWithAi)e;
            if (v == null)
                throw new InternalExpressionException("home requires at least one position argument, and optional distance, or null to cancel");
            if (v instanceof NullValue)
            {
                ec.setWalkTarget(BlockPos.ORIGIN, -1);
                Map<String,Goal> tasks = ((MobEntityInterface)ec).getTemporaryTasks();
                ((MobEntityInterface)ec).getAI(false).remove(tasks.get("home"));
                tasks.remove("home");
                return;
            }

            BlockPos pos;
            int distance = 16;

            if (v instanceof BlockValue)
            {
                pos = ((BlockValue) v).getPos();
                if (pos == null) throw new InternalExpressionException("block is not positioned in the world");
            }
            else if (v instanceof ListValue)
            {
                List<Value> lv = ((ListValue) v).getItems();
                if (lv.get(0) instanceof BlockValue)
                {
                    pos = ((BlockValue) lv.get(0)).getPos();
                    if (lv.size()>1)
                    {
                        distance = (int) NumericValue.asNumber(lv.get(1)).getLong();
                    }
                }
                else if (lv.size()>=3)
                {
                    pos = new BlockPos(NumericValue.asNumber(lv.get(0)).getLong(),
                            NumericValue.asNumber(lv.get(1)).getLong(),
                            NumericValue.asNumber(lv.get(2)).getLong());
                    if (lv.size()>3)
                    {
                        distance = (int) NumericValue.asNumber(lv.get(4)).getLong();
                    }
                }
                else throw new InternalExpressionException("home requires at least one position argument, and optional distance");

            }
            else throw new InternalExpressionException("home requires at least one position argument, and optional distance");

            ec.setWalkTarget(pos, distance);
            Map<String,Goal> tasks = ((MobEntityInterface)ec).getTemporaryTasks();
            if (!tasks.containsKey("home"))
            {
                Goal task = new GoToWalkTargetGoal(ec, 1.0D);
                tasks.put("home", task);
                ((MobEntityInterface)ec).getAI(false).add(10, task);
            }
        }); //requires mixing

        // gamemode
        // spectate
        // "fire"
        // "extinguish"
        // "silent"
        // "gravity"
        // "invulnerable"
        // "dimension"
        // "item"
        // "count",
        // "age",
        // "effect_"name
        // "hold"
        // "hold_offhand"
        // "jump"
        // "nbt" <-big one, for now use run('data merge entity ...
    }};
}