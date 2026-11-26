pub fn use_external_content_fts(connection: &rusqlite::Connection) -> rusqlite::Result<()> {
    connection.execute_batch(
        "DROP TRIGGER IF EXISTS memo_search_changelog_insert;
         DROP TRIGGER IF EXISTS memo_search_changelog_delete;
         DROP TRIGGER IF EXISTS memo_search_changelog_update;
         DROP TRIGGER IF EXISTS memo_tag_search_changelog_insert;
         DROP TRIGGER IF EXISTS memo_tag_search_changelog_delete;
         DROP TABLE IF EXISTS search_changelog;
         DROP TRIGGER IF EXISTS memo_fts_delete;
         DROP TABLE IF EXISTS memo_fts;
         ALTER TABLE memo ADD COLUMN search_content TEXT NOT NULL DEFAULT '';
         CREATE VIRTUAL TABLE memo_fts USING fts5(
            search_content, content='memo', content_rowid='rowid', tokenize='unicode61'
         );",
    )
}
