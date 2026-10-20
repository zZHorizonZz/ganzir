package dev.horizon.ganzir;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.pyrite.common.Revision;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
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

  /**
   * Returns the liens on the node, which stop it from being deleted.
   */
  List<Lien> liens();

  /**
   * Returns the finalizers of the node, which keep it {@link State#DELETING} after a delete.
   */
  List<Finalizer> finalizers();

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
   * The future fails with {@code InvalidArgumentException} if the model does not declare the type or the name is not
   * valid, with {@code FailedPreconditionException} and the reason {@link Reason#RULE} if a rule refuses the child, with
   * the reason {@link Reason#STATE} if this node is not active, and with {@code AlreadyExistsException} if the name is
   * taken within the scope of the type.
   */
  Future<Node> create(String type, String name, Metadata metadata);

  /**
   * Returns the child of the type with the name. The future fails with {@code NotFoundException} if this node has no
   * such child.
   */
  Future<Node> child(String type, String name);

  /**
   * Returns a page of the children of this node, ordered by name. The filter is in the syntax of AIP-160, over
   * {@code type}, {@code state}, {@code name}, {@code display_name} and {@code labels}.
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
   * Renames the node. The future fails with {@code InvalidArgumentException} if the name is not valid, and with
   * {@code AlreadyExistsException} if the name is taken within the scope of its type.
   */
  Future<Node> rename(String name, Revision expected);

  /**
   * Moves the node, and everything below it, under another parent.
   *
   * <p>
   * The future fails with {@code FailedPreconditionException} and the reason {@link Reason#RULE} if a rule refuses the
   * node under the parent or a node below it at its new depth, or if the parent is this node or below it. It fails with
   * the reason {@link Reason#STATE} if either node is not active, and with {@code AlreadyExistsException} if the name
   * of the node is taken under the new parent.
   */
  Future<Node> move(Node parent, Revision expected);

  /**
   * Deletes the node. Its state becomes {@link State#DELETED}, or {@link State#DELETING} while it has finalizers. It
   * keeps its name, and can be restored until the retention of its type ends and it is purged.
   *
   * <p>
   * The future fails with {@code FailedPreconditionException} and the reason {@link Reason#CHILDREN} if the node has
   * children that are not deleted, with the reason {@link Reason#LIEN} if it has liens, and with the reason
   * {@link Reason#STATE} if it is already deleted.
   */
  Future<Node> delete(Revision expected);

  /**
   * Makes a deleted node active again. The future fails with {@code FailedPreconditionException} and the reason
   * {@link Reason#STATE} if the node is active, or if its parent is not active.
   */
  Future<Node> restore(Revision expected);

  /**
   * Places a lien on the node, which stops it from being deleted until the lien is removed. The future fails with
   * {@code FailedPreconditionException} and the reason {@link Reason#STATE} if the node is not active.
   *
   * @param origin who places the lien, 1 to 255 characters, such as {@code billing}
   * @param reason why, for people, up to 1024 characters, such as
   * {@code An active subscription is billed to this project.}
   */
  Future<Lien> lien(String origin, String reason);

  /**
   * Adds a finalizer to the node, or returns the finalizer of that name if the node has it. The future fails with
   * {@code FailedPreconditionException} and the reason {@link Reason#STATE} if the node is not active.
   *
   * @param name 1 to 255 letters, digits, {@code .}, {@code _}, {@code ~}, {@code /} and {@code -}, such as
   * {@code storage.example.com/buckets}
   */
  Future<Finalizer> finalizer(String name);
}
