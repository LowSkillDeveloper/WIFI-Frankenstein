# PY Converters for backups of SQL databases 3WIFI and p3WIFI files to SQLite databases

To convert a backup of the classic 3WIFI database, use the 3wifi.py file. Place the python file in the folder with the unpacked SQL backup, namely the geo.sql and base.sql files

To convert a p3WiFi backup, use the p3wifi.py file. Place the python file in the folder with the unpacked SQL backup and rename the SQL file (for example, p3wifi_dump_19.05.2024.sql) to input.sql and run the python file.

The conversion occurs in several stages. Parsing and insertion into the DB, then the errors_nets.txt and errors_geo.txt files are created, which will contain the lines that could not be added to the DB. The second part attempts to insert the lines that were included in these files (this part of the code is written by ChatGPT), but even so, some lines do not get into the DB, so the code manually adds lines to the DB. If there are unadded lines in the final report, you can add them to the script itself and at the end of the script they will be added to the DB. This part of the code is called "problem_entries", add the data that could not get into the DB, you can also use this part of the code to add your own data to the code.

When you run the code, you will have an option to choose whether to create indexes or not. Choose the option with indexes, the file will weigh more, but it will work correctly and quickly in the application. The option without indexes was originally created for debugging and testing, but it can be used to create smaller databases, but the map may stop working in the application, and the search in the database will also take a long time.

IMPORTANT! DO NOT ATTEMPT TO MANUALLY ADD OR MODIFY THE SQLITE FILE! THIS WILL BREAK THE INDEXING IN THE APPLICATION OR THE ENTIRE DATABASE.
