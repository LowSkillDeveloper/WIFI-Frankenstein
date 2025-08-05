# 🔍 Converters for 3WIFI and p3WIFI SQL Database Backups

Tools for converting SQL backups of 3WIFI and p3WIFI databases to SQLite format for portable use and application integration.


> [!WARNING]
> Converting large dumps can take more than 30 minutes and heavily loads the CPU while using a lot of RAM. It is recommended to use a powerful PC for the conversion. Even if the status seems to be stuck, for example at "Extracting INSERT statements for table" the script continues to parse the dump. It just takes a long time because it needs to process each row and correctly extract the data from it.


## 🚀 Quick Start

### For 3WIFI dump Database Conversion

1. Place `3wifi.py` in the folder with your unpacked SQL backup
2. Ensure `geo.sql` and `base.sql` files are present in the same directory
3. Run the script: `python 3wifi.py`

### For p3WIFI dump Database Conversion

1. Place `p3wifi.py` in the folder with your unpacked SQL backup
2. Rename your SQL backup file (e.g., `p3wifi_dump_19.05.2024.sql`) to `input.sql`
3. Run the script: python `p3wifi.py`

### For RouterScan exported txt Conversion

If you have many txt format files from the RouterScan program and you want to combine them into a single database and use them in the application.

1. Place `txtRouterScan-to-sqlite.py` in the folder
2. Copy all txt files from the RouterScan program to the folder with the script  `txtRouterScan-to-sqlite.py`.
3. Run the script: python `txtRouterScan-to-sqlite.py`

## ⚙️ Conversion Process

The conversion happens in multiple stages:

1. **Initial Parsing**
2. **Parsing problematic strings**
3. **Insertion**
4. **Creating indexes**
5. **Optimization**
6. **Compression to archive**

Some steps may be skipped if they were not selected at startup.

## 📊 Index Options

When running either script, you will be prompted to choose:

- **Create with indexes** (Recommended): Larger file size but provides correct functionality and fast performance in applications
- **Create without indexes**: Smaller file size but may break map functionality and result in slow search performance

## 📚️ Archiving option

When running either script, you will be prompted to choose:

- **Create archive**: The database is placed in an archive to reduce its size, for example when downloading the database from the server.
- **Don't create archive**

If you choose to create an archive, you will have a choice:

- **Split archive into multiple parts**
- **Create single archive file**

If you choose to split into parts, the archive will be divided into the selected number of archives like: anti3wifi.zip.001, anti3wifi.zip.002, anti3wifi.zip.003 (maximum 5 archives)
This is done so that the file can be uploaded to a server that does not allow uploading large files.

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
    },
    {
      "id": "custom-db-auto",
      "name": "Custom Database with Auto-Mapping",
      "downloadUrl": "https://example.com/custom-db.zip",
      "version": "1.0",
      "type": "custom-auto-mapping",
      "tableName": "wifi_networks",
      "columnMapping": {
        "essid": "wifi_name",
        "mac": "bssid", 
        "wifi_pass": "password",
        "wps_pin": "wps",
        "latitude": "lat",
        "longitude": "lon",
        "admin_panel": "admin_credentials",
        "security_type": "security",
        "timestamp": "date_added"
      }
    }
  ]
}

```
## Parameter Description

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

  - `custom-auto-mapping`: Any SQLite database with a custom table/column structure. Same as "custom". But the mapping is already specified in the json file, the user will not need to match the columns themselves after downloading.

- **`tableName`** — This parameter applies only to "custom-auto-mapping", it is needed to specify the name of the table that is used in the sqlite file.

The file can be either a raw `.db`/`.sqlite` file or a ZIP archive.

For split ZIP archives, use `downloadUrl1`, `downloadUrl2`, ..., up to `downloadUrl5` maximum  
(e.g., `.zip.001`, `.zip.002`, etc.).

An example of a split archive is shown in the last entry of the array above.


## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
