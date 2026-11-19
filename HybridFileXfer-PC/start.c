#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <urlmon.h>
#include <ctype.h>
#ifdef _MSC_VER
#pragma comment(lib, "urlmon.lib")
#pragma execution_character_set("utf-8")//设置字符集为utf-8（仅 MSVC）
#endif
// MinGW 下请使用：gcc -O2 -o start.exe start.c -lurlmon

// 验证 IP 地址是否合法
int is_valid_ip(const char *ip_addr) {
    int num, dots = 0;
    const char *ptr = ip_addr;

    if (ip_addr == NULL) return 0;

    while (*ptr) {
        if (*ptr == '.') {
            dots++;
        } else if (!isdigit(*ptr)) {
            return 0;
        }
        ptr++;
    }

    if (dots != 3) return 0;

    char temp[256];
    strcpy(temp, ip_addr);
    char *token = strtok(temp, ".");

    while (token) {
        num = atoi(token);
        if (num < 0 || num > 255) {
            return 0;
        }
        token = strtok(NULL, ".");
    }

    return 1;
}

// 设备 ID 只允许字母、数字与 - _ . :，避免拼进命令行后破坏参数
int is_valid_device_id(const char *device_id) {
    const char *ptr = device_id;
    if (device_id == NULL || *device_id == '\0') {
        return 0;
    }
    while (*ptr) {
        unsigned char c = (unsigned char) *ptr;
        if (!(isalnum(c) || c == '-' || c == '_' || c == '.' || c == ':')) {
            return 0;
        }
        ptr++;
    }
    return 1;
}

int main() {
    // 设置控制台代码页为 UTF-8，确保 C 输出的中文不乱码
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    // 关闭标准输出缓冲，保证重定向/管道下提示语与 Java 输出的顺序正确
    setvbuf(stdout, NULL, _IONBF, 0);

    // JDK 文件的目录和名称
    char jdk_directory[] = "dragonwell-21.0.5.0.5+9-GA";
    char jdk_zip_file[] = "dragonwell_jdk.zip";
    char jdk_url[] = "https://dragonwell.oss-cn-shanghai.aliyuncs.com/21.0.5.0.5%2B9/Alibaba_Dragonwell_Extended_21.0.5.0.5.9_x64_windows.zip";
    char temp_directory[] = "temp_jdk_extract";
    
    int use_system_java = 0;//用于判断是否使用系统java

    // 检查 Java 环境
    printf("正在检查系统Java环境...\n");
    if (system("java -version > nul 2>&1") == 0) {
        use_system_java = 1;
        printf("检测到系统Java，将使用系统Java执行。\n");
    } else {
        printf("未检测到系统Java，正在检查内置JDK...\n");
        
        if (GetFileAttributes(jdk_directory) == INVALID_FILE_ATTRIBUTES) {
            printf("JDK 文件不存在，正在下载...\n");
            // 下载 JDK 压缩文件
            HRESULT hr = URLDownloadToFile(NULL, jdk_url, jdk_zip_file, 0, NULL);
            if (SUCCEEDED(hr)) {
                printf("JDK 文件下载完成：%s\n", jdk_zip_file);

                // 解压缩 JDK 文件到一个临时目录
                printf("正在解压 JDK 文件...\n");
                char unzip_command[512];
                snprintf(unzip_command, sizeof(unzip_command), "powershell -Command \"Expand-Archive -Path %s -DestinationPath %s\"", jdk_zip_file, temp_directory);
                int result = system(unzip_command);

                if (result == 0) {
                    printf("JDK 文件解压缩完成。\n");
                    
                    // 将解压后的目录重命名为目标目录
                    printf("正在移动 JDK 文件到正确位置...\n");
                    snprintf(unzip_command, sizeof(unzip_command), "move %s\\dragonwell-21.0.5.0.5+9-GA %s", temp_directory, jdk_directory);
                    result = system(unzip_command);

                    if (result != 0) {
                        printf("移动 JDK 文件失败，请检查系统权限和文件结构。\n");
                        printf("按任意键退出...\n");
                        getchar();
                        return 1;
                    }

                    // 删除临时解压目录
                    snprintf(unzip_command, sizeof(unzip_command), "rmdir /s /q %s", temp_directory);
                    result = system(unzip_command);
                    if (result != 0) {
                        printf("删除临时目录失败，请手动清理目录：%s\n", temp_directory);
                    }

                    // 删除压缩文件
                    remove(jdk_zip_file);
                } else {
                    printf("解压缩失败，请检查系统是否安装了 PowerShell 或 tar 工具。\n");
                    printf("按任意键退出...\n");
                    getchar();
                    return 1;
                }
            } else {
                //错误处理
                printf("下载失败，错误代码: 0x%08X\n请检查网络连接或下载链接是否正确。\n", (unsigned int) hr);
                printf("下载链接: %s\n", jdk_url);
                printf("目标文件: %s\n", jdk_zip_file);
				printf("可手动下载文件并将其放置在“dragonwell-21.0.5.0.5+9-GA”目录。\n");
                printf("按任意键退出...\n");
                getchar();
                return 1;
            }
        } else {
            printf("内置JDK已存在。\n");
        }
    }

    
    // jar 与 start.exe 必须在同一工作目录，否则下面的 java -jar 会直接失败
    if (GetFileAttributes("HybridFileXfer.jar") == INVALID_FILE_ATTRIBUTES) {
        printf("未找到 HybridFileXfer.jar。\n");
        printf("请确认 start.exe 与 HybridFileXfer.jar 位于同一目录，并在该目录下运行 start.exe。\n");
        printf("按任意键退出...\n");
        getchar();
        return 1;
    }

    printf("============================\n");
    printf("列出已连接设备：\n");
    system("adb devices");
    printf("============================\n");

    // 设置无线或有线模式的相关逻辑
    char wireless_ip_addr[256] = "";
    char device_id[256] = "none";
    char wireless_choice[10];

    printf("请输入 '1' 使用无线模式，直接回车将使用有线模式：");
    fgets(wireless_choice, sizeof(wireless_choice), stdin);
    wireless_choice[strcspn(wireless_choice, "\n")] = 0;

    char command[512];

    //构建Java命令
    if (strcmp(wireless_choice, "1") == 0) {
        printf("请输入无线设备的 IP 地址：");
        fgets(wireless_ip_addr, sizeof(wireless_ip_addr), stdin);
        wireless_ip_addr[strcspn(wireless_ip_addr, "\n")] = 0;

        if (!is_valid_ip(wireless_ip_addr)) {
            printf("无效的IP地址,请检查格式\n");
            printf("按任意键退出...\n");
            getchar();
            return 1;
        }

        if (use_system_java) {
            snprintf(command, sizeof(command), 
                "java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar -c %s", 
                wireless_ip_addr);
        } else {
            snprintf(command, sizeof(command), 
                ".\\%s\\bin\\java.exe -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar -c %s", 
                jdk_directory, wireless_ip_addr);
        }
    } else {
        printf("请输入设备 ID (如果留空，默认使用 -c adb 模式)：");
        fgets(device_id, sizeof(device_id), stdin);
        device_id[strcspn(device_id, "\n")] = 0;

        if (strlen(device_id) != 0 && strcmp(device_id, "none") != 0
            && !is_valid_device_id(device_id)) {
            printf("设备 ID 包含非法字符，只允许字母、数字与 - _ . :\n");
            printf("按任意键退出...\n");
            getchar();
            return 1;
        }

        if (strlen(device_id) == 0 || strcmp(device_id, "none") == 0) {
            if (use_system_java) {
                snprintf(command, sizeof(command), 
                    "java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar -c adb");
            } else {
                snprintf(command, sizeof(command), 
                    ".\\%s\\bin\\java.exe -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar -c adb", 
                    jdk_directory);
            }
        } else {
            if (use_system_java) {
                snprintf(command, sizeof(command), 
                    "java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar -c adb -s %s", 
                    device_id);
            } else {
                snprintf(command, sizeof(command), 
                    ".\\%s\\bin\\java.exe -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar -c adb -s %s", 
                    jdk_directory, device_id);
            }
        }
    }
    //启动java
    int result = system(command);

    if (result != 0) {
        printf("启动失败（退出码 %d）。请依次检查：\n", result);
        printf("1. Java 是否可用（命令行执行 java -version 验证）；\n");
        printf("2. HybridFileXfer.jar 是否与 start.exe 在同一目录；\n");
        printf("3. 手机端是否已启动服务、adb 设备是否已授权。\n");
        printf("按任意键退出...\n");
        getchar();
        return 1;
    }

    printf("操作完成。\n");
    printf("按任意键退出...\n");
    getchar();
    return 0;
}