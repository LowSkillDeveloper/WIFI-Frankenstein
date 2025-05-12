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

## 🔧 Handling Problematic Entries

If the final report shows unadded lines, you can:

1. Add them to the `problem_entries` section at the end of the script
2. Re-run the script to include these entries

This section can also be used to add your own custom data to the database.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
