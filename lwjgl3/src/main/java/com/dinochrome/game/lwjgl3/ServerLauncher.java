package com.dinochrome.game.lwjgl3;

import com.dinochrome.game.net.GameServer;

public class ServerLauncher {

    public static void main(String[] args) {
        try {
            new GameServer();
            System.out.println("Server running...");

            while (true) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
