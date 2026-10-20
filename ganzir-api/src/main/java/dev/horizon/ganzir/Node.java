package dev.horizon.ganzir;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.pyrite.common.Revision;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A container in a hierarchy. A node holds the values that were current when it was read. Its methods read it again
 * before they act.
 *
 * <p>
 * A change that takes {@code expected} fails with {@code AbortedException} if the node changed after that revision.
 */
public interface Node {

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

  String displayName();

  /**
   * Returns the labels of the node, which filters can select by.
   */
  Map<String, String> labels();

  /**
   * Returns the annotations of the node, which filters cannot select by.
   */
  Map<String, String> annotations();

  State state();

  Revision revision();

  Instant createTime();

  Instant updateTime();

  /**
   * Returns when the node was deleted, or nothing if it is active.
   */
  Optional<Instant> deleteTime();

  /**
   * Returns the holds on the node, which stop it from being deleted.
   */
  List<Hold> holds();

  /**
   * Returns the finalizers of the node, which delay its purge after a delete.
   */
  List<String> finalizers();

  /**
   * Creates a child node without metadata.
   *
   * @see #create(String, String, Metadata)
   */
  Future<Node> create(String type, String name);

  /**
   * Creates a child node.
   *
   * <p>
   * The future fails with {@code InvalidArgumentException} if the model does not declare the type, with
   * {@code FailedPreconditionException} and the reason {@link Reason#RULE} if a rule refuses the child, with the reason
   * {@link Reason#STATE} if this node is not active, and with {@code AlreadyExistsException} if the name is taken
   * within the scope of the type.
   */
  Future<Node> create(String type, String name, Metadata metadata);

  /**
   * Returns the child of the type with the name. The future fails with {@code NotFoundException} if this node has no
   * such child.
   */
  Future<Node> child(String type, String name);

  /**
   * Returns a page of the children of this node, ordered by name. The filter is in the syntax of AIP-160, over
   * {@code type}, {@code state} and {@code labels}.
   */
  Future<Page<Node>> children(PageRequest request);

  /**
   * Returns a page of every node below this node, at any depth, ordered by name. The filter is as for
   * {@link #children}.
   */
  Future<Page<Node>> descendants(PageRequest request);

  /**
   * Returns the ancestors of this node, nearest first, up to its root. A root has none.
   */
  Future<List<Node>> ancestors();

  /**
   * Replaces the display name, the labels and the annotations of the node.
   */
  Future<Node> update(Metadata metadata, Revision expected);

  /**
   * Renames the node. The future fails with {@code AlreadyExistsException} if the name is taken within the scope of
   * its type, and with {@code FailedPreconditionException} and the reason {@link Reason#RULE} if a rule refuses the
   * name.
   */
  Future<Node> rename(String name, Revision expected);

  /**
   * Moves the node, and everything below it, under another parent.
   *
   * <p>
   * The future fails with {@code FailedPreconditionException} and the reason {@link Reason#RULE} if a rule of the
   * node's type or of the parent's type refuses the move, or if the parent is this node or below it. It fails with
   * the reason {@link Reason#STATE} if either node is not active, and with {@code AlreadyExistsException} if the name
   * of the node is taken under the new parent.
   */
  Future<Node> move(Node parent, Revision expected);

  /**
   * Deletes the node. Its state becomes {@link State#DELETED}, or {@link State#DELETING} while it has finalizers. It
   * keeps its name, and can be restored until the retention of its type ends and it is purged.
   *
   * <p>
   * The future fails with {@code FailedPreconditionException} and the reason {@link Reason#CHILDREN} if the node
   * has children that are not deleted, with the reason {@link Reason#HELD} if it has holds, and with the reason
   * {@link Reason#STATE} if it is already deleted.
   */
  Future<Node> delete(Revision expected);

  /**
   * Makes a deleted node active again. The future fails with {@code FailedPreconditionException} and the reason
   * {@link Reason#STATE} if the node is active, or if its parent is deleted.
   */
  Future<Node> restore(Revision expected);

  /**
   * Places a hold on the node, which stops it from being deleted until the hold is released.
   *
   * @param origin who places the hold, such as {@code billing}
   * @param reason why, for people, such as {@code An active subscription is billed to this project.}
   */
  Future<Hold> hold(String origin, String reason);

  /**
   * Adds a finalizer to the node. If the node is deleted, it is purged only after every finalizer is removed. Adding
   * a finalizer that the node has does nothing. The future fails with {@code FailedPreconditionException} and the
   * reason {@link Reason#STATE} if the node is deleted.
   */
  Future<Node> addFinalizer(String finalizer);

  /**
   * Removes a finalizer from the node. Removing the last finalizer of a node in {@link State#DELETING} makes it
   * {@link State#DELETED}. Removing a finalizer that the node does not have does nothing.
   */
  Future<Node> removeFinalizer(String finalizer);
}
