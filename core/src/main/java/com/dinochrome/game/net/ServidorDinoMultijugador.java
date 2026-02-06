package com.dinochrome.game.net;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ServidorDinoMultijugador {

    // -------------------------
    // Config
    // -------------------------
    private static final int PUERTO = 4321;
    private static final int MAX_JUGADORES = 2;

    // Mundo (tiene que coincidir con la pantalla)
    private static final int ANCHO = 800;
    private static final float Y_SUELO = 40f;

    // Obstáculos
    private static final long MS_ENTRE_SPAWNS_MIN = 900;
    private static final long MS_ENTRE_SPAWNS_MAX = 1600;

    // -------------------------
    // Estado del servidor
    // -------------------------
    private final DatagramSocket socket;

    // Jugadores conectados (SocketAddress -> jugador)
    private final Map<SocketAddress, Jugador> jugadoresPorAddr = new HashMap<>();

    private boolean partidaIniciada = false;

    // Para spawnear obstáculos
    private final Random random = new Random();
    private long proximoSpawnMs = 0;

    // -------------------------
    // Tipos internos
    // -------------------------
    private static class Jugador {
        int id; // 1 o 2
        SocketAddress addr;
        boolean listo;

        // último estado recibido (por si querés debug)
        float x, y;
        boolean duck;
        long ultimoPaqueteMs;
    }

    private static class EstadoJugador {
        int id;
        float x;
        float y;
        boolean duck;
    }

    // -------------------------
    // Constructor / main
    // -------------------------
    public ServidorDinoMultijugador() throws SocketException {
        socket = new DatagramSocket(PUERTO);
        socket.setSoTimeout(200); // loop no bloqueante eterno
        System.out.println("Servidor Dino escuchando en UDP puerto " + PUERTO);
        planificarProximoSpawn();
    }

    public static void main(String[] args) throws Exception {
        new ServidorDinoMultijugador().loop();
    }

    // -------------------------
    // Loop principal
    // -------------------------
    public void loop() {
        byte[] buffer = new byte[2048];

        while (true) {
            long ahora = System.currentTimeMillis();

            // 1) Recibir paquetes (si hay)
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                SocketAddress addr = packet.getSocketAddress();

                procesarMensaje(addr, msg);

            } catch (SocketTimeoutException timeout) {
                // normal
            } catch (IOException e) {
                e.printStackTrace();
            }

            // 2) Spawnear obstáculos si la partida ya arrancó
            if (partidaIniciada && ahora >= proximoSpawnMs) {
                enviarObstaculoATodos(generarObstaculo());
                planificarProximoSpawn();
            }

            // 3) (Opcional) detectar timeouts / desconexiones
            // Si querés, te lo agrego. Por ahora lo dejo simple.
        }
    }

    // -------------------------
    // Procesamiento de mensajes
    // -------------------------
    private void procesarMensaje(SocketAddress addr, String msg) {
        // Si no está registrado y quiere unirse
        if (msg.equals("JOIN")) {
            manejarJoin(addr);
            return;
        }

        Jugador j = jugadoresPorAddr.get(addr);
        if (j == null) {
            // Si manda algo sin JOIN previo, lo ignoramos o lo forzamos a JOIN
            enviarA(addr, "ERROR;msg=Primero manda JOIN");
            return;
        }

        j.ultimoPaqueteMs = System.currentTimeMillis();

        if (msg.equals("READY")) {
            manejarReady(j);
            return;
        }

        if (msg.startsWith("STATE;")) {
            EstadoJugador estado = parsearEstado(msg);
            if (estado == null) return;

            // Actualizo estado interno (opcional)
            j.x = estado.x;
            j.y = estado.y;
            j.duck = estado.duck;

            // Relay: enviar al otro jugador
            Jugador otro = obtenerOtroJugador(j.id);
            if (otro != null) {
                enviarA(otro.addr, msg); // reenviamos tal cual
            }
            return;
        }

        // Mensaje desconocido
        enviarA(addr, "ERROR;msg=Mensaje no reconocido");
    }

    // -------------------------
    // JOIN / READY / START
    // -------------------------
    private void manejarJoin(SocketAddress addr) {
        if (jugadoresPorAddr.containsKey(addr)) {
            // ya estaba
            Jugador j = jugadoresPorAddr.get(addr);
            enviarA(addr, "ASSIGN;id=" + j.id);
            enviarA(addr, "COUNT;players=" + jugadoresPorAddr.size());
            return;
        }

        if (jugadoresPorAddr.size() >= MAX_JUGADORES) {
            enviarA(addr, "FULL");
            return;
        }

        int idNuevo = (existeId(1) ? 2 : 1);

        Jugador j = new Jugador();
        j.id = idNuevo;
        j.addr = addr;
        j.listo = false;
        j.ultimoPaqueteMs = System.currentTimeMillis();

        jugadoresPorAddr.put(addr, j);

        enviarA(addr, "ASSIGN;id=" + idNuevo);
        broadcast("COUNT;players=" + jugadoresPorAddr.size());

        System.out.println("Jugador conectado id=" + idNuevo + " desde " + addr);

        // Si ya hay 2, todavía no arrancamos: esperamos READY de ambos.
    }

    private void manejarReady(Jugador j) {
        j.listo = true;

        // (Opcional) avisar al otro
        broadcast("READY;id=" + j.id + ";value=1");

        System.out.println("Jugador id=" + j.id + " listo");

        if (jugadoresPorAddr.size() == 2 && ambosListos() && !partidaIniciada) {
            partidaIniciada = true;
            broadcast("START");
            System.out.println("Partida iniciada");
        }
    }

    // -------------------------
    // Obstáculos
    // -------------------------
    private String generarObstaculo() {
        // Cactus o ptero
        boolean cactus = random.nextBoolean();
        int tipo = cactus ? 0 : 1;

        float x = ANCHO; // aparece en el borde derecho
        float y;
        float w;
        float h;

        if (cactus) {
            y = Y_SUELO;
            w = 20 + random.nextInt(10);
            h = 30 + random.nextInt(10);
        } else {
            y = random.nextBoolean() ? (Y_SUELO + 15) : (Y_SUELO + 30);
            w = 40;
            h = 20;
        }

        // Mensaje
        return "OBST;x=" + x + ";y=" + y + ";w=" + w + ";h=" + h + ";t=" + tipo;
    }

    private void enviarObstaculoATodos(String obstMsg) {
        broadcast(obstMsg);
    }

    private void planificarProximoSpawn() {
        long ahora = System.currentTimeMillis();
        long rango = MS_ENTRE_SPAWNS_MAX - MS_ENTRE_SPAWNS_MIN;
        long delta = MS_ENTRE_SPAWNS_MIN + (rango > 0 ? random.nextInt((int) rango) : 0);
        proximoSpawnMs = ahora + delta;
    }

    // -------------------------
    // Parseo de estado
    // -------------------------
    private EstadoJugador parsearEstado(String msg) {
        // Espera: STATE;id=1;x=...;y=...;duck=0
        try {
            String[] partes = msg.split(";");
            if (partes.length < 5) return null;

            EstadoJugador e = new EstadoJugador();

            for (int i = 1; i < partes.length; i++) {
                String p = partes[i];
                String[] kv = p.split("=");
                if (kv.length != 2) continue;

                String k = kv[0];
                String v = kv[1];

                if (k.equals("id")) e.id = Integer.parseInt(v);
                else if (k.equals("x")) e.x = Float.parseFloat(v);
                else if (k.equals("y")) e.y = Float.parseFloat(v);
                else if (k.equals("duck")) e.duck = v.equals("1") || v.equalsIgnoreCase("true");
            }

            if (e.id != 1 && e.id != 2) return null;
            return e;

        } catch (Exception ex) {
            return null;
        }
    }

    // -------------------------
    // Utilidades
    // -------------------------
    private void enviarA(SocketAddress addr, String msg) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length);

            // addr puede ser InetSocketAddress
            if (addr instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) addr;
                p.setAddress(isa.getAddress());
                p.setPort(isa.getPort());
            } else {
                // caso raro, intentamos igual
                return;
            }

            socket.send(p);
        } catch (Exception e) {
            // si falla, no reventamos el servidor
        }
    }

    private void broadcast(String msg) {
        for (Jugador j : jugadoresPorAddr.values()) {
            enviarA(j.addr, msg);
        }
    }

    private boolean existeId(int id) {
        for (Jugador j : jugadoresPorAddr.values()) {
            if (j.id == id) return true;
        }
        return false;
    }

    private Jugador obtenerOtroJugador(int miId) {
        int otroId = (miId == 1) ? 2 : 1;
        for (Jugador j : jugadoresPorAddr.values()) {
            if (j.id == otroId) return j;
        }
        return null;
    }

    private boolean ambosListos() {
        boolean listo1 = false;
        boolean listo2 = false;

        for (Jugador j : jugadoresPorAddr.values()) {
            if (j.id == 1) listo1 = j.listo;
            if (j.id == 2) listo2 = j.listo;
        }
        return listo1 && listo2;
    }
}
