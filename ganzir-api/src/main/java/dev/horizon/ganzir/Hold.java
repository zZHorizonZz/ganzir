package dev.horizon.ganzir;

import io.vertx.core.Future;
import java.time.Instant;

/**
 * A hold on a node, which stops the node from being deleted while it exists.
 */
public interface Hold {

  /**
   * Returns the id of the hold, unique on its node.
   */
  String id();

  String origin();

  String reason();

  Instant createTime();

  /**
   * Releases the hold. Releasing a hold that was already released does nothing.
   */
  Future<Void> release();
}
