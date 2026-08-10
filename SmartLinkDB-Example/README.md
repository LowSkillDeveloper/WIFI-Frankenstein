### 📑 Example of SmartLinkDB JSON File

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


> [!NOTE]
> If you want to add your database to the SmartLinkDB recommendation section in the application for all users, i.e. share your database, then create a ticket in the "Issues" section on github, with a request to add your smartlinkdb link to the application.
