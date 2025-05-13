/**
 * GlobalSocketManager - Socket.IO connection manager
 *
 * Stores and provides access to the shared socket connection
 * across activities.
 */
package com.example.myproject;

import io.socket.client.Socket;

public class GlobalSocketManager {
    private static Socket mSocket;

    public static void setSocket(Socket socket) {
        mSocket = socket;
    }

    public static Socket getSocket() {
        return mSocket;
    }
}