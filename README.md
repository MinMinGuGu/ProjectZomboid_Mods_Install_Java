# ProjectZomboid_Mods_Install_Java
基于腾讯云的对象存储编写的ProjectZomboid在线更新程序
## 运行
1. 使用启动参数
    ```Java
    java -jar ProjectZomboid_Mods_Install_Java-0.0.1.jar secretId secretKey bucketName
    ```
2. 同Jar路径下使用key.properties
    ```properties
    secretId=xxx
    secretKey=xxx
    bucketName=xxx
    ```
## 注意
腾讯云中的存储桶直接存放Mod文件夹  不要用mods作为根目录