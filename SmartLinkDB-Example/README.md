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
      "name": "Split ZIP DB WiFi Database",
      "downloadUrl1": "https://example.com/WIFI-Frankenstein/p3wifi.zip.001",
      "downloadUrl2": "https://example.com/WIFI-Frankenstein/p3wifi.zip.002",
      "downloadUrl3": "https://example.com/WIFI-Frankenstein/p3wifi.zip.003",
      "version": "1.1",
      "type": "3wifi"
    },
    {
      "id": "mega_db",
      "name": "MEGA raw database",
      "downloadUrl": "https://mega.nz/file/XXXXXXXX#YYYYYYYYYYYYYYYYYYYYYYYYYYYYYY",
      "version": "1.0",
      "type": "3wifi"
    },
    {
      "id": "mega_archive",
      "name": "MEGA zip archive database",
      "downloadUrl": "https://mega.nz/#!XXXXXXXX!YYYYYYYYYYYYYYYYYYYYYYYYYYYYYY",
      "version": "1.0",
      "type": "custom"
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
        "admin_panel": "admin_credentials",
        "admin_login": "admin_login",
        "admin_pass": "admin_pass",
        "latitude": "lat",
        "longitude": "lon",
        "security_type": "security",
        "timestamp": "date_added"
      }
    },
    {
      "id": "no-type-db",
      "name": "Database without explicit type",
      "downloadUrl": "https://example.com/auto_detected.db",
      "version": "1.0"
    }
  ]
}
```

---

## What links can be pasted into the app

The "Add by link" field accepts **three kinds of links** — the app detects the type automatically:

1. **JSON manifest** (this file format) — a `.json` file (or any URL whose path contains `smartlink` or `/api/`).
2. **Direct link** to a single file:
   - raw SQLite database: `.db` / `.sqlite`;
   - ZIP archive: `.zip`.
   - When a direct link is used, a single entry is created automatically: `id` = MD5 of the URL, `name` = file name, `version` = `1.0`, `type` = `direct-db` / `direct-archive`.
3. **MEGA file link** (see below).

A direct link or a MEGA link used without a manifest **cannot be auto-updated** (updates require a JSON manifest so the app can compare `version` and find the new `downloadUrl`).

---

## MEGA links

Supported formats (public **file** links only):

```
https://mega.nz/file/<handle>#<key>
https://mega.nz/#!<handle>!<key>
```

The MEGA file may be:
- a raw SQLite database (`.db` / `.sqlite`);
- a ZIP archive (`.zip`) — the archive is auto-detected by its magic bytes and extracted.

> [!IMPORTANT]
> **MEGA folder links (`mega.nz/folder/...`) are NOT supported.** The app shows a clear error instead of trying to download them.

---

## Parameter Description

- **`id`** — Unique identifier of the database.  
  **Must never change**, otherwise the application will not be able to detect or update the already added database.

- **`name`** — Display name of the database in the application.

- **`downloadUrl`** — Direct URL from which the application will download or update the database.  
  This link may change when updating the file on the server. Can point to a raw `.db`/`.sqlite` file, an archive, or a MEGA file link.

- **`downloadUrl1`, `downloadUrl2`, ...** — Alternative to `downloadUrl` for **split ZIP archives** (`.zip.001`, `.zip.002`, etc.). There is **no fixed limit** on the number of parts.  
  > ⚠️ Do **not** mix `downloadUrl` and `downloadUrlN` in the same entry — if both are present the app uses `downloadUrl` only.

- **`version`** — Current version of the database.  
  Must be increased whenever the file is updated on the server so the application knows a new version is available for download.

- **`type`** — Database type (used for the column-mapping auto-configuration and display):
  - `3wifi` — database converted using the `3wifi.py` or `p3wifi.py` scripts.
  - `custom` — any SQLite database with a custom table/column structure.
  - `custom-auto-mapping` — same as `custom`, but the mapping is specified in the JSON file, so the user does not need to match columns after downloading. The mapping is validated against the real table after download; columns that do not exist are skipped.
  - `mega`, `direct-db`, `direct-archive`, `direct-unknown` — auto-assigned when a direct/MEGA link is used without a manifest.
  - **Optional.** If `type` is omitted, it defaults to `auto`: the actual DB format (`3wifi` vs `custom`) is always auto-detected after download by inspecting the tables, so the declared type only affects the mapping configuration and the type label shown in the UI.

- **`tableName`** — Applies only to `custom-auto-mapping`: the name of the table used in the SQLite file. Optional — if omitted, the first table in the database is used.

- **`columnMapping`** — Applies only to `custom-auto-mapping`: maps the app's logical fields to the real column names of your table. The full set of supported logical keys:

  | Logical key | Meaning |
  |---|---|
  | `essid` | Wi-Fi network name |
  | `mac` | BSSID / MAC address |
  | `wifi_pass` | Wi-Fi password |
  | `wps_pin` | WPS PIN |
  | `admin_panel` | Admin panel URL/credentials (single column) |
  | `admin_login` | Admin login (separate column) |
  | `admin_pass` | Admin password (separate column) |
  | `latitude` | Latitude |
  | `longitude` | Longitude |
  | `security_type` | Security type (WPA2, WPA3, ...) |
  | `timestamp` | Record date |

  Use either `admin_panel` **or** the pair `admin_login` + `admin_pass`, not both.

---

## Supported file types

- Raw SQLite file: `.db` / `.sqlite`.
- ZIP archive: `.zip`.
- Split ZIP archive: parts named `.zip.001`, `.zip.002`, ... are merged and extracted.
- **The database file inside an archive must be named `.db`, `.sqlite`, or `.sqlite3`** — it is the first such entry that is extracted.
- Interrupted downloads can be resumed (range requests; per-part resume for split archives).

---

## How updates work

- The app stores, for each added database: the source JSON URL, its `id`, and the version.
- On every update check it re-fetches the manifest, finds the entry with the same `id`, and compares `version`.
- To publish an update: bump `version` (and change `downloadUrl` if needed). `id` must stay the same.
- `columnMapping` / `tableName` are **re-read and re-validated** on update for `custom-auto-mapping` databases.
- Direct links and MEGA links used without a manifest cannot be updated automatically.

---

## Recommended sources (community DB list)

The app also ships a list of **recommended sources**. Each source points to a SmartLinkDB manifest.

> [!NOTE]
> If you want to add your database to the SmartLinkDB recommendation section in the application for all users, i.e. share your database, then create a ticket in the "Issues" section on github, with a request to add your smartlinkdb link to the application.
