import com.webcrawler.tool.core.Crawler;

import java.io.File;

/** 电脑上测试爬虫核心：java CrawlerCli <网址> <输出目录> [maxFiles] */
public class CrawlerCli {
    public static void main(String[] args) throws Exception {
        String url = Crawler.prepareStartUrl(args[0]);
        File out = new File(args[1]);
        Crawler.Config cfg = new Crawler.Config();
        if (args.length > 2) cfg.maxFiles = Integer.parseInt(args[2]);
        cfg.requestTimeoutSeconds = 10;
        cfg.startUrlGuessed = Crawler.isSchemeGuessed(args[0]);
        Crawler c = new Crawler(url, new File(out, "crawl"), new File(out, "zips"), new File(out, "tmp"), cfg, null);
        Crawler.Result r = c.run();
        System.out.println("success=" + r.success + " error=" + r.error + " zip=" + r.zipName + " size=" + r.zipSize
                + " downloaded=" + r.downloadCount + " visited=" + r.visitedCount + " failed=" + r.failCount
                + " stop=" + r.stopReason + " ms=" + r.elapsedMs);
        System.out.println(r.stats);
        System.out.println(r.fileList);
        for (String f : r.failed) System.out.println("FAIL " + f);
    }
}
