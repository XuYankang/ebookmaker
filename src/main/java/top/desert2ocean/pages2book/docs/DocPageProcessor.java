package top.desert2ocean.pages2book.docs;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.util.StringUtils;
import top.desert2ocean.pages2book.core.config.ImageUrl;
import top.desert2ocean.pages2book.core.config.UrlSection;
import top.desert2ocean.pages2book.core.utils.ResourceUtils;
import top.desert2ocean.pages2book.core.utils.UrlUtils;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.processor.PageProcessor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DocPageProcessor implements PageProcessor {
    public DocPageProcessor(DocsConfig docsConfig) {
        this.docsConfig = docsConfig;
    }

    private final DocsConfig docsConfig;

    private boolean catelogProcessed = false;

    private final Map<String, UrlSection> urlConfig = new ConcurrentHashMap<>();

    private final Site site = Site.me().setRetryTimes(3).setSleepTime(1).setTimeOut(10000);

    @Override
    public void process(Page page) {
        String url = page.getUrl().get();
        log.info("start to process {}", url);
        int urlDepth = UrlUtils.getUrlDepth(url, docsConfig.getBaseUrl());
        if (urlDepth > docsConfig.getDepth() || urlDepth < 0) {
            log.info("depth of {} is {}, skip.", urlDepth, url);
            page.setSkip(true);
            return;
        }
        urlConfig.computeIfPresent(url, (u, urlSection) -> {
            urlSection.setDepth(urlDepth);
            return urlSection;
        });
        urlConfig.putIfAbsent(url, UrlSection.builder().url(url).depth(urlDepth).build());
        Document document = page.getHtml().getDocument();
        processCatalog(page, document);

        processContent(page, url, document);
    }

    private void processContent(Page page, String url, Document document) {
        UrlSection urlSecction = urlConfig.get(page.getUrl().get());

        if (docsConfig.getContent() != null) {
            Elements contentElements = document.select(docsConfig.getContent().getCssQuery());
            if (contentElements.size() > 0) {
                Element content = contentElements.get(0);

                //处理图片
                Elements imgs = content.getElementsByTag("img");
                for (Element img : imgs) {
                    //处理不是data开头的
                    String src = img.attr("src");
                    if (!src.startsWith("data:")) {
                        //将其替换为data类型
                        ImageUrl imageUrl = ImageUrl.builder().base(docsConfig.getBaseUrl()).src(src).build();
                        try {
                            String newSrc = ResourceUtils.getImageStringFromRemote(imageUrl.getUrl().toString());
                            img.attr("src", newSrc);
                            log.info("download image {} and do src replace.", imageUrl);
                        } catch (Exception e) {
                            log.error("get image {} error.", imageUrl);
                        }
                    }
                }

                page.putField("title", urlConfig.get(url).getTitle());
                page.putField("html", content.html());
                page.putField("serial", urlSecction.getSerial());
                log.info("extract {} content.", url);

            }
        }
    }

    private void processCatalog(Page page, Document document) {

        if (catelogProcessed) {
            return;
        }
        catelogProcessed = true;
//        //find catalog
//        String parentUrl = page.getUrl().get();
//        UrlSection parentUrlSection = urlConfig.get(parentUrl);

        int number = 0;

        if (docsConfig.getCatalog() != null) {
            Elements catalogElements = document.select(docsConfig.getCatalog().getCssQuery());
            if (catalogElements.size() > 0) {
                Element catalog = catalogElements.get(0);
                Elements allATag = catalog.getElementsByTag("a");
                for (Element element : allATag) {
                    String href = element.attr("href");
                    //只处理相对路径
                    if (!StringUtils.isEmpty(href) && !href.startsWith("http") && !href.startsWith("#")) {
                        try {
                            URL baseUrl = new URL(docsConfig.getBaseUrl());
                            URL targetUrl = new URL(baseUrl, href);
                            String finalUrl = targetUrl.toString();
                            String text = element.text();
                            int finalNumber = ++number;
                            urlConfig.computeIfPresent(finalUrl, (u, us) -> {
                                us.setTitle(text);
                                us.setNumber(finalNumber);
                                return us;
                            });
                            urlConfig.putIfAbsent(finalUrl, UrlSection.builder().url(finalUrl).title(text).number(finalNumber).build());
                            page.addTargetRequest(finalUrl);
                            log.info("add {} to crawler list.", finalUrl);
                        } catch (MalformedURLException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        log.info("catalog processed.");
    }


    @Override
    public Site getSite() {
        return site;
    }

}
