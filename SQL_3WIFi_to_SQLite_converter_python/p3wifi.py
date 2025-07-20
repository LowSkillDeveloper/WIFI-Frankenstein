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
import mmap
import os
import shutil
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
    sql_files = glob.glob(os.path.join(script_dir, "*.sql"))
    return [os.path.basename(f) for f in sql_files]

def select_sql_file():
    sql_files = find_sql_files()
    
    if not sql_files:
        print("\nNo SQL files found in the script directory!")
        print("Please place your SQL file in the same directory as this script.")
        return None
    
    print("\nAvailable SQL files:")
    for i, file in enumerate(sql_files, 1):
        print(f"  {i}. {file}")
    
    while True:
        try:
            choice = input(f"\nSelect file (1-{len(sql_files)}): ").strip()
            if choice.isdigit():
                idx = int(choice) - 1
                if 0 <= idx < len(sql_files):
                    return sql_files[idx]
            print("Invalid choice. Please try again.")
        except (ValueError, KeyboardInterrupt):
            return None

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

def split_large_file(file_path, base_name, extension, num_parts):
    
    file_size = os.path.getsize(file_path)
    print(f"Splitting archive into {num_parts} parts...")
    
    part_size = file_size // num_parts
    remainder = file_size % num_parts
    parts_created = 0
    
    with open(file_path, 'rb') as source:
        for part_num in range(1, num_parts + 1):
            part_name = f"{base_name}{extension}.{part_num:03d}"
            current_part_size = part_size + (remainder if part_num == num_parts else 0)
            
            with open(part_name, 'wb') as part_file:
                written = 0
                while written < current_part_size:
                    chunk = source.read(min(1024*1024, int(current_part_size - written)))
                    if not chunk:
                        break
                    part_file.write(chunk)
                    written += len(chunk)
            
            parts_created += 1
            print(f"Created part {parts_created}: {part_name} ({written / (1024**3):.2f} GB)")
    
    os.remove(file_path)
    print(f"Archive split into {parts_created} parts")
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

def find_insert_blocks(content):
    insert_blocks = []
    
    pattern = r'INSERT INTO\s+`?(\w+)`?\s*\([^)]+\)\s*VALUES'
    
    for match in re.finditer(pattern, content, re.IGNORECASE):
        start_pos = match.start()
        table_name = match.group(1)
        
        next_insert = content.find('INSERT INTO', start_pos + 1)
        if next_insert == -1:
            end_pos = len(content)
        else:
            end_pos = next_insert
        
        insert_block = content[start_pos:end_pos].strip()
        insert_block = re.sub(r'[;\s]+$', ';', insert_block)
        
        insert_blocks.append((table_name, insert_block))
        
    return insert_blocks

def parse_insert_block(insert_block):
    table_match = re.search(r'INSERT INTO\s+`?(\w+)`?', insert_block, re.IGNORECASE)
    if not table_match:
        return None, None, None
        
    table_name = table_match.group(1)
    
    cols_match = re.search(r'INSERT INTO\s+`?\w+`?\s*\(([^)]+)\)', insert_block, re.IGNORECASE)
    if not cols_match:
        return None, None, None
        
    columns = [col.strip().strip('`') for col in cols_match.group(1).split(',')]
    
    values_match = re.search(r'VALUES\s*(.+)', insert_block, re.IGNORECASE | re.DOTALL)
    if not values_match:
        return None, None, None
        
    values_text = values_match.group(1).strip()
    if values_text.endswith(';'):
        values_text = values_text[:-1]
        
    all_values = parse_multiple_values(values_text)
    
    return table_name, columns, all_values

def parse_multiple_values(values_text):
    all_values = []
    
    i = 0
    while i < len(values_text):
        while i < len(values_text) and values_text[i] in ' \t\n\r':
            i += 1
            
        if i >= len(values_text):
            break
            
        if values_text[i] == '(':
            row_start = i
            i += 1
            paren_count = 1
            in_string = False
            string_char = None
            escape_next = False
            
            while i < len(values_text) and paren_count > 0:
                char = values_text[i]
                
                if escape_next:
                    escape_next = False
                    i += 1
                    continue
                    
                if char == '\\' and in_string:
                    escape_next = True
                    i += 1
                    continue
                    
                if not in_string:
                    if char in ["'", '"']:
                        in_string = True
                        string_char = char
                    elif char == '(':
                        paren_count += 1
                    elif char == ')':
                        paren_count -= 1
                else:
                    if char == string_char and not escape_next:
                        in_string = False
                        string_char = None
                        
                i += 1
            
            if paren_count == 0:
                row_content = values_text[row_start+1:i-1]
                row_values = parse_row_values(row_content)
                all_values.append(row_values)
            
            while i < len(values_text) and values_text[i] in ', \t\n\r':
                i += 1
        else:
            i += 1
            
    return all_values

def parse_row_values(row_content):
    values = []
    current_value = []
    in_string = False
    escape_next = False
    string_char = None

    i = 0
    while i < len(row_content):
        char = row_content[i]

        if escape_next:
            if char == 'n':
                current_value.append('\n')
            elif char == 't':
                current_value.append('\t')
            elif char == 'r':
                current_value.append('\r')
            elif char == '\\':
                current_value.append('\\')
            elif char == "'":
                current_value.append("'")
            elif char == '"':
                current_value.append('"')
            else:
                current_value.append(char)
            escape_next = False
            i += 1
            continue

        if char == '\\':
            if in_string:
                escape_next = True
            else:
                current_value.append(char)
            i += 1
            continue

        if not in_string:
            if char in ("'", '"'):
                in_string = True
                string_char = char
            elif char == ',':
                val_str = ''.join(current_value).strip()
                values.append(process_value(val_str))
                current_value = []
            else:
                current_value.append(char)
        else:
            if char == string_char:
                in_string = False
                string_char = None
            else:
                current_value.append(char)
        i += 1

    val_str = ''.join(current_value).strip()
    values.append(process_value(val_str))

    return values

def process_value(val):
    if not val:
        return ''
        
    val = val.strip()

    if val.upper() == 'NULL':
        return None

    if (val.startswith("b'") and val.endswith("'")) or (val.startswith('b"') and val.endswith('"')):
        bin_val = val[2:-1]
        if bin_val == '0': 
            return 0
        if bin_val == '1': 
            return 1
        return bin_val

    if val.upper() == 'TRUE':
        return 1
    if val.upper() == 'FALSE':
        return 0

    if (val.startswith("'") and val.endswith("'")) or (val.startswith('"') and val.endswith('"')):
        return val[1:-1]

    try:
        if '.' in val:
            return float(val)
        else:
            num = int(val)
            if num > 9223372036854775807 or num < -9223372036854775808:
                return str(num)
            return num
    except ValueError:
        pass

    if val == '<empty>':
        return ''

    return val

def create_indices(cursor, option):
    if option == 1:
        print("\nCreating full indexes...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_BSSID ON nets (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_ESSID ON nets (ESSID COLLATE NOCASE);")
        cursor.execute("CREATE INDEX idx_nets_wpspin ON nets(WPSPIN);")
        cursor.execute("CREATE INDEX idx_nets_wifikey ON nets(WiFiKey COLLATE NOCASE);")
        print("Full indexing completed.")
        
    elif option == 2:
        print("\nCreating basic indexes...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_BSSID ON nets (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_ESSID ON nets (ESSID COLLATE NOCASE);")
        print("Basic indexing completed.")
        
    elif option == 3:
        print("Skipping index creation as per user choice.")
    else:
        print("Invalid option selected for index creation.")

def process_insert_block_data(block_data):
    try:
        table_name, insert_block, num_columns, error_file = block_data
        
        parsed_table, columns, values = parse_insert_block(insert_block)
        
        if not parsed_table or not columns or not values:
            return [], []
        
        valid_rows = []
        errors = []
        
        for row in values:
            if len(row) == num_columns:
                valid_rows.append(row)
            else:
                errors.append((str(row), f"Invalid column count: expected {num_columns}, got {len(row)}"))

        if errors:
            with open(error_file, 'a', encoding='utf-8') as err_file:
                for error_line, error_msg in errors:
                    err_file.write(f"Error in {table_name}: {error_msg}\nLine: {error_line}\n\n")
        
        return valid_rows, errors
        
    except Exception as e:
        print(f"Error processing INSERT block: {str(e)}")
        return [], []

def execute_inserts(db_file, sql_file, table_name, num_columns, error_file):
    print(f"\nProcessing {table_name} table...")
    
    print("Reading SQL file...")
    try:
        with open(sql_file, 'r', encoding='utf-8') as f:
            content = f.read()
    except UnicodeDecodeError:
        try:
            with open(sql_file, 'r', encoding='latin1') as f:
                content = f.read()
        except UnicodeDecodeError:
            with open(sql_file, 'r', encoding='cp1251') as f:
                content = f.read()
    
    print(f"File size: {len(content):,}")
    
    print("Cleaning MySQL comments...")
    content = re.sub(r'/\*!\d+\s+SET[^*]+\*/', '', content)
    content = re.sub(r'/\*!\d+\s+(.+?)\s+\*/', r'\1', content)
    content = re.sub(r'^--.*$', '', content, flags=re.MULTILINE)
    
    print("Finding INSERT blocks...")
    insert_blocks = find_insert_blocks(content)
    
    table_blocks = [(name, block) for name, block in insert_blocks if name == table_name]
    
    if not table_blocks:
        print(f"No INSERT blocks found for table {table_name}")
        return 0, 0
    
    print(f"Found {len(table_blocks)} INSERT blocks for table {table_name}")
    
    num_processes = max(1, multiprocessing.cpu_count() - 1)
    print(f"Using {num_processes} processes for parallel processing...")
    
    conn = sqlite3.connect(db_file, timeout=60)
    cursor = conn.cursor()
    
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=10000")
    cursor.execute("PRAGMA temp_store=MEMORY")
    cursor.execute("PRAGMA foreign_keys=OFF")
    
    processed_records = 0
    error_records = 0
    batch_size = 5000
    
    try:
        block_data_list = []
        for table_name_block, insert_block in table_blocks:
            block_data_list.append((table_name_block, insert_block, num_columns, error_file))
        
        with ProcessPoolExecutor(max_workers=num_processes) as executor:
            futures = [executor.submit(process_insert_block_data, block_data) for block_data in block_data_list]
            
            all_valid_rows = []
            with tqdm(total=len(futures), desc=f"Processing {table_name} blocks", unit="block") as pbar:
                for future in as_completed(futures):
                    try:
                        valid_rows, errors = future.result()
                        error_records += len(errors)
                        all_valid_rows.extend(valid_rows)
                        pbar.update(1)
                    except Exception as e:
                        print(f"Error processing block: {str(e)}")
                        pbar.update(1)
        
        print(f"Total valid rows extracted: {len(all_valid_rows):,}")
        
        if all_valid_rows:
            placeholders = ', '.join(['?'] * num_columns)
            sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
            
            print(f"Inserting data into database...")
            with tqdm(total=len(all_valid_rows), desc=f"Inserting {table_name} data", unit="row") as pbar:
                for i in range(0, len(all_valid_rows), batch_size):
                    batch = all_valid_rows[i:i+batch_size]
                    
                    try:
                        cursor.executemany(sql, batch)
                        conn.commit()
                        processed_records += len(batch)
                        pbar.update(len(batch))
                        
                    except sqlite3.Error as e:
                        with open(error_file, 'a', encoding='utf-8') as err_file:
                            err_file.write(f"Batch insert error: {str(e)}\n")
                            err_file.write("Failed records:\n")
                            for record in batch:
                                err_file.write(f"{record}\n")
                            err_file.write("\n")
                        
                        error_records += len(batch)
                        pbar.update(len(batch))
    
    finally:
        conn.close()
        
        with open(error_file, 'a', encoding='utf-8') as err_file:
            err_file.write(f"\n\n{'='*50}\nSUMMARY for {table_name}\n{'='*50}\n")
            err_file.write(f"Total failed records: {error_records}\n")
            err_file.write(f"Total successfully processed records: {processed_records}\n")
            
    return processed_records, error_records

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
                force_close_connections(db_file)
            else:
                print("\nCould not optimize database after multiple attempts.")
                print("The database is still usable, but not optimized.")
                return False

def force_close_connections(db_file):
    time.sleep(2)

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
    CREATE TABLE IF NOT EXISTS nets (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        time TIMESTAMP,
        cmtid INTEGER,
        IP INTEGER,
        Port INTEGER,
        Authorization TEXT,
        name TEXT,
        RadioOff INTEGER DEFAULT 0,
        Hidden INTEGER DEFAULT 0,
        NoBSSID INTEGER NOT NULL,
        BSSID INTEGER NOT NULL,
        ESSID TEXT,
        Security INTEGER,
        NoWiFiKey INTEGER DEFAULT 0,
        WiFiKey TEXT DEFAULT '',
        NoWPS INTEGER DEFAULT 0,
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

def create_archive(db_file, archive_options):
    available_tools = check_archiver_availability()
    
    if available_tools.get('7z') and archive_options['format'] == '7z':
        return create_archive_7z(db_file, archive_options)
    
    return create_archive_python(db_file, archive_options)

def create_archive_7z(db_file, archive_options):
    archive_format = archive_options['format']
    compression_level = archive_options['compression']
    split_archive = archive_options['split']
    
    print(f"\nCreating {archive_format.upper()} archive using 7-Zip...")
    
    base_name = os.path.splitext(db_file)[0]
    
    if archive_format == '7z':
        archive_name = f"{base_name}.7z"
        
        if compression_level == 'medium':
            compress_args = ['-mx=5']
        else:
            compress_args = ['-mx=9']
        
        if split_archive['enabled']:
            file_size_gb = get_file_size_gb(db_file)
            estimated_compressed_gb = file_size_gb * 0.5
            part_size_gb = estimated_compressed_gb / split_archive['parts']
            part_size_mb = int(part_size_gb * 1024)
            
            if part_size_mb < 50:
                part_size_mb = 50
            
            compress_args.extend([f'-v{part_size_mb}m'])
        
        cmd = ['7z', 'a'] + compress_args + [archive_name, db_file]
        
    else:
        archive_name = f"{base_name}.zip"
        
        if compression_level == 'medium':
            cmd = ['7z', 'a', '-tzip', '-mx=5', archive_name, db_file]
        else:
            cmd = ['7z', 'a', '-tzip', '-mx=9', archive_name, db_file]
    
    try:
        print(f"Starting compression...")
        if split_archive['enabled']:
            print(f"Archive will be split into {split_archive['parts']} parts")
        print("This may take several minutes for large files. Please wait...")
        
        process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, 
                                 universal_newlines=True, bufsize=1)
        
        def monitor_progress():
            dots = 0
            while process.poll() is None:
                print(".", end="", flush=True)
                dots += 1
                if dots % 60 == 0:
                    print()
                time.sleep(1)
        
        progress_thread = threading.Thread(target=monitor_progress)
        progress_thread.daemon = True
        progress_thread.start()
        
        stdout, stderr = process.communicate()
        
        print()
        
        if process.returncode == 0:
            print(f"Archive created successfully: {archive_name}")
            
            if archive_format == 'zip' and split_archive['enabled']:
                split_zip_archive_manual(archive_name, split_archive['parts'])
            
            original_size = get_file_size_gb(db_file)
            if split_archive['enabled'] and archive_format == '7z':
                total_compressed_size = 0
                part_num = 1
                while True:
                    part_name = f"{archive_name}.{part_num:03d}"
                    if os.path.exists(part_name):
                        total_compressed_size += get_file_size_gb(part_name)
                        part_num += 1
                    else:
                        break
                compressed_size = total_compressed_size
                print(f"Archive split into {part_num - 1} parts")
            else:
                compressed_size = get_file_size_gb(archive_name)
            
            compression_ratio = (1 - compressed_size / original_size) * 100 if original_size > 0 else 0
            
            print(f"Original size: {original_size:.2f} GB")
            print(f"Compressed size: {compressed_size:.2f} GB")
            print(f"Compression ratio: {compression_ratio:.1f}%")
            
            return True
        else:
            print(f"Error creating archive:")
            print(f"STDOUT: {stdout}")
            print(f"STDERR: {stderr}")
            return False
            
    except FileNotFoundError:
        print("Error: 7z not found. Please install 7-Zip.")
        return False
    except Exception as e:
        print(f"Error creating archive: {str(e)}")
        return False

def split_zip_archive_manual(archive_name, num_parts):
    try:
        base_name = os.path.splitext(archive_name)[0]
        return split_large_file(archive_name, base_name, ".zip", num_parts)
            
    except Exception as e:
        print(f"Error splitting archive: {str(e)}")
        return False

def show_indexing_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                   WiFi Database Converter                                         ║
║                                    Indexing Options                                               ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. Full indexing (Best performance, largest file size)                                           ║
║     - All geo indexes (BSSID, latitude, longitude)                                                ║
║     - All nets indexes (BSSID, ESSID, WPSPIN, WiFiKey)                                            ║
║                                                                                                   ║
║  2. Basic indexing (Good performance, moderate file size)                                         ║
║     - All geo indexes (BSSID, latitude, longitude)                                                ║
║     - Basic nets indexes (BSSID, ESSID)                                                           ║
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
    print("║                              p3WiFi Database Converter v3                                         ║")
    print("║                           SQL to SQLite for WIFI Frankenstein                                     ║")
    print("╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝")

    sql_file = 'input.sql'
    if not os.path.exists(sql_file):
        print(f"\nError: {sql_file} not found!")
        selected_file = select_sql_file()
        if selected_file is None:
            print("No SQL file selected. Exiting...")
            return
        sql_file = selected_file
        print(f"Using file: {sql_file}")
    
    while True:
        preferences = get_user_preferences()
        
        if preferences is None:
            print("Exiting...")
            break
        
        if not display_summary(preferences):
            continue
        
        start_time = time.time()
        db_file = 'p3wifi.db'
        error_file_geo = 'errors_geo.txt'
        error_file_nets = 'errors_nets.txt'

        print(f"\nStarting conversion process...")
        print(f"Source file: {sql_file}")
        print(f"Target database: {db_file}")

        with sqlite3.connect(db_file) as conn:
            cursor = conn.cursor()
            create_tables(cursor)

        records_geo, errors_geo = execute_inserts(db_file, sql_file, 'geo', 4, error_file_geo)
        records_nets, errors_nets = execute_inserts(db_file, sql_file, 'nets', 25, error_file_nets)

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
        print(f"  - Nets table: {records_nets:,} records (Errors: {errors_nets:,})")
        print(f"Database file: {db_file}")
        print(f"Database size: {get_file_size_gb(db_file):.2f} GB")
        
        if errors_geo > 0:
            print(f"Geo errors logged to: {error_file_geo}")
        if errors_nets > 0:
            print(f"Nets errors logged to: {error_file_nets}")

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