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
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import com.qcloud.cos.utils.CRC64;

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
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The type Main.
 *
 * @author minmin
 * @date 2022 /01/31
 */
public class ModsInstallMain {

    private static final String LOCAL_MOD_PATH = Paths.get(System.getProperties().getProperty("user.home"), "Zomboid", "mods").toString();

    private static final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);

    private static final String KEY_FILE_NAME = "key.properties";

    private static String secretId = "";

    private static String secretKey = "";

    private static String bucketName = "";

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
        updateLocalMod(cosClient, serverFileList);
        endRun();
    }

    private static void endRun() throws IOException {
        System.out.println("检查Mod更新完成...");
        System.out.print("按回车键退出程序...");
        System.in.read();
        System.exit(0);
    }

    private static void readData(String[] args) {
        if (args != null && args.length == 3) {
            secretId = args[0];
            secretKey = args[1];
            bucketName = args[2];
            return;
        }
        if (!secretId.isEmpty() && !secretKey.isEmpty() && !bucketName.isEmpty()) {
            return;
        }
        if (!readLocalFile()) {
            throw new RuntimeException("请在Java启动时 传递三个参数对应 secretId secretKey bucketName" + System.lineSeparator() + "或者" + System.lineSeparator() + "在jar下新建key.properties文件配置三个值");
        }
    }

    private static boolean readLocalFile() {

        Path keyPath = Paths.get(System.getProperty("user.dir"), KEY_FILE_NAME);
        if (Files.exists(keyPath)) {
            try (InputStreamReader inputStreamReader = new InputStreamReader(Files.newInputStream(keyPath.toFile().toPath()), StandardCharsets.UTF_8)) {
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

    private static String getCalculateCrc64(File localFile) throws IOException {
        CRC64 crc64 = new CRC64();
        try (FileInputStream stream = new FileInputStream(localFile)) {
            byte[] b = new byte[1024 * 1024];
            while (true) {
                final int read = stream.read(b);
                if (read <= 0) {
                    break;
                }
                crc64.update(b, read);
            }
        }
        return Long.toUnsignedString(crc64.getValue());
    }


    private static void updateLocalMod(COSClient cosClient, List<String> updateModList) {
        long ignoreCount = 0;
        List<Future<?>> futureList = new LinkedList<>();
        for (String modName : updateModList) {
            Path filePath = Paths.get(LOCAL_MOD_PATH, modName);
            if (Files.isDirectory(filePath)) {
                try {
                    Files.createDirectories(filePath);
                    continue;
                } catch (IOException e) {
                    System.err.println("创建本地文件出现错误");
                    throw new RuntimeException(e);
                }
            }
            if (isIgnore(filePath)) {
                ignoreCount++;
                continue;
            }
            Future<?> future = executorService.submit(() -> {
                if (Files.exists(filePath)) {
                    ObjectMetadata objectMetadata = cosClient.getObjectMetadata(bucketName, modName);
                    String serverCrc64Ecma = objectMetadata.getCrc64Ecma();
                    try {
                        String localCalculateCrc64 = getCalculateCrc64(filePath.toFile());
                        if (serverCrc64Ecma.equals(localCalculateCrc64)) {
                            return;
                        }
                    } catch (IOException ignore) {
                    }
                }
                GetObjectRequest getObjectRequest = new GetObjectRequest(bucketName, modName);
                cosClient.getObject(getObjectRequest, filePath.toFile());
            });
            futureList.add(future);
        }
        if (ignoreCount != 0) {
            System.out.println("已忽略非正常mod该有的" + ignoreCount + "个文件");
        }
        if (!futureList.isEmpty()) {
            ConsoleProgressBarHelper consoleProgressBarHelper = new ConsoleProgressBarHelper("更新进度", 0L, (float) futureList.size());
            consoleProgressBarHelper.start();
            for (int i = 0; i < futureList.size(); i++) {
                try {
                    futureList.get(i).get();
                    consoleProgressBarHelper.setCurrCount((long) i);
                } catch (InterruptedException | ExecutionException e) {
                    System.err.println("下载文件时出现异常");
                    throw new RuntimeException(e);
                }
            }
            consoleProgressBarHelper.stop();
            executorService.shutdown();
            while (!executorService.isTerminated()) {
                Thread.yield();
            }
        }
    }

    private static boolean isIgnore(Path filePath) {
        String fileName = filePath.getFileName().toString();
        if (fileName.contains("/")) {
            fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
        }
        return fileName.startsWith(".");
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
}
