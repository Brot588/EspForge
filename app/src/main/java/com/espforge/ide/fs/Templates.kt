package com.espforge.ide.fs

import java.io.File

/**
 * ESP-IDF 工程模板。生成的是官方 esp-idf 结构：
 *
 *   CMakeLists.txt          顶层，include($ENV{IDF_PATH}/tools/cmake/project.cmake)
 *   main/CMakeLists.txt     idf_component_register(...)
 *   main/<app>.c            源码
 *   sdkconfig.defaults      默认配置（含串口 gdbstub 开关）
 *
 * 这样工程可以原样拿到任何装了 ESP-IDF 的机器上编译。
 */
object Templates {

    const val BLINK = "blink"
    const val HELLO = "hello"
    const val WIFI = "wifi"
    const val EMPTY = "empty"

    val all = listOf(
        Triple(BLINK, "点灯 Blink", "GPIO 翻转 + FreeRTOS 延时，最小可烧录工程"),
        Triple(HELLO, "你好世界 Hello", "打印芯片型号 / 核心数 / 内存，验证工具链"),
        Triple(WIFI, "Wi-Fi 连接", "STA 模式连接热点并打印 IP"),
        Triple(EMPTY, "空白工程", "只有最少的 CMakeLists 和 main"),
    )

    /** 工程名 → 合法的 C 标识符，用作 IDF 工程名 */
    fun ident(raw: String): String {
        var s = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_')
        while (s.contains("__")) s = s.replace("__", "_")
        if (s.isEmpty()) s = "app"
        if (!s[0].isLetter()) s = "app_$s"
        return s
    }

    private fun topCMake(name: String) = """
        # ESP-IDF 顶层工程文件，由 EspForge 生成
        cmake_minimum_required(VERSION 3.16)

        include(${'$'}ENV{IDF_PATH}/tools/cmake/project.cmake)
        project($name)
    """.trimIndent() + "\n"

    private fun mainCMake(sources: List<String>) = """
        idf_component_register(
            SRCS ${sources.joinToString(" ") { "\"$it\"" }}
            INCLUDE_DIRS ".")
    """.trimIndent() + "\n"

    /** 串口调试需要的默认配置：打开运行时 gdbstub，日志走 UART0 */
    private fun sdkconfigDefaults(flash: String = "4MB") = """
        # 由 EspForge 生成的默认配置，第一次构建后会并入 sdkconfig
        CONFIG_ESPTOOLPY_FLASHSIZE_${flash.replace("MB", "MB")}=y
        CONFIG_ESPTOOLPY_BAUD_460800B=y
        CONFIG_LOG_DEFAULT_LEVEL_INFO=y
        CONFIG_ESP_MAIN_TASK_STACK_SIZE=4096
        # 串口 gdbstub：允许调试器在运行时接管 UART0（断点/单步/看变量）
        CONFIG_ESP_SYSTEM_GDBSTUB_RUNTIME=y
        CONFIG_ESP_SYSTEM_PANIC_PRINT_REBOOT=y
        # 关掉证书包生成：生成它要 Termux 的 cryptography / _cffi_backend，
        # 离线资源包里没有，构建会在「building certificate bundle」处失败。
        CONFIG_MBEDTLS_CERTIFICATE_BUNDLE=n
    """.trimIndent() + "\n"

    private val gitignore = """
        build/
        sdkconfig
        sdkconfig.old
        managed_components/
        dependencies.lock
        .cache/
    """.trimIndent() + "\n"

    private fun readme(name: String, template: String) = """
        # $name

        由 EspForge 生成的 ESP-IDF 工程（模板：$template）。

        ## 目录

        - `CMakeLists.txt` — 顶层工程文件，引用 `${'$'}IDF_PATH/tools/cmake/project.cmake`
        - `main/` — 主组件，`idf_component_register` 里登记源码
        - `sdkconfig.defaults` — 默认配置（首次构建时并入 `sdkconfig`）

        ## 在这个 App 里

        - 右上角 🔨 构建：等价于 `idf.py build`
        - 右上角 ⚡ 烧录：等价于 `idf.py -p <串口> flash`
        - 串口面板：等价于 `idf.py monitor`

        ## 在电脑上

        ```sh
        . export.sh
        idf.py set-target esp32
        idf.py build flash monitor
        ```
    """.trimIndent() + "\n"

    // ------------------------------------------------------------- 模板源码

    private val blinkMain = """
        #include <stdio.h>
        #include "freertos/FreeRTOS.h"
        #include "freertos/task.h"
        #include "driver/gpio.h"
        #include "esp_chip_info.h"
        #include "esp_log.h"

        static const char *TAG = "blink";

        /* 常见开发板板载 LED：ESP32 DevKitC 是 GPIO2，ESP32-S3-DevKitC 是 GPIO48 */
        #if CONFIG_IDF_TARGET_ESP32S3
        #define BLINK_GPIO 48
        #else
        #define BLINK_GPIO 2
        #endif

        void app_main(void)
        {
            esp_chip_info_t info;
            esp_chip_info(&info);
            ESP_LOGI(TAG, "chip=%s cores=%d rev=v%d.%d", CONFIG_IDF_TARGET,
                     info.cores, info.revision / 100, info.revision % 100);
            ESP_LOGI(TAG, "blink on GPIO%d", BLINK_GPIO);

            gpio_reset_pin(BLINK_GPIO);
            gpio_set_direction(BLINK_GPIO, GPIO_MODE_OUTPUT);

            int level = 0;
            for (int i = 0; ; i++) {
                level = !level;
                gpio_set_level(BLINK_GPIO, level);
                ESP_LOGI(TAG, "tick %d level=%d", i, level);
                vTaskDelay(pdMS_TO_TICKS(500));
            }
        }
    """.trimIndent() + "\n"

    private val helloMain = """
        #include <stdio.h>
        #include <inttypes.h>
        #include "freertos/FreeRTOS.h"
        #include "freertos/task.h"
        #include "esp_chip_info.h"
        #include "esp_flash.h"
        #include "esp_system.h"
        #include "esp_log.h"

        static const char *TAG = "hello";

        void app_main(void)
        {
            printf("Hello from EspForge!\n");

            esp_chip_info_t chip;
            esp_chip_info(&chip);
            printf("target      : %s\n", CONFIG_IDF_TARGET);
            printf("cores       : %d\n", chip.cores);
            printf("silicon rev : v%d.%d\n", chip.revision / 100, chip.revision % 100);
            printf("idf version : %s\n", esp_get_idf_version());

            uint32_t flash_size = 0;
            if (esp_flash_get_size(NULL, &flash_size) == ESP_OK) {
                printf("flash       : %" PRIu32 " MB\n", flash_size / (1024 * 1024));
            }
            printf("free heap   : %" PRIu32 " bytes\n", esp_get_free_heap_size());

            int n = 0;
            while (true) {
                ESP_LOGI(TAG, "uptime %d s, free heap %" PRIu32, n++, esp_get_free_heap_size());
                vTaskDelay(pdMS_TO_TICKS(1000));
            }
        }
    """.trimIndent() + "\n"

    private val wifiMain = """
        #include <string.h>
        #include "freertos/FreeRTOS.h"
        #include "freertos/task.h"
        #include "freertos/event_groups.h"
        #include "esp_wifi.h"
        #include "esp_event.h"
        #include "esp_log.h"
        #include "esp_netif.h"
        #include "nvs_flash.h"

        static const char *TAG = "wifi";

        /* 改成你自己的热点 */
        #define WIFI_SSID     "your-ssid"
        #define WIFI_PASSWORD "your-password"

        static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
        {
            if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
                esp_wifi_connect();
            } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
                ESP_LOGW(TAG, "disconnected, retrying…");
                vTaskDelay(pdMS_TO_TICKS(2000));
                esp_wifi_connect();
            } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
                ip_event_got_ip_t *e = (ip_event_got_ip_t *)data;
                ESP_LOGI(TAG, "got ip: " IPSTR, IP2STR(&e->ip_info.ip));
            }
        }

        static void wifi_task(void *arg)
        {
            ESP_ERROR_CHECK(nvs_flash_init());
            ESP_ERROR_CHECK(esp_netif_init());
            ESP_ERROR_CHECK(esp_event_loop_create_default());
            esp_netif_create_default_wifi_sta();

            wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
            ESP_ERROR_CHECK(esp_wifi_init(&cfg));

            ESP_ERROR_CHECK(esp_event_handler_instance_register(
                WIFI_EVENT, ESP_EVENT_ANY_ID, on_wifi_event, NULL, NULL));
            ESP_ERROR_CHECK(esp_event_handler_instance_register(
                IP_EVENT, IP_EVENT_STA_GOT_IP, on_wifi_event, NULL, NULL));

            wifi_config_t wc = { 0 };
            strncpy((char *)wc.sta.ssid, WIFI_SSID, sizeof(wc.sta.ssid) - 1);
            strncpy((char *)wc.sta.password, WIFI_PASSWORD, sizeof(wc.sta.password) - 1);
            wc.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;

            ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
            ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
            ESP_ERROR_CHECK(esp_wifi_start());

            ESP_LOGI(TAG, "connecting to \"%s\"…", WIFI_SSID);
            vTaskDelete(NULL);
        }

        void app_main(void)
        {
            xTaskCreate(wifi_task, "wifi", 4096, NULL, 5, NULL);
        }
    """.trimIndent() + "\n"

    private val emptyMain = """
        #include "esp_log.h"

        void app_main(void)
        {
            ESP_LOGI("app", "hello esp-idf");
        }
    """.trimIndent() + "\n"

    // ------------------------------------------------------------------ 应用

    fun apply(dir: File, template: String) {
        dir.mkdirs()
        val name = ident(dir.name)
        val t = if (template.isBlank()) HELLO else template

        File(dir, "CMakeLists.txt").writeText(topCMake(name))
        File(dir, "main").mkdirs()
        File(dir, "sdkconfig.defaults").writeText(sdkconfigDefaults())
        File(dir, ".gitignore").writeText(gitignore)
        File(dir, "README.md").writeText(readme(name, t))

        when (t) {
            BLINK -> {
                File(dir, "main/main.c").writeText(blinkMain)
                File(dir, "main/CMakeLists.txt").writeText(mainCMake(listOf("main.c")))
            }
            WIFI -> {
                File(dir, "main/main.c").writeText(wifiMain)
                File(dir, "main/CMakeLists.txt").writeText(mainCMake(listOf("main.c")))
            }
            EMPTY -> {
                File(dir, "main/main.c").writeText(emptyMain)
                File(dir, "main/CMakeLists.txt").writeText(mainCMake(listOf("main.c")))
            }
            else -> {
                File(dir, "main/main.c").writeText(helloMain)
                File(dir, "main/CMakeLists.txt").writeText(mainCMake(listOf("main.c")))
            }
        }
    }
}
