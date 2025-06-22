package net.swofty.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.AwaitingEventExecutor;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.viaversion.vialoader.ViaLoader;
import com.viaversion.vialoader.impl.platform.ViaBackwardsPlatformImpl;
import com.viaversion.vialoader.impl.platform.ViaRewindPlatformImpl;
import com.viaversion.vialoader.impl.platform.ViaVersionPlatformImpl;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.swofty.commons.Configuration;
import net.swofty.commons.ServerType;
import net.swofty.commons.proxy.FromProxyChannels;
import net.swofty.redisapi.api.RedisAPI;
import net.swofty.velocity.command.ServerStatusCommand;
import net.swofty.velocity.data.CoopDatabase;
import net.swofty.velocity.data.ProfilesDatabase;
import net.swofty.velocity.data.UserDatabase;
import net.swofty.velocity.gamemanager.BalanceConfiguration;
import net.swofty.velocity.gamemanager.BalanceConfigurations;
import net.swofty.velocity.gamemanager.GameManager;
import net.swofty.velocity.gamemanager.TransferHandler;
import net.swofty.velocity.packet.PlayerChannelHandler;
import net.swofty.velocity.redis.ChannelListener;
import net.swofty.velocity.redis.RedisListener;
import net.swofty.velocity.redis.RedisMessage;
import net.swofty.velocity.viaversion.injector.SkyBlockViaInjector;
import net.swofty.velocity.viaversion.loader.SkyBlockVLLoader;
import org.json.JSONObject;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Plugin(
        id = "skyblock",
        name = "SkyBlock",
        version = "1.0",
        description = "SkyBlock plugin for Velocity",
        authors = {"Swofty"}
)
public class SkyBlockVelocity {
    @Getter
    private static ProxyServer server = null;
    @Getter
    private static SkyBlockVelocity plugin;
    @Getter
    private static RegisteredServer limboServer;
    @Getter
    private static boolean shouldAuthenticate = false;
    @Getter
    private static boolean supportCrossVersion = false;
    @Inject
    private ProxyServer proxy;

    @Inject
    public SkyBlockVelocity(ProxyServer tempServer, Logger tempLogger, @DataDirectory Path dataDirectory) {
        plugin = this;
        server = tempServer;

        limboServer = server.registerServer(new ServerInfo("limbo", new InetSocketAddress(Configuration.get("host-name"),
                Integer.parseInt(Configuration.get("limbo-port")))));
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server = proxy;
        shouldAuthenticate = Configuration.getOrDefault("require-authentication", false);
        supportCrossVersion = Configuration.getOrDefault("cross-version-support" , false);

        /**
         * initialize cross version support
         */
        if (supportCrossVersion) {
            ViaLoader.init(null, new SkyBlockVLLoader(), new SkyBlockViaInjector(), null, ViaBackwardsPlatformImpl::new, ViaRewindPlatformImpl::new);
        }
        /**
         * Register packets
         */
        server.getEventManager().register(this, PostLoginEvent.class,
                (AwaitingEventExecutor<PostLoginEvent>) postLoginEvent -> EventTask.withContinuation(continuation -> {
                    injectPlayer(postLoginEvent.getPlayer());
                    continuation.resume();
                }));
        server.getEventManager().register(this, PermissionsSetupEvent.class,
                (AwaitingEventExecutor<PermissionsSetupEvent>) permissionsEvent -> EventTask.withContinuation(continuation -> {
                    permissionsEvent.setProvider(permissionSubject -> PermissionFunction.ALWAYS_FALSE);
                    continuation.resume();
                }));
        server.getEventManager().register(this, DisconnectEvent.class, PostOrder.LAST,
                (AwaitingEventExecutor<DisconnectEvent>) disconnectEvent ->
                        disconnectEvent.getLoginStatus() == DisconnectEvent.LoginStatus.CONFLICTING_LOGIN
                                ? null
                                : EventTask.async(() -> removePlayer(disconnectEvent.getPlayer()))
        );

        /**
         * Register commands
         */

        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta statusCommandMeta = commandManager.metaBuilder("serverstatus")
                .aliases("status")
                .plugin(this)
                .build();

        commandManager.register(statusCommandMeta , new ServerStatusCommand());


        /**
         * Handle database
         */
        new ProfilesDatabase("_placeHolder").connect(Configuration.get("mongodb"));
        UserDatabase.connect(Configuration.get("mongodb"));
        CoopDatabase.connect(Configuration.get("mongodb"));

        /**
         * Setup Redis
         */
        RedisAPI.generateInstance(Configuration.get("redis-uri"));
        RedisAPI.getInstance().setFilterID("proxy");
        loopThroughPackage("net.swofty.velocity.redis.listeners", RedisListener.class)
                .forEach(listener ->  {
                    RedisAPI.getInstance().registerChannel(
                        listener.getClass().getAnnotation(ChannelListener.class).channel().getChannelName(),
                            (event2) -> {
                                listener.onMessage(event2.channel, event2.message);
                            });
                });
        for (FromProxyChannels channel : FromProxyChannels.values()) {
            RedisMessage.registerProxyToServer(channel);
        }
        RedisAPI.getInstance().startListeners();

        /**
         * Setup GameManager
         */
        GameManager.loopServers(server);
    }

    @Subscribe
    public void onPlayerJoin(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();

        if (!GameManager.hasType(ServerType.ISLAND)) {
            player.disconnect(
                    Component.text("§cThere are no SkyBlock instances available at the moment. Please try again later.")
            );
            return;
        }

        List<GameManager.GameServer> gameServers = GameManager.getFromType(ServerType.ISLAND);
        List<BalanceConfiguration> configurations = BalanceConfigurations.configurations.get(ServerType.ISLAND);
        GameManager.GameServer toSendTo = gameServers.getFirst();

        for (BalanceConfiguration configuration : configurations) {
            GameManager.GameServer server = configuration.getServer(player, gameServers);
            if (server != null) {
                toSendTo = server;
                break;
            }
        }

        // TODO: Force Resource Pack
        event.setInitialServer(toSendTo.registeredServer());

        if (shouldAuthenticate) {
            RedisMessage.sendMessageToServer(toSendTo.internalID(),
                    FromProxyChannels.PROMPT_PLAYER_FOR_AUTHENTICATION,
                    new JSONObject().put("uuid", player.getUniqueId().toString()));
        }
    }

    @Subscribe
    public void onServerCrash(KickedFromServerEvent event) {
        // Send the player to the limbo
        RegisteredServer originalServer = event.getServer();
        Component reason = event.getServerKickReason().orElse(Component.text(
                "§cYour connection to the server was lost. Please try again later."
        ));
        ServerType serverType = GameManager.getTypeFromRegisteredServer(originalServer);

        event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                limboServer,
                null
        ));

        TransferHandler transferHandler = new TransferHandler(event.getPlayer());
        transferHandler.standardTransferTo(originalServer, serverType);

        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(GameManager.SLEEP_TIME + 300);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            boolean isOnline = GameManager.getFromRegisteredServer(originalServer) != null;
            if (isOnline) {
                transferHandler.forceRemoveFromLimbo();
                event.getPlayer().disconnect(reason);
                return;
            }

            try {
                GameManager.GameServer server = BalanceConfigurations.getServerFor(event.getPlayer(), serverType);
                if (server == null) {
                    transferHandler.forceRemoveFromLimbo();
                    event.getPlayer().disconnect(reason);
                    return;
                }
                transferHandler.noLimboTransferTo(server.registeredServer());
                event.getPlayer().sendPlainMessage("§cAn exception occurred in your connection, so you were put into another SkyBlock server.");
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.SEVERE, "An exception occurred while trying to transfer " + event.getPlayer().getUsername() + " to " + serverType, e);
                transferHandler.forceRemoveFromLimbo();
                event.getPlayer().disconnect(reason);
            }
        });
    }

    public static <T> Stream<T> loopThroughPackage(String packageName, Class<T> clazz) {
        Reflections reflections = new Reflections(packageName);
        Set<Class<? extends T>> subTypes = reflections.getSubTypesOf(clazz);

        return subTypes.stream()
                .map(subClass -> {
                    try {
                        return clazz.cast(subClass.getDeclaredConstructor().newInstance());
                    } catch (InstantiationException | IllegalAccessException | NoSuchMethodException |
                             InvocationTargetException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull);
    }

    private void injectPlayer(Player player) {
        final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
        Channel channel = connectedPlayer.getConnection().getChannel();
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addBefore(Connections.HANDLER, "PACKET", new PlayerChannelHandler(player));
    }

    private void removePlayer(final Player player) {
        final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
        final Channel channel = connectedPlayer.getConnection().getChannel();

        channel.eventLoop().submit(() -> {
            channel.pipeline().remove("PACKET");
        });
    }
}
