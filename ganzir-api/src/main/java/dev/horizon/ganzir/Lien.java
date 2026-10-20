package dev.horizon.ganzir;

import io.vertx.core.Future;
import java.time.Instant;

/**
 * A lien on a node, which stops the node from being deleted while the lien exists.
 */
public interface Lien {

  /**
   * Returns the id that Ganzir generated for the lien. It is unique on its node.
   */
  String id();

  /**
   * Returns who placed the lien, such as {@code billing}.
   */
  String origin();

  /**
   * Returns why the lien was placed, for people.
   */
  String reason();

  Instant createTime();

  /**
   * Removes the lien. Removing a lien that was already removed does nothing.
   */
  Future<Void> remove();
}
