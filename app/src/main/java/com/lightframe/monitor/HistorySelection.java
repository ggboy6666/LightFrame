package com.lightframe.monitor;
/** UI-thread request arbitration: a default load never advances or defeats a user request. */
public final class HistorySelection {
 private int serial;
 public int stamp(){return serial;}
 public int request(){return ++serial;}
 public boolean current(int stamp){return serial==stamp;}
}
