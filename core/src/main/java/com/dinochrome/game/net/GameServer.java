package com.dinochrome.game.net;

import com.esotericsoftware.kryonet.Server;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryo.Kryo;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class GameServer {

	// ===== SERVER STATE =====
	private Server server;
	private int playerCount = 0;

	// ===== READY STATE =====
	private Map<Connection, Boolean> readyMap = new HashMap<>();

	// ===== OBSTACLES =====
	private float obstacleTimer = 0f;
	private int nextObstacleId = 1;
	private boolean gameStarted = false;

    public GameServer() throws IOException {

        server = new Server();

        Kryo kryo = server.getKryo();
        kryo.register(PlayerState.class);
        kryo.register(LobbyState.class);
        kryo.register(StartGame.class);
        kryo.register(ObstacleState.class);

        server.addListener(new Listener() {

            @Override
            public void connected(Connection connection) {

                playerCount++;
                readyMap.put(connection, false);

                // 🔹 avisar lobby
                LobbyState lobby = new LobbyState();
                lobby.playerCount = playerCount;
                server.sendToAllTCP(lobby);

                System.out.println("Jugador conectado ID=" + connection.getID());
            }

            @Override
            public void received(Connection connection, Object object) {

                if (object instanceof PlayerState) {
                    PlayerState ps = (PlayerState) object;

                    // ===== READY =====
                    if (ps.ready) {
                        readyMap.put(connection, true);
                        System.out.println("Jugador READY ID=" + connection.getID());
                        checkStartGame();
                        return;
                    }

                    // ===== GAME STATE =====
                    ps.playerId = connection.getID(); // 🔥 SIEMPRE acá
                    server.sendToAllUDP(ps);
                }
            }

            @Override
            public void disconnected(Connection connection) {

                playerCount--;
                readyMap.remove(connection);

                LobbyState lobby = new LobbyState();
                lobby.playerCount = playerCount;
                server.sendToAllTCP(lobby);

                System.out.println("Jugador desconectado ID=" + connection.getID());
            }

            private void checkStartGame() {
                if (readyMap.size() < 2) return;

                for (boolean ready : readyMap.values()) {
                    if (!ready) return;
                }

                System.out.println("🔥 Ambos jugadores READY → StartGame");
                server.sendToAllTCP(new StartGame());
                gameStarted = true;
            }
        });

        server.start();
        server.bind(54555, 54777);
        System.out.println("🟢 Servidor iniciado");
        new Thread(() -> {
            long lastTime = System.currentTimeMillis();

            while (true) {
                long now = System.currentTimeMillis();
                float delta = (now - lastTime) / 1000f;
                lastTime = now;

                updateServer(delta);

                try {
                    Thread.sleep(16); // ~60 FPS
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }
    
    public void update(float delta) {
        updateServer(delta);
    }

    public void stop() {
        if (server != null) {
            server.stop();
            server.close();
            server = null;
            System.out.println("🔴 Server cerrado");
        }
    }
    
    private void updateServer(float delta) {

        if (!gameStarted) return;
        if (playerCount < 2) return;

        obstacleTimer += delta;

        if (obstacleTimer >= 1.5f) {
            obstacleTimer = 0f;

            ObstacleState o = new ObstacleState();
            o.id = nextObstacleId++;
            o.x = 800;

            boolean isCactus = Math.random() > 0.5;

            if (isCactus) {
                o.type = 0;
                o.y = 40;
                o.width = 25;
                o.height = 40;
            } else {
                o.type = 1;
                o.y = Math.random() > 0.5 ? 55 : 75;
                o.width = 40;
                o.height = 25;
            }

            System.out.println("🪨 Obstáculo enviado ID=" + o.id);
            server.sendToAllTCP(o); // ✅ ahora seguro
        }
    }
}
