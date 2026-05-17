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

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public interface FabricPermissionsProvider {

    boolean hasPermission(ServerPlayer player, String permission);

    void registerPermission(String permission);

    class VanillaPermissionsProvider implements FabricPermissionsProvider {

        private final FabricPlatform platform;

        public VanillaPermissionsProvider(FabricPlatform platform) {
            this.platform = platform;
        }

        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            // ServerPlayer.server and ServerPlayer.gameMode were public in 1.21.1 but are
            // private in 1.21.11, so direct field access raises IllegalAccessError. Use the
            // public getServer() getter and reflect on gameMode with graceful fallbacks.
            FabricConfiguration configuration = platform.getConfiguration();
            boolean singlePlayerOwner = false;
            boolean operator = false;
            MinecraftServer server = null;
            try {
                server = player.getServer();
            } catch (Throwable ignored) {
            }
            if (server == null) {
                // Without a server reference we can't classify the player. Default to
                // granting (we'd rather false-positive than block all WE commands).
                singlePlayerOwner = true;
            } else {
                boolean dedicated = true;
                try {
                    dedicated = server.isDedicatedServer();
                } catch (Throwable ignored) {
                }
                if (!dedicated) {
                    // Integrated server (singleplayer / open-to-LAN). isSingleplayerOwner
                    // was renamed between 1.21.1 and 1.21.11 — probe candidates, fall back
                    // to granting since any non-dedicated host is treated as the world owner.
                    Boolean owner = null;
                    for (String name : new String[]{"isSingleplayerOwner", "isHost", "isOwner", "isSingleplayerHost"}) {
                        try {
                            java.lang.reflect.Method m = server.getClass().getMethod(name, com.mojang.authlib.GameProfile.class);
                            Object result = m.invoke(server, player.getGameProfile());
                            if (result instanceof Boolean) {
                                owner = (Boolean) result;
                                break;
                            }
                        } catch (NoSuchMethodException ignored) {
                        } catch (Throwable ignored) {
                        }
                    }
                    singlePlayerOwner = (owner != null) ? owner : true;
                }
                try {
                    operator = player.hasPermissions(server.getOperatorUserPermissionLevel());
                } catch (Throwable ignored) {
                }
            }
            boolean creative = false;
            if (configuration.creativeEnable) {
                try {
                    java.lang.reflect.Field gmField = null;
                    Class<?> cls = player.getClass();
                    while (cls != null && gmField == null) {
                        try {
                            gmField = cls.getDeclaredField("gameMode");
                        } catch (NoSuchFieldException nsfe) {
                            cls = cls.getSuperclass();
                        }
                    }
                    if (gmField != null) {
                        gmField.setAccessible(true);
                        Object gameMode = gmField.get(player);
                        if (gameMode != null) {
                            Object gameType = gameMode.getClass().getMethod("getGameModeForPlayer").invoke(gameMode);
                            creative = (gameType == GameType.CREATIVE);
                        }
                    }
                } catch (Throwable ignored) {
                    // gameMode field renamed or getGameModeForPlayer method renamed; skip creative grant.
                }
            }
            return configuration.cheatMode
                || singlePlayerOwner
                || operator
                || creative;
        }

        @Override
        public void registerPermission(String permission) {
        }
    }

    class LuckoFabricPermissionsProvider extends VanillaPermissionsProvider {

        public LuckoFabricPermissionsProvider(FabricPlatform platform) {
            super(platform);
        }

        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            return Permissions.getPermissionValue(player, permission)
                .orElseGet(() -> super.hasPermission(player, permission));
        }

        @Override
        public void registerPermission(String permission) {
        }
    }
}
