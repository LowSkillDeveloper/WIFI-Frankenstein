import sys
import subprocess
import time
from datetime import timedelta
import sqlite3
import re
from tqdm import tqdm
from concurrent.futures import ProcessPoolExecutor, as_completed
from importlib.metadata import distribution, PackageNotFoundError
import multiprocessing
import os
import zipfile
import threading
from pathlib import Path
import glob

def check_and_install_dependencies():
    required_packages = {'tqdm': 'tqdm'}
    missing_packages = []

    for package in required_packages:
        try:
            distribution(package)
        except PackageNotFoundError:
            missing_packages.append(package)

    if missing_packages:
        print("Installing missing dependencies...")
        for package in missing_packages:
            print(f"Installing {package}...")
            subprocess.check_call([sys.executable, "-m", "pip", "install", required_packages[package]])
        print("\nDependencies installed. Restarting script...")
        python = sys.executable
        subprocess.call([python] + sys.argv)
        sys.exit()

def find_sql_files():
    script_dir = os.path.dirname(os.path.abspath(__file__))

    patterns = ['geo.sql', 'base.sql', 'input.sql', '*.sql']
    found_files = {}
    
    for pattern in patterns:
        files = glob.glob(os.path.join(script_dir, pattern))
        for f in files:
            basename = os.path.basename(f)
            if basename not in found_files:
                found_files[basename] = f
    
    return found_files

def select_sql_files():
    sql_files = find_sql_files()
    
    if not sql_files:
        print("\nNo SQL files found in the script directory!")
        print("Please place your SQL files in the same directory as this script.")
        return None, None
    
    file_list = list(sql_files.keys())
    
    print("\nAvailable SQL files:")
    for i, file in enumerate(file_list, 1):
        print(f"  {i}. {file}")

    geo_file = None
    if 'geo.sql' in sql_files:
        geo_file = 'geo.sql'
        print(f"\nAutomatically selected geo.sql for geo table")
    else:
        while True:
            try:
                choice = input(f"\nSelect file for 'geo' table (1-{len(file_list)}): ").strip()
                if choice.isdigit():
                    idx = int(choice) - 1
                    if 0 <= idx < len(file_list):
                        geo_file = file_list[idx]
                        break
                print("Invalid choice. Please try again.")
            except (ValueError, KeyboardInterrupt):
                return None, None

    base_file = None
    if 'base.sql' in sql_files:
        base_file = 'base.sql'
        print(f"Automatically selected base.sql for base table")
    else:
        while True:
            try:
                choice = input(f"\nSelect file for 'base' table (1-{len(file_list)}): ").strip()
                if choice.isdigit():
                    idx = int(choice) - 1
                    if 0 <= idx < len(file_list):
                        base_file = file_list[idx]
                        break
                print("Invalid choice. Please try again.")
            except (ValueError, KeyboardInterrupt):
                return None, None
    
    return geo_file, base_file

def extract_insert_statements(content, table_name):
    print(f"Extracting INSERT statements for table: {table_name}")
    
    patterns = [
        rf'INSERT\s+IGNORE\s+INTO\s+`?{re.escape(table_name)}`?\s*\([^)]+\)\s*VALUES\s*(.+?)(?=INSERT\s+(?:IGNORE\s+)?INTO|$)',
        rf'INSERT\s+INTO\s+`?{re.escape(table_name)}`?\s*\([^)]+\)\s*VALUES\s*(.+?)(?=INSERT\s+(?:IGNORE\s+)?INTO|$)',
        rf'INSERT\s+IGNORE\s+INTO\s+`?{re.escape(table_name)}`?\s+VALUES\s*(.+?)(?=INSERT\s+(?:IGNORE\s+)?INTO|$)',
        rf'INSERT\s+INTO\s+`?{re.escape(table_name)}`?\s+VALUES\s*(.+?)(?=INSERT\s+(?:IGNORE\s+)?INTO|$)'
    ]
    
    all_values_blocks = []
    
    for pattern in patterns:
        matches = re.finditer(pattern, content, re.IGNORECASE | re.DOTALL)
        for match in matches:
            values_block = match.group(1).strip()
            if values_block and values_block not in [block for block, _ in all_values_blocks]:
                all_values_blocks.append((values_block, table_name))
    
    print(f"Found {len(all_values_blocks)} INSERT blocks for table {table_name}")
    return all_values_blocks

def parse_values_block_robust(values_text):
    try:
        values_text = values_text.strip()
        
        if values_text.endswith(';'):
            values_text = values_text[:-1].strip()
        
        rows = []
        current_row = ""
        paren_count = 0
        in_string = False
        string_char = None
        escape_next = False
        
        i = 0
        while i < len(values_text):
            char = values_text[i]
            
            if escape_next:
                current_row += char
                escape_next = False
                i += 1
                continue
                
            if char == '\\' and in_string:
                current_row += char
                escape_next = True
                i += 1
                continue
            
            if not in_string:
                if char in ('"', "'"):
                    in_string = True
                    string_char = char
                elif char == '(':
                    paren_count += 1
                elif char == ')':
                    paren_count -= 1
                    if paren_count == 0:
                        current_row += char
                        rows.append(current_row.strip())
                        current_row = ""
                        
                        i += 1
                        while i < len(values_text) and values_text[i] in ', \t\n\r':
                            i += 1
                        continue
            else:
                if char == string_char and not escape_next:
                    in_string = False
                    string_char = None
            
            current_row += char
            i += 1
        
        if current_row.strip():
            rows.append(current_row.strip())
        
        parsed_rows = []
        for row in rows:
            parsed_row = parse_single_row(row)
            if parsed_row is not None:
                parsed_rows.append(parsed_row)
        
        return parsed_rows
        
    except Exception as e:
        print(f"Error parsing values block: {str(e)}")
        return []

def parse_single_row(row_text):
    try:
        row_text = row_text.strip()
        
        if row_text.startswith('(') and row_text.endswith(')'):
            row_text = row_text[1:-1]
        
        values = []
        current_value = ""
        in_string = False
        string_char = None
        escape_next = False
        paren_count = 0
        
        i = 0
        while i < len(row_text):
            char = row_text[i]
            
            if escape_next:
                current_value += char
                escape_next = False
                i += 1
                continue
                
            if char == '\\' and in_string:
                current_value += char
                escape_next = True
                i += 1
                continue
            
            if not in_string:
                if char in ('"', "'"):
                    in_string = True
                    string_char = char
                    current_value += char
                elif char == '(':
                    paren_count += 1
                    current_value += char
                elif char == ')':
                    paren_count -= 1
                    current_value += char
                elif char == ',' and paren_count == 0:
                    values.append(process_value(current_value.strip()))
                    current_value = ""
                else:
                    current_value += char
            else:
                if char == string_char and not escape_next:
                    in_string = False
                    string_char = None
                current_value += char
            
            i += 1
        
        if current_value.strip():
            values.append(process_value(current_value.strip()))
        
        return values
        
    except Exception as e:
        print(f"Error parsing single row: {str(e)}")
        return None

def process_value(val):
    if not val:
        return None
        
    val = val.strip()
    
    if val.upper() == 'NULL':
        return None

    if val.startswith("b'") and val.endswith("'"):
        bin_val = val[2:-1]
        if bin_val == '0': 
            return 0
        elif bin_val == '1': 
            return 1
        else:
            return bin_val
    elif val.startswith('b"') and val.endswith('"'):
        bin_val = val[2:-1]
        if bin_val == '0': 
            return 0
        elif bin_val == '1': 
            return 1
        else:
            return bin_val

    if val.upper() == 'TRUE':
        return 1
    if val.upper() == 'FALSE':
        return 0

    if (val.startswith("'") and val.endswith("'")) or (val.startswith('"') and val.endswith('"')):
        inner_val = val[1:-1]
        inner_val = inner_val.replace("''", "'").replace('\\"', '"').replace("\\'", "'")
        return inner_val

    if re.match(r'\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}', val):
        return val

    try:
        if '.' in val or 'e' in val.lower():
            return float(val)
        else:
            num = int(val)
            if num > 9223372036854775807 or num < -9223372036854775808:
                return str(num)
            return num
    except ValueError:
        pass

    return val

def create_indices(cursor, option):
    if option == 1:
        print("\nCreating full indexes...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID COLLATE NOCASE);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_wpspin ON base(WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_wifikey ON base(WiFiKey COLLATE NOCASE);")
        print("Full indexing completed.")
        
    elif option == 2:
        print("\nCreating basic indexes...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID COLLATE NOCASE);")
        print("Basic indexing completed.")
        
    elif option == 3:
        print("Skipping index creation as per user choice.")
    else:
        print("Invalid option selected for index creation.")

def process_insert_data(args):
    values_block, table_name, expected_columns, error_file = args
    
    try:
        parsed_rows = parse_values_block_robust(values_block)
        
        valid_rows = []
        error_count = 0
        
        for row in parsed_rows:
            if row is None:
                error_count += 1
                continue
                
            if len(row) < expected_columns:
                row.extend([None] * (expected_columns - len(row)))
            elif len(row) > expected_columns:
                row = row[:expected_columns]
            
            valid_rows.append(row)
        
        return valid_rows, error_count
        
    except Exception as e:
        print(f"Error processing insert block: {str(e)}")
        with open(error_file, 'a', encoding='utf-8') as err_file:
            err_file.write(f"Error processing block for {table_name}: {str(e)}\n")
        return [], 1

def execute_inserts_improved(db_file, sql_file, table_name, num_columns, error_file):
    print(f"\nProcessing {table_name} table...")

    print("Reading SQL file...")
    content = None
    encodings = ['utf-8', 'latin1', 'cp1251', 'utf-8-sig']
    
    for encoding in encodings:
        try:
            with open(sql_file, 'r', encoding=encoding) as f:
                content = f.read()
            print(f"Successfully read file with {encoding} encoding")
            break
        except UnicodeDecodeError:
            continue
    
    if content is None:
        print("Failed to read file with any encoding")
        return 0, 0
    
    print(f"File size: {len(content):,} characters")

    print("Cleaning MySQL comments...")
    content = re.sub(r'/\*!\d+.*?\*/', '', content, flags=re.DOTALL)
    content = re.sub(r'^--.*', '', content, flags=re.MULTILINE)
    
    insert_blocks = extract_insert_statements(content, table_name)
    
    if not insert_blocks:
        print(f"No INSERT statements found for table {table_name}")
        return 0, 0
    
    print(f"Found {len(insert_blocks)} INSERT blocks for table {table_name}")
    
    num_processes = max(1, multiprocessing.cpu_count() - 1)
    print(f"Using {num_processes} processes for parallel processing...")
    
    all_rows = []
    total_errors = 0
    
    process_args = [(block, table_name, num_columns, error_file) for block, _ in insert_blocks]
    
    with ProcessPoolExecutor(max_workers=num_processes) as executor:
        futures = [executor.submit(process_insert_data, args) for args in process_args]
        
        with tqdm(total=len(futures), desc=f"Processing {table_name} blocks", unit="block") as pbar:
            for future in as_completed(futures):
                try:
                    valid_rows, error_count = future.result()
                    all_rows.extend(valid_rows)
                    total_errors += error_count
                    pbar.update(1)
                except Exception as e:
                    print(f"Error processing block: {str(e)}")
                    total_errors += 1
                    pbar.update(1)
    
    print(f"Total valid rows extracted: {len(all_rows):,}")
    print(f"Total errors: {total_errors:,}")
    
    if not all_rows:
        print("No valid rows to insert")
        return 0, total_errors
    
    conn = sqlite3.connect(db_file, timeout=60)
    cursor = conn.cursor()
    
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=10000")
    cursor.execute("PRAGMA temp_store=MEMORY")
    cursor.execute("PRAGMA foreign_keys=OFF")
    
    processed_records = 0
    batch_size = 5000
    
    try:
        placeholders = ', '.join(['?'] * num_columns)
        sql = f'INSERT INTO {table_name} VALUES ({placeholders})'
        
        print(f"Inserting data into database...")
        with tqdm(total=len(all_rows), desc=f"Inserting {table_name} data", unit="row") as pbar:
            for i in range(0, len(all_rows), batch_size):
                batch = all_rows[i:i+batch_size]
                
                try:
                    cursor.executemany(sql, batch)
                    conn.commit()
                    processed_records += len(batch)
                    pbar.update(len(batch))
                    
                except sqlite3.Error as e:
                    print(f"\nDatabase error with batch starting at row {i}: {str(e)}")
                    for j, record in enumerate(batch):
                        try:
                            cursor.execute(sql, record)
                            conn.commit()
                            processed_records += 1
                        except sqlite3.Error as single_error:
                            total_errors += 1
                            with open(error_file, 'a', encoding='utf-8') as err_file:
                                err_file.write(f"Single insert error at row {i+j}: {str(single_error)}\n")
                                err_file.write(f"Failed record: {record}\n\n")
                    pbar.update(len(batch))
    
    finally:
        conn.close()
        
        verification_conn = sqlite3.connect(db_file)
        verification_cursor = verification_conn.cursor()
        verification_cursor.execute(f"SELECT COUNT(*) FROM {table_name}")
        actual_count = verification_cursor.fetchone()[0]
        verification_conn.close()
        
        print(f"Final verification: {actual_count:,} records in {table_name} table")
        
        with open(error_file, 'a', encoding='utf-8') as err_file:
            err_file.write(f"\n\n{'='*50}\nSUMMARY for {table_name}\n{'='*50}\n")
            err_file.write(f"Total valid rows extracted: {len(all_rows)}\n")
            err_file.write(f"Total failed records: {total_errors}\n")
            err_file.write(f"Total successfully processed records: {processed_records}\n")
            err_file.write(f"Actual records in database: {actual_count}\n")
        
    return processed_records, total_errors

def optimize_and_vacuum(db_file):
    print("\nOptimizing and vacuuming database...")
    max_attempts = 5
    attempt = 0
    
    while attempt < max_attempts:
        try:
            if attempt > 0:
                wait_time = 3 * (attempt + 1)
                print(f"Waiting {wait_time} seconds before next attempt...")
                time.sleep(wait_time)
            
            with sqlite3.connect(db_file, isolation_level=None, timeout=30) as conn:
                cursor = conn.cursor()
                
                print("Running ANALYZE...")
                cursor.execute("ANALYZE")
                conn.commit()
                
                print("Running PRAGMA optimize...")
                cursor.execute("PRAGMA optimize")
                conn.commit()
                
                print("Running VACUUM...")
                cursor.execute("VACUUM")
                conn.commit()
                
                cursor.close()
                return True
                
        except sqlite3.OperationalError as e:
            attempt += 1
            print(f"Optimization attempt {attempt}/{max_attempts} failed: {str(e)}")
            
            if attempt < max_attempts:
                time.sleep(2)
            else:
                print("\nCould not optimize database after multiple attempts.")
                print("The database is still usable, but not optimized.")
                return False

def create_tables(cursor):
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS geo (
        BSSID INTEGER,
        latitude FLOAT,
        longitude FLOAT,
        quadkey INTEGER
    )
    ''')
    
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS base (
        id INTEGER PRIMARY KEY,
        time TIMESTAMP,
        cmtid INTEGER,
        IP INTEGER,
        Port INTEGER,
        Authorization TEXT,
        name TEXT,
        RadioOff INTEGER NOT NULL DEFAULT 0,
        Hidden INTEGER NOT NULL DEFAULT 0,
        NoBSSID INTEGER NOT NULL,
        BSSID INTEGER NOT NULL,
        ESSID TEXT,
        Security INTEGER,
        WiFiKey TEXT NOT NULL DEFAULT '',
        WPSPIN INTEGER NOT NULL,
        LANIP INTEGER,
        LANMask INTEGER,
        WANIP INTEGER,
        WANMask INTEGER,
        WANGateway INTEGER,
        DNS1 INTEGER,
        DNS2 INTEGER,
        DNS3 INTEGER
    )
    ''')

def get_file_size_gb(file_path):
    if os.path.exists(file_path):
        size_bytes = os.path.getsize(file_path)
        return size_bytes / (1024 ** 3)
    return 0

def check_archiver_availability():
    available_tools = {'python': True}
    
    try:
        subprocess.run(['7z'], capture_output=True, check=False)
        available_tools['7z'] = True
    except FileNotFoundError:
        available_tools['7z'] = False
    
    return available_tools

def create_archive_python(db_file, archive_options):
    archive_format = archive_options['format']
    compression_level = archive_options['compression']
    split_archive = archive_options['split']
    
    print(f"\nCreating {archive_format.upper()} archive using Python libraries...")
    
    base_name = os.path.splitext(db_file)[0]
    
    try:
        if archive_format == 'zip':
            return create_zip_python(db_file, base_name, compression_level, split_archive)
        else:
            print(f"Unsupported format for Python libraries: {archive_format}")
            print("Only ZIP format is supported with Python built-in libraries.")
            return False
            
    except Exception as e:
        print(f"Error creating archive with Python: {str(e)}")
        return False

def create_zip_python(db_file, base_name, compression_level, split_archive):
    
    if compression_level == 'maximum':
        compress_level = 9
        compress_type = zipfile.ZIP_DEFLATED
    else:
        compress_level = 6
        compress_type = zipfile.ZIP_DEFLATED
    
    if not split_archive['enabled']:
        archive_name = f"{base_name}.zip"
        file_size = os.path.getsize(db_file)
        
        print("Compressing database file...")
        
        with zipfile.ZipFile(archive_name, 'w', compress_type, compresslevel=compress_level, 
                           allowZip64=True) as zipf:
            with tqdm(total=file_size, desc="Compressing", unit="B", unit_scale=True, unit_divisor=1024) as pbar:
                with open(db_file, 'rb') as src:
                    info = zipfile.ZipInfo(filename=os.path.basename(db_file))
                    info.compress_type = compress_type
                    
                    with zipf.open(info, 'w', force_zip64=True) as dst:
                        chunk_size = 1024 * 1024
                        while True:
                            chunk = src.read(chunk_size)
                            if not chunk:
                                break
                            dst.write(chunk)
                            pbar.update(len(chunk))
        
        return show_compression_stats(db_file, archive_name)
    
    else:
        return create_split_zip_python_with_progress(db_file, base_name, compress_type, compress_level, split_archive['parts'])

def create_split_zip_python_with_progress(db_file, base_name, compress_type, compress_level, num_parts):
    
    file_size = os.path.getsize(db_file)
    temp_zip = f"{base_name}_temp.zip"
    
    print("Compressing database file...")
    with zipfile.ZipFile(temp_zip, 'w', compress_type, compresslevel=compress_level,
                        allowZip64=True) as zipf:
        with tqdm(total=file_size, desc="Compressing", unit="B", unit_scale=True, unit_divisor=1024) as pbar:
            with open(db_file, 'rb') as src:
                info = zipfile.ZipInfo(filename=os.path.basename(db_file))
                info.compress_type = compress_type
                
                with zipf.open(info, 'w', force_zip64=True) as dst:
                    chunk_size = 1024 * 1024
                    while True:
                        chunk = src.read(chunk_size)
                        if not chunk:
                            break
                        dst.write(chunk)
                        pbar.update(len(chunk))
    
    compressed_size = os.path.getsize(temp_zip)
    
    print(f"Splitting archive into {num_parts} parts...")
    
    part_size = compressed_size // num_parts
    remainder = compressed_size % num_parts
    parts_created = 0
    
    with open(temp_zip, 'rb') as source:
        with tqdm(total=compressed_size, desc="Splitting", unit="B", unit_scale=True, unit_divisor=1024) as pbar:
            for part_num in range(1, num_parts + 1):
                part_name = f"{base_name}.zip.{part_num:03d}"
                current_part_size = part_size + (remainder if part_num == num_parts else 0)
                
                with open(part_name, 'wb') as part_file:
                    written = 0
                    while written < current_part_size:
                        chunk_size = min(1024*1024, int(current_part_size - written))
                        chunk = source.read(chunk_size)
                        if not chunk:
                            break
                        part_file.write(chunk)
                        written += len(chunk)
                        pbar.update(len(chunk))
                
                parts_created += 1
                print(f"Created part {parts_created}: {part_name} ({written / (1024**3):.2f} GB)")
    
    os.remove(temp_zip)
    
    total_compressed_size = sum(os.path.getsize(f"{base_name}.zip.{i:03d}") 
                               for i in range(1, parts_created + 1))
    
    original_size = get_file_size_gb(db_file)
    compressed_size_gb = total_compressed_size / (1024 ** 3)
    compression_ratio = (1 - compressed_size_gb / original_size) * 100 if original_size > 0 else 0
    
    print(f"\nArchive split into {parts_created} parts")
    print(f"Original size: {original_size:.2f} GB")
    print(f"Total compressed size: {compressed_size_gb:.2f} GB")
    print(f"Compression ratio: {compression_ratio:.1f}%")
    
    return True

def show_compression_stats(original_file, compressed_file):
    original_size = get_file_size_gb(original_file)
    compressed_size = get_file_size_gb(compressed_file)
    compression_ratio = (1 - compressed_size / original_size) * 100 if original_size > 0 else 0
    
    print(f"Archive created successfully: {compressed_file}")
    print(f"Original size: {original_size:.2f} GB")
    print(f"Compressed size: {compressed_size:.2f} GB")
    print(f"Compression ratio: {compression_ratio:.1f}%")
    
    return True

def create_archive(db_file, archive_options):
    available_tools = check_archiver_availability()
    
    if available_tools.get('7z') and archive_options['format'] == '7z':
        pass
    
    return create_archive_python(db_file, archive_options)

def show_indexing_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                   SQL to SQLite Database Converter                                ║
║                                    Indexing Options                                               ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. Full indexing (Best performance, largest file size)                                           ║
║     - All geo indexes (BSSID, latitude, longitude)                                                ║
║     - All base indexes (BSSID, ESSID, WPSPIN, WiFiKey)                                            ║
║                                                                                                   ║
║  2. Basic indexing (Good performance, moderate file size)                                         ║
║     - All geo indexes (BSSID, latitude, longitude)                                                ║
║     - Basic base indexes (BSSID, ESSID)                                                           ║
║                                                                                                   ║
║  3. No indexing (Smallest file size, slowest queries)                                             ║
║                                                                                                   ║
║  4. Exit                                                                                          ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-4): """
    return input(menu)

def show_archive_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                     Archive Options                                               ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. Create archive                                                                                ║
║  2. Don't create archive                                                                          ║
║  3. Back to previous menu                                                                         ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-3): """
    return input(menu)

def show_split_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                   Split Archive Options                                           ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. Split archive into multiple parts                                                             ║
║  2. Create single archive file                                                                    ║
║  3. Back to previous menu                                                                         ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-3): """
    return input(menu)

def show_parts_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                 Number of Archive Parts                                           ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  2. Split into 2 parts                                                                            ║
║  3. Split into 3 parts                                                                            ║
║  4. Split into 4 parts                                                                            ║
║  5. Split into 5 parts                                                                            ║
║  6. Back to previous menu                                                                         ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose number of parts (2-6): """
    return input(menu)

def show_format_menu():
    available_tools = check_archiver_availability()
    
    if available_tools.get('7z'):
        menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                  Archive Format Options                                           ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. 7Z format (requires 7-Zip)                                                                    ║
║  2. ZIP format (Universal)                                                                        ║
║  3. Back to previous menu                                                                         ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-3): """
    else:
        menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                            Archive Format Options (Python built-in)                               ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. ZIP format                                                                                    ║
║  2. Back to previous menu                                                                         ║
║                                                                                                   ║
║  Note: 7-Zip not found. Install 7-Zip for 7Z format.                                              ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-2): """
    
    return input(menu)

def show_compression_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                 Compression Level Options                                         ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. Medium compression (Faster processing, moderate compression)                                  ║
║  2. Maximum compression (Slower processing, best compression)                                     ║
║  3. Back to previous menu                                                                         ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-3): """
    return input(menu)

def get_user_preferences():
    preferences = {}
    
    while True:
        indexing_choice = show_indexing_menu()
        if indexing_choice == '4':
            return None
        elif indexing_choice in ['1', '2', '3']:
            preferences['indexing'] = int(indexing_choice)
            break
        else:
            print("Invalid choice. Please try again.")
    
    while True:
        archive_choice = show_archive_menu()
        if archive_choice == '3':
            continue
        elif archive_choice == '2':
            preferences['create_archive'] = False
            return preferences
        elif archive_choice == '1':
            preferences['create_archive'] = True
            break
        else:
            print("Invalid choice. Please try again.")
    
    available_tools = check_archiver_availability()
    if not available_tools.get('7z'):
        print("\nNote: 7-Zip not found. Using Python built-in compression libraries.")
        time.sleep(2)
    
    while True:
        split_choice = show_split_menu()
        if split_choice == '3':
            continue
        elif split_choice == '2':
            preferences['split_archive'] = {'enabled': False}
            break
        elif split_choice == '1':
            while True:
                parts_choice = show_parts_menu()
                if parts_choice == '6':
                    break
                elif parts_choice in ['2', '3', '4', '5']:
                    preferences['split_archive'] = {'enabled': True, 'parts': int(parts_choice)}
                    break
                else:
                    print("Invalid choice. Please try again.")
            
            if 'split_archive' in preferences:
                break
        else:
            print("Invalid choice. Please try again.")
    
    while True:
        format_choice = show_format_menu()
        available_tools = check_archiver_availability()
        
        if available_tools.get('7z'):
            if format_choice == '3':
                continue
            elif format_choice == '1':
                preferences['format'] = '7z'
                break
            elif format_choice == '2':
                preferences['format'] = 'zip'
                break
            else:
                print("Invalid choice. Please try again.")
        else:
            if format_choice == '2':
                continue
            elif format_choice == '1':
                preferences['format'] = 'zip'
                break
            else:
                print("Invalid choice. Please try again.")
    
    while True:
        compression_choice = show_compression_menu()
        if compression_choice == '3':
            continue
        elif compression_choice == '1':
            preferences['compression'] = 'medium'
            break
        elif compression_choice == '2':
            preferences['compression'] = 'maximum'
            break
        else:
            print("Invalid choice. Please try again.")
    
    return preferences

def display_summary(preferences):
    print("\n" + "="*80)
    print("CONVERSION SUMMARY")
    print("="*80)
    
    indexing_types = {
        1: 'Full indexing (All indexes)',
        2: 'Basic indexing (Essential indexes)',
        3: 'No indexing'
    }
    print(f"Indexing: {indexing_types[preferences['indexing']]}")
    
    if preferences['create_archive']:
        print(f"Archive: Yes")
        print(f"Format: {preferences['format'].upper()}")
        if preferences['split_archive']['enabled']:
            print(f"Split: Yes ({preferences['split_archive']['parts']} parts)")
        else:
            print(f"Split: No")
        print(f"Compression: {preferences['compression'].title()}")
    else:
        print(f"Archive: No")
    
    print("="*80)
    
    while True:
        confirm = input("Proceed with these settings? (y/n): ").lower().strip()
        if confirm in ['y', 'yes']:
            return True
        elif confirm in ['n', 'no']:
            return False
        else:
            print("Please enter 'y' for yes or 'n' for no.")

def main():
    check_and_install_dependencies()
    
    sqlite3.connect(':memory:', timeout=60).close()

    print("╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗")
    print("║                           Anti3WiFi & 3WiFi Database Converter v3                                 ║")
    print("║                             SQL to SQLite for WIFI Frankenstein                                   ║")
    print("╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝")

    geo_file, base_file = None, None

    if os.path.exists('geo.sql') and os.path.exists('base.sql'):
        geo_file, base_file = 'geo.sql', 'base.sql'
        print(f"\nAutomatically detected: geo.sql and base.sql")
    else:
        print("\nRequired files: geo.sql and base.sql not found.")
        geo_file, base_file = select_sql_files()
        if geo_file is None or base_file is None:
            print("Required SQL files not selected. Exiting...")
            return
        print(f"Using files: {geo_file} (geo table), {base_file} (base table)")
    
    while True:
        preferences = get_user_preferences()
        
        if preferences is None:
            print("Exiting...")
            break
        
        if not display_summary(preferences):
            continue
        
        start_time = time.time()
        db_file = 'anti3wifi.db'
        error_file_geo = 'errors_geo.txt'
        error_file_base = 'errors_base.txt'

        print(f"\nStarting conversion process...")
        print(f"Source files: {geo_file}, {base_file}")
        print(f"Target database: {db_file}")

        with sqlite3.connect(db_file) as conn:
            cursor = conn.cursor()
            create_tables(cursor)

        records_geo, errors_geo = execute_inserts_improved(db_file, geo_file, 'geo', 4, error_file_geo)
        records_base, errors_base = execute_inserts_improved(db_file, base_file, 'base', 23, error_file_base)

        with sqlite3.connect(db_file) as conn:
            cursor = conn.cursor()
            create_indices(cursor, preferences['indexing'])

        optimize_and_vacuum(db_file)
        
        end_time = time.time()
        duration = end_time - start_time
        
        print(f"\n" + "="*80)
        print("DATABASE CONVERSION COMPLETED")
        print("="*80)
        print(f"Total processing time: {timedelta(seconds=int(duration))}")
        print(f"Records processed:")
        print(f"  - Geo table: {records_geo:,} records (Errors: {errors_geo:,})")
        print(f"  - Base table: {records_base:,} records (Errors: {errors_base:,})")
        print(f"Database file: {db_file}")
        print(f"Database size: {get_file_size_gb(db_file):.2f} GB")
        
        if errors_geo > 0:
            print(f"Geo errors logged to: {error_file_geo}")
        if errors_base > 0:
            print(f"Base errors logged to: {error_file_base}")

        if preferences['create_archive']:
            archive_options = {
                'format': preferences['format'],
                'compression': preferences['compression'],
                'split': preferences['split_archive']
            }
            
            archive_start = time.time()
            success = create_archive(db_file, archive_options)
            archive_end = time.time()
            archive_duration = archive_end - archive_start
            
            if success:
                print(f"Archive creation time: {timedelta(seconds=int(archive_duration))}")
            else:
                print("Archive creation failed!")
        
        print("="*80)
        print("Conversion completed successfully!")
        break

if __name__ == "__main__":
    main()