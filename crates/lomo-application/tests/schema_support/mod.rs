pub fn use_external_content_fts(connection: &rusqlite::Connection) -> rusqlite::Result<()> {
    connection.execute_batch(
        "DROP TRIGGER memo_fts_delete;
         DROP TABLE memo_fts;
         ALTER TABLE memo ADD COLUMN search_content TEXT NOT NULL DEFAULT '';
         CREATE VIRTUAL TABLE memo_fts USING fts5(
            search_content, content='memo', content_rowid='rowid', tokenize='unicode61'
         );",
    )
}
