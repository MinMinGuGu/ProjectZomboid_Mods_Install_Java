package com.gugu.project_zomboid.utils;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The type Console progress bar helper.
 *
 * @author minmin
 * @date 2022 /02/02
 */
@Setter
@Getter
public class ConsoleProgressBarHelper {
    private static final String DEFAULT_TEMPLATE = "%s: %s%% [%s]";
    private final String title;
    private volatile Integer currCount;
    private final Float maxCount;
    private final Long refresh;
    private final Integer progressBarLength;
    private final ExecutorService executorService;

    /**
     * Instantiates a new Console progress bar helper.
     */
    public ConsoleProgressBarHelper() {
        this("已完成");
    }

    /**
     * Instantiates a new Console progress bar helper.
     *
     * @param title the title
     */
    public ConsoleProgressBarHelper(String title) {
        this(title, 0, 100.0F);
    }

    /**
     * Instantiates a new Console progress bar helper.
     *
     * @param title     the title
     * @param currCount the curr count
     * @param maxCount  the max count
     */
    public ConsoleProgressBarHelper(String title, Integer currCount, Float maxCount) {
        this(title, currCount, maxCount, 10L, 50);
    }

    /**
     * Instantiates a new Console progress bar helper.
     *
     * @param title             the title
     * @param currCount         the curr count
     * @param maxCount          the max count
     * @param refresh           the refresh
     * @param progressBarLength the progress bar length
     */
    public ConsoleProgressBarHelper(String title, Integer currCount, Float maxCount, Long refresh, Integer progressBarLength) {
        this.title = title;
        this.currCount = currCount;
        this.maxCount = maxCount;
        this.refresh = refresh;
        this.executorService = Executors.newSingleThreadExecutor();
        this.progressBarLength = progressBarLength;
    }

    /**
     * Start.
     */
    public void start() {
        executorService.execute(() -> {
            while (true){
                System.out.print(getPrintStr());
                try {
                    TimeUnit.MICROSECONDS.sleep(refresh);
                } catch (InterruptedException e) {
                    System.out.print("\r");
                    System.out.print(getPrintStr());
                    System.out.print(System.lineSeparator());
                    return;
                }
                System.out.print("\r");
            }
        });
    }

    /**
     * Stop.
     */
    public void stop(){
        executorService.shutdownNow();
        while (!executorService.isTerminated()){
            Thread.yield();
        }
    }

    private String getPrintStr() {
        int percentage = getPercentage();
        String progressBarStr = getProgressBarStr(percentage);
        return String.format(DEFAULT_TEMPLATE, title, percentage, progressBarStr);
    }

    private int getPercentage() {
        return (int) ((currCount / maxCount) * 100);
    }

    private String getProgressBarStr(int percentage) {
        StringBuilder result = new StringBuilder();
        int printCount = (int)((percentage / 100.0) * progressBarLength);
        for (int i = 0; i < printCount; i++) {
            result.append("█");
        }
        for (int i = 0; i < progressBarLength - printCount; i++) {
            result.append("-");
        }
        return result.toString();
    }

}
