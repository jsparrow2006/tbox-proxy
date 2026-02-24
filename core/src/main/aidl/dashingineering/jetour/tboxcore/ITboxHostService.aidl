package dashingineering.jetour.tboxcore;

import dashingineering.jetour.tboxcore.ITboxHostCallback;

interface ITboxHostService {
    void registerCallback(ITboxHostCallback callback);
    void unregisterCallback(ITboxHostCallback callback);

    boolean start(in String ip, int port);
    void stop();
    boolean sendCommand(in byte[] command);

    boolean isRunning();
    String getHostPackageName();
}