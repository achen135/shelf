package com.achen.shelf.signal;

/** What a shopper should do about a product's price today. */
public enum Signal {
  /** A good price that the trailing year says is unlikely to improve soon. */
  BUY,
  /** Not a good price for this product, and it does go on sale. */
  WAIT,
  /** No call: nothing to buy, too little history, or nothing that points either way. */
  NEUTRAL;

  /** The value stored in {@code deal_signals.signal}. */
  public String dbValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /** The enum for a stored value. */
  public static Signal fromDbValue(String value) {
    return valueOf(value.toUpperCase(java.util.Locale.ROOT));
  }
}
