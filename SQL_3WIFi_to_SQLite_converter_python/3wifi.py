import sys
import subprocess
import time
from datetime import timedelta
import sqlite3
import re
from tqdm import tqdm
import logging
from importlib.metadata import distribution, PackageNotFoundError
import os
import csv
import io
import zipfile
import threading
from pathlib import Path

# Настройка логирования
logging.basicConfig(filename='debug.log', level=logging.DEBUG, format='%(asctime)s - %(levelname)s - %(message)s')

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

def check_archiver_availability():
    """Check what archive tools are available"""
    available_tools = {'python': True}
    
    try:
        subprocess.run(['7z'], capture_output=True, check=False)
        available_tools['7z'] = True
    except FileNotFoundError:
        available_tools['7z'] = False
    
    return available_tools

def clean_sql_content(sql_content, table_name):
    """Pre-process the SQL content to fix common issues before parsing"""
    fixed_content = sql_content
    fixed_content = fixed_content.replace("\\'", "''")
    
    pattern = rf"INSERT (?:IGNORE )?INTO `{table_name}`\s*\([^)]+\)\s*VALUES\s*"
    matches = re.findall(pattern, fixed_content)
    
    for match in matches:
        if not re.search(r"VALUES\s*\(", match):
            fixed_content = fixed_content.replace(match, match + "(")
    
    return fixed_content

def parse_values(values_str):
    """Enhanced parser for SQL values with better handling of complex cases"""
    logging.debug(f"Parsing values string: {values_str}")
    
    if values_str.startswith("((("):
        opening_count = 0
        for char in values_str:
            if char == '(':
                opening_count += 1
            else:
                break
        
        if opening_count > 1:
            values_str = "(" + values_str[opening_count:]
    
    if values_str.startswith("(") and values_str.endswith(")"):
        values_str = values_str[1:-1]
    
    csv_reader = csv.reader([values_str], delimiter=',', quotechar="'", escapechar='\\', doublequote=True)
    try:
        raw_values = next(csv_reader)
    except Exception as e:
        logging.error(f"CSV parsing failed: {e}. Falling back to manual parsing")
        values = []
        current_value = ''
        inside_quotes = False
        escape = False
        
        for i, char in enumerate(values_str):
            if char == "'" and not escape:
                inside_quotes = not inside_quotes
                current_value += char
            elif char == "\\" and inside_quotes:
                escape = not escape
                current_value += char
            elif char == "," and not inside_quotes:
                if i > 0 and values_str[i-1] == ')' and values_str[i-2] == "'":
                    current_value += char
                else:
                    values.append(current_value.strip())
                    current_value = ''
            else:
                current_value += char
                escape = False

        if current_value:
            values.append(current_value.strip())
        
        raw_values = values
    
    parsed_values = []
    for value in raw_values:
        value = value.strip()
        if value == 'NULL':
            parsed_values.append(None)
        elif value.startswith("b'") and value.endswith("'"):
            if value == "b'0'":
                parsed_values.append(0)
            else:
                try:
                    parsed_values.append(value[2:-1].encode('utf-8').decode('unicode_escape'))
                except:
                    parsed_values.append(value[2:-1])
        elif value.startswith("'") and value.endswith("'"):
            inner_value = value[1:-1]
            inner_value = inner_value.replace("''", "'")
            parsed_values.append(inner_value)
        elif re.match(r'\d{4}-\d{2}-\d{2}(\s+\d{2}:\d{2}:\d{2})?', value):
            parsed_values.append(value)
        else:
            try:
                if '.' in value or 'e' in value.lower():
                    parsed_values.append(float(value.replace(',', '')))
                else:
                    int_val = int(value.replace(',', ''))
                    if int_val > 9223372036854775807 or int_val < -9223372036854775808:
                        parsed_values.append(str(int_val))
                    else:
                        parsed_values.append(int_val)
            except (ValueError, TypeError):
                parsed_values.append(value)
    
    logging.debug(f"Parsed values: {parsed_values}")
    return parsed_values

def extract_insert_statements(sql_content, table_name):
    """Extract all INSERT statements for a given table with better multi-line support"""
    sql_content = sql_content.replace('\r\n', '\n').replace('\r', '\n')
    pattern = rf"INSERT (?:IGNORE )?INTO `{table_name}`.*?VALUES\s*(.*?);(?=\s*INSERT|$)"
    matches = re.findall(pattern, sql_content, re.DOTALL | re.IGNORECASE)
    return matches

def read_sql_file(sql_file, table_name):
    """Improved function to read and parse SQL file for a given table"""
    with open(sql_file, 'r', encoding='utf-8') as file:
        sql_content = file.read()
    
    logging.debug(f"Read SQL content from {sql_file} for table {table_name}")
    
    sql_content = clean_sql_content(sql_content, table_name)
    insert_statements = extract_insert_statements(sql_content, table_name)
    
    all_values = []
    for statement in tqdm(insert_statements, desc=f"Parsing SQL file for table {table_name}"):
        values_pattern = r'\(([^()]*(?:\([^)]*\)[^()]*)*)\)'
        value_sets = re.findall(values_pattern, statement, re.DOTALL)
        
        for value_set in value_sets:
            value_set = f"({value_set.strip()})"
            try:
                values = parse_values(value_set)
                logging.debug(f"Parsed values: {values}")
                all_values.append(values)
            except Exception as e:
                logging.error(f"Failed to parse value set: {value_set[:100]}... Error: {e}")
    
    return all_values

def validate_and_normalize_record(values, num_columns, table_name):
    """Validate and normalize a record before insertion"""
    for i, value in enumerate(values):
        if isinstance(value, int):
            if value > 9223372036854775807 or value < -9223372036854775808:
                values[i] = str(value)
    
    if len(values) > num_columns:
        return values[:num_columns], "truncated"
    
    if len(values) < num_columns:
        expanded_values = list(values)
        expanded_values.extend([None] * (num_columns - len(expanded_values)))
        
        if table_name == 'base' and num_columns > 1:
            expanded_values[0] = None
        
        return expanded_values, "expanded"
    
    return values, "normal"

def execute_inserts(db_file, table_name, values_list, num_columns, error_file):
    """Enhanced function to insert data with better error handling and recovery"""
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=10000")
    cursor.execute("PRAGMA temp_store=MEMORY")
    cursor.execute("PRAGMA foreign_keys=OFF")
    
    processed_records = 0
    error_records = 0
    fixed_records = 0
    batch_size = 5000
    
    failed_records = []
    
    with open(error_file, 'w', encoding='utf-8') as err_file:
        batch = []
        record_statuses = []
        
        for values in tqdm(values_list, desc=f"Inserting data into {table_name}"):
            logging.debug(f"Processing record with {len(values)} values")
            
            normalized_values, status = validate_and_normalize_record(values, num_columns, table_name)
            
            if status != "normal":
                if status == "expanded":
                    fixed_records += 1
                    err_file.write(f"Fixed in {table_name}: Expanded from {len(values)} to {num_columns} columns\n")
                    err_file.write(f"Original: {', '.join([str(v) if v is not None else 'NULL' for v in values])}\n\n")
                else:
                    err_file.write(f"Fixed in {table_name}: Truncated from {len(values)} to {num_columns} columns\n")
                    err_file.write(f"Original: {', '.join([str(v) if v is not None else 'NULL' for v in values[:100]])}\n\n")
                    fixed_records += 1
            
            batch.append(normalized_values)
            record_statuses.append(status)
            
            if len(batch) >= batch_size:
                placeholders = ', '.join(['?'] * num_columns)
                sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
                
                try:
                    cursor.executemany(sql, batch)
                    conn.commit()
                    
                    for status in record_statuses:
                        if status == "normal":
                            processed_records += 1
                        else:
                            fixed_records += 1
                    
                    batch = []
                    record_statuses = []
                except sqlite3.Error as e:
                    err_file.write(f"Batch insert error: {str(e)}\n")
                    for i, record in enumerate(batch):
                        try:
                            cursor.execute(sql, record)
                            if record_statuses[i] == "normal":
                                processed_records += 1
                            else:
                                fixed_records += 1
                        except sqlite3.Error as inner_e:
                            error_msg = f"Individual insert error: {str(inner_e)}"
                            record_str = f"Values: {', '.join([str(v) if v is not None else 'NULL' for v in record])}"
                            err_file.write(f"{error_msg}\n{record_str}\n\n")
                            
                            failed_records.append({
                                'error': str(inner_e),
                                'record': record,
                                'index': error_records
                            })
                            
                            error_records += 1
                    
                    conn.commit()
                    batch = []
                    record_statuses = []
        
        if batch:
            placeholders = ', '.join(['?'] * num_columns)
            sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
            
            try:
                cursor.executemany(sql, batch)
                conn.commit()
                
                for status in record_statuses:
                    if status == "normal":
                        processed_records += 1
                    else:
                        fixed_records += 1
            except sqlite3.Error as e:
                err_file.write(f"Final batch insert error: {str(e)}\n")
                for i, record in enumerate(batch):
                    try:
                        cursor.execute(sql, record)
                        if record_statuses[i] == "normal":
                            processed_records += 1
                        else:
                            fixed_records += 1
                    except sqlite3.Error as inner_e:
                        error_msg = f"Individual insert error: {str(inner_e)}"
                        record_str = f"Values: {', '.join([str(v) if v is not None else 'NULL' for v in record])}"
                        err_file.write(f"{error_msg}\n{record_str}\n\n")
                        
                        failed_records.append({
                            'error': str(inner_e),
                            'record': record,
                            'index': error_records
                        })
                        
                        error_records += 1
                
                conn.commit()
    
    conn.close()
    
    with open(error_file, 'a', encoding='utf-8') as err_file:
        if failed_records:
            err_file.write(f"\n\n{'='*50}\nFAILED RECORDS ({len(failed_records)})\n{'='*50}\n")
            for i, failed in enumerate(failed_records):
                err_file.write(f"Failed Record #{i+1}:\n")
                err_file.write(f"Error: {failed['error']}\n")
                err_file.write(f"Values: {', '.join([str(v) if v is not None else 'NULL' for v in failed['record']])}\n\n")
    
        err_file.write(f"\n\n{'='*50}\nSUMMARY\n{'='*50}\n")
        err_file.write(f"Total records processed: {processed_records + fixed_records}\n")
        err_file.write(f"Total normal records: {processed_records}\n")
        err_file.write(f"Total fixed records: {fixed_records}\n")
        err_file.write(f"Total failed records: {error_records}\n")
    
    logging.debug(f"Finished inserting data into {table_name}")
    return processed_records, fixed_records, error_records

def fix_incomplete_line(line, num_columns, table_name):
    """Enhanced function to fix incomplete lines with more intelligent inference"""
    if line.startswith("(((") and ")" in line:
        opening_count = 0
        for char in line:
            if char == '(':
                opening_count += 1
            else:
                break
        
        if opening_count > 1:
            line = "(" + line[opening_count:]
    
    if not line.startswith("(") and ("'" in line or "," in line):
        field_count = 1
        in_quotes = False
        escape = False
        
        for char in line:
            if char == "'" and not escape:
                in_quotes = not in_quotes
            elif char == "\\" and in_quotes:
                escape = True
            elif char == "," and not in_quotes:
                field_count += 1
            else:
                if escape:
                    escape = False
        
        if not line.startswith("("):
            line = "(" + line
        
        if not line.endswith(")"):
            line = line + ")"
        
        missing_fields = num_columns - field_count
        if missing_fields > 0:
            if table_name == 'base' and missing_fields > 0:
                parts = line.split(",", 1)
                if len(parts) > 1:
                    line = "(NULL," + parts[1]
                    missing_fields -= 1
            
            if missing_fields > 0:
                prefix = "NULL," * missing_fields
                line = "(" + prefix + line[1:]
    
    return line

def process_error_log(db_file, error_log_file, table_name, num_columns):
    """Enhanced function to process error logs with better recovery strategies"""
    print(f"\nProcessing error log entries for {table_name}...")
    
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    if not os.path.exists(error_log_file) or os.path.getsize(error_log_file) == 0:
        print(f"No error log file found or it's empty: {error_log_file}")
        return 0
    
    with open(error_log_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    pattern = re.compile(r"Error in " + re.escape(table_name) + r": Invalid column count:.*?\nLine: (.*?)(?=\n\n|$)", re.DOTALL)
    error_lines = pattern.findall(content)
    
    quote_pattern = re.compile(r"Error in " + re.escape(table_name) + r".*?'.*?'.*?\nLine: (.*?)(?=\n\n|$)", re.DOTALL)
    quote_error_lines = quote_pattern.findall(content)
    
    failed_pattern = re.compile(r"Failed Record #\d+:\nError:.*?\nValues: (.*?)(?=\n\nFailed Record|$)", re.DOTALL)
    failed_error_lines = failed_pattern.findall(content)
    
    error_lines.extend(quote_error_lines)
    error_lines.extend(failed_error_lines)
    
    error_lines = list(set(error_lines))
    
    fixed_count = 0
    failed_lines = []
    
    for error_line in error_lines:
        try:
            error_line = error_line.strip()
            
            fixed_line = fix_incomplete_line(error_line, num_columns, table_name)
            
            try:
                values = parse_values(fixed_line)
            except Exception as e:
                logging.error(f"Parse error: {e} for line: {fixed_line[:100]}...")
                if "'" in fixed_line:
                    fixed_line = fixed_line.replace("''", "'").replace("\\'", "'")
                    values = parse_values(fixed_line)
                else:
                    raise e
            
            for i, val in enumerate(values):
                if isinstance(val, int) and (val > 2147483647 or val < -2147483648):
                    values[i] = str(val)
            
            normalized_values, _ = validate_and_normalize_record(values, num_columns, table_name)
            
            placeholders = ', '.join(['?' for _ in range(num_columns)])
            cursor.execute(f"INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})", normalized_values)
            fixed_count += 1
            
        except Exception as e:
            logging.error(f"Error processing line from error log: {str(e)}\nLine: {error_line[:100]}...")
            failed_lines.append(error_line)
    
    for line in failed_lines:
        try:
            values = [None] * num_columns
            
            fields = []
            current = ""
            in_quotes = False
            
            for char in line:
                if char == "'" and (len(current) == 0 or current[-1] != '\\'):
                    in_quotes = not in_quotes
                    current += char
                elif char == ',' and not in_quotes:
                    fields.append(current)
                    current = ""
                else:
                    current += char
            
            if current:
                fields.append(current)
            
            for i, field in enumerate(fields):
                if i >= num_columns:
                    break
                
                field = field.strip()
                if field == 'NULL':
                    values[i] = None
                elif field.startswith("'") and field.endswith("'"):
                    values[i] = field[1:-1].replace("''", "'")
                elif field.isdigit():
                    if len(field) > 9:
                        values[i] = field
                    else:
                        values[i] = int(field)
            
            if table_name == 'base' and num_columns > 1:
                values[0] = None
            
            placeholders = ', '.join(['?' for _ in range(num_columns)])
            cursor.execute(f"INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})", values)
            fixed_count += 1
            
        except Exception as e:
            logging.error(f"Final attempt failed for line: {str(e)}\nLine: {line[:100]}...")
    
    conn.commit()
    conn.close()
    
    print(f"Fixed {fixed_count} entries from the error log for {table_name}")
    return fixed_count

def create_tables(db_file):
    """Create database tables if they don't exist"""
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
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
        id INTEGER PRIMARY KEY AUTOINCREMENT,
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
        WiFiKey TEXT NOT NULL,
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
    
    conn.commit()
    conn.close()

def create_indices(db_file, option):
    """Create indices for better query performance"""
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    if option == 1:
        print("Creating full indices (this may take some time)...")
        # Geo indices
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        
        # Base indices
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID COLLATE NOCASE);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_wpspin ON base(WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_wifikey ON base(WiFiKey COLLATE NOCASE);")
        
    elif option == 2:
        print("Creating basic indices...")
        # Geo indices
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        
        # Base indices
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID COLLATE NOCASE);")
        
    elif option == 3:
        print("Skipping index creation as per user choice.")
    else:
        print("Invalid option selected for index creation.")
    
    conn.commit()
    conn.close()

def optimize_and_vacuum(db_file):
    """Optimize the database and vacuum it to reduce size with enhanced retry logic"""
    print("\nOptimizing and vacuuming database...")
    max_attempts = 5
    attempt = 0
    
    while attempt < max_attempts:
        try:
            if attempt > 0:
                wait_time = 3 * (attempt + 1)
                print(f"Waiting {wait_time} seconds before next attempt...")
                time.sleep(wait_time)
            
            with sqlite3.connect(db_file, isolation_level=None, timeout=60) as conn:
                cursor = conn.cursor()
                
                print("Running ANALYZE...")
                cursor.execute("ANALYZE")
                conn.commit()
                
                print("Running PRAGMA optimize...")
                cursor.execute("PRAGMA optimize")
                conn.commit()
                
                print("Running VACUUM (this may take some time)...")
                cursor.execute("VACUUM")
                conn.commit()
                
                cursor.close()
                return True
                
        except sqlite3.OperationalError as e:
            attempt += 1
            print(f"Optimization attempt {attempt}/{max_attempts} failed: {str(e)}")
            
            if attempt < max_attempts:
                try:
                    with sqlite3.connect(db_file, timeout=10) as temp_conn:
                        temp_conn.execute("PRAGMA wal_checkpoint(FULL);")
                except Exception as checkpoint_error:
                    print(f"Checkpoint failed: {str(checkpoint_error)}")
                
                time.sleep(2)
            else:
                print("\nCould not optimize database after multiple attempts.")
                print("The database is still usable, but not optimized.")
                return False

def get_file_size_gb(file_path):
    """Get file size in GB"""
    if os.path.exists(file_path):
        size_bytes = os.path.getsize(file_path)
        return size_bytes / (1024 ** 3)
    return 0

def create_zip_python(db_file, base_name, compression_level, split_archive):
    """Create ZIP archive using Python's zipfile with progress bar"""
    
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
    """Create split ZIP archive using Python with progress tracking"""
    
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
    """Split large file into specified number of parts"""
    
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
    """Show compression statistics"""
    original_size = get_file_size_gb(original_file)
    compressed_size = get_file_size_gb(compressed_file)
    compression_ratio = (1 - compressed_size / original_size) * 100 if original_size > 0 else 0
    
    print(f"Archive created successfully: {compressed_file}")
    print(f"Original size: {original_size:.2f} GB")
    print(f"Compressed size: {compressed_size:.2f} GB")
    print(f"Compression ratio: {compression_ratio:.1f}%")
    
    return True

def create_archive_python(db_file, archive_options):
    """Create archive using Python built-in libraries"""
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

def create_archive_7z(db_file, archive_options):
    """Create archive using 7z with progress monitoring"""
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
    """Split ZIP archive manually into specified number of parts"""
    try:
        base_name = os.path.splitext(archive_name)[0]
        return split_large_file(archive_name, base_name, ".zip", num_parts)
    except Exception as e:
        print(f"Error splitting archive: {str(e)}")
        return False

def create_archive(db_file, archive_options):
    """Create archive based on options and available tools"""
    available_tools = check_archiver_availability()
    
    if available_tools.get('7z') and archive_options['format'] == '7z':
        return create_archive_7z(db_file, archive_options)
    
    return create_archive_python(db_file, archive_options)

def show_indexing_menu():
    """Show indexing options menu"""
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                   Anti3WiFi & 3WiFi Database Converter                            ║
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
    """Show archive options menu"""
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
    """Show split archive menu"""
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
    """Show number of parts menu"""
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
    """Show archive format menu"""
    available_tools = check_archiver_availability()
    
    if available_tools.get('7z'):
        menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                  Archive Format Options                                           ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════╣
║  1. 7Z format (requires 7-Zip)                                                                    ║
║  2. ZIP format                                                                                    ║
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
║  Note: 7-Zip not found. Install 7-Zip for 7Z format with better compression.                      ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝

Choose an option (1-2): """
    
    return input(menu)

def show_compression_menu():
    """Show compression level menu"""
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
    """Get all user preferences through menu navigation"""
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
        print("For better compression ratios, consider installing 7-Zip.")
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
    """Display summary of user choices"""
    print("\n" + "="*80)
    print("CONVERSION SUMMARY - ANTI3WIFI")
    print("="*80)
    
    indexing_types = {
        1: 'Full indexing (includes WiFiKey & WPSPIN)',
        2: 'Basic indexing (BSSID & ESSID only)',
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
    print("║                             Anti3WiFi & 3WiFi Database Converter v2                               ║")
    print("║                               SQL to SQLite for WIFI Frankenstein                                 ║")
    print("╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝")

    while True:
        preferences = get_user_preferences()
        
        if preferences is None:
            print("Exiting...")
            break
        
        if not display_summary(preferences):
            continue
        
        start_time = time.time()
        db_file = 'anti3wifi.db'
        geo_sql_file = 'geo.sql'
        base_sql_file = 'base.sql'
        error_file_geo = 'errors_geo.txt'
        error_file_base = 'errors_base.txt'

        missing_files = []
        if not os.path.exists(geo_sql_file):
            missing_files.append(geo_sql_file)
        if not os.path.exists(base_sql_file):
            missing_files.append(base_sql_file)
        
        if missing_files:
            print(f"\nError: Missing required files: {', '.join(missing_files)}")
            print("Please make sure geo.sql and base.sql files are in the same directory as this script.")
            continue

        print(f"\nStarting conversion process...")
        print(f"Source files: {geo_sql_file}, {base_sql_file}")
        print(f"Target database: {db_file}")

        print("Creating database tables...")
        create_tables(db_file)

        print("\nProcessing 'geo' table...")
        geo_values = read_sql_file(geo_sql_file, 'geo')
        records_geo, fixed_geo, errors_geo = execute_inserts(db_file, 'geo', geo_values, 4, error_file_geo)
        
        print("\nProcessing 'base' table...")
        base_values = read_sql_file(base_sql_file, 'base')
        records_base, fixed_base, errors_base = execute_inserts(db_file, 'base', base_values, 23, error_file_base)

        if os.path.exists(error_file_geo) and os.path.getsize(error_file_geo) > 0:
            recovered_geo = process_error_log(db_file, error_file_geo, 'geo', 4)
            if recovered_geo > 0:
                print(f"Recovered {recovered_geo} additional records for 'geo' table")
                fixed_geo += recovered_geo
                errors_geo -= recovered_geo if errors_geo >= recovered_geo else errors_geo

        if os.path.exists(error_file_base) and os.path.getsize(error_file_base) > 0:
            recovered_base = process_error_log(db_file, error_file_base, 'base', 23)
            if recovered_base > 0:
                print(f"Recovered {recovered_base} additional records for 'base' table")
                fixed_base += recovered_base
                errors_base -= recovered_base if errors_base >= recovered_base else errors_base

        if errors_geo > 0:
            print(f"\nFailed records for 'geo' table: {errors_geo}")
            print(f"See details in {error_file_geo} in the FAILED RECORDS section")
        
        if errors_base > 0:
            print(f"\nFailed records for 'base' table: {errors_base}")
            print(f"See details in {error_file_base} in the FAILED RECORDS section")

        create_indices(db_file, preferences['indexing'])

        optimize_and_vacuum(db_file)
        
        end_time = time.time()
        duration = end_time - start_time
        
        print(f"\n" + "="*80)
        print("DATABASE CONVERSION COMPLETED - ANTI3WIFI")
        print("="*80)
        print(f"Total processing time: {timedelta(seconds=int(duration))}")
        print(f"Records processed:")
        print(f"  - Geo table: {records_geo:,} normal, {fixed_geo:,} fixed, {errors_geo:,} errors")
        print(f"  - Base table: {records_base:,} normal, {fixed_base:,} fixed, {errors_base:,} errors")
        print(f"Database file: {db_file}")
        print(f"Database size: {get_file_size_gb(db_file):.2f} GB")
        
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
        
        while True:
            another = input("\nWould you like to process another file? (y/n): ").lower().strip()
            if another in ['n', 'no']:
                print("Thank you for using Anti3WiFi Database Converter!")
                return
            elif another in ['y', 'yes']:
                break
            else:
                print("Please enter 'y' for yes or 'n' for no.")

if __name__ == "__main__":
    main()