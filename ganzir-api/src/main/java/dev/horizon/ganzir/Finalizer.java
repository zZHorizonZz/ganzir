package dev.horizon.ganzir;

import io.vertx.core.Future;

/**
 * A finalizer of a node. When the node is deleted, it stays {@link State#DELETING} until every finalizer is removed.
 */
public interface Finalizer {

  /**
   * Returns the name of the finalizer, such as {@code storage.example.com/buckets}. It is unique on its node.
   */
  String name();

  /**
   * Removes the finalizer. Removing the last finalizer of a node in {@link State#DELETING} makes the node
   * {@link State#DELETED}. Removing a finalizer that was already removed does nothing.
   */
  Future<Void> remove();
}
