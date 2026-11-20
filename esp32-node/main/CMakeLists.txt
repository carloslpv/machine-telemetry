idf_component_register(SRCS "main.c" "wifi.c" "ads1115.c" "mqtt_link.c"
                       INCLUDE_DIRS "."
                       PRIV_REQUIRES node_logic esp_driver_i2c esp_driver_gpio esp_wifi esp_netif esp_event
                                     esp_timer nvs_flash mqtt)
