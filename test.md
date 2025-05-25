### 📑 Пример JSON-файла SmartLinkDB

Ниже представлен пример конфигурационного JSON-файла `smartlinkdb`, который используется приложением для загрузки и обновления баз данных.  

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
