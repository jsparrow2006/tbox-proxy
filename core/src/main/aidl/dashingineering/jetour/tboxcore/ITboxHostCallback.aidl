package dashingineering.jetour.tboxcore;

oneway interface ITboxHostCallback {
    void onDataReceived(in byte[] data);
    void onLogMessage(String level, String tag, String message);
    void onHostDied();
    void onHostConnected();
    void onHostDisconnected();
}