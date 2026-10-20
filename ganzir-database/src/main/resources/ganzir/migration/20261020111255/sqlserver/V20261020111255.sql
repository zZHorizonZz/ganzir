CREATE TABLE [ganzir_model] ([version] BIGINT NOT NULL, [definition] NVARCHAR(MAX) NULL, [create_time] DATETIME2(6) NOT NULL, CONSTRAINT [pk_ganzir_model] PRIMARY KEY ([version]));

DECLARE @ganzir_schema_1 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'A declared model, by version. The last version applies to every change.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_1, @level1type = N'TABLE', @level1name = N'ganzir_model';

DECLARE @ganzir_schema_2 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'1 for the first model, and one more for each after it.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_2, @level1type = N'TABLE', @level1name = N'ganzir_model', @level2type = N'COLUMN', @level2name = N'version';

CREATE TABLE [ganzir_node] ([id] NVARCHAR(32) COLLATE Latin1_General_100_BIN2 NOT NULL, [type] NVARCHAR(63) COLLATE Latin1_General_100_BIN2 NOT NULL, [name] NVARCHAR(255) COLLATE Latin1_General_100_BIN2 NOT NULL, [parent_id] NVARCHAR(32) COLLATE Latin1_General_100_BIN2 NULL, [scope] NVARCHAR(32) COLLATE Latin1_General_100_BIN2 NOT NULL, [path] NVARCHAR(850) COLLATE Latin1_General_100_BIN2 NOT NULL, [depth] INT NOT NULL, [display_name] NVARCHAR(255) NOT NULL, [labels] NVARCHAR(MAX) NULL, [label_index] NVARCHAR(MAX) NOT NULL, [annotations] NVARCHAR(MAX) NULL, [state] NVARCHAR(16) NOT NULL, [revision] BIGINT NOT NULL, [create_time] DATETIME2(6) NOT NULL, [update_time] DATETIME2(6) NOT NULL, [delete_time] DATETIME2(6) NULL, [purge_time] DATETIME2(6) NULL, CONSTRAINT [pk_ganzir_node] PRIMARY KEY ([id]));

CREATE UNIQUE INDEX [ux_ganzir_node_type_scope_name] ON [ganzir_node] ([type], [scope], [name]);

CREATE INDEX [ix_ganzir_node_path] ON [ganzir_node] ([path]);

CREATE INDEX [ix_ganzir_node_parent_id] ON [ganzir_node] ([parent_id]);

DECLARE @ganzir_schema_7 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'A node. Its name is unique among the nodes of its type with the same scope.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_7, @level1type = N'TABLE', @level1name = N'ganzir_node';

DECLARE @ganzir_schema_8 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'None for a root.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_8, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'parent_id';

DECLARE @ganzir_schema_9 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'Empty for a type whose names are unique globally, and for a root; the id of the parent otherwise.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_9, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'scope';

DECLARE @ganzir_schema_10 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'The ids from the root to this node, such as /a/b/c/. Its descendants are the nodes whose path starts with it.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_10, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'path';

DECLARE @ganzir_schema_11 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'0 for a root, and one more for each level below it.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_11, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'depth';

DECLARE @ganzir_schema_12 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'Each label as ,hex(key)=hex(value), so that a filter on a label is a LIKE on this column.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_12, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'label_index';

DECLARE @ganzir_schema_13 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'ACTIVE, DELETING or DELETED.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_13, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'state';

DECLARE @ganzir_schema_14 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'When a deleted node may be purged: its delete time and the retention of its type.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_14, @level1type = N'TABLE', @level1name = N'ganzir_node', @level2type = N'COLUMN', @level2name = N'purge_time';

CREATE TABLE [ganzir_lien] ([node_id] NVARCHAR(32) COLLATE Latin1_General_100_BIN2 NOT NULL, [id] NVARCHAR(32) NOT NULL, [origin] NVARCHAR(255) NOT NULL, [reason] NVARCHAR(1024) NOT NULL, [create_time] DATETIME2(6) NOT NULL, CONSTRAINT [pk_ganzir_lien] PRIMARY KEY ([node_id], [id]));

DECLARE @ganzir_schema_16 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'A lien on a node, which stops it from being deleted.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_16, @level1type = N'TABLE', @level1name = N'ganzir_lien';

CREATE TABLE [ganzir_finalizer] ([node_id] NVARCHAR(32) COLLATE Latin1_General_100_BIN2 NOT NULL, [name] NVARCHAR(255) COLLATE Latin1_General_100_BIN2 NOT NULL, CONSTRAINT [pk_ganzir_finalizer] PRIMARY KEY ([node_id], [name]));

DECLARE @ganzir_schema_18 sysname = SCHEMA_NAME() EXEC sp_addextendedproperty @name = N'MS_Description', @value = N'A finalizer of a node, which keeps it deleting after a delete until it is removed.', @level0type = N'SCHEMA', @level0name = @ganzir_schema_18, @level1type = N'TABLE', @level1name = N'ganzir_finalizer';

ALTER TABLE [ganzir_node] ADD CONSTRAINT [fk_ganzir_node_parent_id] FOREIGN KEY ([parent_id]) REFERENCES [ganzir_node] ([id]);

ALTER TABLE [ganzir_lien] ADD CONSTRAINT [fk_ganzir_lien_node_id] FOREIGN KEY ([node_id]) REFERENCES [ganzir_node] ([id]) ON DELETE CASCADE;

ALTER TABLE [ganzir_finalizer] ADD CONSTRAINT [fk_ganzir_finalizer_node_id] FOREIGN KEY ([node_id]) REFERENCES [ganzir_node] ([id]) ON DELETE CASCADE;
