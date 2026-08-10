import os
import re
import sqlite3
import multiprocessing
from queue import Empty
from typing import List, Any, Tuple, Dict, Iterator
import sys
from datetime import datetime

try:
    from tqdm import tqdm
except ImportError:
    class tqdm:
        def __init__(self, total=None, desc=None):
            self.total = total
            self.desc = desc
            self.n = 0
        def update(self, n=1):
            self.n += n
            if self.n % 1000 == 0:
                print(f"{self.desc}: {self.n}")
        def close(self): pass
        def __enter__(self): return self
        def __exit__(self, *args): pass

MAX_QUEUE_SIZE = 20

class SQLParser:
    @staticmethod
    def parse_statement(statement: str) -> Iterator[List[Any]]:
        SQL_ESCAPES = {"'", '\\', '0', 'n', 'r', 't', 'b', '/', '%', 'z', chr(26)}
        values_match = re.search(r"VALUES\s*(.*)", statement, re.IGNORECASE | re.DOTALL)
        if not values_match:
            return

        values_part = values_match.group(1)

        i = 0
        n = len(values_part)

        while i < n:
            if values_part[i] != '(':
                i += 1
                continue

            start_idx = i
            paren_level = 1
            in_string = False

            i += 1
            while i < n and paren_level > 0:
                char = values_part[i]

                if in_string and char == "'":
                    if i + 1 < n and values_part[i + 1] == "'":
                        current_pos = i + 2
                        while current_pos < n and values_part[current_pos] not in (',', ')'):
                            if values_part[current_pos] == "'" and current_pos + 1 < n and values_part[current_pos + 1] == "'":
                                current_pos += 2
                            else:
                                break
                        if current_pos < n and values_part[current_pos] == ',':
                            i = current_pos + 1
                            continue
                    look_ahead = ""
                    for j in range(i + 1, min(i + 5, n)):
                        c = values_part[j]
                        if c not in (' ', '\t', '\n', '\r'):
                            look_ahead = c
                            break
                    if look_ahead and look_ahead not in (',', ')'):
                        i += 1
                        continue
                    else:
                        in_string = False
                        i += 1
                        continue
                elif char == '\\' :
                    next_char = values_part[i + 1] if i + 1 < n else ""
                    if next_char in SQL_ESCAPES:
                        i += 2
                        continue
                    else:
                        i += 1
                        continue
                elif char == "'":
                    in_string = not in_string
                    i += 1
                elif not in_string:
                    if char == '(':
                        paren_level += 1
                        i += 1
                    elif char == ')':
                        paren_level -= 1
                        if paren_level == 0:
                            tuple_content = values_part[start_idx + 1 : i]
                            yield SQLParser._parse_tuple_content(tuple_content)
                            i += 1
                            break
                        i += 1
                    else:
                        i += 1
                else:
                    i += 1

            if paren_level == 0:
                pass

    @staticmethod
    def _parse_tuple_content(content: str) -> List[Any]:
        SQL_ESCAPES = {"'", '\\', '0', 'n', 'r', 't', 'b', '/', '%', 'z', chr(26)}
        values = []
        current = []
        in_string = False
        i = 0
        n = len(content)

        while i < n:
            char = content[i]

            if in_string and char == "'" :
                if i + 1 < n and content[i + 1] == "'":
                    current.append("'")
                    i += 2
                    continue
                else:
                    look_ahead = ""
                    for j in range(i + 1, min(i + 5, n)):
                        c = content[j]
                        if c not in (' ', '\t', '\n', '\r'):
                            look_ahead = c
                            break
                    if look_ahead and look_ahead not in (',', ')'):
                        current.append(char)
                        i += 1
                        continue
                    else:
                        in_string = False
                        i += 1
                        continue
            elif char == '\\' :
                next_char = content[i + 1] if i + 1 < n else ""
                if next_char in SQL_ESCAPES:
                    current.append(next_char)
                    i += 2
                    continue
                else:
                    current.append(char)
                    i += 1
                    continue
            elif char == "'":
                in_string = not in_string
                i += 1
            elif in_string:
                current.append(char)
                i += 1
            elif char == ',':
                values.append(SQLParser._convert_value("".join(current).strip()))
                current = []
                i += 1
            else:
                current.append(char)
                i += 1

        val = "".join(current).strip()
        if len(values) > 0 or (len(content) > 0 and content[-1] != ','):
            values.append(SQLParser._convert_value(val))
        return values

    @staticmethod
    def _convert_value(val: str) -> Any:
        if not val: return None
        v_upper = val.upper()
        if v_upper == 'NULL': return None

        ts_match = re.match(r"'(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})'", val)
        if ts_match:
            try:
                dt_str = ts_match.group(1).replace(" ", "T")
                dt = datetime.fromisoformat(dt_str)
                epoch = int((dt - datetime(1970, 1, 1)).total_seconds())
                return epoch
            except Exception:
                pass

        try:
            if v_upper.startswith('0X'):
                num = int(val, 16)
            elif '.' in val:
                return float(val)
            else:
                num = int(val)

            if num > 9223372036854775807 or num < -9223372036854775808:
                return val
            return num
        except ValueError:
            return val

def get_table_schema(file_path: str) -> Dict[str, Dict[str, Any]]:
    schema = {}
    create_table_pattern = re.compile(r"CREATE TABLE `(\w+)` \((.*?)\) ENGINE=.*?;", re.DOTALL | re.IGNORECASE)

    print("Reading file for schema (this may take a moment)...")
    with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    for match in create_table_pattern.finditer(content):
        table_name = match.group(1)
        columns_block = match.group(2)
        columns = []
        constraints = []
        current_item = []
        paren_level = 0
        for char in columns_block:
            if char == '(': paren_level += 1
            elif char == ')': paren_level -= 1
            if char == ',' and paren_level == 0:
                item = "".join(current_item).strip()
                if item:
                    if item.upper().startswith(('PRIMARY KEY', 'UNIQUE', 'CONSTRAINT', 'KEY', 'FOREIGN KEY')):
                        constraints.append(item)
                    else:
                        columns.append(item)
                current_item = []
            else: current_item.append(char)
        if current_item:
            item = "".join(current_item).strip()
            if item:
                if item.upper().startswith(('PRIMARY KEY', 'UNIQUE', 'CONSTRAINT', 'KEY', 'FOREIGN KEY')):
                    constraints.append(item)
                else:
                    columns.append(item)
        schema[table_name] = {'columns': columns, 'constraints': constraints}
    return schema

def map_type_to_sqlite(col_name: str, col_def: str) -> str:
    """Map MySQL column definition to optimized SQLite type."""
    col_upper = col_def.upper()

    if 'TIMESTAMP' in col_upper or 'DATETIME' in col_upper:
        return 'INTEGER'

    if 'BIGINT' in col_upper:
        return 'INTEGER'

    if 'UNSIGNED' in col_upper:
        return 'INTEGER'

    if 'INT' in col_upper:
        return 'INTEGER'

    if 'FLOAT' in col_upper or 'DOUBLE' in col_upper or 'REAL' in col_upper:
        return 'REAL'

    if 'CHARACTER' in col_upper or col_upper.endswith('TEXT'):
        return 'TEXT'

    if 'BIT' in col_upper:
        return 'INTEGER'

    if 'TINYINT' in col_upper or 'SMALLINT' in col_upper or 'MEDIUMINT' in col_upper:
        return 'INTEGER'

    return 'TEXT'

def create_sqlite_db(db_path: str, schema: Dict[str, Dict[str, Any]]):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    for table_name, info in schema.items():
        columns = info['columns']
        constraints = info['constraints']
        col_defs = []
        for col in columns:
            parts = col.split()
            if not parts: continue
            name = parts[0].replace('`', '')
            type_idx = 1
            while type_idx < len(parts) and not parts[type_idx].upper().startswith(('NOT', 'DEFAULT', 'PRIMARY', 'UNIQUE', 'CHARACTER', 'COLLATE')):
                type_idx += 1
            type_part = " ".join(parts[1:type_idx]) if type_idx > 1 else 'TEXT'
            sqlite_type = map_type_to_sqlite(name, type_part)
            pk = "PRIMARY KEY" if "PRIMARY KEY" in col.upper() else ""
            col_defs.append(f"{name} {sqlite_type} {pk}")
        for constraint in constraints:
            if constraint.upper().startswith('PRIMARY KEY'):
                match = re.search(r"PRIMARY KEY\s*\(`?(\w+)`?\)", constraint, re.IGNORECASE)
                if match: col_defs.append(f"PRIMARY KEY ({match.group(1)})")
            elif constraint.upper().startswith('UNIQUE'):
                match = re.search(r"UNIQUE\s*\(`?(\w+)`?\)", constraint, re.IGNORECASE)
                if match: col_defs.append(f"UNIQUE ({match.group(1)})")
        cursor.execute(f"DROP TABLE IF EXISTS `{table_name}`")
        cursor.execute(f"CREATE TABLE `{table_name}` ({', '.join(col_defs)})")
    conn.commit()
    conn.close()

def worker(input_queue: multiprocessing.JoinableQueue, output_queue: multiprocessing.Queue, error_log_path: str):
    while True:
        task = input_queue.get()
        if task is None:
            input_queue.task_done()
            break
        table_name, statement, expected_cols = task
        batch = []
        errors = []
        try:
            for tuple_vals in SQLParser.parse_statement(statement):
                if len(tuple_vals) == expected_cols:
                    batch.append(tuple_vals)
                else:
                    errors.append(f"Column count mismatch in {table_name}: expected {expected_cols}, got {len(tuple_vals)}")
        except Exception as e:
            errors.append(f"Error parsing statement for {table_name}: {e}")

        if batch:
            output_queue.put((table_name, batch))
        if errors:
            with open(error_log_path, 'a', encoding='utf-8') as f:
                for err in errors: f.write(err + '\n')
        input_queue.task_done()

def writer(db_path: str, output_queue: multiprocessing.Queue):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    rows_inserted = 0
    rows_skipped = 0
    pbar = tqdm(desc="Inserting rows")

    tables_with_unique = {'nets'}

    while True:
        try:
            msg = output_queue.get(timeout=5)
            if msg == "DONE": break
            table_name, batch = msg
            if not batch: continue

            placeholders = ",".join(["?"] * len(batch[0]))

            insert_type = "INSERT OR IGNORE" if table_name in tables_with_unique else "INSERT"
            sql = f"{insert_type} INTO `{table_name}` VALUES ({placeholders})"

            cursor.executemany(sql, batch)
            conn.commit()

            if table_name in tables_with_unique:
                total_expected = len(batch)
                inserted = cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`").fetchone()[0]

                pass

            rows_inserted += len(batch)
            pbar.update(len(batch))
        except Empty: continue
        except Exception as e:
            print(f"Writer error: {e}")
            continue
    pbar.close()

    print(f"\nInsertion summary:")
    print(f"  Total rows processed: {rows_inserted:,}")
    conn.close()

def main(dump_file: str, db_file: str):
    error_log = "failed_rows.txt"
    if os.path.exists(error_log): os.remove(error_log)
    print(f"Step 1: Extracting schema...")
    schema = get_table_schema(dump_file)
    if not schema:
        print("Error: No schema found!")
        return
    print(f"Found tables: {list(schema.keys())}")
    for table_name in schema:
        print(f"  - {table_name}: {len(schema[table_name]['columns'])} columns")
    print(f"Step 2: Creating SQLite DB...")
    create_sqlite_db(db_file, schema)
    print(f"Step 3: Parsing and inserting...")
    input_queue = multiprocessing.JoinableQueue(maxsize=MAX_QUEUE_SIZE)
    output_queue = multiprocessing.Queue(maxsize=MAX_QUEUE_SIZE)
    num_workers = multiprocessing.cpu_count()
    workers = []
    for _ in range(num_workers):
        p = multiprocessing.Process(target=worker, args=(input_queue, output_queue, error_log))
        p.start()
        workers.append(p)
    writer_process = multiprocessing.Process(target=writer, args=(db_file, output_queue))
    writer_process.start()

    current_table = None
    statement_buffer = []
    insert_re = re.compile(r"INSERT INTO `(\w+)` VALUES", re.IGNORECASE)

    with open(dump_file, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if not line: continue

            match = insert_re.search(line)
            if match:
                if current_table and statement_buffer:
                    expected_cols = len(schema.get(current_table, {}).get('columns', [0]))
                    input_queue.put((current_table, "".join(statement_buffer), expected_cols))
                    statement_buffer = []
                current_table = match.group(1)
                statement_buffer.append(line + "\n")
            elif current_table:
                statement_buffer.append(line + "\n")
                if line.rstrip().endswith(';'):
                    expected_cols = len(schema.get(current_table, {}).get('columns', [0]))
                    input_queue.put((current_table, "".join(statement_buffer), expected_cols))
                    statement_buffer = []
                    current_table = None
            else:
                pass

    if current_table and statement_buffer:
        expected_cols = len(schema.get(current_table, {}).get('columns', [0]))
        input_queue.put((current_table, "".join(statement_buffer), expected_cols))

    input_queue.join()
    for _ in range(num_workers): input_queue.put(None)
    for p in workers: p.join()
    output_queue.put("DONE")
    writer_process.join()

    print(f"\nStep 3.5: Cleaning duplicate rows from nets table...")
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()

    conn.execute("PRAGMA cache_size=-2000000")
    conn.execute("PRAGMA temp_store=MEMORY")
    conn.execute("PRAGMA synchronous=NORMAL")

    count_before = cursor.execute("SELECT COUNT(*) FROM nets").fetchone()[0]
    print(f"\n  Rows before cleanup: {count_before:,}")

    print(f"\n  Deleting duplicates (keeping newest with most data)...")

    cursor.execute("ALTER TABLE nets ADD COLUMN temp_score INTEGER")

    cursor.execute("""
        UPDATE nets SET temp_score =
            (CASE WHEN time IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN cmtid IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN IP IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN Port IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN Authorization IS NOT NULL AND Authorization != '' THEN 1 ELSE 0 END) +
            (CASE WHEN name IS NOT NULL AND name != '' THEN 1 ELSE 0 END) +
            (CASE WHEN Security IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN LANIP IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN LANMask IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN WANIP IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN WANMask IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN WANGateway IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN DNS1 IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN DNS2 IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN DNS3 IS NOT NULL THEN 1 ELSE 0 END)
    """)

    cursor.execute("CREATE INDEX idx_temp_score ON nets(temp_score)")

    cursor.execute("""
        DELETE FROM nets WHERE id IN (
            SELECT id FROM (
                SELECT
                    id,
                    ROW_NUMBER() OVER (
                        PARTITION BY NoBSSID, BSSID, ESSID, NoWiFiKey, WiFiKey, NoWPS, WPSPIN
                        ORDER BY temp_score DESC, id DESC
                    ) as rn
                FROM nets
            ) sub
            WHERE rn > 1
        )
    """)

    deleted_count = count_before - cursor.execute("SELECT COUNT(*) FROM nets").fetchone()[0]
    conn.commit()

    cursor.execute("DROP INDEX idx_temp_score")
    cursor.execute("ALTER TABLE nets DROP COLUMN temp_score")
    conn.commit()

    print(f"\n  ✅ Deleted {deleted_count:,} duplicate rows")
    print(f"     ({(deleted_count/count_before)*100:.2f}% of total)")
    print(f"     Remaining: {count_before - deleted_count:,} unique rows")

    print(f"\n  Collecting duplicate statistics...")
    stats_rows = cursor.execute("""
        SELECT
            NoBSSID,
            substr(BSSID, 1, 20) as bssid_short,
            ESSID,
            COUNT(*) as dup_count,
            MAX(id) as max_id,
            MIN(id) as min_id,
            AVG(LENGTH(WiFiKey)) as avg_key_len
        FROM (
            SELECT *,
                   ROW_NUMBER() OVER (
                       PARTITION BY NoBSSID, BSSID, ESSID, NoWiFiKey, WiFiKey, NoWPS, WPSPIN
                       ORDER BY id DESC
                   ) as rn
            FROM nets  -- already without duplicates after deletion
        )
        WHERE rn > 1
        GROUP BY NoBSSID, BSSID, ESSID
        ORDER BY dup_count DESC
        LIMIT 20
    """).fetchall()

    if stats_rows:
        print(f"\n  Top duplicate groups (by count):")
        for i, row in enumerate(stats_rows[:10], 1):
            no_bssid, bssid_short, essid, dup_count, max_id, min_id, avg_key_len = row
            print(f"    {i:2d}. NoBSSID={no_bssid}, BSSID='{bssid_short}...', ESSID='{essid}'")
            print(f"        → {dup_count} duplicate(s), id range [{min_id}-{max_id}], avg key len={avg_key_len:.1f}")

    type_dist = cursor.execute("""
        SELECT
            CASE WHEN name IS NULL OR name = '' THEN 'no_name' ELSE 'has_name' END as name_status,
            CASE WHEN Authorization IS NOT NULL AND Authorization != '' THEN 'has_auth' ELSE 'no_auth' END as auth_status,
            COUNT(*) as cnt
        FROM nets
        GROUP BY 1, 2
        ORDER BY cnt DESC
    """).fetchall()

    print(f"\n  Data richness distribution:")
    for row in type_dist:
        name_status, auth_status, cnt = row
        pct = (cnt / count_before) * 100 if count_before > 0 else 0
        print(f"    {name_status:15s} + {auth_status:15s}: {cnt:>8,d} ({pct:.2f}%)")

    conn.close()

    print(f"\nStep 4: Creating indexes...")
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()

    nets_indexes = [
        ("idx_nets_wifi", "NoBSSID, BSSID, ESSID, NoWiFiKey, WiFiKey, NoWPS, WPSPIN", True),
        ("idx_nets_time", "time", False),
        ("idx_nets_bssid", "BSSID", False),
        ("idx_nets_essid", "ESSID", False),
        ("idx_nets_wifipin", "WiFiKey", False),
        ("idx_nets_wpspin", "WPSPIN", False),
    ]

    print("Creating nets indexes...")
    for idx_name, columns, is_unique in nets_indexes:
        unique_prefix = "UNIQUE" if is_unique else ""
        sql = f"CREATE {unique_prefix} INDEX IF NOT EXISTS {idx_name} ON nets({columns})"
        try:
            cursor.execute(sql)
            print(f"  Created: {idx_name} ({'UNIQUE' if is_unique else 'regular'} on ({columns}))")
        except Exception as e:
            print(f"  Warning: Failed to create {idx_name}: {e}")

    geo_indexes = [
        "CREATE INDEX IF NOT EXISTS idx_geo_quadkey ON geo(quadkey)",
    ]

    print("Creating geo indexes...")
    for idx_sql in geo_indexes:
        try:
            cursor.execute(idx_sql)
        except Exception as e:
            print(f"  Warning: Failed to create index: {e}")

    conn.commit()

    print(f"Step 5: Optimizing SQLite...")
    print("Running VACUUM (this may take a while)...")
    conn.execute("VACUUM")
    print("Running ANALYZE...")
    conn.execute("ANALYZE")

    integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
    print(f"Integrity check: {integrity}")

    nets_count = conn.execute("SELECT COUNT(*) FROM nets").fetchone()[0]
    geo_count = conn.execute("SELECT COUNT(*) FROM geo").fetchone()[0]
    db_size_mb = os.path.getsize(db_file) / (1024 * 1024)

    print("\n=== Final Database Statistics ===")
    print(f"Tables created: {list(schema.keys())}")
    print(f"nets rows: {nets_count:,}")
    print(f"geo rows: {geo_count:,}")
    print(f"Database size: {db_size_mb:.1f} MB ({db_size_mb/1024:.1f} GB)")

    print("\nIndexes created:")
    indexes = conn.execute("SELECT name, tbl_name FROM sqlite_master WHERE type='index' ORDER BY name").fetchall()
    for idx in indexes:
        print(f"  - {idx[0]} ({idx[1]})")

    conn.close()
    print("\nDone!")

if __name__ == "__main__":
    main("dump-p3wifi-20260416.sql", "p3wifi.sqlite")