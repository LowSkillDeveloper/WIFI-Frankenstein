# WiFi Frankenstein

![App Version](https://img.shields.io/badge/app_version-1.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/kotlin-2.2.0_RC-purple)



- 📱 **Download the application:**  
  [https://github.com/LowSkillDeveloper/WIFI-Frankenstein/releases/latest](https://github.com/LowSkillDeveloper/WIFI-Frankenstein/releases/latest)

- 🛠️ **Download scripts for converting SQL 3WiFi to SQLite 3Wifi:**  
  [https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SQL_3WIFi_to_SQLite_converter_python](https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SQL_3WIFi_to_SQLite_converter_python)

- 📂 **Example of the SmartLinkDB file:**  
  [https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SmartLinkDB-Example](https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SmartLinkDB-Example)



## 📱 Overview

**WiFi Frankenstein** is a comprehensive WiFi pentesting and database management application for Android. This application serves as the successor to the original 3WiFi Locator, offering significant improvements and new features for WiFi network analysis, mapping, and database management.

The application combines various WiFi tools and functionality into one powerful package, hence the name "Frankenstein" - it's built from the best parts of different WiFi analysis tools.

## ✨ Features

### 📊 Database Management
- **Local Database Support indexation and mapping**: The local database supports indexing plus WiFi points can be displayed on the map
- **Local 3WiFi Database Support**: Connect and work with huge 3WiFi databases locally
- **Custom Database Integration**: Connect any compatible SQLite databases
- **SmartLinkDB**: Support for updating databases via the Internet
- **Multiple Manufacturer Databases**: 4 separate databases for checking WiFi device manufacturers

### 🗺️ Mapping & Location
- **WiFi Maps**: View WiFi points from 3WiFi db and custom databases without an internet connection (The map itself requires internet)
- **Clustering Control**: Option to enable or disable point clustering on the map
- **Multiple bases**: Possibility to display wifi points from several different databases on the map at once.

### 🛠️ Tools & Features
- **MAC Address Locating**: Find the geographical location of WiFi access points by MAC address
- **WPS Pin Generator**: Local pin code generation
- **WPS Connect**: Possibility to connect via WPS 
- **3WiFi API Integration**: Advanced settings and direct API requests
- **Distance Calculation**: Measure distance to WiFi access point
- **Updates**: Support for updating the application and its components

### 🎨 Customization
- **Theme Options**: Multiple color styles to customize the app appearance
- **Icon Customization**: Change the application icon to suit your preferences

### 💻 Technical Highlights
- Written in **Kotlin 2.0**
- Built on the latest Android SDK
- Utilizes beta and alpha libraries for optimal performance

## ⚠️ Known Issues

- **Profile Saving**: Saving wifi profiles may sometimes fail
- **WPS Pin Detection**: The local WPS pin code generator does not detect possible pin codes (pins are still generated)
- **Local DB**: Some features are not fully transferred from the original locator

## 🔄 Comparison with 3WiFi Locator

| Feature | WiFi Frankenstein | 3WiFi Locator |
|---------|-------------------|---------------|
| Local in-app Database | ⚠️ Limited | ⚠️ Limited |
| Local Full 3WiFi Database | ✅ Supported | ❌ Not supported |
| Custom SQLite Databases | ✅ Supported | ❌ Not supported |
| Offline WiFi Maps | ⚠️ Only DB data | ❌ Not supported |
| WiFi Location by MAC | ✅ Yes | ❌ Not supported |
| Database Updates | ✅ SmartLinkDB | ❌ Not supported |
| App/Component Updates | ✅ Yes | ⚠️ Notification only |
| Customizable Themes | ✅ Multiple options | ⚠️ Dark mode only |
| Custom App Icon | ✅ Supported | ❌ Not supported |
| Advanced API Settings | ✅ Full access | ⚠️ Limited |
| Manufacturer Databases | ✅ 4 databases | ✅ 3 databases |
| Distance Calculation | ✅ Supported | ❌ Not supported |
| SDK version | ✅ Latest | ⚠️Old |


## 📱 Screenshots

<div align="center">
  <img src="https://github.com/user-attachments/assets/18ded2df-5a06-4be7-ac22-0e70484c5b4d" width="250" alt="Screenshot 1" />
  <img src="https://github.com/user-attachments/assets/10dd6ce3-4e53-467e-a5b9-6e4d52f0819e" width="250" alt="Screenshot 2" />
  <img src="https://github.com/user-attachments/assets/8a59d65e-9d77-4dc0-b6b0-7bfc09e96a69" width="250" alt="Screenshot 3" />
</div>

<div align="center">
  <img src="https://github.com/user-attachments/assets/805239a4-ed80-4a72-90e4-449d48c77bc5" width="250" alt="Screenshot 4" />
  <img src="https://github.com/user-attachments/assets/1b028b0d-1f8f-418b-9de1-2d8299e24d0c" width="250" alt="Screenshot 5" />
  <img src="https://github.com/user-attachments/assets/79167d68-cf4f-4762-9140-a4d5fdb9b696" width="250" alt="Screenshot 6" />
</div>

<div align="center">
  <img src="https://github.com/user-attachments/assets/f41d7878-4b33-4077-94d0-ab87e1190be3" width="250" alt="Screenshot 7" />
  <img src="https://github.com/user-attachments/assets/dcb23e83-8013-4db4-8fc5-e1dce1d8a9f2" width="250" alt="Screenshot 8" />
  <img src="https://github.com/user-attachments/assets/8a60d61e-d061-4e45-ad10-ef6d87f60a2f" width="250" alt="Screenshot 9" />
</div>

## 📥 Installation

1. Download the latest release from the Releases section
2. Enable installation from unknown sources in your Android settings
3. Install the APK file
4. Follow the in-app setup guide for database configuration

## 🔐 Permissions

The application requires the following permissions:
- Location access (for mapping and distance calculation)
- Storage access (for database management)
- Network access (for updates and online features)

## 📋 Requirements

- Android 5.0 or higher

## ⚖️ Disclaimer

This application is designed for network administrators, security researchers and WiFi enthusiasts to test and analyze their own networks. Always ensure you have proper authorization before analyzing any WiFi network.

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome! Feel free to open issues or submit pull requests.
