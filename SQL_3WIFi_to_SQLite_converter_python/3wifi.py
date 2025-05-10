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

def clean_sql_content(sql_content, table_name):
    """
    Pre-process the SQL content to fix common issues before parsing
    """
    # Fix incomplete or malformed INSERT statements
    fixed_content = sql_content
    
    # Replace problematic character sequences
    fixed_content = fixed_content.replace("\\'", "''")  # Replace escaped single quotes
    
    # Fix syntax issues with INSERT statements
    pattern = rf"INSERT (?:IGNORE )?INTO `{table_name}`\s*\([^)]+\)\s*VALUES\s*"
    matches = re.findall(pattern, fixed_content)
    
    for match in matches:
        # Ensure VALUES is followed by proper syntax
        if not re.search(r"VALUES\s*\(", match):
            fixed_content = fixed_content.replace(match, match + "(")
    
    return fixed_content

def parse_values(values_str):
    """
    Enhanced parser for SQL values with better handling of complex cases
    """
    logging.debug(f"Parsing values string: {values_str}")
    
    # Handle excessive parentheses (common error pattern)
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
    
    # Use CSV parsing for more robust handling of quoted values
    csv_reader = csv.reader([values_str], delimiter=',', quotechar="'", escapechar='\\', doublequote=True)
    try:
        raw_values = next(csv_reader)
    except Exception as e:
        logging.error(f"CSV parsing failed: {e}. Falling back to manual parsing")
        # Fall back to manual parsing
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
                    # Fallback if decoding fails
                    parsed_values.append(value[2:-1])
        elif value.startswith("'") and value.endswith("'"):
            # Handle quoted strings with improved robustness
            inner_value = value[1:-1]
            # Replace SQL escape for single quote
            inner_value = inner_value.replace("''", "'")
            parsed_values.append(inner_value)
        elif re.match(r'\d{4}-\d{2}-\d{2}(\s+\d{2}:\d{2}:\d{2})?', value):  # Date-time string
            parsed_values.append(value)
        else:
            try:
                if '.' in value or 'e' in value.lower():
                    parsed_values.append(float(value.replace(',', '')))
                else:
                    # Check if number is too large for SQLite INTEGER
                    int_val = int(value.replace(',', ''))
                    if int_val > 9223372036854775807 or int_val < -9223372036854775808:
                        # Store as string to avoid integer overflow
                        parsed_values.append(str(int_val))
                    else:
                        parsed_values.append(int_val)
            except (ValueError, TypeError):
                parsed_values.append(value)
    
    logging.debug(f"Parsed values: {parsed_values}")
    return parsed_values

def extract_insert_statements(sql_content, table_name):
    """
    Extract all INSERT statements for a given table with better multi-line support
    """
    # First normalize line endings
    sql_content = sql_content.replace('\r\n', '\n').replace('\r', '\n')
    
    # Pattern to match INSERT statements, allowing for multi-line
    pattern = rf"INSERT (?:IGNORE )?INTO `{table_name}`.*?VALUES\s*(.*?);(?=\s*INSERT|$)"
    matches = re.findall(pattern, sql_content, re.DOTALL | re.IGNORECASE)
    
    return matches

def read_sql_file(sql_file, table_name):
    """
    Improved function to read and parse SQL file for a given table
    """
    with open(sql_file, 'r', encoding='utf-8') as file:
        sql_content = file.read()
    
    logging.debug(f"Read SQL content from {sql_file} for table {table_name}")
    
    # Clean the SQL content
    sql_content = clean_sql_content(sql_content, table_name)
    
    # Extract all INSERT statements for the table
    insert_statements = extract_insert_statements(sql_content, table_name)
    
    all_values = []
    for statement in tqdm(insert_statements, desc=f"Parsing SQL file for table {table_name}"):
        # Extract value sets from the statement
        values_pattern = r'\(([^()]*(?:\([^)]*\)[^()]*)*)\)'
        value_sets = re.findall(values_pattern, statement, re.DOTALL)
        
        for value_set in value_sets:
            # Ensure proper format for parsing
            value_set = f"({value_set.strip()})"
            try:
                values = parse_values(value_set)
                logging.debug(f"Parsed values: {values}")
                all_values.append(values)
            except Exception as e:
                logging.error(f"Failed to parse value set: {value_set[:100]}... Error: {e}")
    
    return all_values

def validate_and_normalize_record(values, num_columns, table_name):
    """
    Validate and normalize a record before insertion
    """
    # Handle SQLite integer limits (SQLite uses 64-bit signed integers)
    for i, value in enumerate(values):
        if isinstance(value, int):
            # Check if value exceeds SQLite integer limits
            if value > 9223372036854775807 or value < -9223372036854775808:
                # Convert to string to prevent overflow
                values[i] = str(value)
    
    if len(values) > num_columns:
        # Truncate if too many columns
        return values[:num_columns], "truncated"
    
    if len(values) < num_columns:
        # Try to fill in missing values with NULLs
        expanded_values = list(values)
        expanded_values.extend([None] * (num_columns - len(expanded_values)))
        
        # Special handling for base table
        if table_name == 'base' and num_columns > 1:
            # Ensure first column (id) is NULL for auto-increment
            expanded_values[0] = None
        
        return expanded_values, "expanded"
    
    # Record is already the correct length
    return values, "normal"

def execute_inserts(db_file, table_name, values_list, num_columns, error_file):
    """
    Enhanced function to insert data with better error handling and recovery
    """
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    # SQLite optimization
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=10000")
    cursor.execute("PRAGMA temp_store=MEMORY")
    cursor.execute("PRAGMA foreign_keys=OFF")
    
    processed_records = 0
    error_records = 0
    fixed_records = 0
    batch_size = 5000
    
    # Track failed records with details
    failed_records = []
    
    with open(error_file, 'w', encoding='utf-8') as err_file:
        batch = []
        record_statuses = []  # Track status of records for reporting
        
        for values in tqdm(values_list, desc=f"Inserting data into {table_name}"):
            logging.debug(f"Processing record with {len(values)} values")
            
            # Validate and normalize the record
            normalized_values, status = validate_and_normalize_record(values, num_columns, table_name)
            
            if status != "normal":
                if status == "expanded":
                    fixed_records += 1
                    err_file.write(f"Fixed in {table_name}: Expanded from {len(values)} to {num_columns} columns\n")
                    err_file.write(f"Original: {', '.join([str(v) if v is not None else 'NULL' for v in values])}\n\n")
                else:  # truncated
                    err_file.write(f"Fixed in {table_name}: Truncated from {len(values)} to {num_columns} columns\n")
                    err_file.write(f"Original: {', '.join([str(v) if v is not None else 'NULL' for v in values[:100]])}\n\n")
                    fixed_records += 1
            
            batch.append(normalized_values)
            record_statuses.append(status)
            
            # Insert in batches for better performance
            if len(batch) >= batch_size:
                placeholders = ', '.join(['?'] * num_columns)
                sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
                
                try:
                    cursor.executemany(sql, batch)
                    conn.commit()
                    
                    # Count successful records by status
                    for status in record_statuses:
                        if status == "normal":
                            processed_records += 1
                        else:
                            fixed_records += 1
                    
                    batch = []
                    record_statuses = []
                except sqlite3.Error as e:
                    err_file.write(f"Batch insert error: {str(e)}\n")
                    # Try to insert records individually to identify problematic ones
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
                            
                            # Save detailed information about the failed record
                            failed_records.append({
                                'error': str(inner_e),
                                'record': record,
                                'index': error_records
                            })
                            
                            error_records += 1
                    
                    conn.commit()
                    batch = []
                    record_statuses = []
        
        # Insert any remaining records
        if batch:
            placeholders = ', '.join(['?'] * num_columns)
            sql = f'INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})'
            
            try:
                cursor.executemany(sql, batch)
                conn.commit()
                
                # Count successful records by status
                for status in record_statuses:
                    if status == "normal":
                        processed_records += 1
                    else:
                        fixed_records += 1
            except sqlite3.Error as e:
                err_file.write(f"Final batch insert error: {str(e)}\n")
                # Try to insert records individually
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
                        
                        # Save detailed information about the failed record
                        failed_records.append({
                            'error': str(inner_e),
                            'record': record,
                            'index': error_records
                        })
                        
                        error_records += 1
                
                conn.commit()
    
    conn.close()
    
    # Write failed records to error file in a dedicated section
    with open(error_file, 'a', encoding='utf-8') as err_file:
        if failed_records:
            err_file.write(f"\n\n{'='*50}\nFAILED RECORDS ({len(failed_records)})\n{'='*50}\n")
            for i, failed in enumerate(failed_records):
                err_file.write(f"Failed Record #{i+1}:\n")
                err_file.write(f"Error: {failed['error']}\n")
                err_file.write(f"Values: {', '.join([str(v) if v is not None else 'NULL' for v in failed['record']])}\n\n")
    
        # Write summary to error file
        err_file.write(f"\n\n{'='*50}\nSUMMARY\n{'='*50}\n")
        err_file.write(f"Total records processed: {processed_records + fixed_records}\n")
        err_file.write(f"Total normal records: {processed_records}\n")
        err_file.write(f"Total fixed records: {fixed_records}\n")
        err_file.write(f"Total failed records: {error_records}\n")
    
    logging.debug(f"Finished inserting data into {table_name}")
    return processed_records, fixed_records, error_records

def fix_incomplete_line(line, num_columns, table_name):
    """
    Enhanced function to fix incomplete lines with more intelligent inference
    """
    # Fix excessive parentheses at the beginning (special case from error logs)
    if line.startswith("(((") and ")" in line:
        # Count opening parentheses at the beginning
        opening_count = 0
        for char in line:
            if char == '(':
                opening_count += 1
            else:
                break
        
        # Keep only one opening parenthesis
        if opening_count > 1:
            line = "(" + line[opening_count:]
    
    # Check if it's a fragment with missing opening parenthesis
    if not line.startswith("(") and ("'" in line or "," in line):
        # Count how many fields we have
        field_count = 1  # Start with 1 for the first field
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
        
        # Add missing opening parenthesis
        if not line.startswith("("):
            line = "(" + line
        
        # Add closing parenthesis if missing
        if not line.endswith(")"):
            line = line + ")"
        
        # Prepare the line for parsing
        missing_fields = num_columns - field_count
        if missing_fields > 0:
            # Try to determine where the missing fields should go
            if table_name == 'base' and missing_fields > 0:
                # For base table, assume first column is id (auto-increment)
                parts = line.split(",", 1)
                if len(parts) > 1:
                    # Replace opening part with NULL for id
                    line = "(NULL," + parts[1]
                    missing_fields -= 1
            
            # Add NULL for remaining missing fields at the beginning
            if missing_fields > 0:
                prefix = "NULL," * missing_fields
                # Insert after opening parenthesis
                line = "(" + prefix + line[1:]
    
    return line

def process_error_log(db_file, error_log_file, table_name, num_columns):
    """
    Enhanced function to process error logs with better recovery strategies
    """
    print(f"\nProcessing error log entries for {table_name}...")
    
    # Connect to the database
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    # Read the error log
    if not os.path.exists(error_log_file) or os.path.getsize(error_log_file) == 0:
        print(f"No error log file found or it's empty: {error_log_file}")
        return 0
    
    with open(error_log_file, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Extract problematic lines
    pattern = re.compile(r"Error in " + re.escape(table_name) + r": Invalid column count:.*?\nLine: (.*?)(?=\n\n|$)", re.DOTALL)
    error_lines = pattern.findall(content)
    
    # Also look for problematic lines with quotes
    quote_pattern = re.compile(r"Error in " + re.escape(table_name) + r".*?'.*?'.*?\nLine: (.*?)(?=\n\n|$)", re.DOTALL)
    quote_error_lines = quote_pattern.findall(content)
    
    # Extract problematic lines from FAILED RECORDS section
    failed_pattern = re.compile(r"Failed Record #\d+:\nError:.*?\nValues: (.*?)(?=\n\nFailed Record|$)", re.DOTALL)
    failed_error_lines = failed_pattern.findall(content)
    
    # Combine all problematic lines
    error_lines.extend(quote_error_lines)
    error_lines.extend(failed_error_lines)
    
    # Remove duplicates
    error_lines = list(set(error_lines))
    
    fixed_count = 0
    failed_lines = []
    
    for error_line in error_lines:
        try:
            # Clean up the line
            error_line = error_line.strip()
            
            # Fix incomplete lines
            fixed_line = fix_incomplete_line(error_line, num_columns, table_name)
            
            # Parse values
            try:
                values = parse_values(fixed_line)
            except Exception as e:
                logging.error(f"Parse error: {e} for line: {fixed_line[:100]}...")
                # Try a more aggressive fix
                if "'" in fixed_line:
                    # Fix potential quote issues
                    fixed_line = fixed_line.replace("''", "'").replace("\\'", "'")
                    values = parse_values(fixed_line)
                else:
                    raise e
            
            # Special handling for datatype mismatch errors - convert problematic values
            # Try to convert large integer values to appropriate types
            for i, val in enumerate(values):
                # Check if this is a large integer that might be causing datatype mismatch
                if isinstance(val, int) and (val > 2147483647 or val < -2147483648):
                    # Convert to text representation or floating point to avoid SQLite integer limits
                    values[i] = str(val)
            
            # Normalize the record
            normalized_values, _ = validate_and_normalize_record(values, num_columns, table_name)
            
            # Insert the record
            placeholders = ', '.join(['?' for _ in range(num_columns)])
            cursor.execute(f"INSERT OR IGNORE INTO {table_name} VALUES ({placeholders})", normalized_values)
            fixed_count += 1
            
        except Exception as e:
            logging.error(f"Error processing line from error log: {str(e)}\nLine: {error_line[:100]}...")
            failed_lines.append(error_line)
    
    # Try a simpler approach for any remaining failed lines
    for line in failed_lines:
        try:
            # Create a minimal record with just essential fields
            values = [None] * num_columns
            
            # Extract values that can be properly parsed
            # Split by commas outside of quotes
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
            
            # Process each field
            for i, field in enumerate(fields):
                if i >= num_columns:
                    break
                
                field = field.strip()
                if field == 'NULL':
                    values[i] = None
                elif field.startswith("'") and field.endswith("'"):
                    values[i] = field[1:-1].replace("''", "'")
                elif field.isdigit():
                    # Store large integers as strings to avoid SQLite limits
                    if len(field) > 9:  # Potentially large number
                        values[i] = field
                    else:
                        values[i] = int(field)
                # Add other field type parsing as needed
            
            # Ensure first column is NULL for base table (auto-increment)
            if table_name == 'base' and num_columns > 1:
                values[0] = None
            
            # Insert the record
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
    """
    Create database tables if they don't exist
    """
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
    """
    Create indices for better query performance
    """
    conn = sqlite3.connect(db_file)
    cursor = conn.cursor()
    
    if option == 1:  # Full indexing
        print("Creating full indices (this may take some time)...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_quadkey_full ON geo(quadkey, latitude, longitude, BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_coords_bssid ON geo(latitude, longitude, BSSID);")
        
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID_lower ON base (LOWER(ESSID));")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_WPSPIN ON base (WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_WiFiKey ON base (WiFiKey);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_composite ON base (BSSID, ESSID, WiFiKey, WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_bssid_essid ON base (BSSID, ESSID);")
        
    elif option == 2:  # Basic indexing
        print("Creating basic indices...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_BSSID ON base (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_base_ESSID ON base (ESSID);")
    
    elif option == 3:  # No indexing
        print("Skipping index creation as per user choice.")
    else:
        print("Invalid option selected for index creation.")
    
    conn.commit()
    conn.close()

def optimize_and_vacuum(db_file):
    """
    Optimize the database and vacuum it to reduce size with enhanced retry logic
    """
    print("\nOptimizing and vacuuming database...")
    max_attempts = 5
    attempt = 0
    
    # Try to optimize the database
    while attempt < max_attempts:
        try:
            # Increase wait time between attempts
            if attempt > 0:
                wait_time = 3 * (attempt + 1)
                print(f"Waiting {wait_time} seconds before next attempt...")
                time.sleep(wait_time)
            
            # Try to connect and optimize
            with sqlite3.connect(db_file, isolation_level=None, timeout=60) as conn:
                cursor = conn.cursor()
                
                # Optimize in steps with progress reporting
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
                return True  # Success
                
        except sqlite3.OperationalError as e:
            attempt += 1
            print(f"Optimization attempt {attempt}/{max_attempts} failed: {str(e)}")
            
            if attempt < max_attempts:
                # Try to forcibly close connections before next attempt
                try:
                    with sqlite3.connect(db_file, timeout=10) as temp_conn:
                        temp_conn.execute("PRAGMA wal_checkpoint(FULL);")
                except Exception as checkpoint_error:
                    print(f"Checkpoint failed: {str(checkpoint_error)}")
                
                time.sleep(2)  # Simple delay as a placeholder
            else:
                print("\nCould not optimize database after multiple attempts.")
                print("The database is still usable, but not optimized.")
                return False

def show_menu():
    menu = """
WiFi Database Converter Menu:
1. Convert with full indexing (recommended, big size)
3. Convert without indexing (not recommended, smallest size)
4. Exit

Choose an option (1-4): """
    return input(menu)

def main():
    check_and_install_dependencies()
    
    # Set global timeout for SQLite
    sqlite3.connect(':memory:', timeout=60).close()

    while True:
        choice = show_menu()
        if choice == '4':
            print("Exiting...")
            break

        if choice in ('1', '2', '3'):
            start_time = time.time()
            db_file = 'anti3wifi.db'
            geo_sql_file = 'geo.sql'
            base_sql_file = 'base.sql'
            error_file_geo = 'errors_geo.txt'
            error_file_base = 'errors_base.txt'

            # Create tables
            print("Creating database tables...")
            create_tables(db_file)

            # Process geo table
            print("\nProcessing 'geo' table...")
            geo_values = read_sql_file(geo_sql_file, 'geo')
            records_geo, fixed_geo, errors_geo = execute_inserts(db_file, 'geo', geo_values, 4, error_file_geo)
            
            # Process base table
            print("\nProcessing 'base' table...")
            base_values = read_sql_file(base_sql_file, 'base')
            records_base, fixed_base, errors_base = execute_inserts(db_file, 'base', base_values, 23, error_file_base)

            # Process error logs to recover more records
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

            # Display information about failed records
            if errors_geo > 0:
                print(f"\nFailed records for 'geo' table: {errors_geo}")
                print(f"See details in {error_file_geo} in the FAILED RECORDS section")
            
            if errors_base > 0:
                print(f"\nFailed records for 'base' table: {errors_base}")
                print(f"See details in {error_file_base} in the FAILED RECORDS section")

            # Create indices
            create_indices(db_file, int(choice))

            # Optimize database
            optimize_and_vacuum(db_file)
            
            end_time = time.time()
            duration = end_time - start_time
            print(f"\nProcessing completed successfully!")
            print(f"Total time: {timedelta(seconds=int(duration))}")
            print(f"Geo table: {records_geo} normal records, {fixed_geo} fixed records, {errors_geo} errors")
            print(f"Base table: {records_base} normal records, {fixed_base} fixed records, {errors_base} errors")
        else:
            print("Invalid choice. Please try again.")

if __name__ == "__main__":
    main()