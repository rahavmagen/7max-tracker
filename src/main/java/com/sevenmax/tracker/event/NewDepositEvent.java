package com.sevenmax.tracker.event;

/** Published right after a Grow or KashCash webhook creates a new unconfirmed deposit -
 *  lets DepositWaitService wake up any automation currently long-polling for one. */
public record NewDepositEvent(String source) {
}
