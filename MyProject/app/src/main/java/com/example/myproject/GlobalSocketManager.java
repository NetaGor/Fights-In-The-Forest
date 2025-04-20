/**
 * GlobalSocketManager.java - Manager for socket.io connections
 *
 * This class provides a central place to store and access the socket.io
 * connection throughout the application.
 */
package com.example.myproject;

import io.socket.client.Socket;

public class GlobalSocketManager {
    private static Socket mSocket; // The shared socket instance

    /**
     * Sets the global socket instance
     *
     * @param socket The socket.io Socket instance to store
     */
    public static void setSocket(Socket socket) {
        mSocket = socket;
    }

    /**
     * Gets the global socket instance
     *
     * @return The stored socket.io Socket instance
     */
    public static Socket getSocket() {
        return mSocket;
    }
}