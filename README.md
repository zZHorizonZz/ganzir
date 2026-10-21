# Ganzir

Ganzir stores a hierarchy of containers for a Vert.x application, such as organizations, folders and projects, or
tenants, workspaces and environments. You declare the types of containers and the rules between them, and Ganzir
keeps the tree in your database. It answers what a container is inside in one query, so an authorization check can
ask it on every call.

```java
Hierarchy hierarchy = new DatabaseHierarchy(database);

hierarchy.declare(new Model(List.of(
    new NodeType("organization", Scope.GLOBAL, Duration.ofDays(30), List.of(new Root())),
    new NodeType("folder", Scope.PARENT, Duration.ofDays(30),
        List.of(new Parents("organization", "folder"), new Depth(10))),
    new NodeType("project", Scope.GLOBAL, Duration.ofDays(30), List.of(new Parents("organization", "folder"))))))
    .await(10, TimeUnit.SECONDS);

Node example = hierarchy.create("organization", "example.com").await(10, TimeUnit.SECONDS);
Node finance = example.create("folder", "finance").await(10, TimeUnit.SECONDS);
Node ledger = finance.create("project", "ledger-prod").await(10, TimeUnit.SECONDS);

List<Node> ancestors = ledger.ancestors().await(10, TimeUnit.SECONDS);
```

`ancestors` contains `finance` and `example.com`, nearest first.

## Use cases

Use Ganzir for the tenants of a SaaS application. A tenant contains workspaces, and a workspace contains
environments. A permission granted on a tenant applies to everything inside it, because your authorization check
reads the ancestors of each resource.

Use Ganzir to emulate the hierarchy of a cloud. Google's organizations, folders and projects, AWS's organizational
units and accounts, and Azure's management groups, subscriptions and resource groups are each one model.

Use Ganzir for an internal platform, where teams own services, and services own environments.

## The model

A model declares the types of nodes. A type has a name, a scope, a retention, and rules:

| Part      | Description                                                                                                                          |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| scope     | where the names of nodes of this type are unique: `GLOBAL` among all nodes of the type, `PARENT` among the siblings of the same type |
| retention | how long a deleted node can be restored before it is purged                                                                          |
| rules     | the checks that every create, move and rename of a node of this type must pass                                                       |

| Rule                 | Description                                                   |
| -------------------- | ------------------------------------------------------------- |
| `Root()`             | a node of this type can have no parent                        |
| `Parents(types...)`  | a node of this type can be placed under a node of these types |
| `Children(types...)` | a node of this type can contain only nodes of these types     |
| `Depth(n)`           | a node of this type can be at most `n` levels below its root  |

The database enforces the scope of a type with a unique index, so two nodes with the same name cannot be created at
the same time. The rules are checks that Ganzir runs before it writes.

Each model that you declare is stored as a new version. Existing nodes do not change when you declare a new model.

## Nodes

Every node has these fields:

| Field                                    | Description                                                             |
| ---------------------------------------- | ----------------------------------------------------------------------- |
| `id`                                     | an id that Ganzir generates, and that never changes                     |
| `type`, `name`                           | the type of the node, and its name, unique within the scope of its type |
| `parent`                                 | the id of the parent node, or nothing for a root                        |
| `metadata`                               | the display name, the labels and the annotations                        |
| `state`                                  | `ACTIVE`, `DELETING` or `DELETED`                                       |
| `revision`                               | the revision of the node, which every change increments                 |
| `createTime`, `updateTime`, `deleteTime` | when the node was created, last changed and deleted                     |
| `liens`, `finalizers`                    | the liens and the finalizers of the node                                |

The metadata of a node has a display name for people, labels that filters can select by, and annotations that they
cannot, for tools and notes.

`children`, `descendants` and `roots` return pages, ordered by name. They take a filter in the syntax of
[AIP-160](https://google.aip.dev/160), over `type`, `state`, `name`, `display_name` and `labels`, such as
`labels.team = "billing" AND type = project`, or `labels.env:*` for the nodes that have the label at all.

Names, metadata and the hierarchy have these limits:

- A name has 1 to 255 characters.
- A display name has at most 255 characters.
- A label key has 1 to 63 lowercase letters, digits, `_` and `-`, and starts with a letter.
- A label value has 0 to 63 lowercase letters, digits, `_` and `-`.
- A node has at most 64 labels.
- The keys and values of the annotations of a node have at most 256 KiB together.
- A hierarchy is at most 25 levels deep.

The limits of labels are the
[requirements for labels of Google Cloud](https://docs.cloud.google.com/resource-manager/docs/labels-overview#requirements).
`Metadata` checks its own limits when you create it, and fails with `InvalidArgumentException`.

## Moves

`move(parent, expected)` places a node and everything inside it under another parent. Ganzir checks the rules of
the node's type and of the new parent's type, and refuses a move that would place a node inside itself. It updates
the ancestors of the whole subtree in one transaction.

## Liens and finalizers

A lien stops a delete. While a node has a lien, `delete` fails. For example, a billing service places a lien on a
project while it is billed, and removes the lien when the billing ends
([Cloud Resource Manager, project liens](https://docs.cloud.google.com/resource-manager/docs/project-liens)):

```java
Lien lien = ledger.lien("billing", "An active subscription is billed to this project.").await(10, TimeUnit.SECONDS);
lien.remove().await(10, TimeUnit.SECONDS);
```

A finalizer keeps a deleted node `DELETING` until the finalizer is removed. For example, a service that stores data
inside a project adds a finalizer, deletes its data when the project is deleted, and then removes its finalizer
([Kubernetes, finalizers](https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/)):

```java
Finalizer finalizer = ledger.finalizer("storage.example.com/buckets").await(10, TimeUnit.SECONDS);
finalizer.remove().await(10, TimeUnit.SECONDS);
```

A finalizer is identified by its name, so adding a finalizer that the node has returns that finalizer. Each lien is
new, with an id of its own.

## Deletion

`delete(expected)` fails while the node has children that are not deleted, or liens. Otherwise the node becomes
`DELETED`, or `DELETING` while it has finalizers. A deleted node keeps its name, and `restore(expected)` makes it `ACTIVE` again.

`purge()` removes every deleted node whose retention has ended, after its children. Call it on a schedule:

```java
vertx.setPeriodic(Duration.ofHours(1).toMillis(), timer -> hierarchy.purge());
```

## Install

Ganzir requires Java 25 and Vert.x 5. It stores its tables through [Cinder](https://github.com/zZHorizonZz/cinder) in
PostgreSQL, MariaDB, SQL Server or SQLite. `DatabaseHierarchy` works on a Cinder `Database` that you build, with the
protobuf extension:

```java
Database database = Database.builder(pool, new PostgresDriver()).extension(new ProtobufExtension()).build();
Hierarchy hierarchy = new DatabaseHierarchy(database);
```

Create the tables with the migrations under `ganzir/migration` in the JAR, with Flyway.

<!-- x-release-please-start-version -->
```xml
<dependency>
  <groupId>dev.horizon</groupId>
  <artifactId>ganzir-database</artifactId>
  <version>0.2.1-SNAPSHOT</version>
</dependency>
```
<!-- x-release-please-end -->

| Artifact          | Java module                   | Contents                                              |
| ----------------- | ----------------------------- | ----------------------------------------------------- |
| `ganzir-api`      | `dev.horizon.ganzir.api`      | the interfaces of the hierarchy, the model and nodes  |
| `ganzir-database` | `dev.horizon.ganzir.database` | `DatabaseHierarchy`, and the migrations of its tables |

`Metadata` and the failures, which are problem exceptions, are [Pyrite](https://github.com/zZHorizonZz/pyrite)'s
`pyrite-common`, and pages are [aquery](https://github.com/zZHorizonZz/aquery)'s.

## Prior art

- [kcp, workspace types](https://docs.kcp.io/kcp/main/concepts/workspaces/workspace-types/) declare which types of
  workspaces can be parents and children of each other.
- [Kubernetes, object metadata](https://kubernetes.io/docs/reference/kubernetes-api/common-definitions/object-meta/)
  is the metadata that every node has.
- [Google Cloud's resource hierarchy](https://docs.cloud.google.com/resource-manager/docs/cloud-platform-resource-hierarchy),
  [AWS Organizations](https://docs.aws.amazon.com/organizations/latest/userguide/orgs_getting-started_concepts.html)
  and [Azure management groups](https://learn.microsoft.com/en-us/azure/governance/management-groups/overview) are
  hierarchies that a model can express.

## License

Apache License 2.0. For more information, refer to [LICENSE](LICENSE).
