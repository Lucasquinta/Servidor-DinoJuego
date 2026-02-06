package com.dinochrome.game.net;

public class PlayerState {
    public int playerId;
    public float x;
    public float y;
    public boolean ducking;

    // 🔹 lobby
    public boolean ready;
    public int playerCount;
    public boolean startGame;
}
