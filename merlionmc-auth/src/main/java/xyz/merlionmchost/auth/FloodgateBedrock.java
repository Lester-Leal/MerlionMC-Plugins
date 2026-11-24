package xyz.merlionmchost.auth;

import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

/**
 * Bedrock players join through Geyser, and Floodgate has already checked them with Xbox Live, so they skip login.
 * Floodgate decides who is a Bedrock player from data signed with a key only its Geyser has, so a Java player can't
 * pretend to be one. Only loaded when Floodgate is installed (MerlionMCAuth checks first), so servers without it
 * never touch this class.
 */
final class FloodgateBedrock {
    boolean isBedrock(UUID player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player);
    }
}
