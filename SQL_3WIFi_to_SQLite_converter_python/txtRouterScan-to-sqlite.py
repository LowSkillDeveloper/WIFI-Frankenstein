#!/usr/bin/env python3

import os
import sqlite3
import glob
import re
from datetime import datetime
from tqdm import tqdm
import multiprocessing
from concurrent.futures import ProcessPoolExecutor, as_completed
import subprocess
import time
import threading
import zipfile
import shutil

def create_database_structure(db_path):
    conn = sqlite3.connect(db_path)
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
    
    conn.commit()
    conn.close()

def optimize_database(db_path):
    print("Optimizing database...")
    max_attempts = 5
    attempt = 0
    
    while attempt < max_attempts:
        try:
            if attempt > 0:
                wait_time = 3 * (attempt + 1)
                print(f"Waiting {wait_time} seconds before next attempt...")
                time.sleep(wait_time)
            
            with sqlite3.connect(db_path, isolation_level=None, timeout=30) as conn:
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

def mac_to_integer(mac_address):
    if not mac_address or mac_address == "":
        return 0
    
    try:
        clean_mac = mac_address.replace(":", "").replace("-", "").upper()
        if len(clean_mac) == 12:
            return int(clean_mac, 16)
    except (ValueError, AttributeError):
        pass
    
    return 0

def calculate_quadkey(lat, lon, level=18):
    if lat is None or lon is None or lat == 0.0 or lon == 0.0:
        return 0
    
    try:
        lat_rad = lat * 3.14159265359 / 180
        n = 2 ** level
        x_tile = int((lon + 180) / 360 * n)
        y_tile = int((1 - (lat_rad + 3.14159265359/2) / 3.14159265359) / 2 * n)
        
        quadkey = 0
        for i in range(level):
            mask = 1 << (level - i - 1)
            cell = 0
            if (x_tile & mask) != 0:
                cell += 1
            if (y_tile & mask) != 0:
                cell += 2
            quadkey = (quadkey << 2) | cell
            
        return quadkey
    except:
        return 0

def parse_routerscan_line(line):
    if not line.strip() or line.startswith("#"):
        return None
    
    parts = line.split("\t")
    
    if len(parts) < 9:
        return None
    
    try:
        bssid = parts[8].strip() if len(parts) > 8 else ""
        essid = parts[9].strip() if len(parts) > 9 else ""
        wifi_key = parts[11].strip() if len(parts) > 11 else ""
        wps_pin = parts[12].strip() if len(parts) > 12 else ""
        admin_credentials = parts[4].strip() if len(parts) > 4 else ""
        
        latitude = None
        longitude = None
        
        if len(parts) > 20:
            lat_str = parts[19].strip()
            lon_str = parts[20].strip()
            
            try:
                if lat_str and lon_str and lat_str != "" and lon_str != "":
                    lat = float(lat_str)
                    lon = float(lon_str)
                    
                    if (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0 and lat != 0.0 and lon != 0.0):
                        latitude = lat
                        longitude = lon
            except ValueError:
                pass
        
        if latitude is None and longitude is None and len(parts) >= 14:
            for i in range(len(parts) - 5, len(parts) - 1):
                if i >= 0 and i + 1 < len(parts):
                    lat_str = parts[i].strip()
                    lon_str = parts[i + 1].strip()
                    
                    if (re.match(r'^\d{1,2}\.\d+$', lat_str) and re.match(r'^\d{1,3}\.\d+$', lon_str)):
                        try:
                            lat = float(lat_str)
                            lon = float(lon_str)
                            
                            if (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0 and lat != 0.0 and lon != 0.0):
                                latitude = lat
                                longitude = lon
                                break
                        except ValueError:
                            continue
        
        if not essid and not bssid:
            return None
            
        clean_bssid = ""
        if bssid and bssid != "0.0.0.0":
            clean_bssid = bssid.upper().replace("-", ":").strip()
            if clean_bssid and not re.match(r'^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$', clean_bssid):
                no_separators = clean_bssid.replace(":", "").replace("-", "")
                if len(no_separators) == 12 and re.match(r'^[0-9A-F]{12}$', no_separators):
                    clean_bssid = ":".join([no_separators[i:i+2] for i in range(0, 12, 2)])
                else:
                    clean_bssid = ""
        
        clean_wifi_key = None
        if wifi_key and wifi_key not in ["0", "-", "", "NULL"] and len(wifi_key) >= 1:
            clean_wifi_key = wifi_key
            
        clean_wps_pin = None
        if wps_pin and wps_pin not in ["0", "-", "", "NULL"]:
            if wps_pin.isdigit() and len(wps_pin) >= 4:
                clean_wps_pin = wps_pin
            
        clean_admin_panel = None
        if (admin_credentials and admin_credentials not in [":", "-", "", "NULL"] and 
            "0.0.0.0" not in admin_credentials and ":" in admin_credentials):
            clean_admin_panel = admin_credentials
        
        return {
            'essid': essid.strip() if essid else "",
            'bssid': clean_bssid,
            'wifi_key': clean_wifi_key,
            'wps_pin': clean_wps_pin,
            'admin_panel': clean_admin_panel,
            'latitude': latitude,
            'longitude': longitude
        }
        
    except Exception as e:
        print(f"Error parsing line: {str(e)}")
        return None

def process_file(file_path):
    networks = []
    errors = 0
    stats = {
        'total_lines': 0,
        'valid_networks': 0,
        'with_coordinates': 0,
        'with_wifi_key': 0,
        'with_wps_pin': 0,
        'with_admin_panel': 0
    }
    
    try:
        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f:
            for line_num, line in enumerate(f, 1):
                stats['total_lines'] += 1
                try:
                    network = parse_routerscan_line(line)
                    if network:
                        networks.append(network)
                        stats['valid_networks'] += 1
                        
                        if network['latitude'] is not None and network['longitude'] is not None:
                            stats['with_coordinates'] += 1
                        if network['wifi_key']:
                            stats['with_wifi_key'] += 1
                        if network['wps_pin']:
                            stats['with_wps_pin'] += 1
                        if network['admin_panel']:
                            stats['with_admin_panel'] += 1
                            
                except Exception as e:
                    errors += 1
                    if errors < 5:
                        print(f"Error in {os.path.basename(file_path)} line {line_num}: {e}")
    
    except Exception as e:
        print(f"Error reading file {file_path}: {e}")
        return [], 1, {}
        
    return networks, errors, stats

def insert_data_batch(db_path, networks_batch):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    geo_data = []
    nets_data = []
    current_time = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    
    for network in networks_batch:
        bssid_int = mac_to_integer(network['bssid'])
        
        if bssid_int == 0 and network['essid']:
            essid_hash = hash(network['essid']) & 0xFFFFFFFFFFFF
            bssid_int = abs(essid_hash) if essid_hash != 0 else 1
        
        if bssid_int == 0:
            continue
            
        if network['latitude'] is not None and network['longitude'] is not None:
            quadkey = calculate_quadkey(network['latitude'], network['longitude'])
            geo_data.append((
                bssid_int,
                network['latitude'],
                network['longitude'], 
                quadkey
            ))
        
        wifi_key = network['wifi_key'] or ''
        wps_pin_int = 0
        if network['wps_pin']:
            try:
                wps_pin_int = int(network['wps_pin'])
            except ValueError:
                pass
        
        security = 0 if not wifi_key else 2
        
        nets_data.append((
            current_time,
            0,
            0,
            0,
            network['admin_panel'],
            '',
            0,
            0,
            1 if not network['bssid'] else 0,
            bssid_int,
            network['essid'],
            security,
            1 if not wifi_key else 0,
            wifi_key,
            1 if not network['wps_pin'] else 0,
            wps_pin_int,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0
        ))
    
    try:
        if geo_data:
            cursor.executemany(
                "INSERT INTO geo (BSSID, latitude, longitude, quadkey) VALUES (?, ?, ?, ?)",
                geo_data
            )
        
        if nets_data:
            cursor.executemany(
                """INSERT INTO nets (time, cmtid, IP, Port, Authorization, name, RadioOff, 
                   Hidden, NoBSSID, BSSID, ESSID, Security, NoWiFiKey, WiFiKey, NoWPS, 
                   WPSPIN, LANIP, LANMask, WANIP, WANMask, WANGateway, DNS1, DNS2, DNS3) 
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                nets_data
            )
        
        conn.commit()
        return len(nets_data)
        
    except Exception as e:
        print(f"Database error: {e}")
        return 0
    finally:
        conn.close()

def create_indexes(db_path, option):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    if option == 1:
        print("\nCreating full indexes...")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_BSSID ON geo (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_latitude ON geo (latitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_geo_longitude ON geo (longitude);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_BSSID ON nets (BSSID);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_ESSID ON nets (ESSID COLLATE NOCASE);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_wpspin ON nets(WPSPIN);")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_nets_wifikey ON nets(WiFiKey COLLATE NOCASE);")
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
    
    conn.commit()
    conn.close()

def check_archiver_availability():
    available_tools = {'python': True}
    
    try:
        subprocess.run(['7z'], capture_output=True, check=False)
        available_tools['7z'] = True
    except FileNotFoundError:
        available_tools['7z'] = False
    
    return available_tools

def get_file_size_gb(file_path):
    if os.path.exists(file_path):
        size_bytes = os.path.getsize(file_path)
        return size_bytes / (1024 ** 3)
    return 0

def show_compression_stats(original_file, compressed_file):
    original_size = get_file_size_gb(original_file)
    compressed_size = get_file_size_gb(compressed_file)
    compression_ratio = (1 - compressed_size / original_size) * 100 if original_size > 0 else 0
    
    print(f"Archive created successfully: {compressed_file}")
    print(f"Original size: {original_size:.2f} GB")
    print(f"Compressed size: {compressed_size:.2f} GB")
    print(f"Compression ratio: {compression_ratio:.1f}%")
    
    return True

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

def create_archive(db_file, archive_options):
    available_tools = check_archiver_availability()
    
    if available_tools.get('7z') and archive_options['format'] == '7z':
        return create_archive_7z(db_file, archive_options)
    
    return create_archive_python(db_file, archive_options)

def show_indexing_menu():
    menu = """
╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗
║                            RouterScan txt to SQLite Database Converter                            ║
║                                      Indexing Options                                             ║
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
    print("╔═══════════════════════════════════════════════════════════════════════════════════════════════════╗")
    print("║                           RouterScan txt to SQLite Database Converter                             ║")
    print("║                 Convert all RouterScan TXT files in folder to sqlite file (3wifi type)            ║")
    print("╚═══════════════════════════════════════════════════════════════════════════════════════════════════╝")
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    txt_files = glob.glob(os.path.join(script_dir, "*.txt"))
    
    if not txt_files:
        print("\nNo .txt files found in the script directory!")
        print("Please place your RouterScan .txt files in the same directory as this script.")
        return
    
    print(f"\nFound {len(txt_files)} .txt files:")
    for file in txt_files:
        file_size = os.path.getsize(file) / (1024 * 1024)
        print(f"  - {os.path.basename(file)} ({file_size:.1f} MB)")
    
    confirm = input(f"\nProcess all {len(txt_files)} files? (y/n): ").lower().strip()
    if confirm not in ['y', 'yes']:
        print("Processing cancelled.")
        return
    
    remove_duplicates = input(f"\nRemove exact duplicates? (y/n): ").lower().strip()
    should_remove_duplicates = remove_duplicates in ['y', 'yes']
    
    while True:
        preferences = get_user_preferences()
        
        if preferences is None:
            print("Exiting...")
            return
        
        if display_summary(preferences):
            break
    
    start_time = time.time()
    
    db_path = os.path.join(script_dir, "routerscan_3wifi.db")
    if os.path.exists(db_path):
        overwrite = input(f"\nDatabase {db_path} already exists. Overwrite? (y/n): ").lower().strip()
        if overwrite in ['y', 'yes']:
            os.remove(db_path)
        else:
            print("Processing cancelled.")
            return
    
    print(f"\nCreating database: {db_path}")
    create_database_structure(db_path)
    
    print("\nProcessing RouterScan files...")
    all_networks = []
    total_errors = 0
    combined_stats = {
        'total_lines': 0,
        'valid_networks': 0,
        'with_coordinates': 0,
        'with_wifi_key': 0,
        'with_wps_pin': 0,
        'with_admin_panel': 0
    }
    
    num_processes = max(1, multiprocessing.cpu_count() - 1)
    print(f"Using {num_processes} processes for parsing...")
    
    with ProcessPoolExecutor(max_workers=num_processes) as executor:
        future_to_file = {executor.submit(process_file, file_path): file_path 
                         for file_path in txt_files}
        
        with tqdm(total=len(txt_files), desc="Parsing files", unit="file") as pbar:
            for future in as_completed(future_to_file):
                file_path = future_to_file[future]
                try:
                    networks, errors, stats = future.result()
                    all_networks.extend(networks)
                    total_errors += errors
                    
                    for key in combined_stats:
                        combined_stats[key] += stats.get(key, 0)
                    
                    pbar.set_postfix({
                        "Networks": len(all_networks), 
                        "Errors": total_errors,
                        "Coords": combined_stats['with_coordinates']
                    })
                    pbar.update(1)
                except Exception as e:
                    print(f"Error processing {file_path}: {e}")
                    total_errors += 1
                    pbar.update(1)
    
    print(f"\nParsing completed:")
    print(f"  - Total lines processed: {combined_stats['total_lines']:,}")
    print(f"  - Valid networks found: {combined_stats['valid_networks']:,}")
    print(f"  - Networks with coordinates: {combined_stats['with_coordinates']:,}")
    print(f"  - Networks with WiFi keys: {combined_stats['with_wifi_key']:,}")
    print(f"  - Networks with WPS PINs: {combined_stats['with_wps_pin']:,}")
    print(f"  - Networks with admin panels: {combined_stats['with_admin_panel']:,}")
    print(f"  - Total parsing errors: {total_errors:,}")
    
    if not all_networks:
        print("No valid networks found. Exiting.")
        return
    
    if should_remove_duplicates:
        print("\nRemoving exact duplicates...")
        unique_networks = set()
        unique_list = []
        
        for network in all_networks:
            network_tuple = (
                network['essid'],
                network['bssid'], 
                network['wifi_key'],
                network['wps_pin'],
                network['admin_panel'],
                network['latitude'],
                network['longitude']
            )
            
            if network_tuple not in unique_networks:
                unique_networks.add(network_tuple)
                unique_list.append(network)
        
        print(f"After deduplication: {len(unique_list):,} unique networks")
    else:
        print("\nKeeping all records including duplicates...")
        unique_list = all_networks
    
    print("\nInserting data into database...")
    batch_size = 10000
    total_inserted = 0
    
    with tqdm(total=len(unique_list), desc="Inserting data", unit="network") as pbar:
        for i in range(0, len(unique_list), batch_size):
            batch = unique_list[i:i + batch_size]
            inserted = insert_data_batch(db_path, batch)
            total_inserted += inserted
            pbar.update(len(batch))
    
    print(f"\nData insertion completed:")
    print(f"  - Records inserted: {total_inserted:,}")
    
    create_indexes(db_path, preferences['indexing'])
    
    optimize_database(db_path)
    
    end_time = time.time()
    duration = end_time - start_time
    
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    
    cursor.execute("SELECT COUNT(*) FROM geo")
    geo_count = cursor.fetchone()[0]
    
    cursor.execute("SELECT COUNT(*) FROM nets") 
    nets_count = cursor.fetchone()[0]
    
    conn.close()
    
    db_size = os.path.getsize(db_path) / (1024 * 1024)
    
    print(f"\n" + "="*80)
    print("DATABASE CONVERSION COMPLETED")
    print("="*80)
    print(f"Total processing time: {time.strftime('%H:%M:%S', time.gmtime(duration))}")
    print(f"Database: {db_path}")
    print(f"Database size: {db_size:.1f} MB")
    print(f"Geo records: {geo_count:,}")
    print(f"Nets records: {nets_count:,}")
    print(f"Processing errors: {total_errors:,}")
    
    if preferences['create_archive']:
        archive_options = {
            'format': preferences['format'],
            'compression': preferences['compression'],
            'split': preferences['split_archive']
        }
        
        archive_start = time.time()
        success = create_archive(db_path, archive_options)
        archive_end = time.time()
        archive_duration = archive_end - archive_start
        
        if success:
            print(f"Archive creation time: {time.strftime('%H:%M:%S', time.gmtime(archive_duration))}")
        else:
            print("Archive creation failed!")
    
    print("="*80)
    print("Conversion completed successfully!")
    print("="*80)

if __name__ == "__main__":
    main()