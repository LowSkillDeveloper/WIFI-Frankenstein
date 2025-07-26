# 🔍 Converters for 3WIFI and p3WIFI SQL Database Backups

Tools for converting SQL backups of 3WIFI and p3WIFI databases to SQLite format for portable use and application integration.

## 🚀 Quick Start

### For 3WIFI Database Conversion

1. Place `3wifi.py` in the folder with your unpacked SQL backup
2. Ensure `geo.sql` and `base.sql` files are present in the same directory
3. Run the script: `python 3wifi.py`

### For p3WIFI Database Conversion

1. Place `p3wifi.py` in the folder with your unpacked SQL backup
2. Rename your SQL backup file (e.g., `p3wifi_dump_19.05.2024.sql`) to `input.sql`
3. Run the script: python `p3wifi.py`

## ⚙️ Conversion Process

The conversion happens in multiple stages:

1. **Initial Parsing & Insertion**: The script reads and processes SQL data into SQLite
2. **Error Handling**: Creates `errors_nets.txt` and `errors_geo.txt` files for problematic lines
3. **Retry Process**: Attempts to reinsert problematic lines (algorithm by ChatGPT)
4. **Manual Insertion**: Some problematic entries are handled through dedicated code

## 📊 Index Options

When running either script, you will be prompted to choose:

- **Create with indexes** (Recommended): Larger file size but provides correct functionality and fast performance in applications
- **Create without indexes**: Smaller file size but may break map functionality and result in slow search performance

> ⚠️ **IMPORTANT WARNING**: DO NOT MANUALLY MODIFY THE SQLITE DATABASE FILE! This will break the indexing in applications or corrupt the entire database.

## 📑 Example of SmartLinkDB JSON File

Below is an example of a `smartlinkdb` configuration JSON file, which the application uses to download and update databases.

```json
{
  "databases": [
    {
      "id": "3wifi_db",
      "name": "anti3WiFi Database",
      "downloadUrl": "https://example.com/WIFI-Frankenstein/anti3wifi.db",
      "version": "1.1",
      "type": "3wifi"
    },
    {
      "id": "custom_db",
      "name": "Custom WiFi Database",
      "downloadUrl": "https://example.com/WIFI-Frankenstein/my_custom.sqlite",
      "version": "2.0",
      "type": "custom"
    },
    {
      "id": "p3wifiTEST_zip",
      "name": "Zip DB WiFi Database",
      "downloadUrl": "https://example.com/WIFI-Frankenstein/test.db.zip",
      "version": "1.1",
      "type": "3wifi"
    },
    {
      "id": "p3wifi_zip_split",
      "name": "Zip splited DB WiFi Database",
      "downloadUrl1": "https://example.com/WIFI-Frankenstein/p3wifi.zip.001",
      "downloadUrl2": "https://example.com/WIFI-Frankenstein/p3wifi.zip.002",
      "downloadUrl3": "https://example.com/WIFI-Frankenstein/p3wifi.zip.003",
      "version": "1.1",
      "type": "3wifi"
    }
  ]
}

```
### Parameter Description

- **`id`** — Unique identifier of the database.  
  **Must never change**, otherwise the application will not be able to detect or update the already added database.

- **`name`** — Display name of the database in the application.

- **`downloadUrl`** — Direct URL from which the application will download or update the database.  
  This link may change when updating the file on the server.

- **`version`** — Current version of the database.  
  Must be increased whenever the file is updated on the server so the application knows a new version is available for download.

- **`type`** — Type of database:
  - `3wifi`: Database converted using the `3wifi.py` or `p3wifi.py` scripts.
  - `custom`: Any SQLite database with a custom table/column structure.

The file can be either a raw `.db`/`.sqlite` file or a ZIP archive.

For split ZIP archives, use `downloadUrl1`, `downloadUrl2`, ..., up to `downloadUrl5` maximum  
(e.g., `.zip.001`, `.zip.002`, etc.).

An example of a split archive is shown in the last entry of the array above.


## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
