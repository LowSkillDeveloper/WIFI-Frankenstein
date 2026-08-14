# WiFi Frankenstein

![App Version](https://img.shields.io/badge/app_version-2.0-blue)
![Platform](https://img.shields.io/badge/platform-Android-brightgreen)
![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-purple)



- 📱 **Download the application:**  
  [https://github.com/LowSkillDeveloper/WIFI-Frankenstein/releases/latest](https://github.com/LowSkillDeveloper/WIFI-Frankenstein/releases/latest)

- 🛠️ **Download scripts and view the instructions for converting SQL 3WiFi to SQLite 3Wifi:**  
  [https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SQL_3WIFi_to_SQLite_converter_python](https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SQL_3WIFi_to_SQLite_converter_python)

- 📂 **Example of the SmartLinkDB file:**  
  [https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SmartLinkDB-Example](https://github.com/LowSkillDeveloper/WIFI-Frankenstein/tree/main/SmartLinkDB-Example)

>If you want to add your database to the SmartLinkDB recommendation section in the application for all users, i.e. share your database, then create a ticket in the "Issues" section on github, with a request to add your smartlinkdb link to the application.



## 📱 Overview

**WiFi Frankenstein** is a comprehensive WiFi pentesting and database management application for Android. This application serves as the successor to the original 3WiFi Locator, offering significant improvements and new features for WiFi network analysis, mapping, and database management.

The application combines various WiFi tools and functionality into one powerful package, hence the name "Frankenstein" - it's built from the best parts of different WiFi analysis tools.

## ✨ Features

### 📊 Database Management
- **Local Database Support**: The local database supports indexing and WiFi points can be displayed on the map
- **SQlite Database Integration**: Connect any SQLite databases

### 🗺️ Mapping & Location
- **WiFi Maps**: View WiFi points from 3WiFi db and custom databases without an internet connection (The map itself requires internet)
- **Multiple bases**: Possibility to display wifi points from several different databases on the map at once.
- **MAC base location (geomac)**: Finding the location of an access point by its MAC address

### 🛠️ Tools & Features

- **WPS PixieDust Android (OneShot)**: If you have root, you can perform a pixeldust attack from your smartphone with built-in wifi module.
- **MAC Address Locating (geomac)**: Find the geographical location of WiFi access points by MAC address
- **Handshake Capture**: Capture WPA/WPA2 handshakes (supported with root and a custom kernel)
- **DPI/RKN Blocking Check**: Check whether your provider or RKN is blocking DPI/websites
- **Router Scan**: Scan routers via Router Scan by Stas'M
- **Handshake Cracker**: Bruteforce captured handshakes or PMKID
- **wpa-sec.stanev.org**: Check if a network is present in the online database, upload handshakes and import your account's data into the local database
- **Handshake Converter**: Convert handshakes / handshake files between various formats
- **WiFi Channel Analysis**: Page for analyzing WiFi channels, providing insights into channel usage and performance.
- **More Detailed Information**: If you have root rights, you can do an iw scan and get the most detailed information about the Wi-Fi network.
- **Viewing Saved Passwords**:  If you have root, you can view passwords to networks to which the phone was connected and they are saved in its memory.
- **WPS Pin Generator**: Local WPS PIN code generation using many different algorithms and data from databases
- **WPA Password Generator**: Local wpa passwords generation using many different algorithms for old wifi routers
- **Neighbor-based WPS PIN suggestions**: multi-level neighbor-based WPS PIN suggestions from your DB
- **WPS Connect**: Possibility to connect via WPS using non-root and root methods
- **3WiFi API Integration**: Advanced settings and direct API requests
- **Distance Calculation**: Measure distance to WiFi access point
- **Offline IP ranges**: Using local databases, we find ranges of IP addresses for scanning with RouterScan
- **Updates**: Support for updating the application and its components

### 🎨 Customization
- **Theme Options**: Multiple color styles to customize the app appearance
- **Icon Customization**: Change the application icon to suit your preferences

## ⚠️ Known Issues

- **Profile Saving**: Saving wifi profiles may sometimes fail on a new androids

## 🔄 Comparison with 3WiFi Locator

| Feature | WiFi Frankenstein | 3WiFi Locator |
|---------|-------------------|---------------|
| Local in-app Database | ✅ Yes  | ⚠️ Limited |
| WiFi Maps | ✅ Yes | ❌ Not supported |
| Offline Full 3WiFi Database | ✅ Supported | ❌ Not supported |
| WiFi Location (geoMAC) | ✅ Yes | ❌ Not supported |
| Handshake capture ® | ✅ Supported (Root, kernel) | ❌ Not supported |
| Handshake cracker | ✅ Supported | ❌ Not supported |
| wpa-sec.stanev.org integration| ✅ Supported | ❌ Not supported |
| WPS PixieDust (OneShot) ® | ✅ Supported (Root) | ❌ Not supported |
| Router Scan by by Stas'M ® | ✅ Supported (proot) | ❌ Not supported |
| Checking DPI blocking by your provider or RKN | ✅ Supported | ❌ Not supported |
| Custom SQLite Database connection | ✅ Supported | ❌ Not supported |
| Advanced 3WiFi API Settings | ✅ Full access | ⚠️ Limited API access |
| Viewing saved passwords ® | ✅ Supported (Root) | ✅ Supported (Root) |
| WPA Algorithms (Router Keygen) | ✅ Supported | ❌ Not supported |
| WPS Algorithms | ✅ Supported | ⚠️ Limited |
| Local network scanner | ✅ Supported | ❌ Not supported |
| Neighbor-based WPS PIN Algorithms | ✅ Supported | ❌ Not supported |
| 3WIFI offline IP ranges | ✅ Supported | ❌ Not supported |
| WiFi Channel Analysis | ✅ Supported | ❌ Not supported |
| WiFi API Maps | ⚠️ Limited | ❌ Not supported |
| Database Updates | ✅ SmartLinkDB | ❌ Not supported |
| App/Component Updates | ✅ Supported | ⚠️ Notification only |
| Customizable Themes | ✅ Multiple options | ⚠️ Dark mode only |
| Custom App Icon | ✅ Supported | ❌ Not supported |
| Manufacturer Databases | ✅ 3 databases | ✅ 3 databases |
| Distance Calculation | ✅ Supported | ❌ Not supported |
| SDK version | ✅ Latest | ⚠️Old |


## 📱 Screenshots

<div align="center">
  <img width="250" alt="Screenshot 1" src="https://github.com/user-attachments/assets/5b2fbd0a-7481-4c1a-a1b0-11b81f3777e3" />
  <img width="250" alt="Screenshot 2" src="https://github.com/user-attachments/assets/b1a0f10c-57c9-4695-8add-def6cf5dacf7" />
  <img width="250" alt="Screenshot 3" src="https://github.com/user-attachments/assets/01f43032-dbf5-456d-bbda-602b22be98dd" />
</div>

<div align="center">
  <img width="250" alt="Screenshot 4" src="https://github.com/user-attachments/assets/a63a3695-1ba8-4562-96c9-8d0c4812a0c6" />
  <img width="250" alt="Screenshot 5" src="https://github.com/user-attachments/assets/73f88c47-ba13-4a3a-84be-98482be29622" />
  <img width="250" alt="Screenshot 6" src="https://github.com/user-attachments/assets/cfc379bf-33fb-4e07-b8a4-c3689e5cb4b0" />
</div>

<div align="center">
  <img width="250" alt="Screenshot 7" src="https://github.com/user-attachments/assets/3f9bf2cf-7c87-4b94-bd27-8786d1d98aaa" />
  <img width="250" alt="Screenshot 8" src="https://github.com/user-attachments/assets/6575f93a-138b-4189-9fa0-0022c483e6b8" />
  <img width="250" alt="Screenshot 9" src="https://github.com/user-attachments/assets/b3b3b344-8b9f-485e-9f17-bdb5b3e3870e" />
</div>

<div align="center">
  <img width="250" alt="Screenshot 10" src="https://github.com/user-attachments/assets/4e2dd456-c5b1-4c93-bbc5-97b2450da39c" />
  <img width="250" alt="Screenshot 11" src="https://github.com/user-attachments/assets/903ba8e9-5fc2-4012-9687-6e5a05b95b8e" />
  <img width="250" alt="Screenshot 12" src="https://github.com/user-attachments/assets/28ec597a-3629-4272-a5c0-ce09890969d2" />
</div>

<div align="center">
  <img width="250" alt="Screenshot 13" src="https://github.com/user-attachments/assets/5c016be2-779b-47f1-a07c-9db4f75ee491" />
  <img width="250" alt="Screenshot 14" src="https://github.com/user-attachments/assets/b5186339-7521-4701-89e4-77ce20c4477c" />
  <img width="250" alt="Screenshot 15" src="https://github.com/user-attachments/assets/40182056-8c1d-48a1-8c4e-3a493196471f" />
</div>

<div align="center">
  <img width="250" alt="Screenshot 16" src="https://github.com/user-attachments/assets/c96dc5ba-0384-4702-ae46-ff9851fbc540" />
</div>

## 📥 Installation

1. Download the latest release from the Releases section
2. Enable installation from unknown sources in your Android settings
3. Install the APK file
4. Follow the in-app setup guide for database configuration

## 🔐 Permissions

The application requires the following permissions:
- Location access (for mapping and for wifi scanning to work, Android SDK requirement)
- Storage access (for database management)
- Network access (for updates and online features)

## 📋 Requirements

- Android 5.0 or higher

## ⚖️ Disclaimer

This application is designed for network administrators, security researchers and WiFi enthusiasts to test and analyze their own networks. Always ensure you have proper authorization before analyzing any WiFi network.

## 💝 Support Development

If you find this project useful and would like to support its development, you can make a donation using cryptocurrency:

### Bitcoin (BTC)
```
19LYe2QhHXp2YAXSPrYydGc8v3t2TPdEPf
```

### Ethereum (ETH) 
```
0x5ebC5Eb2f59E6B62Ca9b221F2549D5067457D9b8
```

### Monero (XMR)
```
4AC1MepXZA8R6XGcL5mjejWRDqKvmbY3YWGEJTCWmFxJ8gPuLULSYxKSWafy9haMXGYuR2CdF3Vr8Q2kS8pBorVpQ4Lie48
```