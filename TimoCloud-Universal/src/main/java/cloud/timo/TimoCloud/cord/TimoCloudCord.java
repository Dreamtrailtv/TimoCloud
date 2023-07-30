package cloud.timo.TimoCloud.cord;

import cloud.timo.TimoCloud.api.TimoCloudAPI;
import cloud.timo.TimoCloud.api.implementations.internal.TimoCloudInternalImplementationAPIBasicImplementation;
import cloud.timo.TimoCloud.api.implementations.managers.APIResponseManager;
import cloud.timo.TimoCloud.api.implementations.managers.EventManager;
import cloud.timo.TimoCloud.api.utils.APIInstanceUtil;
import cloud.timo.TimoCloud.common.encryption.RSAKeyPairRetriever;
import cloud.timo.TimoCloud.common.encryption.RSAKeyUtil;
import cloud.timo.TimoCloud.common.modules.ModuleType;
import cloud.timo.TimoCloud.common.modules.TimoCloudModule;
import cloud.timo.TimoCloud.common.protocol.Message;
import cloud.timo.TimoCloud.common.protocol.MessageType;
import cloud.timo.TimoCloud.common.sockets.AESDecrypter;
import cloud.timo.TimoCloud.common.sockets.AESEncrypter;
import cloud.timo.TimoCloud.common.sockets.RSAHandshakeHandler;
import cloud.timo.TimoCloud.common.utils.options.OptionSet;
import cloud.timo.TimoCloud.cord.api.TimoCloudInternalMessageAPICordImplementation;
import cloud.timo.TimoCloud.cord.api.TimoCloudMessageAPICordImplementation;
import cloud.timo.TimoCloud.cord.api.TimoCloudUniversalAPICordImplementation;
import cloud.timo.TimoCloud.cord.managers.CordFileManager;
import cloud.timo.TimoCloud.cord.managers.ProxyManager;
import cloud.timo.TimoCloud.cord.sockets.*;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

import java.io.File;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TimoCloudCord implements TimoCloudModule {

    private static final SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BLACK = "\u001B[30m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_PURPLE = "\u001B[35m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_WHITE = "\u001B[37m";

    private static TimoCloudCord instance;
    private OptionSet options;
    private String prefix = ANSI_YELLOW + "[" +ANSI_CYAN + "Timo" + ANSI_RESET + "Cloud" + ANSI_YELLOW + "]" + ANSI_RESET;
    private CordFileManager fileManager;
    private ProxyManager proxyManager;
    private CordSocketClient socketClient;
    private CordSocketClientHandler socketClientHandler;
    private CordSocketMessageManager socketMessageManager;
    private CordSocketServer socketServer;
    private MinecraftDecoder minecraftDecoder;
    private CordStringHandler stringHandler;
    private ScheduledExecutorService scheduler;
    private Channel channel;
    private boolean connected = false;

    private boolean handshakePerformed = false;
    private boolean publicKeyPrinted;
    private EventLoopGroup workerGroup;


    private RSAKeyPairRetriever rsaKeyPairRetriever;

    public static String getTime() {
        return "[" + format.format(new Date()) + "] ";
    }

    private String formatLog(String message, String color) {
        return (getTime() + getPrefix() + color + message + ANSI_RESET);
    }

    @Override
    public void info(String message) {
        System.out.println(formatLog(message, ANSI_RESET));
    }

    @Override
    public void warning(String message) {
        System.err.println(formatLog(message, ANSI_YELLOW));
    }

    @Override
    public void severe(String message) {
        System.err.println(formatLog(message, ANSI_RED));
    }

    @Override
    public void load(OptionSet optionSet) throws Exception {
        this.options = optionSet;
        makeInstances();
        new Thread(this::initSocketServer).start();
        info(ANSI_GREEN + "TimoCloudCord has been loaded");
        scheduleConnecting();
    }

    @Override
    public void unload() {

    }

    private void makeInstances() throws Exception {
        instance = this;
        fileManager = new CordFileManager();
        rsaKeyPairRetriever = new RSAKeyPairRetriever(new File(getFileManager().getBaseDirectory(), "keys/"));
        proxyManager = new ProxyManager();
        socketClient = new CordSocketClient();
        socketClientHandler = new CordSocketClientHandler();
        socketMessageManager = new CordSocketMessageManager();
        socketServer = new CordSocketServer();
        minecraftDecoder = new MinecraftDecoder();
        stringHandler = new CordStringHandler();
        scheduler = Executors.newScheduledThreadPool(1);
        workerGroup = new NioEventLoopGroup();

        APIInstanceUtil.setInternalMessageInstance(new TimoCloudInternalMessageAPICordImplementation());
        APIInstanceUtil.setEventInstance(new EventManager());
        APIInstanceUtil.setUniversalInstance(new TimoCloudUniversalAPICordImplementation());
        APIInstanceUtil.setMessageInstance(new TimoCloudMessageAPICordImplementation());
        APIInstanceUtil.setInternalImplementationAPIInstance(new TimoCloudInternalImplementationAPIBasicImplementation());
        TimoCloudAPI.getMessageAPI().registerMessageListener(new APIResponseManager(), "TIMOCLOUD_API_RESPONSE");
    }

    private void scheduleConnecting() {
        scheduler.scheduleAtFixedRate(this::everySecond, 0, 1, TimeUnit.SECONDS);
        alertConnecting();
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public void alertConnecting() {
        info("Connecting to Core...");
    }

    private void everySecond() {
        try {
            connectToSocket();
        } catch (Exception e) {
            TimoCloudCord.getInstance().severe(e);
        }
    }

    private void connectToSocket() {
        if (isConnected()) return;
        new Thread(() -> {
            try {
                getSocketClient().init(getCoreSocketIP(), getCoreSocketPort());
            } catch (Exception e) {
            }
        }).start();
    }

    public void onSocketConnect(Channel channel) {
        setConnected(true);
        try {
            if (! getRsaKeyPairRetriever().isValidKeyPairExisting()) {
                KeyPair keyPair = getRsaKeyPairRetriever().generateKeyPair();
                info(String.format("Successfully generated public key! Please register this base at the Core by executing the following command in the Core console: '%saddbase %s'", ANSI_RED, RSAKeyUtil.publicKeyToBase64(keyPair.getPublic()) + ANSI_RESET));
                this.publicKeyPrinted = true;
                disconnect(channel);
            }
            KeyPair keyPair = getRsaKeyPairRetriever().getKeyPair();
            new RSAHandshakeHandler(channel, keyPair, (aesKey -> {
                channel.pipeline().addBefore("prepender", "decrypter", new AESDecrypter(aesKey));
                channel.pipeline().addBefore("prepender", "decoder", new StringDecoder(CharsetUtil.UTF_8));
                channel.pipeline().addBefore("prepender", "handler", TimoCloudCord.getInstance().getStringHandler());
                channel.pipeline().addLast( "encrypter", new AESEncrypter(aesKey));
                channel.pipeline().addLast("encoder", new StringEncoder(CharsetUtil.UTF_8));

                getSocketMessageManager().sendMessage(Message.create().setType(MessageType.CORD_HANDSHAKE).set("cord", getName()));
                info("Successfully connected to Core socket!");
            })).startHandshake();
        } catch (Exception e) {
            severe(e);
            disconnect(channel);
        }
    }

    private void disconnect(Channel channel) {
        channel.close();
        setConnected(false);
    }

    public void onSocketDisconnect() {
        if (isConnected()) {
            if (handshakePerformed) info("Disconnected from Core. Reconnecting...");
            else {
                if (! publicKeyPrinted) {
                    try {
                        info(String.format("In order to be able to connect to the Core, you have to register this base by executing the command '%saddbase %s' in the Core console.", ANSI_RED, RSAKeyUtil.publicKeyToBase64(getRsaKeyPairRetriever().getKeyPair().getPublic()) + ANSI_RESET));
                    } catch (Exception e) {
                        severe(e);
                    }
                    publicKeyPrinted = true;
                }
            }
            handshakePerformed = false;
        }
        setConnected(false);

    }

    public void onHandshakeSuccess() {
        getSocketMessageManager().sendMessage(Message.create().setType(MessageType.GET_API_DATA));
        handshakePerformed = true;
    }

    private void initSocketServer() {
        try {
            socketServer.init("0.0.0.0", getProxyPort());
        } catch (Exception e) {
            severe("Error while initializing socket server:");
            TimoCloudCord.getInstance().severe(e);
            System.exit(1);
        }
    }

    private int getProxyPort() {
        Object port = getFileManager().getConfig().get("proxy-port");
        if (port != null) {
            try {
                return (Integer) port;
            } catch (Exception e) {}
        }
        info("No proxy port specified, using any free port.");
        return getFreePort();
    }

    private Integer getFreePort() {
        for (int p = 40000; p<=50000; p++) {
            if (portIsFree(p)) return p;
        }
        return null;
    }


    private boolean portIsFree(int port) {
        try {
            new ServerSocket(port).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getFileName() {
        return new File(this.getClass().getProtectionDomain().getCodeSource().getLocation().getFile()).getName();
    }

    public String getName() {
        return (String) getFileManager().getConfig().get("name");
    }

    public String getCoreSocketIP() {
        return (String) getFileManager().getConfig().get("core-ip");
    }

    public Integer getCoreSocketPort() {
        return (Integer) getFileManager().getConfig().get("core-port");
    }

    public RSAKeyPairRetriever getRsaKeyPairRetriever() {
        return rsaKeyPairRetriever;
    }
    public static TimoCloudCord getInstance() {
        return instance;
    }

    public String getPrefix() {
        return prefix + " ";
    }

    public CordFileManager getFileManager() {
        return fileManager;
    }

    public ProxyManager getProxyManager() {
        return proxyManager;
    }

    public CordSocketClient getSocketClient() {
        return socketClient;
    }

    public CordSocketClientHandler getSocketClientHandler() {
        return socketClientHandler;
    }

    public CordSocketMessageManager getSocketMessageManager() {
        return socketMessageManager;
    }

    public CordSocketServer getSocketServer() {
        return socketServer;
    }

    public MinecraftDecoder getMinecraftDecoder() {
        return minecraftDecoder;
    }

    public CordStringHandler getStringHandler() {
        return stringHandler;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    @Override
    public ModuleType getModuleType() {
        return ModuleType.CORD;
    }
}
