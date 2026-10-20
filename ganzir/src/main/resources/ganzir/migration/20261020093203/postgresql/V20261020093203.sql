CREATE TABLE "ganzir_model" ("version" bigint NOT NULL, "definition" jsonb, "create_time" timestamptz NOT NULL, CONSTRAINT "pk_ganzir_model" PRIMARY KEY ("version"));

COMMENT ON TABLE "ganzir_model" IS 'A declared model, by version. The last version applies to every change.';

COMMENT ON COLUMN "ganzir_model"."version" IS '1 for the first model, and one more for each after it.';

CREATE TABLE "ganzir_node" ("id" varchar(32) COLLATE "C" NOT NULL, "type" varchar(63) COLLATE "C" NOT NULL, "name" varchar(255) COLLATE "C" NOT NULL, "parent_id" varchar(32) COLLATE "C", "scope" varchar(32) COLLATE "C" NOT NULL, "path" varchar(850) COLLATE "C" NOT NULL, "depth" integer NOT NULL, "display_name" varchar(255) NOT NULL, "labels" jsonb, "label_index" text NOT NULL, "annotations" jsonb, "state" varchar(16) NOT NULL, "revision" bigint NOT NULL, "create_time" timestamptz NOT NULL, "update_time" timestamptz NOT NULL, "delete_time" timestamptz, "purge_time" timestamptz, CONSTRAINT "pk_ganzir_node" PRIMARY KEY ("id"));

CREATE UNIQUE INDEX "ux_ganzir_node_type_scope_name" ON "ganzir_node" ("type", "scope", "name");

CREATE INDEX "ix_ganzir_node_path" ON "ganzir_node" ("path");

CREATE INDEX "ix_ganzir_node_parent_id" ON "ganzir_node" ("parent_id");

COMMENT ON TABLE "ganzir_node" IS 'A node. Its name is unique among the nodes of its type with the same scope.';

COMMENT ON COLUMN "ganzir_node"."parent_id" IS 'None for a root.';

COMMENT ON COLUMN "ganzir_node"."scope" IS 'Empty for a type whose names are unique globally, and for a root; the id of the parent otherwise.';

COMMENT ON COLUMN "ganzir_node"."path" IS 'The ids from the root to this node, such as /a/b/c/. Its descendants are the nodes whose path starts with it.';

COMMENT ON COLUMN "ganzir_node"."depth" IS '0 for a root, and one more for each level below it.';

COMMENT ON COLUMN "ganzir_node"."label_index" IS 'Each label as ,hex(key)=hex(value), so that a filter on a label is a LIKE on this column.';

COMMENT ON COLUMN "ganzir_node"."state" IS 'ACTIVE, DELETING or DELETED.';

COMMENT ON COLUMN "ganzir_node"."purge_time" IS 'When a deleted node may be purged: its delete time and the retention of its type.';

CREATE TABLE "ganzir_hold" ("node_id" varchar(32) COLLATE "C" NOT NULL, "id" varchar(32) NOT NULL, "origin" varchar(255) NOT NULL, "reason" varchar(1024) NOT NULL, "create_time" timestamptz NOT NULL, CONSTRAINT "pk_ganzir_hold" PRIMARY KEY ("node_id", "id"));

COMMENT ON TABLE "ganzir_hold" IS 'A hold on a node, which stops it from being deleted.';

CREATE TABLE "ganzir_finalizer" ("node_id" varchar(32) COLLATE "C" NOT NULL, "name" varchar(255) COLLATE "C" NOT NULL, CONSTRAINT "pk_ganzir_finalizer" PRIMARY KEY ("node_id", "name"));

COMMENT ON TABLE "ganzir_finalizer" IS 'A finalizer of a node, which delays its purge after a delete.';

ALTER TABLE "ganzir_node" ADD CONSTRAINT "fk_ganzir_node_parent_id" FOREIGN KEY ("parent_id") REFERENCES "ganzir_node" ("id") ON DELETE RESTRICT;

ALTER TABLE "ganzir_hold" ADD CONSTRAINT "fk_ganzir_hold_node_id" FOREIGN KEY ("node_id") REFERENCES "ganzir_node" ("id") ON DELETE CASCADE;

ALTER TABLE "ganzir_finalizer" ADD CONSTRAINT "fk_ganzir_finalizer_node_id" FOREIGN KEY ("node_id") REFERENCES "ganzir_node" ("id") ON DELETE CASCADE;
