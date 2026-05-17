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

package com.sk89q.worldedit.fabric.net.handler;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.FabricPlayer;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WECUIPacketHandler {
    private WECUIPacketHandler() {
    }

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static final ResourceLocation CUI_IDENTIFIER = ResourceLocation.fromNamespaceAndPath(FabricWorldEdit.MOD_ID, FabricWorldEdit.CUI_PLUGIN_CHANNEL);

    public record CuiPacket(String text) implements CustomPacketPayload {
        public static final Type<CuiPacket> TYPE = new Type<>(CUI_IDENTIFIER);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        // WorldEditCUI (standalone mod) registers the same worldedit:cui packet type with
        // its own (modern, structured) wire format. We can't double-register. When CUI is
        // present, we instead bridge: register a serverbound handler via WorldEditCUI's
        // own CUIPacketHandler API, so the client's CUI handshake reaches our LocalSession.
        // Without this bridge, session.hasCUISupport() stays false and dispatchCUIEvent
        // is never called -> no wireframes.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("worldeditcui")) {
            registerWorldEditCUIBridge();
            return;
        }
        StreamCodec<RegistryFriendlyByteBuf, CuiPacket> codec = CustomPacketPayload.codec(
            (packet, buffer) -> buffer.writeCharSequence(packet.text(), StandardCharsets.UTF_8),
            buffer -> new CuiPacket(buffer.readCharSequence(buffer.readableBytes(), StandardCharsets.UTF_8).toString())
        );
        registerIgnoringDuplicate(() -> PayloadTypeRegistry.playC2S().register(CuiPacket.TYPE, codec));
        registerIgnoringDuplicate(() -> PayloadTypeRegistry.playS2C().register(CuiPacket.TYPE, codec));
        registerIgnoringDuplicate(() -> ServerPlayNetworking.registerGlobalReceiver(CuiPacket.TYPE, (payload, context) -> {
            LocalSession session = FabricWorldEdit.inst.getSession(context.player());
            FabricPlayer actor = FabricAdapter.adaptPlayer(context.player());
            session.handleCUIInitializationMessage(payload.text(), actor);
        }));
    }

    private static void registerIgnoringDuplicate(Runnable registration) {
        try {
            registration.run();
        } catch (IllegalArgumentException ignored) {
            // Already registered by another initializer or classpath entry.
        }
    }

    /**
     * Bridge incoming CUI packets from WorldEditCUI's modern protocol into WorldEdit's
     * LocalSession initialization message. WorldEditCUI exposes a public API
     * {@code CUIPacketHandler.instance().registerServerboundHandler(BiConsumer<CUIPacket, PacketContext>)}.
     * We invoke it reflectively to avoid a compile-time dependency on WorldEditCUI.
     */
    private static void registerWorldEditCUIBridge() {
        org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger("WorldEdit-Fabric");
        try {
            Class<?> handlerCls = Class.forName("org.enginehub.worldeditcui.protocol.CUIPacketHandler");
            java.lang.reflect.Method instanceM = handlerCls.getMethod("instance");
            Object handler = instanceM.invoke(null);
            java.lang.reflect.Method registerM = handlerCls.getMethod(
                "registerServerboundHandler", java.util.function.BiConsumer.class
            );
            java.util.function.BiConsumer<Object, Object> bridgeHandler = (packet, ctx) -> {
                try {
                    java.lang.reflect.Method eventTypeM = packet.getClass().getMethod("eventType");
                    java.lang.reflect.Method argsM = packet.getClass().getMethod("args");
                    String eventType = (String) eventTypeM.invoke(packet);
                    @SuppressWarnings("unchecked")
                    java.util.List<String> args = (java.util.List<String>) argsM.invoke(packet);
                    StringBuilder sb = new StringBuilder(eventType);
                    for (String a : args) {
                        sb.append('|').append(a);
                    }
                    java.lang.reflect.Method playerM = ctx.getClass().getMethod("player");
                    Object player = playerM.invoke(ctx);
                    if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                        LocalSession session = FabricWorldEdit.inst.getSession(sp);
                        FabricPlayer actor = FabricAdapter.adaptPlayer(sp);
                        session.handleCUIInitializationMessage(sb.toString(), actor);
                    }
                } catch (Throwable t) {
                    logger.warn("[CUI-DEBUG] Bridge handler error: {}: {}", t.getClass().getName(), t.getMessage());
                }
            };
            registerM.invoke(handler, bridgeHandler);
            logger.info("[CUI-DEBUG] Registered WorldEditCUI serverbound bridge handler");
        } catch (Throwable t) {
            logger.warn("[CUI-DEBUG] Failed to register WorldEditCUI bridge: {}: {}", t.getClass().getName(), t.getMessage());
        }
    }
}
