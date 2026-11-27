// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import java.util.Objects;
import java.util.UUID;

/** One entry of the player info update packet. */
public class PlayerInfoData {
    private final UUID profileId;
    private final int latency;
    private final boolean listed;
    private final EnumWrappers.NativeGameMode gameMode;
    private final WrappedGameProfile profile;
    private final WrappedChatComponent displayName;
    private final boolean showHat;
    private final int listOrder;
    private final WrappedRemoteChatSessionData chatSession;

    public PlayerInfoData(WrappedGameProfile profile, int latency, EnumWrappers.NativeGameMode gameMode,
            WrappedChatComponent displayName) {
        this(profile.getUUID(), latency, true, gameMode, profile, displayName, true, 0, null);
    }

    public PlayerInfoData(UUID profileId, int latency, boolean listed, EnumWrappers.NativeGameMode gameMode,
            WrappedGameProfile profile, WrappedChatComponent displayName) {
        this(profileId, latency, listed, gameMode, profile, displayName, true, 0, null);
    }

    public PlayerInfoData(UUID profileId, int latency, boolean listed, EnumWrappers.NativeGameMode gameMode,
            WrappedGameProfile profile, WrappedChatComponent displayName, WrappedRemoteChatSessionData chatSession) {
        this(profileId, latency, listed, gameMode, profile, displayName, true, 0, chatSession);
    }

    public PlayerInfoData(UUID profileId, int latency, boolean listed, EnumWrappers.NativeGameMode gameMode,
            WrappedGameProfile profile, WrappedChatComponent displayName, int listOrder,
            WrappedRemoteChatSessionData chatSession) {
        this(profileId, latency, listed, gameMode, profile, displayName, true, listOrder, chatSession);
    }

    public PlayerInfoData(UUID profileId, int latency, boolean listed, EnumWrappers.NativeGameMode gameMode,
            WrappedGameProfile profile, WrappedChatComponent displayName, boolean showHat,
            WrappedRemoteChatSessionData chatSession) {
        this(profileId, latency, listed, gameMode, profile, displayName, showHat, 0, chatSession);
    }

    public PlayerInfoData(UUID profileId, int latency, boolean listed, EnumWrappers.NativeGameMode gameMode,
            WrappedGameProfile profile, WrappedChatComponent displayName, boolean showHat, int listOrder,
            WrappedRemoteChatSessionData chatSession) {
        this.profileId = profileId;
        this.latency = latency;
        this.listed = listed;
        this.gameMode = gameMode;
        this.profile = profile;
        this.displayName = displayName;
        this.showHat = showHat;
        this.listOrder = listOrder;
        this.chatSession = chatSession;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public WrappedGameProfile getProfile() {
        return profile;
    }

    @Deprecated
    public int getPing() {
        return latency;
    }

    public int getLatency() {
        return latency;
    }

    public boolean isListed() {
        return listed;
    }

    public EnumWrappers.NativeGameMode getGameMode() {
        return gameMode;
    }

    public WrappedChatComponent getDisplayName() {
        return displayName;
    }

    public boolean isShowHat() {
        return showHat;
    }

    public int getListOrder() {
        return listOrder;
    }

    public WrappedRemoteChatSessionData getRemoteChatSessionData() {
        return chatSession;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlayerInfoData d && Objects.equals(d.profileId, profileId) && d.latency == latency
                && d.listed == listed && d.gameMode == gameMode && Objects.equals(d.profile, profile)
                && Objects.equals(d.displayName, displayName) && d.showHat == showHat && d.listOrder == listOrder
                && Objects.equals(d.chatSession, chatSession);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, latency, listed, gameMode, profile, displayName, showHat, listOrder);
    }

    @Override
    public String toString() {
        return "PlayerInfoData[profileId=" + profileId + ", latency=" + latency + ", listed=" + listed + ", gameMode="
                + gameMode + ", profile=" + profile + ", displayName=" + displayName + "]";
    }
}
