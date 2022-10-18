package net.william278.huskhomes.teleport;

import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.config.Settings;
import net.william278.huskhomes.messenger.Message;
import net.william278.huskhomes.messenger.MessagePayload;
import net.william278.huskhomes.player.OnlineUser;
import net.william278.huskhomes.player.User;
import net.william278.huskhomes.position.Location;
import net.william278.huskhomes.position.Position;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * A builder for {@link Teleport}s and {@link TimedTeleport}s
 *
 * @since 3.1
 */
@SuppressWarnings("unused")
public class TeleportBuilder {

    // Instance of the implementing plugin
    @NotNull
    private final HuskHomes plugin;

    /**
     * The executor of the teleport; the one who triggered the teleport to happen;
     * whom may not necessarily the person being <i>teleported</i>
     */
    @NotNull
    private final OnlineUser executor;

    /**
     * The teleporter; the one who is being teleported, to be resolved before construction
     */
    private CompletableFuture<User> teleporter;

    /**
     * The target position for the teleporter, to be resolved before construction
     */
    private CompletableFuture<Position> target;

    /**
     * List of {@link Settings.EconomyAction}s to check against.
     * <p>
     * Note that these are checked against the <i>{@link #executor executor}</i> of the teleport;
     * not necessarily the one doing the teleporting
     */
    private final Set<Settings.EconomyAction> economyActions = new HashSet<>();

    /**
     * The type of teleport. Defaults to {@link TeleportType#TELEPORT}
     */
    private TeleportType type = TeleportType.TELEPORT;

    protected TeleportBuilder(@NotNull HuskHomes plugin, @NotNull OnlineUser executor) {
        this.plugin = plugin;
        this.executor = executor;
        this.teleporter = CompletableFuture.completedFuture(executor);
    }

    /**
     * Set the person being teleported as a {@link User}
     *
     * @param teleporter The {@link User} who is doing the teleporting
     * @return The {@link TeleportBuilder} instance
     */
    public TeleportBuilder setTeleporter(@NotNull User teleporter) {
        this.teleporter = CompletableFuture.completedFuture(teleporter);
        return this;
    }

    /**
     * Set the person being teleported as the username of a player, which will attempt to be resolved
     * into a user at the time of construction
     *
     * @param teleporterUsername The username of the player who is doing the teleporting
     * @return The {@link TeleportBuilder} instance
     */
    public TeleportBuilder setTeleporter(@NotNull String teleporterUsername) {
        this.teleporter = CompletableFuture.supplyAsync(() -> plugin
                .findPlayer(teleporterUsername)
                .map(onlineUser -> (User) onlineUser)
                .or(() -> {
                    if (plugin.getSettings().crossServer) {
                        return plugin.getNetworkMessenger()
                                .findPlayer(executor, teleporterUsername).join()
                                .map(username -> new User(UUID.randomUUID(), username));
                    }
                    return Optional.empty();
                })
                .orElse(null));
        return this;
    }

    /**
     * Set the target as a {@link Position}
     *
     * @param position The {@link Position} the teleport is aiming to teleport the {@link #teleporter} to
     * @return The {@link TeleportBuilder} instance
     */
    public TeleportBuilder setTarget(@NotNull Position position) {
        this.target = CompletableFuture.completedFuture(position);
        return this;
    }

    /**
     * Set the target as a {@link Location} on this server
     *
     * @param location The {@link Location} on this server the teleport is aiming to teleport the {@link #teleporter} to
     * @return The {@link TeleportBuilder} instance
     */
    public TeleportBuilder setTarget(@NotNull Location location) {
        this.target = CompletableFuture.completedFuture(new Position(location, plugin.getPluginServer()));
        return this;
    }

    /**
     * Set the target as the position of a player, specified by their username, who may be on another server.
     *
     * @param targetUsername The username of the player on the target server the teleport is aiming to teleport the
     *                       {@link #teleporter} to
     * @return The {@link TeleportBuilder} instance
     */
    public TeleportBuilder setTarget(@NotNull String targetUsername) {
        this.target = getPlayerPosition(targetUsername).thenApply(position -> position.orElse(null));
        return this;
    }

    /**
     * Set the type of teleport
     *
     * @param type The {@link TeleportType} of the teleport
     * @return The {@link TeleportBuilder} instance
     */
    public TeleportBuilder setType(@NotNull TeleportType type) {
        this.type = type;
        return this;
    }

    /**
     * Set the economy actions to check against during the teleport
     *
     * @param economyActions The {@link Settings.EconomyAction}s to check against
     * @return The {@link TeleportBuilder} instance
     * @implNote These are checked against the <i>{@link #executor executor}</i> of the teleport;
     * not necessarily the one doing the teleporting
     */
    public TeleportBuilder setEconomyActions(@NotNull Set<Settings.EconomyAction> economyActions) {
        this.economyActions.addAll(economyActions);
        return this;
    }

    /**
     * Set the economy actions to check against during the teleport
     *
     * @param economyActions The {@link Settings.EconomyAction}s to check against
     * @return The {@link TeleportBuilder} instance
     * @implNote These are checked against the <i>{@link #executor executor}</i> of the teleport;
     * not necessarily the one doing the teleporting
     */
    public TeleportBuilder setEconomyActions(@NotNull Settings.EconomyAction... economyActions) {
        this.economyActions.addAll(Arrays.asList(economyActions));
        return this;
    }

    /**
     * Resolve the teleporter and target, and construct as an instantly-completing {@link Teleport}
     *
     * @return The constructed {@link Teleport}
     */
    public CompletableFuture<Teleport> toTeleport() {
        return CompletableFuture.supplyAsync(() -> {
            final User teleporter = this.teleporter.join();
            final Position target = this.target.join();

            return new Teleport(teleporter, executor, target, type, economyActions, plugin);
        }).exceptionally(e -> {
            plugin.getLoggingAdapter().log(Level.SEVERE, "Failed to create teleport", e);
            return null;
        });
    }

    /**
     * Resolve the teleporter and target, and construct as a {@link TimedTeleport}
     *
     * @return The constructed {@link TimedTeleport}
     */
    public CompletableFuture<TimedTeleport> toTimedTeleport() {
        return CompletableFuture.supplyAsync(() -> {
            final User teleporter = this.teleporter.join();
            final Position target = this.target.join();
            final int warmupTime = plugin.getSettings().teleportWarmupTime;

            if (!(teleporter instanceof OnlineUser onlineUser)) {
                throw new IllegalStateException("Timed teleports can only be executed for local users");
            }

            return new TimedTeleport(onlineUser, executor, target, type, warmupTime, economyActions, plugin);
        }).exceptionally(e -> {
            plugin.getLoggingAdapter().log(Level.SEVERE, "Failed to create timed teleport", e);
            return null;
        });
    }

    /**
     * Gets the position of a player by their username, including players on other servers
     *
     * @param playerName the username of the player being requested
     * @return future optionally supplying the player's position, if the player could be found
     */
    private CompletableFuture<Optional<Position>> getPlayerPosition(@NotNull String playerName) {
        final Optional<OnlineUser> localPlayer = plugin.findPlayer(playerName);
        if (localPlayer.isPresent()) {
            return CompletableFuture.supplyAsync(() -> Optional.of(localPlayer.get().getPosition()));
        }
        if (plugin.getSettings().crossServer) {
            return plugin.getNetworkMessenger()
                    .findPlayer(executor, playerName)
                    .thenApply(foundPlayer -> {
                        if (foundPlayer.isEmpty()) {
                            return Optional.empty();
                        }
                        return plugin.getNetworkMessenger()
                                .sendMessage(executor, new Message(Message.MessageType.POSITION_REQUEST, executor.username,
                                        playerName, MessagePayload.empty(), Message.RelayType.MESSAGE,
                                        plugin.getSettings().clusterId))
                                .orTimeout(3, TimeUnit.SECONDS)
                                .exceptionally(throwable -> null)
                                .thenApply(reply -> Optional.ofNullable(reply.payload.position)).join();
                    });
        }
        return CompletableFuture.supplyAsync(Optional::empty);
    }

}
