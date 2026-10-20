CREATE TABLE "ganzir_model" ("version" INTEGER NOT NULL, "definition" TEXT, "create_time" TEXT NOT NULL, CONSTRAINT "pk_ganzir_model" PRIMARY KEY ("version"));

CREATE TABLE "ganzir_node" ("id" TEXT NOT NULL, "type" TEXT NOT NULL, "name" TEXT NOT NULL, "parent_id" TEXT, "scope" TEXT NOT NULL, "path" TEXT NOT NULL, "depth" INTEGER NOT NULL, "display_name" TEXT NOT NULL, "labels" TEXT, "label_index" TEXT NOT NULL, "annotations" TEXT, "state" TEXT NOT NULL, "revision" INTEGER NOT NULL, "create_time" TEXT NOT NULL, "update_time" TEXT NOT NULL, "delete_time" TEXT, "purge_time" TEXT, CONSTRAINT "pk_ganzir_node" PRIMARY KEY ("id"), CONSTRAINT "fk_ganzir_node_parent_id" FOREIGN KEY ("parent_id") REFERENCES "ganzir_node" ("id") ON DELETE RESTRICT);

CREATE UNIQUE INDEX "ux_ganzir_node_type_scope_name" ON "ganzir_node" ("type", "scope", "name");

CREATE INDEX "ix_ganzir_node_path" ON "ganzir_node" ("path");

CREATE INDEX "ix_ganzir_node_parent_id" ON "ganzir_node" ("parent_id");

CREATE TABLE "ganzir_lien" ("node_id" TEXT NOT NULL, "id" TEXT NOT NULL, "origin" TEXT NOT NULL, "reason" TEXT NOT NULL, "create_time" TEXT NOT NULL, CONSTRAINT "pk_ganzir_lien" PRIMARY KEY ("node_id", "id"), CONSTRAINT "fk_ganzir_lien_node_id" FOREIGN KEY ("node_id") REFERENCES "ganzir_node" ("id") ON DELETE CASCADE);

CREATE TABLE "ganzir_finalizer" ("node_id" TEXT NOT NULL, "name" TEXT NOT NULL, CONSTRAINT "pk_ganzir_finalizer" PRIMARY KEY ("node_id", "name"), CONSTRAINT "fk_ganzir_finalizer_node_id" FOREIGN KEY ("node_id") REFERENCES "ganzir_node" ("id") ON DELETE CASCADE);
