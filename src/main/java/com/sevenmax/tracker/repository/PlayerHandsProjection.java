package com.sevenmax.tracker.repository;

public interface PlayerHandsProjection {
    Long getPlayerId();
    String getUsername();
    String getFullName();
    Long getTotalHands();
    Long getSessionCount();
}
