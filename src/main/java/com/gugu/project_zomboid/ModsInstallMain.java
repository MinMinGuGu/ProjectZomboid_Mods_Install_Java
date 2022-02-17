package com.gugu.project_zomboid;

import com.gugu.project_zomboid.utils.ConsoleProgressBarHelper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.region.Region;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * The type Main.
 *
 * @author minmin
 * @date 2022 /01/31
 */
public class ModsInstallMain {

    private static final String LOCAL_MOD_PATH = Paths.get(System.getProperties().getProperty("user.home"), "Zomboid", "mods").toString();

    private static final String KEY_FILE_NAME = "key.properties";

    private static String secretId;

    private static String secretKey;

    private static String bucketName;

    /**
     * The entry point of application.
     *
     * @param args the input arguments
     * @throws IOException the io exception
     */
    public static void main(String[] args) throws IOException {
        readData(args);
        System.out.println("正在连接服务器...");
        COSClient cosClient = getCosClient();
        System.out.println("服务器连接成功...正在检查Mod更新...");
        List<String> serverFileList = getServerFileList(cosClient);
        List<String> localFileList = getLocalFileList();
        List<String> updateFileList = getUpdateModList(serverFileList, localFileList);
        if (updateFileList.isEmpty()) {
            System.out.println("检查Mod更新完成...");
            System.out.print("按任意键退出程序...");
            System.in.read();
            return;
        }
        System.out.println("预计更新" + updateFileList.size() + "个文件");
        updateLocalMod(cosClient, updateFileList);
        System.out.println("检查Mod更新完成...");
        System.out.print("按任意键退出程序...");
        System.in.read();
    }

    private static void readData(String[] args) {
        if (args != null && args.length == 3) {
            secretId = args[0];
            secretKey = args[1];
            bucketName = args[2];
            return;
        }
        if (!readLocalFile()) {
            throw new RuntimeException("请在Java启动时 传递三个参数对应 secretId secretKey bucketName" + System.lineSeparator() + "或者" + System.lineSeparator() + "在jar下新建key.properties文件配置三个值");
        }
    }

    private static boolean readLocalFile() {

        Path keyPath = Paths.get(System.getProperty("user.dir"), KEY_FILE_NAME);
        if (Files.exists(keyPath)) {
            try(InputStreamReader inputStreamReader = new InputStreamReader(new FileInputStream(keyPath.toFile()), StandardCharsets.UTF_8)){
                Properties properties = new Properties();
                properties.load(inputStreamReader);
                secretId = properties.getProperty("secretId");
                secretKey = properties.getProperty("secretKey");
                bucketName = properties.getProperty("bucketName");
                return secretId != null && secretKey != null && bucketName != null;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return false;
    }

    private static void updateLocalMod(COSClient cosClient, List<String> updateModList) {
        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);
        long ignoreCount = 0;
        List<Future<?>> futureList = new LinkedList<>();
        for (String modName : updateModList) {
            Path filePath = Paths.get(LOCAL_MOD_PATH, modName);
            if (isDir(modName)) {
                if (Files.notExists(filePath)) {
                    try {
                        Files.createDirectories(filePath);
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.err.println("创建本地文件出现错误");
                        throw new RuntimeException(e);
                    }
                }
                ignoreCount++;
                continue;
            }
            if (isIgnore(modName)) {
                ignoreCount++;
                continue;
            }
            Future<?> future = executorService.submit(() -> {
                GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, modName);
                cosClient.getObject(getObjectRequest, filePath.toFile());
            });
            futureList.add(future);
        }
        if (ignoreCount != 0) {
            System.out.println("已忽略不重要的" + ignoreCount + "个文件");
        }
        if (!futureList.isEmpty()) {
            ConsoleProgressBarHelper consoleProgressBarHelper = new ConsoleProgressBarHelper("下载进度", 0, (float) futureList.size());
            consoleProgressBarHelper.start();
            for (int i = 0; i < futureList.size(); i++) {
                try {
                    futureList.get(i).get();
                    consoleProgressBarHelper.setCurrCount(i + 1);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                    System.err.println("下载文件时出现异常");
                    throw new RuntimeException(e);
                }
            }
            executorService.shutdown();
            while (!executorService.isTerminated()) {
                Thread.yield();
            }
            consoleProgressBarHelper.stop();
        }
    }

    private static boolean isIgnore(String fileName) {
        String pass = fileName;
        if (pass.contains("/")) {
            pass = pass.substring(pass.lastIndexOf("/") + 1);
        }
        return pass.startsWith(".");
    }

    private static boolean isDir(String name) {
        String pass = name;
        if (pass.contains("/")) {
            pass = pass.substring(pass.lastIndexOf("/") + 1);
        }
        return !pass.contains(".");
    }

    private static List<String> getServerFileList(COSClient cosClient) {
        List<String> serverFileList = new LinkedList<>();
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest();
        listObjectsRequest.setBucketName(bucketName);
        listObjectsRequest.setMaxKeys(1000);
        ObjectListing objectListing;
        do {
            try {
                objectListing = cosClient.listObjects(listObjectsRequest);
            } catch (CosClientException e) {
                e.printStackTrace();
                System.err.println("获取服务器Mod时发送错误");
                throw new RuntimeException(e);
            }
            List<COSObjectSummary> cosObjectSummaries = objectListing.getObjectSummaries();
            for (COSObjectSummary cosObjectSummary : cosObjectSummaries) {
                String key = cosObjectSummary.getKey();
                serverFileList.add(key);
            }
            String nextMarker = objectListing.getNextMarker();
            listObjectsRequest.setMarker(nextMarker);
        } while (objectListing.isTruncated());
        return serverFileList;
    }

    private static COSClient getCosClient() {
        COSCredentials cosCredentials = new BasicCOSCredentials(secretId, secretKey);
        Region region = new Region("ap-guangzhou");
        ClientConfig clientConfig = new ClientConfig(region);
        clientConfig.setHttpProtocol(HttpProtocol.https);
        return new COSClient(cosCredentials, clientConfig);
    }

    private static List<String> getUpdateModList(List<String> serverFileList, List<String> localModList) {
        List<String> result = new LinkedList<>();
        for (String fileName : serverFileList) {
            if (localModList.contains(fileName)) {
                continue;
            }
            result.add(fileName);
        }
        return result;
    }

    /**
     * Gets local file list.
     *
     * @return the local file list
     */
    static List<String> getLocalFileList() {
        File modsFile = new File(LOCAL_MOD_PATH);
        if (!modsFile.exists()) {
            System.err.println("无法找到目录 " + modsFile.getPath());
            throw new RuntimeException("请至少运行过一次游戏，再使用在线更新");
        }
        List<String> result = new LinkedList<>();
        searchLocalFileList(LOCAL_MOD_PATH, result);
        return result.stream().filter(name -> !"".equals(name)).map(item -> item.replace("\\", "/")).collect(Collectors.toList());
    }

    private static void searchLocalFileList(String pathStr, List<String> result) {
        Path path = Paths.get(pathStr);
        if (Files.isDirectory(path)) {
            for (File file : Objects.requireNonNull(path.toFile().listFiles())) {
                if (Files.isDirectory(file.toPath())) {
                    searchLocalFileList(file.getPath() + File.separator, result);
                } else {
                    searchLocalFileList(file.getPath(), result);
                }
            }
        }
        result.add(pathStr.replace(LOCAL_MOD_PATH + File.separator, ""));
    }
}
