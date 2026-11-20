#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <ctype.h>
#ifdef _MSC_VER
#pragma execution_character_set("utf-8")//设置字符集为utf-8（仅 MSVC）
#endif
// MinGW 下请使用：gcc -O2 -s -o start.exe start.c

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

// 在程序目录下查找可用的 JDK：已知的 Dragonwell 目录名，以及 jdk*/dragonwell*/java* 目录
int find_local_java(char *out, size_t out_size) {
    const char *known = "dragonwell-21.0.5.0.5+9-GA\\bin\\java.exe";
    if (GetFileAttributes(known) != INVALID_FILE_ATTRIBUTES) {
        snprintf(out, out_size, "%s", known);
        return 1;
    }

    // FindFirstFile 的目录部分不支持通配符，先枚举目录，再拼 bin\java.exe 检查
    const char *patterns[] = {"jdk*", "dragonwell*", "java*"};
    for (int i = 0; i < 3; i++) {
        WIN32_FIND_DATAA data;
        HANDLE handle = FindFirstFileA(patterns[i], &data);
        if (handle == INVALID_HANDLE_VALUE) {
            continue;
        }
        do {
            if ((data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0
                || strcmp(data.cFileName, ".") == 0 || strcmp(data.cFileName, "..") == 0) {
                continue;
            }
            char candidate[512];
            snprintf(candidate, sizeof(candidate), "%s\\bin\\java.exe", data.cFileName);
            if (GetFileAttributes(candidate) != INVALID_FILE_ATTRIBUTES) {
                snprintf(out, out_size, "%s", candidate);
                FindClose(handle);
                return 1;
            }
        } while (FindNextFileA(handle, &data));
        FindClose(handle);
    }
    return 0;
}

int main() {
    // 设置控制台代码页为 UTF-8，确保 C 输出的中文不乱码
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    // 关闭标准输出缓冲，保证重定向/管道下提示语与 Java 输出的顺序正确
    setvbuf(stdout, NULL, _IONBF, 0);

    int use_system_java = 0;//用于判断是否使用系统java
    char java_cmd[600];     //java 启动命令（系统 java 或同目录 JDK 的完整路径）
    char local_java[512];

    // 检查 Java 环境：优先系统 Java，其次程序目录下已有的 JDK；不再自动下载
    printf("正在检查系统Java环境...\n");
    if (system("java -version > nul 2>&1") == 0) {
        use_system_java = 1;
        snprintf(java_cmd, sizeof(java_cmd), "java");
        printf("检测到系统Java，将使用系统Java执行。\n");
    } else {
        printf("未检测到系统Java，正在检查程序目录下的JDK...\n");
        if (find_local_java(local_java, sizeof(local_java))) {
            snprintf(java_cmd, sizeof(java_cmd), "\"%s\"", local_java);
            printf("检测到程序目录下的JDK：%s\n", local_java);
        } else {
            printf("\n未找到可用的 Java 运行环境。\n");
            printf("HybridFileXfer 需要 Java 17 或更高版本（推荐 Java 21）。\n\n");
            printf("请任选一种方式处理后重新运行 start.exe：\n");
            printf("  1. 安装 JDK 21：https://adoptium.net/temurin/releases/?os=windows&arch=x64&package=jdk\n");
            printf("     安装时勾选“Add to PATH”，然后在新的命令行窗口执行 java -version 确认；\n");
            printf("  2. 或下载 JDK 压缩包，解压后把整个 JDK 目录放到 start.exe 同目录，\n");
            printf("     目录名以 jdk 或 dragonwell 开头（例如 jdk-21.0.5），程序会自动识别。\n\n");
            printf("说明：为避免下载上百 MB 的运行时，程序不会自动下载 JDK。\n");
            printf("按任意键退出...\n");
            getchar();
            return 1;
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
    char target_args[512];

    printf("请输入 '1' 使用无线模式，直接回车将使用有线模式：");
    fgets(wireless_choice, sizeof(wireless_choice), stdin);
    wireless_choice[strcspn(wireless_choice, "\n")] = 0;

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
        snprintf(target_args, sizeof(target_args), "-c %s", wireless_ip_addr);
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
            snprintf(target_args, sizeof(target_args), "-c adb");
        } else {
            snprintf(target_args, sizeof(target_args), "-c adb -s %s", device_id);
        }
    }

    // 启动 java
    char command[1024];
    snprintf(command, sizeof(command),
             "%s -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar HybridFileXfer.jar %s",
             java_cmd, target_args);
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
