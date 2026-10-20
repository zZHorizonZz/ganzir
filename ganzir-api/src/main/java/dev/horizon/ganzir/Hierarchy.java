package dev.horizon.ganzir;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import io.vertx.core.Future;

/**
 * A hierarchy of nodes, whose types and rules a {@link Model} declares.
 */
public interface Hierarchy {

  /**
   * Declares the model that every change from now on must follow. Existing nodes do not change.
   *
   * <p>
   * The future fails with {@code InvalidArgumentException} if the model is not valid, such as a rule that names a
   * type that the model does not declare, or a type that is not a root and has no parents.
   */
  Future<Void> declare(Model model);

  /**
   * Returns the model that was declared last. The future fails with {@code NotFoundException} if no model was declared.
   */
  Future<Model> model();

  /**
   * Creates a root node without metadata.
   *
   * @see #create(String, String, Metadata)
   */
  Future<Node> create(String type, String name);

  /**
   * Creates a root node.
   *
   * <p>
   * The future fails with {@code InvalidArgumentException} if the model does not declare the type, with
   * {@code FailedPreconditionException} and the reason {@link Reason#RULE} if the type cannot be a root, and with
   * {@code AlreadyExistsException} if a node of the type has the name.
   */
  Future<Node> create(String type, String name, Metadata metadata);

  /**
   * Returns the node with the id, in any state. The future fails with {@code NotFoundException} if no node has the id.
   */
  Future<Node> node(String id);

  /**
   * Returns the node of a {@link Scope#GLOBAL} type with the name. The future fails with {@code NotFoundException} if
   * no node of the type has the name, and with {@code InvalidArgumentException} if the type is not global.
   */
  Future<Node> node(String type, String name);

  /**
   * Returns a page of root nodes, ordered by name. The filter is in the syntax of AIP-160, over {@code type},
   * {@code state} and {@code labels}.
   */
  Future<Page<Node>> roots(PageRequest request);

  /**
   * Purges every deleted node whose retention has ended, with its holds and finalizers. A node is purged only after
   * its children are. Call it on a schedule, such as once an hour.
   *
   * @return the number of nodes purged
   */
  Future<Long> purge();
}
