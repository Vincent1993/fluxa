#!/usr/bin/env python3
"""Exercise the actual SQL in DatabaseMigrations.kt with Python SQLite.
This verifies row preservation, not Room schema validation or Android runtime.
"""
import pathlib
import re
import sqlite3

source = (pathlib.Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/fluxa/app/data/local/DatabaseMigrations.kt").read_text()
statements = re.findall(r'db\.execSQL\("""(.*?)"""\)|db\.execSQL\("([^"\n]*)"\)', source, re.S)

for variant in ("v1", "v2-sync", "v2-pending", "v2-filter", "v2-combined"):
    db = sqlite3.connect(":memory:")
    extended = variant in ("v2-filter", "v2-combined")
    db.execute("""CREATE TABLE articles (id TEXT NOT NULL PRIMARY KEY,
        title TEXT NOT NULL, feedName TEXT NOT NULL, publishedAtEpochSeconds INTEGER NOT NULL,
        isRead INTEGER NOT NULL, isStarred INTEGER NOT NULL, contentHtml TEXT NOT NULL""" +
        (", source TEXT NOT NULL, tags TEXT NOT NULL" if extended else "") + ")")
    values = ["one", "Saved title", "Feed", 1234, 1, 1, "<p>Saved offline</p>"]
    if extended:
        values += ["feed/example", "original-tag"]
    db.execute("INSERT INTO articles VALUES (" + ",".join("?" for _ in values) + ")", values)
    if variant in ("v2-sync", "v2-combined"):
        db.execute("""CREATE TABLE sync_state (source TEXT NOT NULL PRIMARY KEY,
            cursor TEXT, lastSyncAtEpochMillis INTEGER NOT NULL, syncToken TEXT)""")
        db.execute("INSERT INTO sync_state VALUES ('feed', 'cursor', 10, 'token')")
    if variant in ("v2-pending", "v2-combined"):
        db.execute("""CREATE TABLE pending_actions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            articleId TEXT NOT NULL, actionType TEXT NOT NULL, payload TEXT NOT NULL,
            createdAtEpochMillis INTEGER NOT NULL)""")
        db.execute("INSERT INTO pending_actions VALUES (1, 'one', 'AddNote', 'Saved note', 10)")
    for triple, single in statements:
        sql = triple or single
        sql = sql.replace("$source", "source" if extended else "''")
        sql = sql.replace("$tags", "tags" if extended else "''")
        db.execute(sql)
    result = db.execute("SELECT * FROM articles").fetchone()
    assert list(result[:7]) == values[:7], (variant, result)
    assert result[7:] == (("feed/example", "original-tag") if extended else ("", ""))
    columns = db.execute("PRAGMA table_info(articles)").fetchall()
    assert columns[-1][4] == "''" and columns[-2][4] == "''"
    if variant in ("v2-sync", "v2-combined"):
        assert db.execute("SELECT cursor FROM sync_state").fetchone()[0] == "cursor"
    if variant in ("v2-pending", "v2-combined"):
        assert db.execute("SELECT payload FROM pending_actions").fetchone()[0] == "Saved note"
    db.close()
    print(f"PASS {variant}: article, flags, content and experimental state preserved")
