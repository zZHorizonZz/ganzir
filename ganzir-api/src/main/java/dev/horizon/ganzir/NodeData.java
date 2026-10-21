package dev.horizon.ganzir;

import dev.horizon.pyrite.common.Metadata;
import dev.horizon.pyrite.common.Revision;
import java.time.Instant;
import java.util.Optional;

/**
 * The values of a node, without the operations of {@link Node}.
 */
public interface NodeData {

  /**
   * Returns the id that Ganzir generated for the node. It never changes.
   */
  String id();

  String type();

  /**
   * Returns the name of the node, which is unique within the {@link Scope} of its type.
   */
  String name();

  /**
   * Returns the id of the parent, or nothing for a root.
   */
  Optional<String> parent();

  /**
   * Returns the display name, the labels and the annotations of the node.
   */
  Metadata metadata();

  State state();

  Revision revision();

  Instant createTime();

  Instant updateTime();

  /**
   * Returns when the node was deleted, or nothing if it is active.
   */
  Optional<Instant> deleteTime();
}
