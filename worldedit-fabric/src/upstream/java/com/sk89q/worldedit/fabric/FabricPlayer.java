/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric;

import com.sk89q.util.StringUtil;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.fabric.net.handler.WECUIPacketHandler;
import com.sk89q.worldedit.internal.cui.CUIEvent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.WorldEditText;
import com.sk89q.worldedit.util.formatting.component.TextUtils;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;

public class FabricPlayer extends AbstractPlayerActor {

    private final ServerPlayer player;

    protected FabricPlayer(ServerPlayer player) {
        this.player = player;
        ThreadSafeCache.getInstance().getOnlineIds().add(getUniqueId());
    }

    /** Internal getter used by FabricAdapter's cache to detect stale ServerPlayer references. */
    public ServerPlayer getInternalPlayer() {
        return this.player;
    }

    @Override
    public UUID getUniqueId() {
        return player.getUUID();
    }

    @Override
    public BaseItemStack getItemInHand(HandSide handSide) {
        ItemStack is = this.player.getItemInHand(handSide == HandSide.MAIN_HAND ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        return FabricAdapter.adapt(is);
    }

    @Override
    public String getName() {
        return this.player.getName().getString();
    }

    @Override
    public BaseEntity getState() {
        throw new UnsupportedOperationException("Cannot create a state from this object");
    }

    @Override
    public Location getLocation() {
        Vector3 position = Vector3.at(this.player.getX(), this.player.getY(), this.player.getZ());
        return new Location(
            FabricWorldEdit.inst.getWorld(this.player.serverLevel()),
            position,
            this.player.getYRot(),
            this.player.getXRot());
    }

    @Override
    public boolean setLocation(Location location) {
        ServerLevel level = (ServerLevel) FabricAdapter.adapt((World) location.getExtent());
        this.player.teleportTo(
            level,
            location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch()
        );
        // This check doesn't really ever get to be false in Fabric
        // Since Fabric API doesn't allow cancelling the teleport.
        // However, other mods could theoretically mix this in, so allow the detection.
        return this.player.serverLevel() == level;
    }

    @Override
    public World getWorld() {
        return FabricWorldEdit.inst.getWorld(this.player.serverLevel());
    }

    @Override
    public void giveItem(BaseItemStack itemStack) {
        this.player.getInventory().add(FabricAdapter.adapt(itemStack));
    }

    @Override
    public void dispatchCUIEvent(CUIEvent event) {
        // If WorldEditCUI mod is present, use ITS CUIPacket (modern protocol with separated
        // eventType + args) via reflection. WorldEditCUI registered the wire codec for its
        // own class, so the encode succeeds and its client renders the selection wireframe.
        // Otherwise, fall back to our legacy single-string format.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("worldeditcui")) {
            sendCUIViaWorldEditCUI(event);
            return;
        }
        String[] params = event.getParameters();
        String send = event.getTypeId();
        if (params.length > 0) {
            send = send + "|" + StringUtil.joinString(params, "|");
        }
        ServerPlayNetworking.send(
            this.player,
            new WECUIPacketHandler.CuiPacket(send)
        );
    }

    private static final java.util.concurrent.atomic.AtomicBoolean CUI_DIAGNOSTICS_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    private void sendCUIViaWorldEditCUI(CUIEvent event) {
        try {
            Class<?> cls = Class.forName("org.enginehub.worldeditcui.protocol.CUIPacket");
            // Use the (String eventType, String... args) constructor.
            java.lang.reflect.Constructor<?> ctor = cls.getConstructor(String.class, String[].class);
            Object packet = ctor.newInstance(event.getTypeId(), event.getParameters());
            // CUIPacket implements CustomPacketPayload.
            ServerPlayNetworking.send(
                this.player,
                (net.minecraft.network.protocol.common.custom.CustomPacketPayload) packet
            );
            if (CUI_DIAGNOSTICS_LOGGED.compareAndSet(false, true)) {
                org.apache.logging.log4j.LogManager.getLogger("WorldEdit-Fabric")
                    .info("[CUI-DEBUG] First WorldEditCUI packet sent successfully: type={} args={}",
                        event.getTypeId(), java.util.Arrays.toString(event.getParameters()));
            }
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger("WorldEdit-Fabric")
                .warn("[CUI-DEBUG] Failed to send CUI packet via WorldEditCUI reflection: type={} args={} error={}: {}",
                    event.getTypeId(),
                    java.util.Arrays.toString(event.getParameters()),
                    t.getClass().getName(),
                    t.getMessage());
        }
    }

    @Override
    public Locale getLocale() {
        return TextUtils.getLocaleByMinecraftTag(this.player.clientInformation().language());
    }

    @Override
    @Deprecated
    public void printRaw(String msg) {
        for (String part : msg.split("\n")) {
            sendSystem(net.minecraft.network.chat.Component.literal(part));
        }
    }

    /**
     * ServerPlayer.sendSystemMessage(Component) was removed between 1.21.1 and 1.21.11
     * (its method_43496 mapping moved to CommandSource only, not exposed on ServerPlayer).
     * Send a ClientboundSystemChatPacket directly through the player's connection instead;
     * that packet class is stable across both versions.
     *
     * <p>The entire body is wrapped in catch-all - this is called from worker threads
     * (e.g. FAWE async paste completion) where any field access on ServerPlayer can throw
     * IllegalStateException("Currently invalid"). Letting that bubble kills the entire
     * command execution, so we eat the throw and log a budgeted number of failures.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEND_FAILURE_LOG_BUDGET =
        new java.util.concurrent.atomic.AtomicInteger(20);

    private void sendSystem(net.minecraft.network.chat.Component mcComp) {
        if (mcComp == null) return;
        try {
            this.player.connection.send(new ClientboundSystemChatPacket(mcComp, false));
        } catch (Throwable t) {
            if (SEND_FAILURE_LOG_BUDGET.getAndDecrement() > 0) {
                org.apache.logging.log4j.LogManager.getLogger("WorldEdit-Fabric")
                    .warn("[PRINT-DEBUG] sendSystem failed (thread={}): {}: {}",
                        Thread.currentThread().getName(), t.getClass().getName(), t.getMessage());
            }
        }
    }

    @Override
    @Deprecated
    public void printDebug(String msg) {
        sendColorized(msg, ChatFormatting.GRAY);
    }

    @Override
    @Deprecated
    public void print(String msg) {
        sendColorized(msg, ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    @Deprecated
    public void printError(String msg) {
        sendColorized(msg, ChatFormatting.RED);
    }

    @Override
    public void print(Component component) {
        // Component.Serializer.fromJson(String, HolderLookup.Provider) was REMOVED in 1.21.11.
        // Use the Codec-based path (TextCodecs.CODEC / ComponentSerialization.CODEC) which
        // exists in both 1.21.1 and 1.21.11 under the same intermediary (class_8824.field_46597).
        // Fallback chain: codec parse -> legacy Serializer.fromJson -> plain text.
        // Plain text strips colors AND click events — only used as last resort.
        Component formatted = WorldEditText.format(component, getLocale());
        String json = GsonComponentSerializer.INSTANCE.serialize(formatted);
        net.minecraft.network.chat.Component mcComp = parseComponentJsonViaCodec(json);
        if (mcComp == null) {
            mcComp = parseComponentJsonViaLegacySerializer(json);
        }
        if (mcComp != null) {
            sendSystem(mcComp);
            return;
        }
        String plain = com.sk89q.worldedit.util.formatting.text.serializer.plain.PlainComponentSerializer
            .INSTANCE.serialize(formatted);
        sendSystem(net.minecraft.network.chat.Component.literal(plain));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean PRINT_DIAGNOSTICS_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @Nullable
    private static net.minecraft.network.chat.Component parseComponentJsonViaCodec(String json) {
        String failureReason = "init";
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(json);
            Class<?> codecHolder = null;
            for (String className : new String[]{
                "net.minecraft.network.chat.ComponentSerialization",
                "net.minecraft.network.chat.TextCodecs",
                "net.minecraft.class_8824"
            }) {
                try {
                    codecHolder = Class.forName(className);
                    failureReason = "found class " + className;
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
            if (codecHolder == null) {
                logPrintFailure("no codec holder class found", json);
                return null;
            }
            com.mojang.serialization.Codec<?> codec = null;
            String foundFieldName = null;
            for (java.lang.reflect.Field f : codecHolder.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && com.mojang.serialization.Codec.class.isAssignableFrom(f.getType())
                    && (f.getName().equals("CODEC") || f.getName().equals("field_46597"))) {
                    f.setAccessible(true);
                    codec = (com.mojang.serialization.Codec<?>) f.get(null);
                    foundFieldName = f.getName();
                    break;
                }
            }
            if (codec == null) {
                logPrintFailure("codec field not found on " + codecHolder.getName(), json);
                return null;
            }
            com.mojang.serialization.DataResult<?> result = codec.parse(com.mojang.serialization.JsonOps.INSTANCE, el);
            Object parsed = result.result().orElse(null);
            if (parsed == null) {
                logPrintFailure("codec parse returned empty: " + result.error().map(e -> e.message()).orElse("(no error)"), json);
                return null;
            }
            if (!(parsed instanceof net.minecraft.network.chat.Component)) {
                logPrintFailure("parsed wrong type: " + parsed.getClass().getName(), json);
                return null;
            }
            if (PRINT_DIAGNOSTICS_LOGGED.compareAndSet(false, true)) {
                org.apache.logging.log4j.LogManager.getLogger("WorldEdit-Fabric")
                    .info("[PRINT-DEBUG] First codec parse succeeded: holder={} field={}",
                        codecHolder.getName(), foundFieldName);
            }
            return (net.minecraft.network.chat.Component) parsed;
        } catch (Throwable t) {
            logPrintFailure(failureReason + " -> " + t.getClass().getName() + ": " + t.getMessage(), json);
            return null;
        }
    }

    private static void logPrintFailure(String reason, String json) {
        // Only log the first few failures to avoid spamming the log on every command.
        if (PRINT_DIAGNOSTICS_LOGGED.compareAndSet(false, true)) {
            String preview = json.length() > 200 ? json.substring(0, 200) + "..." : json;
            org.apache.logging.log4j.LogManager.getLogger("WorldEdit-Fabric")
                .warn("[PRINT-DEBUG] Codec parse failed ({}); will use legacy fallback. JSON preview: {}",
                    reason, preview);
        }
    }

    @Nullable
    private static net.minecraft.network.chat.Component parseComponentJsonViaLegacySerializer(String json) {
        // Component.Serializer (inner class) removed in 1.21.11 — reflection so the failed
        // class lookup doesn't poison the call path on 1.21.11.
        try {
            Class<?> serializerCls = Class.forName("net.minecraft.network.chat.Component$Serializer");
            java.lang.reflect.Method m = serializerCls.getMethod("fromJson", String.class, net.minecraft.core.HolderLookup.Provider.class);
            Object result = m.invoke(null, json, FabricWorldEdit.registryAccess());
            return result instanceof net.minecraft.network.chat.Component
                ? (net.minecraft.network.chat.Component) result
                : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void sendTitle(Component title, Component sub) {
    }

    private void sendColorized(String msg, ChatFormatting formatting) {
        for (String part : msg.split("\n")) {
            MutableComponent component = net.minecraft.network.chat.Component.literal(part)
                .withStyle(style -> style.withColor(formatting));
            sendSystem(component);
        }
    }

    @Override
    public boolean trySetPosition(Vector3 pos, float pitch, float yaw) {
        this.player.connection.teleport(pos.x(), pos.y(), pos.z(), yaw, pitch);
        return true;
    }

    @Override
    public String[] getGroups() {
        return new String[]{}; // WorldEditMod.inst.getPermissionsResolver().getGroups(this.player.username);
    }

    @Override
    public BlockBag getInventoryBlockBag() {
        return null;
    }

    @Override
    public boolean hasPermission(String perm) {
        return FabricWorldEdit.inst.getPermissionsProvider().hasPermission(player, perm);
    }

    @Override
    public void setPermission(String permission, boolean value) {
    }

    @Nullable
    @Override
    public <T> T getFacet(Class<? extends T> cls) {
        return null;
    }

    @Override
    public boolean isAllowedToFly() {
        return player.getAbilities().mayfly;
    }

    @Override
    public void setFlying(boolean flying) {
        if (player.getAbilities().flying != flying) {
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
        }
    }

    @Override
    public <B extends BlockStateHolder<B>> void sendFakeBlock(BlockVector3 pos, B block) {
        World world = getWorld();
        if (!(world instanceof FabricWorld)) {
            return;
        }
        BlockPos loc = FabricAdapter.toBlockPos(pos);
        if (block == null) {
            final ClientboundBlockUpdatePacket packetOut = new ClientboundBlockUpdatePacket(((FabricWorld) world).getWorld(), loc);
            player.connection.send(packetOut);
        } else {
            final ClientboundBlockUpdatePacket packetOut = new ClientboundBlockUpdatePacket(
                loc,
                FabricAdapter.adapt(block.toImmutableState())
            );
            player.connection.send(packetOut);
            if (block instanceof BaseBlock && block.getBlockType().equals(BlockTypes.STRUCTURE_BLOCK)) {
                final LinCompoundTag nbtData = ((BaseBlock) block).getNbt();
                if (nbtData != null) {
                    player.connection.send(new ClientboundBlockEntityDataPacket(
                        new BlockPos(pos.x(), pos.y(), pos.z()),
                        BlockEntityType.STRUCTURE_BLOCK,
                        NBTConverter.toNative(nbtData)
                    ));
                }
            }
        }
    }

    @Override
    public SessionKey getSessionKey() {
        return new SessionKeyImpl(player);
    }

    static class SessionKeyImpl implements SessionKey {
        // If not static, this will leak a reference

        private final UUID uuid;
        private final String name;

        SessionKeyImpl(ServerPlayer player) {
            this.uuid = player.getUUID();
            this.name = player.getName().getString();
        }

        @Override
        public UUID getUniqueId() {
            return uuid;
        }

        @Nullable
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isActive() {
            // We can't directly check if the player is online because
            // the list of players is not thread safe
            return ThreadSafeCache.getInstance().getOnlineIds().contains(uuid);
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

    }

}
