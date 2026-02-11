// =====================================================
// ARCHIVO: ServidorDinoMultijugador.java
// PAQUETE: com.dinochrome.game.net
// =====================================================
package com.dinochrome.game.net;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

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

    // Timeout de jugador (si no manda nada, lo sacamos)
    // Señor: con 5s va bien para pruebas. Si querés más tolerancia: 8000 o 10000.
    private static final long TIMEOUT_JUGADOR_MS = 5000;

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

            // 0) LIMPIAR JUGADORES CAÍDOS (clave para poder reconectar)
            limpiarJugadoresPorTimeout(ahora);

            // 1) Recibir paquetes (si hay)
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(
                    packet.getData(),
                    0,
                    packet.getLength(),
                    StandardCharsets.UTF_8
                ).trim();

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
        }
    }

    // -------------------------
    // Procesamiento de mensajes
    // -------------------------
    private void procesarMensaje(SocketAddress addr, String msg) {

        // 0) DESCUBRIMIENTO POR BROADCAST
        if (msg.equals("BUSCAR_SERVIDOR")) {
            enviarA(addr, "SERVIDOR_AQUI");
            return;
        }

        // (Opcional) BYE explícito
        if (msg.equals("BYE")) {
            desconectarJugador(addr, "BYE");
            return;
        }

        // 1) JOIN
        if (msg.equals("JOIN")) {
            manejarJoin(addr);
            return;
        }

        Jugador j = jugadoresPorAddr.get(addr);
        if (j == null) {
            enviarA(addr, "ERROR;msg=Primero manda JOIN");
            return;
        }

        // MUY IMPORTANTE: actualizar último contacto en cualquier mensaje válido
        j.ultimoPaqueteMs = System.currentTimeMillis();

        // 2) READY
        if (msg.equals("READY")) {
            manejarReady(j);
            return;
        }

        // 3) STATE (relay)
        if (msg.startsWith("STATE;")) {
            EstadoJugador estado = parsearEstado(msg);
            if (estado == null) return;

            j.x = estado.x;
            j.y = estado.y;
            j.duck = estado.duck;

            Jugador otro = obtenerOtroJugador(j.id);
            if (otro != null) {
                enviarA(otro.addr, msg);
            }
            return;
        }

        // 4) Desconocido
        enviarA(addr, "ERROR;msg=Mensaje no reconocido");
    }

    // -------------------------
    // JOIN / READY / START
    // -------------------------
    private void manejarJoin(SocketAddress addr) {

        // Si ya estaba conectado (misma addr), re-enviamos info
        if (jugadoresPorAddr.containsKey(addr)) {
            Jugador j = jugadoresPorAddr.get(addr);
            j.ultimoPaqueteMs = System.currentTimeMillis();

            enviarA(addr, "ASSIGN;id=" + j.id);
            enviarA(addr, "COUNT;players=" + jugadoresPorAddr.size());
            broadcast("COUNT;players=" + jugadoresPorAddr.size());
            return;
        }

        // Si está lleno, no entra
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

        // Mandar COUNT directo y broadcast (UDP puede perderse)
        enviarA(addr, "COUNT;players=" + jugadoresPorAddr.size());
        broadcast("COUNT;players=" + jugadoresPorAddr.size());

        System.out.println("Jugador conectado id=" + idNuevo + " desde " + addr);
    }

    private void manejarReady(Jugador j) {
        j.listo = true;

        broadcast("READY;id=" + j.id + ";value=1");

        System.out.println("Jugador id=" + j.id + " listo");

        if (jugadoresPorAddr.size() == 2 && ambosListos() && !partidaIniciada) {
            partidaIniciada = true;
            broadcast("START");
            System.out.println("Partida iniciada");
        }
    }

    // -------------------------
    // Timeout / desconexión
    // -------------------------
    private void limpiarJugadoresPorTimeout(long ahora) {
        boolean sacoAlguien = false;

        Iterator<Map.Entry<SocketAddress, Jugador>> it = jugadoresPorAddr.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SocketAddress, Jugador> entry = it.next();
            Jugador j = entry.getValue();

            if ((ahora - j.ultimoPaqueteMs) > TIMEOUT_JUGADOR_MS) {
                System.out.println("Jugador id=" + j.id + " timeout. Se elimina (" + entry.getKey() + ")");
                it.remove();
                sacoAlguien = true;
            }
        }

        if (sacoAlguien) {
            // Si alguien se fue, la partida ya no es válida
            if (jugadoresPorAddr.size() < 2) {
                partidaIniciada = false;

                // También reseteamos "listo" del que queda, para que el lobby sea coherente
                for (Jugador j : jugadoresPorAddr.values()) {
                    j.listo = false;
                }
            }

            // Refrescar lobby
            broadcast("COUNT;players=" + jugadoresPorAddr.size());
        }
    }

    private void desconectarJugador(SocketAddress addr, String motivo) {
        Jugador j = jugadoresPorAddr.remove(addr);
        if (j != null) {
            System.out.println("Jugador id=" + j.id + " desconectado (" + motivo + ")");

            if (jugadoresPorAddr.size() < 2) {
                partidaIniciada = false;
                for (Jugador restante : jugadoresPorAddr.values()) {
                    restante.listo = false;
                }
            }

            broadcast("COUNT;players=" + jugadoresPorAddr.size());
        }
    }

    // -------------------------
    // Obstáculos
    // -------------------------
    private String generarObstaculo() {
        boolean cactus = random.nextBoolean();
        int tipo = cactus ? 0 : 1;

        float x = ANCHO;
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

            if (addr instanceof InetSocketAddress) {
                InetSocketAddress isa = (InetSocketAddress) addr;
                p.setAddress(isa.getAddress());
                p.setPort(isa.getPort());
            } else {
                return;
            }

            socket.send(p);
        } catch (Exception ignored) {}
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
