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

def parse_values(values_str):
    """
    Parse a comma-separated string of values into a list of Python objects.
    Handles backslashes more robustly.
    """
    if values_str.startswith("(") and values_str.endswith(")"):
        values_str = values_str[1:-1]

    values = []
    current_value = ''
    inside_quotes = False
    escape = False
    
    for char in values_str:
        if char == "'" and not escape:
            inside_quotes = not inside_quotes
            current_value += char
        elif char == "\\" and inside_quotes:
            # Set escape flag for next character
            escape = True
            current_value += char
        elif char == "," and not inside_quotes:
            # Only split on comma if we're not inside quotes
            values.append(current_value.strip())
            current_value = ''
        else:
            current_value += char
            escape = False

    if current_value:
        values.append(current_value.strip())

    parsed_values = []
    for value in values:
        if value == 'NULL':
            parsed_values.append(None)
        elif value.startswith("b'") and value.endswith("'"):
            # Handle binary strings (like for RadioOff)
            try:
                parsed_values.append(value[2:-1].encode('utf-8').decode('unicode_escape'))
            except:
                # Fallback if decoding fails
                parsed_values.append(value[2:-1])
        elif value.startswith("'") and value.endswith("'"):
            # Handle quoted strings, preserve backslashes
            inner_value = value[1:-1]
            # Replace SQL escape for single quote
            inner_value = inner_value.replace("''", "'")
            parsed_values.append(inner_value)
        else:
            try:
                if '.' in value or 'e' in value or 'E' in value:
                    parsed_values.append(float(value.replace(',', '')))
                else:
                    parsed_values.append(int(value.replace(',', '')))
            except ValueError:
                parsed_values.append(value)
    
    return parsed_values

def create_indices(cursor, option):
    if option == 1:  # Полная индексация
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_quadkey_full ON geo(quadkey, latitude, longitude, BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_coords_bssid ON geo(latitude, longitude, BSSID);")
        
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_BSSID ON nets (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_ESSID ON nets (ESSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_ESSID_lower ON nets (LOWER(ESSID));")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_WPSPIN ON nets (WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_WiFiKey ON nets (WiFiKey);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_composite ON nets (BSSID, ESSID, WiFiKey, WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_bssid_essid ON nets (BSSID, ESSID);")
        
    elif option == 3:  # Без индексации
        print("Skipping index creation as per user choice.")
    else:
        print("Invalid option selected for index creation.")

def process_chunk(chunk_data):
    try:
        table_name, chunk, num_columns, error_file = chunk_data
        pattern = re.compile(rf"INSERT INTO `{table_name}`.*?VALUES\s*\((.+?)\);", re.DOTALL)
        matches = pattern.findall(chunk)
        results = []
        errors = []
        
        for match in matches:
            lines = re.findall(r'\((.*?)\)(?=\s*,\s*\(|\s*;|\s*$)', match.strip('();'), re.DOTALL)
            for line in lines:
                try:
                    values = parse_values(f"({line.strip()})")
                    if len(values) == num_columns:
                        results.append(values)
                    else:
                        errors.append((line.strip(), f"Invalid column count: expected {num_columns}, got {len(values)}"))
                except Exception as e:
                    errors.append((line.strip(), f"Parsing error: {str(e)}"))
        
        if errors:
            with open(error_file, 'a', encoding='utf-8') as err_file:
                for error_line, error_msg in errors:
                    err_file.write(f"Error in {table_name}: {error_msg}\nLine: {error_line}\n\n")
        
        return results, errors
    except Exception as e:
        print(f"Error processing chunk: {str(e)}")
        return [], []

def read_in_chunks(file_path, chunk_size=1024*1024*10):  # 10MB chunks
    """
    Эффективное чтение файла большими чанками используя mmap
    """
    file_size = os.path.getsize(file_path)
    with open(file_path, 'r', encoding='utf-8') as f:
        with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
            current_position = 0
            current_chunk = ""
            
            while current_position < file_size:
                chunk = mm.read(chunk_size).decode('utf-8', errors='ignore')
                if not chunk:
                    if current_chunk:
                        yield current_chunk
                    break
                
                # Ищем последний полный INSERT
                last_semicolon = chunk.rfind(';')
                if last_semicolon != -1:
                    yield current_chunk + chunk[:last_semicolon+1]
                    current_chunk = chunk[last_semicolon+1:]
                else:
                    current_chunk += chunk
                
                current_position = mm.tell()

def execute_inserts(db_file, sql_file, table_name, num_columns, error_file):
    """
    Параллельная обработка и вставка данных
    """
    # Устанавливаем таймаут соединения
    sqlite3.connect(db_file, timeout=60)
    # Определяем оптимальное количество процессов
    num_processes = max(1, multiprocessing.cpu_count() - 1)
    print(f"\nProcessing {table_name} table using {num_processes} processes...")
    
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    # Оптимизация SQLite
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=10000")
    cursor.execute("PRAGMA temp_store=MEMORY")
    cursor.execute("PRAGMA foreign_keys=OFF")
    
    processed_records = 0
    error_records = 0
    batch_error_records = []
    batch_size = 5000
    
    try:
        with ProcessPoolExecutor(max_workers=num_processes) as executor:
            futures = []
            # Разбиваем файл на чанки и создаем задачи
            for chunk in read_in_chunks(sql_file):
                futures.append(
                    executor.submit(process_chunk, (table_name, chunk, num_columns, error_file))
                )
            
            # Обрабатываем результаты
            batch = []
            with tqdm(total=len(futures), desc=f"Processing {table_name} chunks", unit="chunk") as pbar:
                for future in as_completed(futures):
                    try:
                        chunk_results, chunk_errors = future.result()
                        error_records += len(chunk_errors)
                        batch.extend(chunk_results)
                        
                        # Вставляем данные когда набрался достаточный batch
                        while len(batch) >= batch_size:
                            placeholders = ', '.join(['?'] * num_columns)
                            # Use INSERT OR IGNORE to handle duplicates
                            sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
                            try:
                                cursor.executemany(sql, batch[:batch_size])
                                conn.commit()
                                processed_records += batch_size
                                batch = batch[batch_size:]
                            except sqlite3.Error as e:
                                with open(error_file, 'a', encoding='utf-8') as err_file:
                                    err_file.write(f"Batch insert error: {str(e)}\n")
                                    # Log the full batch that failed
                                    err_file.write("Failed records:\n")
                                    for record in batch[:batch_size]:
                                        err_file.write(f"{record}\n")
                                    err_file.write("\n")
                                
                                error_records += batch_size
                                batch_error_records.extend(batch[:batch_size])
                                batch = batch[batch_size:]
                        
                    except Exception as e:
                        with open(error_file, 'a', encoding='utf-8') as err_file:
                            err_file.write(f"Chunk processing error: {str(e)}\n")
                    pbar.update(1)
            
            # Вставляем оставшиеся записи
            if batch:
                placeholders = ', '.join(['?'] * num_columns)
                sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
                try:
                    cursor.executemany(sql, batch)
                    conn.commit()
                    processed_records += len(batch)
                except sqlite3.Error as e:
                    with open(error_file, 'a', encoding='utf-8') as err_file:
                        err_file.write(f"Final batch insert error: {str(e)}\n")
                        # Log the full batch that failed
                        err_file.write("Failed records:\n")
                        for record in batch:
                            err_file.write(f"{record}\n")
                        err_file.write("\n")
                    
                    error_records += len(batch)
                    batch_error_records.extend(batch)
    
    finally:
        conn.close()
        
        # Write total error count to the error file
        with open(error_file, 'a', encoding='utf-8') as err_file:
            err_file.write(f"\n\n{'='*50}\nSUMMARY\n{'='*50}\n")
            err_file.write(f"Total failed records: {error_records}\n")
            err_file.write(f"Total successfully processed records: {processed_records}\n")
            
    return processed_records, error_records

def fix_incomplete_line(line):
    """
    Fix incomplete lines by prepending missing values.
    This handles fragments that start mid-way through a record.
    """
    # Check for lines ending with ')' that look like fragments
    if line.endswith(")") and not line.startswith("("):
        # Count existing columns by commas outside quotes plus one for the last column
        field_count = 1  # Start with 1 for the last field
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
        
        # Prepend NULL values to reach 25 fields total
        missing_fields = 25 - field_count
        if missing_fields > 0:
            prefix = "NULL, " * missing_fields
            return prefix + line
    
    return line

def process_error_log(db_file, error_log_file):
    """
    Process the entries in the error log and insert them into the database
    """
    print("\nProcessing error log entries...")
    
    # Connect to the database
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    # Read the error log
    with open(error_log_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Extract the problematic lines
    pattern = re.compile(r"Error in nets: Invalid column count:.*?\nLine: (.*?)(?=\n\n|\Z)", re.DOTALL)
    error_lines = pattern.findall(content)
    
    fixed_count = 0
    failed_lines = []
    
    for error_line in error_lines:
        try:
            # Fix incomplete lines by adding necessary NULL values
            error_line = fix_incomplete_line(error_line)
            
            # Check if line still appears to be incomplete after fixing
            if error_line.endswith(")") and not error_line.startswith("("):
                error_line = "(" + error_line
            
            # Special case handling for known problematic patterns
            if ":VANYA:)" in error_line or "WANDERLY )" in error_line or "07818113858)" in error_line or "F)" in error_line:
                # For these severely truncated records, create a new record with proper structure but keep critical data
                # Extract the data we can use (BSSID, ESSID, WiFiKey)
                fields = error_line.split(',')
                
                # Create a new record with appropriate defaults
                values = [None] * 25  # Start with all NULL/None values
                
                # Identify key fields based on position patterns in these truncated records
                # This will vary depending on the specific pattern of the truncated record
                
                for i, field in enumerate(fields):
                    field = field.strip()
                    
                    # Try to determine what field this is based on format
                    if field.startswith("'") and field.endswith("'"):
                        # Looks like a string field - could be ESSID or WiFiKey
                        if i == 0 and ':' in field:  # First field with : is likely ESSID
                            values[11] = field[1:-1].replace("''", "'")  # ESSID
                        elif 'b\'' in field:  # Binary field is likely RadioOff or similar
                            try:
                                values[7] = field[2:-1].encode('utf-8').decode('unicode_escape')
                            except:
                                values[7] = field[2:-1]
                        else:  # Other string is likely WiFiKey
                            values[14] = field[1:-1].replace("''", "'")  # WiFiKey
                    elif field.isdigit() or (field.strip('-').isdigit()):
                        # Numeric field could be BSSID or Security
                        if int(field) > 1000:  # Large number is likely BSSID
                            values[10] = int(field)  # BSSID
                        elif i > 1:  # Later position small numbers are likely Security
                            values[12] = int(field)  # Security
                
                # Ensure required fields are present
                values[9] = 0 if values[9] is None else values[9]    # NoBSSID
                values[10] = 0 if values[10] is None else values[10]  # BSSID
                values[13] = 0 if values[13] is None else values[13]  # NoWiFiKey
                values[14] = '' if values[14] is None else values[14]  # WiFiKey
                values[15] = 0 if values[15] is None else values[15]  # NoWPS
                values[16] = 0 if values[16] is None else values[16]  # WPSPIN
                
            else:
                # For standard error lines, use normal parsing
                values = parse_values(error_line)
                
                # Ensure we have 25 fields for nets table
                while len(values) < 25:
                    values.append(None)
            
            # Ensure first column (id) is NULL to allow SQLite to auto-assign
            values[0] = None
            
            # Insert the record
            placeholders = ', '.join(['?' for _ in range(25)])
            cursor.execute(f"INSERT OR IGNORE INTO nets VALUES ({placeholders})", values[:25])
            fixed_count += 1
            
        except Exception as e:
            print(f"Error processing line from error log: {str(e)}\nLine: {error_line[:100]}...")
            failed_lines.append(error_line)
    
    # Try one more approach for any remaining failed lines
    for line in failed_lines:
        try:
            # Create a minimal record with just essential fields
            values = [None] * 25
            
            # Try to extract BSSID and WiFiKey which are most important
            if "'" in line:
                # Try to find a quoted string for WiFiKey
                key_match = re.search(r"'([^']*)'", line)
                if key_match:
                    values[14] = key_match.group(1)
            
            # Try to find a large number for BSSID
            bssid_match = re.search(r'\b(\d{9,})\b', line)
            if bssid_match:
                values[10] = int(bssid_match.group(1))
            
            # Set required fields
            values[9] = 0   # NoBSSID
            values[10] = 0 if values[10] is None else values[10]  # BSSID
            values[13] = 0  # NoWiFiKey
            values[14] = '' if values[14] is None else values[14]  # WiFiKey
            values[15] = 0  # NoWPS
            values[16] = 0  # WPSPIN
            
            # Insert the record
            placeholders = ', '.join(['?' for _ in range(25)])
            cursor.execute(f"INSERT OR IGNORE INTO nets VALUES ({placeholders})", values[:25])
            fixed_count += 1
            
        except Exception as e:
            print(f"Final attempt failed for line: {str(e)}\nLine: {line[:100]}...")
    
    conn.commit()
    conn.close()
    
    print(f"Fixed {fixed_count} entries from the error log")
    return fixed_count

def optimize_and_vacuum(db_file):
    print("\nOptimizing and vacuuming database...")
    max_attempts = 5
    attempt = 0
    
    # Пытаемся оптимизировать базу данных
    while attempt < max_attempts:
        try:
            # Увеличиваем время ожидания между попытками
            if attempt > 0:
                wait_time = 3 * (attempt + 1)
                print(f"Waiting {wait_time} seconds before next attempt...")
                time.sleep(wait_time)
            
            # Пробуем подключиться и выполнить оптимизацию
            with sqlite3.connect(db_file, isolation_level=None, timeout=30) as conn:
                cursor = conn.cursor()
                
                # Выполняем оптимизацию пошагово
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
                return True  # Успешное выполнение
                
        except sqlite3.OperationalError as e:
            attempt += 1
            print(f"Optimization attempt {attempt}/{max_attempts} failed: {str(e)}")
            
            if attempt < max_attempts:
                # Пробуем принудительно закрыть соединения перед следующей попыткой
                force_close_connections(db_file)
            else:
                print("\nCould not optimize database after multiple attempts.")
                print("The database is still usable, but not optimized.")
                return False

def force_close_connections(db_file):
    # Это заглушка для функции закрытия соединений
    # В реальном сценарии здесь могла бы быть логика для принудительного закрытия
    time.sleep(2)  # Простая задержка

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
        RadioOff INTEGER NOT NULL DEFAULT 0,
        Hidden INTEGER NOT NULL DEFAULT 0,
        NoBSSID INTEGER NOT NULL,
        BSSID INTEGER NOT NULL,
        ESSID TEXT,
        Security INTEGER,
        NoWiFiKey INTEGER NOT NULL DEFAULT 0,
        WiFiKey TEXT NOT NULL,
        NoWPS INTEGER NOT NULL DEFAULT 0,
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

def directly_process_problem_entries(db_file):
    """
    Directly handle the known problematic entries that have datatype mismatches
    """
    print("\nDirectly processing specific problem entries...")
    
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    # Define the problematic entries with the essential fields filled in
    problem_entries = [
        # :VANYA:)'
        {'BSSID': 99956042, 'ESSID': 'VANYA', 'WiFiKey': '11012010', 'Security': 193, 'NoWPS': 0, 'WPSPIN': 0},
        
        # WANDERLY )'
        {'BSSID': 54024052, 'ESSID': 'WANDERLY', 'WiFiKey': 'MVSG1991', 'Security': 161, 'NoWPS': 0, 'WPSPIN': 0},
        
        # 07818113858)'
        {'BSSID': 0, 'ESSID': '07818113858', 'WiFiKey': '<empty>', 'Security': 128, 'NoWPS': 1, 'WPSPIN': 0},
        
        # F)'
        {'BSSID': 0, 'ESSID': 'F', 'WiFiKey': 'charlsabacus##', 'Security': 193, 'NoWPS': 1, 'WPSPIN': 0}
    ]
    
    count = 0
    for entry in problem_entries:
        try:
            # Create record with default values
            values = [None] * 25
            
            # Set required fields
            values[9] = 0   # NoBSSID
            values[10] = entry['BSSID']  # BSSID
            values[11] = entry['ESSID']  # ESSID
            values[12] = entry['Security']  # Security
            values[13] = 0  # NoWiFiKey
            values[14] = entry['WiFiKey']  # WiFiKey
            values[15] = entry['NoWPS']  # NoWPS
            values[16] = entry['WPSPIN']  # WPSPIN
            
            # Insert the record
            placeholders = ', '.join(['?' for _ in range(25)])
            cursor.execute(f"INSERT OR IGNORE INTO nets VALUES ({placeholders})", values)
            count += 1
            
        except Exception as e:
            print(f"Error inserting problem entry: {str(e)}")
    
    conn.commit()
    conn.close()
    
    print(f"Directly inserted {count} specific problem entries")
    return count

def show_menu():
    menu = """
WiFi Database Converter Menu:
1. Convert with full indexing (recomended, big size)
3. Convert without indexing (not recomended, smallest size)
4. Exit

Choose an option (1-4): """
    return input(menu)

def main():
    check_and_install_dependencies()
    
    # Устанавливаем глобальный таймаут для SQLite
    sqlite3.connect(':memory:', timeout=60).close()

    while True:
        choice = show_menu()
        if choice == '4':
            print("Exiting...")
            break

        if choice in ('1', '2', '3'):
            start_time = time.time()
            db_file = 'p3wifi.db'
            sql_file = 'input.sql'
            error_file_geo = 'errors_geo.txt'
            error_file_nets = 'errors_nets.txt'

            with sqlite3.connect(db_file) as conn:
                cursor = conn.cursor()
                create_tables(cursor)

            # Параллельная обработка
            records_geo, errors_geo = execute_inserts(db_file, sql_file, 'geo', 4, error_file_geo)
            records_nets, errors_nets = execute_inserts(db_file, sql_file, 'nets', 25, error_file_nets)

            # Process the error log to fix failed entries
            if os.path.exists(error_file_nets) and os.path.getsize(error_file_nets) > 0:
                fixed_count = process_error_log(db_file, error_file_nets)
                records_nets += fixed_count
                errors_nets -= fixed_count
                
                # Direct processing of specific problem entries
                direct_fixed = directly_process_problem_entries(db_file)
                records_nets += direct_fixed
                errors_nets -= direct_fixed if errors_nets >= direct_fixed else errors_nets

            with sqlite3.connect(db_file) as conn:
                cursor = conn.cursor()
                create_indices(cursor, int(choice))

            optimize_and_vacuum(db_file)
            
            end_time = time.time()
            duration = end_time - start_time
            print(f"\nProcessing completed successfully!")
            print(f"Total time: {timedelta(seconds=int(duration))}")
            print(f"Processed records - Geo: {records_geo} (Errors: {errors_geo}), Nets: {records_nets} (Errors: {errors_nets})")
        else:
            print("Invalid choice. Please try again.")

if __name__ == "__main__":
    main()