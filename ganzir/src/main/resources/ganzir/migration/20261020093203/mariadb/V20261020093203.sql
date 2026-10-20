CREATE TABLE `ganzir_model` (`version` BIGINT NOT NULL COMMENT '1 for the first model, and one more for each after it.', `definition` JSON NULL, `create_time` DATETIME(6) NOT NULL, CONSTRAINT `pk_ganzir_model` PRIMARY KEY (`version`));

ALTER TABLE `ganzir_model` COMMENT = 'A declared model, by version. The last version applies to every change.';

CREATE TABLE `ganzir_node` (`id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL, `type` VARCHAR(63) COLLATE utf8mb4_bin NOT NULL, `name` VARCHAR(255) COLLATE utf8mb4_bin NOT NULL, `parent_id` VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT 'None for a root.', `scope` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL COMMENT 'Empty for a type whose names are unique globally, and for a root; the id of the parent otherwise.', `path` VARCHAR(850) COLLATE utf8mb4_bin NOT NULL COMMENT 'The ids from the root to this node, such as /a/b/c/. Its descendants are the nodes whose path starts with it.', `depth` INT NOT NULL COMMENT '0 for a root, and one more for each level below it.', `display_name` VARCHAR(255) NOT NULL, `labels` JSON NULL, `label_index` LONGTEXT NOT NULL COMMENT 'Each label as ,hex(key)=hex(value), so that a filter on a label is a LIKE on this column.', `annotations` JSON NULL, `state` VARCHAR(16) NOT NULL COMMENT 'ACTIVE, DELETING or DELETED.', `revision` BIGINT NOT NULL, `create_time` DATETIME(6) NOT NULL, `update_time` DATETIME(6) NOT NULL, `delete_time` DATETIME(6) NULL, `purge_time` DATETIME(6) NULL COMMENT 'When a deleted node may be purged: its delete time and the retention of its type.', CONSTRAINT `pk_ganzir_node` PRIMARY KEY (`id`));

CREATE UNIQUE INDEX `ux_ganzir_node_type_scope_name` ON `ganzir_node` (`type`, `scope`, `name`);

CREATE INDEX `ix_ganzir_node_path` ON `ganzir_node` (`path`);

CREATE INDEX `ix_ganzir_node_parent_id` ON `ganzir_node` (`parent_id`);

ALTER TABLE `ganzir_node` COMMENT = 'A node. Its name is unique among the nodes of its type with the same scope.';

CREATE TABLE `ganzir_hold` (`node_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL, `id` VARCHAR(32) NOT NULL, `origin` VARCHAR(255) NOT NULL, `reason` VARCHAR(1024) NOT NULL, `create_time` DATETIME(6) NOT NULL, CONSTRAINT `pk_ganzir_hold` PRIMARY KEY (`node_id`, `id`));

ALTER TABLE `ganzir_hold` COMMENT = 'A hold on a node, which stops it from being deleted.';

CREATE TABLE `ganzir_finalizer` (`node_id` VARCHAR(32) COLLATE utf8mb4_bin NOT NULL, `name` VARCHAR(255) COLLATE utf8mb4_bin NOT NULL, CONSTRAINT `pk_ganzir_finalizer` PRIMARY KEY (`node_id`, `name`));

ALTER TABLE `ganzir_finalizer` COMMENT = 'A finalizer of a node, which delays its purge after a delete.';

ALTER TABLE `ganzir_node` ADD CONSTRAINT `fk_ganzir_node_parent_id` FOREIGN KEY (`parent_id`) REFERENCES `ganzir_node` (`id`) ON DELETE RESTRICT;

ALTER TABLE `ganzir_hold` ADD CONSTRAINT `fk_ganzir_hold_node_id` FOREIGN KEY (`node_id`) REFERENCES `ganzir_node` (`id`) ON DELETE CASCADE;

ALTER TABLE `ganzir_finalizer` ADD CONSTRAINT `fk_ganzir_finalizer_node_id` FOREIGN KEY (`node_id`) REFERENCES `ganzir_node` (`id`) ON DELETE CASCADE;
