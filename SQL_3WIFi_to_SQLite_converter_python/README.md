# 🔍 Converter for p3WIFI SQL Database Backups

Tool for converting SQL backups of the p3WIFI database (`nets` and `geo` tables) to SQLite format for portable use and application integration.


> [!WARNING]
> Converting large dumps can take more than 30 minutes and heavily loads the CPU while using a lot of RAM. It is recommended to use a powerful PC for the conversion. Even if the status seems to be stuck, the script continues to parse the dump. It just takes a long time because it needs to process each row and correctly extract the data from it.

### Requirements

- Python 3.7+
- Optional: `pip install tqdm`  (without it the script still works, but the progress bar is replaced by plain text output.)

### Usage

1. Place `3wifi_sql_to_sqlite.py` in the folder with your p3WIFI SQL backup named `dump-p3wifi-*.sql` (e.g. `dump-p3wifi-20260416.sql`).
2. Run the script:

   ```
   python 3wifi_sql_to_sqlite.py
   ```

   The script automatically finds the `dump-p3wifi-*.sql` file in the current directory. If there are several such files, it will ask you to specify one explicitly.

3. Result: a SQLite database file `p3wifi.sqlite` is created in the same folder.

> ⚠️ **IMPORTANT WARNING**: DO NOT MANUALLY MODIFY THE SQLITE DATABASE FILE! This will break the indexing in applications or corrupt the entire database.


### Optional arguments

You can also specify the files explicitly:

```
python 3wifi_sql_to_sqlite.py <dump.sql> [<output.sqlite>]
```

- `<dump.sql>` - the source SQL dump (by default auto-detected as `dump-p3wifi-*.sql`).
- `<output.sqlite>` - the name of the resulting database (default: `p3wifi.sqlite`).

If some rows or statements cannot be parsed, they are logged to `failed_rows.txt` in the same folder.

## ⚙️ Conversion Process

The conversion happens automatically. Stages:

1. **Extracting schema** - reads table definitions from the dump.
2. **Creating SQLite DB** - creates the database with mapped column types.
3. **Parsing and inserting** - parses `INSERT` statements (multiprocessing) and writes rows to the database.
4. **Cleaning duplicates** - removes duplicate rows from the `nets` table, keeping the most complete ones.
5. **Creating indexes** - always created for fast search (e.g. on `nets` and `geo`).
6. **Optimization** - `VACUUM` and `ANALYZE`, plus an integrity check.
